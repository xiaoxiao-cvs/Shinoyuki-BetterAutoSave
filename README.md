# BetterAutoSave（更好的自动保存）

**简体中文** | [English](README.en.md)

![BetterAutoSave](https://raw.githubusercontent.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/main/docs/images/BetterAutoSave.png)

> 让服务器自动存档时不再卡顿 ~  
> 此Mod部分代码由 Claude Opus 4.8 / Claude Fable 5 生成，若有任何问题请提交 issue

**下载**：[Modrinth](https://modrinth.com/mod/shinoyuki-betterautosave) · [GitHub Releases](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/releases)

> 项目状态：活跃开发中，处于 1.0 前的快速迭代期，更新较频繁（含配合 BetterBackup 的联动发版）。核心异步存档已在生产环境长期验证、默认即安全；异步区块加载为较新的可选特性，默认关闭。建议关注 Releases / Modrinth 获取更新

## 这个 mod 解决什么问题

原版 Minecraft 服务器每 5 分钟自动存一次档。存档的那一下，主线程要把所有改动过的区块打包、写进硬盘——这期间整个服务器是停住的。空服感觉不到，但 mod 多、人多的服上，这一卡经常有 200 毫秒到几秒，全服玩家一起卡。

除了定时存档，还有几个时刻同样会卡：

- 玩家传送、或大量区块被卸载时（区块离开内存前要存一次）
- 存档时某片区域实体特别多（大型农场 / 刷怪塔），逐个保存实体也会额外卡
- 村庄、袭击这类全局数据（原版 SavedData），以及部分 mod 存进同一机制的大块数据，写盘时单个文件大了能卡 50-200 毫秒

BAS 把这些存档过程全部搬到后台线程。主线程只做一件必须当场做的事——给要保存的数据拍个快照，剩下的打包和写硬盘都交给后台。卡顿基本消失。


## 适用环境

两个加载器同源维护，按服务端选对应 jar：

- **Forge 1.20.1**：Forge 47.3.22 或更新（47.3 / 47.4 系列都行），Java 17 或更新
- **NeoForge 1.21.1**：NeoForge 21.1 系列，Java 21 或更新

均为纯服务端 mod，客户端不用装。

## 安装

从 [Modrinth](https://modrinth.com/mod/shinoyuki-betterautosave) 或 Releases 下载对应加载器的 jar——**Forge 版认准带 `-all` 的 jar**（文件名形如 `shinoyuki_betterautosave-<版本>-all.jar`，自带 MixinExtras 等依赖；不带 `-all` 的 thin jar 会因缺依赖在加载时崩溃），**NeoForge 版**用 `shinoyuki_betterautosave-neoforge-<版本>.jar`——丢进服务端的 `mods/` 文件夹，启动即可。第一次启动后，配置文件会生成在：

```
config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml
```

默认配置开箱即用，大多数服不用改。

## 会不会丢存档？

不会。BAS 的设计前提就是绝不能比原版更不安全：

- 关服时会先等所有还没写完的存档落盘，再让服务器退出。
- 关服时的最后一次保存走原版同步流程，和不装 BAS 完全一样。
- BAS 不会"攒着晚点再存"——区块该存的时候立刻进入后台处理，不存在"几分钟没存、崩服就丢"的窗口（有些同类 mod 有这个问题，见下方兼容性）。
- 后台写盘万一失败会自动重试，且绝不假装成功：区块 / 世界数据（SavedData）重试用尽后退回原版同步写法兜底（区块需仍在加载中）；实体因为已被原版逐出内存、没有坐标恢复队列，重试用尽后会记 ERROR 并丢弃该区块本次的实体增量——这一点与原版一致（原版实体存盘同样无重试、无同步兜底，BAS 反而多重试了几次）。

## 工作原理（想了解再看）

原版存档卡，是因为两件重活儿都压在主线程上：把世界数据打包成存档格式、把打包好的数据写进硬盘。

BAS 让主线程只做快照——把区块当前的方块、光照、方块实体等数据拷一份独立副本。这一步很快，因为只是拷贝，不做格式转换。拷完主线程立刻继续跑游戏，后台线程拿着这份副本去打包和写盘。

因为后台线程操作的是副本，和主线程互不干扰，主线程拍完快照就能放手，不用等写盘完成。区块、实体、世界数据三类存档都走这套流程。

服务器卡的时候 BAS 会自动减速（TPS 掉到一定程度就少拍点快照），但快到下一个存档周期时强制全速，保证不会积压。

## 配置

配置文件在 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`，常用项：

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关，关掉等于没装（退回原版行为） |
| throttle.chunksPerTickBase | 4 | 主线程每游戏刻最多给几个区块拍快照 |
| throttle.adaptiveEnabled | true | 服务器卡时自动减速，一般别关 |
| workers.chunkWorkerThreads | 2 | 区块后台线程数 |
| workers.entityWorkerThreads | 2 | 实体后台线程数 |
| workers.savedDataWorkerThreads | 1 | 世界数据后台线程数，装了大量写原版 SavedData 的 mod 可调到 2 |
| compat.eventCompatMode | PARTIAL | 兼容档位，见下方说明 |

其余项（重试次数、关服超时、监控开关等）配置文件里都有注释，按需调。

### 双端功能对照

本 mod 有 Forge 1.20.1 与 NeoForge 1.21.1 两个版本，功能并不完全一致 —— 有些差异是因为 NeoForge 上游已经把问题解决了，那些项在 NeoForge 版里**不需要**存在，而不是缺失：

| 功能 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 异步存盘（区块 / 实体 / 世界数据） | 有 | 有 |
| 异步区块加载（`[load]` 段） | 有 | **无**（该段配置在 NeoForge 版不存在） |
| level.dat 注册表缓存 | 有 | 不需要（上游已把注册表表移出 level.dat） |
| level.dat 启动预检 / 备份 / 写后校验 | 有 | 不需要（1.21 的 Main 已有三级自愈与损坏隔离） |
| playerdata 读失败回退 | 有 | 不需要（1.21 上游已修） |
| advancements / stats 原子写 | 有 | 有 |
| advancements 脏跳过、玩家存盘错峰 | 有 | 有 |

### 玩家存盘（`[playerData]` 段）

装了很多 mod 的服务器上，自动保存那一刻要把每个在线玩家的三个文件全写一遍：`playerdata/<uuid>.dat`、`stats/<uuid>.json`、`advancements/<uuid>.json`。实测约 6.7ms/人，60 人时约 400ms 全压在一个游戏刻上。

| 字段 | 默认 | 说明 |
|---|---|---|
| playerData.loadFallback | true | 玩家存档读不出来时，先隔离损坏件再从 `.dat_old` 恢复，而不是让他以新号上线 |
| playerData.atomicSidecarWrite | true | 成就与统计改成「先写临时文件再原子替换」，并留一份 `.bak` |
| playerData.sidecarFsync | false | 上一项额外做一次同步刷盘。代价落在主线程且按在线人数放大（60 人一次自动保存 120 次），原版这条路径上没有任何 fsync，ext4 默认模式也已隐含该顺序，故默认关闭 |
| playerData.advancementsSkipMode | OFF | 成就没变化时跳过重写。见下 |
| playerData.advancementsForceFullWriteCycles | 12 | 连续跳过多少次后强制全量写一次 |
| playerData.staggerMaxPerTick | 0 | 每游戏刻最多存几个玩家，0 = 关闭（原版行为，一刻写完所有人） |

**前两项是数据安全修复，默认开，建议别关。** 原版在这两条路径上的结局分别是「玩家背包清空且无报错」和「成就文件被截断后进度清零」，1.21 上游都已修复，这里是回移。

**`advancementsSkipMode` 是本版最大的性能杠杆。** 原版每次自动保存都把成就文件整个重建重写一遍，不管有没有变化；实测它占玩家存盘总开销的 55%，而在 137 mod 的生产服上它几乎从不变（三次连续自动保存之间，在线玩家的成就文件 md5 完全相同）。开启方式跟注册表缓存一样：先设 `AUDIT` 跑几天确认日志里没有 `MISMATCH`，再设 `ON`。

**`staggerMaxPerTick` 不改变任何数据安全性质** —— 每个玩家仍然恰好每个自动保存周期存一次，最大陈旧窗口一点没变，只是各人的存盘时刻被错开。建议值 1 或 2；60 人时每刻 1 个只要 3 秒就排完，远在 5 分钟周期内。`/save-all`、关服、玩家退出都会绕过错峰、立刻写完。

> 注意：错峰期间原版的「正在存档」标志是 false，靠这个标志判断存档状态的 mod 看不到这些写入。

### level.dat 完整性（`[levelData]` 段）

| 字段 | 默认 | 说明 |
|---|---|---|
| levelData.verifyOnStartup | true | 启动时检查 level.dat，坏了就隔离并用 `level.dat_old` 复位 |
| levelData.startupBackup | true | 校验通过后存一份到 `<world>/betterautosave/leveldat/`，保留 3 代 |
| levelData.postWriteVerify | CHECKSUM | 每次写完 level.dat 后在后台线程回读校验。OFF / CHECKSUM / FULL |

原版只有 `level.dat_old` 一份备份，而且每次写盘都轮转 —— 一次带病启动就能把它吃掉。更麻烦的是原版的回退只在文件**缺失**时才生效；文件**存在但内容坏了**的时候，专用服会抛异常并打印一句指向数据包的误导日志然后退出。还有一种更安静的情况：文件合法但少了一个 `DataVersion` 整数，服务器会**启动成功**但世界种子静默变成 0、出生点与游戏规则全回默认，而地形文件还是旧的。

> BAS 的启动备份**永远不会被自动恢复**，原版也不认识它。自动修复只用 `level.dat_old`（最多退一个保存周期）。真要用启动备份回滚，BAS 会把可用副本和精确命令打进日志，由你停服后手动执行 —— 因为回滚会倒退世界时间、天气、游戏规则、世界边界和末影龙战进度，如果期间换过 mod 还会重映射方块 ID。

### 与备份工具的配合

BAS 把区块存盘改成异步之后，`/save-all flush` 返回（以及控制台那行 `Saved the game`）**不再代表数据已经全部落盘**。靠这行日志判断「保存完成」的外部备份工具会拿到一个提前的信号。

进程内的 mod 请改用 BAS 的 `SaveCoordination` API 拿准确状态。外部脚本目前建议改成等 BAS 自己的 `/betterautosave flush` 完成提示。

> 原版的普通 `/save-all`（不带 `flush`）本来就不保证落盘 —— 区块交给后台 IO 线程、实体只做增量保存。真正有落盘承诺的只有 `/save-all flush` 这一条，也只有它受本条影响。

### 兼容档位 eventCompatMode

少数 mod 会监听"区块保存"事件。这个开关控制 BAS 给它们的数据完整度：

- **PARTIAL（默认，推荐）**：性能最好，99% 的 mod 感知不到差别。
- **FULL**：和原版 100% 一致，但性能减半。只有当某个 mod 明确因为读不到区块方块数据而报错时才需要切。
- **DISABLED**：完全不派发该事件，最省，前提是你确定没有 mod 监听它。

不确定就保持 PARTIAL。

> 注意：PARTIAL/DISABLED 下 BAS 自行拼装 sections、不调用原版 `ChunkSerializer.write`。若某 mod 通过 mixin 直接挂在 `ChunkSerializer.write` 上写自定义区块数据（而非走 `ChunkDataEvent.Save` 或 Forge capability——这两条 PARTIAL 仍照常支持），它的序列化会被绕过、数据每次存盘被静默丢弃且无报错。装了这类 mod 请切 FULL。

### level.dat 注册表缓存（实验性，默认关闭，仅 Forge 版）

如果你的服务器 mod 很多，用 spark 看 MSPT 曲线大概率能看到**每 5 分钟一根整齐的尖峰**，哪怕服上一个人都没有。那不是区块存盘，是原版每次自动保存都会无条件重写一遍 `level.dat`——而 mod 装多了以后，这个文件里绝大部分内容是 Forge 的注册表 ID 对照表，每次都要从头重建再整体压缩一遍。

实测数据（137 个 mod 的 1.20.1 生产服）：

| 项 | 实测值 |
|---|---|
| level.dat 解压后大小 | 1,234,370 字节 |
| 其中注册表 ID 表（`fml/Registries`） | 1,215,091 字节（**98.4%**，17 个注册表 / 26,648 条 ID） |
| 相隔 5 分钟的两份 level.dat 逐字节 diff | 只有 **5 个字节**不同，且全在世界数据区；注册表那 122 万字节**完全相同** |
| 重建注册表表的主线程开销 | 每次自动保存约 25ms |

也就是说，每 5 分钟花 25ms 重算的东西，算出来跟上次一模一样。开启本功能后 BAS 把它缓存下来复用。

| 字段 | 默认 | 说明 |
|---|---|---|
| levelData.cacheRegistrySnapshot | false | 总开关。热重载生效，关掉即刻丢弃缓存 |
| levelData.registryCacheRevalidateCycles | 12 | 每命中多少次强制重算一次并与缓存逐字段对拍，不一致就报 ERROR 并采用实时结果。12 ≈ 每小时校验一次 |

**默认关闭是有意的。** 这个功能省的是每 5 分钟一次的 25ms 尖峰，不是吞吐问题——TPS 通常根本不掉。所以它先以 opt-in 形式发布，等在真实整合包上跑够久再考虑改默认。建议的开启方式：先把 `registryCacheRevalidateCycles` 设成 `1`（每次都校验，没有性能收益、纯审计），跑几天确认日志里没有 `MISMATCH`，再改回 `12` 拿收益。

缓存由三层独立机制失效，任一触发即重建：Forge 的 `IdMappingEvent`、每次写盘前对每个注册表的条目数与冻结状态做指纹校验、以及上面那个周期性全量重算对拍。

> 注意：缓存命中时会跳过 `ForgeHooks.writeAdditionalLevelSaveData` 整个方法，因此其它 mod 注入到该方法里的逻辑也会被跳过。目前没有已知的这类 mod（该方法标了 `@ApiStatus.Internal` 且只有一处调用），而且缓存内容本来就是从"那些注入全跑过一遍"的结果里摘下来的；万一某个 mod 往里写随时间变化的数据，周期性对拍会把它报成 `MISMATCH`。看到 `MISMATCH` 就把本开关关掉并反馈 issue。

> NeoForge 版没有这个配置项：NeoForge 上游已经把注册表 ID 表整个从 `level.dat` 里移除了，没有可缓存的东西，也就不存在这个尖峰。

### 异步区块加载（实验性，默认关闭，仅 Forge 版）

> 开启前务必先备份整个世界文件夹。这是实验性功能，改动的是区块「加载」路径——把存档字节解析成游戏对象（反序列化）这一步搬到后台线程。设计上即便后台解析失败，也会用同一份字节在主线程重读兜底、不丢数据；但任何动加载路径的功能，在你特定的 mod 组合下都可能有未覆盖的边界。先备份再开，是对自己存档负责。

原版加载区块时，把硬盘上的存档字节解析成游戏对象这一步是压在主线程上的。视距大、玩家快速移动或传送时，这步会成为主线程负担。开启异步加载后，BAS 把解析也搬到后台线程，主线程只做必须当场做的部分（POI 一致性、光照、加载事件回放），腾出的主线程时间转化为更高的 TPS 余量。这对「视距 10-12 + 多人」的生产服收益最实在；纯单人极限飞行（视距拉满）撞的是原版单线程区块流水线的天花板，异步解析帮不上那一段。

默认关闭，需在配置里手动开启。相关配置在 `[load]` 段（线程数在 `[workers]` 段）：

| 字段 | 默认 | 说明 |
|---|---|---|
| load.enabled | false | 异步加载总开关，独立于存档侧的 `general.enabled` |
| load.loadEventCompatMode | PARTIAL | 切分档位：PARTIAL=解析走后台、POI/光照/事件回主线程；FULL=整段解析留主线程（等于本功能关闭、零行为偏差，作 mod 不兼容时的兜底）。无 DISABLED 态（加载事件必须在主线程派发） |
| load.maxInFlight | 128 | 同时提交给后台的解析任务上限。防止一批区块同时解析完、回放全砸进同一个游戏刻造成瞬时卡。高=吞吐高但单刻突发大，低=更平滑但区块到达慢，按服逐步上调到突发重现为止 |
| load.loadMaxRetries | 1 | 后台解析抛错时的重试次数，用尽后退回原版主线程读取（不丢数据） |
| workers.loadWorkerThreads | 2 | 异步加载后台线程数。解析基本是单线程瓶颈，2 个够用，调大基本浪费 |

出问题随时把 `load.enabled` 改回 `false`，或把 `loadEventCompatMode` 切成 `FULL`，即刻回到原版加载行为（配置热重载，无需重启）。

> 注意：`load.enabled` 只在启动时生效——关时异步加载 mixin 完全不注入，避免与 C2ME 等重写加载路径的 mod 启动冲突，所以从关改开须重启服务器（改回关可热重载即刻生效）。开启后区块反序列化在后台线程跑：原版与走 ForgeCaps / 事件的常规 mod 都安全，但若某 mod 直接 mixin `ChunkSerializer.read` 并假设主线程（写非并发集合、派发假设主线程的事件），可能静默出问题且不触发兜底。不确定就保持关闭或用 `loadEventCompatMode=FULL`。

## 服内命令（需要 OP 权限）

| 命令 | 作用 |
|---|---|
| `/betterautosave status` | 一行显示当前状态 |
| `/betterautosave metrics` | 一行指标摘要 |
| `/betterautosave debug` | 完整诊断：队列深度、各阶段耗时、计数器 |
| `/betterautosave flush` | 立刻把所有没写完的存档同步落盘 |
| `/betterautosave drain-unload` | 关服前手动等所有待落盘的区块写完 |
| `/betterautosave hottest-chunks [数量]` | 列出存档最慢的区块（默认 10，最多 50），定位卡点 |
| `/betterautosave force-async` | 强制当前维度所有区块走一次后台存档（诊断用） |

## 监控（v0.9，可选）

v0.9 加了两样诊断工具，让你不用外挂 profiler 就能看清存档性能。

**hottest-chunks 命令** 按存档耗时从高到低列出最慢的区块：

```
/betterautosave hottest-chunks 20
```

高耗时区块通常出现在方块实体密集的地方——大型自动化农场、mod 商店面板、复杂红石装置。找到具体位置就能针对性优化。

**Prometheus 指标接口**（默认关闭）开启后会在指定端口提供一个 `/metrics` 页面，接 Grafana 可以画存档性能的长期趋势图，比逐次敲 `/betterautosave debug` 看快照方便得多。在配置里启用：

```toml
[prometheus]
enabled = true
port = 9450
```

安全提示：端口默认监听 `0.0.0.0`。公网服（云服务器 / VPS）请用防火墙限制 9450 端口，或把 `bindAddress` 改成 `127.0.0.1` 只允许本机连接。指标数据不含玩家隐私，但会暴露服务器的活动规律。

## 和哪些 mod 冲突

- **Smooth Chunk Save**：二选一。两者都改了区块卸载时的存档路径。相比之下 BAS 不延迟落盘（没有数据丢失窗口）、不取消原版定时存档、不吞异常。
- **C2ME-Forge / C2ME**：按功能拆开看。它的 IO / 存档侧功能（异步存档 `ioSystem.async`、序列化器重写 `gcFreeChunkSerializer`）和 BAS 抢同一条存档路径，需二选一；它的**并行加载**在 BAS 默认配置（`load.enabled=false`）下与 BAS 互补、不冲突，但一旦开启 BAS 的异步加载（`load.enabled=true`，0.16.0 起），两者都会改写区块加载调度，此时加载路径也需二选一；它的 worldgen / `midTickChunkTasksInterval` 与 BAS 始终互补（BAS 从不碰 worldgen 与 ChunkStatus 升级链）。共存办法：关掉 C2ME 的 IO / 存档侧功能、把 `autoSave` 设为 `VANILLA`，让 BAS 管存档、C2ME 管加载；若同时想开 BAS 异步加载，则再关掉 C2ME 的并行加载，让 BAS 管存档+加载、C2ME 只管 worldgen。C2ME-Forge 已停更，实际同装少见。
- **Fast Async World Save（`fastasyncworldsave`）/ SmoothChunkSave 等其它异步 / 分 tick 存盘 mod**：与 BAS 抢同一条 `ChunkMap.save` / `saveAllChunks` 接管路径，结构性二选一，不可同装——同装时按 mixin 优先级决定谁先 cancel，另一方静默失效，极端交错下写盘语义可能错乱。BAS 启动探测到 `fastasyncworldsave` 会打 WARN 提示（SmoothChunkSave 等按名在此披露，不逐一硬编码 modId）。
- **Lithium 移植版（Radium / Canary）、Starlight Forge**：兼容。
- 其他同样改了区块存档的 mod：可能让 BAS 的接管偶尔失败，这种情况 BAS 会自动退回原版处理，不影响数据安全，只是少了点性能收益。

完整兼容性矩阵见 [ROADMAP](docs/ROADMAP.md#bas-兼容性矩阵-代码核对)。

## 出问题怎么快速恢复

三档都不会破坏世界数据：

1. **临时关掉**：把配置里 `general.enabled` 改成 `false`，重启或 `/reload`。mod 还在，但所有逻辑跳过，等于原版。
2. **彻底卸载**：把 jar 从 `mods/` 移走重启。世界数据由原版存档继续保护，卸载不丢数据。
3. **只调参数**：怀疑是性能档位问题，先调 `chunksPerTickBase`（范围 1-64）或把 `eventCompatMode` 切成 `FULL`，不必整个卸载。

## 构建 / 开发

```bash
./gradlew build                 # 编译 + 跑全部测试（common / forge / neoforge）
./gradlew :forge:runServer      # 启动 1.20.1 Forge 开发服务器
./gradlew :neoforge:runServer   # 启动 1.21.1 NeoForge 开发服务器
```

多模块结构：`common/`（零-MC 纯算法核心，被两个加载器 source-merge 复用，皇冠存档状态机只此一份不分叉）+ `forge/`（1.20.1）+ `neoforge/`（1.21.1）。双版本设计与移植细节见 [MULTIVERSION_PLAN.md](docs/MULTIVERSION_PLAN.md)。

技术原理、生态调研、完整兼容性矩阵、版本路线图见 [ROADMAP.md](docs/ROADMAP.md)。

## 许可

AGPL-3.0-or-later，附两条第 7 条附加许可（[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)）：整合包分发例外——官方发布的未修改 jar 可原样收录进整合包 / 服务端包，保留项目名与仓库链接即可，无额外义务；Minecraft 链接例外——明确允许与 Minecraft 本体及 LGPL 许可的加载器组合分发。修改后的版本仍受 AGPL 全部条款约束（含第 13 条网络条款）。
