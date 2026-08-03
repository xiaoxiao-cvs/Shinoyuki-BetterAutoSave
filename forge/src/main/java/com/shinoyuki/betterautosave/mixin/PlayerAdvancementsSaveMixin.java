package com.shinoyuki.betterautosave.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.io.AtomicFileWriter;
import com.shinoyuki.betterautosave.core.playerdata.AdvancementsSkipPolicy;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * advancements 原子写 (Critical 数据安全修复).
 *
 * <p>vanilla 的 {@link PlayerAdvancements#save()} 用
 * {@code Files.newBufferedWriter(path)} (CREATE + TRUNCATE_EXISTING) 打开唯一一份副本, 先截断再
 * 流式写入, <b>没有临时文件、没有备份</b>。对照 {@code playerdata/<uuid>.dat} 走的是
 * {@code Util.safeReplaceFile} 且留 {@code .dat_old} —— 任意世界目录都能看到
 * {@code playerdata/} 里一堆 {@code .dat_old} 而 {@code advancements/} 一个备份都没有。
 *
 * <p>写到一半崩溃就留下截断的 JSON。下次登录解析失败,
 * {@code catch (JsonParseException) { LOGGER.error }} 之后<b>继续加载</b>, 玩家带空进度上线,
 * 再下一次 autosave 把这份空进度写回文件, 永久坐实。大整合包下该文件可达几百 KB, 写窗口不小。
 *
 * <p><b>本 mixin 不改变落盘字节</b>, 只改变落盘方式: 先写 {@code .bastmp} 并 fsync, 再原子替换,
 * 并把上一代轮转成 {@code .bak}。备份 mod 看到的内容与原版逐字节相同。
 *
 * <p><b>为何 HEAD 取消并复刻 JSON 构建</b>: 只替换写盘那一段需要绑定方法体内
 * {@code Files.newBufferedWriter} 那条 INVOKE, 在 {@code defaultRequire=1} 下一旦被第三方重写就是
 * 启动期硬崩。复刻的是 vanilla 的 6 行纯数据变换 (过滤 hasProgress + toJsonTree + 加 DataVersion),
 * 无副作用、无事件、无 Forge 注入 (已核对 1.20.1-47.3.22 的 Forge patch: 该方法体内零 Forge 代码),
 * 因此取消它不会跳过任何第三方逻辑。<b>本复刻绑定 1.20.1</b>, 跨版本移植时必须重新核对方法体。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsSaveMixin {

    @Shadow
    @Final
    private static Gson GSON;

    @Shadow
    @Final
    private Path playerSavePath;

    @Shadow
    @Final
    private Map<Advancement, AdvancementProgress> progress;

    /**
     * 自上次成功写盘以来 progress 是否被改动过。初值 true 保证进程内第一次 save 必然全量写。
     *
     * <p><b>绝不能复用 vanilla 的 {@code progressChanged}</b>: 那个集合被
     * {@code flushDirty(ServerPlayer)} 每 tick 清空 ({@code ServerPlayer.tick} 每 tick 调它),
     * autosave 时几乎恒为空 —— 拿它当写盘脏标志会把"确实变了"的保存也跳掉, 静默丢进度。
     *
     * <p>置位点只有 award / revoke / reload / load 四处, 与 {@code this.progress} 的真实改写点
     * 一一对应。第三方绕过这四处直接改 progress 的路径由 forceFullWriteCycles 兜。
     */
    @Unique
    private boolean betterautosave$progressDirty = true;

    /** 自上次全量写盘以来连续跳过的次数, 驱动周期性强制全写。 */
    @Unique
    private int betterautosave$cyclesSinceFullWrite;

    /** 上次落盘内容的 SHA-256, 仅 AUDIT 模式用于对拍。 */
    @Unique
    private byte[] betterautosave$lastWrittenDigest;

    /** AUDIT 模式累计抓到的矛盾次数, 进日志便于判断能否翻 ON。 */
    @Unique
    private int betterautosave$auditMismatches;

    @Inject(method = "award", at = @At("RETURN"))
    private void betterautosave$markDirtyOnAward(Advancement advancement, String criterionKey,
                                                 CallbackInfoReturnable<Boolean> cir) {
        // 只有真的 grantProgress 成功才返回 true; 每 tick 触发的 CriteriaTriggers.TICK 走到这里
        // 恒返回 false, 故不会把标志永久钉成脏。
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            betterautosave$progressDirty = true;
        }
    }

    @Inject(method = "revoke", at = @At("RETURN"))
    private void betterautosave$markDirtyOnRevoke(Advancement advancement, String criterionKey,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            betterautosave$progressDirty = true;
        }
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void betterautosave$markDirtyOnReload(ServerAdvancementManager manager, CallbackInfo ci) {
        betterautosave$progressDirty = true;
    }

    /**
     * 从磁盘读完也置脏。看似多余 (读完内存与磁盘一致), 但 load 会跑
     * {@code DataFixTypes.ADVANCEMENTS.updateToCurrentVersion} —— 跨版本升级时序列化产物与磁盘上
     * 那份并不相同, 必须落盘一次。代价是每次登录多写一次, 换的是升级不丢。
     */
    @Inject(method = "load", at = @At("RETURN"))
    private void betterautosave$markDirtyOnLoad(ServerAdvancementManager manager, CallbackInfo ci) {
        betterautosave$progressDirty = true;
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void betterautosave$atomicSave(CallbackInfo ci) {
        ConfigSpec.AdvancementsSkipMode skipMode = BetterAutoSaveConfig.playerDataAdvancementsSkipMode();
        boolean atomic = BetterAutoSaveConfig.playerDataAtomicSidecarWrite();
        if (!atomic && skipMode == ConfigSpec.AdvancementsSkipMode.OFF) {
            return;
        }

        AdvancementsSkipPolicy.Decision decision = AdvancementsSkipPolicy.decide(
                skipMode, betterautosave$progressDirty, betterautosave$cyclesSinceFullWrite,
                BetterAutoSaveConfig.playerDataAdvancementsForceFullWriteCycles());
        if (decision == AdvancementsSkipPolicy.Decision.SKIP) {
            betterautosave$cyclesSinceFullWrite++;
            ci.cancel();
            return;
        }

        byte[] bytes;
        try {
            // 与 vanilla save() 的前半段逐行等价。
            Map<ResourceLocation, AdvancementProgress> map = new LinkedHashMap<>();
            for (Map.Entry<Advancement, AdvancementProgress> entry : progress.entrySet()) {
                AdvancementProgress advancementProgress = entry.getValue();
                if (advancementProgress.hasProgress()) {
                    map.put(entry.getKey().getId(), advancementProgress);
                }
            }
            JsonElement json = GSON.toJsonTree(map);
            json.getAsJsonObject().addProperty("DataVersion",
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 序列化阶段抛 (第三方 AdvancementProgress 实现异常等): 放行 vanilla, 由它按原逻辑处理。
            // 不能在这里吞掉, 否则该玩家的进度这一轮完全不落盘且无人知晓。
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] advancements 序列化失败, 回退原版写盘路径: {}",
                    playerSavePath, t);
            return;
        }

        byte[] digest = betterautosave$sha256(bytes);
        if (decision == AdvancementsSkipPolicy.Decision.WRITE_AUDIT
                && betterautosave$lastWrittenDigest != null
                && !Arrays.equals(digest, betterautosave$lastWrittenDigest)) {
            betterautosave$auditMismatches++;
            // 脏标志说"没变"但内容确实变了 = 有改动路径绕过了 award/revoke/load/reload。
            // 这正是 AUDIT 模式存在的意义: 在翻 ON 之前把它抓出来。
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] advancements 脏跳过审计 MISMATCH (第 {} 次): {} "
                    + "的内容变了但脏标志显示未变, 说明有改动路径绕过了 award/revoke/load/reload。"
                    + "请保持 advancementsSkipMode=AUDIT 不要翻 ON, 并反馈 issue",
                    betterautosave$auditMismatches, playerSavePath);
        }

        if (atomic) {
            try {
                AtomicFileWriter.write(bytes, playerSavePath,
                        playerSavePath.resolveSibling(playerSavePath.getFileName() + ".bak"));
            } catch (IOException e) {
                // 与 vanilla 同级别的失败处理 (它也只 LOGGER.error)。原子写的好处是失败时目标文件
                // 仍是上一版完整内容, 而不是被截断的半截。
                BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] advancements 原子写失败: {}", playerSavePath, e);
                // 写失败不清脏标志: 下一周期必须重试, 否则这次改动永远不落盘。
                return;
            }
        }

        betterautosave$progressDirty = false;
        betterautosave$cyclesSinceFullWrite = 0;
        betterautosave$lastWrittenDigest = digest;
        if (atomic) {
            // 已由本 mixin 写完, 取消 vanilla 的截断写。atomic 关闭时不取消, 让 vanilla 自己写
            // (此时本 mixin 只提供脏跳过, 不接管落盘)。
            ci.cancel();
        }
    }

    @Unique
    private static byte[] betterautosave$sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法, 不可达。真发生就退化为"永不相等", 使 AUDIT 只会误报不会漏报。
            return null;
        }
    }
}
