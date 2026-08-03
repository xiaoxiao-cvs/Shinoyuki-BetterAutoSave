package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.leveldat.LevelDataIntegrity;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * level.dat 启动期预检、自动修复与冗余备份。
 *
 * <p><b>注入点为何是这里</b>: {@code net.minecraft.server.Main} 的调用序是
 * <pre>
 * :125 ServerModLoader.load()        &lt;- mod 构造 + config 加载 + EVENT_BUS.start() 全部完成
 * :141 validateAndCreateAccess       &lt;- DirectoryLock 已拿到, 目录独占无并发
 * :142 readAdditionalLevelSaveData() &lt;- 本注入点, 早于后面全部对 level.dat 的读
 * :170 getDataTag()                  &lt;- 抛 UncheckedIOException 的就是它
 * :209 saveDataTag()                 &lt;- vanilla 在开始 tick 之前就写一次, 并轮转掉 level.dat_old
 * </pre>
 * 挂在 :142 的 HEAD 意味着: 我们比任何一次读都早, 也比那次会销毁 {@code level.dat_old} 的写更早。
 * 此时 BAS 的 COMMON config 已完整加载 (config 加载属 mod loading 阶段, 在 :125 内跑完),
 * 因此不需要像 {@code LoadMixinGate} 那样裸读 toml。
 *
 * <p>目标方法是 Forge patch 新增的非混淆方法, 故 {@code remap = false}; 整方法 HEAD 注入不依赖
 * 方法体形状, 不需要 MixinConfigPlugin 门控。客户端 {@code WorldOpenFlows} 也调同一方法,
 * 一处 hook 双端覆盖。
 *
 * <p><b>目录不能用 {@code getWorldDir()}</b>: 那是 Forge 加的但 {@code return baseDir;} ——
 * 返回的是 saves 根目录而不是世界目录。必须用 {@code @Shadow} 的 {@code levelDirectory}。
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelDataIntegrityMixin {

    @Shadow
    @Final
    LevelStorageSource.LevelDirectory levelDirectory;

    @Inject(method = "readAdditionalLevelSaveData", at = @At("HEAD"), remap = false)
    private void betterautosave$verifyAndBackup(CallbackInfo ci) {
        if (!BetterAutoSaveConfig.enabled()) {
            return;
        }
        boolean verify = BetterAutoSaveConfig.levelDataVerifyOnStartup();
        boolean backup = BetterAutoSaveConfig.levelDataStartupBackup();
        if (!verify && !backup) {
            return;
        }
        Path levelDat = levelDirectory.dataFile();
        Path worldDir = levelDat.toAbsolutePath().getParent();
        if (worldDir == null) {
            return;
        }

        LevelDataIntegrity.Result result = LevelDataIntegrity.verify(levelDat);

        if (verify && !result.usable()) {
            betterautosave$attemptRepair(levelDat, worldDir, result);
            // 修复后重新评估, 决定还要不要备份。
            result = LevelDataIntegrity.verify(levelDat);
        }

        if (verify && result.usable() && !result.registriesPresent()) {
            // 只报警: vanilla 世界首次用 Forge 打开时本来就没有 fml 段, 不是故障。
            BetterAutoSaveMod.LOGGER.warn("[BetterAutoSave] level.dat 可读但不含 fml/Registries; "
                    + "若这是一个已经用 Forge 跑过的世界, 说明注册表 ID 表丢了, 加载时不会触发缺失映射检测");
        }

        if (backup && result.usable()) {
            try {
                Path dest = LevelDataIntegrity.backup(levelDat, worldDir, LevelDataIntegrity.KEEP_BACKUPS);
                BetterAutoSaveMod.LOGGER.info("[BetterAutoSave] level.dat 启动备份: {}", dest);
            } catch (Exception e) {
                BetterAutoSaveMod.LOGGER.warn("[BetterAutoSave] level.dat 启动备份失败 (不影响启动)", e);
            }
        }
    }

    /**
     * 自动修复只从 {@code level.dat_old} 走 —— 那最多退一个存盘周期。
     * BAS 自己的备份**绝不自动恢复**, 只把可用副本与精确命令打出来交给人。
     */
    private void betterautosave$attemptRepair(Path levelDat, Path worldDir,
                                              LevelDataIntegrity.Result result) {
        BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] level.dat 启动预检未通过: {} ({})",
                result.verdict(), result.detail());
        Path oldDat = levelDirectory.oldDataFile();
        try {
            Path corrupted = levelDirectory.corruptedDataFile(LocalDateTime.now());
            if (LevelDataIntegrity.restoreFromOld(levelDat, oldDat, corrupted)) {
                BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] 已用 level.dat_old 自动恢复 level.dat; "
                        + "损坏的原件隔离为 {}. 世界数据最多回退一个存盘周期, 注册表 ID 表不受影响. "
                        + "请在本次启动后确认世界时间 / 出生点 / gamerule 是否符合预期", corrupted.getFileName());
                return;
            }
        } catch (Exception e) {
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] 从 level.dat_old 恢复失败", e);
        }

        // 到这里: level.dat 坏且 level.dat_old 也不可用。不自动动 BAS 备份, 只给材料。
        try {
            List<Path> backups = LevelDataIntegrity.listBackups(worldDir.resolve(LevelDataIntegrity.BACKUP_DIR));
            if (backups.isEmpty()) {
                BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] level.dat 与 level.dat_old 均不可用, "
                        + "且没有 BAS 启动备份可用. 服务器很可能无法正常启动, 或以默认世界设置静默启动 —— "
                        + "启动后请立即确认世界种子与出生点");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Path p : backups) {
                sb.append("\n    cp \"").append(p.toAbsolutePath()).append("\" \"")
                        .append(levelDat.toAbsolutePath()).append('"');
            }
            // 刻意不自动执行: 回滚到启动时的副本会倒退世界时间 / 天气 / gamerule / 世界边界 /
            // 末影龙战 / 计划事件; 若期间 mod 集变过, 那份注册表 ID 表还会重映射整个世界的方块。
            // 这是运维决策不是机器决策。
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] level.dat 与 level.dat_old 均不可用. "
                    + "BAS 有 {} 份启动备份可用, 但**不会自动恢复** —— 回滚会倒退世界时间/天气/gamerule/"
                    + "世界边界/末影龙战, mod 集变过时还会重映射方块 ID. 请停服后人工执行其中一条:{}",
                    backups.size(), sb);
        } catch (Exception e) {
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] 列出 level.dat 备份失败", e);
        }
    }
}
