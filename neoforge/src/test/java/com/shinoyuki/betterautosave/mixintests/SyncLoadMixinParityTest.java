package com.shinoyuki.betterautosave.mixintests;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * v0.20 硬门禁: 同步加载检测"常态零采栈"的设计不可被后人改坏。
 *
 * <p><b>为何用字节码断言</b>: 单测里无法真的触发一次 {@code getChunk} 四槽缓存未命中 (需要活的
 * ServerLevel 与区块生成器)。故对 build/classes 的编译产物按指令断言, deletion-sensitive。
 *
 * <p><b>包名必须是 mixintests 而不是 mixin</b>: 本模块的测试跑在 MDG 激活的 mixin 运行期下,
 * {@code com.shinoyuki.betterautosave.mixin} 包 (两平台 mixins.json 声明的 package) 内的类被直接
 * classload 会抛 IllegalClassLoadError, 既有 SavedDataDirtyVisibilityTest 的注释已记录该后果。
 * 本测试只读 .class 字节, 不 classload 被测类。
 *
 * <p>被锁死的不变式: handler 里的调用序列必须精确是
 * {@code nanoTime -> Operation.call -> nanoTime -> onSyncLoadReturned}。这一条同时保证了
 * original.call 恰好一次、计时闭合在 call 两侧、阈值判定与采栈全部发生在 call 之后、handler 里
 * 没有任何多余调用。把采栈或阈值判定挪到 call 之前, 每一次四槽缓存未命中都要付采栈的代价 ——
 * 那正是这个功能敢默认开启的唯一理由。
 */
class SyncLoadMixinParityTest {

    private static final String SYNC_LOAD_MIXIN =
            "com.shinoyuki.betterautosave.mixin.ServerChunkCacheSyncLoadMixin";
    private static final String SERVER_MIXIN =
            "com.shinoyuki.betterautosave.mixin.MinecraftServerMixin";
    private static final String SYNC_LOAD_DETECTOR =
            "com.shinoyuki.betterautosave.diagnostic.SyncLoadDetector";
    private static final String TICK_GAP_DETECTOR =
            "com.shinoyuki.betterautosave.diagnostic.TickGapDetector";
    private static final String HANDLER = "betterautosave$measureSyncChunkLoad";
    private static final String OPERATION_OWNER =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    /**
     * main 类目录, 不依赖工作目录。FG6 (forge) 测试工作目录=模块根; MDG (neoforge) 测试工作目录=
     * 模块/build/minecraft-junit。逐个试候选相对路径取第一个存在的, 两套工具链都覆盖。
     */
    private Path mainClassesDir() {
        for (String candidate : new String[]{
                "build/classes/java/main",
                "../classes/java/main",
                "../../build/classes/java/main"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Path.of("build/classes/java/main");
    }

    private ClassNode loadClass(String className) throws IOException {
        Path classFile = mainClassesDir().resolve(className.replace('.', '/') + ".class");
        assertTrue(Files.exists(classFile),
                "编译产物缺失 (先跑 compileJava): " + classFile.toAbsolutePath());
        ClassNode node = new ClassNode();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private MethodNode method(ClassNode node, String name) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        fail("方法未找到: " + node.name + "#" + name);
        return null;
    }

    /**
     * handler 本体 + javac 为它编出的 lambda 合成方法。若有人把逻辑藏进 lambda, 单看本体会漏检。
     */
    private List<MethodNode> methodAndItsLambdas(ClassNode node, String handler) {
        List<MethodNode> out = new ArrayList<>();
        String lambdaPrefix = "lambda$" + handler + "$";
        for (MethodNode m : node.methods) {
            if (m.name.equals(handler) || m.name.startsWith(lambdaPrefix)) {
                out.add(m);
            }
        }
        assertTrue(!out.isEmpty(), "未找到 handler 方法或其 lambda: " + node.name + "#" + handler);
        return out;
    }

    private List<String> callNamesInOrder(MethodNode m) {
        List<String> names = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                names.add(call.name);
            }
        }
        return names;
    }

