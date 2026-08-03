package com.shinoyuki.betterautosave.core.playerdata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家存盘错峰队列的硬门禁。
 *
 * <p>判定标准 (删掉对应逻辑即挂):
 * <ul>
 *   <li>{@link #every_enqueued_player_is_eventually_taken} —— 这是本功能的核心不变式:
 *       排进去的人一个都不能漏。漏一个 = 那个玩家这一周期没落盘</li>
 *   <li>{@link #logged_out_player_is_dropped} —— 删掉 remove 即挂 (会对已 remove 的玩家再存一次)</li>
 *   <li>{@link #drain_all_empties_queue} —— /save-all 与关服路径靠它把积压写完</li>
 *   <li>{@link #enqueue_is_idempotent_within_a_cycle} —— 同一玩家不得排两次</li>
 * </ul>
 */
class PlayerSaveStaggerTest {

    private static List<UUID> uuids(int n) {
        List<UUID> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new UUID(0L, i));
        }
        return out;
    }

    @Test
    void every_enqueued_player_is_eventually_taken() {
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        List<UUID> all = uuids(60);
        assertEquals(60, stagger.enqueueAll(all));

        List<UUID> seen = new ArrayList<>();
        int guard = 0;
        while (!stagger.isEmpty() && guard++ < 1000) {
            seen.addAll(stagger.takeUpTo(2));
        }

        assertTrue(stagger.isEmpty(), "队列必须能被排空");
        assertEquals(60, seen.size(), "排进去的 60 个人必须全部被取出 —— 漏一个就是那人这周期没落盘");
        assertTrue(seen.containsAll(all));
        assertEquals(30, guard, "60 人 / 每 tick 2 个 = 30 个 tick, 远小于 6000 tick 的周期");
    }

    @Test
    void take_up_to_respects_limit_and_preserves_order() {
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        stagger.enqueueAll(uuids(5));

        List<UUID> first = stagger.takeUpTo(2);
        assertEquals(2, first.size());
        assertEquals(new UUID(0L, 0), first.get(0), "先入先出");
        assertEquals(new UUID(0L, 1), first.get(1));
        assertEquals(3, stagger.size());
    }

    @Test
    void take_up_to_zero_still_takes_one() {
        // 上层用 0 表示"关闭错峰", 走的是 drainAll 而不是这里; 真传进来 0 时不能死锁在空转。
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        stagger.enqueueAll(uuids(3));
        assertEquals(1, stagger.takeUpTo(0).size());
    }

    @Test
    void logged_out_player_is_dropped() {
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        stagger.enqueueAll(uuids(3));

        stagger.remove(new UUID(0L, 1));

        List<UUID> taken = stagger.takeUpTo(10);
        assertEquals(2, taken.size(), "登出的玩家必须移出队列 —— vanilla 的 remove 已经存过它了");
        assertFalse(taken.contains(new UUID(0L, 1)));
    }

    @Test
    void drain_all_empties_queue() {
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        stagger.enqueueAll(uuids(7));

        List<UUID> all = stagger.drainAll();

        assertEquals(7, all.size(), "/save-all 与关服必须能一次把积压写完");
        assertTrue(stagger.isEmpty());
    }

    @Test
    void enqueue_is_idempotent_within_a_cycle() {
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        stagger.enqueueAll(uuids(3));
        stagger.enqueueAll(uuids(3));

        assertEquals(3, stagger.size(), "同一玩家不得排两次, 否则一个周期内会被存两遍");
    }

    @Test
    void take_from_empty_queue_is_safe() {
        PlayerSaveStagger stagger = new PlayerSaveStagger();
        assertTrue(stagger.takeUpTo(4).isEmpty());
        assertTrue(stagger.drainAll().isEmpty());
    }
}
