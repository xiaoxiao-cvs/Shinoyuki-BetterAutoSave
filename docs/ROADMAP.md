# BetterAutoSave 路线图

当前版本 0.19.0，Forge 1.20.1 与 NeoForge 1.21.1 同源双加载器维护。

本文只讲当前能力边界与后续方向。配置项详解见 [CONFIGURATION.md](CONFIGURATION.md)，与其它 mod 的兼容性见 [COMPATIBILITY.md](COMPATIBILITY.md)，v0.2 至 v0.9 的逐版本技术方案与生产实测数据已归档至 [archive/ROADMAP-v0.2-v0.9.md](archive/ROADMAP-v0.2-v0.9.md)。

## 能力总览

| 能力 | 引入版本 | 默认 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|---|---|
| 区块存盘异步化 | v0.2 / v0.4 | 开 | 有 | 有 |
| 实体存盘异步化 | v0.6.0 | 开 | 有 | 有 |
| SavedData 异步化 | v0.7.0 | 开 | 有 | 有 |
| SaveListener / 管线状态 API | v0.7.0 / v0.10.0 | 开 | 有 | 有 |
| Prometheus 指标接口 | v0.9.0 | 关 | 有 | 有 |
| hottest-chunks 耗时排行 | v0.9.0 | 常驻 | 有 | 有 |
| 异步区块加载（仅反序列化阶段） | v0.16.0 | 关 | 有 | 无 |
| 异步 POI 预读 | v0.16.0 | 随异步加载 | 有 | 无 |
| 主线程取材成本优化 | v0.17.0 | 开 | 有 | 有 |
| 玩家存档读失败回退 | v0.19.0 | 开 | 有 | 上游已修，不需要 |
| 成就 / 统计原子写 | v0.19.0 | 开 | 有 | 无，缺口 |
| level.dat 启动校验 | v0.19.0 | 开 | 有 | 上游已修，不需要 |
| level.dat 启动备份 / 写后回读 | v0.19.0 | 开 | 有 | 无，缺口 |
| level.dat 注册表快照缓存 | v0.19.0 | 关 | 有 | 上游无此问题，不需要 |
| 成就脏跳过 | v0.19.0 | 关 | 有 | 无，未移植 |
| 玩家存盘错峰 | v0.19.0 | 关 | 有 | 无，未移植 |

三种「无」的性质不同，都以 1.21.1 反编译源码核对过：

- **上游已修，不需要**：NeoForge 1.21.1 在原版层面已解决，对应开关在 NeoForge 版不存在，不是功能缺失。`PlayerDataStorage.load` 已具备损坏隔离与 `.dat_old` 回退；`server.Main` 读 `level.dat` 失败时已有回退、隔离与拒绝启动；NeoForge 的 `CommonHooks.writeAdditionalLevelSaveData` 只写 `LoadingModList`，注册表 ID 表整个不在 `level.dat` 里，没有可缓存的对象。
- **缺口**：1.21.1 上游没有对应保护，而 BAS 也还没移植。`PlayerAdvancements.save` 与 `ServerStatsCounter.save` 在 1.21.1 仍是截断覆盖写（`newBufferedWriter` / `FileUtils.writeStringToFile`，无临时文件、无备份）；`level.dat` 仍只有单代 `level.dat_old`，写盘后也没有任何回读校验。这两项是 NeoForge 端优先补的对象。
- **未移植**：纯性能优化，缺失不影响数据安全。

## 演进脉络

BAS 的主线是把原版压在主线程上的存盘工作逐条搬离：v0.2 先搬区块 NBT 编码，v0.4 把同一套处理覆盖到区块卸载与 eager save 两条调用点，v0.6 覆盖实体，v0.7 覆盖 SavedData 并顺带暴露 SaveListener 公开 API 解锁下游 mod 接入，v0.9 补上监控与诊断能力。这一阶段结束时，存盘侧主线程只剩下技术上无法搬走的取材工作。

