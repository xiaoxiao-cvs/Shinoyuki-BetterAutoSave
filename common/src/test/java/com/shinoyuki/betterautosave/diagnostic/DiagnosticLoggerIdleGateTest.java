package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期日志抑制判据 {@link DiagnosticLogger#idle} 的门禁测试.
 *
 * <p>存在理由: idle 判据是"整段摘要要不要打"的唯一开关, 少一项比较不会让任何别的测试挂,
 * 但会让对应那段观测在它最该出现的场景下被静默吞掉 —— 诊断两项
 * (syncLoadStalls / tickGapExceeded) 尤其如此: 存盘完全空闲的服务器照样会发生主线程同步
 * 区块加载与秒级 tick 间停顿, 而那时四项存盘 counter 恒等于上次, 判据一旦漏掉这两项就永远
 * 返回 true. 因此本类对 idle 里每一项比较都建一条会挂的用例.
 */
class DiagnosticLoggerIdleGateTest {

    /** 与 {@link #snapshotOf} 及 {@link #idleOf} 的下标一一对应, 仅用于失败信息. */
    private static final String[] COUNTER_NAMES = {
            "chunksSubmitted", "chunkMapSaveAsync", "entitiesSubmitted",
            "savedDataSubmitted", "syncLoadStalls", "tickGapExceeded",
    };

    private static final String[] GAUGE_NAMES = {
            "workerQueueDepth", "inFlightSerializing", "inFlightIoPending", "mustDrainPending",
    };

    private static final long[] NO_GAUGES = {0L, 0L, 0L, 0L};

    @Test
    void idle_is_true_only_when_every_counter_equals_last_seen() {
        long[] counters = {7L, 5L, 3L, 2L, 4L, 6L};
        assertTrue(idleOf(snapshotOf(counters, NO_GAUGES), counters),
                "六项 counter 全等于上次且四个 gauge 全零时必须判为空闲");

        for (int i = 0; i < counters.length; i++) {
            long[] advanced = counters.clone();
            advanced[i] = counters[i] + 1L;
            assertFalse(idleOf(snapshotOf(advanced, NO_GAUGES), counters),
                    COUNTER_NAMES[i] + " 单独前进时必须判为非空闲 (idle 里该项比较被删就会挂)");
        }
    }

    @Test
    void idle_is_false_when_only_sync_load_stalls_advanced() {
        // 场景: 存盘完全空闲 (四项存盘 counter 与上次逐项相等), 只有玩家跑图触发了主线程同步区块加载.
        // 这正是 syncLoad 摘要最该出现的时刻, 判据漏掉该项则整段被吞.
        long[] lastSeen = {12L, 9L, 0L, 0L, 3L, 1L};
        long[] now = {12L, 9L, 0L, 0L, 4L, 1L};
        assertFalse(idleOf(snapshotOf(now, NO_GAUGES), lastSeen),
                "存盘静止但 syncLoadStalls 增长时必须打出摘要");
    }

    @Test
    void idle_is_false_when_only_tick_gap_exceeded_advanced() {
        // 场景同上, 只有 tick 之间的停顿超阈值. 这段时间不计入 MSPT, 监控面板看不到,
        // 周期日志是唯一出口, 判据漏掉该项则运维完全无从发现.
        long[] lastSeen = {12L, 9L, 0L, 0L, 3L, 1L};
        long[] now = {12L, 9L, 0L, 0L, 3L, 2L};
        assertFalse(idleOf(snapshotOf(now, NO_GAUGES), lastSeen),
                "存盘静止但 tickGapExceeded 增长时必须打出摘要");
    }

    @Test
    void idle_is_false_when_any_in_flight_gauge_is_non_zero() {
        long[] counters = {7L, 5L, 3L, 2L, 4L, 6L};
        for (int i = 0; i < GAUGE_NAMES.length; i++) {
            long[] gauges = NO_GAUGES.clone();
            gauges[i] = 1L;
            assertFalse(idleOf(snapshotOf(counters, gauges), counters),
                    GAUGE_NAMES[i] + " 非零时仍有在途工作, 不得判为空闲 (idle 里该项比较被删就会挂)");
        }
    }

    @Test
    void idle_is_true_on_cold_start_all_zero() {
        // 边界: 服务器刚起, 全部 counter 与 lastSeen 都是 0. 此时不该刷一屏全零摘要.
        long[] zero = {0L, 0L, 0L, 0L, 0L, 0L};
        assertTrue(idleOf(snapshotOf(zero, NO_GAUGES), zero),
                "冷启动全零必须判为空闲");
    }

    @Test
    void idle_gate_is_exact_under_randomized_counters() {
        long seed = new Random().nextLong();
        Random rnd = new Random(seed);
        for (int round = 0; round < 200; round++) {
            long[] lastSeen = new long[COUNTER_NAMES.length];
            for (int i = 0; i < lastSeen.length; i++) {
                lastSeen[i] = rnd.nextInt(64);
            }
            assertTrue(idleOf(snapshotOf(lastSeen, NO_GAUGES), lastSeen),
                    "seed=" + seed + " round=" + round + " lastSeen=" + Arrays.toString(lastSeen)
                            + ": 逐项相等必须判空闲");

            int moved = rnd.nextInt(COUNTER_NAMES.length);
            long[] now = lastSeen.clone();
            now[moved] = lastSeen[moved] + 1L + rnd.nextInt(32);
            assertFalse(idleOf(snapshotOf(now, NO_GAUGES), lastSeen),
                    "seed=" + seed + " round=" + round + " 变动项=" + COUNTER_NAMES[moved]
                            + " (" + lastSeen[moved] + " -> " + now[moved] + "): 必须判非空闲");
        }
    }

    /**
     * 用真实 {@link SaveMetrics} 的 record/set 入口构造快照, 而不是直接 new Snapshot:
     * Snapshot 有 37 个同为 long 的分量, 手写构造实参极易错位且错位后无编译期信号;
     * 走真实入口还顺带覆盖了 "record 方法确实写到了对应 counter" 这一层.
     */
    private static SaveMetrics.Snapshot snapshotOf(long[] counters, long[] gauges) {
        SaveMetrics metrics = new SaveMetrics();
        repeat(counters[0], metrics::recordChunkSubmitted);
        repeat(counters[1], metrics::recordChunkMapSaveAsync);
        repeat(counters[2], metrics::recordEntitySubmitted);
        repeat(counters[3], metrics::recordSavedDataSubmitted);
        repeat(counters[4], () -> metrics.recordSyncLoadStall(1_000_000L));
        repeat(counters[5], () -> metrics.recordTickGap(2_000_000L));
        metrics.setWorkerQueueDepth(gauges[0]);
        repeat(gauges[1], metrics::incInFlightSerializing);
        repeat(gauges[2], metrics::incInFlightIoPending);
        repeat(gauges[3], metrics::incMustDrainPending);
        return metrics.snapshot();
    }

    private static boolean idleOf(SaveMetrics.Snapshot snap, long[] lastSeen) {
        return DiagnosticLogger.idle(snap, lastSeen[0], lastSeen[1], lastSeen[2], lastSeen[3],
                lastSeen[4], lastSeen[5]);
    }

    private static void repeat(long times, Runnable action) {
        for (long i = 0; i < times; i++) {
            action.run();
        }
    }
}
