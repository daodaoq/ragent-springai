package com.ragent.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ragent.ai.config.RateLimitProperties;
import com.ragent.ai.service.ratelimit.RedisRateLimiter;
import com.ragent.common.result.Result;
import com.ragent.web.service.AgentService;
import com.ragent.web.service.UnifiedChatService;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.ChatService;
import com.ragent.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AI 对话接口（SSE 流式）。
 * 普通对话 / RAG / Agent 三种模式；conversationId 缺省时向后兼容（多轮记忆退化为 default）。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatService chatService;
    private final RagService ragService;
    private final AgentService agentService;
    private final ChatMemoryService chatMemoryService;
    private final UnifiedChatService unifiedChatService;
    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProps;

    /** 统一对话流式（自动路由 RAG/Agent/普通对话）：先 mode 事件，再对应引擎事件。前端主入口。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        return unifiedChatService.stream(request.message() == null ? "" : request.message(),
                request.conversationId(), currentUserId(), request.kbId());
    }

    /** 普通对话流式（P5 起带多轮记忆；保留向后兼容） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message();
        return rateLimited(() -> "⚠️ 系统繁忙，请稍后再试。",
                () -> chatService.stream(message, request.conversationId()));
    }

    /** RAG 知识库问答流式（P6 起带多轮记忆 + 查询日志：先 rewritten 事件，再 sources，再 content） */
    @PostMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ragStream(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message();
        return rateLimited(() -> busySse(),
                () -> ragService.ragStream(message, request.conversationId(), currentUserId(), null, request.kbId()));
    }

    /** 当前登录用户 ID（RAG 端点公开可访问，未登录返回 null；仅用于查询日志归属） */
    private Long currentUserId() {
        try {
            Object id = StpUtil.getLoginIdDefaultNull();
            return id == null ? null : Long.valueOf(id.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Agent 智能体流式（工具调用事件 tool-call + 最终答案 content） */
    @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentStream(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message();
        return rateLimited(() -> busySse(),
                () -> agentService.agentStream(message, request.conversationId()));
    }

    /**
     * P8-3b：旧 SSE 端点（/ai/chat|rag|agent/stream）与 /ai/stream 共享同一全局限流队列，
     * 未获得执行权时直接回退"繁忙"提示（这些旧端点不做排队位次 UX，排队语义由统一入口承担）。
     * 修复"旧端点绕过 Redis 限流"的成本/DoS 敞口。
     */
    private <T> Flux<T> rateLimited(java.util.function.Supplier<T> fallback, java.util.function.Supplier<Flux<T>> engine) {
        if (!rateLimitProps.isEnabled()) {
            return engine.get();
        }
        String baseKey = rateLimitProps.getQueueKey();
        RedisRateLimiter.Attempt attempt = rateLimiter.acquire(baseKey);
        if (attempt.result() == 1) {
            // P8-4a：defer + subscribeOn 把引擎内部阻塞调用（LLM/检索）移出 Netty 事件循环
            return Flux.defer(engine).subscribeOn(Schedulers.boundedElastic())
                    .doFinally(sig -> rateLimiter.release(attempt.reqId(), baseKey));
        }
        return Flux.just(fallback.get());
    }

    private static ServerSentEvent<String> busySse() {
        return ServerSentEvent.<String>builder().event("content").data("⚠️ 系统繁忙，请稍后再试。").build();
    }

    /** 清空某会话的多轮记忆 */
    @PostMapping("/memory/clear")
    public Result<Void> clearMemory(@RequestBody ChatRequest request) {
        chatMemoryService.clear(request.conversationId());
        return Result.success();
    }

    /** kbId：P9 指定知识库检索（null = 全部可见库；仅 RAG 意图生效） */
    public record ChatRequest(String message, String conversationId, Long kbId) {
    }
}
