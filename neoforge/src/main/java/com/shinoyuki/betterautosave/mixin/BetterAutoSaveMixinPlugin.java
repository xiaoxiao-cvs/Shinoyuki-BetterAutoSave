package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.diagnostic.SyncLoadMixinGate;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * v0.20.1: 门控 {@code ServerChunkCacheSyncLoadMixin} 的应用, 与 forge 侧同名类对称。
 *
 * <p>本平台没有异步加载 (load 侧 mixin 是 forge 独有), 因此这里只有同步加载检测器这一条门控;
 * 判据全部落在 common 的 {@link SyncLoadMixinGate} 里, 双平台共用同一套规则与同一批单测。
 *
 * <p>触发条件与 forge 侧完全相同: 同装 {@code @Overwrite ServerChunkCache.getChunk} 的 mod 时,
 * Mixin 在优先级判定处抛 {@code InvalidInjectionException} 直接崩服, 该 mixin 上的 {@code require = 0}
 * 拦不住 —— 详见 {@link SyncLoadMixinGate} 的类注释与实测异常原文。1.21.1 上 Lithium 系同样在场,
 * 故必须对称处理, 不能因为崩溃报告来自 1.20.1 就只修一侧。
 */
public final class BetterAutoSaveMixinPlugin implements IMixinConfigPlugin {

    // 不用 BetterAutoSaveMod.LOGGER: 引用它会在类变换极早期触发 mod 主类加载。同类的 SERIES_CONFIG_DIR /
    // MOD_ID 是编译期常量, javac 内联后不留类引用, 故可继续用。
    private static final Logger LOGGER = LogManager.getLogger("BetterAutoSave");

    // 一次判定缓存: 每个 mixin 都会调 shouldApplyMixin, 避免重复读盘与重复遍历 mod 列表。
    private Boolean syncLoadApplicable;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (SyncLoadMixinGate.isSyncLoadMixin(mixinClassName)) {
            return isSyncLoadApplicable();
        }
        return true;
    }

    private boolean isSyncLoadApplicable() {
        Boolean cached = syncLoadApplicable;
        if (cached != null) {
            return cached;
        }
        boolean applicable = computeSyncLoadApplicable();
        syncLoadApplicable = applicable;
        return applicable;
    }

    private boolean computeSyncLoadApplicable() {
        if (!SyncLoadMixinGate.readSyncLoadDetection(configPath())) {
            LOGGER.info("[BetterAutoSave] sync chunk load detector is off in config; skipping its mixin");
            return false;
        }
        String conflict = SyncLoadMixinGate.firstConflictingModId(loadedModIds());
        if (conflict != null) {
            // warn 而不是 info: 用户装了 0.20 却看不到任何同步加载报告时, 这一行是唯一的解释。
            LOGGER.warn("[BetterAutoSave] sync chunk load detector disabled: mod '{}' overwrites "
                    + "ServerChunkCache.getChunk, which Mixin will not let us wrap. "
                    + "Async saving is unaffected.", conflict);
            return false;
        }
        return true;
    }

    /**
     * 类变换期能拿到的 mod id。必须用 {@code LoadingModList} 而不是 {@code ModList} —— 后者要到 mod 构造
     * 之后才建好, 在这里取是 null。
     */
    private static List<String> loadedModIds() {
        try {
            List<String> ids = new ArrayList<>();
            LoadingModList.get().getMods().forEach(info -> ids.add(info.getModId()));
            return ids;
        } catch (Throwable t) {
            // 这里吞异常是刻意的: 拿不到 mod 列表时唯一安全的选择是按"无冲突"继续 (维持 0.20.0 行为),
            // 让崩溃与否交回 Mixin 判定, 而不是因为探测失败就静默关掉一个正常功能。
            LOGGER.warn("[BetterAutoSave] could not read the loading mod list ({}); "
                    + "sync chunk load detector will be applied as usual", t.toString());
            return List.of();
        }
    }

    private static Path configPath() {
        try {
            return FMLPaths.CONFIGDIR.get()
                    .resolve(BetterAutoSaveMod.SERIES_CONFIG_DIR)
                    .resolve(BetterAutoSaveMod.MOD_ID)
                    .resolve("common.toml");
        } catch (Throwable t) {
            // FMLPaths 未就绪的极端早期: 退回相对 game 目录的 config/ (dedicated server CWD = game 目录)。
            return Path.of("config", BetterAutoSaveMod.SERIES_CONFIG_DIR, BetterAutoSaveMod.MOD_ID, "common.toml");
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
