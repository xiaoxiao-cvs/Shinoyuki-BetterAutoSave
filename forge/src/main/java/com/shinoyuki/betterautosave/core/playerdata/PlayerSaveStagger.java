package com.shinoyuki.betterautosave.core.playerdata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 把 autosave 那一刻"一次性写完所有玩家"摊到随后的若干 tick 上。
 *
 * <p><b>问题形状</b>: {@code PlayerList.saveAll()} 是个裸 for 循环, 在
 * {@code tickCount % autosavePeriod == 0} 的那**一个** tick 里把所有人写完。60 人时按实测
 * 6.7ms/人 约 400ms 落在单 tick 上 —— 这是周期性尖峰而不是吞吐问题, 与 issue #25 同一定性。
 *
 * <p><b>为什么这是零数据安全回归</b>: 每个玩家仍然恰好每 {@code autosavePeriod} 存一次, 最大陈旧
 * 窗口一点没变, 只是各人的存盘时刻被错开。不引入线程、不改落盘时序、不碰 /save-off、不碰关服、
 * 不碰断线路径。前案有 Paper 的 "Incremental chunk and player saving" (2019 至今) 与 Sponge 的
 * {@code world.playerAutoSave.batch-*} (MIT) 两个生产级实现。
 *
 * <p><b>默认值刻意不抄 Paper</b>: Paper 的 {@code maxPerTick()} 在 stock 的 {@code rate=-1} 下
 * 实际返回 20 而不是文档暗示的 10 (判据读的是原始 rate, -1 既不等于 1 也不 > 100)。137 mod 包下
 * 20 人/tick ≈ 134ms, 直接爆预算。BAS 默认取 1-2。
 *
 * <p><b>线程模型</b>: 只在服务器主线程上被 {@code saveAll} 与 tick 钩子调用, 故用普通集合。
 */
public final class PlayerSaveStagger {

    /** 待存玩家。LinkedHashSet 保证先入先出且天然去重 (同一玩家不会排两次)。 */
    private final Set<UUID> pending = new LinkedHashSet<>();

    /** 本轮 autosave 一共排了多少人, 仅用于日志。 */
    private int lastBatchSize;

    /**
     * autosave 时把全部在线玩家排进待存队列。
     *
     * @return 排入的人数
     */
    public int enqueueAll(List<UUID> onlinePlayers) {
        pending.addAll(onlinePlayers);
        lastBatchSize = pending.size();
        return lastBatchSize;
    }

    /**
     * 取出至多 {@code max} 个待存玩家。
     *
     * @param max 每 tick 上限; 小于 1 时视为 1 (0 由调用方在更外层解释为"关闭错峰")
     */
    public List<UUID> takeUpTo(int max) {
        int n = Math.max(1, max);
        List<UUID> out = new ArrayList<>(Math.min(n, pending.size()));
        var it = pending.iterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    /** 取出全部待存玩家并清空 —— 用于 /save-all、关服等必须立刻写完的路径。 */
    public List<UUID> drainAll() {
        List<UUID> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /**
     * 玩家登出时移出待存队列。
     *
     * <p>必须做: vanilla 的 {@code PlayerList.remove} 自己会存一次该玩家, 若不移出, 随后的 tick
     * 会对一个已经 remove 掉的 ServerPlayer 再存一次 —— Sponge 的 {@code AutoSaveMapQueue.remove}
     * 就是为此存在。
     */
    public void remove(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }

    public int lastBatchSize() {
        return lastBatchSize;
    }

    public void clear() {
        pending.clear();
        lastBatchSize = 0;
    }
}