    /**
     * {@code guardedCall} 是否真的被 {@code thresholdCall} 的阈值分支支配 (dominate)。
     *
     * <p>只断言"两者之间存在一条条件跳转"是挡不住的: 把分支缩小成只包住阈值内那一条语句、之后无条件
     * 采栈, 阈值比较与它的跳转仍然排在采栈之前, 顺序断言与"存在跳转"断言全部通过, 而采栈已经回到了
     * 常态路径上。故判据是两条同时成立:
     *
     * <p>1) 合格跳转必须紧跟在产生它那个布尔值的比较之后 —— 内联的 long 阈值比较是 {@code lcmp},
     * 把判定抽成 {@code static boolean} 帮助方法则是那次布尔返回值的调用。这样才能确定这条跳转就是
     * 阈值判定本身, 而不是方法里任意一条无关的条件跳转。
     *
     * <p>2) 落空分支必须直接 RETURN —— RETURN 要落在这条跳转与它自己的目标标签之间, 也就是真正被
     * 跳过的那一段里。只要求"跳转之后的任意位置存在 RETURN"不够: 被守卫的方法在阈值分支之后本就还有
     * 一条与阈值无关的早返 (tracker 尚未注入时的兜底), 它会被误认成阈值分支的物证, 于是"把分支缩小成
     * 只包住阈值内那一条语句、之后无条件采栈"仍然照常放行。
     */
    private boolean isDominatedByThresholdBranch(MethodNode m, String thresholdCall, String guardedCall) {
        boolean seenThreshold = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                if (call.name.equals(guardedCall)) {
                    return false;
                }
                if (call.name.equals(thresholdCall)) {
                    seenThreshold = true;
                }
                continue;
            }
            if (seenThreshold && insn instanceof JumpInsnNode jump && isThresholdJump(jump)
                    && returnsBeforeTarget(jump)) {
                return true;
            }
        }
        return false;
    }

    /** 落空分支是否直接 RETURN: RETURN 必须落在跳转与它自己的目标标签之间, 即真正被跳过的那一段。 */
    private boolean returnsBeforeTarget(JumpInsnNode jump) {
        for (AbstractInsnNode n = jump.getNext(); n != null && n != jump.label; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.RETURN) {
                return true;
            }
        }
        return false;
    }

    /** 这条跳转是不是阈值比较本身产生的那一条。 */
    private boolean isThresholdJump(JumpInsnNode jump) {
        AbstractInsnNode prev = previousRealInsn(jump);
        if (prev == null) {
            return false;
        }
        int op = jump.getOpcode();
        if (op == Opcodes.IFGE || op == Opcodes.IFGT || op == Opcodes.IFLE || op == Opcodes.IFLT) {
            return prev.getOpcode() == Opcodes.LCMP;
        }
        if (op == Opcodes.IFEQ || op == Opcodes.IFNE) {
            // 判定被抽成 static boolean 帮助方法后的等价形状, 跳转直接吃那次调用的布尔返回值。
            // 不收这一种会把一次纯粹的提取重构误判成"阈值判定被架空"。
            return prev instanceof MethodInsnNode call
                    && Type.getReturnType(call.desc).getSort() == Type.BOOLEAN;
        }
        return false;
    }

    /** 跳过 label / 行号 / 栈图这些没有操作码的伪指令, 取真正的前一条字节码。 */
    private AbstractInsnNode previousRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && prev.getOpcode() < 0) {
            prev = prev.getPrevious();
        }
        return prev;
    }

    /** 没有这条, 下面的计数断言在 handler 被改名后会静默归零并 PASS。 */
    @Test
    void sync_load_handler_method_exists() throws IOException {
        ClassNode node = loadClass(SYNC_LOAD_MIXIN);
        MethodNode handler = method(node, HANDLER);
        assertEquals(HANDLER, handler.name);
    }

    /** 本文件最重要的一条: 调用序列即设计契约。 */
    @Test
    void handler_invocation_sequence_is_exact() throws IOException {
        ClassNode node = loadClass(SYNC_LOAD_MIXIN);
        List<String> actual = callNamesInOrder(method(node, HANDLER));
        assertEquals(List.of("nanoTime", "call", "nanoTime", "onSyncLoadReturned"), actual,
                "ServerChunkCacheSyncLoadMixin#" + HANDLER + " 的调用序列必须精确是 "
                        + "nanoTime -> Operation.call -> nanoTime -> onSyncLoadReturned。"
                        + "任何一处调换或增删都意味着: original.call 不止一次 / 计时没有闭合在 call 两侧 / "
                        + "阈值判定或采栈被挪到了 call 之前 —— 最后一种会让每次四槽缓存未命中都付采栈代价, "
                        + "直接毁掉本功能默认开启的前提。实际序列: " + actual);
    }

    /** 采栈只能发生在 SyncLoadDetector 的阈值分支之内, handler 里一次都不许有。 */
    @Test
    void handler_never_captures_stack_directly() throws IOException {
        ClassNode node = loadClass(SYNC_LOAD_MIXIN);
        int suspicious = 0;
        for (MethodNode m : methodAndItsLambdas(node, HANDLER)) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && (call.owner.contains("StackWalker")
                            || call.name.equals("capture")
                            || call.name.equals("record")
                            || call.name.equals("fingerprint"))) {
                    suspicious++;
                }
            }
        }
        assertEquals(0, suspicious,
                "handler 里不得直接采栈或写 tracker: 这些动作必须留在 SyncLoadDetector 的超阈值分支内, "
                        + "否则常态 (未超阈值) 路径也要付采栈代价");
    }

    /** call 必须是 MixinExtras 的 Operation.call, 防止有人换成同名的自造接口把包裹语义偷换掉。 */
    @Test
    void operation_call_owner_is_mixinextras() throws IOException {
        ClassNode node = loadClass(SYNC_LOAD_MIXIN);
        int matched = 0;
        for (AbstractInsnNode insn : method(node, HANDLER).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.name.equals("call")) {
                assertEquals(OPERATION_OWNER, call.owner,
                        "handler 里那次 call 必须是 MixinExtras 的 Operation.call, 实际 owner: " + call.owner);
                matched++;
            }
        }
        assertEquals(1, matched, "handler 必须恰好调一次 Operation.call");
    }

    /** tick gap 的四个新注入点必须都在, 且 TAIL 戳记必须是干净的一行, 没有被塞进任何早返逻辑。 */
    @Test
    void minecraft_server_mixin_tick_gap_shape() throws IOException {
        ClassNode node = loadClass(SERVER_MIXIN);
        for (String name : new String[]{
                "betterautosave$onTickServerHead",
                "betterautosave$onTickServerTailStamp",
                "betterautosave$onTaskHead",
                "betterautosave$onTaskReturn"}) {
            method(node, name);
        }

        List<String> stampCalls = callNamesInOrder(method(node, "betterautosave$onTickServerTailStamp"));
        assertEquals(List.of("nanoTime"), stampCalls,
                "betterautosave$onTickServerTailStamp 必须只有一次 System.nanoTime() 调用。"
                        + "任何额外调用都意味着它被塞进了 isInstalled/isDegraded 之类的早返逻辑 —— "
                        + "那会让未安装 / 降级会话的 tick gap 计算整段失效, 而降级会话恰恰最需要这段观测。"
                        + "实际调用: " + stampCalls);
    }

    /** 方法在 class 文件里的声明序号。javac 按源码顺序写出方法表, Mixin 也按这个顺序注入。 */
    private int methodIndex(ClassNode node, String name) {
        for (int i = 0; i < node.methods.size(); i++) {
            if (node.methods.get(i).name.equals(name)) {
                return i;
            }
        }
        fail("方法未找到: " + node.name + "#" + name);
        return -1;
    }

    /**
     * 两个 TAIL 注入的声明顺序即执行顺序 (Mixin 对同一条 RETURN 指令按 mixin classNode.methods 的顺序
     * 依次 insertBefore)。戳记必须声明在后面, 否则 BAS 自己的 tick 尾部工作会跑在戳记之后, 被算进下一次
     * 上报的 tick 外停顿。调换声明顺序不改任何一行逻辑, 没有这条门禁就完全无声。
     */
    @Test
    void tail_stamp_is_declared_after_bas_tail_work() throws IOException {
        ClassNode node = loadClass(SERVER_MIXIN);
        int tailWork = methodIndex(node, "betterautosave$onTickServer");
        int stamp = methodIndex(node, "betterautosave$onTickServerTailStamp");
        assertTrue(tailWork < stamp,
                "betterautosave$onTickServerTailStamp 必须声明在 betterautosave$onTickServer 之后 "
                        + "(同一条 RETURN 上的多个 TAIL 注入按声明顺序执行)。顺序一旦调换, 恢复队列 drain、"
                        + "诊断摘要与调度 dispatch 都会落在戳记之后, 被计进 gapNs, 而 tickServer HEAD 处那条"
                        + "注释也随之失实。实际声明序号: onTickServer=" + tailWork + " tailStamp=" + stamp);
    }

    /**
     * 采栈必须被阈值分支支配: 挪到早返之前, 常态零采栈的前提就没了。
     *
     * <p>设计不变量的真正居所是 SyncLoadDetector 而不是 mixin handler —— handler 只是把判定整个转交给
     * 它。上面那几条只看得住 handler, 这一条看住判定本身。
     */
    @Test
    void detector_captures_stack_only_after_threshold_branch() throws IOException {
        ClassNode node = loadClass(SYNC_LOAD_DETECTOR);
        MethodNode m = method(node, "onSyncLoadReturned");
        List<String> calls = callNamesInOrder(m);

        int threshold = calls.indexOf("syncLoadThresholdMs");
        int stall = calls.indexOf("recordSyncLoadStall");
        int capture = calls.indexOf("capture");
        assertTrue(threshold >= 0, "阈值读取消失了: " + calls);
        assertTrue(capture > threshold,
                "SyncLoadStackCapture.capture 必须在阈值判定之后 —— 挪到前面, 每一次四槽缓存未命中都要付"
                        + "一次 StackWalker 全栈遍历, 那正是本功能敢默认开启的唯一前提。实际序列: " + calls);
        assertTrue(capture > stall,
                "recordSyncLoadStall 必须先于采栈 (tracker 未注入时指标仍要计数), 实际: " + calls);

        assertTrue(isDominatedByThresholdBranch(m, "syncLoadThresholdMs", "capture"),
                "采栈必须被阈值分支支配: 阈值比较产生的那条跳转与采栈之间必须有一条早返 RETURN。"
                        + "只验\"两者之间存在条件跳转\"挡不住把分支缩小成只包住 recordSyncLoadStall、"
                        + "之后无条件采栈的改法 —— 那种形状下采栈已经回到常态路径, 每一次四槽缓存未命中"
                        + "都要付一次 StackWalker 全栈遍历。实际序列: " + calls);
    }

    /** 深度档同理: 先判定后取值, 未超阈值的任务一次 accessor 调用都不许有。 */
    @Test
    void tick_gap_deep_reads_runnable_only_after_threshold() throws IOException {
        ClassNode node = loadClass(TICK_GAP_DETECTOR);
        MethodNode m = method(node, "onTaskFinished");
        List<String> calls = callNamesInOrder(m);

        int threshold = calls.indexOf("tickGapThresholdMs");
        int runnable = calls.indexOf("betterautosave$getRunnable");
        assertTrue(threshold >= 0, "阈值读取消失了: " + calls);
        assertTrue(runnable > threshold,
                "TickTaskAccessor#betterautosave$getRunnable 必须在阈值判定之后 —— doRunTask 每 tick 执行"
                        + "几百次, 挪到前面就是每个任务都付一次 accessor 转型 + getClass().getName()。"
                        + "实际序列: " + calls);
        assertTrue(isDominatedByThresholdBranch(m, "tickGapThresholdMs", "betterautosave$getRunnable"),
                "取 runnable 必须被单任务阈值分支支配: 阈值比较产生的那条跳转与取值之间必须有一条早返 "
                        + "RETURN, 否则把分支缩小之后每个任务照样付一次 accessor 转型 + "
                        + "getClass().getName()。实际序列: " + calls);
    }
}
