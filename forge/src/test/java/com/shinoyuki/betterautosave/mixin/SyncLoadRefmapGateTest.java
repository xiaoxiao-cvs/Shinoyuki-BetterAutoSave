package com.shinoyuki.betterautosave.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.20 forge 独有硬门禁: 生产 SRG 环境下同步加载检测必须真的能挂上。
 *
 * <p>为什么非有不可: {@code ServerChunkCacheSyncLoadMixin} 用了 {@code require = 0}
 * (注入点失配时降级为检测不生效, 而不是启动崩服), 代价是丢掉运行期的注入失败信号。而
 * {@code managedBlock} 的 SRG 映射只声明在父类 {@code BlockableEventLoop} 上, owner 却必须保留成
 * {@code ServerChunkCache$MainThreadExecutor} —— 这依赖 Mixin AP 上溯父类再把 owner 搬回原类。
 * 这一步若失败, 开发环境 (official 名) 完全察觉不到, 生产 SRG 服上则静默失效。构建期在这里堵住。
 *
 * <p>刻意用原始文本 contains 断言而不引 JSON 库: forge 的 test 依赖里没有 JSON 解析器,
 * 而这三条断言对文本形态已经足够精确。
 */
class SyncLoadRefmapGateTest {

    private static final String MIXIN_SIMPLE_NAME = "ServerChunkCacheSyncLoadMixin";

    /** FG6 测试工作目录 = 模块根; 兼容从仓库根跑测试的情形。 */
    private Path refmapFile() {
        for (String candidate : new String[]{
                "build/tmp/compileJava/shinoyuki_betterautosave.refmap.json",
                "../tmp/compileJava/shinoyuki_betterautosave.refmap.json",
                "forge/build/tmp/compileJava/shinoyuki_betterautosave.refmap.json"}) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return Path.of("build/tmp/compileJava/shinoyuki_betterautosave.refmap.json");
    }

    private String refmap() throws IOException {
        Path file = refmapFile();
        assertTrue(Files.isRegularFile(file),
                "refmap 缺失 (先跑 compileJava): " + file.toAbsolutePath());
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 截出该 mixin 自己那一段 JSON 对象 (从类名键起到第一个 '}' 止)。条目的值全是字符串, 内部不会出现
     * 花括号, 所以这一刀是准确的。断言必须限定在本条目内: 对整个 refmap 做 contains, 将来别处出现同一个
     * SRG 名就会把断言变成永真。
     */
    private String mixinEntry(String text, String mixinSimpleName) {
        int start = text.indexOf(mixinSimpleName + "\"");
        assertTrue(start >= 0, "refmap 里没有 " + mixinSimpleName + " 条目");
        int end = text.indexOf('}', start);
        assertTrue(end > start, "refmap 里 " + mixinSimpleName + " 条目没有闭合的 '}'");
        return text.substring(start, end);
    }

    @Test
    void refmap_contains_sync_load_mixin_entry() throws IOException {
        String text = refmap();
        assertTrue(text.contains(MIXIN_SIMPLE_NAME),
                "refmap 里没有 " + MIXIN_SIMPLE_NAME + " 条目: Mixin AP 没有为它产出任何映射, "
                        + "生产 SRG 服上整个同步加载检测都不会挂上 (require=0 还会让它静默失效)");
    }

    @Test
    void get_chunk_is_mapped_to_srg() throws IOException {
        String entry = mixinEntry(refmap(), MIXIN_SIMPLE_NAME);
        assertTrue(entry.contains("m_7587_"),
                "refmap 的 " + MIXIN_SIMPLE_NAME + " 条目里没有 getChunk 的 SRG 名 m_7587_: "
                        + "method = \"getChunk\" 没有被映射, 生产环境找不到宿主方法。实际条目: " + entry);
    }

    @Test
    void managed_block_keeps_main_thread_executor_owner() throws IOException {
        String text = refmap();
        String expected = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;m_18701_"
                + "(Ljava/util/function/BooleanSupplier;)V";
        assertTrue(text.contains(expected),
                "refmap 里 managedBlock 的映射必须保留 owner 为 ServerChunkCache$MainThreadExecutor 且方法名换成 "
                        + "SRG m_18701_。managedBlock 的 SRG 只声明在父类 BlockableEventLoop 上, 需要 AP 上溯父类"
                        + "再把 owner 搬回原类; Mixin 的 MemberInfo.matches 对 owner 是精确字符串相等且不回溯继承链, "
                        + "owner 一旦变成 BlockableEventLoop 就一处都匹配不上。期望片段: " + expected);
    }
}
