package io.github.oatelauser.thunder.core.internal.storage;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 断点持久化与重启采样策略：resume 文件的加载 / 保存 / 删除与脏标记降频刷盘
 * （原每件一写 → 脏标记 + ≥2s 窗口合并，写放大数量级下降）。恢复编排
 * （逐件重校验与位图落定）留在 session。uploaded/downloaded 计数经 supplier
 * 读取，避免对 engine 侧会话统计的反向依赖。
 */
public final class ResumeStore {

    private static final Logger log = LoggerFactory.getLogger(ResumeStore.class);
    private static final long FLUSH_INTERVAL_MILLIS = 2000;

    private final Path file;
    private final byte[] infoHash;
    private final int pieceCount;
    private final LongSupplier uploadedSupplier;
    private final LongSupplier downloadedSupplier;
    private final Random random = new Random();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private long lastFlushMillis = System.currentTimeMillis();

    public ResumeStore(Path file, TorrentMetadata meta, LongSupplier uploadedSupplier,
            LongSupplier downloadedSupplier) {
        this.file = file;
        this.infoHash = meta.infoHash().clone();
        this.pieceCount = meta.pieceCount();
        this.uploadedSupplier = uploadedSupplier;
        this.downloadedSupplier = downloadedSupplier;
    }

    /**
     * 加载断点状态；文件不可用（{@link ResumeException}：损坏 / 版本不符 / 不属于
     * 当前种子）返回 null——已记录日志，调用方从零开始。
     */
    @Nullable
    public ResumeState load() {
        try {
            return ResumeState.load(file, infoHash, pieceCount);
        } catch (ResumeException e) {
            log.info("resume state unusable, starting fresh: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SAMPLED 档的抽样子集：均匀随机 10% + 边界件（首/末）。
     */
    public Set<Integer> restartSample(Bitfield completed) {
        Set<Integer> sample = new HashSet<>();
        int count = pieceCount;
        if (count > 0 && completed.has(0)) {
            sample.add(0);
        }
        if (count > 1 && completed.has(count - 1)) {
            sample.add(count - 1);
        }
        // 上限取 min(已完成件数, 10%)：只完成少量件就恢复时（如 5/1000），
        // 若按总件数定 target，样本永远凑不齐 → 死循环
        int target = Math.max(1, Math.min(completed.cardinality(), count / 10));
        while (sample.size() < target) {
            int candidate = random.nextInt(count);
            if (completed.has(candidate)) {
                sample.add(candidate);
            }
        }
        return sample;
    }

    /** 标记断点已变更（件完成）；由 {@link #flushIfDue} 周期合并落盘。 */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * 降频刷盘：脏标记置位且距上次刷盘 ≥2s 才真正保存（先清脏标记再保存——保存
     * 期间的新变更留待下一窗口）。返回是否执行了刷盘。
     */
    public boolean flushIfDue(long nowMillis, Supplier<Bitfield> localSnapshot) {
        if (!dirty.get() || nowMillis - lastFlushMillis < FLUSH_INTERVAL_MILLIS) {
            return false;
        }
        dirty.set(false);
        saveNow(localSnapshot.get());
        lastFlushMillis = nowMillis;
        return true;
    }

    /** 立即保存；I/O 失败只告警不抛——断点文件丢失只损失续传粒度，不该失败任务。 */
    public void saveNow(Bitfield localSnapshot) {
        try {
            ResumeState.save(file, new ResumeState(infoHash, pieceCount, localSnapshot,
                    uploadedSupplier.getAsLong(), downloadedSupplier.getAsLong(),
                    System.currentTimeMillis()));
        } catch (IOException e) {
            log.warn("cannot save resume state", e);
        }
    }

    /** 删除状态文件（连同下载数据一起删除任务时调用）。 */
    public void delete() {
        ResumeState.delete(file);
    }
}
