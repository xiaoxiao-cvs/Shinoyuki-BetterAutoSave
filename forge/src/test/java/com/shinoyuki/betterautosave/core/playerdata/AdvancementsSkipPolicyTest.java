package com.shinoyuki.betterautosave.core.playerdata;

import com.shinoyuki.betterautosave.config.ConfigSpec.AdvancementsSkipMode;
import org.junit.jupiter.api.Test;

import static com.shinoyuki.betterautosave.core.playerdata.AdvancementsSkipPolicy.Decision;
import static com.shinoyuki.betterautosave.core.playerdata.AdvancementsSkipPolicy.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * advancements 脏跳过判定的硬门禁。
 *
 * <p>判定标准 (删掉对应逻辑即挂):
 * <ul>
 *   <li>{@link #dirty_always_writes} —— 删掉 dirty 判断即挂。这是最重要的一条:
 *       脏了还跳过 = 玩家进度丢失</li>
 *   <li>{@link #off_never_skips} —— 删掉 OFF 短路即挂 (OFF 必须是逐字节 vanilla 行为)</li>
 *   <li>{@link #audit_never_skips} —— AUDIT 一旦真跳过就失去审计意义, 且是无声的行为变更</li>
 *   <li>{@link #force_full_write_bounds_skip_streak} —— 删掉强制全写即挂</li>
 * </ul>
 */
class AdvancementsSkipPolicyTest {

    @Test
    void off_never_skips() {
        for (int cycles = 0; cycles < 50; cycles++) {
            assertEquals(Decision.WRITE_FULL, decide(AdvancementsSkipMode.OFF, false, cycles, 12),
                    "OFF 必须逐字节保持 vanilla 行为, 任何情况都不得跳过");
        }
    }

    @Test
    void dirty_always_writes() {
        for (AdvancementsSkipMode mode : AdvancementsSkipMode.values()) {
            assertEquals(Decision.WRITE_FULL, decide(mode, true, 0, 12),
                    mode + ": 脏了必须写 —— 跳过就是丢玩家进度");
            assertEquals(Decision.WRITE_FULL, decide(mode, true, 999, 12),
                    mode + ": 脏了必须写, 与跳过计数无关");
        }
    }

    @Test
    void audit_never_skips() {
        assertEquals(Decision.WRITE_AUDIT, decide(AdvancementsSkipMode.AUDIT, false, 0, 12));
        assertEquals(Decision.WRITE_AUDIT, decide(AdvancementsSkipMode.AUDIT, false, 5, 12));
        // AUDIT 模式下永远不出现 SKIP: 它照常写盘, 只是额外对拍。
    }

    @Test
    void on_skips_when_clean() {
        assertEquals(Decision.SKIP, decide(AdvancementsSkipMode.ON, false, 0, 12));
        assertEquals(Decision.SKIP, decide(AdvancementsSkipMode.ON, false, 11, 12));
    }

    @Test
    void force_full_write_bounds_skip_streak() {
        // 连续跳过达到阈值必须强制全写, 无论脏标志怎么说 —— 这是对"第三方绕过 award() 改 progress"
        // 与"外部改动文件后 vanilla 本会自愈"两件事的唯一兜底。
        assertEquals(Decision.WRITE_FULL, decide(AdvancementsSkipMode.ON, false, 12, 12));
        assertEquals(Decision.WRITE_FULL, decide(AdvancementsSkipMode.ON, false, 100, 12));
        assertEquals(Decision.WRITE_FULL, decide(AdvancementsSkipMode.AUDIT, false, 12, 12));
    }

    @Test
    void zero_force_cycles_disables_forced_write() {
        for (int cycles : new int[]{0, 12, 1000, Integer.MAX_VALUE}) {
            assertEquals(Decision.SKIP, decide(AdvancementsSkipMode.ON, false, cycles, 0),
                    "forceFullWriteCycles=0 表示永不强制, 干净时应一直跳过");
        }
    }

    @Test
    void skip_streak_below_threshold_still_skips() {
        for (int cycles = 0; cycles < 12; cycles++) {
            assertEquals(Decision.SKIP, decide(AdvancementsSkipMode.ON, false, cycles, 12),
                    "未达阈值的第 " + cycles + " 次应继续跳过 (12 次里 11 次是免费的)");
        }
    }
}
