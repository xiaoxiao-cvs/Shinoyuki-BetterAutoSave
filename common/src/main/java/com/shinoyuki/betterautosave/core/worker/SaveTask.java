package com.shinoyuki.betterautosave.core.worker;

import org.slf4j.LoggerFactory;

public interface SaveTask {

    String taskName();

    void execute() throws Exception;

    void onUnhandledError(Throwable cause);

    /**
     * P2: pipeline 降级 (degraded) 时, 队列里尚未执行的 task 的善后入口。
     *
     * <p>把善后契约放在接口上而不是让 {@code SnapshotPipeline} 用 instanceof 链路由, 是为了让
     * "新增一种 SaveTask 但忘了接善后"在**编译期**就没有发生空间。此前那条 instanceof 链没有 else
     * 分支, 而外层 {@code drainQueueOnDegrade} 无条件计数并写进"已善后 N 个"的 ERROR ——
     * 落不进任何分支的 task 被静默丢弃, 失败被伪装成成功。当前四个实现全部覆盖了本方法,
     * 所以默认实现不可达; 它存在的意义是: 万一将来新增第五种实现且忘了覆盖, 日志里会响亮暴露,
     * 而不是无声无息。
     */
    default void abandonOnDegrade() {
        LoggerFactory.getLogger("BetterAutoSave").error(
                "[BetterAutoSave] SaveTask 实现 {} 未提供 degraded 善后, 该任务被丢弃 (taskName={}); "
                        + "这是 BAS 自身的缺陷, 请反馈 issue",
                getClass().getName(), taskName());
    }
}
