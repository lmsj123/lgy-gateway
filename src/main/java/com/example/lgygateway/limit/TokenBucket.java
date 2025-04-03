package com.example.lgygateway.limit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

public class TokenBucket {
    private final long capacity;
    private final double refillRatePerMs;
    private final StampedLock lock = new StampedLock();
    private final AtomicLong tokens;
    private volatile long lastRefillTime;

    public TokenBucket(long capacity, long refillPerSecond) {
        this.capacity = capacity;
        this.refillRatePerMs = refillPerSecond / 1000.0;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillTime = System.currentTimeMillis();
    }

    public boolean tryAcquire(int token) {
        long stamp = lock.tryOptimisticRead();
        long current = tokens.get();
        if (current < token && tryRefill()) {
            current = tokens.get();
        }
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { current = tokens.get(); }
            finally { lock.unlockRead(stamp); }
        }
        return tokens.compareAndSet(current, current - token);
    }

    private boolean tryRefill() {
        long now = System.currentTimeMillis();
        long lastTime = lastRefillTime;
        if (now <= lastTime) return false;

        long timeDelta = now - lastTime;
        double newTokens = timeDelta * refillRatePerMs;
        if (newTokens < 1) return false;

        long stamp = lock.writeLock();
        try {
            long actual = Math.min(capacity, tokens.get() + (long)newTokens);
            tokens.set(actual);
            lastRefillTime = now;
            return true;
        } finally { lock.unlockWrite(stamp); }
    }
}