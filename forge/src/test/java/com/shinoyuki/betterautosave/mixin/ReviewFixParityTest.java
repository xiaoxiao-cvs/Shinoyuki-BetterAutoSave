package com.shinoyuki.betterautosave.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 0.19.0 上线前对抗审查确认项的字节码回归。与 {@link com.shinoyuki.betterautosave.core.snapshot.AuditFixParityTest}
 * 同一手法: mixin 方法在 bare JUnit 下没有 MC 运行期可跑, 故对 build/classes 编译产物按调用做断言,
 * deletion-sensitive (删掉修复 -> 断言挂)。
 *
 * <ul>
 *   <li>主开关一致性: {@code general.enabled} 的文档承诺是"关掉等于没装"。本轮新增的每一个拦截点
 *       都必须自己兑现它 —— 少一个, 运维关掉主开关后那条路径仍在跑 BAS 代码, 而
 *       {@code /betterautosave status} 会报 DISABLED, 排障时直接把人带沟里</li>
 *   <li>关服窗口复位: {@code saveEverything} 是 try/finally 且只有一条 IRETURN, 异常路径不触发
 *       {@code @At("RETURN")}, autosave 窗口标志会永久停在 true。{@code stopServer} 的 HEAD 是
 *       两条关服路径唯一的公共汇合点, 必须在那里强制复位, 否则崩溃关服只写 staggerMaxPerTick 个人</li>
 *   <li>stats 写失败不得 cancel: 失败可能发生在备份轮转之后, 此刻正本已被移走; 取消 vanilla 就
 *       再没有东西把它写回来</li>
 * </ul>
 */
class ReviewFixParityTest {

    /** 见 AuditFixParityTest 同名方法: FG6 与 MDG 的测试工作目录不同, 逐个试候选相对路径。 */
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

    private MethodNode loadMethod(String className, String methodName) throws IOException {
        Path classFile = mainClassesDir().resolve(className.replace('.', '/') + ".class");
        assertTrue(Files.exists(classFile), "编译产物缺失 (先跑 compileJava): " + classFile.toAbsolutePath());
        ClassNode node = new ClassNode();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(node, 0);
        }
        for (MethodNode m : node.methods) {
            if (m.name.equals(methodName)) {
                return m;
            }
        }
        fail("方法未找到: " + className + "#" + methodName);
        return null;
    }

    private int countCalls(MethodNode m, String calleeName) {
        int n = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.name.equals(calleeName)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 本轮新增的全部拦截点, 每一个都必须自查主开关。新增拦截点时必须同步加进这张表 —— 这正是
     * 上一轮 {@code abandonOnDegrade} 那个洞的教训: 只数调用次数不逐个断言, 新增的那一种会被漏掉。
     */
    @Test
    void every_new_interception_point_honors_the_master_switch() throws IOException {
        String[][] entryPoints = {
                {"com.shinoyuki.betterautosave.mixin.PlayerListSaveAllMixin", "betterautosave$stagger"},
                {"com.shinoyuki.betterautosave.mixin.PlayerAdvancementsSaveMixin", "betterautosave$atomicSave"},
                {"com.shinoyuki.betterautosave.mixin.ServerStatsCounterSaveMixin", "betterautosave$atomicSave"},
                {"com.shinoyuki.betterautosave.mixin.PlayerDataStorageLoadMixin",
                        "betterautosave$restoreMissingPrimary"},
                {"com.shinoyuki.betterautosave.mixin.PlayerDataStorageLoadMixin",
                        "betterautosave$recoverUnreadablePrimary"},
                {"com.shinoyuki.betterautosave.mixin.LevelDataIntegrityMixin", "betterautosave$verifyAndBackup"},
                {"com.shinoyuki.betterautosave.mixin.LevelDataPostWriteVerifyMixin",
                        "betterautosave$verifyAfterWrite"},
                {"com.shinoyuki.betterautosave.mixin.MinecraftServerMixin", "betterautosave$beginSaveWindow"},
                {"com.shinoyuki.betterautosave.mixin.MinecraftServerMixin",
                        "betterautosave$drainStaggeredPlayerSaves"},
        };
        for (String[] entry : entryPoints) {
            MethodNode m = loadMethod(entry[0], entry[1]);
            assertTrue(countCalls(m, "enabled") >= 1,
                    "主开关: " + entry[0] + "#" + entry[1] + " 必须调用 BetterAutoSaveConfig.enabled(); "
                            + "否则 general.enabled=false 之后这条路径仍在跑, 与文档承诺的"
                            + "\"关掉等于没装\"矛盾");
        }
    }

    @Test
    void crash_path_shutdown_hook_resets_autosave_window() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.MinecraftServerMixin",
                "betterautosave$enterShutdownModeOnCrashPath");
        assertTrue(countCalls(m, "setInAutosaveWindow") >= 1,
                "stopServer 的 HEAD 必须强制复位 autosave 窗口标志: saveEverything 抛异常时 "
                        + "@At(\"RETURN\") 不触发, 标志会停在 true, 随后 stopServer 内的 saveAll 只写 "
                        + "staggerMaxPerTick 个人就 cancel, 且此后无 tick 消化队列 -> 其余在线玩家存档回退");
    }

    @Test
    void stats_atomic_write_failure_falls_back_to_vanilla_instead_of_cancelling() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ServerStatsCounterSaveMixin",
                "betterautosave$atomicSave");

        LabelNode handler = null;
        for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
            if ("java/io/IOException".equals(tcb.type)) {
                handler = tcb.handler;
                break;
            }
        }
        assertTrue(handler != null, "betterautosave$atomicSave 必须仍然捕获原子写的 IOException");

        boolean seenHandler = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn == handler) {
                seenHandler = true;
                continue;
            }
            if (!seenHandler) {
                continue;
            }
            if (insn instanceof MethodInsnNode call && call.name.equals("cancel")) {
                fail("stats 原子写失败后不得 cancel vanilla 写盘: 失败可能发生在备份轮转之后、原子替换"
                        + "之前, 此刻 stats/<uuid>.json 正本已被移走。取消 vanilla 就再没有东西把它写"
                        + "回来, 下次启动 ServerStatsCounter 见不到文件会静默按全新统计加载 (归零)。"
                        + "catch 块必须直接 return, 与 PlayerAdvancementsSaveMixin 对称");
            }
            if (insn.getOpcode() == Opcodes.RETURN) {
                return;
            }
        }
        fail("IOException 处理块里没有找到 return —— 无法确认失败路径会回退到 vanilla 写盘");
    }
}
