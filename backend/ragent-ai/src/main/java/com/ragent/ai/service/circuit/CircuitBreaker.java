package com.ragent.ai.service.circuit;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自研三态熔断器（CLOSED → OPEN → HALF_OPEN → CLOSED）。
 * <ul>
 *   <li><b>CLOSED</b>：正常放行；连续失败 {@code failureThreshold} 次转入 OPEN。</li>
 *   <li><b>OPEN</b>：拒绝放行；持续 {@code openDurationMs} 后自动转 HALF_OPEN。</li>
 *   <li><b>HALF_OPEN</b>：放行最多 {@code halfOpenMaxCalls} 个试调用，1 次成功即恢复 CLOSED，
 *       失败则回到 OPEN。</li>
 * </ul>
 * 线程安全：状态用 {@link AtomicReference}，计数用 {@link AtomicInteger}。
 */
@Slf4j
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long openDurationMs;
    private final int halfOpenMaxCalls;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger halfOpenTrial = new AtomicInteger();
    private volatile long openedAt;

    public CircuitBreaker(String name, int failureThreshold, long openDurationMs, int halfOpenMaxCalls) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
    }

    /** 是否允许本次调用通过。 */
    public boolean tryAcquire() {
        State s = state.get();
        if (s == State.CLOSED) {
            return true;
        }
        if (s == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= openDurationMs) {
                // 到期自动进入 HALF_OPEN
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenTrial.set(0);
                    log.info("熔断器 {} 进入 HALF_OPEN，放行试调用", name);
                }
                return halfOpenTrial.incrementAndGet() <= halfOpenMaxCalls;
            }
            return false;
        }
        // HALF_OPEN：只放行有限的试调用
        return halfOpenTrial.incrementAndGet() <= halfOpenMaxCalls;
    }

    /** 记录一次成功调用。 */
    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            consecutiveFailures.set(0);
            halfOpenTrial.set(0);
            log.info("熔断器 {} HALF_OPEN 试调用成功，恢复 CLOSED", name);
        } else {
            consecutiveFailures.set(0);
        }
    }

    /** 记录一次失败调用（可能触发状态迁移）。 */
    public void recordFailure() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.OPEN);
            openedAt = System.currentTimeMillis();
            halfOpenTrial.set(0);
            log.warn("熔断器 {} HALF_OPEN 试调用失败，回到 OPEN", name);
            return;
        }
        int f = consecutiveFailures.incrementAndGet();
        if (f >= failureThreshold) {
            state.set(State.OPEN);
            openedAt = System.currentTimeMillis();
            halfOpenTrial.set(0);
            log.warn("熔断器 {} 连续失败 {} 次，熔断打开（{}ms）", name, f, openDurationMs);
        }
    }

    public State state() {
        return state.get();
    }

    public String name() {
        return name;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long openedAt() {
        return openedAt;
    }
}
