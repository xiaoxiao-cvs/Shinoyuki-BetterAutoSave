package com.shinoyuki.betterautosave.diagnostic;

import java.util.Arrays;

/**
 * v0.20: 一个 (归因主体, 栈指纹) 组合下的主线程同步区块加载滑动窗口统计.
 *
 * <p>形状对齐 {@link ChunkLatencyRecord}: 固定大小 ring buffer 存最近 N 次
 * 阻塞时长, 写满后覆盖最旧的.
 *
 * <p><b>为什么额外存 totalBlockedNs</b>: 窗口只保留最近 N 次, 而
 * {@code /betterautosave diagnose} 的 Top N 排序要按"该调用点历史上一共
 * 偷走了多少主线程时间"排, 被窗口覆盖掉的旧样本同样算数, 故历史累计必须
 * 独立于窗口持久化.
 *
 * <p><b>为什么坐标用 int 而不是 ChunkPos, 维度用 String 而不是 ResourceKey</b>:
 * common 模块零 Minecraft 依赖 (被 forge/neoforge source-merge 重编), 平台侧
 * 在传入前完成拆解.
 *
 * <p><b>线程安全</b>: 所有 mutable state 由 {@code synchronized} 实例方法保护.
 * 写方只有服务器主线程 (mixin 超阈值路径, 低频), 读方三路 (命令线程 /
 * Prometheus HTTP 线程 / DiagnosticLogger 所在 tick), 与 ChunkLatencyRecord
 * 读写形态一致.
 */
public final class SyncLoadRecord {

    private static final String[] NO_FRAMES = new String[0];

    private final String attribution;
    private final String stackFingerprint;
    private final long[] samples;

    private int writeIdx;
    private int sampleCount;
    private long totalSamples;
    private long totalBlockedNs;
    private long lastAtMillis;
    private int lastChunkX;
    private int lastChunkZ;
    private String lastDimensionId = "";
    private String[] lastStackFrames = NO_FRAMES;

    SyncLoadRecord(String attribution, String stackFingerprint, int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        this.attribution = attribution;
        this.stackFingerprint = stackFingerprint;
        this.samples = new long[windowSize];
    }

    synchronized void addSample(long blockedNs, int chunkX, int chunkZ, String dimensionId, String[] stackFrames) {
        samples[writeIdx] = blockedNs;
        writeIdx = (writeIdx + 1) % samples.length;
        if (sampleCount < samples.length) {
            sampleCount++;
        }
        totalSamples++;
        totalBlockedNs += blockedNs;
        lastAtMillis = System.currentTimeMillis();
        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        lastDimensionId = dimensionId != null ? dimensionId : "";
        // 防御性 clone: 调用方 (采栈路径) 复用数组或事后改写都不能污染已冻结的现场.
        lastStackFrames = stackFrames != null && stackFrames.length > 0 ? stackFrames.clone() : NO_FRAMES;
    }

    public String attribution() {
        return attribution;
    }

    public String stackFingerprint() {
        return stackFingerprint;
    }

    /** 窗口内样本数, 上限 windowSize. */
    public synchronized int sampleCount() {
        return sampleCount;
    }

    /** 历史累计命中次数, 不受窗口覆盖影响. */
    public synchronized long totalSamples() {
        return totalSamples;
    }

    /** 历史累计阻塞纳秒, 不受窗口覆盖影响; Top N 排序用它. */
    public synchronized long totalBlockedNs() {
        return totalBlockedNs;
    }

    public synchronized long lastNs() {
        if (sampleCount == 0) {
            return 0L;
        }
        int last = (writeIdx - 1 + samples.length) % samples.length;
        return samples[last];
    }

    /** 窗口内最大值 (不是历史 cumulative max). */
    public synchronized long maxNs() {
        long max = 0L;
        for (int i = 0; i < sampleCount; i++) {
            if (samples[i] > max) {
                max = samples[i];
            }
        }
        return max;
    }

    /** p99 (窗口内). count=0 返回 0. */
    public synchronized long p99Ns() {
        if (sampleCount == 0) {
            return 0L;
        }
        long[] copy = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(copy);
        int idx = Math.max(0, (int) Math.ceil(sampleCount * 0.99) - 1);
        return copy[idx];
    }

    public synchronized long lastAtMillis() {
        return lastAtMillis;
    }

    public synchronized int lastChunkX() {
        return lastChunkX;
    }

    public synchronized int lastChunkZ() {
        return lastChunkZ;
    }

    /** 无样本时返回空串. */
    public synchronized String lastDimensionId() {
        return lastDimensionId;
    }

    /** 最近一次事件的完整栈 (拷贝, 调用方改写不影响内部现场); 无样本时长度 0. */
    public synchronized String[] lastStackFrames() {
        return lastStackFrames.clone();
    }
}
