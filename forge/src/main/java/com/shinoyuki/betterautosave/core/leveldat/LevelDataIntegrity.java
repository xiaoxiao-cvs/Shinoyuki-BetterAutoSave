package com.shinoyuki.betterautosave.core.leveldat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * level.dat 的启动期完整性检查、自动修复与冗余副本。
 *
 * <p><b>为什么 1.20.1 专用服需要这个</b>: vanilla 的 {@code LevelStorageSource.readLevelData}
 * 有两条读取路径 —— {@code level.dat} 不存在时会落到 {@code level.dat_old} 并成功 (覆盖
 * {@code Util.safeReplaceOrMoveFile} 的 rename 序列制造的文件缺失窗口); 但 {@code level.dat}
 * <b>存在而内容不可读</b>时, 专用服真正用的那个 reader 首行就
 * {@code catch (IOException) { throw new UncheckedIOException }}, 抛异常而非返回 null,
 * 第二条路径结构性不可达。异常最终被 {@code Main} 接住, 打印的却是
 * "Failed to load datapacks ... --safeMode", 进程不崩、退出码正常。
 *
 * <p>还有一类更隐蔽的: 文件是合法 gzip + 合法 NBT, 但 {@code Data} 缺失或
 * {@code Data.DataVersion} 这一个 int 丢了 —— {@code NbtUtils.getDataVersion} 兜底成 -1,
 * 触发 {@code WorldGenSettingsFix} 走"丢弃整张表重建"的语义, 后续链路清一色
 * {@code asX(default)} / {@code resultOrPartial} 无一处拦得下, 于是<b>服务器启动成功而世界种子
 * 静默变成 0</b>, spawn / 时间 / 天气 / gamerule / 世界边界 / 末影龙战全部回默认, 区域文件还在
 * (地形对不上)。启动继续走到那次 {@code saveDataTag} 时, 还会把这份默认数据写回并把损坏的
 * {@code level.dat} 轮转成 {@code level.dat_old} —— 同一次启动内销毁最后一份好副本, 全程零告警。
 *
 * <p>1.21 上游已修 ({@code getDataTagFallback} + {@code restoreLevelDataFromOld} 三级自愈 +
 * 隔离成 {@code level.dat_corrupted_<ts>} + 两级全失败时响亮中止)。本类把等价能力回移到 1.20.1。
 *
 * <p><b>刻意不做的事: 不从 BAS 自己的备份自动恢复。</b> vanilla 只认识 {@code level.dat} 与
 * {@code level.dat_old} 两个精确文件名, 永远不会去读我们的备份 —— 所以"只存一份备份"等于零闭合,
 * 真正干活的是本类的预检 + 文件层修复。而自动回滚到一份启动时的旧副本会倒退世界时间 / 天气 /
 * gamerule / 世界边界 / DragonFight / ScheduledEvents, 若期间 mod 集变过还会用旧的注册表 ID 表
 * 重映射整个世界的方块 —— 那是运维决策不是机器决策。自动恢复只从 {@code level.dat_old}
 * (最多退一个存盘周期) 走; BAS 备份只负责在最坏情况下还有东西可捞, 由人执行。
 */
public final class LevelDataIntegrity {

    /** 备份子目录. 放世界目录内, 使它跟着世界一起被备份 mod 归档、也跟着世界一起回滚, 保持自洽。 */
    public static final String BACKUP_DIR = "betterautosave/leveldat";

    /**
     * 保留几代备份。vanilla 的 {@code level.dat_old} 每次写盘都轮转, 只有一代 —— 一次带病启动
     * 就能吃掉它。3 代意味着故障必须连续熬过三次启动才会耗尽全部退路。
     */
    public static final int KEEP_BACKUPS = 3;

    public enum Verdict {
        /** 可读且语义完整。 */
        OK,
        /** 文件不存在或长度为 0。vanilla 对这一条本来就能回退, 属最轻。 */
        MISSING,
        /** 存在但 gzip/NBT 解不开 (截断、流损坏)。vanilla 的回退对这一条不可达。 */
        UNREADABLE,
        /** 解得开但缺 Data / DataVersion / LevelName。这是会静默重置世界的那一类。 */
        SEMANTICALLY_INCOMPLETE
    }

    public record Result(Verdict verdict, boolean registriesPresent, String detail) {
        public boolean usable() {
            return verdict == Verdict.OK;
        }
    }

