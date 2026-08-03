package com.shinoyuki.betterautosave.core.playerdata;

import com.shinoyuki.betterautosave.config.ConfigSpec;

/**
 * advancements 脏跳过的判定, 抽成纯函数以便单测 (mixin 里那份状态没法在裸 JUnit 构造)。
 *
 * <p><b>为什么这是本轮最大的杠杆</b>: {@code PlayerAdvancements.save()} 完全没有 dirty 判断 ——
 * 每次 autosave 都遍历全部已加载成就、按 {@code hasProgress()} 过滤、经 Gson 重建整棵 JSON 树、
 * pretty-print, 再把整个文件写出去, 不管内容变没变。137 mod 生产服实测它占整个
 * {@code PlayerList.saveAll} 的 55%, 约为写玩家真实 NBT 的两倍。
 *
 * <p>而它几乎从不变: 跨三次连续 autosave 采样在线玩家的 advancements 文件, 每次 md5 完全相同
 * (mtime 在推进, 内容不动), 同期 playerdata 与 stats 每轮都变。全服 31 名玩家 18,883 条
 * criterion 时间戳里 89% 超过 30 天未变, 过去 24 小时新增只有 36 条 (0.19%) —— 绝大部分是
 * {@code minecraft:recipes/*} 这类一次解锁永不再变的条目。
 *
 * <p><b>跳过是磁盘字节等价的</b>: 不写 = 文件保持上一次写下的内容, 而上一次写下的内容与这一次
 * 会写的完全一致。备份 mod 看到的数据一模一样。
 */
public final class AdvancementsSkipPolicy {

    public enum Decision {
        /** 正常全量写盘, 并把脏标志清掉、强制全写计数归零。 */
        WRITE_FULL,
        /** 完全不写。仅 ON 模式且判定为干净时出现。 */
        SKIP,
        /**
         * 照常写盘, 但同时对拍"按脏标志本该跳过"与"内容其实变了"是否矛盾。
         * 仅 AUDIT 模式且判定为干净时出现 —— 没有性能收益, 是把"我论证过它不会变"变成
         * "我在这台服上实测它没变过"的唯一手段。
         */
        WRITE_AUDIT
    }

    private AdvancementsSkipPolicy() {
    }

    /**
     * @param mode                当前档位
     * @param dirty               自上次成功写盘以来 award/revoke/load/reload 是否发生过
     * @param cyclesSinceFullWrite 自上次全量写盘以来连续跳过的次数
     * @param forceFullWriteCycles 连续跳过多少次后强制全写一次; 0 表示永不强制
     */
    public static Decision decide(ConfigSpec.AdvancementsSkipMode mode,
                                  boolean dirty,
                                  int cyclesSinceFullWrite,
                                  int forceFullWriteCycles) {
        if (mode == ConfigSpec.AdvancementsSkipMode.OFF) {
            return Decision.WRITE_FULL;
        }
        if (dirty) {
            return Decision.WRITE_FULL;
        }
        // 周期性强制全写: 覆盖第三方绕过 award() 直接改 progress 的路径 (例如自己拿
        // getOrStartProgress(adv).grantProgress(key)), 同时恢复 vanilla "每次重写"对外部改动
        // (备份还原 / 管理员手改文件) 的自愈性 —— 那份自愈是跳过之后唯一丢掉的东西。
        if (forceFullWriteCycles > 0 && cyclesSinceFullWrite >= forceFullWriteCycles) {
            return Decision.WRITE_FULL;
        }
        return mode == ConfigSpec.AdvancementsSkipMode.ON ? Decision.SKIP : Decision.WRITE_AUDIT;
    }
}
