package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickGapTrackerTest {

    @Test
    void record_gap_only_touches_gap_record() {
        // 串线检查: 默认档不建 map, 一条 gap 不能凭空造出 task 条目.
        TickGapTracker t = new TickGapTracker(100, 64);
        t.recordGap(17_100_000_000L, 12345);

        assertEquals(0, t.taskCount(), "默认档不得产生 task 条目");
        assertTrue(t.topTasksByTotalNs(10).isEmpty());
        TickGapRecord gap = t.gapRecord();
        assertEquals(TickGapTracker.GAP_LABEL, gap.label());
        assertEquals(1, gap.sampleCount());
        assertEquals(1L, gap.totalSamples());
        assertEquals(17_100_000_000L, gap.totalNs());
        assertEquals(17_100_000_000L, gap.maxNs());
        assertEquals(17_100_000_000L, gap.lastNs());
        assertEquals(17_100_000_000L, gap.p99Ns());
        assertEquals(12345, gap.lastTickCount());
        assertTrue(gap.lastAtMillis() > 0L);
    }

    @Test
    void record_task_only_touches_task_map() {
        // 串线检查的反方向: 深度档的单任务耗时不能被并进 tick 间隔统计,
        // 两者单位与语义不同, 合并会让 diagnose 输出无法解读.
        TickGapTracker t = new TickGapTracker(100, 64);
        t.recordTask("net.minecraft.server.MinecraftServer$$Lambda", 300_000_000L, 77);

        assertEquals(1, t.taskCount());
        assertEquals(0, t.gapRecord().sampleCount(), "深度档不得污染 gapRecord");
        assertEquals(0L, t.gapRecord().totalNs());

        TickGapRecord task = t.topTasksByTotalNs(10).get(0);
        assertEquals("net.minecraft.server.MinecraftServer$$Lambda", task.label());
        assertEquals(300_000_000L, task.totalNs());
        assertEquals(77, task.lastTickCount());
    }

    @Test
    void gap_ring_buffer_overwrites_oldest_but_total_keeps_history() {
        TickGapTracker t = new TickGapTracker(5, 64);
        for (int i = 1; i <= 8; i++) {
            t.recordGap(i * 1_000_000_000L, i);
        }
        TickGapRecord gap = t.gapRecord();
        assertEquals(5, gap.sampleCount(), "窗口满后 sampleCount 不再增长");
        assertEquals(8L, gap.totalSamples(), "totalSamples 仍累计所有写入");
        assertEquals(36_000_000_000L, gap.totalNs(),
                "totalNs 必须是 1s..8s 全部 8 个之和, 不受窗口覆盖影响");
        assertEquals(8_000_000_000L, gap.maxNs());
        assertEquals(8_000_000_000L, gap.lastNs());
        assertEquals(8, gap.lastTickCount(), "lastTickCount 跟最近一次样本走");
        assertTrue(gap.p99Ns() >= 4_000_000_000L,
                "窗口内最小 4s, p99 不该返回被覆盖的旧值, got " + gap.p99Ns());
    }

    @Test
    void gap_p99_excludes_single_outlier_in_100_sample_window() {
        TickGapTracker t = new TickGapTracker(100, 64);
        for (int i = 0; i < 99; i++) {
            t.recordGap(1_000_000_000L, i);
        }
        t.recordGap(17_100_000_000L, 99);

        TickGapRecord gap = t.gapRecord();
        assertEquals(100, gap.sampleCount());
        assertTrue(gap.p99Ns() <= 1_000_000_000L,
                "100 样本 1 outlier (1%) 时 p99 应反映多数, got " + gap.p99Ns());
        assertEquals(17_100_000_000L, gap.maxNs(), "max 必须反映那次 17.1 秒停顿");
    }

    @Test
    void empty_gap_record_returns_zero_for_all_stats() {
        TickGapRecord r = new TickGapRecord("x", 10);
        assertEquals(0, r.sampleCount());
        assertEquals(0L, r.totalSamples());
        assertEquals(0L, r.totalNs());
        assertEquals(0L, r.p99Ns());
        assertEquals(0L, r.maxNs());
        assertEquals(0L, r.lastNs());
        assertEquals(0L, r.lastAtMillis());
        assertEquals(0, r.lastTickCount());
    }

    @Test
    void record_constructed_with_non_positive_window_throws() {
        assertThrows(IllegalArgumentException.class, () -> new TickGapRecord("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new TickGapRecord("x", -1));
    }

    @Test
    void deep_task_lru_evicts_least_recently_recorded_label() {
        TickGapTracker t = new TickGapTracker(10, 3);
        t.recordTask("task-a", 100L, 1);
        t.recordTask("task-b", 200L, 2);
        t.recordTask("task-c", 300L, 3);
        assertEquals(3, t.taskCount(), "正好等于 trackLimit 时不驱逐");

        t.recordTask("task-d", 400L, 4);
        assertEquals(3, t.taskCount(), "超过上限一个后 taskCount 收敛到 trackLimit");

        List<TickGapRecord> all = t.topTasksByTotalNs(0);
        assertFalse(all.stream().anyMatch(r -> r.label().equals("task-a")), "最老的 task-a 应被驱逐");
        assertTrue(all.stream().anyMatch(r -> r.label().equals("task-d")), "最新的 task-d 应保留");
    }

    @Test
    void deep_task_lru_access_order_protects_recently_recorded() {
        TickGapTracker t = new TickGapTracker(10, 3);
        t.recordTask("task-a", 100L, 1);
        t.recordTask("task-b", 200L, 2);
        t.recordTask("task-c", 300L, 3);
        t.recordTask("task-a", 150L, 4);
        t.recordTask("task-d", 400L, 5);

        List<TickGapRecord> all = t.topTasksByTotalNs(0);
        assertTrue(all.stream().anyMatch(r -> r.label().equals("task-a")),
                "task-a 被重新 record 后应被 access-order 保护");
        assertFalse(all.stream().anyMatch(r -> r.label().equals("task-b")),
                "此时最老的是 task-b, 应被驱逐");
    }

    @Test
    void top_tasks_sorted_descending_and_n_boundaries_return_all() {
        TickGapTracker t = new TickGapTracker(10, 64);
        t.recordTask("task-a", 100_000_000L, 1);
        t.recordTask("task-b", 50_000_000L, 2);
        // task-c 命中 3 次共 30ms, 排在 task-b 之后: 排序按累计而不是命中次数
        t.recordTask("task-c", 10_000_000L, 3);
        t.recordTask("task-c", 10_000_000L, 4);
        t.recordTask("task-c", 10_000_000L, 5);

        List<TickGapRecord> top = t.topTasksByTotalNs(3);
        assertEquals("task-a", top.get(0).label());
        assertEquals("task-b", top.get(1).label());
        assertEquals("task-c", top.get(2).label());
        assertEquals(30_000_000L, top.get(2).totalNs());
        assertEquals(3L, top.get(2).totalSamples());
        assertTrue(top.get(0).totalNs() > top.get(1).totalNs(), "排序必须严格降序");

        assertEquals(3, t.topTasksByTotalNs(0).size());
        assertEquals(3, t.topTasksByTotalNs(-1).size());
        assertEquals(3, t.topTasksByTotalNs(99).size(), "n > size 时返回全部");
        assertEquals(1, t.topTasksByTotalNs(1).size());
    }

    @Test
    void clear_resets_both_task_map_and_gap_record() {
        TickGapTracker t = new TickGapTracker(10, 64);
        t.recordGap(5_000_000_000L, 10);
        t.recordTask("task-a", 100L, 10);
        TickGapRecord before = t.gapRecord();
        assertEquals(1, t.taskCount());
        assertEquals(1, before.sampleCount());

        t.clear();

        assertEquals(0, t.taskCount(), "clear 必须清空 task map");
        TickGapRecord after = t.gapRecord();
        assertNotSame(before, after, "gapRecord 必须被整体替换成新实例, 而不是只清 map");
        assertEquals(0, after.sampleCount(), "clear 后 gapRecord 不得残留窗口样本");
        assertEquals(0L, after.totalSamples(), "clear 后 gapRecord 的历史累计也必须归零");
        assertEquals(0L, after.totalNs());
        assertEquals(TickGapTracker.GAP_LABEL, after.label());
    }

    @Test
    void concurrent_gap_and_task_records_do_not_lose_samples() throws InterruptedException {
        TickGapTracker t = new TickGapTracker(10_000, 64);
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
                        t.recordGap(1_000L, j);
                        t.recordTask("task-" + (j % 4), 2_000L, j);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "concurrent-tick-gap-record-" + i).start();
        }
        start.countDown();
        done.await();

        assertEquals(0, errors.get(), "并发 record 不应抛异常");
        assertEquals((long) threads * writesPerThread, t.gapRecord().totalSamples(),
                "所有 recordGap 必须被计入");
        assertEquals((long) threads * writesPerThread * 1_000L, t.gapRecord().totalNs());

        assertEquals(4, t.taskCount(), "4 个不同 label, trackLimit=64 不该驱逐");
        long taskSamples = 0L;
        long taskNs = 0L;
        for (TickGapRecord r : t.topTasksByTotalNs(0)) {
            taskSamples += r.totalSamples();
            taskNs += r.totalNs();
        }
        assertEquals((long) threads * writesPerThread, taskSamples, "所有 recordTask 必须被计入");
        assertEquals((long) threads * writesPerThread * 2_000L, taskNs);
    }

    @Test
    void invalid_construction_args_throw() {
        assertThrows(IllegalArgumentException.class, () -> new TickGapTracker(0, 64));
        assertThrows(IllegalArgumentException.class, () -> new TickGapTracker(-1, 64));
        assertThrows(IllegalArgumentException.class, () -> new TickGapTracker(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new TickGapTracker(100, -1));
    }

    @Test
    void window_size_and_track_limit_are_exposed_verbatim() {
        TickGapTracker t = new TickGapTracker(37, 11);
        assertEquals(37, t.windowSize());
        assertEquals(11, t.trackLimit());
        assertEquals(100, TickGapTracker.DEFAULT_WINDOW_SIZE);
        assertEquals("tick-gap", TickGapTracker.GAP_LABEL);
    }
}
