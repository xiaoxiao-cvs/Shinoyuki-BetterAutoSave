# BetterAutoSave compatibility

[简体中文](COMPATIBILITY.md) | **English**

This document covers how BAS coexists with other mods, the evidence behind each verdict, and the injection-point detail mod authors need for troubleshooting. For what each setting does, see [CONFIGURATION.en.md](CONFIGURATION.en.md).

Verdicts reflect 0.19.0, both the Forge 1.20.1 and NeoForge 1.21.1 builds.

## 1. What BAS touches

BAS only injects into the storage, serialization and server-lifecycle layer. Deduplicated, its mixins target 13 classes, none of which belong to the gameplay layer.

**Explicitly untouched** (the evidence is the absence of any mixin targeting these classes):

| Area | Evidence |
|---|---|
| Light engine internals | No mixin on `LevelLightEngine` / `ThreadedLevelLightEngine` / `LightChunkGetter`. With async loading enabled, BAS only defers two call sites *inside* `ChunkSerializer.read` (`retainData`, `queueSectionData`) back to the main thread; the light engine itself is never rewritten |
| Worldgen | No mixin on `ChunkGenerator` / `NoiseBasedChunkGenerator` / `WorldGenRegion` |
| The ChunkStatus upgrade chain | No mixin on `ChunkStatus` / `ChunkHolder`; both appear only as type references |
| Entity ticking | No mixin on `Entity` / `LivingEntity` / `EntityTickList`. The only entity-side target is `EntityStorage.storeEntities`, the save entry point |
| Network packets | No mixin on any networking class |
| Chunk load scheduling (default config) | With `load.enabled=false`, the four load-side mixins are not injected at all — see "startup gating" below |

## 2. Cannot be installed together

These mods take over the same save path as BAS, which makes them structurally pick-one. Installed together, mixin priority decides who cancels first, the other silently stops working, and save semantics can corrupt under extreme interleaving.

| Mod | Notes |
|---|---|
| **Fast Async World Save** (`fastasyncworldsave`) | BAS logs a WARN at startup when it detects this one. It is the only hardcoded modId, because it is the only one whose modId can be confirmed |
| **Smooth Chunk Save** | Also modifies the chunk-unload save path. BAS does not probe for it in code; it is disclosed here by name. Compared to it, BAS does not delay disk writes (no data-loss window), does not cancel vanilla periodic autosaves and does not swallow exceptions |
| Other async / per-tick save mods | Anything taking over `ChunkMap.save` or `saveAllChunks` falls in this category |

The startup probe only logs a WARN. It does not block startup, does not disable any feature, and BAS declares no `incompatible` entry in its mod metadata — the call is left to the server owner.

## 3. C2ME / C2ME-Forge: split it by feature

C2ME is a bundle of features whose relationship with BAS differs per feature. It is neither a blanket "conflict" nor a blanket "compatible".

| C2ME feature | Relationship | Notes |
|---|---|---|
| Async saving (`ioSystem.async`) | Conflict, pick one | Takes over the same save path as BAS |
| Serializer rewrite (`gcFreeChunkSerializer`) | Conflict, pick one | Same |
| Parallel loading | Complementary under BAS defaults; conflict once BAS async loading is on | With `load.enabled=false` (default) BAS never touches load scheduling; with `load.enabled=true` both rewrite `scheduleChunkLoad` |
| Worldgen / `midTickChunkTasksInterval` | Always complementary | BAS never touches worldgen or the ChunkStatus upgrade chain |

To run both: turn off C2ME's IO / save-side features and set `autoSave` to `VANILLA` — BAS owns saving, C2ME owns loading. If you also want BAS async loading, additionally turn off C2ME's parallel loading so BAS owns saving plus loading and C2ME owns only worldgen.

C2ME-Forge was archived on 2025-07-12, so the overlap is rare in practice.

## 4. Verified compatible

| Mod | Reasoning |
|---|---|
| Starlight (Forge 1.20.1) | BAS only reads DataLayer through the public `getDataLayerData()` API, which Starlight must keep honoring (vanilla saving uses it too); `DataLayer.copy()` is a `byte[].clone` and is decoupled from the light engine implementation |
| Radium / Canary (Lithium ports) | Change entity and block ticking, not the save path |
| Modernfix | Memory and startup optimization, does not touch the save path |
| FerriteCore | Changes BlockState memory representation; BAS goes through the standard `PalettedContainer.copy()` |
| Embeddium / Rubidium | Client-side rendering, unrelated to server-side saving |
| Worldgen mods (Terralith / BetterEnd / Tectonic / Cataclysm, etc.) | See the compatibility levels below. Injections that go through `ChunkDataEvent.Save` or capabilities work normally at the default level |

Other mods that also touch chunk saving may occasionally make BAS's takeover fail, in which case BAS falls back to vanilla handling — data safety is unaffected, you just lose some of the performance gain.

| Mod | Verdict | Notes |
|---|---|---|
| DimThread | Medium risk, untested | Gives each dimension its own tick thread, which may trip BAS's `ServerThreadAssert`. Would need dedicated adaptation; no measurements exist |

## 5. Compatibility levels and the data-integrity contract

### Save side: `compat.eventCompatMode`

Default **PARTIAL** on both builds. Values: PARTIAL / FULL / DISABLED.

