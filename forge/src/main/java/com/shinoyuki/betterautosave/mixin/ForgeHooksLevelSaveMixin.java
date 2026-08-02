package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.leveldat.RegistryTagCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * issue #25: 拦 {@link ForgeHooks#writeAdditionalLevelSaveData} 复用缓存的 fml tag,
 * 消除 vanilla autosave 每 5 分钟重建 Forge 注册表 ID 映射表的主线程开销.
 *
 * <p><b>为何是 level.dat 而不是 SavedData</b>: 调用链是
 * {@code MinecraftServer.tickServer -> saveEverything -> saveAllChunks ->
 * LevelStorageAccess.saveDataTag -> ForgeHooks.writeAdditionalLevelSaveData}, 写的是
 * {@code <world>/level.dat}. BAS 的 {@code DimensionDataStorageMixin} 拦的是
 * {@code <world>/data/*.dat} (SavedData), 与本路径零重叠 —— 这条尖峰一直是 BAS 覆盖不到的盲区.
 *
 * <p><b>为何 hook 整方法而不是内部 INVOKE</b>: {@code @Redirect} 到方法体内的
 * {@code RegistryManager.ACTIVE.takeSnapshot(true)} 只能拦住实测成本的同一段, 却把注入点绑死在
 * 一条 INVOKE 上 —— 一旦别的 mod 重写该方法体, {@code defaultRequire=1} 会在启动期硬崩
 * (这正是 load 侧 mixin 需要 {@code LoadMixinGate} 门控的原因). HEAD/RETURN 的整方法
 * {@code @Inject} 不依赖方法体形状, 无需 MixinConfigPlugin 门控, 因而支持配置热重载.
 *
 * <p><b>命中时取消 vanilla 逻辑的代价</b>: 同时也跳过了其它 mod 注入到
 * {@code writeAdditionalLevelSaveData} 的逻辑. 该方法是 {@code @ApiStatus.Internal} 且仅被
 * {@code saveDataTag} 调用一处, 实际不存在已知的第三方注入; 且缓存内容本就是从"co-injector 全跑过
 * 一遍"的那次结果里摘下来的, 只要它们的产出恒定就等价. 若某个 co-injector 产出随时间变化,
 * {@code registryCacheRevalidateCycles} 的周期性重算比对会把它作为 MISMATCH 报出来.
 *
 * <p><b>三处必须放行 vanilla 的场景</b>, 全部由 {@code cache == null} 这一个判据覆盖 ——
 * {@link BetterAutoSaveCore} 只在 {@code ServerStartingEvent} 装载、{@code ServerStoppingEvent} 卸载:
 * <ul>
 *   <li>{@code net.minecraft.server.Main} 在服务器开始 tick **之前**的那次 saveDataTag
 *       (此时 MinecraftServer 尚未创建)</li>
 *   <li>客户端 {@code OptimizeWorldScreen} 跑在 world-optimize 工作线程上的写盘</li>
 *   <li>关服卸载之后到进程退出之间的任何写盘</li>
 * </ul>
 */
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class ForgeHooksLevelSaveMixin {

    @Inject(method = "writeAdditionalLevelSaveData",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void betterautosave$reuseCachedRegistryTag(WorldData worldData,
                                                             CompoundTag levelTag,
                                                             CallbackInfo ci) {
        RegistryTagCache cache = BetterAutoSaveCore.registryTagCache();
        if (cache == null) {
            return;
        }
        if (!BetterAutoSaveConfig.enabled() || !BetterAutoSaveConfig.levelDataCacheRegistrySnapshot()) {
            // 运行期热关闭时把缓存丢掉: 否则重新打开后会拿一份可能已过期的旧 tag 直接命中,
            // 而关闭期间发生的注册表变更没有任何一层失效逻辑在跑.
            cache.invalidate("levelData.cacheRegistrySnapshot 已关闭");
            return;
        }
        CompoundTag cached = cache.lookup();
        if (cached == null) {
            return;
        }
        levelTag.put("fml", cached);
        ci.cancel();
    }

    @Inject(method = "writeAdditionalLevelSaveData",
            at = @At("RETURN"),
            remap = false)
    private static void betterautosave$captureRegistryTag(WorldData worldData,
                                                          CompoundTag levelTag,
                                                          CallbackInfo ci) {
        // 只有 HEAD 未命中放行时才会走到这里 (命中路径已 ci.cancel()), 故这里拿到的恒是
        // vanilla 本次真算出来的结果.
        RegistryTagCache cache = BetterAutoSaveCore.registryTagCache();
        if (cache == null) {
            return;
        }
        if (!BetterAutoSaveConfig.enabled() || !BetterAutoSaveConfig.levelDataCacheRegistrySnapshot()) {
            return;
        }
        cache.store(levelTag.getCompound("fml"));
    }
}
