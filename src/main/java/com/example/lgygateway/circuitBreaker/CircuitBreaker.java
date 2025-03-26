package com.example.lgygateway.circuitBreaker;

import com.example.lgygateway.config.CircuitBreakerConfig;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CircuitBreaker {
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final CircuitBreakerStats stats = new CircuitBreakerStats();
    private final AtomicInteger halfOpenPermits = new AtomicInteger(0);
    private final CircuitBreakerConfig config;

    @Autowired
    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
    }

    /**
     * 判断是否允许请求通过
     */
    public boolean allowRequest() {
        if (state.get() == State.OPEN) {
            // 检查是否应该尝试恢复
            if (System.currentTimeMillis() - stats.getLastFailureTime() > config.getOpenTimeoutMs()) {
                // CAS操作确保只有一个线程执行状态转换
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenPermits.set(config.getHalfOpenPermits());
                }
            } else {
                return false;
            }
        }

        if (state.get() == State.HALF_OPEN) {
            // 半开状态下使用许可机制控制请求量
            int currentPermits = halfOpenPermits.get();
            return currentPermits > 0 && halfOpenPermits.compareAndSet(currentPermits, currentPermits - 1);
        }

        return true;
    }

    /**
     * 记录成功请求
     */
    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            // 半开状态下累计成功次数
            int successCount = stats.getHalfOpenSuccessCount().incrementAndGet();
            if (successCount >= config.getHalfOpenSuccessThreshold()) {
                // 成功阈值达到，关闭熔断器
                state.set(State.CLOSED);
                stats.reset();
            }
        } else {
            stats.getClosedSuccessCount().incrementAndGet();
            tryResetFailureCount();
        }
    }

    /**
     * 记录失败请求
     */
    public void recordFailure() {
        stats.getFailureCount().incrementAndGet();
        stats.setLastFailureTime(System.currentTimeMillis());

        if (state.get() == State.HALF_OPEN) {
            // 半开状态下遇到任何失败立即恢复熔断
            state.set(State.OPEN);
        } else if (shouldTrip()) {
            state.set(State.OPEN);
        }
    }

    /**
     * 自动重置闭合状态下的失败计数器
     */
    private void tryResetFailureCount() {
        int total = stats.getClosedSuccessCount().get() + stats.getFailureCount().get();
        if (total >= config.getCounterResetThreshold()) {
            stats.getClosedSuccessCount().set(0);
            stats.getFailureCount().set(0);
        }
    }

    /**
     * 判断是否应该触发熔断
     */
    private boolean shouldTrip() {
        int success = stats.getClosedSuccessCount().get();
        int failure = stats.getFailureCount().get();
        int total = success + failure;

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
        private final AtomicInteger closedSuccessCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
        private volatile long lastFailureTime = 0;
        public void reset() {
            closedSuccessCount.set(0);
            failureCount.set(0);
            halfOpenSuccessCount.set(0);
            lastFailureTime = 0;
        }
    }
}