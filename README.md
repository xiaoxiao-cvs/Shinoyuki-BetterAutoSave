# BetterAutoSave（更好的自动保存）

**简体中文** | [English](README.en.md)

![BetterAutoSave](https://raw.githubusercontent.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/main/docs/images/BetterAutoSave.png)

> 让服务器自动存档时不再卡顿 ~
> 此Mod部分代码由 Claude Opus 4.8 / Claude Fable 5 生成，若有任何问题请提交 issue

**下载**：[Modrinth](https://modrinth.com/mod/shinoyuki-betterautosave) · [GitHub Releases](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/releases)

> 项目状态：活跃开发中，处于 1.0 前的快速迭代期，更新较频繁（含配合 BetterBackup 的联动发版）。核心异步存档已在生产环境长期验证、默认即安全；异步区块加载为较新的可选特性，默认关闭。建议关注 Releases / Modrinth 获取更新

## 这个 mod 解决什么问题

原版 Minecraft 服务器每 5 分钟自动存一次档。存档的那一下，主线程要把所有改动过的区块打包、写进硬盘——这期间整个服务器是停住的。空服感觉不到，但 mod 多、人多的服上，这一卡经常有 200 毫秒到几秒，全服玩家一起卡。

除了定时存档，还有几个时刻同样会卡：玩家传送或大量区块被卸载时（区块离开内存前要存一次）、某片区域实体特别多时（大型农场 / 刷怪塔）、以及村庄袭击这类全局数据（原版 SavedData）写盘时单个文件太大。

BAS 让主线程只做一件必须当场做的事——给要保存的数据拍个独立快照，剩下的打包和写硬盘都交给后台线程。因为后台操作的是副本，与主线程互不干扰，主线程拍完就能放手。区块、实体、世界数据三类存档都走这套流程。服务器卡的时候 BAS 会自动减速，但快到下一个存档周期时强制全速，保证不会积压。

## 适用环境与安装

两个加载器同源维护，均为纯服务端 mod，客户端不用装：

- **Forge 1.20.1**：Forge 47.3.22 或更新（47.3 / 47.4 系列都行），Java 17 或更新
- **NeoForge 1.21.1**：NeoForge 21.1 系列，Java 21 或更新

从 [Modrinth](https://modrinth.com/mod/shinoyuki-betterautosave) 或 Releases 下载对应加载器的 jar，丢进服务端的 `mods/` 文件夹即可：

- **Forge 版认准带 `-all` 的 jar**（形如 `shinoyuki_betterautosave-<版本>-all.jar`，自带 MixinExtras 等依赖）。不带 `-all` 的 thin jar 会因缺依赖在加载时崩溃。
- **NeoForge 版**用 `shinoyuki_betterautosave-neoforge-<版本>.jar`。

第一次启动后配置文件生成在 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`，默认配置开箱即用，大多数服不用改。

## 会不会丢存档

不会。BAS 的设计前提就是绝不能比原版更不安全：

- 关服时会先等所有还没写完的存档落盘，再让服务器退出，最后一次保存走原版同步流程。
- BAS 不会「攒着晚点再存」——区块该存的时候立刻进入后台处理，不存在「几分钟没存、崩服就丢」的窗口（有些同类 mod 有这个问题）。
- 后台写盘万一失败会自动重试，且绝不假装成功：区块与世界数据重试用尽后退回原版同步写法兜底；实体因为已被原版逐出内存、没有坐标恢复队列，重试用尽后会记 ERROR 并丢弃该区块本次的实体增量——这一点与原版一致（原版实体存盘同样无重试、无同步兜底，BAS 反而多重试了几次）。

除此之外，Forge 版还补上了原版三处会静默丢数据的路径（玩家存档读失败、成就与统计的截断写、`level.dat` 的单份备份），这些修复默认开启，详见 [配置参考](docs/CONFIGURATION.md)。

## 常用配置

| 字段 | 默认 | 说明 |
|---|---|---|
| `general.enabled` | true | 总开关，关掉等于没装（退回原版行为） |
| `throttle.chunksPerTickBase` | 4 | 主线程每游戏刻最多给几个区块拍快照 |
| `throttle.adaptiveEnabled` | true | 服务器卡时自动减速，一般别关 |
| `workers.chunkWorkerThreads` | 2 | 区块后台线程数 |
| `workers.entityWorkerThreads` | 2 | 实体后台线程数 |
| `workers.savedDataWorkerThreads` | 1 | 世界数据后台线程数，装了大量写原版 SavedData 的 mod 可调到 2 |
| `compat.eventCompatMode` | PARTIAL | 事件兼容档位，不确定就别动 |

Forge 版共 43 个配置项，NeoForge 版 26 个。**全部配置项的含义、默认值理由与上线建议见 [配置参考 CONFIGURATION.md](docs/CONFIGURATION.md)**，其中包括玩家存档保护、`level.dat` 完整性、异步区块加载、Prometheus 监控与备份工具配合。

## 双端功能对照

两个版本功能并不完全一致。有些差异是因为 NeoForge 上游已经把问题解决了，那些项在 NeoForge 版里不需要存在；另一些是 Forge 版先行、尚未对称移植。

| 功能 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 异步存盘（区块 / 实体 / 世界数据） | 有 | 有 |
| 异步区块加载（`[load]` 段） | 有 | 无（该段配置不存在，NeoForge 版无任何加载侧 mixin） |
| level.dat 注册表缓存 | 有 | 不需要（上游已把注册表表移出 level.dat） |
| level.dat 启动校验 | 有 | 不需要（1.21 读失败已有回退与损坏隔离） |
| level.dat 启动备份 / 写后回读 | 有 | 无（1.21 上游也没有，待移植） |
| playerdata 读失败回退 | 有 | 不需要（1.21 上游已修） |
| 成就 / 统计原子写 | 有 | 无（1.21 上游仍是截断写，待移植） |
| 成就脏跳过、玩家存盘错峰 | 有 | 无（性能优化，待移植） |
| 同步区块加载 / tick 间停顿诊断 | 有 | 有 |

## 服内命令

需要 OP 权限（等级 2）。

| 命令 | 作用 |
|---|---|
| `/betterautosave status` | 一行显示当前状态 |
| `/betterautosave metrics` | 一行指标摘要 |
| `/betterautosave debug` | 完整诊断：队列深度、各阶段耗时、计数器 |
| `/betterautosave flush` | 把所有未落盘的存档排空。命令立即返回，后台轮询直到完成或超时（`safety.shutdownTimeoutSeconds`） |
| `/betterautosave drain-unload` | 等所有待落盘的区块写完，同样是后台轮询、命令立即返回 |
| `/betterautosave hottest-chunks [数量]` | 列出存档最慢的区块（默认 10，可填 1-50），定位卡点 |
| `/betterautosave force-async` | 强制当前维度所有区块走一次后台存档（诊断用） |
| `/betterautosave diagnose [数量]` | 列出主线程同步区块加载的来源与 tick 间停顿统计（默认 10，可填 1-50） |
| `/betterautosave diagnose reset` | 清空上面两张统计表（累计计数不清，见下） |

高耗时区块通常出现在方块实体密集的地方——大型自动化农场、mod 商店面板、复杂红石装置。

## 看不见的卡顿：主线程同步加载与 tick 间停顿

有两类卡顿常规监控看不出来。0.20.0 起 BAS 会自己把它们记下来，只观测、不干预，默认开启。

**主线程同步区块加载。** 区块不在内存里的时候，如果有代码在主线程上直接把它要过来，主线程就得原地等到硬盘读完、必要时连地形生成一起等完——这期间整个服务器停住。一次 74 人的生产压测里实测到单次 5.2 秒。BAS 把超过 50 毫秒（阈值可调）的每一次都记下来：阻塞时长、区块坐标、维度，以及调用栈里第一个非原版类。

**tick 之间的长停顿。** MSPT 只统计一个游戏刻内部的耗时，两刻之间的等待不计入。同一次压测里出现过 17.1 秒和 14.8 秒的停顿，TPS 曲线和监控面板上完全看不见——面板一切正常，玩家却在断线。BAS 在每刻开始与结束各取一次时间戳，间隔超过 1 秒（可调）就记一次；要落到具体是哪个任务，另有一个默认关闭的深度档。

**开销**：同步加载的探针装在原版四槽区块缓存未命中之后的那条分支上，缓存命中时根本执行不到；未命中时也只多两次纳秒取时，调用栈只在确实超过阈值之后才采集，正常运行中一次都不采。tick 间隔就是每刻两次纳秒取时。

**关于归因**：检测报告的是「阻塞发生在哪条调用链上」，不是「谁有 bug」。检测到的阻塞多数来自第三方 mod 的调用模式——同步取区块在很多场景下是完全合理的写法，只是代价随服务器规模、视距和硬盘速度放大——不代表该 mod 存在缺陷。请把它当作定位的起点，而不是结论。

用 `/betterautosave diagnose` 查看，或接 Prometheus 看 `bas_sync_load_stalls_total`、`bas_tick_gap_max_seconds` 等四个新指标。八个配置项与完整输出样例见 [配置参考第八节](docs/CONFIGURATION.md#八诊断与监控)。

## 设计边界

BAS 经过评估，在目前的极致兼容性前提下已经没有更多的异步区块优化空间可做；尚有空间的地方，动了都会破坏兼容性，导致数据安全问题和各种 mod 间的兼容问题。

一次 74 人在线的生产压测，采样 550 秒，主线程上 `NbtIo` 写入占 0.03%，`ChunkSerializer` 序列化占 0.67%，BAS 自身全部开销合计 1.42%。这不等于「已经没有任何优化空间」——同一份采样里还剩约 0.3 个百分点（`copySections` 对空 section 也无条件做两份 `PalettedContainer.copy`，约 0.1 个百分点；POI 回放批量化，约 0.1 至 0.2 个百分点），但这已经在噪声量级，不改变兼容性前提能拿到的收益不足半个百分点。

也不等于「BAS 让区块加载不再是瓶颈」，事实相反：瓶颈恰恰在区块系统中 BAS 够不着的那半边——同一份采样里，`DistanceManager` 的距离场传播占掉了区块系统主线程预算的 82%，真正推进加载的任务只占 18%。

再往前一步分别会破坏什么，是具体的：把 `ForgeCaps` 搬到后台线程，与 issue #8 那次数据丢失同一家族，挂区块 capability 的 mod 会静默丢数据；把 `ChunkDataEvent.Load` 搬到后台线程，所有监听方都会在非主线程上被回调，它不抛异常，只是慢慢腐化；把 POI / `SectionStorage` 搬到后台线程，`SectionStorage` 不是线程安全的，后果是村民 AI 数据静默腐化；接管 `DistanceManager`，它的状态机线程不安全，且与 C2ME 直接冲突；强制双端安装或强制全量异步，等于放弃单端安装、opt-in、随时可回退这个最大差异化优势。

性能这条路已经走到了兼容性允许的尽头，所以这一版换了方向：不再挖那零点几个百分点，而是让 BAS 能告诉服主卡顿真正来自哪里。完整论证见 [ROADMAP.md 的「明确不做及其理由」](docs/ROADMAP.md#明确不做及其理由)。

## 和哪些 mod 冲突

- **不可同装**（都抢同一条存盘接管路径）：Fast Async World Save（`fastasyncworldsave`，BAS 检测到会打 WARN）、Smooth Chunk Save，以及其它异步 / 分 tick 存盘 mod。
- **C2ME / C2ME-Forge**：按功能拆开。存档侧二选一；并行加载在 BAS 默认配置下互补，但开启 BAS 的异步加载后也需二选一；worldgen 始终互补。
- **兼容**：Starlight、Radium / Canary、Modernfix、FerriteCore 等。

判定依据、完整拦截点清单与兼容档位的数据完整性契约见 [兼容性 COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 出问题怎么快速恢复

三档都不会破坏世界数据：

1. **临时关掉**：把 `general.enabled` 改成 `false`，重启或 `/reload`。mod 还在，但所有逻辑跳过，等于原版。
2. **彻底卸载**：把 jar 从 `mods/` 移走重启。世界数据由原版存档继续保护，卸载不丢数据。
3. **只调参数**：怀疑是性能档位问题，先调 `chunksPerTickBase`（1-64）或把 `eventCompatMode` 切成 `FULL`，不必整个卸载。

## 构建 / 开发

```bash
./gradlew build                 # 编译 + 跑全部测试（common / forge / neoforge）
./gradlew :forge:runServer      # 启动 1.20.1 Forge 开发服务器
./gradlew :neoforge:runServer   # 启动 1.21.1 NeoForge 开发服务器
```

多模块结构：`common/`（零-MC 纯算法核心，被两个加载器 source-merge 复用，皇冠存档状态机只此一份不分叉）+ `forge/`（1.20.1）+ `neoforge/`（1.21.1）。

版本路线图与能力总览见 [ROADMAP.md](docs/ROADMAP.md)，双版本移植细节见 [archive/MULTIVERSION_PLAN.md](docs/archive/MULTIVERSION_PLAN.md)。

## 许可

AGPL-3.0-or-later，附两条第 7 条附加许可（[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)）：整合包分发例外——官方发布的未修改 jar 可原样收录进整合包 / 服务端包，保留项目名与仓库链接即可，无额外义务；Minecraft 链接例外——明确允许与 Minecraft 本体及 LGPL 许可的加载器组合分发。修改后的版本仍受 AGPL 全部条款约束（含第 13 条网络条款）。
