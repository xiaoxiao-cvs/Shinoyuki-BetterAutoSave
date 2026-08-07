# BetterAutoSave 兼容性

**简体中文** | [English](COMPATIBILITY.en.md)

本文覆盖 BAS 与其它 mod 的共存关系、判定依据，以及给 mod 作者排查用的拦截点明细。配置项含义见 [CONFIGURATION.md](CONFIGURATION.md)。

结论对应 0.19.0，Forge 1.20.1 与 NeoForge 1.21.1 双端。

## 一、BAS 触碰哪些东西

BAS 只注入存储、序列化与服务器生命周期这一层。全部 mixin 目标类去重后共 13 个，没有一个属于游戏逻辑层。

**明确不碰**（依据是 mixin 清单里不存在对应目标类）：

| 范围 | 依据 |
|---|---|
| 光照引擎内部 | 无 `LevelLightEngine` / `ThreadedLevelLightEngine` / `LightChunkGetter` 的 mixin。异步加载开启时只是把 `ChunkSerializer.read` 内部对 `retainData` / `queueSectionData` 的两次调用延迟回主线程回放，不改写光照引擎自身 |
| worldgen | 无 `ChunkGenerator` / `NoiseBasedChunkGenerator` / `WorldGenRegion` 的 mixin |
| ChunkStatus 升级链 | 无 `ChunkStatus` / `ChunkHolder` 的 mixin，两者仅作为类型引用出现 |
| 实体 tick | 无 `Entity` / `LivingEntity` / `EntityTickList` 的 mixin。实体侧唯一目标是 `EntityStorage.storeEntities`，即写盘入口 |
| 网络包 | 无任何网络类的 mixin |
| 区块加载调度（默认配置下） | `load.enabled=false` 时，4 个加载侧 mixin 的字节码根本不注入，见下文「加载侧 mixin 的启动期门控」 |

## 二、不可同装

这几类 mod 与 BAS 抢同一条存盘接管路径，属结构性二选一。同装时由 mixin 优先级决定谁先 cancel，另一方静默失效，极端交错下写盘语义可能错乱。

| Mod | 说明 |
|---|---|
| **Fast Async World Save**（`fastasyncworldsave`） | BAS 启动时探测到它会打一条 WARN。这是目前唯一硬编码探测的 modId，因为只有它的 modId 可以确证 |
| **Smooth Chunk Save** | 同样改区块卸载时的存盘路径。BAS 不做代码探测，在此按名披露。相比之下 BAS 不延迟落盘（没有数据丢失窗口）、不取消原版定时存档、不吞异常 |
| 其它异步 / 分 tick 存盘 mod | 凡是接管 `ChunkMap.save` 或 `saveAllChunks` 的都属此类 |

启动探测只打 WARN，不阻止启动、不禁用任何功能，也不在 mod 元数据里声明 `incompatible` —— 判断权留给服主。

## 三、C2ME / C2ME-Forge：按功能拆开看

C2ME 是功能集合，与 BAS 的关系逐项不同，不是笼统的「冲突」或「兼容」。

| C2ME 功能 | 与 BAS 的关系 | 说明 |
|---|---|---|
| 异步存档（`ioSystem.async`） | 冲突，二选一 | 与 BAS 抢同一条存盘路径 |
| 序列化器重写（`gcFreeChunkSerializer`） | 冲突，二选一 | 同上 |
| 并行加载 | BAS 默认配置下互补；开启 BAS 异步加载后冲突 | `load.enabled=false`（默认）时 BAS 完全不碰加载调度；`load.enabled=true` 后两者都改 `scheduleChunkLoad`，加载侧也需二选一 |
| worldgen / `midTickChunkTasksInterval` | 始终互补 | BAS 从不碰 worldgen 与 ChunkStatus 升级链 |

共存办法：关掉 C2ME 的 IO / 存档侧功能、把 `autoSave` 设为 `VANILLA`，让 BAS 管存档、C2ME 管加载。若还想开 BAS 的异步加载，则再关掉 C2ME 的并行加载，让 BAS 管存档加载、C2ME 只管 worldgen。