| Level | Behavior | Effect on third-party mods |
|---|---|---|
| PARTIAL (default) | The main thread fires `ChunkDataEvent.Save` with a core tag that excludes sections; the worker assembles sections afterwards | Listeners that only attach sub-tags or read non-section fields are unaffected; a listener calling `tag.get("sections")` gets null |
| FULL | The main thread builds the complete tag (sections included) before firing the event; the worker only does IO | 100% vanilla-equivalent, at reduced performance benefit |
| DISABLED | The event is never dispatched | Mods listening to `ChunkDataEvent.Save` stop working |

**The data-integrity red line for PARTIAL and DISABLED**: neither calls `ChunkSerializer.write` to assemble sections. So if a mod injects extra chunk NBT by mixing into `ChunkSerializer.write` directly — rather than through `ChunkDataEvent.Save` or capabilities / data attachments, both of which PARTIAL still honors — its serialization is bypassed and that data is silently dropped on every save with no error. Switch to FULL if you run such a mod.

### Load side: `load.loadEventCompatMode` (Forge only)

Default **PARTIAL**. Only two levels, PARTIAL and FULL — there is **deliberately no DISABLED**, because `ChunkDataEvent.Load` must always be dispatched on the main thread. The weakest level is therefore FULL (the whole read stays on the main thread), not "skip the event".

**The async-loading contract**: once enabled, `ChunkSerializer.read` runs on a load worker. BAS defers every *known* vanilla and Forge main-thread side effect (POI, lighting, ForgeCaps, `ChunkDataEvent.Load`) back to the main thread for replay — but any *other* mod's code injected into `ChunkSerializer.read` (or the `LevelChunk` constructor, or the codecs it uses) also runs on that worker.

- Third-party injections that **throw** are self-healing: after the worker throws, the terminal fallback re-reads the same bytes on the main thread, losing no data.
- Injections that **do not throw but assume the main thread** (writing a non-concurrent collection, firing a main-thread-only event) can race or corrupt silently, and the fallback will not trigger.

If unsure, keep `load.enabled=false` or use `loadEventCompatMode=FULL`.

### Startup gating of the load-side mixins

`load.enabled` is read off the on-disk `common.toml` by an `IMixinConfigPlugin` at class-load time. While it is off, `ChunkMapLoadMixin`, `ChunkSerializerLoadMixin`, `LevelChunkCapsLoadMixin` and `SectionStorageLoadMixin` are not injected at all.

This exists so BAS can coexist with mods that rewrite `scheduleChunkLoad` / `ChunkSerializer.read`, such as C2ME-Forge — otherwise `defaultRequire=1` would turn that into a hard InjectionError crash at startup.

The cost is that turning it from off to on requires a server restart; config hot-reload alone cannot enable it (turning it back off does hot-reload instantly). A missing config file, a read failure, or a missing section or key are all treated as off.

## 6. Injection points

For mod authors and troubleshooting. Only business-logic mixins are listed; the pure Accessor / Invoker interfaces (six on each build, no business logic) are omitted.

### Forge 1.20.1 (18 business mixins)

| Target | Method | Gated by |
|---|---|---|
| `ChunkAccess` | `setUnsaved` | none |
| `ChunkMap` | `saveAllChunks` | `general.enabled` |
| `ChunkMap` | `save(ChunkAccess)` | `general.enabled`, `compat.eventCompatMode` |
| `ChunkMap` | the `thenApplyAsync` inside `scheduleChunkLoad` | the whole `load.*` group (startup gated) |
| `ChunkSerializer` | `read` (7 `@WrapOperation`) | startup gated |
| `LevelChunk` | `initInternal` inside the constructor | startup gated |
| `SectionStorage` | POI reading (Invoker plus injected methods) | startup gated |
| `DimensionDataStorage` | `save()` | `general.enabled`, `safety.savedDataMaxFileSizeMB` |
| `SavedData` | `setDirty(boolean)` / `isDirty()` | none |
| `EntityStorage` | `storeEntities` | `general.enabled` |
| `MinecraftServer` | `stopServer` / `saveEverything` / `tickServer` | partly `general.enabled` and the stagger setting |
| `ForgeHooks` | `writeAdditionalLevelSaveData` | `general.enabled` + `levelData.cacheRegistrySnapshot` |
| `LevelStorageSource$LevelStorageAccess` | `readAdditionalLevelSaveData` | `levelData.verifyOnStartup` / `startupBackup` |
| `LevelStorageSource$LevelStorageAccess` | `saveDataTag` | `levelData.postWriteVerify` (OFF disables it) |
| `PlayerAdvancements` | `award` / `revoke` / `reload` / `load` / `save` | `playerData.advancements*`, `atomicSidecarWrite` |
| `PlayerDataStorage` | `load` | `playerData.loadFallback` |
| `PlayerList` | `saveAll` / `remove` | `playerData.staggerMaxPerTick` |
| `ServerStatsCounter` | `save` | `playerData.atomicSidecarWrite`, `sidecarFsync` |

### NeoForge 1.21.1 (7 business mixins)

| Target | Method | Gated by |
|---|---|---|
| `ChunkAccess` | `setUnsaved` | none |
| `ChunkMap` | `saveAllChunks` | `general.enabled` |
| `ChunkMap` | `save(ChunkAccess)` | `general.enabled`, `compat.eventCompatMode` |
| `DimensionDataStorage` | `save()` | `general.enabled`, `safety.savedDataMaxFileSizeMB` |
| `EntityStorage` | `storeEntities` | `general.enabled` |
| `MinecraftServer` | `tickServer` | none |
| `SavedData` | `setDirty(boolean)` / `isDirty()` | none |

The NeoForge build is a pure async-save implementation: no load-side mixins, no `levelData` or `playerData` injections, and every method it intercepts is on a write path.
