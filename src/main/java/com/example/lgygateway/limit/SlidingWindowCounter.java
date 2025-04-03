package com.example.lgygateway.limit;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// 滑动窗口计数器（线程安全）
public class SlidingWindowCounter {
    // 窗口配置
    private final int windowSizeSec;    // 窗口总时长（秒）
    private final int sliceCount;       // 窗口分片数量
    private final long sliceIntervalMs; // 分片间隔（毫秒）

    // 时间槽数据结构
    private static class Slice {
        LongAdder counter = new LongAdder(); // 当前分片的计数器
        long timestamp;                         // 分片的起始时间戳

        Slice(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    // 环形缓冲区存储分片
    private final Slice[] slices;
    private volatile int currentIndex;  // 当前活跃分片索引
    private volatile long windowStartTime; // 窗口起始时间

    // 总请求数缓存（后台线程更新）
    private volatile long totalRequests = 0;
    // 改用分段锁（按分片索引取锁）
    private Lock[] segmentLocks;
    // 后台线程池
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public SlidingWindowCounter(int windowSizeSec, int sliceCount) {
        this.windowSizeSec = windowSizeSec;
        this.sliceCount = sliceCount;
        this.sliceIntervalMs = (windowSizeSec * 1000L) / sliceCount;

        // 初始化分片数组
        long now = System.currentTimeMillis();
        this.slices = new Slice[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            slices[i] = new Slice(now - (sliceCount - i) * sliceIntervalMs);
        }
        this.currentIndex = sliceCount - 1;
        this.windowStartTime = now - windowSizeSec * 1000L;
        segmentLocks = new Lock[sliceCount];
        Arrays.fill(segmentLocks, new ReentrantLock());
        // 启动后台任务
        startSliceRotator();
    }

    // ----------------- 核心方法 -----------------

    public boolean allowRequest(long maxRequests) {
        // 1. 快速检查缓存的总请求数
        long realTimeTotal = calculateRealTimeTotal();
        if (realTimeTotal >= maxRequests) {
            return false;
        }

        // 2. 原子递增当前分片计数器
        Slice currentSlice = getCurrentSlice();
        currentSlice.counter.increment();
        long newCount = currentSlice.counter.longValue();

        // 3. 二次检查（防止在两次更新间隙超限）
        if (totalRequests + newCount > maxRequests) {
            currentSlice.counter.decrement();
            return false;
        }
        return true;
    }

    // ----------------- 私有方法 -----------------

    private Slice getCurrentSlice() {
        long now = System.currentTimeMillis();
        int index = currentIndex;

        // 检查当前分片是否过期
        if (now - slices[index].timestamp >= sliceIntervalMs) {
            return rotateSlices(now);
        }
        return slices[index];
    }

    private Slice rotateSlices(long now) {
        int lockIndex = currentIndex % 16;
        segmentLocks[lockIndex].lock();
        try {
            // 双重检查锁模式
            int index = currentIndex;
            if (now < slices[currentIndex].timestamp) {
                // 处理时钟回拨
                windowStartTime = now - windowSizeSec * 1000L;
                return slices[currentIndex];
            }

            // 计算需要前进的分片数
            long timePassed = now - slices[index].timestamp;
            int steps = (int) (timePassed / sliceIntervalMs);

            // 重置过期分片
            for (int i = 1; i <= steps; i++) {
                int newIndex = (index + i) % sliceCount;
                slices[newIndex].counter.reset();
                slices[newIndex].timestamp = now + i * sliceIntervalMs;
            }

            // 更新当前索引和窗口起始时间
            currentIndex = (index + steps) % sliceCount;
            windowStartTime = now - windowSizeSec * 1000L;
            return slices[currentIndex];
        } finally {
            segmentLocks[lockIndex].unlock();
        }
    }

    // 后台任务1: 定期轮转分片
    private void startSliceRotator() {
        scheduler.scheduleAtFixedRate(() -> rotateSlices(System.currentTimeMillis()), sliceIntervalMs, sliceIntervalMs, TimeUnit.MILLISECONDS);
    }


    // ----------------- 资源清理 -----------------
    public void shutdown() {
        scheduler.shutdown();
    }

    // 实时计算总数（惰性更新）
    private long calculateRealTimeTotal() {
        long sum = 0;
        long windowStart = System.currentTimeMillis() - windowSizeSec * 1000L;
        for (Slice slice : slices) {
            if (slice.timestamp >= windowStart) {
                sum += slice.counter.longValue();
            }
        }
        return sum;
    }
}