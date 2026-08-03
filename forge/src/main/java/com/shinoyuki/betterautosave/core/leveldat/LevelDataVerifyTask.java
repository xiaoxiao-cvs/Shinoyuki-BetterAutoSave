package com.shinoyuki.betterautosave.core.leveldat;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * 在 worker 线程上回读刚写完的 level.dat 并校验 (阶段 2d)。
 *
 * <p>只读不修: 发现问题只响亮报警并给出可用副本, 绝不在运行期自动回滚 —— 此刻服务器正在跑,
 * 用一份旧的世界元数据覆盖正在使用的会造成比原问题更大的混乱。修复留到下次启动由
 * {@code LevelDataIntegrityMixin} 按既定判据处理, 那时 {@code level.dat_old} 还完好。
 */
public final class LevelDataVerifyTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final Path levelDat;
    private final Path oldDat;
    private final LevelDataIntegrity.VerifyStrength strength;

    public LevelDataVerifyTask(Path levelDat, Path oldDat, LevelDataIntegrity.VerifyStrength strength) {
        this.levelDat = levelDat;
        this.oldDat = oldDat;
        this.strength = strength;
    }

    @Override
    public String taskName() {
        return "level.dat verify";
    }

    @Override
    public void execute() {
        LevelDataIntegrity.Result result = LevelDataIntegrity.verifyAfterWrite(levelDat, strength);
        if (result.usable()) {
            return;
        }
        boolean backupOk = LevelDataIntegrity.verify(oldDat).usable();
        // FATAL 级措辞: 这条如果出现, 说明刚落盘的世界元数据是坏的, 而进程还在正常运行 ——
        // 运维必须尽快介入, 否则下一次写盘会把还完好的 level.dat_old 也轮转掉。
        LOGGER.error("[BetterAutoSave] level.dat 写后回读校验未通过: {} ({}). "
                        + "刚写下的世界元数据已损坏, 而服务器仍在运行。level.dat_old {}。"
                        + "建议尽快停服并按 BAS 启动日志里的备份列表人工恢复; "
                        + "**不要**让服务器继续跑到下一次存盘, 那会把现有的 level.dat_old 也轮转掉",
                result.verdict(), result.detail(),
                backupOk ? "目前仍完好, 是当前最好的恢复源" : "同样不可用, 请改用 BAS 的启动备份");
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        LOGGER.error("[BetterAutoSave] level.dat 写后回读校验任务异常 (校验本身不影响已落盘数据)", cause);
    }

    @Override
    public void abandonOnDegrade() {
        // 纯校验任务, 降级时丢弃无数据后果 —— 它不持有任何待落盘内容, 也没有 gauge 需要配平。
        LOGGER.warn("[BetterAutoSave] pipeline 降级, 跳过本次 level.dat 写后回读校验");
    }
}
