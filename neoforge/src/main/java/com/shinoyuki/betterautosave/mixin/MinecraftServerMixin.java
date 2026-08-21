package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterautosave.diagnostic.TickGapDetector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    @Final
    private static int AUTOSAVE_INTERVAL;

    @Shadow
    private int tickCount;

    @Shadow
    public abstract float getCurrentSmoothedTickTime();

    /** 上一次 tickServer 结束的时间戳。0 表示"还没有可比对的上一 tick" (首 tick 与刚安装后)。 */
    @Unique
    private long betterautosave$lastTickEndNanos;

    /**
     * 深度档的任务开始时间戳栈。{@code doRunTask} 是可重入的 —— managedBlock 内的等待循环会
     * pollTask() 再跑一个任务, 那个任务又可能触发 getChunk 再进 managedBlock —— 单个 long 字段会串值。
     * 槽位 0 存栈底; 深度超过数组长度时该层静默不计时 (不扩容, 不抛异常)。
     */
    @Unique
    private final long[] betterautosave$deepTaskStartNs = new long[64];

    @Unique
    private int betterautosave$deepTaskDepth;

    /**
     * v0.20: tick 外停顿的默认档测点。本 HEAD 与下面的 TAIL 戳记之间的差值, 就是
     * {@code waitUntilNextTick} 里 pollTask + park 花掉的墙钟 —— 这段时间完全不计入 MSPT,
     * 一次十几秒的停顿在监控面板上是全盲的。
     */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void betterautosave$onTickServerHead(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        long now = System.nanoTime();
        long prev = betterautosave$lastTickEndNanos;
        // 每 tick 归零深度栈: @At("RETURN") 不在异常路径触发, 某个任务抛 Error 逃逸会让深度计数
        // 永久偏移, 之后所有深度档采样落到错误槽位。tick 边界是天然的自愈点。
        betterautosave$deepTaskDepth = 0;
        betterautosave$lastTickEndNanos = 0L;
        if (prev != 0L) {
            TickGapDetector.onTickStart(now - prev, tickCount);
        }
    }

    /**
     * v0.20 深度档: 逐个任务计时, 把一次长 gap 归因到具体任务。
     *
     * <p>必须写全描述符: {@code MinecraftServer} 同时有 {@code doRunTask(TickTask)} 与桥接方法
     * {@code doRunTask(Runnable)}, 裸写方法名会同时命中两个并双记。
     *
     * <p>HEAD 无条件 push / RETURN 无条件 pop, 只有"是否取时间戳"看配置。按配置跳过 push 的话,
     * 配置在任务执行中途被热重载翻转就会 push/pop 不配对。深度档关闭时的常态成本是一次 volatile
     * 布尔读 + 一次数组写。
     */
    @Inject(method = "doRunTask(Lnet/minecraft/server/TickTask;)V", at = @At("HEAD"))
    private void betterautosave$onTaskHead(TickTask task, CallbackInfo ci) {
        int d = betterautosave$deepTaskDepth;
        if (d >= 0 && d < betterautosave$deepTaskStartNs.length) {
            betterautosave$deepTaskStartNs[d] = TickGapDetector.deepEnabled() ? System.nanoTime() : 0L;
        }
        betterautosave$deepTaskDepth = d + 1;
    }

    @Inject(method = "doRunTask(Lnet/minecraft/server/TickTask;)V", at = @At("RETURN"))
    private void betterautosave$onTaskReturn(TickTask task, CallbackInfo ci) {
        int d = betterautosave$deepTaskDepth - 1;
        if (d < 0) {
            betterautosave$deepTaskDepth = 0;
            return;
        }
        betterautosave$deepTaskDepth = d;
        if (d >= betterautosave$deepTaskStartNs.length) {
            return;
        }
        long t0 = betterautosave$deepTaskStartNs[d];
        if (t0 == 0L) {
            // 本次进入时深度档是关的 (或槽位溢出未记录), 没有可用的起点。
            return;
        }
        TickGapDetector.onTaskFinished(task, System.nanoTime() - t0, tickCount);
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void betterautosave$onTickServer(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }

        // 恢复队列 drain 必须与 degraded 闸门解耦, 先于早返执行.
        // 降级后存活的 chunk worker 与 vanilla IOWorker 回调线程上的在途 task 仍会执行, 其 IO 失败
        // 仍 enqueueRecovery 投进 ChunkRecoveryQueue。若 drain 跟随 degraded 早返一起停摆, 这些失败
        // chunk 的 vanilla isUnsaved 永远停在 false, vanilla autosave/unload 全部跳过, 整个降级会话
        // (可能数小时) 不落盘; 而降级常由 OOM 等灾难触发, 崩溃概率显著升高 -- 一旦降级期进程被 kill,
        // onServerStopping 的一次性 drain 不执行, 增量永久静默丢失。失败恢复恰恰在降级时最需要, 故
        // drain 不受 degraded 影响。内部对空队列 / server==null 已零开销早返, 降级下调用安全。
        BetterAutoSaveCore.pipeline().drainChunkRecoveryQueue();

        // v0.20: 诊断日志与 degraded 闸门解耦。同步区块加载停顿与 tick 外停顿完全由第三方调用模式引起,
        // 跟 BAS 管线是否降级无关; 降级会话可能持续数小时, 若周期摘要随 degraded 一起停摆, 这两段观测
        // 恰好在最需要它们的场景 (降级常由 OOM 等灾难触发) 下全盲。副作用是存盘指标摘要在降级会话里
        // 也继续输出 —— 有意为之, 降级管线的队列状态同样值得看。
        DiagnosticLogger diag = BetterAutoSaveCore.diagnosticLogger();
        if (diag != null) {
            diag.onServerTick();
        }

        // 调度 dispatch 仍受 degraded 闸门保护: 降级后所有新 save 走 vanilla 同步,
        // BAS 不再主动 dispatch chunk。
        if (BetterAutoSaveCore.pipeline().isDegraded()) {
            return;
        }
        int ticksIntoCycle = tickCount % AUTOSAVE_INTERVAL;
        int remainingTicks = AUTOSAVE_INTERVAL - ticksIntoCycle;
        int remainingSeconds = remainingTicks / 20;

        BetterAutoSaveCore.scheduler().onServerTick(getCurrentSmoothedTickTime(), remainingSeconds);
    }

    /**
     * 必须是独立于 {@link #betterautosave$onTickServer} 的第二个 TAIL 注入: 那个 handler 第一条语句
     * 就是 isInstalled() 早返, 后面还有 isDegraded() 早返, 把戳记塞进去会让未安装 / 降级会话的
     * gap 计算全部失效 —— 而降级会话恰恰是最需要这段观测的场景。
     *
     * <p><b>必须声明在 {@link #betterautosave$onTickServer} 之后</b>: 两个 handler 注入同一条 RETURN
     * 指令, Mixin 按 mixin 类里的方法声明顺序依次 insertBefore, 因此声明顺序就是执行顺序。戳记若先跑,
     * BAS 自己的 tick 尾部工作 (drainChunkRecoveryQueue / 周期摘要 / scheduler 的主线程 capture) 会被
     * 算进下一次的 gap, 而那行日志说的是 "this time is not counted in MSPT" —— 这部分恰恰计入 MSPT,
     * 上报的数字与措辞就都失实了。
     */
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void betterautosave$onTickServerTailStamp(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        betterautosave$lastTickEndNanos = System.nanoTime();
    }
}
