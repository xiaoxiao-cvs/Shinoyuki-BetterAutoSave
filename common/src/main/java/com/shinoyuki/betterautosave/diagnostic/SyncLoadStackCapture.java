package com.shinoyuki.betterautosave.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * v0.20: 超阈值同步区块加载的调用栈采集与归因过滤.
 *
 * <p>纯 JDK ({@link StackWalker} 是 Java 9 API, common 的 release 是 17),
 * 故放 common 双平台共用. 采集与过滤刻意拆成两层:
 * {@link #capture(int)} 只负责取原始帧名, 全部判定逻辑在纯函数
 * {@link #filterFrames(String[], int)} 里, 后者可用合成数组直接单测.
 *
 * <p><b>为什么保留 vanilla 帧</b>: 真机实测表明"跳过全部 net.minecraft"这条规则会把
 * 结果做成废纸 —— 玩家传送触发的同步取图, 整条链从 {@code TeleportCommand} 到
 * {@code getChunk} 全是 vanilla, 过滤完一帧不剩, 归因只能落到 unknown; 而 vanilla 内部
 * 用 Guava 迭代器的路径 (例如碰撞遍历) 会把归因指到 {@code AbstractIterator} 这种
 * 基础库上, 同样没有信息. 事实上生产采样里同步加载多数就是 vanilla 自身发起的
 * (实体流体检测 / 传送 / 落点判定), 那些 vanilla 帧名正是判断触发场景的依据.
 * 因此这里只剥两类帧: 基础库 (JDK/Guava/fastutil/netty 等, 永远不是答案), 以及
 * vanilla 里纯粹把调用往下传的转发层 (见 {@link #FORWARDING_CLASSES}).
 *
 * <p><b>帧格式</b>: {@code 类全名#方法名}. 只有类名时 {@code Entity} 这种归因仍然太粗,
 * 带上方法名才能区分 {@code Entity#updateFluidHeightAndDoFluidPushing} 与
 * {@code Entity#getOnPosLegacy} 这两种完全不同的触发场景. 平台侧要查 modid 时用
 * {@link #classOf(String)} 取回类名部分.
 *
 * <p>刻意不过滤 {@code net.minecraftforge.} / {@code net.neoforged.}: 事件派发帧说明
 * 这次加载发生在哪个事件里, 本身就是有效归因线索.
 *
 * <p><b>失败计数而不是静默</b>: 采集失败不能影响主流程 (它只是诊断路径),
 * 但也不能悄悄消失, 否则"一条都没抓到"分不清是真没有还是采集一直在挂.
 */
public final class SyncLoadStackCapture {

    /** 全部帧都被过滤掉时的归因主体. */
    public static final String UNKNOWN_ATTRIBUTION = "unknown";

    /** 帧内分隔类名与方法名. 用 '#' 而不是 '.', 类名本身含点, 否则无法可靠拆回. */
    public static final char FRAME_SEPARATOR = '#';

    private static final String[] NO_FRAMES = new String[0];

    private static final String VANILLA_PREFIX = "net.minecraft.";

    // BAS 自己只过滤插桩两层 (采栈点所在的 diagnostic 与注入所在的 mixin), 不整包过滤
    // com.shinoyuki.betterautosave.: 一个专门归因他人的功能不该豁免自己. 整包过滤下, 将来 BAS
    // 自身若新增主线程取图路径, 停顿会被记到栈上下一个第三方帧头上. 插桩两层则必须过滤, 否则
    // 每条栈的第 0 帧恒为采集器自身, 归因永远指向 BAS.
    //
    // 基础库同样必须过滤: 它们出现在栈上只是因为 vanilla 或某个 mod 用了它们的容器与迭代器,
    // 把归因落在 com.google.common.collect.AbstractIterator 上等于没有归因.
    private static final String[] SKIPPED_PREFIXES = {
            "java.",
            "jdk.",
            "sun.",
            "com.mojang.",
            "com.google.",
            "it.unimi.dsi.",
            "io.netty.",
            "org.apache.",
            "com.shinoyuki.betterautosave.diagnostic.",
            "com.shinoyuki.betterautosave.mixin.",
            "com.llamalad7.",
            "org.spongepowered.",
    };

    // vanilla 里只负责把取图调用往下传的层. 它们必然出现在每一条同步加载栈的最内侧,
    // 保留下来会让所有停顿的归因主体都变成同一个 Level, 聚合表随之失去区分度.
    private static final Set<String> FORWARDING_CLASSES = Set.of(
            "net.minecraft.server.level.ServerChunkCache",
            "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor",
            "net.minecraft.world.level.Level",
            "net.minecraft.world.level.LevelReader",
            "net.minecraft.world.level.LevelAccessor",
            "net.minecraft.world.level.CommonLevelAccessor",
            "net.minecraft.world.level.BlockGetter",
            "net.minecraft.world.level.chunk.ChunkSource",
            "net.minecraft.util.thread.BlockableEventLoop"
    );

    // 只取 getClassName() / getMethodName(), 不取 getDeclaringClass(), 故不要
    // RETAIN_CLASS_REFERENCE: 该 Option 让 walker 额外保留 Class 引用, 且在装了 SecurityManager
    // 的环境下要求 RuntimePermission("getStackWalkerWithClassReference"), 对本用途是纯开销与纯风险.
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final LongAdder FAILURES = new LongAdder();

    private SyncLoadStackCapture() {
    }

    /**
     * 采集当前线程调用栈里前 maxDepth 个有归因价值的帧, 顺序由内向外
     * (第 0 帧最靠近注入点), 每帧形如 {@code 类全名#方法名}.
     *
     * <p>只在超阈值路径调用: 常态一次栈都不采, 因此这里整栈取名再过滤的
     * 开销 (相对于流式 limit 提前收敛) 不进热路径, 换来的是过滤逻辑可被
     * 纯函数单测覆盖.
     */
    public static String[] capture(int maxDepth) {
        if (maxDepth <= 0) {
            return NO_FRAMES;
        }
        try {
            String[] raw = WALKER.walk(stream -> stream
                    .map(frame -> frame.getClassName() + FRAME_SEPARATOR + frame.getMethodName())
                    .toArray(String[]::new));
            return filterFrames(raw, maxDepth);
        } catch (RuntimeException e) {
            FAILURES.increment();
            return NO_FRAMES;
        }
    }

    /**
     * 从原始帧数组里按原顺序取出前 maxDepth 个有归因价值的帧.
     * 纯函数: 不读任何全局状态, 不改入参. 入参既接受 {@code 类名#方法名},
     * 也接受裸类名 (此时 {@link #classOf(String)} 原样返回).
     */
    public static String[] filterFrames(String[] frames, int maxDepth) {
        if (frames == null || frames.length == 0 || maxDepth <= 0) {
            return NO_FRAMES;
        }
        List<String> kept = new ArrayList<>(Math.min(maxDepth, frames.length));
        for (String frame : frames) {
            if (!isInteresting(classOf(frame))) {
                continue;
            }
            kept.add(frame);
            if (kept.size() == maxDepth) {
                break;
            }
        }
        return kept.isEmpty() ? NO_FRAMES : kept.toArray(new String[0]);
    }

    /** 该帧是否有归因价值. 独立 public 便于单测直接断言过滤规则. 入参是类名, 不是整帧. */
    public static boolean isInteresting(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        for (String prefix : SKIPPED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return !FORWARDING_CLASSES.contains(className);
    }

    /** 取回帧里的类名部分; 帧不含分隔符时原样返回, 故对裸类名也成立. */
    public static String classOf(String frame) {
        if (frame == null || frame.isEmpty()) {
            return "";
        }
        int sep = frame.indexOf(FRAME_SEPARATOR);
        return sep < 0 ? frame : frame.substring(0, sep);
    }

    /**
     * 是否是 vanilla 的类. 平台侧据此跳过对 vanilla 帧的 modid 反查 ——
     * loader 会把 minecraft 本身也登记成一个 mod, 不跳过的话每条全 vanilla 的栈
     * 都会被归因成 "minecraft", 把"这是原版自身发起的"这个结论盖掉.
     */
    public static boolean isVanilla(String className) {
        return className != null && className.startsWith(VANILLA_PREFIX);
    }

    /**
     * 归因主体 = 过滤后最内层的那一帧; 全被过滤时退回
     * {@value #UNKNOWN_ATTRIBUTION}. 平台侧优先拿栈里的 mod 帧查 modid,
     * 查不到 (整条栈都是 vanilla) 才退回本方法的结果.
     */
    public static String attributionOf(String[] stackFrames) {
        if (stackFrames == null || stackFrames.length == 0 || stackFrames[0] == null) {
            return UNKNOWN_ATTRIBUTION;
        }
        return stackFrames[0];
    }

    /** 采集失败累计次数. */
    public static long failures() {
        return FAILURES.sum();
    }

    /** 仅供测试与 {@code /betterautosave diagnose reset} 使用. */
    public static void resetFailures() {
        FAILURES.reset();
    }
}
