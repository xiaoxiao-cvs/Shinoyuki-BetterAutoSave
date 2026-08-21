package com.shinoyuki.betterautosave.diagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class DiagnosticLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("BetterAutoSave");

    /** 摘要行里单条归因主体的显示上限, 超出截断: 第三方 mod 的类全限定名可以很长, 不能撑爆日志行. */
    private static final int ATTRIBUTION_DISPLAY_LIMIT = 40;

    /** 摘要行里列出的同步加载来源条数. */
    private static final int TOP_SYNC_LOAD_ENTRIES = 3;

    private final SaveMetrics metrics;
    private final BooleanSupplier diagnosticLogging;
    private final IntSupplier diagnosticLogIntervalTicks;
    // 诊断 tracker 允许为 null: 服务器启动早期与未注入的会话下, 摘要行退化为零值而不是让周期日志整段失败.
    private final SyncLoadTracker syncLoadTracker;
    private final TickGapTracker tickGapTracker;
    private int tickCounter;
    private long lastSubmittedSeen;
    private long lastChunkMapSaveAsyncSeen;
    private long lastEntitiesSubmittedSeen;
    private long lastSavedDataSubmittedSeen;
    private long lastSyncLoadStallsSeen;
    private long lastTickGapExceededSeen;

    public DiagnosticLogger(SaveMetrics metrics, BooleanSupplier diagnosticLogging,
                            IntSupplier diagnosticLogIntervalTicks,
                            SyncLoadTracker syncLoadTracker,
                            TickGapTracker tickGapTracker) {
        this.metrics = metrics;
        this.diagnosticLogging = diagnosticLogging;
        this.diagnosticLogIntervalTicks = diagnosticLogIntervalTicks;
        this.syncLoadTracker = syncLoadTracker;
        this.tickGapTracker = tickGapTracker;
    }

    public void onServerTick() {
        if (!diagnosticLogging.getAsBoolean()) {
            tickCounter = 0;
            return;
        }
        int interval = diagnosticLogIntervalTicks.getAsInt();
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;
        emit();
    }

    /**
     * 周期日志的抑制判据. 存盘四项 counter 与四个队列 gauge 全静止时视为空闲, 不刷日志。
     *
     * <p>诊断两项 (syncLoadStalls / tickGapExceeded) 必须并入判据: 存盘完全空闲的服务器照样会发生
     * 主线程同步区块加载 (玩家跑图) 与秒级 tick 间停顿 (第三方 mod 的任务), 若只看存盘口径,
     * 新增的两段摘要恰好会在最需要它们的场景下被整段吞掉。
     */
    static boolean idle(SaveMetrics.Snapshot snap,
                        long lastSubmittedSeen,
                        long lastChunkMapSaveAsyncSeen,
                        long lastEntitiesSubmittedSeen,
                        long lastSavedDataSubmittedSeen,
                        long lastSyncLoadStallsSeen,
                        long lastTickGapExceededSeen) {
        return snap.chunksSubmitted() == lastSubmittedSeen
                && snap.chunkMapSaveAsync() == lastChunkMapSaveAsyncSeen
                && snap.entitiesSubmitted() == lastEntitiesSubmittedSeen
                && snap.savedDataSubmitted() == lastSavedDataSubmittedSeen
                && snap.workerQueueDepth() == 0L
                && snap.inFlightSerializing() == 0L
                && snap.inFlightIoPending() == 0L
                && snap.mustDrainPending() == 0L
                && snap.syncLoadStalls() == lastSyncLoadStallsSeen
                && snap.tickGapExceeded() == lastTickGapExceededSeen;
    }

    public void emit() {
        SaveMetrics.Snapshot snap = metrics.snapshot();
        if (idle(snap, lastSubmittedSeen, lastChunkMapSaveAsyncSeen, lastEntitiesSubmittedSeen,
                lastSavedDataSubmittedSeen, lastSyncLoadStallsSeen, lastTickGapExceededSeen)) {
            return;
        }
        lastSubmittedSeen = snap.chunksSubmitted();
        lastChunkMapSaveAsyncSeen = snap.chunkMapSaveAsync();
        lastEntitiesSubmittedSeen = snap.entitiesSubmitted();
        lastSavedDataSubmittedSeen = snap.savedDataSubmitted();
        lastSyncLoadStallsSeen = snap.syncLoadStalls();
        lastTickGapExceededSeen = snap.tickGapExceeded();
        LOGGER.info("[BetterAutoSave] metrics");
        LOGGER.info("[BetterAutoSave]   |- chunks: submitted={} completed={} failed={} retried={} fallback={}",
                snap.chunksSubmitted(),
                snap.chunksCompleted(),
                snap.chunksFailed(),
                snap.chunksRetried(),
                snap.chunksFallback());
        LOGGER.info("[BetterAutoSave]   |- chunkMapSave: async={} fallback={} bypass={} mustDrainPending={}",
                snap.chunkMapSaveAsync(),
                snap.chunkMapSaveFallback(),
                snap.chunkMapSaveBypass(),
                snap.mustDrainPending());
        if (snap.entitiesSubmitted() > 0L) {
            LOGGER.info("[BetterAutoSave]   |- entities: submitted={} completed={} failed={} retried={} fallback={}",
                    snap.entitiesSubmitted(),
                    snap.entitiesCompleted(),
                    snap.entitiesFailed(),
                    snap.entitiesRetried(),
                    snap.entitiesFallback());
        }
        if (snap.savedDataSubmitted() > 0L) {
            LOGGER.info("[BetterAutoSave]   |- savedData: submitted={} completed={} failed={} fallback={}",
                    snap.savedDataSubmitted(),
                    snap.savedDataCompleted(),
                    snap.savedDataFailed(),
                    snap.savedDataFallback());
        }
        LOGGER.info("[BetterAutoSave]   |- queue: chunkDepth={} savedDataDepth={}",
                snap.workerQueueDepth(),
                snap.savedDataQueueDepth());
        LOGGER.info("[BetterAutoSave]   |- inflight: serializing={} ioPending={}",
                snap.inFlightSerializing(),
                snap.inFlightIoPending());
        LOGGER.info("[BetterAutoSave]   |- latency p50/p99 (us): capture={}/{} worker={}/{} io={}/{}",
                SaveMetrics.formatLatencyUs(snap.mainThreadCapture().p50Ns()),
                SaveMetrics.formatLatencyUs(snap.mainThreadCapture().p99Ns()),
                SaveMetrics.formatLatencyUs(snap.workerNbtBuild().p50Ns()),
                SaveMetrics.formatLatencyUs(snap.workerNbtBuild().p99Ns()),
                SaveMetrics.formatLatencyUs(snap.ioStore().p50Ns()),
                SaveMetrics.formatLatencyUs(snap.ioStore().p99Ns()));
        // 两行诊断摘要无条件输出 (不做 >0 条件分支), 保证每次 emit 的行数恒定, 便于日志解析.
        // 措辞是中立事实陈述: 只报数值与调用点, 停顿的成因通常在 BAS 之外, 不代表被归因方存在缺陷.
        LOGGER.info("[BetterAutoSave]   |- syncLoad: stalls={} totalBlocked={} tracked={} top={}",
                snap.syncLoadStalls(),
                SaveMetrics.formatMs(snap.syncLoadStallNs()),
                syncLoadTracker != null ? syncLoadTracker.size() : 0,
                topSyncLoadSummary());
        // 措辞是 "after tick N" 而不是 "@tick N"/"before tick N": 记录点读到的 tickCount 是自增前的值,
        // 即这段停顿发生在第 N 刻跑完之后, 与 diagnose 命令和平台侧 warn 的文案保持一致.
        LOGGER.info("[BetterAutoSave]   `- tickGap: exceeded={} max={} last={} after tick {} deepTasks={}",
                snap.tickGapExceeded(),
                SaveMetrics.formatMs(snap.tickGapMaxNs()),
                tickGapTracker != null ? SaveMetrics.formatMs(tickGapTracker.gapRecord().lastNs()) : "0ms",
                tickGapTracker != null ? tickGapTracker.gapRecord().lastTickCount() : 0,
                tickGapTracker != null ? tickGapTracker.taskCount() : 0);
    }

    /** 形如 {@code flan=5188ms x3, ftbchunks=210ms x1}; 无 tracker 或无样本时返回 {@code none}. */
    private String topSyncLoadSummary() {
        if (syncLoadTracker == null) {
            return "none";
        }
        List<SyncLoadRecord> top = syncLoadTracker.topByTotalBlockedNs(TOP_SYNC_LOAD_ENTRIES);
        if (top.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(96);
        for (SyncLoadRecord record : top) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String attribution = record.attribution();
            if (attribution.length() > ATTRIBUTION_DISPLAY_LIMIT) {
                attribution = attribution.substring(0, ATTRIBUTION_DISPLAY_LIMIT);
            }
            sb.append(attribution)
                    .append('=')
                    .append(SaveMetrics.formatMs(record.totalBlockedNs()))
                    .append(" x")
                    .append(record.totalSamples());
        }
        return sb.toString();
    }
}
