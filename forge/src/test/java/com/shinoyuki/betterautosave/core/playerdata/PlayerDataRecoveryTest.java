package com.shinoyuki.betterautosave.core.playerdata;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * playerdata 读侧回退的硬门禁。
 *
 * <p>判定标准 (逐条对应一个必须存在的核心逻辑, 删掉即挂):
 * <ul>
 *   <li>{@link #missing_primary_is_restored_from_backup} —— 删掉 restoreMissingPrimary 的 copy 即挂。
 *       这条覆盖的是"落盘两次 rename 之间崩溃"这个真实事故形态</li>
 *   <li>{@link #unreadable_primary_is_quarantined_and_recovered} —— 删掉 quarantine 或备份读取即挂</li>
 *   <li>{@link #brand_new_player_is_untouched} —— 若把"无备份"误当成需要介入, 新玩家首次登录会被搞坏</li>
 *   <li>{@link #both_corrupt_leaves_scene_untouched} —— 备份也坏时必须保持现场, 不得隔离正本</li>
 * </ul>
 */
class PlayerDataRecoveryTest {

    private static final String UUID = "0b1e7c8a-0000-4000-8000-000000000001";

    private static CompoundTag playerTag(String marker, int xp) {
        CompoundTag tag = new CompoundTag();
        tag.putString("BasMarker", marker);
        tag.putInt("XpTotal", xp);
        tag.putInt("DataVersion", 3465);
        return tag;
    }

    private static void writeNbt(File target, CompoundTag tag) throws IOException {
        NbtIo.writeCompressed(tag, target);
    }

    @Test
    void missing_primary_is_restored_from_backup(@TempDir Path dir) throws IOException {
        File playerDir = dir.toFile();
        File backup = PlayerDataRecovery.backupFile(playerDir, UUID);
        writeNbt(backup, playerTag("from-backup", 4242));
        File primary = PlayerDataRecovery.primaryFile(playerDir, UUID);
        assertFalse(primary.exists(), "前置: 正本缺失, 模拟 safeReplaceFile 两次 rename 之间崩溃");

        PlayerDataRecovery.Outcome outcome =
                PlayerDataRecovery.restoreMissingPrimary(playerDir, UUID, "TestPlayer");

        assertEquals(PlayerDataRecovery.Outcome.RESTORED, outcome);
        assertTrue(primary.exists(), "正本必须被复位, 否则 vanilla 随后会把玩家当新号");
        CompoundTag restored = NbtIo.readCompressed(primary);
        assertEquals("from-backup", restored.getString("BasMarker"));
        assertEquals(4242, restored.getInt("XpTotal"), "恢复出来的必须是备份里的真实数据");
        assertTrue(backup.exists(), "备份必须保留 (用 copy 而非 move), 以防复位后的正本又出问题");
    }

    @Test
    void unreadable_primary_is_quarantined_and_recovered(@TempDir Path dir) throws IOException {
        File playerDir = dir.toFile();
        File primary = PlayerDataRecovery.primaryFile(playerDir, UUID);
        // 截断的 gzip: 模拟写到一半被 kill。
        Files.write(primary.toPath(), "not-a-gzip-stream".getBytes(StandardCharsets.UTF_8));
        byte[] corruptBytes = Files.readAllBytes(primary.toPath());
        writeNbt(PlayerDataRecovery.backupFile(playerDir, UUID), playerTag("from-backup", 777));

        CompoundTag recovered =
                PlayerDataRecovery.recoverUnreadablePrimary(playerDir, UUID, "TestPlayer");

        assertNotNull(recovered, "备份可读时必须恢复出 tag");
        assertEquals(777, recovered.getInt("XpTotal"));

        File[] quarantined = playerDir.listFiles((d, n) -> n.startsWith(UUID + "_corrupted_"));
        assertNotNull(quarantined);
        assertEquals(1, quarantined.length, "损坏原件必须被隔离保留供排查, 不能被回收掉");
        assertArrayEquals(corruptBytes, Files.readAllBytes(quarantined[0].toPath()),
                "隔离副本必须是原始字节, 不得被改写");
        assertFalse(primary.exists(), "坏正本必须从正本位置挪走");
    }

    @Test
    void brand_new_player_is_untouched(@TempDir Path dir) {
        File playerDir = dir.toFile();

        PlayerDataRecovery.Outcome outcome =
                PlayerDataRecovery.restoreMissingPrimary(playerDir, UUID, "NewPlayer");

        assertEquals(PlayerDataRecovery.Outcome.NO_BACKUP, outcome);
        assertFalse(PlayerDataRecovery.primaryFile(playerDir, UUID).exists(),
                "新玩家首次登录: 不得凭空造出正本");
        assertNull(PlayerDataRecovery.recoverUnreadablePrimary(playerDir, UUID, "NewPlayer"),
                "无备份时必须返回 null 交回 vanilla 的新号行为");
    }

    @Test
    void healthy_primary_is_not_touched(@TempDir Path dir) throws IOException {
        File playerDir = dir.toFile();
        File primary = PlayerDataRecovery.primaryFile(playerDir, UUID);
        writeNbt(primary, playerTag("live", 1));
        byte[] before = Files.readAllBytes(primary.toPath());
        writeNbt(PlayerDataRecovery.backupFile(playerDir, UUID), playerTag("stale-backup", 0));

        PlayerDataRecovery.Outcome outcome =
                PlayerDataRecovery.restoreMissingPrimary(playerDir, UUID, "TestPlayer");

        assertEquals(PlayerDataRecovery.Outcome.NOT_NEEDED, outcome);
        assertArrayEquals(before, Files.readAllBytes(primary.toPath()),
                "正本健康时绝不能被旧备份覆盖 —— 那是把玩家回档");
    }

    @Test
    void both_corrupt_leaves_scene_untouched(@TempDir Path dir) throws IOException {
        File playerDir = dir.toFile();
        File primary = PlayerDataRecovery.primaryFile(playerDir, UUID);
        File backup = PlayerDataRecovery.backupFile(playerDir, UUID);
        Files.write(primary.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        Files.write(backup.toPath(), "also-broken".getBytes(StandardCharsets.UTF_8));

        assertNull(PlayerDataRecovery.recoverUnreadablePrimary(playerDir, UUID, "TestPlayer"),
                "两边都坏时必须返回 null");
        assertTrue(primary.exists(), "备份不可用时不得隔离正本 —— 那会让人工抢救少一份材料");
        File[] quarantined = playerDir.listFiles((d, n) -> n.contains("_corrupted_"));
        assertNotNull(quarantined);
        assertEquals(0, quarantined.length, "不得产生隔离副本");
    }

    @Test
    void missing_primary_with_corrupt_backup_is_left_alone(@TempDir Path dir) throws IOException {
        File playerDir = dir.toFile();
        Files.write(PlayerDataRecovery.backupFile(playerDir, UUID).toPath(),
                "broken".getBytes(StandardCharsets.UTF_8));

        PlayerDataRecovery.Outcome outcome =
                PlayerDataRecovery.restoreMissingPrimary(playerDir, UUID, "TestPlayer");

        assertEquals(PlayerDataRecovery.Outcome.BACKUP_UNREADABLE, outcome);
        assertFalse(PlayerDataRecovery.primaryFile(playerDir, UUID).exists(),
                "备份不可解析时不得把它复位成正本");
    }
}
