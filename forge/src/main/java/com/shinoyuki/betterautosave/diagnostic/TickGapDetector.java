package com.shinoyuki.betterautosave.diagnostic;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.mixin.accessor.TickTaskAccessor;
import net.minecraft.server.TickTask;
import org.slf4j.Logger;

import java.util.concurrent.atomic.LongAdder;

/**
 * v0.20: tick 之间停顿的判定与记录。
 *
 * <p>{@code tickServer} 之外的时间 (waitUntilNextTick 里的 pollTask 与 park) 不计入 MSPT, 因此一次
 * 十几秒的停顿在绝大多数监控面板上是完全不可见的 —— 面板只会显示"服务器很健康", 而玩家已经卡了十几秒。
 * 默认档就是用 tickServer 的 HEAD 与 TAIL 两个时间戳把这段墙钟量出来。
 */
public final class TickGapDetector {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    /** 归一化后的任务标签长度上限。lambda 隐藏类名可以很长, 截断后仍保留 owner 类名, 归因价值不丢。 */
    private static final int MAX_LABEL_LENGTH = 128;

    /**
     * 深度档单任务阈值 = tickGapThresholdMs 的十分之一。构成一次 1 秒 gap 的单个任务通常在 100ms 量级,
     * 直接复用 tickGapThresholdMs 会把它们全部漏掉; 派生而不是新开配置键, 是为了不让运维面对两个需要
     * 一起调的阈值。
     */
    private static final long DEEP_THRESHOLD_DIVISOR = 10L;

    /**
     * 两行 warn 之间的最小间隔。tick gap 没有 SyncLoadDetector 那种 (归因, 栈指纹) 键可以去重 ——
     * 同一个成因每 tick 都会重新触发 —— 所以这里用速率限制。tickGapThresholdMs 允许调到下限 50,
     * 而健康服务器的常态 gap 就在 50ms 上下抖动, 不限流会产出每秒二十行 warn。
     */
    private static final long WARN_MIN_INTERVAL_NS = 10_000_000_000L;

    /**
     * 因限流而没有打出的 warn 行数。与 SyncLoadDetector 同理: 聚合数据每次都进 tracker,
     * 被丢掉的只有人类可读的那一行, 但丢了多少必须能查到。
     */
    private static final LongAdder SUPPRESSED_WARNS = new LongAdder();

    /**
     * 上一行 warn 的时间戳。{@link Long#MIN_VALUE} 是"还没打过"的哨兵: nanoTime 的原点是任意的,
     * 0 是一个合法的时间戳值, 不能拿来当哨兵。
     */
    private static volatile long LAST_WARN_NANOS = Long.MIN_VALUE;

    /** mixin 的 doRunTask HEAD 用它决定这一次要不要取时间戳。深度档关闭时只剩一次 volatile 读。 */
    public static boolean deepEnabled() {
        return BetterAutoSaveCore.isInstalled() && BetterAutoSaveConfig.tickGapDeepAttribution();
    }

    public static void onTickStart(long gapNs, int tickCount) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.tickGapDetection()) {
            return;
        }
        long thresholdNs = BetterAutoSaveConfig.tickGapThresholdMs() * 1_000_000L;
        if (gapNs < thresholdNs) {
            return;
        }

        // 与 SyncLoadDetector 同理: 计数先于 tracker 判空, 保证 Prometheus 不漏报。
        BetterAutoSaveCore.metrics().recordTickGap(gapNs);

        TickGapTracker tracker = BetterAutoSaveCore.tickGapTracker();
        if (tracker != null) {
            tracker.recordGap(gapNs, tickCount);
        }
        long nowNs = System.nanoTime();
        if (LAST_WARN_NANOS == Long.MIN_VALUE || nowNs - LAST_WARN_NANOS >= WARN_MIN_INTERVAL_NS) {
            LAST_WARN_NANOS = nowNs;
            // tickCount 在 tickServer 的 HEAD 读到的是自增前的值, 即刚结束的那个 tick 的序号;
            // 这段停顿发生在它之后, 所以措辞是 after 而不是 before。
            LOGGER.warn("[BetterAutoSave] inter-tick gap: {} after tick {} (this time is not counted in MSPT)",
                    SaveMetrics.formatMs(gapNs), tickCount);
        } else {
            SUPPRESSED_WARNS.increment();
        }
    }

    public static void onTaskFinished(TickTask task, long durationNs, int tickCount) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.tickGapDeepAttribution()) {
            return;
        }
        long thresholdNs = BetterAutoSaveConfig.tickGapThresholdMs() * 1_000_000L / DEEP_THRESHOLD_DIVISOR;
        if (durationNs < thresholdNs) {
            return;
        }
        TickGapTracker tracker = BetterAutoSaveCore.tickGapTracker();
        if (tracker == null) {
            return;
        }
        Runnable runnable = ((TickTaskAccessor) (Object) task).betterautosave$getRunnable();
        String label = normalize(runnable != null ? runnable.getClass().getName() : task.getClass().getName());
        tracker.recordTask(label, durationNs, tickCount);
    }

    /**
     * 剥掉 Java 17/21 lambda 隐藏类名里 '/' 之后的那串地址 —— 它每次启动都不同, 留着会让同一个 lambda
     * 在 LRU 表里占掉无数条目。截断点之前保留的仍是 owner 类名。
     */
    static String normalize(String className) {
        if (className == null || className.isEmpty()) {
            return "unknown";
        }
        int slash = className.indexOf('/');
        String label = slash >= 0 ? className.substring(0, slash) : className;
        if (label.length() > MAX_LABEL_LENGTH) {
            return label.substring(0, MAX_LABEL_LENGTH);
        }
        return label;
    }

    /** 因限流未打印的 warn 行数。聚合数据不受影响, 少的只是人类可读的那一行。 */
    public static long suppressedWarns() {
        return SUPPRESSED_WARNS.sum();
    }

    /**
     * 复位限流状态。由 {@code /betterautosave diagnose reset} 调用 —— 统计被清空之后, 下一次超阈值
     * 理应能重新打出那一行人类可读的证据, 否则 reset 之后的日志是哑的。
     */
    public static void resetLogThrottle() {
        LAST_WARN_NANOS = Long.MIN_VALUE;
        SUPPRESSED_WARNS.reset();
    }

    private TickGapDetector() {
    }
}
