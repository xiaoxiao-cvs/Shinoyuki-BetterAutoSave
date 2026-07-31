package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.core.Holder;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * 单个 section 的落盘原料 —— 只装 worker 编码 {@code sections} 真正要读的两样东西。
 *
 * <p><b>为何不包成 {@link net.minecraft.world.level.chunk.LevelChunkSection}</b>: 它吃两个容器的构造器
 * 无条件调 {@code recalcBlockCounts()}, 对每个 palette 多于一项的 section 遍历 4096 格喂
 * {@code Int2IntOpenHashMap}。而 {@code nonEmptyBlockCount / tickingBlockCount / tickingFluidCount}
 * 只服务活区块的 tick 调度与网络包, vanilla {@code ChunkSerializer.write} 序列化 section 时一个都不读 ——
 * 计数全错的副本序列化出的字节与正确副本逐字节相同。包一层 LevelChunkSection 等于每次 capture 在主线程
 * 白扫整块地形。
 *
 * <p><b>{@code preEncodedBiomes} 的存在理由</b>: {@link PalettedContainerRO} 接口没有 {@code copy()},
 * 只有 {@link PalettedContainer} 实现类有。活 section 的 biomes 若是第三方只读实现 (vanilla 不会出现),
 * 无法拷贝脱钩, 直接把活引用塞进快照会让 worker 与主线程共享可变状态。此时改为在主线程当场编码成 NBT
 * 带走, {@code biomes} 置 null。两者恒有且只有一个非 null。
 */
public record SectionSnapshot(
        PalettedContainer<BlockState> states,
        PalettedContainerRO<Holder<Biome>> biomes,
        Tag preEncodedBiomes) {

    /** 常规路径: biomes 是 PalettedContainer, 已 copy() 脱钩。 */
    public static SectionSnapshot ofCopiedBiomes(
            PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        return new SectionSnapshot(states, biomes, null);
    }

    /** 第三方只读 biomes 容器无法 copy(), 主线程已编码成 NBT。 */
    public static SectionSnapshot ofPreEncodedBiomes(
            PalettedContainer<BlockState> states, Tag preEncodedBiomes) {
        return new SectionSnapshot(states, null, preEncodedBiomes);
    }
}
