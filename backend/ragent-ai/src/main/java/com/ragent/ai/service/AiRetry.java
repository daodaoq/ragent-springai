package com.ragent.ai.service;

import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * AI 调用容错（P5 补强）：DeepSeek 高峰期会偶发返回 503（"Service is too busy"）/429 限流，
 * 官方建议客户端重试。统一策略：瞬时错误(429/5xx)指数退避自动重试，重试耗尽后给出友好中文文案。
 */
public final class AiRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    /**
     * 流式调用自动重试。仅在「尚未产出任何内容」时重试，避免部分 token 已推送给前端后
     * 再整段重发导致的重复文本；重试耗尽或非瞬时错误时统一输出友好错误文案。
     *
     * @param attempt 每次重试都会重新执行的流式调用工厂（必须惰性，重试靠重新订阅触发新请求）
     */
    public static Flux<String> streamWithRetry(Supplier<Flux<String>> attempt) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(attempt)
                .doOnNext(s -> emitted.set(true))
                .retryWhen(Retry.backoff(MAX_ATTEMPTS, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter(e -> !emitted.get() && isTransient(e)))
                .onErrorResume(e -> Flux.just("\n\n⚠️ " + friendlyMessage(e)));
    }

    /** 同步调用自动重试（Agent 工具循环等；调用方应运行在非 Netty 事件循环线程上） */
    public static <T> T callWithRetry(CheckedSupplier<T> supplier) {
        int attempts = 0;
        long backoffMillis = INITIAL_BACKOFF.toMillis();
        while (true) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                if (!isTransient(e) || ++attempts >= MAX_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF.toMillis());
            }
        }
    }

    /** 是否为可重试的瞬时错误：429 限流、5xx 服务不可用 */
    public static boolean isTransient(Throwable e) {
        if (e instanceof WebClientResponseException w) {
            int status = w.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        if (e instanceof HttpStatusCodeException h) {
            int status = h.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }

    /** 将错误映射为友好中文文案 */
    public static String friendlyMessage(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("503") || msg.contains("Service Unavailable") || msg.contains("too busy")
                || msg.contains("overloaded") || msg.contains("service_unavailable")) {
            return "DeepSeek 服务繁忙（503），已自动重试仍失败。请稍后再试或换个时间。";
        }
        if (msg.contains("429") || msg.contains("Too Many Requests")) {
            return "请求过于频繁（429），已触发限流。请稍后再试。";
        }
        if (msg.contains("401") || msg.contains("invalid api key") || msg.contains("authentication")) {
            return "DeepSeek API Key 无效或未配置。请检查 application-local.yml。";
        }
        if (msg.contains("404") || msg.contains("model not found") || msg.contains("not exist")) {
            return "模型不存在或已被下架。请检查 application.yml 中的 model 配置。";
        }
        return "AI 服务出错：" + msg;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get();
    }

    private AiRetry() {
    }
}
