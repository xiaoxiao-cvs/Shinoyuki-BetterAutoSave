package com.shinoyuki.betterautosave.core.leveldat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #25: level.dat 注册表快照缓存的三层失效硬门禁。
 *
 * <p>判定标准 (逐条对应一个必须存在的核心逻辑, 删掉即挂):
 * <ul>
 *   <li>{@link #fingerprint_change_invalidates_cache} —— 删掉 lookup 里的指纹比对即挂</li>
 *   <li>{@link #unlocked_registry_is_never_cached} —— 删掉 store 里的 allLocked 闸门即挂</li>
 *   <li>{@link #revalidation_detects_content_drift} —— 删掉周期性重算比对即挂</li>
 *   <li>{@link #empty_fml_is_never_cached} —— 删掉空 tag 闸门即挂 (这条一旦失守会静默写空注册表)</li>
 * </ul>
 */
class RegistryTagCacheTest {

    private static final String REG_BLOCK = "minecraft:block";
    private static final String REG_ITEM = "minecraft:item";

    /** 造一个形状与真实 fml 一致的 tag: fml/Registries/<name>/ids。entryCount 只影响内容, 不影响形状。 */
    private static CompoundTag fml(int blockIds, int itemIds) {
        CompoundTag registries = new CompoundTag();
        registries.put(REG_BLOCK, idsTag(blockIds));
        registries.put(REG_ITEM, idsTag(itemIds));
        CompoundTag root = new CompoundTag();
        root.put("Registries", registries);
        root.put("LoadingModList", new ListTag());
        return root;
    }

    private static CompoundTag idsTag(int count) {
        ListTag ids = new ListTag();
        for (int i = 0; i < count; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putString("K", "mod:entry_" + i);
            entry.putInt("V", i);
            ids.add(entry);
        }
        CompoundTag data = new CompoundTag();
        data.put("ids", ids);
        return data;
    }

    private static RegistryFingerprint fingerprint(int blockCount, int itemCount, boolean allLocked) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(REG_BLOCK, blockCount);
        counts.put(REG_ITEM, itemCount);
        return new RegistryFingerprint(counts, allLocked);
    }

    /** 指纹采样桩: 采样结果由外部随时改写, 模拟运行期注册表变化。 */
    private static final class StubSampler implements RegistryTagCache.FingerprintSampler {
        private final AtomicReference<RegistryFingerprint> next;

        StubSampler(RegistryFingerprint initial) {
            this.next = new AtomicReference<>(initial);
        }

        void set(RegistryFingerprint fp) {
            next.set(fp);
        }

        @Override
        public RegistryFingerprint sample(java.util.Set<String> registryNames) {
            return next.get();
        }
    }

    @Test
    void cold_lookup_misses_then_store_serves_same_instance() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);

        assertNull(cache.lookup(), "冷启动必须放行 vanilla");

        CompoundTag fresh = fml(10, 20);
        cache.store(fresh);
        assertTrue(cache.isPopulated());

        CompoundTag hit = cache.lookup();
        // 交出的必须是同一实例 —— 每次 lookup 做 1.2MB 深拷贝正是 issue #12 踩过的主线程尖峰。
        assertSame(fresh, hit, "命中必须直接交出缓存实例, 不做防御性 copy");
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.builds());
    }

    @Test
    void fingerprint_change_invalidates_cache() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);
        cache.store(fml(10, 20));
        assertNotNull(cache.lookup(), "指纹未变时应命中");

        // 模拟某 mod 调 public 的 ForgeRegistry.unfreeze() 后新增了注册项 —— 这条路径不发 IdMappingEvent,
        // 只有每次写盘前的指纹重采能发现。
        sampler.set(fingerprint(11, 20, true));

        assertNull(cache.lookup(), "注册表条目数变化必须使缓存失效并放行 vanilla");
        assertFalse(cache.isPopulated());
        assertEquals(1L, cache.invalidations());
    }

    @Test
    void unlocked_registry_invalidates_even_with_same_counts() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);
        cache.store(fml(10, 20));

        // 条目数一模一样, 但注册表被解冻 -> 处于可变态, 不能再支撑缓存。
        sampler.set(fingerprint(10, 20, false));

        assertNull(cache.lookup(), "注册表解冻后即使条目数不变也必须失效");
        assertFalse(cache.isPopulated());
    }

    @Test
    void unlocked_registry_is_never_cached() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, false));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);

        cache.store(fml(10, 20));

        assertFalse(cache.isPopulated(), "注册表未全部锁定时不得进入缓存态");
        assertEquals(0L, cache.builds());
        assertNull(cache.lookup());
    }

    @Test
    void empty_fml_is_never_cached() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);

        cache.store(new CompoundTag());
        assertFalse(cache.isPopulated(), "空 fml 进缓存会让之后每次写盘都丢掉整份注册表映射");

        // Registries 子 tag 缺失同样不得缓存。
        CompoundTag noRegistries = new CompoundTag();
        noRegistries.put("LoadingModList", new ListTag());
        cache.store(noRegistries);
        assertFalse(cache.isPopulated(), "缺 Registries 子 tag 时不得缓存");
        assertEquals(0L, cache.builds());
    }

    @Test
    void revalidation_forces_rebuild_after_configured_cycles() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 2, sampler);
        cache.store(fml(10, 20));

        assertNotNull(cache.lookup(), "第 1 次应命中");
        assertNotNull(cache.lookup(), "第 2 次应命中");
        assertNull(cache.lookup(), "达到 revalidateCycles 后必须放行 vanilla 重算");
        assertEquals(1L, cache.revalidations());
        // 重算期间缓存不清空 (store 未回调时保持保守态), 但也不再命中。
        assertTrue(cache.isPopulated());
    }

    @Test
    void revalidation_detects_content_drift() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 1, sampler);
        cache.store(fml(10, 20));

        assertNotNull(cache.lookup(), "第 1 次应命中");
        assertNull(cache.lookup(), "第 2 次进入重算");

        // vanilla 真算出来的内容与缓存不一致: 指纹骗过了前两层 (条目数没变), 只有内容对拍能抓到。
        CompoundTag drifted = fml(10, 20);
        drifted.getCompound("Registries").getCompound(REG_BLOCK).putString("injected", "by-some-mod");
        cache.store(drifted);

        assertEquals(1L, cache.mismatches(), "内容漂移必须被周期性重算比对抓到");
        assertSame(drifted, cache.lookup(), "MISMATCH 后必须采纳实时重建结果");
    }

    @Test
    void revalidation_pass_keeps_cache_and_reports_no_mismatch() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 1, sampler);
        cache.store(fml(10, 20));

        assertNotNull(cache.lookup());
        assertNull(cache.lookup(), "进入重算");
        cache.store(fml(10, 20));

        assertEquals(0L, cache.mismatches(), "内容一致时不得报 MISMATCH");
        assertNotNull(cache.lookup(), "重算通过后应恢复命中");
    }

    @Test
    void zero_revalidate_cycles_never_forces_rebuild() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);
        cache.store(fml(10, 20));

        for (int i = 0; i < 50; i++) {
            assertNotNull(cache.lookup(), "revalidateCycles=0 时不应强制重算");
        }
        assertEquals(0L, cache.revalidations());
    }

    @Test
    void explicit_invalidate_clears_and_is_idempotent() {
        StubSampler sampler = new StubSampler(fingerprint(10, 20, true));
        RegistryTagCache cache = new RegistryTagCache(() -> 0, sampler);
        cache.store(fml(10, 20));

        cache.invalidate("IdMappingEvent (frozen=true)");
        assertFalse(cache.isPopulated());
        assertEquals(1L, cache.invalidations());

        // 已空再调不得重复计数 (IdMappingEvent 在一次登录握手里可能连发)。
        cache.invalidate("再来一次");
        assertEquals(1L, cache.invalidations());
        assertNull(cache.lookup());
    }
}
