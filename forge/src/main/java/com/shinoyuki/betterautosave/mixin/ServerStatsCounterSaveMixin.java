package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.io.AtomicFileWriter;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * stats 原子写 (Critical 数据安全修复), 与 {@link PlayerAdvancementsSaveMixin} 同一问题的另一半。
 *
 * <p>vanilla 的 {@link ServerStatsCounter#save()} 是
 * {@code FileUtils.writeStringToFile(this.file, this.toJson())} —— 就地截断覆写, 无临时文件、
 * 无备份。写到一半崩溃留下截断 JSON, 下次加载解析失败后统计归零。
 *
 * <p>与 advancements 那边不同的是, 这里的"复刻"只有一次 {@code toJson()} 调用, 没有任何
 * 数据变换逻辑, 因此 HEAD 取消的复刻面积几乎为零。已核对 1.20.1-47.3.22 的 Forge patch:
 * 该方法体内无 Forge 代码。
 *
 * <p>落盘字节与原版逐字节相同 (同一个 {@code toJson()} 产出, 同为 UTF-8), 只是改为一次性落位。
 */
@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterSaveMixin {

    @Shadow
    @Final
    private File file;

    @Shadow
    protected abstract String toJson();

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void betterautosave$atomicSave(CallbackInfo ci) {
        // 主开关承诺"关掉等于没装", 所有拦截点都必须兑现它, 否则运维关了主开关仍在跑 BAS 的代码。
        if (!BetterAutoSaveConfig.enabled() || !BetterAutoSaveConfig.playerDataAtomicSidecarWrite()) {
            return;
        }
        byte[] bytes;
        try {
            // vanilla 的 FileUtils.writeStringToFile(File, String) 单参重载用平台默认编码;
            // 这里显式 UTF-8。stats JSON 的 key 是注册表 id、value 是数字, 内容恒为 ASCII 子集,
            // 故两者产出的字节相同 —— 显式 UTF-8 只是消除对平台默认编码的隐性依赖。
            bytes = toJson().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] stats 序列化失败, 回退原版写盘路径: {}", file, t);
            return;
        }
        Path target = file.toPath();
        try {
            AtomicFileWriter.write(bytes, target,
                    target.resolveSibling(target.getFileName() + ".bak"),
                    BetterAutoSaveConfig.playerDataSidecarFsync());
        } catch (IOException e) {
            BetterAutoSaveMod.LOGGER.error("[BetterAutoSave] stats 原子写失败, 回退原版写盘路径: {}", file, e);
            // 不能 cancel: 失败可能发生在备份轮转之后、替换之前, 此刻正本已被移走。取消 vanilla 就没有
            // 任何东西再把它写回来, 下次启动 ServerStatsCounter 见不到文件会静默按全新统计加载。
            // 放行 vanilla 的截断写至少能把正本重建出来 —— 它自己也只是 LOGGER.error 不上抛。
            return;
        }
        ci.cancel();
    }
}
