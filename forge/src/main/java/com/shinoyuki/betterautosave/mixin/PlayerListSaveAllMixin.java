package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.playerdata.PlayerListSaveAccess;
import com.shinoyuki.betterautosave.core.playerdata.PlayerSaveStagger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家存盘分批错峰 (阶段 2c)。
 *
 * <p>{@code PlayerList.saveAll()} 是个裸 for 循环, 在 autosave 那一个 tick 里把所有人写完。
 * 60 人时按实测约 400ms 落在单 tick 上。本 mixin 把它排进 {@link PlayerSaveStagger},
 * 由 {@code MinecraftServerMixin} 的 tick 钩子每 tick 取几个出来存。
 *
 * <p><b>只在 autosave 路径错峰</b>。判据来自 {@code MinecraftServer.saveEverything} 的
 * {@code flush}/{@code forced} 两个参数 (由 {@code MinecraftServerMixin} 记录窗口):
 * <ul>
 *   <li>autosave = {@code saveEverything(true, false, false)} -> 允许错峰</li>
 *   <li>{@code /save-all} = forced=true -> 不错峰</li>
 *   <li>{@code /save-all flush} = flush=true -> 不错峰</li>
 *   <li>关服 {@code stopServer} 直接调 {@code playerList.saveAll()} 而不经 saveEverything ->
 *       窗口标志未置位 -> 不错峰</li>
 * </ul>
 * 任何非 autosave 路径进来时, 先把队列里积压的人同步写完再走 vanilla 全量循环, 保证
 * "{@code /save-all} 之后所有人都已落盘"这条语义不变。
 *
 * <p>vanilla {@code save(ServerPlayer)} 首行的 Forge 补丁
 * {@code if (pPlayer.connection == null) return;} 用于跳过 FakePlayer —— 本 mixin 全程走
 * {@code save(...)} 而不是自己拼落盘逻辑, 该早退自然保留。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListSaveAllMixin implements PlayerListSaveAccess {

    @Shadow
    @Final
    private List<ServerPlayer> players;

    @Shadow
    protected abstract void save(ServerPlayer player);

    @Inject(method = "saveAll", at = @At("HEAD"), cancellable = true)
    private void betterautosave$stagger(CallbackInfo ci) {
        PlayerSaveStagger stagger = BetterAutoSaveCore.playerSaveStagger();
        if (stagger == null) {
            return;
        }
        int maxPerTick = BetterAutoSaveConfig.playerDataStaggerMaxPerTick();
        boolean inAutosave = BetterAutoSaveCore.isInAutosaveWindow();

        if (maxPerTick <= 0 || !inAutosave) {
            // 非错峰路径 (/save-all, 关服, 或功能关闭): 先把积压写完再放行 vanilla 全量循环。
            // 不这么做的话, /save-all 返回时可能仍有玩家停在队列里没落盘。
            betterautosave$flushPending(stagger);
            return;
        }

        List<UUID> uuids = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            uuids.add(player.getUUID());
        }
        stagger.enqueueAll(uuids);
        // 本 tick 先做一批, 剩下的交给 tick 钩子, 避免"排完队但这一刻一个都没存"。
        betterautosave$saveBatch(stagger.takeUpTo(maxPerTick));
        ci.cancel();
    }

    /**
     * 玩家登出时把它移出待存队列。vanilla 的 remove 自己会存一次, 若不移出, 随后的 tick 会对一个
     * 已被 remove 的 ServerPlayer 再存一次。
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void betterautosave$dropFromStagger(ServerPlayer player, CallbackInfo ci) {
        PlayerSaveStagger stagger = BetterAutoSaveCore.playerSaveStagger();
        if (stagger != null) {
            stagger.remove(player.getUUID());
        }
    }

    @Unique
    private void betterautosave$flushPending(PlayerSaveStagger stagger) {
        if (!stagger.isEmpty()) {
            betterautosave$saveBatch(stagger.drainAll());
        }
    }

    /** 供 tick 钩子调用: 存一批待存玩家。 */
    @Override
    public void betterautosave$saveBatch(List<UUID> uuids) {
        for (UUID uuid : uuids) {
            ServerPlayer player = betterautosave$findOnline(uuid);
            if (player != null) {
                save(player);
            }
            // 找不到 = 已登出, vanilla 的 remove 已经存过, 跳过即可。
        }
    }

    @Unique
    private ServerPlayer betterautosave$findOnline(UUID uuid) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }
}
