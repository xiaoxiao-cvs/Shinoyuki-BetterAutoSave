package com.shinoyuki.betterautosave.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.shinoyuki.betterautosave.diagnostic.SyncLoadDetector;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.function.BooleanSupplier;

/**
 * v0.20: 主线程同步区块加载观测点。
 *
 * <p>vanilla {@code getChunk} 命中 4 槽缓存即返回, 只有未命中才走到
 * {@code mainThreadProcessor.managedBlock(...)} 在主线程干等 IO 与生成完成。包住这一次调用即可测出
 * 单次同步加载让服务器主线程阻塞了多久 —— 这段时间计入 MSPT, 但 vanilla 不作任何归因。
 *
 * <p><b>只观测不干预</b>: handler 不改变控制流, 不吞异常, {@code original.call} 原样透传。
 *
 * <p><b>常态开销</b>: 缓存未命中路径每次多两次 {@code System.nanoTime()}; 阈值判定与采栈全部在
 * {@code original.call} 返回之后由 {@link SyncLoadDetector} 完成, 未超阈值时一次栈都不采。四条语句的
 * 先后顺序由 {@code SyncLoadMixinParityTest} 的字节码门禁锁死。
 *
 * <p><b>{@code require = 0}</b>: 纯观测功能不允许硬化成启动崩溃。同装重写 {@code getChunk} 的区块 mod
 * 会让该 INVOKE 消失, 此时应降级为"检测不生效"而不是 InjectionError 崩服。丢失的注入失败信号由构建期
 * 的字节码门禁补回。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheSyncLoadMixin {

    @Shadow
    @Final
    public ServerLevel level;

    /**
     * 接收者形参必须 {@code @Coerce}: 目标 owner {@code ServerChunkCache$MainThreadExecutor} 是包私有
     * final 类, 无法在本包内直接书写该类型; 声明成它的父类 {@code BlockableEventLoop} 由 Mixin 的
     * {@code canCoerce} 放行。
     *
     * <p>尾部 {@code chunkX/chunkZ} 是 WrapOperation 捕获宿主方法前缀参数的能力, 对应 {@code getChunk}
     * 的第 0/1 个参数。
     */
    @WrapOperation(
            method = "getChunk",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;"
                            + "managedBlock(Ljava/util/function/BooleanSupplier;)V"),
            require = 0)
    private void betterautosave$measureSyncChunkLoad(@Coerce BlockableEventLoop<Runnable> processor,
                                                     BooleanSupplier isDone,
                                                     Operation<Void> original,
                                                     int chunkX, int chunkZ) {
        long t0 = System.nanoTime();
        original.call(processor, isDone);
        long blockedNs = System.nanoTime() - t0;
        SyncLoadDetector.onSyncLoadReturned(blockedNs, chunkX, chunkZ, level);
    }
}
