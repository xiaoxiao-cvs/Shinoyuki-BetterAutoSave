package com.shinoyuki.betterautosave.core.leveldat;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code RegistryManager.ACTIVE} 里持久化注册表的廉价指纹, 用于判定
 * {@link RegistryTagCache} 缓存的 fml tag 是否仍与当前注册表状态一致.
 *
 * <p><b>为何需要它</b>: {@code IdMappingEvent} 覆盖 Forge 官方的三条 ID 变更路径
 * (freezeData / revertTo / injectSnapshot), 但 {@code ForgeRegistry.unfreeze()} 是 public
 * 且**不发任何事件** —— 那是绕过事件钩子唯一的合法入口. 指纹在每次写盘前重采一次,
 * 兜住"某 mod 自己 unfreeze 后改了注册表"这条非官方路径.
 *
 * <p><b>为何够便宜</b>: 只做 N 次 map 查表 + N 次 {@code Set.size()} (N = 持久化注册表数,
 * 生产实测 17), 全部 O(1). 与它要替代的 {@code takeSnapshot} (遍历 26,648 条 id 重建红黑树)
 * 差三个数量级, 可以每次写盘无条件跑.
 *
 * <p><b>覆盖不到的残余</b>: 若某个持久化注册表是在第一次写 level.dat **之后**才被创建的,
 * 它不在 {@link #capture} 的 names 集合里, 指纹不会察觉. Forge 的注册表创建走
 * {@code NewRegistryEvent}, 发生在 mod 加载期 (早于服务器启动, 也早于任何一次 level.dat 写),
 * 故实际不可达; 真要兜住只能重算 takeSnapshot, 那就没有缓存了. 由
 * {@code registryCacheRevalidateCycles} 的周期性重算比对做最终兜底.
 */
public final class RegistryFingerprint {

    /** 注册表名 -> 条目数. 用 LinkedHashMap 保序只为日志可读, 比较走 Map.equals 与顺序无关. */
    private final Map<String, Integer> entryCounts;

    /**
     * 采样时所有目标注册表是否都存在且处于 locked (frozen) 态.
     * 任一注册表缺失或被 unfreeze 都置 false, 使 {@link #matches} 恒不成立 —— 宁可退回
     * vanilla 重算, 不拿一个"处于可变状态的注册表"去支撑缓存.
     */
    private final boolean allLocked;

    public RegistryFingerprint(Map<String, Integer> entryCounts, boolean allLocked) {
        this.entryCounts = Collections.unmodifiableMap(new LinkedHashMap<>(entryCounts));
        this.allLocked = allLocked;
    }

    /**
     * 读当前 {@code RegistryManager.ACTIVE} 采一份指纹.
     *
     * @param registryNames 目标注册表名 (从上次缓存的 fml/Registries 子 tag 的 key 集得来)
     */
    public static RegistryFingerprint capture(Set<String> registryNames) {
        Map<String, Integer> counts = new LinkedHashMap<>(registryNames.size());
        boolean allLocked = true;
        for (String name : registryNames) {
            ResourceLocation key = ResourceLocation.tryParse(name);
            // 名字来自我们上次自己写出的 tag, 解析失败属不可能; 真发生就当作指纹失配处理.
            ForgeRegistry<?> registry = key == null ? null : RegistryManager.ACTIVE.getRegistry(key);
            if (registry == null) {
                allLocked = false;
                counts.put(name, -1);
                continue;
            }
            if (!registry.isLocked()) {
                allLocked = false;
            }
            counts.put(name, registry.getKeys().size());
        }
        return new RegistryFingerprint(counts, allLocked);
    }

    /**
     * 与另一份指纹比对. 只有"双方都处于全 locked 态且条目数逐项相等"才算匹配.
     *
     * <p>刻意不用 equals/hashCode: 这里的语义是"能否据此复用缓存", 不是值相等 ——
     * 两次都 unlocked 的指纹在值上相等, 但都不该支撑缓存复用.
     */
    public boolean matches(RegistryFingerprint other) {
        if (other == null || !this.allLocked || !other.allLocked) {
            return false;
        }
        return this.entryCounts.equals(other.entryCounts);
    }

    public boolean allLocked() {
        return allLocked;
    }

    public int registryCount() {
        return entryCounts.size();
    }

    /** 全部注册表条目数之和, 仅供日志展示. */
    public int totalEntries() {
        int sum = 0;
        for (Integer v : entryCounts.values()) {
            if (v > 0) {
                sum += v;
            }
        }
        return sum;
    }

    @Override
    public String toString() {
        return "RegistryFingerprint[registries=" + entryCounts.size()
                + ", entries=" + totalEntries()
                + ", allLocked=" + allLocked + "]";
    }
}
