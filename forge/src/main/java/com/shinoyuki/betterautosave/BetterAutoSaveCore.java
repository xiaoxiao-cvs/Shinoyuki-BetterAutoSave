package com.shinoyuki.betterautosave;

import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.leveldat.RegistryTagCache;
import com.shinoyuki.betterautosave.core.playerdata.PlayerSaveStagger;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.ChunkLatencyTracker;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterautosave.diagnostic.PrometheusExporter;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

public final class BetterAutoSaveCore {

    private static volatile SaveMetrics METRICS;
    private static volatile SaveScheduler SCHEDULER;
    private static volatile SnapshotPipeline PIPELINE;
    private static volatile AsyncIoBridge IO_BRIDGE;
    private static volatile DiagnosticLogger DIAGNOSTIC_LOGGER;
    private static volatile PrometheusExporter EXPORTER;
    private static volatile ChunkLatencyTracker LATENCY_TRACKER;
    private static volatile RegistryTagCache REGISTRY_TAG_CACHE;
    private static volatile PlayerSaveStagger PLAYER_SAVE_STAGGER;

    /**
     * 是否正处于 autosave 触发的 saveEverything 窗口内。由 MinecraftServerMixin 在
     * saveEverything 的 HEAD/RETURN 置位与清除, 供玩家存盘错峰判断"这次是不是 autosave"。
     *
     * <p>只在主线程读写 (saveEverything 与 saveAll 都在主线程), 用 volatile 只为可见性直白。
     */
    private static volatile boolean IN_AUTOSAVE_WINDOW;

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
        REGISTRY_TAG_CACHE = null;
        PLAYER_SAVE_STAGGER = null;
        IN_AUTOSAVE_WINDOW = false;
    }

    public static void setPlayerSaveStagger(PlayerSaveStagger stagger) {
        PLAYER_SAVE_STAGGER = stagger;
    }

    public static PlayerSaveStagger playerSaveStagger() {
        return PLAYER_SAVE_STAGGER;
    }

    public static void setInAutosaveWindow(boolean value) {
        IN_AUTOSAVE_WINDOW = value;
    }

    public static boolean isInAutosaveWindow() {
        return IN_AUTOSAVE_WINDOW;
    }

    /**
     * issue #25: level.dat 注册表快照缓存. 绑定 MinecraftServer 生命周期 (install/uninstall),
     * 使 ForgeHooksLevelSaveMixin 只在"服务器已起且未关"的窗口内介入 —— 服务器开始 tick 前
     * (net.minecraft.server.Main 的预写) 与客户端 world-optimize 线程上的写盘都恒为 null, 自动放行 vanilla.
     */
    public static void setRegistryTagCache(RegistryTagCache cache) {
        REGISTRY_TAG_CACHE = cache;
    }

    public static RegistryTagCache registryTagCache() {
        return REGISTRY_TAG_CACHE;
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
