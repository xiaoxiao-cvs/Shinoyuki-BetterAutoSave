package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncLoadTrackerTest {

    private static final String[] FRAMES = {
            "io.github.flemmli97.flan.player.ClientBlockDisplayTracker",
            "net.minecraftforge.eventbus.EventBus",
    };

    private static void record(SyncLoadTracker t, String attribution, long blockedNs) {
        t.record(attribution, "cafebabe", blockedNs, 120, -340, "minecraft:overworld", FRAMES);
    }

    @Test
    void single_sample_yields_max_p99_last_equal_to_sample() {
        SyncLoadTracker t = new SyncLoadTracker(100, 64);
        t.record("flan", "cafebabe", 5_188_000_000L, 120, -340, "minecraft:overworld", FRAMES);

        List<SyncLoadRecord> top = t.topByTotalBlockedNs(10);
        assertEquals(1, top.size());
        SyncLoadRecord r = top.get(0);
        assertEquals("flan", r.attribution());
        assertEquals("cafebabe", r.stackFingerprint());
        assertEquals(1, r.sampleCount());
        assertEquals(1L, r.totalSamples());
        assertEquals(5_188_000_000L, r.totalBlockedNs());
        assertEquals(5_188_000_000L, r.p99Ns());
        assertEquals(5_188_000_000L, r.maxNs());
        assertEquals(5_188_000_000L, r.lastNs());
        assertEquals(120, r.lastChunkX());
        assertEquals(-340, r.lastChunkZ());
        assertEquals("minecraft:overworld", r.lastDimensionId());
        assertTrue(r.lastAtMillis() > 0L, "记录一次后 lastAtMillis 必须被戳上");
    }

    @Test
    void ring_buffer_overwrites_oldest_but_total_blocked_keeps_history() {
        SyncLoadTracker t = new SyncLoadTracker(5, 64);
        // 写 8 个样本 100ms..800ms: 窗口只留后 5 个 (400..800), 历史累计必须是全部 8 个之和.
        for (int i = 1; i <= 8; i++) {
            record(t, "flan", i * 100_000_000L);
        }
        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals(5, r.sampleCount(), "窗口满后 sampleCount 不再增长");
        assertEquals(8L, r.totalSamples(), "totalSamples 仍累计所有写入");
        assertEquals(3_600_000_000L, r.totalBlockedNs(),
                "totalBlockedNs 必须是 100..800ms 全部 8 个之和, 不受窗口覆盖影响");
        assertEquals(800_000_000L, r.maxNs(), "窗口 max 应为最新最大值 800ms");
        assertEquals(800_000_000L, r.lastNs(), "lastNs 是最新写入");
        assertTrue(r.p99Ns() >= 400_000_000L,
                "窗口内最小 400ms, p99 不该返回被覆盖的旧值, got " + r.p99Ns());
    }

    @Test
    void p99_excludes_single_outlier_in_100_sample_window() {
        SyncLoadTracker t = new SyncLoadTracker(100, 64);
        for (int i = 0; i < 99; i++) {
            record(t, "flan", 1_000_000L);
        }
        record(t, "flan", 800_000_000L);

        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals(100, r.sampleCount());
        assertTrue(r.p99Ns() <= 1_000_000L,
                "100 样本 1 outlier (1%) 时 p99 应反映多数, got " + r.p99Ns());
        assertEquals(800_000_000L, r.maxNs(), "max 必须反映 outlier, 不能被吞");
    }

    @Test
    void p99_reflects_outlier_when_outliers_exceed_one_percent() {
        SyncLoadTracker t = new SyncLoadTracker(100, 64);
        for (int i = 0; i < 90; i++) {
            record(t, "flan", 1_000_000L);
        }
        for (int i = 0; i < 10; i++) {
            record(t, "flan", 800_000_000L);
        }
        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals(100, r.sampleCount());
        assertTrue(r.p99Ns() >= 500_000_000L,
                "10 outlier in 100 (10%) 应让 p99 落在 outlier 区, got " + r.p99Ns());
    }

    @Test
    void empty_record_returns_zero_and_empty_scene() {
        SyncLoadRecord r = new SyncLoadRecord("a", "f", 10);
        assertEquals(0, r.sampleCount());
        assertEquals(0L, r.totalSamples());
        assertEquals(0L, r.totalBlockedNs());
        assertEquals(0L, r.p99Ns());
        assertEquals(0L, r.maxNs());
        assertEquals(0L, r.lastNs());
        assertEquals(0L, r.lastAtMillis());
        assertEquals(0, r.lastChunkX());
        assertEquals(0, r.lastChunkZ());
        assertTrue(r.lastDimensionId().isEmpty(), "零样本时维度必须是空串而不是 null");
        assertEquals(0, r.lastStackFrames().length, "零样本时栈必须是空数组而不是 null");
    }

    @Test
    void record_constructed_with_non_positive_window_throws() {
        assertThrows(IllegalArgumentException.class, () -> new SyncLoadRecord("a", "f", 0));
        assertThrows(IllegalArgumentException.class, () -> new SyncLoadRecord("a", "f", -1));
    }

    @Test
    void lru_evicts_least_recently_recorded_attribution_when_limit_hit() {
        SyncLoadTracker t = new SyncLoadTracker(10, 3);
        record(t, "mod-a", 100L);
        record(t, "mod-b", 200L);
        record(t, "mod-c", 300L);
        assertEquals(3, t.size(), "正好等于 trackLimit 时不驱逐");

        record(t, "mod-d", 400L);
        assertEquals(3, t.size(), "超过上限一个后 size 收敛到 trackLimit");

        List<SyncLoadRecord> all = t.topByTotalBlockedNs(0);
        assertFalse(all.stream().anyMatch(r -> r.attribution().equals("mod-a")), "最老的 mod-a 应被驱逐");
        assertTrue(all.stream().anyMatch(r -> r.attribution().equals("mod-d")), "最新的 mod-d 应保留");
    }

    @Test
    void lru_access_order_protects_recently_recorded_from_eviction() {
        SyncLoadTracker t = new SyncLoadTracker(10, 3);
        record(t, "mod-a", 100L);
        record(t, "mod-b", 200L);
        record(t, "mod-c", 300L);
        // 重新命中 mod-a, 它从最老变成最新
        record(t, "mod-a", 150L);
        record(t, "mod-d", 400L);

        List<SyncLoadRecord> all = t.topByTotalBlockedNs(0);
        assertTrue(all.stream().anyMatch(r -> r.attribution().equals("mod-a")),
                "mod-a 被重新 record 后应被 access-order 保护");
        assertFalse(all.stream().anyMatch(r -> r.attribution().equals("mod-b")),
                "此时最老的是 mod-b, 应被驱逐");
    }

    @Test
    void same_attribution_with_different_fingerprint_tracked_separately() {
        // 同一个 mod 可能有多条不同调用路径, 指纹不同就必须分成独立条目,
        // 否则两条路径的耗时被叠加, diagnose 输出的栈也只剩一条.
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        t.record("flan", "aaaaaaaa", 1_000L, 1, 1, "dim", FRAMES);
        t.record("flan", "bbbbbbbb", 2_000L, 2, 2, "dim", FRAMES);
        assertEquals(2, t.size(), "attribution 相同但指纹不同必须各占一条");

        List<SyncLoadRecord> all = t.topByTotalBlockedNs(0);
        all.forEach(r -> assertEquals(1L, r.totalSamples(),
                r.stackFingerprint() + " 只应收到自己那一条样本"));
    }

    @Test
    void top_by_total_blocked_is_sorted_descending() {
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        record(t, "mod-a", 100_000_000L);
        record(t, "mod-b", 50_000_000L);
        // mod-c 命中 3 次共 30ms, 仍低于 mod-b 的 50ms: 排序按总阻塞而不是命中次数
        record(t, "mod-c", 10_000_000L);
        record(t, "mod-c", 10_000_000L);
        record(t, "mod-c", 10_000_000L);

        List<SyncLoadRecord> top = t.topByTotalBlockedNs(3);
        assertEquals(3, top.size());
        assertEquals("mod-a", top.get(0).attribution());
        assertEquals("mod-b", top.get(1).attribution());
        assertEquals("mod-c", top.get(2).attribution());
        assertEquals(30_000_000L, top.get(2).totalBlockedNs());
        assertEquals(3L, top.get(2).totalSamples());
        assertTrue(top.get(0).totalBlockedNs() > top.get(1).totalBlockedNs(), "排序必须严格降序");
    }

    @Test
    void top_by_total_blocked_n_boundaries_return_all() {
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        record(t, "mod-a", 100L);
        record(t, "mod-b", 200L);
        record(t, "mod-c", 300L);
        assertEquals(3, t.topByTotalBlockedNs(0).size());
        assertEquals(3, t.topByTotalBlockedNs(-1).size());
        assertEquals(3, t.topByTotalBlockedNs(99).size(), "n > size 时返回全部");
        assertEquals(1, t.topByTotalBlockedNs(1).size());
    }

    @Test
    void zero_nanos_sample_is_recorded_verbatim() {
        // 阈值判定是平台侧 detector 的职责, tracker 传什么记什么: 传 0 也必须如实计入,
        // 否则"阈值恰好相等"的边界事件会在 tracker 层被二次吞掉.
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        record(t, "mod-a", 0L);
        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals(1, r.sampleCount());
        assertEquals(1L, r.totalSamples());
        assertEquals(0L, r.totalBlockedNs());
        assertEquals(0L, r.maxNs());
    }

    @Test
    void last_stack_frames_is_defensive_copy_on_both_write_and_read() {
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        String[] source = {"io.github.flemmli97.flan.A", "io.github.flemmli97.flan.B"};
        t.record("flan", "cafebabe", 1_000L, 1, 2, "dim", source);

        // 写入侧: 调用方事后改写源数组不能污染已冻结的现场
        source[0] = "mutated-by-caller";
        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals("io.github.flemmli97.flan.A", r.lastStackFrames()[0]);

        // 读出侧: 调用方改写返回值不能污染内部
        String[] out = r.lastStackFrames();
        out[0] = "mutated-by-reader";
        assertEquals("io.github.flemmli97.flan.A", r.lastStackFrames()[0]);
    }

    @Test
    void last_scene_fields_follow_most_recent_sample() {
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        t.record("flan", "cafebabe", 1_000L, 1, 2, "minecraft:overworld", new String[]{"a.A"});
        t.record("flan", "cafebabe", 2_000L, -7, 9, "minecraft:the_nether", new String[]{"b.B"});

        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals(-7, r.lastChunkX());
        assertEquals(9, r.lastChunkZ());
        assertEquals("minecraft:the_nether", r.lastDimensionId());
        assertEquals("b.B", r.lastStackFrames()[0]);
        assertEquals(3_000L, r.totalBlockedNs(), "现场字段被覆盖, 但累计量仍是两次之和");
    }

    @Test
    void concurrent_record_same_key_does_not_lose_samples() throws InterruptedException {
        SyncLoadTracker t = new SyncLoadTracker(10_000, 64);
        final int threads = 8;
        final int writesPerThread = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < writesPerThread; j++) {
                        t.record("flan", "cafebabe", 1_000L, j, j, "dim", FRAMES);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "concurrent-sync-load-record-" + i).start();
        }
        start.countDown();
        done.await();

        assertEquals(0, errors.get(), "并发 record 不应抛异常");
        SyncLoadRecord r = t.topByTotalBlockedNs(1).get(0);
        assertEquals((long) threads * writesPerThread, r.totalSamples(),
                "所有写入必须被计入 totalSamples");
        assertEquals((long) threads * writesPerThread * 1_000L, r.totalBlockedNs(),
                "累计阻塞纳秒必须精确等于全部样本之和");
    }

    @Test
    void invalid_construction_args_throw() {
        assertThrows(IllegalArgumentException.class, () -> new SyncLoadTracker(0, 64));
        assertThrows(IllegalArgumentException.class, () -> new SyncLoadTracker(-1, 64));
        assertThrows(IllegalArgumentException.class, () -> new SyncLoadTracker(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyncLoadTracker(100, -1));
    }

    @Test
    void clear_removes_all_records() {
        SyncLoadTracker t = new SyncLoadTracker(10, 64);
        record(t, "mod-a", 100L);
        record(t, "mod-b", 200L);
        assertEquals(2, t.size());
        t.clear();
        assertEquals(0, t.size());
        assertTrue(t.topByTotalBlockedNs(10).isEmpty());
    }

    @Test
    void window_size_and_track_limit_are_exposed_verbatim() {
        SyncLoadTracker t = new SyncLoadTracker(37, 11);
        assertEquals(37, t.windowSize());
        assertEquals(11, t.trackLimit());
        assertEquals(100, SyncLoadTracker.DEFAULT_WINDOW_SIZE);
    }

    @Test
    void fingerprint_is_stable_distinct_and_zero_for_empty() {
        String[] a = {"io.github.flemmli97.flan.A", "net.minecraftforge.eventbus.EventBus"};
        String[] b = {"io.github.flemmli97.flan.B", "net.minecraftforge.eventbus.EventBus"};

        String fa = SyncLoadTracker.fingerprint(a);
        assertEquals(fa, SyncLoadTracker.fingerprint(new String[]{a[0], a[1]}),
                "同输入必须同输出 (纯函数)");
        assertFalse(fa.equals(SyncLoadTracker.fingerprint(b)), "不同栈必须得到不同指纹");
        assertEquals(8, fa.length(), "指纹是 8 位 hex");
        assertTrue(fa.matches("[0-9a-f]{8}"), "指纹必须是小写 hex, got " + fa);

        assertEquals("00000000", SyncLoadTracker.fingerprint(null));
        assertEquals("00000000", SyncLoadTracker.fingerprint(new String[0]));

        // 帧顺序不同 = 不同调用路径, 指纹必须区分
        assertFalse(SyncLoadTracker.fingerprint(new String[]{"x.A", "x.B"})
                        .equals(SyncLoadTracker.fingerprint(new String[]{"x.B", "x.A"})),
                "帧顺序不同的栈不能撞指纹");
        // 分隔符必须真正分隔, 否则 ["ab","c"] 与 ["a","bc"] 会撞
        assertFalse(SyncLoadTracker.fingerprint(new String[]{"ab", "c"})
                        .equals(SyncLoadTracker.fingerprint(new String[]{"a", "bc"})),
                "拼接必须带分隔符, 否则相邻帧边界丢失");
    }
}
