package com.shinoyuki.betterautosave.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * v0.20: 超阈值同步区块加载的调用栈采集与归因过滤.
 *
 * <p>纯 JDK ({@link StackWalker} 是 Java 9 API, common 的 release 是 17),
 * 故放 common 双平台共用. 采集与过滤刻意拆成两层:
 * {@link #capture(int)} 只负责取原始帧名, 全部判定逻辑在纯函数
 * {@link #filterFrames(String[], int)} 里, 后者可用合成数组直接单测.
 *
 * <p><b>为什么要过滤</b>: 一条同步加载栈里绝大多数帧是 vanilla 与 JDK 的
 * 转发帧, 对"谁发起了这次同步取图"零信息量. 剥掉它们之后剩下的第一帧就是
 * 发起方, 包名本身已足以识别来源. 刻意不过滤 {@code net.minecraftforge.} /
 * {@code net.neoforged.}: 事件派发帧说明这次加载发生在哪个事件里, 本身就是
 * 有效归因线索.
 *
 * <p><b>失败计数而不是静默</b>: 采集失败不能影响主流程 (它只是诊断路径),
 * 但也不能悄悄消失, 否则"一条都没抓到"分不清是真没有还是采集一直在挂.
 */
public final class SyncLoadStackCapture {

    /** 全部帧都被过滤掉时的归因主体. */
    public static final String UNKNOWN_ATTRIBUTION = "unknown";

    private static final String[] NO_FRAMES = new String[0];

    // BAS 自己只过滤插桩两层 (采栈点所在的 diagnostic 与注入所在的 mixin), 不整包过滤
    // com.shinoyuki.betterautosave.: 一个专门归因他人的功能不该豁免自己. 整包过滤下, 将来 BAS
    // 自身若新增主线程取图路径, 停顿会被记到栈上下一个第三方帧头上. 插桩两层则必须过滤, 否则
    // 每条栈的第 0 帧恒为采集器自身, 归因永远指向 BAS.
    private static final String[] SKIPPED_PREFIXES = {
            "net.minecraft.",
            "java.",
            "jdk.",
            "sun.",
            "com.mojang.",
            "com.shinoyuki.betterautosave.diagnostic.",
            "com.shinoyuki.betterautosave.mixin.",
            "com.llamalad7.",
            "org.spongepowered.",
    };

    // 只取 getClassName(), 不取 getDeclaringClass(), 故不要 RETAIN_CLASS_REFERENCE:
    // 该 Option 让 walker 额外保留 Class 引用, 且在装了 SecurityManager 的环境下要求
    // RuntimePermission("getStackWalkerWithClassReference"), 对本用途是纯开销与纯风险.
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final LongAdder FAILURES = new LongAdder();

    private SyncLoadStackCapture() {
    }

    /**
     * 采集当前线程调用栈里前 maxDepth 个有归因价值的帧, 顺序由内向外
     * (第 0 帧最靠近注入点).
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
                    .map(StackWalker.StackFrame::getClassName)
                    .toArray(String[]::new));
            return filterFrames(raw, maxDepth);
        } catch (RuntimeException e) {
            FAILURES.increment();
            return NO_FRAMES;
        }
    }

    /**
     * 从原始帧名数组里按原顺序取出前 maxDepth 个有归因价值的帧.
     * 纯函数: 不读任何全局状态, 不改入参.
     */
    public static String[] filterFrames(String[] classNames, int maxDepth) {
        if (classNames == null || classNames.length == 0 || maxDepth <= 0) {
            return NO_FRAMES;
        }
        List<String> kept = new ArrayList<>(Math.min(maxDepth, classNames.length));
        for (String className : classNames) {
            if (!isInteresting(className)) {
                continue;
            }
            kept.add(className);
            if (kept.size() == maxDepth) {
                break;
            }
        }
        return kept.isEmpty() ? NO_FRAMES : kept.toArray(new String[0]);
    }

    /** 该帧是否有归因价值. 独立 public 便于单测直接断言过滤规则. */
    public static boolean isInteresting(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        for (String prefix : SKIPPED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 归因主体 = 过滤后最内层的那一帧; 全被过滤时退回
     * {@value #UNKNOWN_ATTRIBUTION}. 平台侧再拿它去查 modid, 查不到就用它本身.
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