    private LevelDataIntegrity() {
    }

    /** 逐层校验一个 level.dat。分层判据见类注释。 */
    public static Result verify(Path levelDat) {
        if (levelDat == null || !Files.isRegularFile(levelDat)) {
            return new Result(Verdict.MISSING, false, "文件不存在");
        }
        try {
            if (Files.size(levelDat) == 0L) {
                return new Result(Verdict.MISSING, false, "文件长度为 0");
            }
        } catch (IOException e) {
            return new Result(Verdict.UNREADABLE, false, "无法读取文件长度: " + e);
        }

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(levelDat.toFile());
        } catch (Throwable t) {
            return new Result(Verdict.UNREADABLE, false, "gzip/NBT 解析失败: " + t);
        }
        if (root == null) {
            return new Result(Verdict.UNREADABLE, false, "NBT 根为 null");
        }

        // L2: 这三项任一缺失都会让 vanilla 走进"静默重置世界"那条路。
        if (!root.contains("Data")) {
            return new Result(Verdict.SEMANTICALLY_INCOMPLETE, false, "根缺少 Data");
        }
        CompoundTag data = root.getCompound("Data");
        if (!data.contains("DataVersion")) {
            return new Result(Verdict.SEMANTICALLY_INCOMPLETE, false,
                    "Data 缺少 DataVersion (会让 DataFixer 按 -1 处理并重建世界生成设置)");
        }
        if (!data.contains("LevelName")) {
            return new Result(Verdict.SEMANTICALLY_INCOMPLETE, false, "Data 缺少 LevelName");
        }

        // L3: 只报警不判失败 —— vanilla 世界第一次用 Forge 打开时本来就没有 fml 段。
        boolean registries = root.contains("fml")
                && root.getCompound("fml").contains("Registries")
                && !root.getCompound("fml").getCompound("Registries").isEmpty();
        return new Result(Verdict.OK, registries, "ok");
    }

    /**
     * 用 {@code level.dat_old} 修复不可用的 {@code level.dat}: 先把坏的隔离, 再把备份复位。
     *
     * <p>只在 old 自身校验通过时才动手 —— 备份也坏就保持现场, 免得人工抢救少一份材料。
     *
     * @return true 表示已修复 (调用方随后让 vanilla 照常读)
     */
    public static boolean restoreFromOld(Path levelDat, Path oldDat, Path corruptedDest) throws IOException {
        if (!verify(oldDat).usable()) {
            return false;
        }
        if (Files.exists(levelDat)) {
            Files.createDirectories(corruptedDest.toAbsolutePath().getParent());
            Files.move(levelDat, corruptedDest, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(oldDat, levelDat, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * 把当前 level.dat 原样存一份到 {@code <world>/betterautosave/leveldat/}, 并把旧的裁到 keep 份。
     *
     * <p><b>必须是原始字节 copy, 不能是 tag 重序列化</b>: 重序列化产物字节不同 (gzip 重压 +
     * CompoundTag 用 HashMap 故 key 顺序按哈希), 无法与原文件做字节比对; 而且
     * {@code NbtIo.readCompressed} 读完根 tag 就 close, 不校验 GZIP trailer —— 重序列化会把
     * "文件其实被截断了"这个证据一并抹掉。
     *
     * @return 新备份的路径
     */
    public static Path backup(Path levelDat, Path worldDir, int keep) throws IOException {
        Path dir = worldDir.resolve(BACKUP_DIR);
        Files.createDirectories(dir);
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path dest = dir.resolve("level.dat." + stamp);
        Files.copy(levelDat, dest, StandardCopyOption.REPLACE_EXISTING);
        prune(dir, keep);
        return dest;
    }

    /** 按文件名 (时间戳) 倒序保留 keep 份, 其余删除。 */
    static void prune(Path dir, int keep) throws IOException {
        List<Path> all = listBackups(dir);
        for (int i = keep; i < all.size(); i++) {
            try {
                Files.deleteIfExists(all.get(i));
            } catch (IOException ignored) {
                // 清理失败无关紧要, 下次启动再试。
            }
        }
    }

    /** 现有备份, 新的在前。 */
    public static List<Path> listBackups(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("level.dat."))
                    .forEach(out::add);
        }
        // 文件名里的时间戳是 yyyy-MM-dd_HH-mm-ss, 字典序即时间序。
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return out;
    }
}
