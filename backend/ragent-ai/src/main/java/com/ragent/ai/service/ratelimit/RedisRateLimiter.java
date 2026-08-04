package com.ragent.ai.service.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.config.RateLimitProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 分布式队列限流器（进程内自研，无微服务 / 无 Redisson）：
 * <ul>
 *   <li><b>ZSET</b>：排队请求按入队时间戳排序（FCFS 公平排队），score = 入队 ms。</li>
 *   <li><b>Lua</b>：入队/出队/推进全部原子化，避免并发下 inFlight 计数错乱。</li>
 *   <li><b>Pub/Sub</b>：推进线程把 admitted / rejected 事件广播到 {@link RateLimitProperties#getNotifyChannel()}，
 *       排队中的 SSE 请求订阅后即时唤醒，无需等待轮询。</li>
 *   <li>SSE 侧另用 {@link #queueEvents} 轮询 ZRANK 反馈「第 N 位」，同时以 Redis hash 状态为兜底
 *       （Pub/Sub 偶发丢消息也能在 1s 内推进）。</li>
 * </ul>
 * 语义：admitted=直接放行；queued=进入公平队列并反馈位次，等待/超时后 admitted 或 rejected；
 * rejected=队列已满或排队超时。Redis 异常时<b>失败开放</b>（直接放行），保证问答可用性。
 */
@Slf4j
@Service
@EnableScheduling
public class RedisRateLimiter {

    /** 结果语义：1=admitted，0=queued（position 为当前位次），-1=rejected（队列满） */
    public record Attempt(String reqId, int result, int position) {
    }

    // ---------------- Lua 脚本 ----------------

    /** acquire：inFlight 未满直接放行；满则入 ZSET 排队；队列满则拒绝。 */
    private static final String ACQUIRE_LUA = """
            local inflightKey = KEYS[1]
            local queueKey = KEYS[2]
            local reqKey = KEYS[3]
            local reqId = ARGV[1]
            local now = tonumber(ARGV[2])
            local capacity = tonumber(ARGV[3])
            local queueCapacity = tonumber(ARGV[4])
            local waitMs = tonumber(ARGV[5])
            local ttl = tonumber(ARGV[6])

            local inflight = tonumber(redis.call('INCR', inflightKey))
            if inflight <= capacity then
                redis.call('HMSET', reqKey, 'status', 'admitted', 'key', queueKey, 'ts', now, 'expire_at', now + waitMs)
                redis.call('EXPIRE', reqKey, ttl)
                return 1
            end
            redis.call('DECR', inflightKey)
            if redis.call('ZCARD', queueKey) >= queueCapacity then
                redis.call('HMSET', reqKey, 'status', 'rejected', 'key', queueKey, 'ts', now, 'reason', 'queue_full')
                redis.call('EXPIRE', reqKey, ttl)
                return -1
            end
            redis.call('ZADD', queueKey, now, reqId)
            redis.call('HMSET', reqKey, 'status', 'queued', 'key', queueKey, 'ts', now, 'expire_at', now + waitMs)
            redis.call('EXPIRE', reqKey, ttl)
            return 0
            """;

    /** advance：inFlight 有空位时按时间序补推队头；过期成员标记 rejected 并广播。 */
    private static final String ADVANCE_LUA = """
            local inflightKey = KEYS[1]
            local queueKey = KEYS[2]
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local waitMs = tonumber(ARGV[3])
            local channel = ARGV[4]

            local inflight = tonumber(redis.call('GET', inflightKey) or '0')
            while inflight < capacity do
                local head = redis.call('ZRANGE', queueKey, 0, 0, 'WITHSCORES')
                if #head == 0 then break end
                local reqId = head[1]
                local ts = tonumber(head[2])
                redis.call('ZREM', queueKey, reqId)
                local reqKey = 'rlim:req:' .. reqId
                if ts + waitMs < now then
                    redis.call('HMSET', reqKey, 'status', 'rejected', 'reason', 'timeout')
                    redis.call('EXPIRE', reqKey, 60)
                    redis.call('PUBLISH', channel, cjson.encode({reqId = reqId, type = 'rejected', reason = 'timeout'}))
                else
                    redis.call('INCR', inflightKey)
                    inflight = inflight + 1
                    redis.call('HMSET', reqKey, 'status', 'admitted')
                    redis.call('EXPIRE', reqKey, 60)
                    redis.call('PUBLISH', channel, cjson.encode({reqId = reqId, type = 'admitted'}))
                end
            end
            return inflight
            """;

    /** releaseAndAdvance：释放一个 inFlight 名额并立即补推队头（避免空等扫描周期）。 */
    private static final String RELEASE_ADVANCE_LUA = """
            local inflightKey = KEYS[1]
            local queueKey = KEYS[2]
            local reqKey = KEYS[3]
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local waitMs = tonumber(ARGV[3])
            local channel = ARGV[4]

            redis.call('DEL', reqKey)
            local inflight = tonumber(redis.call('DECR', inflightKey) or '0')
            if inflight < 0 then
                redis.call('SET', inflightKey, '0')
                inflight = 0
            end
            while inflight < capacity do
                local head = redis.call('ZRANGE', queueKey, 0, 0, 'WITHSCORES')
                if #head == 0 then break end
                local reqId = head[1]
                local ts = tonumber(head[2])
                redis.call('ZREM', queueKey, reqId)
                local k = 'rlim:req:' .. reqId
                if ts + waitMs < now then
                    redis.call('HMSET', k, 'status', 'rejected', 'reason', 'timeout')
                    redis.call('EXPIRE', k, 60)
                    redis.call('PUBLISH', channel, cjson.encode({reqId = reqId, type = 'rejected', reason = 'timeout'}))
                else
                    redis.call('INCR', inflightKey)
                    inflight = inflight + 1
                    redis.call('HMSET', k, 'status', 'admitted')
                    redis.call('EXPIRE', k, 60)
                    redis.call('PUBLISH', channel, cjson.encode({reqId = reqId, type = 'admitted'}))
                end
            end
            return inflight
            """;

    private final DefaultRedisScript<Long> acquireScript = new DefaultRedisScript<>(ACQUIRE_LUA, Long.class);
    private final DefaultRedisScript<Long> advanceScript = new DefaultRedisScript<>(ADVANCE_LUA, Long.class);
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_ADVANCE_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties props;
    private RedisMessageListenerContainer listenerContainer;
    /** 活跃排队请求的 pub/sub 事件汇聚器（reqId → sink），Admitted/Rejected 由全局监听器路由到对应 sink */
    private final ConcurrentHashMap<String, Sinks.Many<QueueEvent>> sinks = new ConcurrentHashMap<>();

    public RedisRateLimiter(StringRedisTemplate redis, ObjectMapper objectMapper, RateLimitProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @PostConstruct
    void startListener() throws Exception {
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(redis.getConnectionFactory());
        listenerContainer.addMessageListener((message, pattern) -> dispatch(message), new ChannelTopic(props.getNotifyChannel()));
        // 手动构造需先 afterPropertiesSet 创建订阅连接，再 start
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        log.info("Redis 队列限流已启动: key={}, capacity={}, queue={}, wait={}s",
                props.getQueueKey(), props.getCapacity(), props.getQueueCapacity(), props.getWaitTimeoutSeconds());
    }

    @PreDestroy
    void stopListener() {
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
    }

    // ---------------- 对外 API ----------------

    /** 尝试获取执行权：1=admitted，0=queued（附位次），-1=rejected（队列满）。Redis 异常时失败开放（admitted）。 */
    public Attempt acquire(String baseKey) {
        if (!props.isEnabled()) {
            return new Attempt("", 1, 0);
        }
        String reqId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try {
            Long res = redis.execute(acquireScript,
                    List.of(inflightKey(baseKey), queueKey(baseKey), reqKey(reqId)),
                    reqId, String.valueOf(now), String.valueOf(props.getCapacity()),
                    String.valueOf(props.getQueueCapacity()),
                    String.valueOf(props.getWaitTimeoutSeconds() * 1000L),
                    String.valueOf(props.getWaitTimeoutSeconds() + 60L));
            int r = res == null ? 1 : res.intValue();
            int pos = r == 0 ? (int) position(reqId, baseKey) : 0;
            if (log.isDebugEnabled()) {
                log.debug("限流 acquire reqId={} result={} pos={}", reqId, r, pos);
            }
            return new Attempt(reqId, r, pos);
        } catch (Exception e) {
            log.warn("限流 acquire 异常，失败开放放行: {}", e.getMessage());
            return new Attempt("", 1, 0);
        }
    }

    /** 当前位次（1 基；未在队列返回 0）。 */
    public long position(String reqId, String baseKey) {
        if (reqId == null || reqId.isEmpty()) {
            return 0;
        }
        try {
            Long rank = redis.opsForZSet().rank(queueKey(baseKey), reqId);
            return rank == null ? 0 : rank + 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 请求状态（admitted / queued / rejected / null）。 */
    public String status(String reqId) {
        try {
            Object v = redis.opsForHash().get(reqKey(reqId), "status");
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 请求拒绝原因（rejected 时有值）。 */
    public String reason(String reqId) {
        try {
            Object v = redis.opsForHash().get(reqKey(reqId), "reason");
            return v == null ? "unknown" : v.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 排队事件流：实时位次 + 终态（Admitted/Rejected）。
     * Pub/Sub 提供即时唤醒，1s 轮询 ZRANK 作为兜底并给出「第 N 位」更新。
     */
    public Flux<QueueEvent> queueEvents(String reqId, String baseKey) {
        Sinks.Many<QueueEvent> sink = Sinks.many().multicast().onBackpressureBuffer(16);
        sinks.put(reqId, sink);
        Flux<QueueEvent> pubsub = sink.asFlux();
        Flux<QueueEvent> poll = Flux.interval(Duration.ofSeconds(1))
                .mapNotNull(tick -> probe(reqId, baseKey))
                .takeUntil(ev -> QueueEvent.ADMITTED.equals(ev.type()) || QueueEvent.REJECTED.equals(ev.type()));
        return Flux.merge(pubsub, poll)
                .doFinally(sig -> {
                    sinks.remove(reqId);
                    sink.tryEmitComplete();
                });
    }

    /** 释放执行名额并补推队头（engine 结束后调用；幂等）。 */
    public void release(String reqId, String baseKey) {
        if (reqId == null || reqId.isEmpty() || !props.isEnabled()) {
            return;
        }
        try {
            redis.execute(releaseScript,
                    List.of(inflightKey(baseKey), queueKey(baseKey), reqKey(reqId)),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(props.getCapacity()),
                    String.valueOf(props.getWaitTimeoutSeconds() * 1000L),
                    props.getNotifyChannel());
        } catch (Exception e) {
            log.warn("限流 release 失败 reqId={}: {}", reqId, e.getMessage());
        }
    }

    /** 兜底推进（无需新 release 也补推队头、清理过期成员），默认每 3s 一次。 */
    @Scheduled(fixedDelayString = "${ragent.ratelimit.sweep-interval-ms:3000}")
    public void sweep() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            redis.execute(advanceScript,
                    List.of(inflightKey(props.getQueueKey()), queueKey(props.getQueueKey())),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(props.getCapacity()),
                    String.valueOf(props.getWaitTimeoutSeconds() * 1000L),
                    props.getNotifyChannel());
        } catch (Exception e) {
            log.warn("限流推进队列失败: {}", e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    private void dispatch(org.springframework.data.redis.connection.Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode node = objectMapper.readTree(body);
            String reqId = node.path("reqId").asText();
            if (reqId.isEmpty()) {
                return;
            }
            Sinks.Many<QueueEvent> sink = sinks.get(reqId);
            if (sink == null) {
                return; // 已取消订阅（SSE 断开等）
            }
            String type = node.path("type").asText();
            if ("admitted".equals(type)) {
                sink.tryEmitNext(QueueEvent.admitted());
            } else if ("rejected".equals(type)) {
                sink.tryEmitNext(QueueEvent.rejected(node.path("reason").asText("timeout")));
            }
        } catch (Exception e) {
            log.warn("解析限流通知失败: {}", body);
        }
    }

    /** 轮询探测：admitted/rejected 终态优先，否则返回当前位次。 */
    private QueueEvent probe(String reqId, String baseKey) {
        String st = status(reqId);
        if ("admitted".equals(st)) {
            return QueueEvent.admitted();
        }
        if ("rejected".equals(st)) {
            return QueueEvent.rejected(reason(reqId));
        }
        long p = position(reqId, baseKey);
        return p > 0 ? QueueEvent.position(p) : null;
    }

    private static String inflightKey(String base) {
        return "rlim:inflight:" + base;
    }

    private static String queueKey(String base) {
        return "rlim:queue:" + base;
    }

    private static String reqKey(String reqId) {
        return "rlim:req:" + reqId;
    }
}
