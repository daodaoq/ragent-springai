package com.ragent.web.service.impl;

import com.ragent.ai.config.RateLimitProperties;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.ChatService;
import com.ragent.ai.service.QueryPipeline;
import com.ragent.ai.service.RagService;
import com.ragent.ai.service.ratelimit.QueueEvent;
import com.ragent.ai.service.ratelimit.RedisRateLimiter;
import com.ragent.web.service.AgentService;
import com.ragent.web.service.UnifiedChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 统一对话服务实现：意图自动路由（前端不再分三个对话框）+ Redis 分布式队列限流削峰。
 * 三种引擎共享同一 conversationId 的多轮记忆（各引擎自行读写 Redis）。
 * <p>
 * 先跑一次查询处理管线（gateByIntent=false，路由由本服务负责），按意图分发：
 * <ul>
 *   <li>AGENT → AgentService（工具循环，SSE: tool-call + content）</li>
 *   <li>RAG → RagService（检索，SSE: rewritten + sources + content，复用已算好的管线产物）</li>
 *   <li>CHAT/OTHER → ChatService（纯对话，SSE: content）</li>
 * </ul>
 * 每条回答前置一个 mode 事件（rag/chat/agent）供前端展示引擎徽标。
 * <p>
 * 限流：进入分发前先 {@link RedisRateLimiter#acquire(String)}，未满直接放行；
 * 已满则入公平队列（ZSET FCFS），SSE 实时回传 {@code queue-position} 位次，被推进放行后切到真实引擎，
 * 排队超时/队列满则回传 {@code rate-limited} 事件。
 */
@Service
@RequiredArgsConstructor
public class UnifiedChatServiceImpl implements UnifiedChatService {

    private final QueryPipeline queryPipeline;
    private final RagService ragService;
    private final ChatService chatService;
    private final AgentService agentService;
    private final ChatMemoryService chatMemoryService;
    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProps;

    @Override
    public Flux<ServerSentEvent<String>> stream(String question, String conversationId, Long userId) {
        String q = question == null ? "" : question;
        if (!rateLimitProps.isEnabled()) {
            return engine(q, conversationId, userId);
        }
        String baseKey = rateLimitProps.getQueueKey();
        RedisRateLimiter.Attempt attempt = rateLimiter.acquire(baseKey);
        return switch (attempt.result()) {
            case 1 -> engine(q, conversationId, userId)
                    .doFinally(sig -> rateLimiter.release(attempt.reqId(), baseKey));
            case -1 -> rejected(rateLimiter.reason(attempt.reqId()));
            default -> queued(attempt, q, conversationId, userId);
        };
    }

    /** 排队中的 SSE 流：先报初始位次，收到 admitted 后切到真实引擎；超时/队列满被拒则 rate-limited。 */
    private Flux<ServerSentEvent<String>> queued(RedisRateLimiter.Attempt attempt, String q,
                                                 String conversationId, Long userId) {
        String baseKey = rateLimitProps.getQueueKey();
        String reqId = attempt.reqId();
        Flux<ServerSentEvent<String>> status = rateLimiter.queueEvents(reqId, baseKey)
                .concatMap(ev -> {
                    if (QueueEvent.ADMITTED.equals(ev.type())) {
                        return engine(q, conversationId, userId).subscribeOn(Schedulers.boundedElastic());
                    }
                    if (QueueEvent.REJECTED.equals(ev.type())) {
                        return rejected(ev.reason());
                    }
                    return Flux.just(sse("queue-position", Long.toString(ev.position())));
                });
        return Flux.concat(
                        Flux.just(sse("queue-position", Long.toString(attempt.position()))),
                        status)
                .doFinally(sig -> rateLimiter.release(reqId, baseKey));
    }

    /** 被限流拒绝：先 rate-limited 事件（前端可展示原因），再补一条 content 兜底文案。 */
    private static Flux<ServerSentEvent<String>> rejected(String reason) {
        return Flux.just(
                sse("rate-limited", "{\"reason\":\"" + reason + "\"}"),
                sse("content", "\n\n⚠️ 系统繁忙，请求被限流拒绝，请稍后再试。"));
    }

    /** 意图路由引擎（无排队语义，纯分发）。 */
    private Flux<ServerSentEvent<String>> engine(String q, String conversationId, Long userId) {
        List<ChatMemoryService.ChatMessage> history = chatMemoryService.load(conversationId);
        QueryPipeline.ProcessedQuery pq = queryPipeline.run(q, history, false);
        String intent = pq.intent() == null ? "RAG" : pq.intent().toUpperCase();
        return switch (intent) {
            case "AGENT" -> Flux.concat(
                    Flux.just(mode("agent")),
                    agentService.agentStream(q, conversationId));
            case "CHAT", "OTHER" -> Flux.concat(
                    Flux.just(mode("chat")),
                    chatService.stream(q, conversationId).map(c -> sse("content", c)));
            default -> Flux.concat( // RAG 或未知意图：兜底走知识库检索
                    Flux.just(mode("rag")),
                    ragService.ragStream(q, conversationId, userId, pq));
        };
    }

    private static ServerSentEvent<String> mode(String m) {
        return ServerSentEvent.<String>builder().event("mode").data("\"" + m + "\"").build();
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
