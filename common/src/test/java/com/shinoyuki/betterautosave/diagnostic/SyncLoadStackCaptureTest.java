package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncLoadStackCaptureTest {

    @Test
    void vanilla_jdk_and_own_instrumentation_frames_are_filtered() {
        assertFalse(SyncLoadStackCapture.isInteresting("net.minecraft.server.level.ServerChunkCache"));
        assertFalse(SyncLoadStackCapture.isInteresting("java.lang.Thread"));
        assertFalse(SyncLoadStackCapture.isInteresting("jdk.internal.reflect.DirectMethodHandleAccessor"));
        assertFalse(SyncLoadStackCapture.isInteresting("sun.misc.Unsafe"));
        assertFalse(SyncLoadStackCapture.isInteresting("com.mojang.datafixers.DataFixerUpper"));
        assertFalse(SyncLoadStackCapture.isInteresting("com.shinoyuki.betterautosave.mixin.ServerChunkCacheSyncLoadMixin"));
        assertFalse(SyncLoadStackCapture.isInteresting("com.shinoyuki.betterautosave.diagnostic.SyncLoadDetector"));
        assertFalse(SyncLoadStackCapture.isInteresting("com.llamalad7.mixinextras.injector.wrapoperation.Operation"));
        assertFalse(SyncLoadStackCapture.isInteresting("org.spongepowered.asm.mixin.injection.invoke.Invoker"));
        assertFalse(SyncLoadStackCapture.isInteresting(null));
        assertFalse(SyncLoadStackCapture.isInteresting(""));
    }

    @Test
    void third_party_and_event_dispatch_frames_are_kept() {
        // 事件派发帧刻意保留: 它说明这次同步加载发生在哪个事件里, 是有效归因线索.
        // 删掉这条保留逻辑 (把 net.minecraftforge. / net.neoforged. 也加进跳过前缀) 本用例必挂.
        assertTrue(SyncLoadStackCapture.isInteresting("io.github.flemmli97.flan.player.ClientBlockDisplayTracker"));
        assertTrue(SyncLoadStackCapture.isInteresting("net.minecraftforge.eventbus.EventBus"));
        assertTrue(SyncLoadStackCapture.isInteresting("net.neoforged.bus.EventBus"));
        // net.minecraftforge 与 net.minecraft. 只差一个点, 前缀匹配必须精确到点
        assertTrue(SyncLoadStackCapture.isInteresting("net.minecraftforge.common.world.ForgeChunkManager"));
        assertTrue(SyncLoadStackCapture.isInteresting("javassist.SomeGenerated"),
                "javassist 不是 java. 前缀, 不能被误杀");
    }

    @Test
    void own_non_instrumentation_frames_are_not_self_exempted() {
        // 自我豁免面守卫: 只有 BAS 的插桩两层 (diagnostic / mixin) 该被剔除, 其余 BAS 包必须如实归因.
        // 把跳过前缀放宽回整包 "com.shinoyuki.betterautosave." 本用例必挂.
        assertTrue(SyncLoadStackCapture.isInteresting("com.shinoyuki.betterautosave.core.scheduler.SaveScheduler"));
        assertTrue(SyncLoadStackCapture.isInteresting("com.shinoyuki.betterautosave.core.worker.SnapshotPipeline"));
        assertTrue(SyncLoadStackCapture.isInteresting("com.shinoyuki.betterautosave.command.BetterAutoSaveCommand"));
        assertEquals("com.shinoyuki.betterautosave.core.worker.SnapshotPipeline",
                SyncLoadStackCapture.attributionOf(SyncLoadStackCapture.filterFrames(new String[]{
                        "com.shinoyuki.betterautosave.diagnostic.SyncLoadStackCapture",
                        "com.shinoyuki.betterautosave.mixin.ServerChunkCacheSyncLoadMixin",
                        "net.minecraft.server.level.ServerChunkCache",
                        "com.shinoyuki.betterautosave.core.worker.SnapshotPipeline",
                }, 24)),
                "BAS 自身的非插桩帧必须能成为归因主体, 而不是被跳到下一个第三方帧");
    }

    @Test
    void filter_frames_keeps_order_and_drops_noise() {
        String[] raw = {
                "com.shinoyuki.betterautosave.diagnostic.SyncLoadStackCapture",
                "com.shinoyuki.betterautosave.mixin.ServerChunkCacheSyncLoadMixin",
                "net.minecraft.server.level.ServerChunkCache",
                "io.github.flemmli97.flan.player.ClientBlockDisplayTracker",
                "net.minecraftforge.eventbus.EventBus",
                "java.lang.Thread",
                "dev.ftb.mods.ftbchunks.ChunkTeleporter",
        };
        String[] out = SyncLoadStackCapture.filterFrames(raw, 24);
        assertArrayEquals(new String[]{
                "io.github.flemmli97.flan.player.ClientBlockDisplayTracker",
                "net.minecraftforge.eventbus.EventBus",
                "dev.ftb.mods.ftbchunks.ChunkTeleporter",
        }, out, "过滤后必须保持由内向外的原顺序, 第 0 帧是最内层的第三方帧");
    }

    @Test
    void filter_frames_truncates_to_max_depth() {
        String[] raw = {"a.A", "b.B", "c.C", "d.D"};
        assertArrayEquals(new String[]{"a.A", "b.B"}, SyncLoadStackCapture.filterFrames(raw, 2),
                "maxDepth 必须截断且保留最内层的那几帧");
        assertEquals(1, SyncLoadStackCapture.filterFrames(raw, 1).length);
        assertEquals(4, SyncLoadStackCapture.filterFrames(raw, 99).length, "maxDepth 大于实际帧数时全返");
    }

    @Test
    void filter_frames_boundary_inputs_return_empty() {
        String[] raw = {"a.A", "b.B"};
        assertEquals(0, SyncLoadStackCapture.filterFrames(raw, 0).length);
        assertEquals(0, SyncLoadStackCapture.filterFrames(raw, -1).length);
        assertEquals(0, SyncLoadStackCapture.filterFrames(null, 24).length);
        assertEquals(0, SyncLoadStackCapture.filterFrames(new String[0], 24).length);
        assertEquals(0, SyncLoadStackCapture.filterFrames(new String[]{"java.lang.Thread", null}, 24).length,
                "全部帧都被过滤时返回空数组而不是 null");
    }

    @Test
    void filter_frames_does_not_mutate_input() {
        String[] raw = {"java.lang.Thread", "a.A", "b.B"};
        String[] copy = Arrays.copyOf(raw, raw.length);
        SyncLoadStackCapture.filterFrames(raw, 1);
        assertArrayEquals(copy, raw, "纯函数不得改写入参");
    }

    @Test
    void capture_with_non_positive_depth_returns_empty() {
        assertEquals(0, SyncLoadStackCapture.capture(0).length);
        assertEquals(0, SyncLoadStackCapture.capture(-1).length);
    }

    @Test
    void capture_from_real_stack_applies_the_same_filter() {
        String[] frames = level1(24);
        assertTrue(frames.length > 0,
                "JUnit 运行期栈里必然有 org.junit 帧, 采集结果不该为空: " + Arrays.toString(frames));
        for (String f : frames) {
            // 帧是 类名#方法名, 过滤判定吃的是类名: 直接把整帧喂进去会因为不匹配任何前缀而恒真
            assertTrue(SyncLoadStackCapture.isInteresting(SyncLoadStackCapture.classOf(f)),
                    "采集结果混入了应被过滤的帧: " + f);
            assertFalse(f.startsWith("java."), "java. 帧必须被过滤, got " + f);
            assertFalse(f.startsWith("jdk."), "jdk. 帧必须被过滤, got " + f);
            assertNotEquals(SyncLoadStackCaptureTest.class.getName(), SyncLoadStackCapture.classOf(f),
                    "本测试类在 com.shinoyuki.betterautosave.diagnostic 下, 必须被插桩层过滤规则剔除");
        }
        assertTrue(frames[0].indexOf(SyncLoadStackCapture.FRAME_SEPARATOR) > 0,
                "真实采集的帧必须带方法名, 否则 Entity 这类归因粒度太粗: " + frames[0]);
        assertTrue(SyncLoadStackCapture.capture(2).length <= 2, "maxDepth 必须在真实栈上同样生效");
    }

    @Test
    void capture_does_not_count_failures_on_normal_path() {
        SyncLoadStackCapture.resetFailures();
        assertEquals(0L, SyncLoadStackCapture.failures());
        SyncLoadStackCapture.capture(8);
        assertEquals(0L, SyncLoadStackCapture.failures(), "正常采集不得计入失败数");
    }

    @Test
    void attribution_of_picks_innermost_frame_and_falls_back_to_unknown() {
        assertEquals("io.github.flemmli97.flan.A",
                SyncLoadStackCapture.attributionOf(new String[]{"io.github.flemmli97.flan.A", "b.B"}));
        assertEquals("unknown", SyncLoadStackCapture.attributionOf(new String[0]));
        assertEquals("unknown", SyncLoadStackCapture.attributionOf(null));
        assertEquals("unknown", SyncLoadStackCapture.attributionOf(new String[]{null}));
        assertEquals(SyncLoadStackCapture.UNKNOWN_ATTRIBUTION, "unknown");
    }

    @Test
    void forwarding_layer_is_dropped_but_business_vanilla_frames_are_kept() {
        // 转发层必须剥掉: 它们出现在每一条同步加载栈的最内侧, 保留下来会让所有停顿归因成同一个 Level
        assertFalse(SyncLoadStackCapture.isInteresting("net.minecraft.server.level.ServerChunkCache"));
        assertFalse(SyncLoadStackCapture.isInteresting("net.minecraft.world.level.Level"));
        assertFalse(SyncLoadStackCapture.isInteresting("net.minecraft.world.level.LevelReader"));
        assertFalse(SyncLoadStackCapture.isInteresting("net.minecraft.world.level.CommonLevelAccessor"));
        assertFalse(SyncLoadStackCapture.isInteresting("net.minecraft.util.thread.BlockableEventLoop"));

        // 业务 vanilla 帧必须保留: 生产采样里同步加载多数由原版自身发起 (实体流体检测 / 传送 /
        // 落点判定), 把 net.minecraft 整体过滤会让这些场景全部归因成 unknown。
        assertTrue(SyncLoadStackCapture.isInteresting("net.minecraft.server.commands.TeleportCommand"));
        assertTrue(SyncLoadStackCapture.isInteresting("net.minecraft.world.entity.Entity"));
        assertTrue(SyncLoadStackCapture.isInteresting("net.minecraft.server.level.ServerPlayer"));
        assertTrue(SyncLoadStackCapture.isInteresting("net.minecraft.server.level.ServerLevel"));
    }

    @Test
    void base_libraries_are_never_the_attribution() {
        // 真机首测就踩到这条: vanilla 碰撞遍历内部用 Guava 迭代器, 归因被指到 AbstractIterator 上
        assertFalse(SyncLoadStackCapture.isInteresting("com.google.common.collect.AbstractIterator"));
        assertFalse(SyncLoadStackCapture.isInteresting("it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap"));
        assertFalse(SyncLoadStackCapture.isInteresting("io.netty.channel.DefaultChannelPipeline"));
        assertFalse(SyncLoadStackCapture.isInteresting("org.apache.commons.lang3.Validate"));
    }

    @Test
    void all_vanilla_stack_attributes_to_the_business_frame_instead_of_unknown() {
        // 真机回归: 玩家传送触发的同步取图, 整条链全是 vanilla。旧规则 (整体跳过 net.minecraft)
        // 会过滤到一帧不剩, 归因落成 unknown, 等于没有归因。
        String[] raw = {
                "com.shinoyuki.betterautosave.diagnostic.SyncLoadStackCapture#capture",
                "com.shinoyuki.betterautosave.mixin.ServerChunkCacheSyncLoadMixin#handler",
                "net.minecraft.server.level.ServerChunkCache#getChunk",
                "net.minecraft.world.level.Level#getChunk",
                "net.minecraft.world.level.LevelReader#getChunk",
                "net.minecraft.world.entity.Entity#getOnPosLegacy",
                "net.minecraft.server.level.ServerPlayer#teleportTo",
                "net.minecraft.server.commands.TeleportCommand#performTeleport",
        };
        String[] kept = SyncLoadStackCapture.filterFrames(raw, 24);
        assertArrayEquals(new String[]{
                "net.minecraft.world.entity.Entity#getOnPosLegacy",
                "net.minecraft.server.level.ServerPlayer#teleportTo",
                "net.minecraft.server.commands.TeleportCommand#performTeleport",
        }, kept, "转发层与插桩层剥掉之后, 剩下的必须是能说明触发场景的业务帧");
        assertEquals("net.minecraft.world.entity.Entity#getOnPosLegacy",
                SyncLoadStackCapture.attributionOf(kept),
                "全 vanilla 栈的归因主体必须是最内层业务帧, 不能是 unknown");
    }

    @Test
    void mod_frame_wins_over_vanilla_when_present() {
        // Flan 那类第三方触发: 栈里既有 mod 帧也有 vanilla 业务帧, 归因主体必须是 mod 帧,
        // 因为平台侧要拿它去查 modid。
        String[] kept = SyncLoadStackCapture.filterFrames(new String[]{
                "net.minecraft.world.level.Level#getBlockState",
                "io.github.flemmli97.flan.player.ClientBlockDisplayTracker#resetBlocks",
                "net.minecraft.server.level.ServerPlayer#tick",
        }, 24);
        assertEquals("io.github.flemmli97.flan.player.ClientBlockDisplayTracker#resetBlocks",
                SyncLoadStackCapture.attributionOf(kept));
    }

    @Test
    void class_can_be_recovered_from_frame_for_modid_lookup() {
        assertEquals("io.github.flemmli97.flan.player.ClientBlockDisplayTracker",
                SyncLoadStackCapture.classOf("io.github.flemmli97.flan.player.ClientBlockDisplayTracker#resetBlocks"));
        // 裸类名 (无分隔符) 必须原样返回, 否则合成数组写的老用例与平台侧的兜底路径都会取到空串
        assertEquals("a.B", SyncLoadStackCapture.classOf("a.B"));
        assertEquals("", SyncLoadStackCapture.classOf(null));
        assertEquals("", SyncLoadStackCapture.classOf(""));
    }

    @Test
    void is_vanilla_only_matches_minecraft_itself() {
        assertTrue(SyncLoadStackCapture.isVanilla("net.minecraft.world.entity.Entity"));
        // loader 包不是 vanilla: 它们会被拿去查 modid, 事件派发帧本身也是有效归因线索
        assertFalse(SyncLoadStackCapture.isVanilla("net.minecraftforge.eventbus.EventBus"));
        assertFalse(SyncLoadStackCapture.isVanilla("net.neoforged.bus.EventBus"));
        assertFalse(SyncLoadStackCapture.isVanilla("io.github.flemmli97.flan.X"));
        assertFalse(SyncLoadStackCapture.isVanilla(null));
    }

    private static String[] level1(int depth) {
        return level2(depth);
    }

    private static String[] level2(int depth) {
        return level3(depth);
    }

    private static String[] level3(int depth) {
        return SyncLoadStackCapture.capture(depth);
    }
}
