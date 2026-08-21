package com.shinoyuki.betterautosave.diagnostic;

import java.util.Arrays;

/**
 * v0.20: 一条 tick 停顿统计的滑动窗口 —— 既可以是默认档那条唯一的
 * tick 间隔聚合, 也可以是深度档下单个任务类型的耗时聚合.
 *
 * <p>与 {@link SyncLoadRecord} 同形状, 差别在于"最近一次"现场字段是 tick
 * 序号而不是坐标, 且不存栈: tick gap 发生在 tick 之间, 采栈时主线程已经
 * 离开了造成停顿的那条调用链, 栈没有归因价值.
 *
 * <p><b>线程安全</b>: 全部 mutable state 由 {@code synchronized} 实例方法
 * 保护, 同 SyncLoadRecord.
 */
public final class TickGapRecord {

    private final String label;
    private final long[] samples;

    private int writeIdx;
    private int sampleCount;
    private long totalSamples;
    private long totalNs;
    private long lastAtMillis;
    private int lastTickCount;

    TickGapRecord(String label, int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        this.label = label;
        this.samples = new long[windowSize];
    }

    synchronized void addSample(long durationNs, int tickCount) {
        samples[writeIdx] = durationNs;
        writeIdx = (writeIdx + 1) % samples.length;
        if (sampleCount < samples.length) {
            sampleCount++;
        }
        totalSamples++;
        totalNs += durationNs;
        lastTickCount = tickCount;
        lastAtMillis = System.currentTimeMillis();
    }

    public String label() {
        return label;
    }

    public synchronized int sampleCount() {
        return sampleCount;
    }

    public synchronized long totalSamples() {
        return totalSamples;
    }

    /** 历史累计纳秒, 不受窗口覆盖影响; 深度档 Top N 排序用它. */
    public synchronized long totalNs() {
        return totalNs;
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

    public synchronized int lastTickCount() {
        return lastTickCount;
    }
}
