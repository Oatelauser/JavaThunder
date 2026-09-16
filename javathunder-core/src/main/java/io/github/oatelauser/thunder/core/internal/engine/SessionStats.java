package io.github.oatelauser.thunder.core.internal.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话计数与速率：下载 / 上传累计字节（CAS 递增，任意线程安全）与周期采样的
 * EMA 速率（供进度快照与 tracker announce 上报）。tickRates 由 progressLoop
 * 周期调用；首次调用建立采样基线（等价于循环入口的 last* 初始化）。
 */
final class SessionStats {

    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong();
    private volatile long downloadRate;
    private volatile long uploadRate;
    private long lastDownloaded;
    private long lastUploaded;
    private long lastMillis;
    /**
     * 首拍只建立基线、不计算速率：断点恢复注入的历史计数发生在首拍之前，
     * 若计入窗口会折成虚假的瞬时速率尖峰（原实现在循环入口初始化 last* 基线，
     * 速率恒从 0 起步）。
     */
    private boolean sampled;

    void addDownloaded(long bytes) {
        downloaded.addAndGet(bytes);
    }

    void addUploaded(long bytes) {
        uploaded.addAndGet(bytes);
    }

    long downloaded() {
        return downloaded.get();
    }

    long uploaded() {
        return uploaded.get();
    }

    long downloadRate() {
        return downloadRate;
    }

    long uploadRate() {
        return uploadRate;
    }

    /**
     * 周期速率采样：窗口内字节增量换算为秒速率后做 EMA 平滑；首次调用仅建立
     * 基线（见 {@link #sampled}）。
     */
    void tickRates(long nowMillis) {
        long dt = Math.max(1, nowMillis - lastMillis);
        if (sampled) {
            // EMA 平滑（α=0.3）：瞬时抖动不至于让 ETA 上蹿下跳
            downloadRate = ema(downloadRate, (downloaded.get() - lastDownloaded) * 1000 / dt);
            uploadRate = ema(uploadRate, (uploaded.get() - lastUploaded) * 1000 / dt);
        }
        lastDownloaded = downloaded.get();
        lastUploaded = uploaded.get();
        lastMillis = nowMillis;
        sampled = true;
    }

    private static long ema(long previous, long instantaneous) {
        return (long) (0.3 * instantaneous + 0.7 * Math.max(0, previous));
    }
}
