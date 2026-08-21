package com.shinoyuki.betterautosave.diagnostic;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * v0.20: 同步加载事件的判定、归因与记录。
 *
 * <p>阈值判定与采栈全部集中在这里, {@code ServerChunkCacheSyncLoadMixin} 的 handler 里一行都不做 ——
 * mixin handler 只负责"计时 + 转交", 这样字节码门禁才能用一条固定的调用序列锁死"常态零采栈"。
 */
public final class SyncLoadDetector {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    /**
     * 已经打过一行 warn 的 (attribution, 栈指纹)。5 秒级的同步加载在跑图时可能每秒发生多次,
     * 不去重会把服务器日志刷爆; 聚合数据仍然每次都进 tracker, 只有那行人类可读的 warn 去重。
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 去重集合的硬上限。归因主体本就寥寥可数, 封顶只为杜绝"指纹爆炸"把这张表变成内存泄漏。 */
    private static final int WARNED_LIMIT = 512;

    /**
     * 因去重集合封顶而没能打出 warn 的调用点次数。只统计"本该打出、却被封顶挡下"的那些行:
     * 已经打过 warn 的调用点再次触发时被去重掉是设计意图, 不算抑制, 计进来会把数字系统性放大,
     * 与 diagnose 那行"被抑制的 warn 行数"的语义对不上。封顶之后新调用点的那行人类可读证据会消失,
     * 聚合数据不受影响 —— 但按规范 6, 被丢弃的东西必须计数而不是静默。
     */
    private static final LongAdder SUPPRESSED_WARNS = new LongAdder();

    public static void onSyncLoadReturned(long blockedNs, int chunkX, int chunkZ, ServerLevel level) {
        // 世界初始化期的 spawn chunk 预载早于 ServerStartingEvent, 此时 metrics/tracker 尚未注入。
        // 那段阻塞是预期的启动成本, 不是运行期病症, 不记录。
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.syncLoadDetection()) {
            return;
        }
        long thresholdNs = BetterAutoSaveConfig.syncLoadThresholdMs() * 1_000_000L;
        if (blockedNs < thresholdNs) {
            return;
        }

        // 计数先于 tracker 判空: tracker 未注入时指标仍要递增, 否则 Prometheus 会漏报整段窗口。
        BetterAutoSaveCore.metrics().recordSyncLoadStall(blockedNs);

        SyncLoadTracker tracker = BetterAutoSaveCore.syncLoadTracker();
        if (tracker == null) {
            return;
        }

        String[] frames = SyncLoadStackCapture.capture(BetterAutoSaveConfig.syncLoadStackDepth());
        String first = SyncLoadStackCapture.attributionOf(frames);
        String modId = ModAttribution.modIdOf(first);
        String attribution = modId != null ? modId : first;
        String dimensionId = level != null ? level.dimension().location().toString() : "unknown";
        String fingerprint = SyncLoadTracker.fingerprint(frames);

        tracker.record(attribution, fingerprint, blockedNs, chunkX, chunkZ, dimensionId, frames);

        String warnKey = attribution + '|' + fingerprint;
        if (WARNED.contains(warnKey)) {
            // 这个调用点已经打过一行证据, 属于正常去重, 不算被封顶抑制。
            return;
        }
        if (WARNED.size() < WARNED_LIMIT && WARNED.add(warnKey)) {
            LOGGER.warn("[BetterAutoSave] main-thread sync chunk load: {} at ({},{}) in {}, called from {}",
                    SaveMetrics.formatMs(blockedNs), chunkX, chunkZ, dimensionId, first);
        } else {
            SUPPRESSED_WARNS.increment();
        }
    }

    /** 因去重集合封顶而未打出的 warn 行数。diagnose 用它把"被封顶吞掉"与"没发生"区分开。 */
    public static long suppressedWarns() {
        return SUPPRESSED_WARNS.sum();
    }

    /**
     * 清空 warn 去重集合与抑制计数。由 {@code /betterautosave diagnose reset} 与
     * {@code BetterAutoSaveCore.uninstall()} 调用 —— 统计被清空之后, 同一个调用点理应能重新打出
     * 那一行人类可读的证据。
     */
    public static void resetLogDedup() {
        WARNED.clear();
        SUPPRESSED_WARNS.reset();
    }

    private SyncLoadDetector() {
    }
}
