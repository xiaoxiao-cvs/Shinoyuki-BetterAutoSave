# BetterAutoSave 配置参考

**简体中文** | [English](CONFIGURATION.en.md)

配置文件位置：

```
config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml
```

默认配置开箱即用，大多数服务器不需要改动。本文按配置段逐项说明，配置文件内也有等价的英文注释。

**双端差异**：Forge 1.20.1 版共 35 个配置项，NeoForge 1.21.1 版 18 个。差的 17 项是 `[levelData]`、`[playerData]`、`[load]` 三整段加上 `workers.loadWorkerThreads`。两端共有的 18 项，默认值、取值范围与枚举完全相同。差异逐项说明见 [ROADMAP.md 的能力总览](ROADMAP.md#能力总览)。

## 一、总开关与节流

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `general.enabled` | `true` | 布尔 | 总开关。关掉后全部 mod 逻辑绕过，存盘回落原版行为，等于没装 |
| `throttle.chunksPerTickBase` | `4` | 1 - 64 | 主线程每游戏刻开始捕获的区块数基线。自适应节流可能把它减半或跳过该刻，除非 deadline guard 已触发 |
| `throttle.adaptiveEnabled` | `true` | 布尔 | 服务器吃力时自动降速：平均 tick 时间超 51.3ms（TPS 低于 19.5）降预算，超 52.6ms（TPS 低于 19）跳过该刻。只在跑基准测试时关，生产环境应保持开启 |
| `throttle.deadlineGuardSeconds` | `30` | 5 - 240 | 距下一次自动保存不足这么多秒时绕过节流，保证脏区块在本周期内完成快照（原版周期 300 秒）。下限是 5 而不是 0，因为 0 会让该条件永不成立，持续低 TPS 时整个周期都不强制推进，反而扩大崩溃丢失窗口 |

## 二、后台线程

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `workers.chunkWorkerThreads` | `2` | 1 - 8 | 区块 NBT 构建线程数。超过 2 到 3 个没有收益，因为原版 IOWorker 会把 region 文件写入串行化 |
| `workers.entityWorkerThreads` | `2` | 1 - 8 | 实体 NBT 构建线程数 |
| `workers.savedDataWorkerThreads` | `1` | 1 - 4 | SavedData（`.dat`）写入线程数。典型负载 1 个足够；装了写大量 SavedData 的 mod（如 MTR、ANTE）可调到 2 |
| `workers.loadWorkerThreads` | `2` | 1 - 8 | 异步加载的反序列化线程数，与存盘 worker 池独立，避免存盘积压饿死加载。反序列化基本是单线程瓶颈，2 个覆盖典型负载。仅 Forge 版 |

## 三、失败处理与关服

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `safety.shutdownTimeoutSeconds` | `60` | 5 - 600 | 关服流程等待在途快照排空的总时长。超时后剩余项走同步的原版存盘路径 |
| `safety.maxRetries` | `3` | 0 - 10 | 区块 NBT 构建或 IO 提交失败后重新入队的次数。用尽后状态停在 FAILED 并走同步回退 |
| `safety.savedDataMaxFileSizeMB` | `50` | 1 - 1024 | 超过此阈值的 SavedData 走同步写入而非 worker 队列，防止单个超大 SavedData 拉高主线程分配、长时间堵塞队列 |

关于 `savedDataMaxFileSizeMB` 有一处容易误解：阈值约束的是**未压缩**的序列化尺寸（主线程分配的 byte[] 大小），不是磁盘上 `.dat` 文件的尺寸。比较对象是上次写入的未压缩长度，没有历史记录时用磁盘 gzip 尺寸乘一个保守比例估算。因此磁盘上远小于 50MB 的高压缩率文件仍可能被判定走同步路径。

## 四、事件兼容档位

| 键 | 默认 | 取值 | 说明 |
|---|---|---|---|
| `compat.eventCompatMode` | `PARTIAL` | `PARTIAL` / `FULL` / `DISABLED` | 控制 `ChunkDataEvent.Save` 的派发时机与数据完整度 |

不确定就保持 PARTIAL，99% 的 mod 感知不到差别。三档的具体行为、以及 PARTIAL / DISABLED 下会静默丢弃哪类第三方注入，见 [COMPATIBILITY.md 的兼容档位一节](COMPATIBILITY.md#五兼容档位与数据完整性契约)。

## 五、玩家存档（仅 Forge 版）

装了很多 mod 的服务器上，自动保存那一刻要把每个在线玩家的三个文件全写一遍：`playerdata/<uuid>.dat`、`stats/<uuid>.json`、`advancements/<uuid>.json`。137 mod 生产服实测约 6.7ms/人，4 人约 27ms，外推到 60 人约 400ms，全压在一个游戏刻上。其中成就文件占 55%、玩家存档占 30%、统计文件占 15%。

| 键 | 默认 | 取值 | 说明 |
|---|---|---|---|
| `playerData.loadFallback` | `true` | 布尔 | 玩家存档读不出来时，先隔离损坏件再从 `.dat_old` 恢复 |
| `playerData.atomicSidecarWrite` | `true` | 布尔 | 成就与统计改成「先写临时文件再原子替换」，并留一份 `.bak` |
| `playerData.sidecarFsync` | `false` | 布尔 | 上一项额外做一次同步刷盘 |
| `playerData.advancementsSkipMode` | `OFF` | `OFF` / `AUDIT` / `ON` | 成就没变化时跳过重写 |
| `playerData.advancementsForceFullWriteCycles` | `12` | 0 - 1000 | 连续跳过多少次后强制全量写一次 |
| `playerData.staggerMaxPerTick` | `0` | 0 - 64 | 每游戏刻最多存几个玩家，0 = 原版行为（一刻写完所有人） |

### 前两项是数据安全修复，建议别关

原版在这两条路径上的结局分别是：

**玩家存档读失败等同于新玩家。** `PlayerDataStorage.save` 写入时先把在用文件改名为 `<uuid>.dat_old` 再把临时文件改名就位，但 `load` 只读 `<uuid>.dat`。读不出来时原版只记一条无堆栈的 WARN 就按新玩家处理 —— 背包、末影箱、出生点、经验、以及挂在 capability 上的全部 mod 数据一起归零，而完好的 `.dat_old` 就躺在同一个目录里从未被查看，下次自动保存还会用空白玩家覆盖它。开启后改为：先把损坏的主文件隔离成 `<uuid>_corrupted_<时间戳>.dat` 保留证据，再尝试 `.dat_old`。主文件缺失（真正的新玩家）这条路径行为完全不变。

**成就与统计文件是截断写。** 每玩家写的三个文件里只有 `playerdata/<uuid>.dat` 享受原版的临时文件加改名，`ServerStatsCounter.save` 与 `PlayerAdvancements.save` 都是截断唯一副本再往里流式写。任意世界文件夹都能验证这一点：`playerdata/` 满是 `.dat_old`，而 `stats/` 与 `advancements/` 一个备份都没有。写到一半崩溃会留下截断的 JSON，下次登录解析失败、异常被吞、加载继续并用空进度表，玩家进服无任何进度，下次自动保存再把空表写回去。大整合包上成就文件可达几百 KB，写入窗口并不小。

这两个问题的上游状态并不一样。**读失败回退在 1.21 已修**：`PlayerDataStorage.load` 会先把损坏件复制成 `<uuid>_corrupted_<时间戳>.dat` 再回退到 `.dat_old`，所以本项是回移到 1.20.1。**成就与统计的截断写至今没修**：1.21.1 的 `PlayerAdvancements.save` 仍用 `newBufferedWriter`（隐含 CREATE 加 TRUNCATE_EXISTING），`ServerStatsCounter.save` 仍是 `FileUtils.writeStringToFile` 直写目标文件 —— 上游只给了 `playerdata/<uuid>.dat` 一条路径原子写，两个 sidecar 文件一直没给。

`sidecarFsync` 默认关闭是有意的：消除截断窗口靠的是「临时文件加原子替换」本身，fsync 覆盖的是更窄的一种失败 —— 改名已持久而数据尚未回写时断电，留下尺寸正确但内容全零的文件。而 ext4 在默认的 `data=ordered` 下本来就会在提交覆盖式改名前刷新新写数据，最常见的 Linux 配置上这个开关近乎冗余。代价却实打实落在主线程且按人数放大：60 人一次自动保存就是同一刻内 120 次同步设备刷新，每次都在等文件系统日志提交，而 BAS 自己的 worker 写入正在争用它。原版在这条路径上一次 fsync 都没有。只在主机没有带电池保护的写缓存、且确实要防非正常断电时才建议开，并配合 `staggerMaxPerTick` 把刷新摊开。

### advancementsSkipMode 是本段最大的性能杠杆

原版 `PlayerAdvancements.save` 完全没有脏检查：每次自动保存都遍历全部已加载进度、经 Gson 重建整棵 JSON 树、美化输出并写整个文件，无论有没有变化。而在 137 mod 的生产服上它几乎从不变 —— 连续三次自动保存采样，在线玩家的成就文件逐字节相同（mtime 前进、md5 恒定），同期他们的 playerdata 与 stats 每次都变。全部 31 名玩家的 18,883 条 criterion 时间戳中 89% 早于 30 天，仅 36 条（0.19%）来自最近 24 小时，主体是一次解锁后永不再变的 `minecraft:recipes/*`。

跳过写入在磁盘上是字节等价的，备份类 mod 看到的数据完全相同。

三档：`OFF` 是原版行为；`AUDIT` 照常每次写入，但同时计算脏标志**本应**做出的判断并与实际内容对比，不一致时记 ERROR 并点名玩家 —— 没有任何提速，存在意义是让你在自己的整合包上证明该标志可信；`ON` 才真正跳过。

推荐上线顺序：先设 `AUDIT` 跑几天，确认日志里没有 MISMATCH，再改 `ON`。这与 `levelData.cacheRegistrySnapshot` 是同一套打法。

实现上没有复用原版的 `progressChanged` 集合：它被 `flushDirty` 每 tick 清空，自动保存时几乎恒为空，拿它当写盘脏标志会把确实变了的保存也跳掉。BAS 用独立标志，只在授予或撤销进度真正成功时置位。

脏标志由 `award()`、`revoke()`、`load()`、`reload()` 设置。绕过这些直接改进度的 mod（例如持有 `getOrStartProgress` 返回的对象并在其上调 `grantProgress`）不会置位标志 —— `advancementsForceFullWriteCycles` 限定了这类变更最多多久必然落盘一次，同时也补回原版「每次无条件重写」顺带具备的、对外部改动（备份还原、管理员手改文件）的自愈性。默认 12 相当于每玩家每小时保证全量写一次，12 次里省掉 11 次。

### staggerMaxPerTick 不改变任何数据安全性质

每个玩家仍然恰好每个自动保存周期存一次，最坏陈旧窗口一点没变，变的只是各人被写的时刻。不开线程、不改写入顺序，`/save-all`、关服、玩家退出都绕过错峰立刻写完。

建议值 1 或 2。**不要照抄 Paper 的默认值** —— 它的 `maxPerTick()` 在默认 `rate=-1` 下实际返回 20，而重整合包上每刻 20 人已超过 100ms，反而违背初衷。按每刻 1 人算，60 人 3 秒排空，远在 5 分钟周期之内。

> 注意：错峰期间 `MinecraftServer.isSaving` 为 false，靠这个标志判断「存盘进行中」的 mod 看不到这些写入。

## 六、level.dat 完整性与写入优化（仅 Forge 版）

| 键 | 默认 | 取值 | 说明 |
|---|---|---|---|
| `levelData.verifyOnStartup` | `true` | 布尔 | 启动时检查 level.dat，坏了就隔离并用 `level.dat_old` 复位 |
| `levelData.startupBackup` | `true` | 布尔 | 校验通过后存一份原始字节副本到 `<world>/betterautosave/leveldat/`，保留 3 代 |
| `levelData.postWriteVerify` | `CHECKSUM` | `OFF` / `CHECKSUM` / `FULL` | 每次写完 level.dat 后在 worker 线程回读校验 |
| `levelData.cacheRegistrySnapshot` | `false` | 布尔 | 缓存 Forge 注册表 ID 表，消除自动保存的主线程尖峰 |
| `levelData.registryCacheRevalidateCycles` | `12` | 0 - 1000 | 每命中多少次强制重算一次并与缓存逐字段对拍 |

### 为什么需要这三层校验

原版只有 `level.dat_old` 一份备份，而且每次写盘都轮转 —— 一次带病启动就能把它吃掉。

更麻烦的是原版的回退只在文件**缺失**时才结构上可达：专用服实际使用的 reader 以 `catch (IOException) { throw new UncheckedIOException }` 开头，所以文件**存在但读不出来**时根本走不到回退，异常表现为「Failed to load datapacks, can't proceed with server load」并以正常状态码退出 —— 把运维指向数据包，而真正的问题在 level.dat。

还有一种更安静的失败：gzip 与 NBT 都合法，但缺少 `Data`、或仅缺其中的 `DataVersion` 整数时，`getDataVersion` 回落 -1，导致世界生成 datafixer 丢弃并重建整个维度表，下游无人拒绝 —— 服务器**启动成功**，但世界种子变成 0、出生点、时间、天气、游戏规则、世界边界全回默认，而 region 文件还是旧地形。启动随后把这份默认数据写回并把损坏文件轮转成 `level.dat_old`，同一次启动内摧毁最后一份好副本，全程无报错。

`verifyOnStartup` 按四级判据检查（文件缺失 / 无法解压或解析 / 结构不完整 / 正常），前三种从 `level.dat_old` 自动修复，损坏文件移为 `level.dat_corrupted_<时间戳>`（与 1.21 同命名）。若 `level.dat_old` 也不可用则不动任何文件，只打印可用备份与手动恢复的确切命令。

`postWriteVerify` 回答的是另一个问题：`verifyOnStartup` 要到下次启动才发问，那时原版可能已经把损坏文件轮转进 `level.dat_old` 吃掉最后一份好副本；写后回读在损坏出现的那一刻就发现，此时恢复窗口最宽。校验跑在 SavedData worker 队列上，主线程只付出提交任务的成本，且只读不修复（服务器在线时覆盖正在使用的世界元数据比原故障更麻烦），只大声记日志，修复留到下次启动。`CHECKSUM` 做端到端解压，抓截断与流损坏；`FULL` 额外解析 NBT 并施加与启动校验相同的结构判据，能多抓「合法 NBT 但缺 DataVersion」。

需要说明的是，BAS 并没有接管 `level.dat` 的写入（异步写已评估并否决），这里校验的是原版自己的写入 —— 但仍然值得做，磁盘故障、文件系统问题和其它 mod 都可能破坏它。

> BAS 的启动备份**永远不会被自动恢复**，原版也不认识它。自动修复只用 `level.dat_old`（最多退一个保存周期）。真要用启动备份回滚，BAS 会把可用副本和精确命令打进日志，由你停服后手动执行 —— 因为回滚会倒退世界时间、天气、游戏规则、世界边界和末影龙战进度，如果期间换过 mod 还会重映射方块 ID。这是运维决策，BAS 只负责打印然后停手。

### 注册表缓存（实验性，默认关闭）

如果你的服务器 mod 很多，用 spark 看 MSPT 曲线大概率能看到**每 5 分钟一根整齐的尖峰**，哪怕服上一个人都没有。那不是区块存盘，是原版每次自动保存都会无条件重写一遍 `level.dat`。

这里要先纠正一个容易误判的归类：出问题的是世界根目录下的 `level.dat`（世界元数据），不是 `world/data/` 下的 `*.dat`（SavedData）。BAS 的异步存盘一直只覆盖后者。

137 mod 的 1.20.1 生产服实测：

| 项 | 实测值 |
|---|---|
| level.dat 解压后大小 | 1,234,370 字节 |
| 其中注册表 ID 表（`fml/Registries`） | 1,215,091 字节（98.44%，17 个注册表 / 26,648 条 ID） |
| 世界业务数据（`/Data`） | 12,018 字节（0.97%） |
| 相隔 5 分钟的两份 level.dat 逐字节 diff | 只有 5 个字节不同，且全在 `/Data`；注册表那 122 万字节完全相同 |
| 重建注册表表的主线程开销 | 每次自动保存约 25ms |

也就是说，每 5 分钟花 25ms 重算的东西，算出来跟上次一模一样。开启后 BAS 把它缓存下来复用。这项优化只减少主线程重建工作，不改变写盘时机、不引入后台线程、不触碰 `level.dat` 的落盘协议。

缓存由三层相互独立的机制失效，任一触发即重建：Forge 的 `IdMappingEvent`（覆盖官方全部三条 ID 变更路径）、每次写盘前对每个持久化注册表采一次指纹（条目数与冻结状态，兜住 `ForgeRegistry.unfreeze()` 这条公开且不发任何事件的路径）、以及 `registryCacheRevalidateCycles` 的周期性强制重算对拍。

**默认关闭是有意的。** 这个功能省的是每 5 分钟一次的 25ms 尖峰，不是吞吐问题 —— TPS 通常根本不掉。建议的开启方式：先把 `registryCacheRevalidateCycles` 设成 `1`（每次都校验，没有性能收益、纯审计），跑几天确认日志里没有 MISMATCH，再改回 `12` 拿收益。设 `0` 表示永不重校验，只在长期无 MISMATCH 后使用。

生产环境实测：连续运行 15 小时 18 分，93 次周期性强制重算全部一致、零 MISMATCH，四次 `save-all` 取回的 `level.dat` 注册表区间逐字节相同，`writeAdditionalLevelSaveData` 的主线程耗时由 76ms 降至 16ms。

> 注意：缓存命中时会跳过 `ForgeHooks.writeAdditionalLevelSaveData` 整个方法，因此其它 mod 注入到该方法里的逻辑也会被跳过。目前没有已知的这类 mod（该方法标了 `@ApiStatus.Internal` 且只有一处调用），而且缓存内容本来就是从「那些注入全跑过一遍」的结果里摘下来的；万一某个 mod 往里写随时间变化的数据，周期性对拍会把它报成 MISMATCH。看到 MISMATCH 就把本开关关掉并反馈 issue。

## 七、异步区块加载（实验性，默认关闭，仅 Forge 版）

> 开启前务必先备份整个世界文件夹。这是实验性功能，改动的是区块「加载」路径。设计上即便后台解析失败，也会用同一份字节在主线程重读兜底、不丢数据；但任何动加载路径的功能，在你特定的 mod 组合下都可能有未覆盖的边界。

原版加载区块时，把硬盘上的存档字节解析成游戏对象这一步是压在主线程上的。视距大、玩家快速移动或传送时，这步会成为主线程负担。开启后 BAS 把解析搬到后台线程，主线程只做必须当场做的部分（POI 一致性、光照、加载事件回放），腾出的时间转化为更高的 TPS 余量。

这对「视距 10-12 加多人」的生产服收益最实在；纯单人极限飞行（视距拉满）撞的是原版单线程区块流水线的天花板，异步解析帮不上那一段。

| 键 | 默认 | 取值 | 说明 |
|---|---|---|---|
| `load.enabled` | `false` | 布尔 | 异步加载总开关，独立于存档侧的 `general.enabled` |
| `load.loadEventCompatMode` | `PARTIAL` | `PARTIAL` / `FULL` | 切分档位。无 DISABLED 态 |
| `load.maxInFlight` | `128` | 2 - 1024 | 同时提交给后台的解析任务上限 |
| `load.loadMaxRetries` | `1` | 0 - 5 | 后台解析抛错时的重试次数 |
| `load.asyncPoiPrefetch` | `true` | 布尔 | 在 load worker 上离主线程读取 POI region |

`maxInFlight` 限制的是同时完成的 off-thread 加载数量，避免它们的主线程回放与区块安装全落在一个游戏刻内。这种爆发在高速飞行加高视距下表现为 "Can't keep up" —— 并行解码喂完成的速度超过主线程安装的速度。超额的加载会排队（worker 保持有活干）而不是洪泛。调高则吞吐更高但每刻爆发更大，调低则更平滑但区块到达更慢。按服务器逐步上调到爆发重现为止。

`loadMaxRetries` 针对的失败几乎总是瞬态的（尽管有守卫，Codec dispatch 缓存仍可能与并发解码竞争，或 DataFixer 缓存打嗝），单次重试通常就能解决而不必付出主线程回退成本。设 0 表示首次抛出即回退。终态回退会在主线程重读同一段 region 字节，所以无论此值为何都不会丢数据。

`asyncPoiPrefetch` 是加载侧的 Tier A 优化：worker 读 POI 字节（只走 IOWorker，线程安全）交给主线程，主线程在回放前解析并填充 POI 缓存，随后的一致性检查命中缓存而不是读盘。异步加载开启后，这段 POI 读盘等待是主线程上最大的剩余开销。仅在 `load.enabled=true` 且档位为 PARTIAL 时生效；若某个 POI 存储类 mod 行为异常，设 false 回落到原版主线程 POI 读取。

出问题随时把 `load.enabled` 改回 `false`，或把 `loadEventCompatMode` 切成 `FULL`，即刻回到原版加载行为。

> `load.enabled` 从关改开必须重启服务器（改回关可热重载即刻生效）—— 关闭时异步加载 mixin 的字节码完全不注入，这是为了避免与 C2ME 等重写加载路径的 mod 启动冲突。开启后的第三方 mod 兼容契约见 [COMPATIBILITY.md](COMPATIBILITY.md#加载侧loadloadeventcompatmode仅-forge)。

## 八、诊断与监控

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `diagnostics.diagnosticLogging` | `true` | 布尔 | 周期性把队列深度、吞吐与延迟分位数记入服务器日志 |
| `diagnostics.diagnosticLogIntervalTicks` | `6000` | 20 - 72000 | 诊断摘要输出间隔，单位游戏刻（20 刻 = 1 秒）。默认 6000 即 5 分钟 |
| `prometheus.enabled` | `false` | 布尔 | 启用 Prometheus 指标 HTTP 导出器 |
| `prometheus.bindAddress` | `"0.0.0.0"` | 字符串 | HTTP 服务器绑定地址 |
| `prometheus.port` | `9450` | 1024 - 65535 | HTTP 服务器端口，默认避开 9090 / 9100 / 25565 |
| `hottestChunks.windowSize` | `100` | 10 - 1000 | 每区块保留用于计算 p99 的延迟样本数。每区块 100 个样本约 1.6 KB |
| `hottestChunks.trackLimit` | `10000` | 100 - 1000000 | 同时追踪的最大区块数，达到上限时按 LRU 驱逐最久未存盘的区块 |

持续监控优先用 Prometheus 导出器而不是日志。开启后服务器在 `bindAddress:port` 提供 `GET /metrics`（Prometheus exposition 格式），接 Grafana 可以画存档性能的长期趋势图：

```toml
[prometheus]
enabled = true
port = 9450
```

**安全提示**：默认绑定 `0.0.0.0`，接受任意网卡的连接。指标本身不含玩家隐私，但会泄露服务器的活动规律。服务器有公网 IP 时请用防火墙限制该端口，或把 `bindAddress` 改成 `127.0.0.1` 只允许本机抓取。

## 九、与备份工具的配合

BAS 把区块存盘改成异步之后，`/save-all flush` 返回（以及控制台那行 `Saved the game`）**不再代表数据已经全部落盘**。靠这行日志判断「保存完成」的外部备份工具会拿到一个提前的信号。

进程内的 mod 请改用 BAS 的 `SaveCoordination` API 拿准确状态。外部脚本目前建议改成等 BAS 自己的 `/betterautosave flush` 完成提示。

> 原版的普通 `/save-all`（不带 `flush`）本来就不保证落盘 —— 区块交给后台 IO 线程、实体只做增量保存。真正有落盘承诺的只有 `/save-all flush` 这一条，也只有它受本条影响。

## 十、热重载行为

以下配置项在代码中明确标注可热重载，改完即刻生效：`levelData.cacheRegistrySnapshot`、`playerData.loadFallback`、`playerData.atomicSidecarWrite`、`playerData.sidecarFsync`、`playerData.advancementsSkipMode`、`load.asyncPoiPrefetch`。

`load.enabled` 明确**不是**热重载生效：mixin 在类加载期按磁盘上的值决定是否应用，从关改开必须重启。

其余配置项的注释未标注热重载属性，改动后建议重启以确保生效。