C2ME-Forge 已于 2025-07-12 archived，实际同装的情况很少。

## 四、已验证兼容

| Mod | 判定依据 |
|---|---|
| Starlight（Forge 1.20.1） | BAS 只用公共 API `getDataLayerData()` 读 DataLayer，Starlight 必须保持该 API 契约（原版存盘也用它）；`DataLayer.copy()` 是 `byte[].clone`，与底层光照引擎实现解耦 |
| Radium / Canary（Lithium 移植版） | 改实体与方块 tick，不碰存盘路径 |
| Modernfix | 内存与启动优化，不碰存盘路径 |
| FerriteCore | 改 BlockState 内存表示，BAS 走标准的 `PalettedContainer.copy()` |
| Embeddium / Rubidium | 客户端渲染，与服务端存盘无关 |
| worldgen mod（Terralith / BetterEnd / Tectonic / Cataclysm 等） | 见下节兼容档位。走 `ChunkDataEvent.Save` 或 capability 的注入在默认档位下正常工作 |

其它同样改区块存档的 mod：可能让 BAS 的接管偶尔失败，此时 BAS 自动退回原版处理，数据安全不受影响，只是少了性能收益。

| Mod | 判定 | 说明 |
|---|---|---|
| DimThread | 中风险，未实测 | 给每个维度独立 tick 线程，可能触发 BAS 的 `ServerThreadAssert`。需要单独适配，目前没有实测数据 |

## 五、兼容档位与数据完整性契约

### 存盘侧：`compat.eventCompatMode`

默认 **PARTIAL**（两端一致）。三档取值 PARTIAL / FULL / DISABLED。

| 档位 | 行为 | 对第三方 mod 的影响 |
|---|---|---|
| PARTIAL（默认） | 主线程用不含 sections 的 core tag 触发 `ChunkDataEvent.Save`，worker 随后组装 sections | 只挂子 tag 或读非 section 字段的监听方不受影响；调用 `tag.get("sections")` 的监听方会拿到 null |
| FULL | 主线程构建完整 tag（含 sections）后触发事件，worker 只做 IO | 与原版 100% 等价，性能收益降低 |
| DISABLED | 完全跳过事件派发 | 挂 `ChunkDataEvent.Save` 的 mod 会失效 |

**PARTIAL 与 DISABLED 的数据完整性红线**：这两档不调用 `ChunkSerializer.write` 来组装 sections。因此，若某 mod 通过直接 mixin 进 `ChunkSerializer.write` 来注入额外区块 NBT（而不是走 `ChunkDataEvent.Save` 或 capability / data attachment —— 这两条 PARTIAL 仍照常尊重），它的序列化会被绕过，那部分数据每次存盘被静默丢弃且不报错。装了这类 mod 必须切 FULL。

### 加载侧：`load.loadEventCompatMode`（仅 Forge）

默认 **PARTIAL**。只有 PARTIAL / FULL 两档，**刻意没有 DISABLED** —— `ChunkDataEvent.Load` 必须始终在主线程触发，所以最弱档是 FULL（整段 read 留在主线程），而不是「跳过事件」。

**异步加载的兼容契约**：开启后 `ChunkSerializer.read` 跑在 load worker 上。BAS 把所有已知的原版与 Forge 主线程副作用（POI、光照、ForgeCaps、`ChunkDataEvent.Load`）延迟回主线程回放，但任何**其它** mod 注入 `ChunkSerializer.read`（或 `LevelChunk` 构造器、或其使用的 Codec）的代码也会跑在那个 worker 上。

- 会**抛异常**的第三方注入可以自愈：worker 抛出后终态回退到主线程重读同一段字节，不丢数据。
- **不抛异常但假设主线程**的注入（写非并发集合、触发只允许主线程的事件）可能静默竞争或损坏，且回退不会触发。

不确定就保持 `load.enabled=false`，或用 `loadEventCompatMode=FULL`。

