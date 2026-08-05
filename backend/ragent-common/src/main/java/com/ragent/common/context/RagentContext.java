package com.ragent.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import org.slf4j.MDC;

import java.util.List;

/**
 * 请求级上下文（TransmittableThreadLocal）：traceId / userId / username / ip / module / action。
 * <ul>
 *   <li><b>跨线程透传</b>：TTL 让上下文在自定义线程池（见 {@link RagentThreadPools}）与 Reactor
 *       （配合 {@link RagentContextAccessor} 自动传播）之间透传，异步日志/操作不再丢失调用方身份。</li>
 *   <li><b>同步 MDC</b>：{@link #set} 时同步写入 SLF4J MDC，logback-spring.xml 白名单
 *       （userId/action/module/traceId/ip）自动带上，异步线程经 TTL + MDC 恢复后同样带上下文。</li>
 * </ul>
 */
public final class RagentContext {

    public static final String KEY = RagentContext.class.getName();
    public static final String TRACE_KEY = "traceId";
    public static final String USER_KEY = "userId";
    public static final String IP_KEY = "ip";
    public static final String MODULE_KEY = "module";
    public static final String ACTION_KEY = "action";

    private static final List<String> MDC_KEYS = List.of(USER_KEY, MODULE_KEY, ACTION_KEY, TRACE_KEY, IP_KEY);

    private static final TransmittableThreadLocal<RagentContext> HOLDER = new TransmittableThreadLocal<>();

    private final String traceId;
    private final Long userId;
    private final String username;
    private final String ip;
    private final String module;
    private final String action;

    private RagentContext(Builder b) {
        this.traceId = b.traceId;
        this.userId = b.userId;
        this.username = b.username;
        this.ip = b.ip;
        this.module = b.module;
        this.action = b.action;
    }

    /** 当前线程上下文（可能为 null）。 */
    public static RagentContext current() {
        return HOLDER.get();
    }

    /**
     * P8-3c：当前请求的用户作用域标识——登录用户返回 {@code u{userId}}，匿名返回 {@code anon}。
     * 用于给会话记忆、摘要等用户态数据的存储键加前缀，避免跨用户串话
     * （匿名用户之间仍共享同一作用域，无法区分匿名者；登录用户彼此及与匿名流量完全隔离）。
     */
    public static String userScope() {
        RagentContext ctx = current();
        Long uid = ctx == null ? null : ctx.userId();
        return uid == null ? "anon" : "u" + uid;
    }

    /** 设置上下文并同步 MDC；传 null 等价于 {@link #clear()}。 */
    public static void set(RagentContext ctx) {
        if (ctx == null) {
            clear();
            return;
        }
        HOLDER.set(ctx);
        ctx.syncMdc();
    }

    /** 清空上下文并清理本上下文对应的 MDC 键。 */
    public static void clear() {
        HOLDER.remove();
        for (String k : MDC_KEYS) {
            MDC.remove(k);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 把上下文字段写入 MDC（供 logback / ELK 使用）。 */
    private void syncMdc() {
        put(TRACE_KEY, traceId);
        put(USER_KEY, userId == null ? null : String.valueOf(userId));
        put(IP_KEY, ip);
        put(MODULE_KEY, module);
        put(ACTION_KEY, action);
    }

    private static void put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    public String traceId() {
        return traceId;
    }

    public Long userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public String ip() {
        return ip;
    }

    public String module() {
        return module;
    }

    public String action() {
        return action;
    }

    public static final class Builder {

        private String traceId;
        private Long userId;
        private String username;
        private String ip;
        private String module;
        private String action;

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public Builder module(String module) {
            this.module = module;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public RagentContext build() {
            return new RagentContext(this);
        }
    }
}
