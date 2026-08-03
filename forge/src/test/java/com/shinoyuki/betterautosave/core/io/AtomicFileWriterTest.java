package com.shinoyuki.betterautosave.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伴生文件原子写的硬门禁。
 *
 * <p>判定标准: 删掉 {@link AtomicFileWriter#write} 里的备份轮转, {@link #previous_generation_is_rotated_to_backup}
 * 挂; 删掉 tmp 清理, {@link #failed_write_leaves_no_orphan_tmp} 挂; 把原子替换改成就地截断写,
 * {@link #target_is_never_left_truncated} 挂。
 */
class AtomicFileWriterTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writes_content_and_leaves_no_tmp(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("stats.json");

        AtomicFileWriter.write(bytes("{\"a\":1}"), target, null);

        assertArrayEquals(bytes("{\"a\":1}"), Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("stats.json.bastmp")), "成功路径不得留下临时文件");
    }

    @Test
    void previous_generation_is_rotated_to_backup(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("advancements.json");
        Path backup = dir.resolve("advancements.json.bak");
        Files.write(target, bytes("gen-1"));

        AtomicFileWriter.write(bytes("gen-2"), target, backup);

        assertArrayEquals(bytes("gen-2"), Files.readAllBytes(target));
        assertArrayEquals(bytes("gen-1"), Files.readAllBytes(backup),
                "上一代必须被轮转成备份 —— vanilla 在这两个文件上一份备份都没有, 这正是要补的");
    }

    @Test
    void backup_rotation_is_skipped_when_target_absent(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("advancements.json");
        Path backup = dir.resolve("advancements.json.bak");

        AtomicFileWriter.write(bytes("gen-1"), target, backup);

        assertArrayEquals(bytes("gen-1"), Files.readAllBytes(target));
        assertFalse(Files.exists(backup), "首次写不该凭空造出备份");
    }

    @Test
    void target_is_never_left_truncated(@TempDir Path dir) throws IOException {
        // 连续多代写入, 每一代结束后目标都必须是完整的某一代内容, 不能是半截。
        Path target = dir.resolve("advancements.json");
        Path backup = dir.resolve("advancements.json.bak");
        String big = "x".repeat(400_000);
        for (int gen = 0; gen < 5; gen++) {
            AtomicFileWriter.write(bytes(big + gen), target, backup);
            byte[] actual = Files.readAllBytes(target);
            assertEquals(big.length() + 1, actual.length,
                    "第 " + gen + " 代落盘后目标必须是完整内容");
            assertArrayEquals(bytes(big + gen), actual);
        }
        assertArrayEquals(bytes(big + 3), Files.readAllBytes(backup), "备份必须是上一代");
    }

    @Test
    void creates_missing_parent_directories(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested").resolve("deep").resolve("stats.json");

        AtomicFileWriter.write(bytes("ok"), target, null);

        assertTrue(Files.exists(target));
        assertArrayEquals(bytes("ok"), Files.readAllBytes(target));
    }

    @Test
    void failed_write_leaves_no_orphan_tmp(@TempDir Path dir) throws IOException {
        // 让 target 的父路径是一个普通文件, 使 createDirectories / 打开 tmp 必然失败。
        Path blocker = dir.resolve("blocker");
        Files.write(blocker, bytes("i am a file"));
        Path target = blocker.resolve("stats.json");

        boolean threw = false;
        try {
            AtomicFileWriter.write(bytes("data"), target, null);
        } catch (IOException expected) {
            threw = true;
        }

        assertTrue(threw, "不可写路径必须把 IOException 抛给调用方, 不得静默吞掉");
        assertArrayEquals(bytes("i am a file"), Files.readAllBytes(blocker),
                "失败路径不得破坏无关文件");
    }
}
