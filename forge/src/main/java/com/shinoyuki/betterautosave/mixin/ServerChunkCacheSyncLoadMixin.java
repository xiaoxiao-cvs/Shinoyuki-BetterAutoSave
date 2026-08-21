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
 * v0.20: 主线程同步区块加载检测。
 *
 * <p>{@code ServerChunkCache.getChunk} 的四槽缓存全部未命中时, vanilla 落到
 * {@code this.mainThreadProcessor.managedBlock(future::isDone)} —— 主线程就地把区块的读盘 (以及需要时的
 * 生成) 干等到完成。这段墙钟计入 MSPT, 但归因信息在 spark 之外完全不可见: 谁调的 getChunk 只存在于当时的
 * 调用栈里。包住这一次 INVOKE 是拿到该调用栈的唯一时机。
 *
 * <p>四条语句的顺序是本功能的核心不变式, 由 {@code SyncLoadMixinParityTest} 的字节码门禁锁死:
 * 计时闭合与阈值判定全部发生在 {@code original.call} 之后, 因此常态一次栈都不采, 只多两次
 * {@code System.nanoTime()}。把阈值判定或采栈挪到 call 之前, 每一次缓存未命中都要付采栈的代价。
 *
 * <p>{@code require = 0}: 本功能是纯观测, 失败模式必须是"检测不生效"而不是启动崩服。mixins.json 的
 * {@code injectors.defaultRequire = 1} 会把注入点失配变成启动期 InjectionError, 而同装重写 getChunk 的
 * 第三方 mod (C2ME-forge 一类) 完全可能让这条 INVOKE 消失。丢掉的"注入失败"运行期信号由构建期门禁
 * {@code SyncLoadMixinParityTest} 与 {@code SyncLoadRefmapGateTest} 补回。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheSyncLoadMixin {

    @Shadow
    @Final
    public ServerLevel level;

    /**
     * {@code @Coerce} 是必需的: {@code ServerChunkCache$MainThreadExecutor} 是包私有类, 本 mixin 所在包
     * 写不出该类型。Mixin 0.8.5 的 {@code Injector.checkCoerce} 判 {@code 实际类型.hasSuperClass(声明类型)},
     * 而 {@code MainThreadExecutor extends BlockableEventLoop<Runnable>}, 故声明父类型合法。
     *
     * <p>{@code @At target} 的 owner 必须精确写 {@code ServerChunkCache$MainThreadExecutor}:
     * Mixin 的 {@code MemberInfo.matches} 对 owner 是精确字符串相等, 不回溯继承链, 写成
     * {@code BlockableEventLoop} 一处都匹配不上。
     *
     * <p>尾部的 {@code chunkX / chunkZ} 是 WrapOperation 捕获宿主方法前缀参数的能力,
     * 对应 {@code getChunk(int x, int z, ChunkStatus, boolean)} 的前两个参数。
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
