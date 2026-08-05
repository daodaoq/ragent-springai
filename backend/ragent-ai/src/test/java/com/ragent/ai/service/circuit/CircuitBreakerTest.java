package com.ragent.ai.service.circuit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8-8a：三态熔断器单元测试（CLOSED → OPEN → HALF_OPEN → CLOSED）。
 */
class CircuitBreakerTest {

    private CircuitBreaker breaker() {
        return new CircuitBreaker("test", 2, 100, 1);
    }

    @Test
    void closedAllowsCallsAndOpensAfterFailures() {
        CircuitBreaker cb = breaker();
        assertTrue(cb.tryAcquire());
        cb.recordFailure();
        assertTrue(cb.tryAcquire());
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.tryAcquire()); // OPEN 期间拒绝
    }

    @Test
    void openTransitionsToHalfOpenAfterDurationAndRecoversOnSuccess() throws InterruptedException {
        CircuitBreaker cb = breaker();
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        Thread.sleep(120); // 超过 openDurationMs=100
        assertTrue(cb.tryAcquire()); // 进入 HALF_OPEN 并放行试调用
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void halfOpenFailureGoesBackToOpen() throws InterruptedException {
        CircuitBreaker cb = breaker();
        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(120);
        cb.tryAcquire();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.tryAcquire());
    }

    @Test
    void successResetsConsecutiveFailures() {
        CircuitBreaker cb = breaker();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        // 连续失败被成功打断，不应到 threshold=2 就 OPEN
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }
}
