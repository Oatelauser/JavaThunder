package io.github.oatelauser.thunder.core.internal.ratelimit;

import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 令牌桶限速器（DESIGN §5.10）：容量 = max(速率, {@value #MIN_BURST_BYTES}) 字节，
 * 连续平滑补充（非整秒突刺）。速率 0 表示不限速。
 *
 * <p>acquire 在拿不到令牌时阻塞等待——虚拟线程上代价为零（ADR-0001）。
 * 锁只覆盖令牌账目（微秒级），等待发生在锁外。
 */
public final class RateLimiter {

    public static final int MIN_BURST_BYTES = 16 * 1024;
    private static final long MAX_REFILL_GAP_NANOS = 64_000_000_000L; // 64s

    private final long capacity;
    private final LongSupplier clock;
    private final long rateBytesPerSecond;
    private final ReentrantLock lock = new ReentrantLock();

    private long availableTokens;
    private long lastRefillNanos;

    public RateLimiter(long bytesPerSecond) {
        this(bytesPerSecond, System::nanoTime);
    }

    RateLimiter(long bytesPerSecond, LongSupplier clock) {
        if (bytesPerSecond < 0) {
            throw new IllegalArgumentException("rate must be >= 0: " + bytesPerSecond);
        }
        this.rateBytesPerSecond = bytesPerSecond;
        this.capacity = bytesPerSecond == 0 ? Long.MAX_VALUE : Math.max(bytesPerSecond, MIN_BURST_BYTES);
        this.clock = clock;
        this.availableTokens = capacity;
        this.lastRefillNanos = clock.getAsLong();
    }

    public static RateLimiter unlimited() {
        return new RateLimiter(0, System::nanoTime);
    }

    public boolean isUnlimited() {
        return rateBytesPerSecond == 0;
    }

    /** 扣取 {@code bytes} 个令牌；不足则阻塞等待补充。可中断。 */
    public void acquire(int bytes) throws InterruptedException {
        if (isUnlimited()) {
            return;
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        while (true) {
            long waitNanos;
            lock.lock();
            try {
                refill();
                if (availableTokens >= bytes) {
                    availableTokens -= bytes;
                    return;
                }
                long deficit = bytes - availableTokens;
                // 纳秒数 = deficit / rate * 1e9，防溢出用 double 过渡
                waitNanos = (long) (deficit * 1_000_000_000.0 / rateBytesPerSecond);
            } finally {
                lock.unlock();
            }
            LockSupport.parkNanos(waitNanos);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private void refill() {
        long now = clock.getAsLong();
        long elapsed = Math.min(now - lastRefillNanos, MAX_REFILL_GAP_NANOS);
        if (elapsed <= 0) {
            return;
        }
        long wholeSeconds = elapsed / 1_000_000_000L;
        long remainderNanos = elapsed % 1_000_000_000L;
        long added = wholeSeconds * rateBytesPerSecond
            + remainderNanos * rateBytesPerSecond / 1_000_000_000L;
        availableTokens = Math.min(capacity, availableTokens + added);
        lastRefillNanos = now;
    }
}