v0.10 至 v0.12 转向数据安全硬化与双加载器：接力协议在多轮对抗审查后收口为单字 CAS 状态机，NeoForge 1.21.1 端口以 source-merge 方式共享同一份算法核心。

v0.13 至 v0.17 开辟加载侧：把区块反序列化搬到后台线程，v0.16.0 转正为 opt-in 正式功能并补上异步 POI 预读，v0.17.0 消除了存盘取材中一处结果从不被使用的全区块遍历。

v0.19.0 把范围扩到存盘路径上此前未覆盖的两块：玩家存档三件套与 `level.dat`。前者补上原版三条静默丢数据路径并提供两项性能开关，后者建立起启动校验、启动备份、写后回读的完整闭环，并用注册表快照缓存消除了自动保存的固定主线程尖峰。

## 生态定位

2026-05 对 Forge 1.20.1 上区块异步化方向的调研结论（2026-08 复核，各项目状态未变）：

| 项目 | 平台 | Forge 1.20.1 | 状态 | 覆盖范围 |
|---|---|---|---|---|
| Moonrise（[Tuinity/Moonrise](https://github.com/Tuinity/Moonrise)） | Fabric + NeoForge | 否 | 活跃维护（1.21.x 起） | Paper chunk system 移植 + Starlight |
| C2ME（Fabric） | Fabric | 不适用 | 活跃维护 | 区块生成 + IO + 加载全套 |
| C2ME-Forge（[RelativityMC/C2ME-forge](https://github.com/RelativityMC/C2ME-forge)） | Forge | 是（0.2.0+alpha.12） | 2025-07-12 已 archived | 区块生成 + IO + 加载，不碰 SavedData |
| Starlight Forge | Forge | 是（1.1.2） | 已 archived | 光照引擎重写 |
| BAS | Forge + NeoForge | 是，活跃维护 | 当前 v0.19 | 区块 / 实体 / SavedData 存盘 + 玩家存档 + level.dat + 区块加载（opt-in，仅反序列化阶段） |

Forge 1.20.1 上专做存盘异步化且仍活跃维护的同类不多，SavedData 异步化与 `level.dat` 完整性这两块目前也少见同类覆盖。BAS 的定位是把这条存盘路径持续做透，而不是复刻一个区块优化全家桶。

## 后续方向

当前没有规划中的 minor 版本。候选工作按优先级：

- **NeoForge 端补数据安全缺口**：上表两项标为「缺口」的（成就 / 统计原子写、`level.dat` 启动备份与写后回读）在 1.21.1 上游确认没有对应保护，是 NeoForge 端优先级最高的移植对象。其余 Forge 独有项要么上游已修，要么是纯性能优化。
- **opt-in 功能转默认**：注册表快照缓存与成就脏跳过都在生产环境验证过收益，但都改变了原版的写盘行为，需要在更多真实整合包上跑够时长再考虑翻默认。
- **运营侧工作优先于新功能**：BAS 的存盘覆盖面已完整，后续重心在发版铺量、多 mod 兼容矩阵维护与问题响应。

已否决的方向，不再考虑：

- **完整版异步区块加载**（铺开 ChunkStatus 升级链与 worldgen）：跨区块依赖图需要 actor 模型重构，工作量数量级超出本项目可行范围，且与大量 worldgen mod 的注入点正面撞车。只做风险最小的反序列化阶段，即已落地的 opt-in 版本。完整论证见[归档](archive/ROADMAP-v0.2-v0.9.md#v080--chunk-load-路径异步化-已废弃)。
- **按玩家移动预测预加载区块**：加载已经足够快，瓶颈是吞吐不是延迟；预测开销随在线人数增长，会压在 `DistanceManager` 这个真正的瓶颈上，人多时反而更差。
- **线程安全的 SectionStorage 重写（POI Tier B）**：C2ME 式的重写只能再换约 9% 的主线程占比，代价是村民 AI 数据可能静默腐化，收益风险比不成立。已落地的 Tier A（POI 读盘后台化）覆盖了这块开销的绝大部分。
