package com.ragent.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 结构化日志工具类 —— 封装 MDC，方便任何代码快速接入 ELK 日志体系。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 方式1：快速打带上下文的日志
 * LogHelper.info("UserService", "用户 {} 登录成功", username);
 *
 * // 方式2：顺序设置 MDC 后复用（MDC 是线程局部，用后务必 removeContext 清理）
 * LogHelper.setUserId(userId);
 * LogHelper.setModule("auth");
 * log.info("登录成功");  // MDC 自动注入 JSON
 * LogHelper.removeContext("userId", "module");
 *
 * // 方式3：用 try-with-resources 自动清理（推荐，只清理本上下文设置的键）
 * try (var ctx = LogHelper.context("userId", userId, "action", "delete")) {
 *     log.info("开始删除...");
 *     // ... 业务逻辑 ...
 * }
 *
 * // 方式4：一键注入 Controller 通用上下文（在拦截器/过滤器中调用）
 * LogHelper.attachRequest(userId, action, module);
 * }</pre>
 */
public final class LogHelper {

    private LogHelper() {}

    // ===== 快捷日志 =====

    public static void info(String loggerName, String format, Object... args) {
        LoggerFactory.getLogger(loggerName).info(format, args);
    }

    public static void warn(String loggerName, String format, Object... args) {
        LoggerFactory.getLogger(loggerName).warn(format, args);
    }

    public static void error(String loggerName, String format, Object... args) {
        LoggerFactory.getLogger(loggerName).error(format, args);
    }

    public static void debug(String loggerName, String format, Object... args) {
        LoggerFactory.getLogger(loggerName).debug(format, args);
    }

    // ===== MDC 上下文（null 值直接忽略，避免 MDC.put 抛 IllegalArgumentException）=====

    /** 设置单个 MDC 键值 */
    public static void mdc(String key, String value) {
        put(key, value);
    }

    /** 设置用户 ID（会出现在 ES 日志的 userId 字段） */
    public static void setUserId(String userId) {
        put("userId", userId);
    }

    /** 设置操作类型（login / upload / delete / ask 等） */
    public static void setAction(String action) {
        put("action", action);
    }

    /** 设置模块名（controller / service / auth / rag 等） */
    public static void setModule(String module) {
        put("module", module);
    }

    /** 设置链路追踪 ID */
    public static void setTraceId(String traceId) {
        put("traceId", traceId);
    }

    /** 设置客户端 IP */
    public static void setIp(String ip) {
        put("ip", ip);
    }

    /** 批量设置 MDC 请求上下文 */
    public static void attachRequest(String userId, String action, String module) {
        setUserId(userId);
        setAction(action);
        setModule(module);
    }

    /** 移除指定 MDC 键（仅移除传入的键，不影响其他上下文） */
    public static void removeContext(String... keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    /** 清空所有 MDC */
    public static void clearMdc() {
        MDC.clear();
    }

    // ===== try-with-resources 支持 =====

    /**
     * 创建 MDC 上下文，在 try-with-resources 中自动清理<b>本次设置的键</b>（不影响外层上下文）。
     *
     * <pre>{@code
     * try (var ignored = LogHelper.context("userId", "123", "action", "upload")) {
     *     log.info("上传开始");
     *     fileService.upload(...);
     * }
     * }</pre>
     */
    public static MdcContext context(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues 必须成对提供");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            put(keyValues[i], keyValues[i + 1]);
            keys.add(keyValues[i]);
        }
        return new MdcContext(keys);
    }

    private static void put(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        MDC.put(key, value);
    }

    /**
     * MDC 上下文，实现 AutoCloseable，在 try-with-resources 结束时清理本次设置的键。
     */
    public static class MdcContext implements AutoCloseable {

        private final Set<String> keys;

        MdcContext(Set<String> keys) {
            this.keys = keys;
        }

        @Override
        public void close() {
            for (String key : keys) {
                MDC.remove(key);
            }
        }
    }
}
