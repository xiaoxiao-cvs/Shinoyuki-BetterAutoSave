package com.shinoyuki.betterautosave.diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.20: tick 之间停顿的两级聚合.
 *
 * <p>默认档只有一条聚合 record ({@link #recordGap}): tick 序号天然单调递增,
 * 按它建 key 会让 LRU 每条都是新 key 而瞬间打满, 所以默认档根本不建 map.
 * 深度档 ({@link #recordTask}) 才按任务类型建 LRU map, 结构与
 * {@link SyncLoadTracker} 完全一致.
 *
 * <p><b>为什么两级不合并成一张表</b>: 间隔 (两个 tick 之间的墙钟) 与单任务
 * 耗时是两种单位不同的量, 混在一张表里会让 {@code /betterautosave diagnose}
 * 的输出无法解读.
 */
public final class TickGapTracker {

    /** 同 SyncLoadTracker.DEFAULT_WINDOW_SIZE: 低频事件, 窗口大小无可调价值. */
    public static final int DEFAULT_WINDOW_SIZE = 100;

    /** 默认档那条唯一聚合 record 的标签. */
    public static final String GAP_LABEL = "tick-gap";

    private final int windowSize;
    private final int trackLimit;
    private final Map<String, TickGapRecord> tasks;

    // volatile: clear() 整体替换实例而不是逐字段清零, 并发读方拿到的要么是旧对象
    // 要么是新对象, 两者各自自洽, 不会观察到半清空状态.
    private volatile TickGapRecord gapRecord;

    public TickGapTracker(int windowSize, int trackLimit) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        if (trackLimit <= 0) {
            throw new IllegalArgumentException("trackLimit must be > 0");
        }
        this.windowSize = windowSize;
        this.trackLimit = trackLimit;
        this.gapRecord = new TickGapRecord(GAP_LABEL, windowSize);
        this.tasks = Collections.synchronizedMap(new LinkedHashMap<String, TickGapRecord>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TickGapRecord> eldest) {
                return size() > trackLimit;
            }
        });
    }

    /**
     * 默认档: 记录一次已被调用方判定为超阈值的 tick 间隔. 不触碰 task map.
     */
    public void recordGap(long gapNs, int tickCount) {
        gapRecord.addSample(gapNs, tickCount);
    }

    /**
     * 深度档: 记录一次超阈值的单任务耗时. 不触碰默认档的 gapRecord.
     */
    public void recordTask(String taskLabel, long durationNs, int tickCount) {
        TickGapRecord r;
        synchronized (tasks) {
            r = tasks.get(taskLabel);
            if (r == null) {
                r = new TickGapRecord(taskLabel, windowSize);
                tasks.put(taskLabel, r);
            }
        }
        // 锁外 addSample, 与 SyncLoadTracker 同一论证.
        r.addSample(durationNs, tickCount);
    }

    /** 恒非 null. */
    public TickGapRecord gapRecord() {
        return gapRecord;
    }

    /** 按历史累计纳秒降序拿 top n 个任务类型. n <= 0 时返回全部. */
    public List<TickGapRecord> topTasksByTotalNs(int n) {
        List<TickGapRecord> snapshot;
        synchronized (tasks) {
            snapshot = new ArrayList<>(tasks.values());
        }
        snapshot.sort((a, b) -> Long.compare(b.totalNs(), a.totalNs()));
        if (n > 0 && snapshot.size() > n) {
            return new ArrayList<>(snapshot.subList(0, n));
        }
        return snapshot;
    }

    public int taskCount() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    public int windowSize() {
        return windowSize;
    }

    public int trackLimit() {
        return trackLimit;
    }

    public void clear() {
        synchronized (tasks) {
            tasks.clear();
        }
        gapRecord = new TickGapRecord(GAP_LABEL, windowSize);
    }
}
