package com.shinoyuki.betterautosave.mixin;

import com.mojang.datafixers.DataFixer;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.playerdata.PlayerDataRecovery;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * playerdata 读侧回退 (Critical 数据安全修复).
 *
 * <p>vanilla 的 {@link PlayerDataStorage#load(Player)} 只读 {@code <uuid>.dat} 一个文件, 缺失或
 * 读失败时打一条无堆栈 WARN 后返回 null, {@code player.load} 根本不被调用 —— 玩家以新号上线,
 * 背包 / 末影箱 / 坐标 / 经验 / 全部 ForgeCaps mod 数据归零, 而 vanilla 自己刚写下的完好
 * {@code <uuid>.dat_old} 就在旁边从不被查阅, 并会被下一次 autosave 覆盖。详见
 * {@link PlayerDataRecovery}。
 *
 * <p><b>两个注入点各管一半, 都不取消 vanilla 逻辑</b>:
 * <ul>
 *   <li>HEAD —— 只处理"正本缺失"(落盘两次 rename 之间崩溃的窗口)。把备份复位成正本后放行,
 *       于是 datafixer、{@code player.load}、{@code PlayerLoadFromFile} 事件的调用顺序与 vanilla
 *       逐行一致, <b>零行为偏差</b>。代价只有一次 {@code exists()}, 正常登录路径无额外 IO。</li>
 *   <li>RETURN —— 兜"正本存在但内容不可读"。此时 vanilla 已经把玩家当成新号走完 (事件已发),
 *       我们隔离残骸、从备份恢复、补做 datafixer + {@code player.load}, 再把恢复出的 tag 作为
 *       返回值交回调用方 ({@code PlayerList} 会用它取 RootVehicle 等)。
 *       <b>已知行为偏差</b>: 这条路径上 {@code player.load} 发生在 {@code PlayerLoadFromFile}
 *       事件之后而非之前。仅在"文件已损坏"这条 vanilla 必然丢数据的路径上发生, 用一点顺序偏差
 *       换回整个存档是划算的。</li>
 * </ul>
 *
 * <p>两处都是整方法 HEAD/RETURN 注入, 不绑定方法体内任何 INVOKE, 因此不受第三方重写方法体影响
 * (与 {@code ForgeHooksLevelSaveMixin} 同一取舍)。
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageLoadMixin {

    @Shadow
    @Final
    private File playerDir;

    @Shadow
    @Final
    protected DataFixer fixerUpper;

    @Inject(method = "load", at = @At("HEAD"))
    private void betterautosave$restoreMissingPrimary(Player player, CallbackInfoReturnable<CompoundTag> cir) {
        if (!BetterAutoSaveConfig.playerDataLoadFallback()) {
            return;
        }
        PlayerDataRecovery.restoreMissingPrimary(playerDir, player.getStringUUID(),
                player.getName().getString());
    }

    @Inject(method = "load", at = @At("RETURN"), cancellable = true)
    private void betterautosave$recoverUnreadablePrimary(Player player, CallbackInfoReturnable<CompoundTag> cir) {
        if (!BetterAutoSaveConfig.playerDataLoadFallback()) {
            return;
        }
        if (cir.getReturnValue() != null) {
            return;
        }
        // 走到这里只有两种可能: (a) 正本不存在且 HEAD 没能复位 (无备份 = 真新玩家, 或备份也坏);
        // (b) 正本存在但 readCompressed 抛了。(a) 里的真新玩家会在 recoverUnreadablePrimary 里
        // 因无备份而返回 null, 与 vanilla 行为一致。
        CompoundTag recovered = PlayerDataRecovery.recoverUnreadablePrimary(playerDir,
                player.getStringUUID(), player.getName().getString());
        if (recovered == null) {
            return;
        }
        // 补做 vanilla 在 tag 非 null 分支里做的事, 参数与 vanilla 逐字对应。
        int dataVersion = NbtUtils.getDataVersion(recovered, -1);
        player.load(DataFixTypes.PLAYER.updateToCurrentVersion(fixerUpper, recovered, dataVersion));
        cir.setReturnValue(recovered);
    }
}
