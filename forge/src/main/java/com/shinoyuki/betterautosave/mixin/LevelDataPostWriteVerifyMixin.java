package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.leveldat.LevelDataIntegrity;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * level.dat 写后回读校验 (阶段 2d)。
 *
 * <p><b>它解决的是"坏了多久才知道", 不是"坏了怎么救"</b>。启动预检
 * ({@code LevelDataIntegrityMixin}) 要等下一次启动才发现问题, 而那时 vanilla 可能已经把损坏件
 * 轮转进 {@code level.dat_old}、把最后一份好副本吃掉了。写后校验在损坏产生的那一刻就报警,
 * 此时 {@code level.dat_old} 还是完好的, 抢救窗口最大。
 *
 * <p><b>不占主线程</b>: 校验提交给 BAS 的 SavedData worker 队列执行。主线程只付一次任务提交,
 * 校验本身 (解压 1.2MB, 或再加一遍 NBT 解析) 全在 worker 上。
 *
 * <p><b>只读不修</b>: 发现问题只打 FATAL 级日志并提示可用副本, 绝不自动回滚 —— 此刻服务器正在
 * 运行, 用一份旧 level.dat 覆盖正在使用的世界元数据会造成比原问题更大的混乱。修复留到下次启动由
 * {@code LevelDataIntegrityMixin} 按既定判据处理。
 *
 * <p>注意本 mod <b>不接管</b> level.dat 的写盘 (异步写已论证否决), 所以这里校验的是 vanilla 自己
 * 刚写下的内容。这仍然有价值: 磁盘故障、文件系统问题、其它 mod 的干扰都可能让那次写出问题。
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelDataPostWriteVerifyMixin {

    @Shadow
    @Final
    LevelStorageSource.LevelDirectory levelDirectory;

    @Inject(method = "saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("RETURN"))
    private void betterautosave$verifyAfterWrite(RegistryAccess registries, WorldData worldData,
                                                 CompoundTag playerNbt, CallbackInfo ci) {
        LevelDataIntegrity.VerifyStrength strength = BetterAutoSaveConfig.levelDataPostWriteVerify();
        if (strength == LevelDataIntegrity.VerifyStrength.OFF) {
            return;
        }
        var pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || pipeline.isDegraded()) {
            // 降级时不再往队列里塞东西; 少一次校验远好过在无存活 worker 的队列里堆积。
            return;
        }
        Path levelDat = levelDirectory.dataFile();
        Path oldDat = levelDirectory.oldDataFile();
        pipeline.savedDataWorkerQueue().offer(new com.shinoyuki.betterautosave.core.leveldat.LevelDataVerifyTask(
                levelDat, oldDat, strength));
    }
}
