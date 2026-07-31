package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * issue #24 回归: 用真 vanilla 容器验证 {@link SectionSnapshot} 依赖的脱钩语义。
 *
 * <p><b>分工</b>: {@code copySections} 是私有方法, 单测无法直接调 (构造真 LevelChunkSection 需要动态
 * biome 注册表)。故"生产代码确实调了两次 copy()"由 {@link ChunkCaptureSectionParityTest} 的字节码断言锁死;
 * 本测试负责证明这两次 copy() 是**载荷性**的 —— 拷贝分支与 alias 分支在同一组改动下行为确实分叉, 不是
 * 可有可无的仪式。少了这个对照, 字节码断言只是形式检查。
 */
class SectionSnapshotDecouplingTest {

    /** 用真 vanilla 注册表建容器, 需要 Bootstrap (同 core.state 下既有测试的做法)。 */
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static PalettedContainer<BlockState> liveStates(long seed) {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        // 多 palette 项 + 随机分布: 单值 palette 会走 count() 的 O(1) 快路, 掩盖真实语义。
        BlockState[] palette = {
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState(),
                Blocks.AIR.defaultBlockState()};
        Random rng = new Random(seed);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    states.getAndSetUnchecked(x, y, z, palette[rng.nextInt(palette.length)]);
                }
            }
        }
        return states;
    }

    private static Tag encode(PalettedContainer<BlockState> states) {
        return PalettedContainer.codecRW(
                        Block.BLOCK_STATE_REGISTRY,
                        BlockState.CODEC,
                        PalettedContainer.Strategy.SECTION_STATES,
                        Blocks.AIR.defaultBlockState())
                .encodeStart(NbtOps.INSTANCE, states)
                .getOrThrow(msg -> new IllegalStateException(msg));
    }

    /**
     * copy() 是载荷性的对照实验: 同一组主线程改动下, 拷贝快照的编码结果不变, alias 快照的编码结果变。
     * 若哪天 vanilla 把 copy() 改成浅拷 (或有 mod 覆写), 两条断言会一起塌, 直接暴露 BAS 的脱钩前提失效。
     */
    @Test
    void copied_snapshot_holds_still_while_aliased_snapshot_leaks() {
        PalettedContainer<BlockState> live = liveStates(20260731L);
        SectionSnapshot copiedSnapshot = new SectionSnapshot(live.copy(), null);
        SectionSnapshot aliasedSnapshot = new SectionSnapshot(live, null);

        Tag copiedAtCapture = encode(copiedSnapshot.states());
        Tag aliasedAtCapture = encode(aliasedSnapshot.states());
        assertEquals(copiedAtCapture, aliasedAtCapture,
                "前置条件: capture 那一刻两者内容必须相同, 否则后面的分叉无法归因于改动");

        // 主线程在 worker 编码期间继续改世界: 边界格 + 中间格 + 引入 palette 里没有的新方块 (触发 onResize)。
        live.getAndSetUnchecked(0, 0, 0, Blocks.BEDROCK.defaultBlockState());
        live.getAndSetUnchecked(15, 15, 15, Blocks.DIAMOND_BLOCK.defaultBlockState());
        live.getAndSetUnchecked(8, 7, 9, Blocks.LAVA.defaultBlockState());

        assertEquals(copiedAtCapture, encode(copiedSnapshot.states()),
                "拷贝快照必须冻结在 capture 那一刻: 变了说明 copy() 没有真正脱钩, "
                        + "异步存盘会把 capture 之后的世界写进这一代存档 (issue #24)");
        assertNotEquals(aliasedAtCapture, encode(aliasedSnapshot.states()),
                "alias 快照必须跟着活容器变: 不变说明本对照实验没造成任何差异, "
                        + "上一条断言退化为恒真");
    }
}
