package com.example.lgygateway.circuitBreaker;

import com.example.lgygateway.config.GrayCircuitBreakerConfig;
import com.example.lgygateway.config.NormalCircuitBreakerConfig;
import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.LongAdder;

public class CircuitBreaker {
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    // 状态管理使用版本号原子引用
    private final AtomicStampedReference<State> stateRef =
            new AtomicStampedReference<>(State.CLOSED, 0);
    private final CircuitBreakerStats stats = new CircuitBreakerStats();
    private final AtomicInteger halfOpenPermits = new AtomicInteger(0);
    private NormalCircuitBreakerConfig config = new NormalCircuitBreakerConfig();

    public CircuitBreaker(NormalCircuitBreakerConfig config) {
        this.config = config;
    }
    public CircuitBreaker(GrayCircuitBreakerConfig config) {
        this.config.setHalfOpenPermits(config.getHalfOpenPermits());
        this.config.setFailureThreshold(config.getFailureThreshold());
        this.config.setOpenTimeoutMs(config.getOpenTimeoutMs());
        this.config.setCounterResetThreshold(config.getCounterResetThreshold());
        this.config.setMinRequestThreshold(config.getMinRequestThreshold());
        this.config.setHalfOpenSuccessThreshold(config.getHalfOpenSuccessThreshold());
    }
    /**
     * 判断是否允许请求通过
     */
    public boolean allowRequest() {
        int[] stamp = new int[1];
        State currentState = stateRef.get(stamp);

        if (currentState == State.OPEN) {
            if (System.currentTimeMillis() - stats.getLastFailureTime() > config.getOpenTimeoutMs()) {
                // 使用CAS原子操作转换状态
                if (stateRef.compareAndSet(State.OPEN, State.HALF_OPEN, stamp[0], stamp[0] + 1)) {
                    halfOpenPermits.set(config.getHalfOpenPermits());
                }
                return true;
            }
            return false;
        }

        if (currentState == State.HALF_OPEN) {
            int current;
            do {
                current = halfOpenPermits.get();
                if (current <= 0) return false;
            } while (!halfOpenPermits.compareAndSet(current, current - 1));
            return true;
        }

        return true;
    }

    /**
     * 记录成功请求
     */
    public void recordSuccess() {
        State currentState = stateRef.getReference();
        if (currentState == State.HALF_OPEN) {
            stats.getHalfOpenSuccessCount().increment();
            if (stats.getHalfOpenSuccessCount().sum() >= config.getHalfOpenSuccessThreshold()) {
                // 仅在半开状态时才允许转换到关闭状态
                stateRef.compareAndSet(State.HALF_OPEN, State.CLOSED,
                        stateRef.getStamp(), stateRef.getStamp() + 1);
                stats.reset();
            }
        } else {
            stats.getClosedSuccessCount().increment();
            tryResetFailureCount();
        }
    }

    /**
     * 记录失败请求
     */
    public void recordFailure() {
        stats.getFailureCount().increment();
        stats.setLastFailureTime(System.currentTimeMillis());

        State currentState = stateRef.getReference();
        if (currentState == State.HALF_OPEN) {
            stateRef.set(State.OPEN, stateRef.getStamp() + 1);
        } else if (shouldTrip()) {
            stateRef.set(State.OPEN, stateRef.getStamp() + 1);
        }
    }

    /**
     * 自动重置闭合状态下的失败计数器
     */
    private void tryResetFailureCount() {
        long total = stats.getClosedSuccessCount().sum() + stats.getFailureCount().sum();
        if (total >= config.getCounterResetThreshold()) {
            synchronized (stats) {
                if (stats.getClosedSuccessCount().sum() + stats.getFailureCount().sum() >= config.getCounterResetThreshold()) {
                    stats.getClosedSuccessCount().reset();
                    stats.getFailureCount().reset();
                }
            }
        }
    }

    /**
     * 判断是否应该触发熔断
     */
    private boolean shouldTrip() {
        long success = stats.getClosedSuccessCount().sum();
        long failure = stats.getFailureCount().sum();
        long total = success + failure;

        if (total < config.getMinRequestThreshold()) {
            return false;
        }
        double failureRate = (failure * 100.0) / total;
        return failureRate >= config.getFailureThreshold();
    }

    /**
     * 状态统计内部类
     */
    @Data
    private static class CircuitBreakerStats {
        private final LongAdder closedSuccessCount = new LongAdder();
        private final LongAdder failureCount = new LongAdder();
        private final LongAdder halfOpenSuccessCount = new LongAdder();
        private volatile long lastFailureTime;
        public void reset() {
            closedSuccessCount.reset();
            failureCount.reset();
            halfOpenSuccessCount.reset();
            lastFailureTime = 0;
        }
    }
    /**
     * 监控数据获取方法
     */
    public CircuitBreakerMetrics getMetrics() {
        return new CircuitBreakerMetrics(
                stateRef.getReference(),
                stats.getClosedSuccessCount().sum(),
                stats.getFailureCount().sum(),
                stats.getHalfOpenSuccessCount().sum()
        );
    }

    @Data
    public static class CircuitBreakerMetrics {
        private final State state;
        private final long closedSuccess;
        private final long failures;
        private final long halfOpenSuccess;
    }
}