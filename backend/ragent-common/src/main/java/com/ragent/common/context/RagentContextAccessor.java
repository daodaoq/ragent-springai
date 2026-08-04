package com.ragent.common.context;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 {@link RagentContext} 注册为 Micrometer {@link ThreadLocalAccessor}，
 * 配合 reactor 的 {@code Hooks.enableAutomaticContextPropagation()} 在 Reactor 的
 * 订阅线程 / subscribeOn 调度线程之间自动透传（无需每个算子手动 contextWrite）。
 */
public final class RagentContextAccessor implements ThreadLocalAccessor<RagentContext> {

    @Override
    public Object key() {
        return RagentContext.KEY;
    }

    @Override
    public RagentContext getValue() {
        return RagentContext.current();
    }

    @Override
    public void setValue(RagentContext value) {
        RagentContext.set(value);
    }

    @Override
    public void setValue() {
        // 恢复为空上下文（快照还原到无上下文线程时清空）
        RagentContext.clear();
    }

    @Override
    public void reset() {
        RagentContext.clear();
    }
}
