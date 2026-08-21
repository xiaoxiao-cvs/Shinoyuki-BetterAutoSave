package com.shinoyuki.betterautosave.mixin.accessor;

import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * v0.20: 取出 {@link TickTask} 包装的真实 {@link Runnable}。
 *
 * <p>vanilla 只暴露 {@code getTick()}, 没有 runnable 的 getter。缺这个 accessor 的话 tick gap 深度档
 * 每一条记录的类名都是 {@code net.minecraft.server.TickTask}, 归因表零信息量。
 */
@Mixin(TickTask.class)
public interface TickTaskAccessor {

    @Accessor("runnable")
    Runnable betterautosave$getRunnable();
}
