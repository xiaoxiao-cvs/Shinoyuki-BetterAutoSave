package com.shinoyuki.betterautosave.core.playerdata;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * playerdata 读侧回退: {@code <uuid>.dat} 缺失或不可读时, 隔离残骸并从 vanilla 自己刚写下的
 * {@code <uuid>.dat_old} 恢复, 而不是让玩家以新号身份上线.
 *
 * <p><b>为什么这是 Critical</b>: {@code PlayerDataStorage.save} 通过
 * {@code Util.safeReplaceFile} 落盘, 该方法先把正本 rename 成 {@code .dat_old}, 再把临时文件
 * rename 成正本. 而 {@code PlayerDataStorage.load} **只读 {@code <uuid>.dat} 这一个文件**:
 * 缺失或读失败时 vanilla 只打一条无堆栈的 WARN, 返回 null, 于是 {@code player.load} 根本不被调用
 * —— 玩家以全新身份上线, 背包 / 末影箱 / 坐标 / 经验 / 游戏模式 / 所有挂在 ForgeCaps 上的 mod 数据
 * 全部归零. 而 vanilla 自己几毫秒前写下的完好 {@code .dat_old} 就躺在旁边, 从不被查阅;
 * 下一次 autosave 会用这个空玩家把它覆盖掉.
 *
 * <p>也就是说: 在那两次 rename 之间崩溃或掉电, 就会静默清空一个玩家的整个存档, 而完整备份
 * 就在同一目录里. 运维大约只有一个 autosave 周期的时间发现它.
 *
 * <p><b>1.21 上游已修</b>: 读失败的文件被另存为 {@code <uuid>_corrupted_<时间戳>.dat},
 * 然后尝试 {@code .dat_old}. 本类把该行为回移到 1.20.1, 含隔离副本 —— 不可读的原件被留存供事后
 * 分析, 而不是被回收掉.
 *
 * <p><b>两条路径分开处理</b>:
 * <ul>
 *   <li>{@link #restoreMissingPrimary} 在 vanilla 读之前跑, 只处理"正本缺失"(rename 窗口崩溃).
 *       它把备份复位成正本后就让 vanilla 照常读 —— datafixer / {@code player.load} /
 *       {@code PlayerLoadFromFile} 事件的顺序与 vanilla 逐行一致, 零行为偏差。</li>
 *   <li>{@link #recoverUnreadablePrimary} 在 vanilla 读失败之后跑, 处理"正本存在但不可读".
 *       这条路径上 vanilla 已经把玩家当成新号 (事件已发), 我们再补救。</li>
 * </ul>
 */
public final class PlayerDataRecovery {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private static final DateTimeFormatter QUARANTINE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public enum Outcome {
        /** 正本可读或本就不该介入 (新玩家首次登录), 未做任何事. */
        NOT_NEEDED,
        /** 已从 {@code .dat_old} 恢复. */
        RESTORED,
        /** 正本有问题但没有可用备份, 保持 vanilla 行为不动现场. */
        NO_BACKUP,
        /** 备份本身也不可读, 保持 vanilla 行为不动现场. */
        BACKUP_UNREADABLE
    }

    private PlayerDataRecovery() {
    }

    public static File primaryFile(File playerDir, String uuid) {
        return new File(playerDir, uuid + ".dat");
    }

    public static File backupFile(File playerDir, String uuid) {
        return new File(playerDir, uuid + ".dat_old");
    }

    /**
     * 正本缺失时, 把 {@code .dat_old} 复位成 {@code .dat}, 让 vanilla 随后照常读到它.
     *
     * <p>只在正本**不存在**时介入, 不读正本 —— 因此正常登录路径上零额外 IO (一次 exists 检查).
     * 正本存在但内容坏的情况由 {@link #recoverUnreadablePrimary} 兜.
     *
     * <p>复位用 copy 而非 move: 保留 {@code .dat_old} 不动, 万一复位后的正本又出问题, 备份还在.
     */
    public static Outcome restoreMissingPrimary(File playerDir, String uuid, String playerName) {
        File primary = primaryFile(playerDir, uuid);
        if (primary.exists() && primary.isFile()) {
            return Outcome.NOT_NEEDED;
        }
        File backup = backupFile(playerDir, uuid);
        if (!backup.exists() || !backup.isFile()) {
            // 新玩家首次登录走的就是这条, 属正常情况, 不打日志.
            return Outcome.NO_BACKUP;
        }
        if (readOrNull(backup) == null) {
            LOGGER.error("[BetterAutoSave] 玩家 {} ({}) 的 {}.dat 缺失, 且备份 {}.dat_old 也无法解析; "
                    + "保持原版行为不动现场, 该玩家将以新号上线. 请人工检查这两个文件",
                    playerName, uuid, uuid, uuid);
            return Outcome.BACKUP_UNREADABLE;
        }
        try {
            Files.copy(backup.toPath(), primary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[BetterAutoSave] 玩家 {} ({}) 的 {}.dat 缺失, 从 {}.dat_old 复位失败; "
                    + "该玩家将以新号上线", playerName, uuid, uuid, uuid, e);
            return Outcome.NO_BACKUP;
        }
        LOGGER.error("[BetterAutoSave] 玩家 {} ({}) 的 playerdata 正本缺失, 已从 {}.dat_old 恢复. "
                + "这通常意味着上次保存写到一半被中断 (崩溃 / 掉电 / 强杀); "
                + "原版在这种情况下会让该玩家以新号上线并丢失全部数据",
                playerName, uuid, uuid);
        return Outcome.RESTORED;
    }

    /**
     * 正本存在但 vanilla 读失败时: 隔离残骸, 从备份恢复, 返回恢复出来的 tag.
     *
     * @return 恢复成功返回 tag; 无可用备份返回 null (调用方保持 vanilla 的新号行为)
     */
    public static CompoundTag recoverUnreadablePrimary(File playerDir, String uuid, String playerName) {
        File primary = primaryFile(playerDir, uuid);
        File backup = backupFile(playerDir, uuid);
        if (!backup.exists() || !backup.isFile()) {
            if (primary.exists()) {
                LOGGER.error("[BetterAutoSave] 玩家 {} ({}) 的 {}.dat 无法解析且没有 {}.dat_old 可回退; "
                        + "保持原版行为, 该玩家将以新号上线", playerName, uuid, uuid, uuid);
            }
            return null;
        }
        CompoundTag recovered = readOrNull(backup);
        if (recovered == null) {
            LOGGER.error("[BetterAutoSave] 玩家 {} ({}) 的 {}.dat 与 {}.dat_old 均无法解析; "
                    + "保持原版行为不动现场", playerName, uuid, uuid, uuid);
            return null;
        }
        quarantine(primary, playerDir, uuid, playerName);
        LOGGER.error("[BetterAutoSave] 玩家 {} ({}) 的 playerdata 正本无法解析, 已从 {}.dat_old 恢复; "
                + "损坏的原件已隔离保留供排查", playerName, uuid, uuid);
        return recovered;
    }

    /** 把不可读的正本另存为 {@code <uuid>_corrupted_<时间戳>.dat}, 与 1.21 上游同名. */
    private static void quarantine(File primary, File playerDir, String uuid, String playerName) {
        if (!primary.exists()) {
            return;
        }
        File dest = new File(playerDir, uuid + "_corrupted_" + LocalDateTime.now().format(QUARANTINE_STAMP) + ".dat");
        try {
            // move 而非 copy: 必须把坏文件从正本位置挪走, 否则下面的恢复无从落位。
            Files.move(primary.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[BetterAutoSave] 玩家 {} ({}) 的损坏 playerdata 隔离失败, 恢复仍会继续",
                    playerName, uuid, e);
        }
    }

    /**
     * 读一个 playerdata 文件, 任何失败返回 null.
     *
     * <p>刻意走 {@code NbtIo.readCompressed(File)} 这个与 vanilla 完全相同的重载 (内部用无配额
     * accounter). 换成带配额的读法会让大背包玩家撞配额异常, 恰好走回"以新号上线"这条我们正要堵的路。
     */
    private static CompoundTag readOrNull(File file) {
        try {
            return NbtIo.readCompressed(file);
        } catch (Throwable t) {
            return null;
        }
    }
}