### 加载侧 mixin 的启动期门控

`load.enabled` 由一个 `IMixinConfigPlugin` 在类加载期读取磁盘上的 `common.toml` 决定：关闭时，`ChunkMapLoadMixin`、`ChunkSerializerLoadMixin`、`LevelChunkCapsLoadMixin`、`SectionStorageLoadMixin` 这 4 个加载侧 mixin 完全不注入。

这样设计是为了与 C2ME-Forge 这类重写 `scheduleChunkLoad` / `ChunkSerializer.read` 的 mod 共存 —— 否则在 `defaultRequire=1` 下会直接 InjectionError 启动硬崩。

代价是：从关改开必须重启服务器，光靠配置热重载启用不了（改回关则热重载即刻生效）。配置文件缺失、读失败、缺段或缺键一律按关处理。

## 六、拦截点明细

给 mod 作者与排查用。以下只列业务逻辑拦截，纯 Accessor / Invoker（各 6 个，无业务逻辑）不列。

### Forge 1.20.1（18 个业务 mixin）

| 目标 | 方法 | 受哪个配置门控 |
|---|---|---|
| `ChunkAccess` | `setUnsaved` | 无 |
| `ChunkMap` | `saveAllChunks` | `general.enabled` |
| `ChunkMap` | `save(ChunkAccess)` | `general.enabled`、`compat.eventCompatMode` |
| `ChunkMap` | `scheduleChunkLoad` 内的 `thenApplyAsync` | `load.*` 全组（启动期门控） |
| `ChunkSerializer` | `read`（7 处 `@WrapOperation`） | 启动期门控 |
| `LevelChunk` | 构造器内的 `initInternal` | 启动期门控 |
| `SectionStorage` | POI 读取相关（Invoker + 注入方法） | 启动期门控 |
| `DimensionDataStorage` | `save()` | `general.enabled`、`safety.savedDataMaxFileSizeMB` |
| `SavedData` | `setDirty(boolean)` / `isDirty()` | 无 |
| `EntityStorage` | `storeEntities` | `general.enabled` |
| `MinecraftServer` | `stopServer` / `saveEverything` / `tickServer` | 部分受 `general.enabled` 与错峰开关门控 |
| `ForgeHooks` | `writeAdditionalLevelSaveData` | `general.enabled` + `levelData.cacheRegistrySnapshot` |
| `LevelStorageSource$LevelStorageAccess` | `readAdditionalLevelSaveData` | `levelData.verifyOnStartup` / `startupBackup` |
| `LevelStorageSource$LevelStorageAccess` | `saveDataTag` | `levelData.postWriteVerify`（OFF 即禁用） |
| `PlayerAdvancements` | `award` / `revoke` / `reload` / `load` / `save` | `playerData.advancements*`、`atomicSidecarWrite` |
| `PlayerDataStorage` | `load` | `playerData.loadFallback` |
| `PlayerList` | `saveAll` / `remove` | `playerData.staggerMaxPerTick` |
| `ServerStatsCounter` | `save` | `playerData.atomicSidecarWrite`、`sidecarFsync` |

### NeoForge 1.21.1（7 个业务 mixin）

| 目标 | 方法 | 受哪个配置门控 |
|---|---|---|
| `ChunkAccess` | `setUnsaved` | 无 |
| `ChunkMap` | `saveAllChunks` | `general.enabled` |
| `ChunkMap` | `save(ChunkAccess)` | `general.enabled`、`compat.eventCompatMode` |
| `DimensionDataStorage` | `save()` | `general.enabled`、`safety.savedDataMaxFileSizeMB` |
| `EntityStorage` | `storeEntities` | `general.enabled` |
| `MinecraftServer` | `tickServer` | 无 |
| `SavedData` | `setDirty(boolean)` / `isDirty()` | 无 |

NeoForge 版是纯异步存盘实现，没有任何加载侧 mixin，也没有 `levelData` / `playerData` 相关拦截 —— 拦截的方法全部是写路径。
