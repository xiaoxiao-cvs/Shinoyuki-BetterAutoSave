package com.shinoyuki.betterautosave.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.io.AtomicFileWriter;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void betterautosave$atomicSave(CallbackInfo ci) {
        if (!BetterAutoSaveConfig.playerDataAtomicSidecarWrite()) {
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
        try {
            AtomicFileWriter.write(bytes, playerSavePath,
                    playerSavePath.resolveSibling(playerSavePath.getFileName() + ".bak"));
        } catch (IOException e) {
            // 与 vanilla 同级别的失败处理 (它也只 LOGGER.error)。原子写的好处是失败时目标文件
            // 仍是上一版完整内容, 而不是被截断的半截。
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] advancements 原子写失败: {}", playerSavePath, e);
        }
        ci.cancel();
    }
}
