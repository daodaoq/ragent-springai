package com.ragent.ai.service.circuit;

import com.ragent.common.context.RagentThreadPools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型路由装饰器：以 {@link ChatModel} 实现注入到 Spring AI 链路，所有 ChatClient 调用（对话/RAG 生成/
 * 意图改写/Agent 工具循环/评测裁判）自动经过它，业务代码零改动。
 * <ul>
 *   <li><b>优先级降级链</b>：按 {@link ModelEndpoint} 列表顺序尝试；某模型熔断（OPEN）或调用失败即切下一个。</li>
 *   <li><b>三态熔断</b>：每个端点独立 {@link CircuitBreaker}，连续失败打开、到期半开试调、成功即恢复。</li>
 *   <li><b>流式首包探测</b>：流式调用 {@code firstTokenTimeoutMs} 内无首个 token 判失败并降级，
 *       降低「挂死」带来的响应延迟（对应 <a href="https://www.anthropic.com/news/claude-fable-5-mythos-5">降延迟</a> 目标）。</li>
 * </ul>
 * 进程内调用（ragent-web → ragent-ai 模块接口），不引入 OpenFeign / 微服务。
 */
@Slf4j
public class ChatModelRouter implements ChatModel {

    private final List<ModelEndpoint> endpoints;
    private final long firstTokenTimeoutMs;
    private final long syncTimeoutMs;
    /** 当前生效模型名（降级链最新一次成功使用的端点），供状态观测 */
    private final AtomicReference<String> currentModel = new AtomicReference<>("primary");

    /**
     * P8-4b：同步 .call() 的兜底执行池。用有界池 + Future.get(timeout) 给阻塞调用加超时，
     * 模型挂死时不再无限占线程；池满（AbortPolicy）直接抛错进入候选降级，也不阻塞调用方。
     * TTL 透传保证调用方上下文（traceId/userId）带到模型调用线程。
     */
    private static final ExecutorService CALL_EXECUTOR = RagentThreadPools.newExecutor("model-sync-call",
            4, 8, 100, new ThreadPoolExecutor.AbortPolicy());

    public ChatModelRouter(List<ModelEndpoint> endpoints, long firstTokenTimeoutMs, long syncTimeoutMs) {
        this.endpoints = endpoints;
        this.firstTokenTimeoutMs = firstTokenTimeoutMs;
        this.syncTimeoutMs = syncTimeoutMs;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Throwable last = null;
        for (ModelEndpoint ep : endpoints) {
            if (!ep.breaker().tryAcquire()) {
                log.warn("模型 {} 熔断中，同步调用跳过，尝试下一个候选", ep.name());
                continue;
            }
            try {
                ChatResponse resp = CALL_EXECUTOR.submit(() -> ep.model().call(prompt))
                        .get(syncTimeoutMs, TimeUnit.MILLISECONDS);
                ep.breaker().recordSuccess();
                currentModel.set(ep.name());
                return resp;
            } catch (TimeoutException e) {
                last = new IllegalStateException("模型 " + ep.name() + " 同步调用超时(" + syncTimeoutMs + "ms)", e);
                ep.breaker().recordFailure();
                log.warn("{}: {}", last.getMessage(), e.getMessage());
            } catch (Exception e) {
                last = e;
                ep.breaker().recordFailure();
                log.warn("模型 {} 同步调用失败: {}", ep.name(), e.getMessage());
            }
        }
        throw new IllegalStateException("所有候选模型均不可用", last);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return tryStream(prompt, 0);
    }

    /** 优先级降级链（流式）：当前端点熔断/失败 → 递归尝试下一个。 */
    private Flux<ChatResponse> tryStream(Prompt prompt, int idx) {
        if (idx >= endpoints.size()) {
            return Flux.error(new IllegalStateException("所有候选模型均不可用"));
        }
        ModelEndpoint ep = endpoints.get(idx);
        if (!ep.breaker().tryAcquire()) {
            log.warn("模型 {} 熔断中，流式跳过，尝试下一个候选", ep.name());
            return tryStream(prompt, idx + 1);
        }
        String name = ep.name();
        // P8-4d：成功/失败记录只归属"本端点自己的流"。doOnComplete 放在 onErrorResume 之前，
        // 候选模型降级成功时不再把成功记到已失败的主端点（否则 HALF_OPEN 被误关回 CLOSED、
        // 连续失败计数被清零、currentModel 显示成已失败模型）。
        Flux<ChatResponse> attempt = ep.model().stream(prompt)
                // 首包探测：firstTokenTimeoutMs 内无首个 token → 首包超时器先触发 → error → onErrorResume 降级；
                // 后续 token 间隔不设限（模型思考/停顿不应触发降级）；空流完成也视为失败（switchIfEmpty）
                .timeout(Mono.delay(Duration.ofMillis(firstTokenTimeoutMs)), item -> Mono.never())
                .switchIfEmpty(Flux.error(new FirstTokenTimeoutException(name)))
                .doOnComplete(() -> {
                    ep.breaker().recordSuccess();
                    currentModel.set(name);
                });
        return attempt.onErrorResume(e -> {
            ep.breaker().recordFailure();
            log.warn("模型 {} 流式调用失败（{}）: {}，切换到候选模型", name,
                    e instanceof FirstTokenTimeoutException ? "首包超时" : "异常", e.getMessage());
            return tryStream(prompt, idx + 1);
        });
    }

    /** 当前降级链状态（端点 + 熔断器），供状态观测接口使用。 */
    public List<ModelEndpoint> endpoints() {
        return endpoints;
    }

    public String currentModel() {
        return currentModel.get();
    }
}
