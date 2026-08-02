package com.shinoyuki.betterautosave.core.leveldat;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 缓存 {@code ForgeHooks.writeAdditionalLevelSaveData} 产出的 {@code fml} CompoundTag,
 * 使 vanilla autosave 每 5 分钟重写 level.dat 时不必重建 Forge 注册表 ID 映射表.
 *
 * <p><b>为何值得缓存</b> (issue #25 生产实测): 137 mod 的服务器上 level.dat 解压后 1,234,370 B,
 * 其中 {@code fml/Registries} 占 1,215,091 B = 98.44% (17 个注册表 / 26,648 条 id).
 * 把相隔 5 分钟的两代 level.dat 解压后逐字节 diff: 1,234,370 B 中仅 5 个字节不同, 且全部落在
 * {@code /Data} 区间, {@code /fml} 那 1,222,341 B 逐字节完全相同 —— 每次 autosave 重建的
 * 内容 99% 是逐次一模一样的死数据. spark 实测该重建占每次 autosave 主线程 76ms/3 ≈ 25ms.
 *
 * <p><b>为何缓存是安全的</b>: 专用服上 {@code RegistryManager.ACTIVE} 在开始 tick 后恒定 ——
 * {@code injectSnapshot} 只有启动读档与客户端握手两个调用点, {@code add/addAlias/clear/remove}
 * 全部被 {@code isLocked()} 挡回, {@code /reload} 走的是 {@code RegistryAccess} 另一套容器.
 *
 * <p><b>三层失效</b> (缺一不可, 依次收窄):
 * <ol>
 *   <li>{@code IdMappingEvent} 订阅 -> {@link #invalidate}: 覆盖 Forge 官方全部三条 ID 变更路径
 *       ({@code freezeData} / {@code revertTo} / {@code injectSnapshot}).</li>
 *   <li>每次写盘前重采 {@link RegistryFingerprint}: 兜住 {@code ForgeRegistry.unfreeze()}
 *       这条 public 且不发事件的非官方路径.</li>
 *   <li>{@code registryCacheRevalidateCycles} 周期性强制重算并逐字段比对: 把"我论证过它不会变"
 *       变成"我在这台服上实测它没变过"的唯一手段, 兜住上面两层都不认识的未知路径.</li>
 * </ol>
 *
 * <p><b>缓存的 tag 直接交出去, 不做防御性 copy</b>: 交出去的实例会被 {@code NbtIo.writeCompressed}
 * 只读遍历, 用完即弃. 每次 lookup 都 copy 一份 1.2MB 的 NBT 树, 代价与重建同量级 —— 那正是
 * issue #12 踩过的主线程深拷贝坑. 残余风险 (第三方 mixin 在 saveDataTag 里 mutate 我们交出的
 * fml 子树) 由第 3 层周期性比对兜底.
 *
 * <p><b>线程模型</b>: 全部方法 synchronized. 调用频率是每 5 分钟一次 (写盘) 加极罕见的
 * IdMappingEvent, 锁必然无竞争, 代价可忽略; 换来的是不必推理"IdMappingEvent 到底在哪个线程发".
 */
public final class RegistryTagCache {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    /** 指纹采样器. 生产实现是 {@link RegistryFingerprint#capture}; 单测注入桩以摆脱 Forge 注册表依赖. */
    @FunctionalInterface
    public interface FingerprintSampler {
        RegistryFingerprint sample(Set<String> registryNames);
    }

    private final IntSupplier revalidateCyclesSupplier;
    private final FingerprintSampler sampler;

    private CompoundTag cachedFml;
    private RegistryFingerprint cachedFingerprint;
    private Set<String> registryNames = Collections.emptySet();

    /** 自上次全量重算以来的命中次数, 驱动第 3 层周期性重算. */
    private int cyclesSinceRevalidate;
    /** 本轮 lookup 是为了重算比对而故意放行的, store 时要拿新旧结果对拍. */
    private boolean revalidationPending;

    private long hits;
    private long builds;
    private long revalidations;
    private long mismatches;
    private long invalidations;

    public RegistryTagCache(IntSupplier revalidateCyclesSupplier, FingerprintSampler sampler) {
        this.revalidateCyclesSupplier = revalidateCyclesSupplier;
        this.sampler = sampler;
    }

    /** 生产构造: 指纹读真实的 {@code RegistryManager.ACTIVE}. */
    public static RegistryTagCache production(IntSupplier revalidateCyclesSupplier) {
        return new RegistryTagCache(revalidateCyclesSupplier, RegistryFingerprint::capture);
    }

    /**
     * 取可复用的 fml tag.
     *
     * @return 非 null 表示命中, 调用方直接 {@code levelTag.put("fml", it)} 并取消 vanilla 逻辑;
     *         null 表示必须放行 vanilla 重建, 且重建完必须回调 {@link #store}
     */
    public synchronized CompoundTag lookup() {
        if (cachedFml == null) {
            return null;
        }

        int cycles = revalidateCyclesSupplier.getAsInt();
        if (cycles > 0 && cyclesSinceRevalidate >= cycles) {
            // 故意放行让 vanilla 真算一遍, store() 会把结果与缓存对拍.
            // 注意不在这里清 cachedFml: 对拍需要旧值, 且万一 store 没被回调 (第三方 mixin 取消了
            // writeAdditionalLevelSaveData), 状态停在"每次都放行"而非"缓存被清后又重建", 更保守.
            if (!revalidationPending) {
                revalidationPending = true;
                revalidations++;
            }
            return null;
        }

        RegistryFingerprint now = sampler.sample(registryNames);
        if (!cachedFingerprint.matches(now)) {
            invalidate("注册表指纹变化 " + cachedFingerprint + " -> " + now);
            return null;
        }

        cyclesSinceRevalidate++;
        hits++;
        return cachedFml;
    }

    /**
     * vanilla 重建完 fml 之后回调, 接管为新缓存. 若本轮是周期性重算, 顺带与旧缓存对拍.
     *
     * @param freshFml vanilla 刚写进 levelTag 的 fml 子 tag
     */
    public synchronized void store(CompoundTag freshFml) {
        boolean wasRevalidation = revalidationPending;
        revalidationPending = false;

        if (freshFml == null || freshFml.isEmpty()) {
            // 理论不可达 (vanilla 恒写出非空 fml). 真发生时绝不能把空 tag 缓存下来 —— 那会让之后
            // 每次写盘都往 level.dat 塞一个空 fml, 静默丢掉整份注册表 ID 映射.
            LOGGER.warn("[BetterAutoSave] writeAdditionalLevelSaveData 产出的 fml 为空, 放弃缓存本轮结果");
            clearState();
            return;
        }

        Set<String> names = new LinkedHashSet<>(freshFml.getCompound("Registries").getAllKeys());
        if (names.isEmpty()) {
            LOGGER.warn("[BetterAutoSave] fml/Registries 为空, 放弃缓存本轮结果 (注册表尚未就绪?)");
            clearState();
            return;
        }

        if (wasRevalidation && cachedFml != null) {
            if (cachedFml.equals(freshFml)) {
                LOGGER.info("[BetterAutoSave] level.dat 注册表缓存周期性重算比对 PASS (registries={} entries={})",
                        cachedFingerprint == null ? -1 : cachedFingerprint.registryCount(),
                        cachedFingerprint == null ? -1 : cachedFingerprint.totalEntries());
            } else {
                mismatches++;
                // 采纳新值而非停用缓存: 新值就是 vanilla 本次真算出来的, 直接落盘正确. 但必须响亮报错,
                // 因为它意味着"注册表在运行期变了而三层失效的前两层都没认出来" —— 这是该关掉缓存的信号.
                LOGGER.error("[BetterAutoSave] level.dat 注册表缓存周期性重算比对 MISMATCH (第 {} 次): "
                        + "缓存内容与实时重建结果不一致, 已采纳实时结果. 这说明注册表在运行期发生了 "
                        + "IdMappingEvent 与指纹校验都未覆盖的变更; 若反复出现, 请把 "
                        + "levelData.cacheRegistrySnapshot 设为 false 并反馈 issue", mismatches);
            }
        }

        RegistryFingerprint fingerprint = sampler.sample(names);
        if (!fingerprint.allLocked()) {
            // 注册表处于可变态时坚决不缓存. 若此时缓存, 下一轮 matches() 必然失配 -> 每周期
            // invalidate + 重建, 日志刷屏且毫无收益; 不如干脆不进缓存态.
            LOGGER.warn("[BetterAutoSave] 注册表未全部处于 locked 态 ({}), 本轮不缓存 level.dat 注册表快照",
                    fingerprint);
            clearState();
            return;
        }

        cachedFml = freshFml;
        registryNames = Collections.unmodifiableSet(names);
        cachedFingerprint = fingerprint;
        cyclesSinceRevalidate = 0;
        builds++;
        LOGGER.info("[BetterAutoSave] level.dat 注册表快照已缓存 ({}); 后续 autosave 将复用, "
                + "每 {} 次命中强制重算比对一次", fingerprint, revalidateCyclesSupplier.getAsInt());
    }

    /** 外部失效入口 (IdMappingEvent / 配置关闭 / 关服). reason 进日志便于事后定位. */
    public synchronized void invalidate(String reason) {
        if (cachedFml == null) {
            return;
        }
        invalidations++;
        clearState();
        LOGGER.info("[BetterAutoSave] level.dat 注册表缓存已失效: {}", reason);
    }

    private void clearState() {
        cachedFml = null;
        cachedFingerprint = null;
        registryNames = Collections.emptySet();
        cyclesSinceRevalidate = 0;
        revalidationPending = false;
    }

    public synchronized boolean isPopulated() {
        return cachedFml != null;
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long builds() {
        return builds;
    }

    public synchronized long revalidations() {
        return revalidations;
    }

    public synchronized long mismatches() {
        return mismatches;
    }

    public synchronized long invalidations() {
        return invalidations;
    }
}
