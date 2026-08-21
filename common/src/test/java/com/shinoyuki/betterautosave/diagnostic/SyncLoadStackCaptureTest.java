package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertTrue(SyncLoadStackCapture.isInteresting(f), "采集结果混入了应被过滤的帧: " + f);
            assertFalse(f.startsWith("java."), "java. 帧必须被过滤, got " + f);
            assertFalse(f.startsWith("jdk."), "jdk. 帧必须被过滤, got " + f);
        }
        assertFalse(Arrays.asList(frames).contains(SyncLoadStackCaptureTest.class.getName()),
                "本测试类在 com.shinoyuki.betterautosave.diagnostic 下, 必须被插桩层过滤规则剔除");
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
