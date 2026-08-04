package com.ragent.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型调用分布式队列限流配置（application.yml 中 ragent.ratelimit.*）。
 * 机制：Redis ZSET（FCFS 公平排队）+ Lua（原子入队/出队/推进）+ Pub/Sub（admitted/rejected 通知）。
 * SSE 侧通过轮询 ZRANK 反馈实时排队位次，被推进出队时由 Pub/Sub 即时唤醒。
 */
@Data
@ConfigurationProperties(prefix = "ragent.ratelimit")
public class RateLimitProperties {

    /** 总开关（默认开：本地单用户容量足够时队列几乎不参与，但机制常驻） */
    private boolean enabled = true;

    /** 并发处理中的模型请求数上限，超过则进入队列 */
    private int capacity = 4;

    /** 队列最大长度，满则直接拒绝（rate-limited） */
    private int queueCapacity = 50;

    /** 在队列中的最大等待时间（秒），超时拒绝 */
    private int waitTimeoutSeconds = 30;

    /** 全局队列 key（限流对象：模型调用通道） */
    private String queueKey = "model:chat";

    /** Redis Pub/Sub 通知频道（admitted/rejected 事件） */
    private String notifyChannel = "rlim:notify";

    /** 扫描推进队列的间隔（毫秒，兜底推进：即使无新 release 也能补推 + 清理过期） */
    private long sweepIntervalMs = 3000;
}
