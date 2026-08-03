package com.shinoyuki.betterautosave.core.playerdata;

import java.util.List;
import java.util.UUID;

/**
 * 由 {@code PlayerListSaveAllMixin} 实现, 让 tick 钩子能调到 {@code PlayerList} 的 protected
 * {@code save(ServerPlayer)}。与本仓既有的 {@code SectionStorageLoadAccess} 同一模式。
 */
public interface PlayerListSaveAccess {

    /** 存一批待存玩家 (按 UUID 查在线实例, 已登出的跳过)。 */
    void betterautosave$saveBatch(List<UUID> uuids);
}
