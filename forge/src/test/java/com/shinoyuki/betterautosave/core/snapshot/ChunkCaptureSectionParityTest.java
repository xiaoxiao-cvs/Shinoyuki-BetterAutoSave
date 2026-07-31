package com.shinoyuki.betterautosave.core.snapshot;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * issue #24 回归: capture 期 section 取材的三条不变式。
 *
 * <p><b>为何用字节码断言</b>: 同 {@link ChunkForgeCapsParityTest} —— 本模块 test 任务裸 JUnit 无 MC 运行期,
 * 无法构造真实 LevelChunk 调 capture。故对 build/classes 编译产物按指令断言, deletion-sensitive。
 *
 * <ul>
 *   <li>不再包 {@code LevelChunkSection}: 它的构造器无条件 {@code recalcBlockCounts()}, 对每个多 palette 项
 *       的 section 遍历 4096 格; 而这三个方块计数不进落盘 NBT。实测该白扫占 capture 主线程成本约一半</li>
 *   <li>FULL 档短路必须发生在取材之前: {@code ChunkSerializer.write} 自己会采一遍 sections/光照/heightmap,
 *       短路挪到取材之后就退化回"先采后废"</li>
 *   <li>非 {@code PalettedContainer} 的 biomes 必须主线程预编码而非直接 alias 活引用: RO 接口没有 copy(),
 *       塞活引用等于让 worker 与主线程共享可变状态</li>
 * </ul>
 */
class ChunkCaptureSectionParityTest {

    private static final String CAPTURE = "com.shinoyuki.betterautosave.core.snapshot.ChunkCaptureProcedure";
    private static final String ASSEMBLER = "com.shinoyuki.betterautosave.core.snapshot.ChunkNbtAssembler";
    private static final String LEVEL_CHUNK_SECTION = "net/minecraft/world/level/chunk/LevelChunkSection";

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

    private int countCalls(ClassNode node, String owner, String name) {
        int n = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && (owner == null || call.owner.equals(owner))
                        && call.name.equals(name)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 首个匹配调用在该方法指令流中的下标; 无匹配返回 -1。 */
    private int firstCallIndex(MethodNode m, String name) {
        AbstractInsnNode[] insns = m.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] instanceof MethodInsnNode call && call.name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 快照 section 绝不能再包 LevelChunkSection —— 构造器会强制全格重算方块计数。 */
    @Test
    void capture_never_constructs_level_chunk_section() throws IOException {
        ClassNode node = loadClass(CAPTURE);
        int ctorCalls = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals(LEVEL_CHUNK_SECTION)
                        && call.name.equals("<init>")) {
                    ctorCalls++;
                }
            }
        }
        assertEquals(0, ctorCalls,
                "ChunkCaptureProcedure 不得构造 LevelChunkSection: 其构造器无条件 recalcBlockCounts(), "
                        + "对每个多 palette 项的 section 遍历 4096 格喂 Int2IntOpenHashMap, 而三个方块计数"
                        + "不进落盘 NBT (issue #24)");
    }

    /** capture 产物必须装进 SectionSnapshot 的两个工厂之一, 二者都在即两条分支都还在。 */
    @Test
    void copy_sections_builds_section_snapshots() throws IOException {
        ClassNode node = loadClass(CAPTURE);
        MethodNode copySections = method(node, "copySections");
        int copied = 0;
        int preEncoded = 0;
        for (AbstractInsnNode insn : copySections.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                if (call.name.equals("ofCopiedBiomes")) {
                    copied++;
                } else if (call.name.equals("ofPreEncodedBiomes")) {
                    preEncoded++;
                }
            }
        }
        assertTrue(copied >= 1,
                "copySections 常规分支必须走 SectionSnapshot.ofCopiedBiomes (biomes 已 copy() 脱钩)");
        assertTrue(preEncoded >= 1,
                "copySections 对非 PalettedContainer 的 biomes 必须走 SectionSnapshot.ofPreEncodedBiomes "
                        + "主线程预编码; 退回直接 alias 活引用 = worker 与主线程共享可变状态");
    }

    /**
     * 两个容器都必须 copy() 脱钩 —— states 一次, biomes 常规分支一次。这是 BAS 异步存盘的命根子:
     * 少任何一次, worker 编码期间主线程改的方块就会漏进这一代存档。
     */
    @Test
    void copy_sections_decouples_both_containers() throws IOException {
        ClassNode node = loadClass(CAPTURE);
        MethodNode copySections = method(node, "copySections");
        int copies = 0;
        for (AbstractInsnNode insn : copySections.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/chunk/PalettedContainer")
                    && call.name.equals("copy")) {
                copies++;
            }
        }
        assertEquals(2, copies,
                "copySections 必须恰好两次 PalettedContainer.copy(): states 一次 + 常规分支 biomes 一次。"
                        + "少一次 = 快照与活容器共享可变状态, 异步存盘写进 capture 之后的世界; "
                        + "多一次 = 有分支重复拷贝, 白烧主线程");
    }

    /** FULL 档必须在取材之前短路, 否则 copySections 等整段取材又变成先采后废。 */
    @Test
    void full_mode_short_circuits_before_gathering() throws IOException {
        ClassNode node = loadClass(CAPTURE);
        MethodNode capture = method(node, "captureWithGeneration");
        int shortCircuit = firstCallIndex(capture, "ofPrebuiltFullTag");
        int gather = firstCallIndex(capture, "copySections");
        assertTrue(shortCircuit >= 0,
                "captureWithGeneration 必须调 ChunkSnapshot.ofPrebuiltFullTag 走 FULL 短路 (issue #24)");
        assertTrue(gather >= 0, "captureWithGeneration 必须调 copySections 采 section 原料");
        assertTrue(shortCircuit < gather,
                "FULL 短路必须先于 copySections: ChunkSerializer.write 内部自己采 sections/光照/heightmap, "
                        + "短路挪到取材之后等于每次存盘白采一遍 (issue #24)");
    }

    /** 预编码的 biomes 必须被 worker 端消费, 否则 preEncodedBiomes 分支的 section 落盘 biomes 为空。 */
    @Test
    void assembler_consumes_pre_encoded_biomes() throws IOException {
        ClassNode node = loadClass(ASSEMBLER);
        assertTrue(countCalls(node, null, "preEncodedBiomes") >= 1,
                "ChunkNbtAssembler 必须读 SectionSnapshot.preEncodedBiomes(); 缺失则第三方只读 biomes 容器"
                        + "那条分支的 section 落盘时 biomes 为 null (issue #24)");
    }
}
