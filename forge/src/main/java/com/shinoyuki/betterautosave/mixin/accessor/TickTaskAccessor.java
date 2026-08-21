package com.shinoyuki.betterautosave.mixin.accessor;

import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * v0.20 深度档归因的前提。{@code TickTask} 只对外暴露 {@code getTick()}, 被包装的 {@code runnable}
 * 字段没有 getter —— 不取它的话每条深度档记录的类名都是 {@code net.minecraft.server.TickTask},
 * 产出的表格零信息量。
 */
@Mixin(TickTask.class)
public interface TickTaskAccessor {

    @Accessor("runnable")
    Runnable betterautosave$getRunnable();
}
