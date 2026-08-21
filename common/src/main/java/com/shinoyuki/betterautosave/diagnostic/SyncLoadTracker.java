package com.shinoyuki.betterautosave.diagnostic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v0.20: 主线程同步区块加载事件的 LRU 聚合, 给
 * {@code /betterautosave diagnose} 与周期诊断日志提供数据源.
 *
 * <p>数据结构与并发策略照抄 {@link ChunkLatencyTracker}: synchronized
 * access-order {@link LinkedHashMap} + removeEldestEntry 实现 LRU,
 * get-or-create 在 map 锁内, addSample 在 map 锁外.
 *
 * <p><b>key 为什么不含坐标</b>: 同步加载是"谁在什么调用点上同步取图"的问题,
 * 不是"哪个坐标慢"的问题. 把 chunkX/chunkZ 塞进 key, 玩家跑一次图就能产生
 * 上千个互不相同的 key, trackLimit=64 会被瞬间打满, LRU 退化成随机丢弃,
 * 聚合彻底失效. 坐标与维度只作为 record 的"最近一次"现场字段保留.
 */
public final class SyncLoadTracker {

    /**
     * 窗口大小不开配置键: 同步加载停顿是低频事件 (超阈值才记录), 窗口大小
     * 对运维无可调价值, 由 wiring 统一传入本常量; 构造器仍收 windowSize
     * 以便测试用小窗口验证覆盖语义.
     */
    public static final int DEFAULT_WINDOW_SIZE = 100;

    private static final String EMPTY_FINGERPRINT = "00000000";
    private static final int FNV32_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV32_PRIME = 0x01000193;

    private final int windowSize;
    private final int trackLimit;
    private final Map<String, SyncLoadRecord> records;

    public SyncLoadTracker(int windowSize, int trackLimit) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        if (trackLimit <= 0) {
            throw new IllegalArgumentException("trackLimit must be > 0");
        }
        this.windowSize = windowSize;
        this.trackLimit = trackLimit;
        this.records = Collections.synchronizedMap(new LinkedHashMap<String, SyncLoadRecord>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SyncLoadRecord> eldest) {
                return size() > trackLimit;
            }
        });
    }

    /**
     * 记录一次已被调用方判定为超阈值的同步加载. 本类不做任何阈值判定:
     * 阈值语义 (含"恰好相等要记录") 属于平台侧 detector, 这里传什么记什么.
     */
    public void record(String attribution, String stackFingerprint, long blockedNs,
                       int chunkX, int chunkZ, String dimensionId, String[] stackFrames) {
        String key = attribution + "|" + stackFingerprint;
        SyncLoadRecord r;
        synchronized (records) {
            r = records.get(key);
            if (r == null) {
                r = new SyncLoadRecord(attribution, stackFingerprint, windowSize);
                records.put(key, r);
            }
        }
        // record 内部 synchronized, 与 tracker 锁不重叠. 即便此刻该 record 被 LRU 逐出,
        // 样本仍写入我们手上的引用, 只是变成不可见孤儿 —— 与 ChunkLatencyTracker 同一论证.
        r.addSample(blockedNs, chunkX, chunkZ, dimensionId, stackFrames);
    }

    /** 按历史累计阻塞纳秒降序拿 top n. n <= 0 时返回全部. */
    public List<SyncLoadRecord> topByTotalBlockedNs(int n) {
        List<SyncLoadRecord> snapshot;
        synchronized (records) {
            snapshot = new ArrayList<>(records.values());
        }
        snapshot.sort((a, b) -> Long.compare(b.totalBlockedNs(), a.totalBlockedNs()));
        if (n > 0 && snapshot.size() > n) {
            return new ArrayList<>(snapshot.subList(0, n));
        }
        return snapshot;
    }

    public int size() {
        synchronized (records) {
            return records.size();
        }
    }

    public int windowSize() {
        return windowSize;
    }

    public int trackLimit() {
        return trackLimit;
    }

    public void clear() {
        synchronized (records) {
            records.clear();
        }
    }

    /**
     * 栈指纹: 32 位 FNV-1a, 8 位小写 hex. 同一归因主体可能有多条不同调用路径,
     * 指纹把它们分成独立条目, 而完整栈只在 record 里留最近一次.
     *
     * <p>纯函数无锁; null 或空数组返回 {@value #EMPTY_FINGERPRINT}.
     */
    public static String fingerprint(String[] stackFrames) {
        if (stackFrames == null || stackFrames.length == 0) {
            return EMPTY_FINGERPRINT;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stackFrames.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(stackFrames[i]);
        }
        int hash = FNV32_OFFSET_BASIS;
        for (byte b : sb.toString().getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV32_PRIME;
        }
        return String.format(Locale.ROOT, "%08x", hash);
    }
}
