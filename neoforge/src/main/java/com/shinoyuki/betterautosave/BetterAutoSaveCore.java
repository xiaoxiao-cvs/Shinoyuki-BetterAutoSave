package com.shinoyuki.betterautosave;

import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.ChunkLatencyTracker;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterautosave.diagnostic.ModAttribution;
import com.shinoyuki.betterautosave.diagnostic.PrometheusExporter;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.diagnostic.SyncLoadDetector;
import com.shinoyuki.betterautosave.diagnostic.SyncLoadTracker;
import com.shinoyuki.betterautosave.diagnostic.TickGapDetector;
import com.shinoyuki.betterautosave.diagnostic.TickGapTracker;

public final class BetterAutoSaveCore {

    private static volatile SaveMetrics METRICS;
    private static volatile SaveScheduler SCHEDULER;
    private static volatile SnapshotPipeline PIPELINE;
    private static volatile AsyncIoBridge IO_BRIDGE;
    private static volatile DiagnosticLogger DIAGNOSTIC_LOGGER;
    private static volatile PrometheusExporter EXPORTER;
    private static volatile ChunkLatencyTracker LATENCY_TRACKER;
    private static volatile SyncLoadTracker SYNC_LOAD_TRACKER;
    private static volatile TickGapTracker TICK_GAP_TRACKER;

    public static void install(SaveMetrics metrics,
                               SaveScheduler scheduler,
                               SnapshotPipeline pipeline,
                               AsyncIoBridge ioBridge,
                               DiagnosticLogger diagnosticLogger) {
        METRICS = metrics;
        SCHEDULER = scheduler;
        PIPELINE = pipeline;
        IO_BRIDGE = ioBridge;
        DIAGNOSTIC_LOGGER = diagnosticLogger;
    }

    public static void uninstall() {
        METRICS = null;
        SCHEDULER = null;
        PIPELINE = null;
        IO_BRIDGE = null;
        DIAGNOSTIC_LOGGER = null;
        EXPORTER = null;
        LATENCY_TRACKER = null;
        SYNC_LOAD_TRACKER = null;
        TICK_GAP_TRACKER = null;
        // 诊断侧的三处进程级状态同样绑 server 生命周期: 单机客户端"连远程服 -> 退回单人"会换一批
        // mod 上下文, 上一轮的 modid 索引、warn 去重集合与 warn 限流时间戳跨 server 实例复用没有意义
        // (限流时间戳留着还会让新会话的第一行 gap 证据被吞掉)。
        ModAttribution.invalidate();
        SyncLoadDetector.resetLogDedup();
        TickGapDetector.resetLogThrottle();
    }

    /**
     * v0.9: 单独 setter 跟 install 解耦. exporter 是可选组件
     * (config.prometheusEnabled=false 时不实例化), 启动失败时也仅 setter
     * 不调用. install 签名保持 5 参数稳定.
     */
    public static void setExporter(PrometheusExporter exporter) {
        EXPORTER = exporter;
    }

    public static PrometheusExporter exporter() {
        return EXPORTER;
    }

    /**
     * v0.9: ChunkLatencyTracker 给 hottest-chunks 命令提供数据源.
     * 永远启用 (内存可控 ~MB), 用 setter 注入跟 exporter 一致风格.
     */
    public static void setLatencyTracker(ChunkLatencyTracker tracker) {
        LATENCY_TRACKER = tracker;
    }

    public static ChunkLatencyTracker latencyTracker() {
        return LATENCY_TRACKER;
    }

    /**
     * v0.20: 主线程同步区块加载的聚合表, 给 {@code /betterautosave diagnose} 与周期诊断日志供数。
     * 与 latencyTracker 同款 setter 注入 —— install 的 5 参签名保持稳定。
     */
    public static void setSyncLoadTracker(SyncLoadTracker tracker) {
        SYNC_LOAD_TRACKER = tracker;
    }

    public static SyncLoadTracker syncLoadTracker() {
        return SYNC_LOAD_TRACKER;
    }

    /** v0.20: tick 外停顿的聚合表 (默认档单条 + 深度档任务表)。 */
    public static void setTickGapTracker(TickGapTracker tracker) {
        TICK_GAP_TRACKER = tracker;
    }

    public static TickGapTracker tickGapTracker() {
        return TICK_GAP_TRACKER;
    }

    public static SaveMetrics metrics() {
        return METRICS;
    }

    public static SaveScheduler scheduler() {
        return SCHEDULER;
    }

    public static SnapshotPipeline pipeline() {
        return PIPELINE;
    }

    public static AsyncIoBridge ioBridge() {
        return IO_BRIDGE;
    }

    public static DiagnosticLogger diagnosticLogger() {
        return DIAGNOSTIC_LOGGER;
    }

    public static boolean isInstalled() {
        return PIPELINE != null;
    }

    private BetterAutoSaveCore() {
    }
}
