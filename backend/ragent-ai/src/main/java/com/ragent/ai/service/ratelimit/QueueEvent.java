package com.ragent.ai.service.ratelimit;

/**
 * 限流排队事件（RedisRateLimiter.queueEvents 的输出）。
 * type 取值：admitted（终态，可执行模型调用）/ rejected（终态，被拒，附 reason）/ position（实时位次，1 基）。
 */
public record QueueEvent(String type, long position, String reason) {

    public static final String ADMITTED = "admitted";
    public static final String REJECTED = "rejected";
    public static final String POSITION = "position";

    public static QueueEvent admitted() {
        return new QueueEvent(ADMITTED, 0, null);
    }

    public static QueueEvent rejected(String reason) {
        return new QueueEvent(REJECTED, 0, reason);
    }

    public static QueueEvent position(long pos) {
        return new QueueEvent(POSITION, pos, null);
    }
}
