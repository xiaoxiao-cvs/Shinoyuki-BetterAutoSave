# BetterAutoSave configuration reference

[简体中文](CONFIGURATION.md) | **English**

Config file location:

```
config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml
```

The defaults work out of the box and most servers need no changes. This document walks through every setting by section; the config file itself carries equivalent comments.

**Differences between the two builds**: the Forge 1.20.1 build has 35 settings, the NeoForge 1.21.1 build 18. The 17 missing ones are the entire `[levelData]`, `[playerData]` and `[load]` sections plus `workers.loadWorkerThreads`. For the 18 shared settings, defaults, ranges and enum values are identical. A per-feature breakdown is in the [capability overview in ROADMAP.md](ROADMAP.md#能力总览) (Chinese).

## 1. Master switch and throttling

| Key | Default | Range | Description |
|---|---|---|---|
| `general.enabled` | `true` | boolean | Master switch. Off means all mod logic is bypassed and saving falls back to vanilla, as if not installed |
| `throttle.chunksPerTickBase` | `4` | 1 - 64 | Baseline number of chunks the main thread starts capturing per game tick. Adaptive throttling may halve it or skip the tick entirely, unless the deadline guard has fired |
| `throttle.adaptiveEnabled` | `true` | boolean | Slow down when the server struggles: above 51.3ms average tick time (TPS below 19.5) the budget drops, above 52.6ms (TPS below 19) the tick is skipped. Only turn it off for benchmarking |
| `throttle.deadlineGuardSeconds` | `30` | 5 - 240 | With fewer than this many seconds left before the next autosave, throttling is bypassed so dirty chunks still get captured within the cycle (the vanilla period is 300s). The floor is 5 rather than 0 because 0 would make the condition never true, leaving a sustained-low-TPS server with no forced progress for a whole cycle and a wider crash-loss window |

## 2. Worker threads

| Key | Default | Range | Description |
|---|---|---|---|
| `workers.chunkWorkerThreads` | `2` | 1 - 8 | Threads building chunk NBT. More than 2-3 gains nothing because vanilla's IOWorker serializes region file writes |
| `workers.entityWorkerThreads` | `2` | 1 - 8 | Threads building entity NBT |
| `workers.savedDataWorkerThreads` | `1` | 1 - 4 | Threads writing SavedData (`.dat`). One is enough for typical loads; raise to 2 with mods that write large SavedData (MTR, ANTE) |
| `workers.loadWorkerThreads` | `2` | 1 - 8 | Threads deserializing chunks for async loading, kept separate from the save pools so a save backlog cannot starve loading. Deserialization is essentially a single-thread bottleneck, so 2 covers typical loads. Forge build only |

## 3. Failure handling and shutdown

| Key | Default | Range | Description |
|---|---|---|---|
| `safety.shutdownTimeoutSeconds` | `60` | 5 - 600 | Total time the shutdown sequence waits for in-flight snapshots to drain. After the timeout, whatever remains goes through the vanilla synchronous path |
| `safety.maxRetries` | `3` | 0 - 10 | Re-queue attempts after a chunk's NBT build or IO submission fails. Once exhausted the state stops at FAILED and falls back to a synchronous write |
| `safety.savedDataMaxFileSizeMB` | `50` | 1 - 1024 | SavedData above this threshold is written synchronously rather than through the worker queue, so one oversized SavedData cannot inflate main-thread allocation or block the queue for a long time |

One easy misreading of `savedDataMaxFileSizeMB`: the threshold constrains the **uncompressed** serialized size (the byte array the main thread allocates), not the size of the `.dat` file on disk. It compares against the previous write's uncompressed length, estimating from the on-disk gzip size times a conservative ratio when there is no history. So a highly compressible file that is far smaller than 50MB on disk can still be routed to the synchronous path.

## 4. Event compatibility level

| Key | Default | Values | Description |
|---|---|---|---|
| `compat.eventCompatMode` | `PARTIAL` | `PARTIAL` / `FULL` / `DISABLED` | Controls when `ChunkDataEvent.Save` is dispatched and how complete the data is |

Stay on PARTIAL if unsure; 99% of mods cannot tell the difference. What each level does, and which class of third-party injection PARTIAL / DISABLED silently drops, is in [the compatibility levels section of COMPATIBILITY.en.md](COMPATIBILITY.en.md#5-compatibility-levels-and-the-data-integrity-contract).

## 5. Player data (Forge build only)

On a heavily modded server the autosave tick writes three files per online player: `playerdata/<uuid>.dat`, `stats/<uuid>.json` and `advancements/<uuid>.json`. Measured on a 137-mod production server at roughly 6.7ms per player — about 27ms at 4 players, extrapolating to roughly 400ms at 60, all in a single tick. Advancements account for 55% of it, player data 30%, stats 15%.

| Key | Default | Values | Description |
|---|---|---|---|
| `playerData.loadFallback` | `true` | boolean | When a player's save cannot be read, quarantine it and recover from `.dat_old` |
| `playerData.atomicSidecarWrite` | `true` | boolean | Write advancements and stats through a temp file and an atomic rename, keeping one `.bak` |
| `playerData.sidecarFsync` | `false` | boolean | Additionally fsync those writes |
| `playerData.advancementsSkipMode` | `OFF` | `OFF` / `AUDIT` / `ON` | Skip rewriting advancements when nothing changed |
| `playerData.advancementsForceFullWriteCycles` | `12` | 0 - 1000 | Force one full write after this many consecutive skips |
| `playerData.staggerMaxPerTick` | `0` | 0 - 64 | Players written per tick; 0 = vanilla behavior (everyone in one tick) |

### The first two are data-safety fixes; keep them on

Here is what vanilla does on those two paths.

**A failed player-data read is treated as a brand new player.** `PlayerDataStorage.save` renames the live file to `<uuid>.dat_old` before moving the temp file into place, but `load` only ever reads `<uuid>.dat`. When that read fails, vanilla logs a single stackless WARN and proceeds as a new player — inventory, ender chest, spawn point, experience and every mod's capability data zeroed at once, while the intact `.dat_old` sits in the same directory, never consulted, and the next autosave overwrites it with the blank player. With this on, the corrupt primary is first quarantined as `<uuid>_corrupted_<timestamp>.dat` to preserve evidence, then `.dat_old` is tried. A genuinely missing primary (a real new player) behaves exactly as before.

**Advancements and stats are truncating writes.** Of the three files written per player, only `playerdata/<uuid>.dat` gets vanilla's temp-file-and-rename treatment. `ServerStatsCounter.save` and `PlayerAdvancements.save` both truncate the only copy and stream into it. Any world folder shows this: `playerdata/` is full of `.dat_old`, while `stats/` and `advancements/` have no backups at all. A crash mid-write leaves truncated JSON; the next login fails to parse it, the exception is swallowed, loading continues with an empty progress table, the player joins with no progress, and the next autosave writes that empty table back. On large modpacks the advancements file can reach several hundred KB, so the write window is not small.

The upstream status of these two differs. **The read fallback was fixed in 1.21**: `PlayerDataStorage.load` copies the damaged file to `<uuid>_corrupted_<timestamp>.dat` and falls back to `.dat_old`, so this setting is a backport to 1.20.1. **The truncating sidecar writes have never been fixed**: in 1.21.1, `PlayerAdvancements.save` still uses `newBufferedWriter` (implying CREATE plus TRUNCATE_EXISTING) and `ServerStatsCounter.save` is still `FileUtils.writeStringToFile` straight onto the target. Upstream only ever gave `playerdata/<uuid>.dat` an atomic path.

`sidecarFsync` ships off deliberately. What closes the truncation window is the temp-file-and-atomic-rename itself; fsync only covers a narrower failure — power loss after the rename is durable but before the data is written back, leaving a correctly sized but all-zero file. And ext4 under its default `data=ordered` already flushes newly written data before committing an overwriting rename, so on the most common Linux configuration this switch is close to redundant. The cost, meanwhile, is real and lands on the main thread scaled by player count: at 60 players one autosave means 120 synchronous device flushes in the same tick, each waiting on a filesystem journal commit while BAS's own workers contend for it. Vanilla performs no fsync anywhere on this path. Turn it on only if the host has no battery-backed write cache and unclean power loss is a real risk, and pair it with `staggerMaxPerTick`.

### advancementsSkipMode is the biggest lever here

Vanilla's `PlayerAdvancements.save` has no dirty check at all: every autosave it walks all loaded progress, rebuilds the whole JSON tree through Gson, pretty-prints it and writes the entire file, changed or not. On a 137-mod production server it almost never changes — across three consecutive autosaves the online players' advancements files were byte-identical (mtime advancing, md5 constant) while their playerdata and stats changed every time. Of all 31 players' 18,883 criterion timestamps, 89% predate 30 days and only 36 (0.19%) come from the last 24 hours, dominated by `minecraft:recipes/*` entries that never change once unlocked.

Skipping is byte-equivalent on disk; backup mods see exactly the same data.

The three levels: `OFF` is vanilla behavior; `AUDIT` still writes every time but also computes what the dirty flag **would** have decided and compares it against the actual content, logging an ERROR naming the player on a mismatch — no speedup at all, its purpose is to let you prove the flag is trustworthy on your own modpack; `ON` actually skips.

Recommended rollout: set `AUDIT`, run for a few days, confirm no MISMATCH in the log, then set `ON`. This is the same playbook as `levelData.cacheRegistrySnapshot`.

The implementation deliberately does not reuse vanilla's `progressChanged` set: `flushDirty` clears it every tick, so it is almost always empty at autosave time, and using it as the write dirty flag would skip saves that genuinely changed. BAS uses an independent flag set only when granting or revoking progress actually succeeds.

That flag is set by `award()`, `revoke()`, `load()` and `reload()`. A mod that bypasses those and mutates progress directly (for example holding the object returned by `getOrStartProgress` and calling `grantProgress` on it) will not set it — `advancementsForceFullWriteCycles` bounds how long such a change can go unwritten, and also restores the self-healing property vanilla had by accident, where an externally damaged or hand-edited advancements file gets overwritten from memory on the next autosave. The default of 12 amounts to one guaranteed full write per player per hour, saving 11 writes out of every 12.

### staggerMaxPerTick changes no data-safety property

Every player is still written exactly once per autosave period, so the worst-case staleness window is unchanged; only the moment each write happens moves. No threads are involved, write order is untouched, and `/save-all`, shutdown and player disconnect all bypass staggering and flush immediately.

Suggested values are 1 or 2. **Do not copy Paper's default** — its `maxPerTick()` actually returns 20 under the default `rate=-1`, and 20 players per tick on a heavy modpack already exceeds 100ms, defeating the purpose. At one per tick, 60 players drain in 3 seconds, well inside the 5-minute period.

> Note: while staggering, `MinecraftServer.isSaving` is false, so a mod that keys off that flag to detect "a save is in progress" will not observe these writes.

## 6. level.dat integrity and write optimization (Forge build only)

| Key | Default | Values | Description |
|---|---|---|---|
| `levelData.verifyOnStartup` | `true` | boolean | Check level.dat at startup; if broken, quarantine it and restore from `level.dat_old` |
| `levelData.startupBackup` | `true` | boolean | After a successful check, keep a raw byte copy under `<world>/betterautosave/leveldat/`, 3 generations |
| `levelData.postWriteVerify` | `CHECKSUM` | `OFF` / `CHECKSUM` / `FULL` | Read level.dat back on a worker thread after each write |
| `levelData.cacheRegistrySnapshot` | `false` | boolean | Cache the Forge registry ID table to remove the autosave main-thread spike |
| `levelData.registryCacheRevalidateCycles` | `12` | 0 - 1000 | Force a full rebuild every N cache hits and compare it against the cache tag by tag |

### Why three layers of checking are needed

Vanilla keeps exactly one spare copy, `level.dat_old`, and rotates it on every write — one boot with a damaged file consumes it.

Worse, the vanilla fallback is only structurally reachable when the file is **missing**: the reader a dedicated server actually uses opens with `catch (IOException) { throw new UncheckedIOException }`, so a file that **exists but cannot be read** never reaches the fallback. It surfaces as "Failed to load datapacks, can't proceed with server load" and exits with a normal status code — pointing the operator at datapacks when the real problem is level.dat.

There is a quieter failure too: when gzip and NBT are both valid but `Data` is missing, or merely its `DataVersion` integer is, `getDataVersion` falls back to -1, the world-generation datafixer discards and rebuilds the entire dimension table, and nothing downstream objects. The server **starts successfully** with seed 0, default spawn, time, weather, gamerules and world border, while the region files still hold the old terrain. Startup then writes that default data back and rotates the damaged file into `level.dat_old`, destroying the last good copy within the same boot, silently.

`verifyOnStartup` checks against four verdicts (missing / undecompressable or unparseable / structurally incomplete / fine) and auto-repairs the first three from `level.dat_old`, moving the damaged file to `level.dat_corrupted_<timestamp>` (the same naming 1.21 uses). If `level.dat_old` is unusable too, it touches nothing and only prints the available backups plus the exact recovery commands.

`postWriteVerify` answers a different question. `verifyOnStartup` only asks at the next boot, by which point vanilla may already have rotated the damaged file into `level.dat_old` and eaten the last good copy; verifying right after the write catches corruption the moment it appears, when the recovery window is widest. The check runs on the SavedData worker queue, so the main thread only pays for submitting the task, and it is read-only — overwriting live world metadata while the server is running would be worse than the original fault, so it only logs loudly and leaves repair to the next startup. `CHECKSUM` does an end-to-end decompression, catching truncation and stream damage; `FULL` additionally parses the NBT and applies the same structural verdicts as the startup check, which also catches "valid NBT but no DataVersion".

Note that BAS does not take over writing `level.dat` at all (async writing was evaluated and rejected) — what is being verified here is vanilla's own write. It is still worth doing: disk faults, filesystem problems and other mods can all damage it.

> BAS's startup backups are **never restored automatically**, and vanilla does not know they exist. Automatic repair only ever uses `level.dat_old` (at most one save cycle back). To roll back to a startup copy, BAS prints the available copies and the exact command for you to run with the server stopped — because such a rollback rewinds world time, weather, gamerules, world border and dragon-fight progress, and remaps block IDs if the mod set changed in between. That is an operations decision; BAS only prints and stops.

### Registry cache (experimental, off by default)

On a heavily modded server, a spark MSPT graph will usually show **an evenly spaced spike every 5 minutes**, even with nobody online. That is not chunk saving — it is vanilla unconditionally rewriting `level.dat` on every autosave.

One classification trap to clear up first: the problem is the `level.dat` in the world root (world metadata), not the `*.dat` files under `world/data/` (SavedData). BAS's async saving has always covered only the latter.

Measured on a 137-mod 1.20.1 production server:

| Item | Measured |
|---|---|
| level.dat, uncompressed | 1,234,370 bytes |
| of which the registry ID table (`fml/Registries`) | 1,215,091 bytes (98.44%, 17 registries / 26,648 ids) |
| world data (`/Data`) | 12,018 bytes (0.97%) |
| Byte-diff of two level.dat files 5 minutes apart | only 5 bytes differ, all in `/Data`; the 1.22 MB registry block is byte-identical |
| Main-thread cost of rebuilding that table | roughly 25ms per autosave |

In other words, the thing being recomputed every 5 minutes comes out identical to last time. With this on, BAS caches and reuses it. The optimization only removes main-thread rebuild work: it does not change write timing, introduce background threads, or touch the `level.dat` write protocol.

Three independent layers invalidate the cache, any one of which triggers a rebuild: Forge's `IdMappingEvent` (covering all three official ID-change paths), a per-write fingerprint of every persisted registry (entry count plus frozen state, catching the public `ForgeRegistry.unfreeze()` path that fires no event), and the periodic forced recompute below.

**Off by default on purpose.** What it saves is a periodic ~25ms main-thread spike, not a throughput problem — TPS usually does not drop at all. Recommended rollout: set `registryCacheRevalidateCycles` to `1` first (verify on every write — no speedup, pure audit), run for a few days, confirm no MISMATCH in the log, then set it back to `12` to collect the benefit. Setting `0` disables revalidation entirely and should only be used after a long MISMATCH-free run.

Production measurement: 15 hours 18 minutes of continuous operation, 93 periodic forced recomputes all matching with zero MISMATCH, four `save-all` runs whose retrieved `level.dat` registry sections were byte-identical, and `writeAdditionalLevelSaveData` main-thread cost dropping from 76ms to 16ms.

> Note: on a cache hit, the whole of `ForgeHooks.writeAdditionalLevelSaveData` is skipped, so any other mod injecting into that method is skipped too. No such mod is known (the method is `@ApiStatus.Internal` with a single caller), and the cached content was itself taken from a pass where all such injections ran; a mod writing time-varying data there would be reported by the periodic comparison as a MISMATCH. If you see MISMATCH, turn this off and file an issue.

## 7. Async chunk loading (experimental, off by default, Forge build only)

> Back up your entire world folder before enabling this. It is experimental and changes the chunk *loading* path. By design, even if background parsing fails it re-reads the same bytes on the main thread and loses no data; but any feature touching the load path may hit an edge case not covered under your specific mod combination.

When vanilla loads a chunk, parsing the on-disk bytes into game objects sits on the main thread. With a large view distance, or players moving fast or teleporting, that becomes a main-thread burden. With this on, BAS moves parsing to background threads; the main thread only does what must happen in place (POI consistency, lighting, load-event replay), and the freed time turns into TPS headroom.

This pays off most on "view distance 10-12 plus multiplayer" production servers. Pure single-player extreme flying with view distance maxed hits the ceiling of vanilla's single-threaded chunk pipeline, which async parsing cannot help with.

| Key | Default | Values | Description |
|---|---|---|---|
| `load.enabled` | `false` | boolean | Master switch, independent of the save side's `general.enabled` |
| `load.loadEventCompatMode` | `PARTIAL` | `PARTIAL` / `FULL` | Split level. There is no DISABLED |
| `load.maxInFlight` | `128` | 2 - 1024 | Cap on parse tasks submitted to the background at once |
| `load.loadMaxRetries` | `1` | 0 - 5 | Retries when background parsing throws |
| `load.asyncPoiPrefetch` | `true` | boolean | Read POI regions off-thread on the load worker |

`maxInFlight` limits how many off-thread loads can complete simultaneously, so their main-thread replay and chunk installation do not all land in one tick. That burst shows up as "Can't keep up" during high-speed flight at high view distance — parallel decoding feeds completions faster than the main thread installs them. Excess loads queue (keeping the workers busy) rather than flooding. Higher means more throughput but larger per-tick bursts; lower means smoother but slower chunk arrival. Raise it per server until bursts reappear.

The failures `loadMaxRetries` targets are almost always transient (despite the guards, the codec dispatch cache can still race with concurrent decoding, or the DataFixer cache can hiccup), and a single retry usually resolves them without paying for a main-thread fallback. Setting 0 falls back on the first throw. The terminal fallback re-reads the same region bytes on the main thread, so no value here can cause data loss.

`asyncPoiPrefetch` is the load side's Tier A optimization: the worker reads POI bytes (through IOWorker only, which is thread-safe) and hands them to the main thread, which parses and populates the POI cache before replay so the consistency check hits the cache instead of the disk. Once async loading is on, that POI read wait is the largest remaining main-thread cost. It only applies with `load.enabled=true` at the PARTIAL level; if a POI-storage mod misbehaves, set it false to fall back to vanilla main-thread POI reads.

If something goes wrong, set `load.enabled` back to `false`, or switch `loadEventCompatMode` to `FULL`, to return to vanilla loading behavior.

> Turning `load.enabled` from off to on requires a server restart (turning it back off hot-reloads instantly) — while off, the async-load mixin bytecode is not injected at all, which is what keeps startup clean alongside mods like C2ME that rewrite the load path. The third-party compatibility contract once enabled is in [COMPATIBILITY.en.md](COMPATIBILITY.en.md#load-side-loadloadeventcompatmode-forge-only).

## 8. Diagnostics and monitoring

| Key | Default | Range | Description |
|---|---|---|---|
| `diagnostics.diagnosticLogging` | `true` | boolean | Periodically log queue depths, throughput and latency percentiles |
| `diagnostics.diagnosticLogIntervalTicks` | `6000` | 20 - 72000 | Interval between diagnostic summaries in game ticks (20 ticks = 1s). The default 6000 is 5 minutes |
| `prometheus.enabled` | `false` | boolean | Enable the Prometheus metrics HTTP exporter |
| `prometheus.bindAddress` | `"0.0.0.0"` | string | Bind address for the HTTP server |
| `prometheus.port` | `9450` | 1024 - 65535 | HTTP server port; the default avoids 9090 / 9100 / 25565 |
| `hottestChunks.windowSize` | `100` | 10 - 1000 | Latency samples kept per chunk for the p99 calculation. 100 samples per chunk is roughly 1.6 KB |
| `hottestChunks.trackLimit` | `10000` | 100 - 1000000 | Maximum chunks tracked at once; at the limit, the least recently saved chunk is evicted |

For continuous monitoring prefer the Prometheus exporter over the log. Once enabled, the server serves `GET /metrics` (Prometheus exposition format) on `bindAddress:port`; hooked into Grafana it gives long-term save-performance trends:

```toml
[prometheus]
enabled = true
port = 9450
```

**Security note**: the default bind is `0.0.0.0`, accepting connections on any interface. The metrics contain no player privacy but do expose the server's activity patterns. If the server has a public IP, firewall the port or set `bindAddress` to `127.0.0.1` to allow local scraping only.

## 9. Working with backup tools

Now that BAS writes chunks asynchronously, the return of `/save-all flush` (and the `Saved the game` console line) **no longer means the data is fully on disk**. An external backup tool that keys off that line gets a premature signal.

In-process mods should use BAS's `SaveCoordination` API for an accurate state. For external scripts, wait for BAS's own `/betterautosave flush` completion message instead.

> Vanilla's plain `/save-all` (without `flush`) never guaranteed durability either — chunks go to the background IO worker and entities only get an incremental save. Only `/save-all flush` carried that promise, and only it is affected here.

## 10. Hot-reload behavior

These settings are explicitly documented in code as hot-reloadable and take effect immediately: `levelData.cacheRegistrySnapshot`, `playerData.loadFallback`, `playerData.atomicSidecarWrite`, `playerData.sidecarFsync`, `playerData.advancementsSkipMode`, `load.asyncPoiPrefetch`.

`load.enabled` is explicitly **not** hot-reloadable: mixins decide whether to apply at class-load time based on the on-disk value, so turning it on requires a restart.

The remaining settings carry no documented hot-reload property; restart after changing them to be sure they take effect.
