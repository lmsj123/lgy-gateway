package com.example.lgygateway.limit;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.StampedLock;

public class TokenBucket {
    private final long capacity;
    private final double refillRatePerMs;
    private final StampedLock lock = new StampedLock();
    private final DoubleAdder availableTokens;
    private volatile long lastRefillTime;

    public TokenBucket(long capacity, long refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillRatePerMs = refillTokensPerSecond / 1000.0;
        this.availableTokens = new DoubleAdder();
        this.availableTokens.add(capacity);
        this.lastRefillTime = System.currentTimeMillis();
    }

    public boolean tryAcquire(int tokens) {
        refill();
        long stamp = lock.tryOptimisticRead();
        double current = availableTokens.sum();
        if (current < tokens) {
            return false;
        }
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                current = availableTokens.sum();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        if (current >= tokens) {
            availableTokens.add(-tokens);
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long lastTime = lastRefillTime;
        long timeDelta = now - lastTime;
        if (timeDelta <= 0) return;

        double newTokens = timeDelta * refillRatePerMs;
        long stamp = lock.writeLock();
        try {
            double current = availableTokens.sum() + newTokens;
            double actualNew = Math.min(capacity, current);
            availableTokens.reset();
            availableTokens.add(actualNew);
            lastRefillTime = now;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}