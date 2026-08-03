package com.shinoyuki.betterautosave.core.leveldat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * level.dat 启动预检 / 修复 / 备份的硬门禁。
 *
 * <p>判定标准 (删掉对应逻辑即挂):
 * <ul>
 *   <li>{@link #missing_data_version_is_semantically_incomplete} —— 删掉 DataVersion 判据即挂。
 *       这条是最阴险的一类: 文件合法但缺这一个 int 就会让服务器带着 seed=0 静默启动</li>
 *   <li>{@link #broken_primary_is_repaired_from_old} —— 删掉 restoreFromOld 即挂</li>
 *   <li>{@link #broken_old_leaves_scene_untouched} —— 备份也坏时必须保持现场</li>
 *   <li>{@link #backup_is_raw_byte_copy} —— 改成 tag 重序列化即挂 (字节会变, 截断证据丢失)</li>
 *   <li>{@link #prune_keeps_newest_only} —— 删掉裁剪即挂</li>
 * </ul>
 */
class LevelDataIntegrityTest {

    private static CompoundTag levelRoot(String name, int dataVersion, boolean withRegistries) {
        CompoundTag data = new CompoundTag();
        data.putString("LevelName", name);
        data.putInt("DataVersion", dataVersion);
        data.putLong("Time", 12345L);
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        if (withRegistries) {
            CompoundTag registries = new CompoundTag();
            CompoundTag one = new CompoundTag();
            one.putString("dummy", "x");
            registries.put("minecraft:block", one);
            CompoundTag fml = new CompoundTag();
            fml.put("Registries", registries);
            root.put("fml", fml);
        }
        return root;
    }

    private static void write(Path p, CompoundTag tag) throws IOException {
        NbtIo.writeCompressed(tag, p.toFile());
    }

    @Test
    void healthy_file_verifies_ok(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        write(levelDat, levelRoot("world", 3465, true));

        LevelDataIntegrity.Result r = LevelDataIntegrity.verify(levelDat);

        assertEquals(LevelDataIntegrity.Verdict.OK, r.verdict());
        assertTrue(r.usable());
        assertTrue(r.registriesPresent());
    }

    @Test
    void missing_file_is_missing(@TempDir Path dir) {
        LevelDataIntegrity.Result r = LevelDataIntegrity.verify(dir.resolve("level.dat"));
        assertEquals(LevelDataIntegrity.Verdict.MISSING, r.verdict());
    }

    @Test
    void zero_length_file_is_missing(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        Files.write(levelDat, new byte[0]);
        assertEquals(LevelDataIntegrity.Verdict.MISSING, LevelDataIntegrity.verify(levelDat).verdict());
    }

    @Test
    void truncated_gzip_is_unreadable(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        write(levelDat, levelRoot("world", 3465, true));
        byte[] full = Files.readAllBytes(levelDat);
        Files.write(levelDat, java.util.Arrays.copyOf(full, full.length / 2));

        assertEquals(LevelDataIntegrity.Verdict.UNREADABLE, LevelDataIntegrity.verify(levelDat).verdict());
    }

    @Test
    void missing_data_version_is_semantically_incomplete(@TempDir Path dir) throws IOException {
        // 合法 gzip + 合法 NBT, 只少了 Data.DataVersion 这一个 int ——
        // vanilla 会按 -1 处理, 让世界生成设置整张表被丢弃重建, 服务器带 seed=0 静默启动成功。
        Path levelDat = dir.resolve("level.dat");
        CompoundTag root = levelRoot("world", 3465, true);
        root.getCompound("Data").remove("DataVersion");
        write(levelDat, root);

        LevelDataIntegrity.Result r = LevelDataIntegrity.verify(levelDat);

        assertEquals(LevelDataIntegrity.Verdict.SEMANTICALLY_INCOMPLETE, r.verdict(),
                "缺 DataVersion 必须判为不可用 —— 它会让服务器静默重置世界而不是报错");
        assertFalse(r.usable());
    }

    @Test
    void missing_data_compound_is_semantically_incomplete(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        CompoundTag root = new CompoundTag();
        root.putString("unrelated", "x");
        write(levelDat, root);

        assertEquals(LevelDataIntegrity.Verdict.SEMANTICALLY_INCOMPLETE,
                LevelDataIntegrity.verify(levelDat).verdict());
    }

    @Test
    void missing_registries_warns_but_still_usable(@TempDir Path dir) throws IOException {
        // 原版世界第一次用 Forge 打开就长这样, 不是故障。
        Path levelDat = dir.resolve("level.dat");
        write(levelDat, levelRoot("world", 3465, false));

        LevelDataIntegrity.Result r = LevelDataIntegrity.verify(levelDat);

        assertTrue(r.usable(), "缺 fml/Registries 不得判为不可用");
        assertFalse(r.registriesPresent());
    }

    @Test
    void broken_primary_is_repaired_from_old(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        Path oldDat = dir.resolve("level.dat_old");
        Path corrupted = dir.resolve("level.dat_corrupted_test");
        Files.write(levelDat, "broken".getBytes(StandardCharsets.UTF_8));
        byte[] brokenBytes = Files.readAllBytes(levelDat);
        write(oldDat, levelRoot("recovered", 3465, true));

        assertTrue(LevelDataIntegrity.restoreFromOld(levelDat, oldDat, corrupted));

        assertTrue(LevelDataIntegrity.verify(levelDat).usable(), "修复后正本必须可用");
        assertEquals("recovered",
                NbtIo.readCompressed(levelDat.toFile()).getCompound("Data").getString("LevelName"));
        assertArrayEquals(brokenBytes, Files.readAllBytes(corrupted),
                "损坏原件必须原样隔离保留供排查");
        assertTrue(Files.exists(oldDat), "备份本身不得被移走");
    }

    @Test
    void broken_old_leaves_scene_untouched(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        Path oldDat = dir.resolve("level.dat_old");
        Files.write(levelDat, "broken".getBytes(StandardCharsets.UTF_8));
        Files.write(oldDat, "also-broken".getBytes(StandardCharsets.UTF_8));

        assertFalse(LevelDataIntegrity.restoreFromOld(levelDat, oldDat, dir.resolve("c")),
                "备份不可用时必须返回 false");
        assertArrayEquals("broken".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(levelDat),
                "备份不可用时不得动正本 —— 人工抢救需要它");
        assertFalse(Files.exists(dir.resolve("c")), "不得产生隔离副本");
    }

    @Test
    void backup_is_raw_byte_copy(@TempDir Path dir) throws IOException {
        Path world = Files.createDirectories(dir.resolve("world"));
        Path levelDat = world.resolve("level.dat");
        write(levelDat, levelRoot("world", 3465, true));
        byte[] original = Files.readAllBytes(levelDat);

        Path dest = LevelDataIntegrity.backup(levelDat, world, 3);

        assertArrayEquals(original, Files.readAllBytes(dest),
                "备份必须是原始字节 copy —— tag 重序列化会改变字节, 且抹掉文件被截断的证据");
        assertTrue(dest.startsWith(world.resolve("betterautosave").resolve("leveldat")));
    }

    @Test
    void prune_keeps_newest_only(@TempDir Path dir) throws IOException {
        Path backupDir = Files.createDirectories(dir.resolve("betterautosave/leveldat"));
        for (String stamp : new String[]{
                "2026-08-01_10-00-00", "2026-08-02_10-00-00",
                "2026-08-03_10-00-00", "2026-08-04_10-00-00", "2026-08-05_10-00-00"}) {
            Files.write(backupDir.resolve("level.dat." + stamp), stamp.getBytes(StandardCharsets.UTF_8));
        }

        LevelDataIntegrity.prune(backupDir, 3);

        List<Path> remaining = LevelDataIntegrity.listBackups(backupDir);
        assertEquals(3, remaining.size());
        assertEquals("level.dat.2026-08-05_10-00-00", remaining.get(0).getFileName().toString(),
                "最新的必须在前");
        assertEquals("level.dat.2026-08-03_10-00-00", remaining.get(2).getFileName().toString(),
                "必须保留最新的 3 代, 删掉更旧的");
    }

    /**
     * 复现 vanilla {@code Util.safeReplaceFile} 换名过程中 level.dat 短暂不存在的窗口: 单次判定
     * 会把一个完全健康的世界报成 MISSING, 而那条告警指示运维停服做破坏性回滚。
     */
    @Test
    void single_shot_verify_reports_missing_inside_rename_window(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        // 窗口中的状态: 正本已被移走, 尚未换上新的。
        assertEquals(LevelDataIntegrity.Verdict.MISSING,
                LevelDataIntegrity.verifyAfterWrite(levelDat, LevelDataIntegrity.VerifyStrength.CHECKSUM)
                        .verdict(),
                "单次判定在换名窗口里必然误判 —— 这正是需要重试的原因");
    }

    @Test
    void retry_rides_out_the_rename_window(@TempDir Path dir) throws Exception {
        Path levelDat = dir.resolve("level.dat");
        CompoundTag good = levelRoot("world", 3465, true);
        // 模拟窗口: 校验开始时文件不存在, 120ms 后 (第 2 次尝试之前) 才落位。
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(120L);
                write(levelDat, good);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writer.start();
        try {
            LevelDataIntegrity.Result result = LevelDataIntegrity.verifyAfterWriteWithRetry(
                    levelDat, LevelDataIntegrity.VerifyStrength.CHECKSUM, 3, 150L);
            assertEquals(LevelDataIntegrity.Verdict.OK, result.verdict(),
                    "重试必须把换名窗口排除掉, 否则健康世界会被报成损坏并触发人工回滚");
            assertTrue(result.usable());
        } finally {
            writer.join();
        }
    }

    @Test
    void retry_does_not_mask_a_genuinely_broken_file(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        Files.write(levelDat, "not-gzip".getBytes(StandardCharsets.UTF_8));

        LevelDataIntegrity.Result result = LevelDataIntegrity.verifyAfterWriteWithRetry(
                levelDat, LevelDataIntegrity.VerifyStrength.CHECKSUM, 3, 10L);

        assertEquals(LevelDataIntegrity.Verdict.UNREADABLE, result.verdict(),
                "重试只排除瞬时窗口, 真损坏必须照报 —— 否则这层校验就白做了");
    }

    @Test
    void retry_returns_immediately_when_first_attempt_passes(@TempDir Path dir) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        write(levelDat, levelRoot("world", 3465, true));

        long start = System.nanoTime();
        LevelDataIntegrity.Result result = LevelDataIntegrity.verifyAfterWriteWithRetry(
                levelDat, LevelDataIntegrity.VerifyStrength.CHECKSUM, 3, 5_000L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(LevelDataIntegrity.Verdict.OK, result.verdict());
        assertTrue(elapsedMs < 1_000L,
                "通过路径不得付任何重试代价, 实测耗时 " + elapsedMs + "ms");
    }
}
