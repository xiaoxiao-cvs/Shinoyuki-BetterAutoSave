package com.shinoyuki.betterautosave.core.io;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 通用的 tmp + fsync + ATOMIC_MOVE 原子写, 供非 NBT 的伴生文件 (advancements / stats 的 JSON) 使用.
 *
 * <p><b>为什么需要它</b>: {@code PlayerList.save} 每个玩家写三个文件, 只有
 * {@code playerdata/<uuid>.dat} 享受 vanilla 的临时文件 + 原子替换 + {@code .dat_old} 备份.
 * 另外两个是就地截断写:
 * <ul>
 *   <li>{@code ServerStatsCounter.save} -> {@code FileUtils.writeStringToFile(this.file, ...)}</li>
 *   <li>{@code PlayerAdvancements.save} -> {@code Files.newBufferedWriter(path)}
 *       (CREATE + TRUNCATE_EXISTING)</li>
 * </ul>
 * 两者都是先把唯一一份副本截断再往里流式写. 写到一半被 kill 就留下截断的 JSON;
 * 下次登录解析失败 -> 异常被 catch 后**继续加载** -> 玩家带空进度上线 ->
 * 下一次 autosave 把空进度写回, 永久坐实. 大整合包下 advancements 可达几百 KB, 写窗口不小.
 *
 * <p><b>与 {@link AtomicNbtWriter} 的关系</b>: 后者的原子写骨架是私有的且绑定 NBT/gzip 语义.
 * 本类是同一骨架的字节数组版本, 额外支持写前把现有目标轮转成备份. 两者暂时并存, 待
 * {@code AtomicNbtWriter} 泛化后由它委托过来 (届时现有 SavedData 落盘行为必须逐字节不变).
 *
 * <p><b>临时文件命名</b>: 用 {@code <target>.bastmp} 固定名而非 {@code createTempFile} 唯一名.
 * 固定名要求调用方保证同一目标同一时刻只有一个写者 —— 本类当前的两个调用方
 * (advancements / stats) 都在服务器主线程上串行执行, 满足该前提. **若将来有并发写者接入,
 * 必须改成唯一名**, 否则两个写者会打开同一 tmp 交错内容, 再被 ATOMIC_MOVE 原子地发布出去.
 */
public final class AtomicFileWriter {

    /** 临时文件后缀. 与 vanilla 的 {@code .tmp} 区分, 便于运维一眼看出是 BAS 的残留。 */
    private static final String TMP_SUFFIX = ".bastmp";

    private AtomicFileWriter() {
    }

    /**
     * 把 {@code content} 原子写入 {@code target}, 落盘前做一次 fsync.
     *
     * <p>调用方必须不在服务器主线程上 —— fsync 会同步等设备确认。主线程路径请用四参重载并传
     * {@code fsync=false}.
     */
    public static void write(byte[] content, Path target, Path backup) throws IOException {
        write(content, target, backup, true);
    }

    /**
     * 把 {@code content} 原子写入 {@code target}.
     *
     * @param backup 非 null 时, 在替换之前把现有 target 轮转到该路径 (目标不存在则跳过轮转).
     *               轮转紧邻替换发生, 不提前做 —— 提前轮转会留下"备份是上一代、正本缺失"的窗口。
     * @param fsync  替换之前是否等临时文件真正落到设备。消除截断窗口靠的是 tmp + 原子替换本身,
     *               与本参数无关; fsync 额外覆盖的是"改名已持久但数据还在 page cache 里"时掉电,
     *               得到一个长度正确、内容全零的文件。它的代价是一次同步刷盘, 在主线程上按在线
     *               人数放大, 故主线程调用方一律传 false (见 playerData.sidecarFsync)。
     */
    public static void write(byte[] content, Path target, Path backup, boolean fsync) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        // tmp 与 target 同目录, 保证 ATOMIC_MOVE 在同一文件系统卷上 (跨卷 move 不可原子).
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                fos.write(content);
                fos.flush();
                if (fsync) {
                    fos.getFD().sync();
                }
            }
            if (backup != null && Files.exists(target)) {
                // 轮转失败不阻断主流程: 没有备份仍然好过不写新内容, 而新内容本身是原子替换的。
                try {
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // best-effort, 继续走替换。
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 文件系统不支持原子 move: 降级为非原子替换。tmp 内容已完整, 仍优于就地截断写。
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败无关紧要, 原 IOException 优先上抛。
            }
            throw e;
        }
    }
}
