package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
    public abstract float getAverageTickTime();

    /**
     * P1: 崩溃路径也必须进 shutdownMode。
     *
     * <p>正常关服走 {@code runServer -> handleServerStopping -> ServerStoppingEvent ->
     * BetterAutoSaveMod.onServerStopping}, 那里会 {@code enterShutdownMode()} 并 drain。但
     * {@code runServer} 的 {@code catch (Throwable)} 分支**跳过** {@code handleServerStopping},
     * 直接落到 finally 里的 {@code stopServer()} —— 于是崩溃时 {@code ServerStoppingEvent} 从不发出,
     * {@code isShutdownMode()} 在整个 {@code stopServer} 期间恒 false, 四个 mixin 的关服守卫全部失效
     * (它们本该让最后一轮存盘走 vanilla 同步路径)。同一形态还覆盖"其它 mod 在 {@code ServerStoppingEvent}
     * 里抛异常打断事件链"这条本仓 2026-06-28 实际踩过的事故。
     *
     * <p>{@code stopServer()} 是两条路径唯一的公共汇合点, 且必然早于其内部的
     * {@code playerList.saveAll()} 与 {@code saveAllChunks()}, 因此在它的 HEAD 置位来得及。
     *
     * <p>正常路径上 {@code onServerStopping} 已经 uninstall, {@code scheduler()} 返回 null,
     * 本钩子自然 no-op, 不会重复进入。
     *
     * <p>注意这不是活体丢数据洞: vanilla {@code stopServer} 内的强制卸载排空循环保证走到
     * {@code saveAllChunks} 时脏 chunk 已空, 兜底存在。但那份兜底完全寄生在这个隐性前提上,
     * 第三方重写 {@code ChunkMap.hasWork} / {@code processUnloads} 即失效。
     */
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void betterautosave$enterShutdownModeOnCrashPath(CallbackInfo ci) {
        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        if (scheduler != null && !scheduler.isShutdownMode()) {
            scheduler.enterShutdownMode();
        }
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

        // 调度 dispatch 与诊断日志仍受 degraded 闸门保护: 降级后所有新 save 走 vanilla 同步,
        // BAS 不再主动 dispatch chunk。
        if (BetterAutoSaveCore.pipeline().isDegraded()) {
            return;
        }
        int ticksIntoCycle = tickCount % AUTOSAVE_INTERVAL;
        int remainingTicks = AUTOSAVE_INTERVAL - ticksIntoCycle;
        int remainingSeconds = remainingTicks / 20;

        BetterAutoSaveCore.scheduler().onServerTick(getAverageTickTime(), remainingSeconds);
        DiagnosticLogger diag = BetterAutoSaveCore.diagnosticLogger();
        if (diag != null) {
            diag.onServerTick();
        }
    }
}
