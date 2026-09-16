package io.github.oatelauser.thunder.tracker;

import java.util.concurrent.atomic.AtomicLong;

/** 可观测计数器：按传输拆分的 announce 与 scrape 请求量。 */
final class TrackerMetrics {

    private final AtomicLong httpAnnounces = new AtomicLong();
    private final AtomicLong udpAnnounces = new AtomicLong();
    private final AtomicLong scrapes = new AtomicLong();

    void httpAnnounce() {
        httpAnnounces.incrementAndGet();
    }

    void udpAnnounce() {
        udpAnnounces.incrementAndGet();
    }

    void scrape() {
        scrapes.incrementAndGet();
    }

    long httpAnnounces() {
        return httpAnnounces.get();
    }

    long udpAnnounces() {
        return udpAnnounces.get();
    }

    long scrapes() {
        return scrapes.get();
    }
}
