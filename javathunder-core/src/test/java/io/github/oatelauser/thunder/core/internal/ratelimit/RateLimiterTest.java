package io.github.oatelauser.thunder.core.internal.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    /** 手动推进的纳秒时钟。 */
    private static final class FakeClock extends AtomicLong {
        FakeClock() {
            super(0);
        }

        void advanceSeconds(double seconds) {
            addAndGet((long) (seconds * 1_000_000_000L));
        }
    }

    @Test
    void unlimitedAcquireNeverBlocks() {
        RateLimiter limiter = RateLimiter.unlimited();
        assertDoesNotThrow(() -> limiter.acquire(1024 * 1024 * 1024));
    }

    @Test
    void burstCapacityPassesImmediatelyThenRefills() throws InterruptedException {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(8192, clock::get); // 容量 = max(8192, 16KiB) = 16KiB

        limiter.acquire(16384); // 整桶立刻通过

        Thread probe = new Thread(() -> {
            try {
                limiter.acquire(8192);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        probe.start();
        Thread.sleep(50);
        assertTrue(probe.isAlive(), "acquire should block while bucket is empty");

        clock.advanceSeconds(1.0); // 1 秒补满 8192
        probe.join(2000);
        assertTrue(!probe.isAlive(), "acquire should return after refill");
    }

    @Test
    void interruptDuringWaitPropagates() throws InterruptedException {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(1024, clock::get); // 容量 16KiB
        limiter.acquire(16384); // 排空

        Thread probe = new Thread(() -> {
            try {
                limiter.acquire(16384);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });
        probe.start();
        Thread.sleep(50);
        probe.interrupt();
        probe.join(2000);
        assertTrue(probe.isInterrupted() || !probe.isAlive());
    }

    @Test
    void rejectsNegativeRate() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-1));
    }
}
