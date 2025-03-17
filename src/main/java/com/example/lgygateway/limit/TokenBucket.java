package com.example.lgygateway.limit;

import java.util.concurrent.atomic.AtomicLong;

public class TokenBucket {
    private final long capacity; // 桶容量
    private final double refillRatePerMs; // 令牌填充速率（个/毫秒）
    private final AtomicLong lastRefillTime; // 最后填充时间
    private final AtomicLong availableTokens; // 当前可用令牌

    public TokenBucket(long capacity, long refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillRatePerMs = refillTokensPerSecond / 1000.0;
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        this.availableTokens = new AtomicLong(capacity);
    }

    public boolean tryAcquire(int tokens) {
        refill();
        long currentAvailable = availableTokens.get();
        while (currentAvailable >= tokens) {
            if (availableTokens.compareAndSet(currentAvailable, currentAvailable - tokens)) {
                return true;
            }
            currentAvailable = availableTokens.get();
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long lastTime = lastRefillTime.get();
        long timeDelta = now - lastTime;
        if (timeDelta <= 0) {
            return;
        }
        double newTokens = timeDelta * refillRatePerMs;
        long currentAvailable = availableTokens.get();
        long newTokenCount = Math.min(capacity, (long) (currentAvailable + newTokens));
        
        if (availableTokens.compareAndSet(currentAvailable, newTokenCount)) {
            lastRefillTime.compareAndSet(lastTime, now);
        }
    }
}