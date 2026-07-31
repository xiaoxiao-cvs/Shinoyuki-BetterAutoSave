package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.core.Holder;
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
 */
public record SectionSnapshot(
        PalettedContainer<BlockState> states,
        PalettedContainerRO<Holder<Biome>> biomes) {
}
