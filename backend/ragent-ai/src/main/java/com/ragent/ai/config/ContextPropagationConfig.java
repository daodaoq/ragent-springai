package com.ragent.ai.config;

import com.ragent.common.context.RagentContextAccessor;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Reactor 自动上下文传播：把 {@link com.ragent.common.context.RagentContext} 注册为
 * Micrometer {@code ThreadLocalAccessor}，并开启 {@link Hooks#enableAutomaticContextPropagation()}。
 * 效果：SSE 响应流经 subscribeOn(boundedElastic) 等调度线程时，traceId/userId 自动透传，
 * 异步日志（含 Agent 工具循环、查询日志落库、记忆摘要）不再丢失调用方上下文。
 */
@Configuration
public class ContextPropagationConfig {

    @PostConstruct
    void enable() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new RagentContextAccessor());
        Hooks.enableAutomaticContextPropagation();
    }
}
