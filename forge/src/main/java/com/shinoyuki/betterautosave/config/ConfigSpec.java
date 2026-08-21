package com.shinoyuki.betterautosave.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfigSpec {

    public enum EventCompatMode {
        PARTIAL,
        FULL,
        DISABLED
    }

    /**
     * 异步加载切分模式。无 DISABLED 态: ChunkDataEvent.Load 不可跳 (第三方监听方假设主线程, 见
     * docs/ASYNC_LOAD_DESIGN.md 第六节), 故最弱也是 FULL (整段 read 留主线程, 零行为偏差), 不存在
     * "跳过事件" 的合法态。
     */
    public enum LoadCompatMode {
        PARTIAL,
        FULL
    }

    /**
     * advancements 脏跳过档位。AUDIT 是 issue #25 已验证过的上线打法: 照常写盘但同时对拍,
     * 生产跑够时长零 MISMATCH 后才翻 ON。没有"部分跳过"这种中间态, 故不是布尔的三态化。
     */
    public enum AdvancementsSkipMode {
        OFF,
        AUDIT,
        ON
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue CHUNKS_PER_TICK_BASE;
    public static final ForgeConfigSpec.IntValue WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue ENTITY_WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue SAVED_DATA_WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue LOAD_WORKER_THREADS;
    public static final ForgeConfigSpec.BooleanValue ADAPTIVE_ENABLED;
    public static final ForgeConfigSpec.IntValue SHUTDOWN_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue DEADLINE_GUARD_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue SAVED_DATA_MAX_FILE_SIZE_MB;
    public static final ForgeConfigSpec.EnumValue<EventCompatMode> EVENT_COMPAT_MODE;
    public static final ForgeConfigSpec.BooleanValue LEVEL_DATA_CACHE_REGISTRY_SNAPSHOT;
    public static final ForgeConfigSpec.IntValue LEVEL_DATA_REGISTRY_CACHE_REVALIDATE_CYCLES;
    public static final ForgeConfigSpec.BooleanValue LEVEL_DATA_VERIFY_ON_STARTUP;
    public static final ForgeConfigSpec.BooleanValue LEVEL_DATA_STARTUP_BACKUP;
    public static final ForgeConfigSpec.EnumValue<com.shinoyuki.betterautosave.core.leveldat.LevelDataIntegrity.VerifyStrength>
            LEVEL_DATA_POST_WRITE_VERIFY;
    public static final ForgeConfigSpec.BooleanValue PLAYER_DATA_LOAD_FALLBACK;
    public static final ForgeConfigSpec.BooleanValue PLAYER_DATA_ATOMIC_SIDECAR_WRITE;
    public static final ForgeConfigSpec.BooleanValue PLAYER_DATA_SIDECAR_FSYNC;
    public static final ForgeConfigSpec.EnumValue<AdvancementsSkipMode> PLAYER_DATA_ADVANCEMENTS_SKIP_MODE;
    public static final ForgeConfigSpec.IntValue PLAYER_DATA_ADVANCEMENTS_FORCE_FULL_WRITE_CYCLES;
    public static final ForgeConfigSpec.IntValue PLAYER_DATA_STAGGER_MAX_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue LOAD_ENABLED;
    public static final ForgeConfigSpec.EnumValue<LoadCompatMode> LOAD_EVENT_COMPAT_MODE;
    public static final ForgeConfigSpec.IntValue LOAD_MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue LOAD_MAX_IN_FLIGHT;
    public static final ForgeConfigSpec.BooleanValue LOAD_POI_PREFETCH;
    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_LOGGING;
    public static final ForgeConfigSpec.IntValue DIAGNOSTIC_LOG_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue SYNC_LOAD_DETECTION;
    public static final ForgeConfigSpec.IntValue SYNC_LOAD_THRESHOLD_MS;
    public static final ForgeConfigSpec.IntValue SYNC_LOAD_TRACK_LIMIT;
    public static final ForgeConfigSpec.IntValue SYNC_LOAD_STACK_DEPTH;
    public static final ForgeConfigSpec.BooleanValue TICK_GAP_DETECTION;
    public static final ForgeConfigSpec.IntValue TICK_GAP_THRESHOLD_MS;
    public static final ForgeConfigSpec.BooleanValue TICK_GAP_DEEP_ATTRIBUTION;
    public static final ForgeConfigSpec.IntValue TICK_GAP_DEEP_TRACK_LIMIT;
    public static final ForgeConfigSpec.BooleanValue PROMETHEUS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> PROMETHEUS_BIND_ADDRESS;
    public static final ForgeConfigSpec.IntValue PROMETHEUS_PORT;
    public static final ForgeConfigSpec.IntValue HOTTEST_CHUNKS_WINDOW_SIZE;
    public static final ForgeConfigSpec.IntValue HOTTEST_CHUNKS_TRACK_LIMIT;

    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("BetterAutoSave common configuration (shared across all worlds)").push("general");

        ENABLED = BUILDER
                .comment("Master switch. When false, all mod logic is bypassed and chunk saving falls back to vanilla behavior.")
                .define("enabled", true);

        BUILDER.pop();

        BUILDER.comment("Throttle for main-thread snapshot capture").push("throttle");

        CHUNKS_PER_TICK_BASE = BUILDER
                .comment("Base limit on chunks the scheduler will start capturing per server tick.",
                         "Adaptive throttling may halve or skip based on TPS unless the deadline guard fires.")
                .defineInRange("chunksPerTickBase", 4, 1, 64);

        ADAPTIVE_ENABLED = BUILDER
                .comment("Reduce per-tick budget when avg tick time exceeds 51.3ms (TPS<19.5) and skip ticks when above 52.6ms (TPS<19).",
                         "Disable only for benchmarking; production servers should keep this on.")
                .define("adaptiveEnabled", true);

        DEADLINE_GUARD_SECONDS = BUILDER
                .comment("When the autosave cycle has fewer seconds remaining than this value, throttling is bypassed",
                         "to ensure all dirty chunks complete a snapshot within the cycle. Vanilla cycle length is 300s.",
                         "Minimum 5: 0 would disable the deadline guard entirely (remainingSeconds is always >= 0, so the",
                         "'remaining < guard' condition never holds), letting sustained low TPS defer every dirty chunk for a",
                         "whole cycle with no forced flush - a footgun that widens the loss window on kill/OOM, so the floor is 5.")
                .defineInRange("deadlineGuardSeconds", 30, 5, 240);

        BUILDER.pop();

        BUILDER.comment("Worker-thread pool sizes").push("workers");

        WORKER_THREADS = BUILDER
                .comment("Threads dedicated to building chunk NBT off the main thread.",
                         "More threads do not help past 2-3 because vanilla IOWorker serializes region-file writes.")
                .defineInRange("chunkWorkerThreads", 2, 1, 8);

        ENTITY_WORKER_THREADS = BUILDER
                .comment("Threads dedicated to building entity NBT off the main thread.")
                .defineInRange("entityWorkerThreads", 2, 1, 8);

        SAVED_DATA_WORKER_THREADS = BUILDER
                .comment("v0.7: threads dedicated to writing SavedData (.dat) files off the main thread.",
                         "1 is enough for typical loads (SavedData files are few and small).",
                         "Bump to 2 if you run mods with many large SavedData files (e.g. MTR, ANTE).")
                .defineInRange("savedDataWorkerThreads", 1, 1, 4);

        LOAD_WORKER_THREADS = BUILDER
                .comment("v0.x: threads dedicated to off-thread chunk deserialization (async load).",
                         "Independent pool from chunk-save workers so a save backlog cannot starve loads.",
                         "Deserialize is largely single-thread bound; 2 covers typical loads.")
                .defineInRange("loadWorkerThreads", 2, 1, 8);

        BUILDER.pop();

        BUILDER.comment("Failure handling and shutdown").push("safety");

        SHUTDOWN_TIMEOUT_SECONDS = BUILDER
                .comment("Total time the server stop sequence will wait for in-flight snapshots to drain",
                         "before falling back to a synchronous vanilla save path for the stragglers.")
                .defineInRange("shutdownTimeoutSeconds", 60, 5, 600);

        MAX_RETRIES = BUILDER
                .comment("Number of times a chunk that fails NBT build or IO submit will be re-queued",
                         "before its state is parked in FAILED and a synchronous fallback is used.")
                .defineInRange("maxRetries", 3, 0, 10);

        SAVED_DATA_MAX_FILE_SIZE_MB = BUILDER
                .comment("v0.7: SavedData whose UNCOMPRESSED serialized size exceeds this threshold are written",
                         "synchronously (vanilla streaming path) instead of dispatched to the worker queue.",
                         "The threshold bounds the main-thread memory footprint (the uncompressed byte[] that",
                         "serialize-once allocates), NOT the on-disk .dat size: it is compared against the last",
                         "written uncompressed length, or - with no history yet - the on-disk gzip size scaled up",
                         "by a conservative ratio to estimate the uncompressed footprint. So a compressible file",
                         "far under this in MB on disk can still be routed sync when its in-memory form is large.",
                         "Prevents a single oversized SavedData from spiking main-thread allocation / blocking the",
                         "savedData worker queue for many seconds.",
                         "Default 50 MB covers typical mod-registered SavedData; raise if you have legitimate",
                         "files larger than this and confirmed your worker IO can handle them.")
                .defineInRange("savedDataMaxFileSizeMB", 50, 1, 1024);

        BUILDER.pop();

        BUILDER.comment("Event compatibility").push("compat");

        EVENT_COMPAT_MODE = BUILDER
                .comment("ChunkDataEvent.Save dispatch mode (v0.2).",
                         "PARTIAL (default, recommended): main thread fires the event with a core tag that excludes sections.",
                         "  Most mods only attach sub-tags or read non-section fields and are unaffected.",
                         "  Listeners that call tag.get(\"sections\") will see null - flip to FULL if you have such a listener.",
                         "  Worker thread assembles sections after the event fires; perf gain is highest in this mode.",
                         "FULL: main thread builds the complete tag (sections included) and fires the event with full data.",
                         "  100% vanilla-equivalent semantics. Worker only does IO. Perf gain reduced (sections encoded on main thread).",
                         "DISABLED: skip the event entirely. Worker assembles sections (same path as PARTIAL).",
                         "  Use only when you are certain no listener mod relies on ChunkDataEvent.Save.",
                         "  Saves the per-chunk event dispatch overhead but breaks any mod that hooks Save.",
                         "COMPAT WARNING: PARTIAL/DISABLED assemble sections without calling ChunkSerializer.write, so a mod",
                         "  that injects extra chunk NBT by mixing into ChunkSerializer.write directly (instead of via",
                         "  ChunkDataEvent.Save or Forge capabilities, both of which PARTIAL still honors) has its serialization",
                         "  bypassed and that data silently dropped every save, with no error. Flip to FULL if you run such a mod",
                         "  (FULL invokes the real write()).")
                .defineEnum("eventCompatMode", EventCompatMode.PARTIAL);

        BUILDER.pop();

        BUILDER.comment("level.dat write optimization (issue #25)").push("levelData");

        LEVEL_DATA_CACHE_REGISTRY_SNAPSHOT = BUILDER
                .comment("Reuse a cached copy of the Forge registry ID table that gets written into level.dat,",
                         "instead of rebuilding it from scratch on every autosave.",
                         "",
                         "WHAT THIS FIXES: vanilla rewrites level.dat unconditionally every autosave (default every",
                         "5 minutes). On a heavily modded server almost all of that file is the Forge registry ID table.",
                         "Measured on a 137-mod 1.20.1 server: level.dat is 1,234,370 bytes uncompressed, of which",
                         "fml/Registries is 1,215,091 bytes (98.4%, 17 registries / 26,648 ids). Rebuilding it costs",
                         "about 25ms of main-thread time per autosave, which shows up as a periodic MSPT spike.",
                         "Diffing two consecutive level.dat files 5 minutes apart showed 5 differing bytes out of",
                         "1,234,370 - all in /Data; the entire /fml block was byte-identical.",
                         "",
                         "WHY IT IS SAFE: on a dedicated server the ACTIVE registry set is fixed once the server starts",
                         "ticking (registry writes are rejected while locked, and datapack /reload uses a different",
                         "container). The cache is additionally invalidated by three independent layers: Forge's",
                         "IdMappingEvent, a per-write fingerprint of every persisted registry (entry count + locked",
                         "state), and the periodic full recompute below.",
                         "",
                         "Default false: opt-in. The gain is a periodic ~25ms main-thread spike, not a throughput",
                         "problem, so this ships off until it has been proven on real modpacks. Turn it on together",
                         "with a non-zero revalidate interval, watch the log for MISMATCH, then leave it on.",
                         "Hot-reloadable: no restart needed, and turning it off drops the cache immediately.",
                         "",
                         "COMPAT WARNING: when the cache hits, ForgeHooks.writeAdditionalLevelSaveData is skipped",
                         "entirely, so any other mod injecting into that method is skipped too. No such mod is known",
                         "(the method is @ApiStatus.Internal with a single caller), and the cached content already",
                         "includes whatever such a mod contributed on the build pass, so a constant contribution is",
                         "equivalent. A time-varying one would be reported by the revalidation below as a MISMATCH.",
                         "",
                         "NeoForge note: this setting does not exist on the NeoForge build - NeoForge removed the",
                         "registry ID table from level.dat entirely, so there is nothing to cache there.")
                .define("cacheRegistrySnapshot", false);

        LEVEL_DATA_REGISTRY_CACHE_REVALIDATE_CYCLES = BUILDER
                .comment("How many cache hits to serve before forcing one full rebuild and comparing it against the",
                         "cached copy, tag by tag. A mismatch logs an ERROR and the freshly computed value is used.",
                         "",
                         "This costs exactly as much as not caching for that one autosave, and buys the only real",
                         "proof that the cached table is still correct on YOUR modpack: the other two invalidation",
                         "layers can only catch registry changes that go through Forge's own events or that change a",
                         "registry's entry count, and cannot see a mod that swaps IDs in place after calling the",
                         "public ForgeRegistry.unfreeze().",
                         "",
                         "Default 12: with the vanilla 5-minute autosave that is one verified rebuild per hour,",
                         "so 11 of every 12 autosaves are cheap. Set 1 to verify every single write (no speedup,",
                         "pure audit mode - useful for a few days when first enabling on a new modpack).",
                         "Set 0 to never revalidate: only do that once you have run for a long time with no MISMATCH.")
                .defineInRange("registryCacheRevalidateCycles", 12, 0, 1000);

        LEVEL_DATA_VERIFY_ON_STARTUP = BUILDER
                .comment("Check level.dat for readability and structural completeness at startup, and repair it from",
                         "level.dat_old when it is broken.",
                         "",
                         "WHAT THIS FIXES: vanilla's fallback to level.dat_old only fires when the reader returns",
                         "null. That happens when level.dat is MISSING - but the reader a dedicated server actually",
                         "uses starts with catch (IOException) { throw new UncheckedIOException }, so when the file",
                         "EXISTS BUT IS UNREADABLE the fallback is structurally unreachable. The exception surfaces",
                         "as 'Failed to load datapacks, can't proceed with server load ... --safeMode' and the process",
                         "exits with a normal status code, pointing the operator at datapacks instead of at level.dat.",
                         "",
                         "There is a quieter failure too. If the file is valid gzip and valid NBT but Data is missing,",
                         "or just the single DataVersion int inside it is gone, then getDataVersion falls back to -1,",
                         "which makes the world-generation datafixer discard and rebuild the whole dimension table.",
                         "Nothing downstream rejects that, so the server STARTS SUCCESSFULLY with seed 0, default",
                         "spawn, default time, default weather, default gamerules and default world border, while the",
                         "region files still hold the old terrain. Startup then rewrites that default data back out",
                         "and rotates the damaged file into level.dat_old - destroying the last good copy in the same",
                         "boot, with no error anywhere.",
                         "",
                         "When the check fails, the damaged file is moved aside as level.dat_corrupted_<timestamp>",
                         "(the same naming 1.21 uses) and level.dat_old is put back in its place, so the world loses",
                         "at most one save cycle. If level.dat_old is unusable too, nothing is touched and the",
                         "available BAS backups are printed with the exact command to restore one by hand.",
                         "",
                         "Default true: this only does anything on a path where the vanilla outcome is a failed start",
                         "with a misleading message, or a silently reset world. Costs one read of level.dat at boot.")
                .define("verifyOnStartup", true);

        LEVEL_DATA_STARTUP_BACKUP = BUILDER
                .comment("Keep known-good copies of level.dat under <world>/betterautosave/leveldat/, made at startup",
                         "right after the file has been verified.",
                         "",
                         "vanilla keeps exactly one spare copy, level.dat_old, and rotates it on every single write -",
                         "so one boot with a damaged file is enough to consume it. These copies are taken only when",
                         "the file passed verification, are raw byte copies (not a re-serialization, which would",
                         "change the bytes and throw away the evidence of a truncated file), and 3 generations are",
                         "kept, so a fault has to survive three restarts before every fallback is gone.",
                         "",
                         "IMPORTANT: these copies are never restored automatically, and vanilla will never read them -",
                         "it only knows the names level.dat and level.dat_old. Rolling back to a startup copy would",
                         "rewind world time, weather, gamerules, world border, the dragon fight and scheduled events,",
                         "and if the mod set changed in between it would also remap block IDs across the whole world.",
                         "That is an operator decision, so BAS prints the available copies and the exact command and",
                         "stops there. Automatic repair only ever uses level.dat_old.",
                         "",
                         "Default true. Each copy is the size of level.dat (a few hundred KB on a large modpack).")
                .define("startupBackup", true);

        LEVEL_DATA_POST_WRITE_VERIFY = BUILDER
                .comment("Read level.dat back on a worker thread right after it is written and check it.",
                         "",
                         "This answers a different question from verifyOnStartup. That one asks 'is it broken?' at",
                         "the next boot - by which point vanilla may already have rotated the damaged file into",
                         "level.dat_old and consumed the last good copy. This one notices at the moment the damage",
                         "appears, while level.dat_old is still intact and the recovery window is at its widest.",
                         "",
                         "The check runs on the SavedData worker queue, so the main thread only pays for submitting",
                         "the task. It is read-only and never rolls anything back: the server is live at that point,",
                         "and overwriting the world metadata that is currently in use would cause more trouble than",
                         "the original fault. It logs loudly instead, and repair happens at the next start.",
                         "",
                         "OFF: no check.",
                         "CHECKSUM (default): decompress the file end to end, which catches truncation and stream",
                         "  corruption. Roughly one sequential read of the file.",
                         "FULL: also parse the NBT and apply the same structural checks as verifyOnStartup, which",
                         "  additionally catches the 'valid NBT but missing DataVersion' case. Costs more, still",
                         "  entirely off the main thread.",
                         "",
                         "Note BAS does not take over writing level.dat - asynchronous writing of it was evaluated",
                         "and rejected - so what is being verified here is vanilla's own write. That is still worth",
                         "doing: disk faults, filesystem problems and other mods can all damage it.")
                .defineEnum("postWriteVerify",
                        com.shinoyuki.betterautosave.core.leveldat.LevelDataIntegrity.VerifyStrength.CHECKSUM);

        BUILDER.pop();

        BUILDER.comment("Player data (playerdata/, stats/ and advancements/)").push("playerData");

        PLAYER_DATA_LOAD_FALLBACK = BUILDER
                .comment("On a failed read of playerdata/<uuid>.dat, quarantine it and try <uuid>.dat_old before",
                         "treating the player as brand new.",
                         "",
                         "WHAT THIS FIXES: PlayerDataStorage.save writes <uuid>.dat through Util.safeReplaceFile,",
                         "which first renames the live file to <uuid>.dat_old and only then renames the new temp file",
                         "into place. PlayerDataStorage.load reads ONLY <uuid>.dat: if it is missing or unreadable,",
                         "vanilla logs a single WARN with no stack trace, returns null, and never calls player.load -",
                         "so the player comes online as a brand new player. Empty inventory, empty ender chest, spawn",
                         "position, zero experience, default game mode, and every mod's data that rides on ForgeCaps,",
                         "all gone. The intact <uuid>.dat_old that vanilla itself wrote moments earlier is never",
                         "consulted, and the next autosave overwrites it with the blank player.",
                         "A crash or power loss in the window between those two renames therefore wipes that player",
                         "silently, with a complete backup sitting right next to the missing file. The operator has",
                         "about one autosave interval to notice before the backup is recycled.",
                         "",
                         "This is fixed upstream in 1.21: the unreadable file is copied aside as",
                         "<uuid>_corrupted_<timestamp>.dat and <uuid>.dat_old is tried next. This setting backports",
                         "that behavior to 1.20.1, quarantine copy included, so the unreadable original survives for",
                         "inspection instead of being recycled.",
                         "",
                         "Default true: this only runs on a path whose vanilla outcome is guaranteed data loss, so",
                         "there is no scenario in which the vanilla behavior is preferable. Hot-reloadable.",
                         "",
                         "COMPAT WARNING: a utility that blanks a player by deleting <uuid>.dat will now find the",
                         "player restored from <uuid>.dat_old on next login. Such a tool must delete both files.")
                .define("loadFallback", true);

        PLAYER_DATA_ATOMIC_SIDECAR_WRITE = BUILDER
                .comment("Write stats/<uuid>.json and advancements/<uuid>.json atomically (temp file + fsync +",
                         "rename, keeping one .bak) instead of truncating the live file in place.",
                         "",
                         "WHAT THIS FIXES: of the three files PlayerList.save writes per player, only",
                         "playerdata/<uuid>.dat gets vanilla's temp-file-plus-rename treatment and a .dat_old backup.",
                         "ServerStatsCounter.save calls FileUtils.writeStringToFile and PlayerAdvancements.save opens",
                         "the target with newBufferedWriter (CREATE + TRUNCATE_EXISTING): both truncate the only copy",
                         "and then stream into it. Confirm this on any world folder - playerdata/ is full of .dat_old",
                         "files and stats/ and advancements/ have no backups at all.",
                         "A crash partway through leaves a truncated JSON file. On the next login the parse fails,",
                         "the exception is caught and logged at ERROR, and loading CONTINUES with an empty progress",
                         "map - so the player joins with no advancements, and the next autosave writes that empty map",
                         "back over the file. The advancements file is up to a few hundred KB on a large modpack, so",
                         "the write window is not small. The same shape applies to stats.",
                         "",
                         "The bytes that land on disk are identical, they just land all at once. The added cost on",
                         "the main thread is two renames per file - the durability barrier that would actually be",
                         "expensive there is sidecarFsync below, and that one is off by default.",
                         "",
                         "Default true. Hot-reloadable.")
                .define("atomicSidecarWrite", true);

        PLAYER_DATA_SIDECAR_FSYNC = BUILDER
                .comment("With atomicSidecarWrite on, also fsync the temp file before renaming it into place.",
                         "",
                         "WHAT IT BUYS: temp-file-plus-rename alone already removes the truncation window, which is",
                         "the failure this feature exists to fix. fsync covers a narrower one - a host power loss or",
                         "kernel panic in the seconds between the rename and the kernel writing the data back, where",
                         "the rename is durable but the data is not, leaving a correctly sized file full of zeroes.",
                         "Note that ext4 in its default data=ordered mode already flushes a newly written file before",
                         "committing a rename over an existing one, so on the most common Linux setup this switch is",
                         "close to redundant.",
                         "",
                         "WHY IT IS OFF BY DEFAULT: PlayerList.save runs on the server thread, once per online player",
                         "per autosave, and writes both sidecar files each time. Turning this on puts two synchronous",
                         "device flushes per player on the main thread - 120 of them in a single tick at 60 players,",
                         "each one waiting on a filesystem journal commit that BAS's own worker writes are competing",
                         "for. Vanilla does no fsync anywhere on this path, not even for playerdata/<uuid>.dat, so",
                         "leaving this on would be a main-thread regression against vanilla in the exact place this",
                         "release is trying to make cheaper.",
                         "",
                         "Turn it on if the host has no battery-backed write cache and unclean power loss is a real",
                         "risk, and pair it with staggerMaxPerTick so the flushes are spread across ticks.",
                         "",
                         "Default false. Hot-reloadable.")
                .define("sidecarFsync", false);

        PLAYER_DATA_ADVANCEMENTS_SKIP_MODE = BUILDER
                .comment("Skip rewriting advancements/<uuid>.json when the player's progress has not changed since",
                         "the last successful write.",
                         "",
                         "WHY THIS IS THE BIGGEST WIN HERE: PlayerAdvancements.save has no dirty check at all. Every",
                         "autosave it walks every loaded advancement, filters by hasProgress(), rebuilds the whole",
                         "JSON tree through Gson, pretty-prints it and writes the entire file - whether or not",
                         "anything changed. Measured on a 137-mod server it is 55% of the entire PlayerList.saveAll",
                         "frame, roughly twice the cost of writing the player's actual NBT.",
                         "And on that server it changes almost never: sampling the live world folder across three",
                         "consecutive autosaves, the advancements files of the online players were byte-for-byte",
                         "identical every time (mtime advancing, md5 constant), while their playerdata and stats",
                         "files changed on every single pass. Of 18,883 criterion timestamps across all 31 players,",
                         "89% were older than 30 days and only 36 (0.19%) were from the last 24 hours - the bulk of",
                         "the content is minecraft:recipes/* entries that unlock once and never change again.",
                         "Skipping is byte-equivalent on disk, so backup mods see exactly the same data.",
                         "",
                         "OFF (default): vanilla behavior, rewrite every time.",
                         "AUDIT: still writes every time, but also computes what the dirty flag WOULD have decided and",
                         "  compares it against the actual content. A disagreement logs an ERROR naming the player.",
                         "  No speedup - this exists so you can prove the flag is correct on YOUR modpack before",
                         "  trusting it. This is the same roll-out shape used for levelData.cacheRegistrySnapshot.",
                         "ON: actually skip the write when the flag says clean.",
                         "",
                         "Recommended sequence: set AUDIT, run for a few days, confirm no MISMATCH appears in the",
                         "log, then set ON. Hot-reloadable.",
                         "",
                         "COMPAT WARNING: the dirty flag is set by award(), revoke(), load() and reload(). A mod that",
                         "reaches past those and mutates progress directly (for instance by holding the object from",
                         "getOrStartProgress and calling grantProgress on it) will not set the flag. The forced full",
                         "write below bounds how long such a change can go unwritten; AUDIT reports it as a MISMATCH.")
                .defineEnum("advancementsSkipMode", AdvancementsSkipMode.OFF);

        PLAYER_DATA_ADVANCEMENTS_FORCE_FULL_WRITE_CYCLES = BUILDER
                .comment("With advancementsSkipMode not OFF, force one full write after this many consecutive skips,",
                         "even when the dirty flag says nothing changed.",
                         "",
                         "Two things this buys back. First, it bounds the damage from a mod that mutates progress",
                         "without going through award()/revoke() - such a change lands on disk within this many",
                         "autosaves instead of never. Second, it restores vanilla's accidental self-healing property:",
                         "because vanilla rewrites the file unconditionally, an externally corrupted or hand-edited",
                         "advancements file gets overwritten with the in-memory truth on the next autosave. Skipping",
                         "gives that up, and this setting hands it back on a schedule.",
                         "",
                         "Default 12: with the vanilla 5-minute autosave that is one guaranteed write per hour per",
                         "player, so 11 of every 12 are free. Set 0 to never force a write (only do that once AUDIT",
                         "has run clean for a long time).")
                .defineInRange("advancementsForceFullWriteCycles", 12, 0, 1000);

        PLAYER_DATA_STAGGER_MAX_PER_TICK = BUILDER
                .comment("Spread the autosave's player writes across ticks instead of doing all of them in one.",
                         "0 (default) keeps vanilla behavior: everyone is written in the single autosave tick.",
                         "",
                         "PlayerList.saveAll is a bare loop that writes every online player during the one tick where",
                         "tickCount % autosavePeriod == 0. Measured at roughly 6.7ms per player on a 137-mod server,",
                         "which is about 27ms at 4 players and extrapolates to some 400ms at 60 - a periodic spike",
                         "rather than a throughput problem.",
                         "",
                         "WHY THIS IS NOT A DATA-SAFETY TRADE: every player is still written exactly once per",
                         "autosave period, so the worst-case staleness window is completely unchanged. Only the",
                         "moment each player is written moves. No threads, no change to write ordering, no effect on",
                         "/save-off, shutdown, or the disconnect path - all of which bypass staggering and flush any",
                         "queued players first. Paper has shipped the same idea since 2019 and Sponge has its own.",
                         "",
                         "Suggested values: 1 or 2. Do not copy Paper's default here - its maxPerTick() actually",
                         "returns 20 under the stock rate=-1 config, and 20 players per tick on a heavy modpack is",
                         "already over 100ms, which defeats the purpose. With 1 per tick, 60 players drain in 3",
                         "seconds, far inside the 5-minute period.",
                         "",
                         "COMPAT WARNING: while draining, MinecraftServer.isSaving is false. A mod that relies on",
                         "that flag to detect 'a save is in progress' will not see the staggered writes.")
                .defineInRange("staggerMaxPerTick", 0, 0, 64);

        BUILDER.pop();

        BUILDER.comment("v0.x: Async chunk load (off-thread ChunkSerializer.read)").push("load");

        LOAD_ENABLED = BUILDER
                .comment("Master switch for async chunk loading. When false, ChunkSerializer.read stays entirely on",
                         "the main thread (vanilla behavior), independent of the global 'enabled' save switch.",
                         "Default false: opt-in until the off-thread load path is proven on your modpack.",
                         "TAKES EFFECT AT STARTUP: the async-load mixins are applied (or skipped) based on this value at",
                         "load time (a MixinConfigPlugin reads it from disk), so the load-side bytecode is absent entirely",
                         "when off - this keeps it from clashing at startup with mods that rewrite scheduleChunkLoad /",
                         "ChunkSerializer.read (e.g. C2ME-forge). Because of that, toggling this on/off requires a server",
                         "restart to change mixin application; a runtime config hot-reload alone will not enable it.",
                         "COMPAT CONTRACT: when on, ChunkSerializer.read runs on a load worker. BAS defers all KNOWN",
                         "vanilla/Forge main-thread side effects (POI, light, ForgeCaps, ChunkDataEvent.Load) back to the",
                         "main thread, but any OTHER mod that injects into ChunkSerializer.read (or the LevelChunk ctor / the",
                         "Codecs it uses) runs on that worker. A third-party injection that THROWS self-heals (the worker",
                         "throws, the terminal fallback re-reads on the main thread, no data lost); one that does NOT throw",
                         "but assumes the main thread (writes a non-concurrent collection, fires a main-thread-only event) can",
                         "silently race/corrupt and the fallback will not fire. Before enabling, confirm no mod injects",
                         "main-thread state into ChunkSerializer.read, or keep this off / use loadEventCompatMode=FULL.")
                .define("enabled", false);

        LOAD_EVENT_COMPAT_MODE = BUILDER
                .comment("Async chunk-load split mode (v0.x).",
                         "PARTIAL (default): pure NBT->object deserialization runs on a load worker; POI consistency,",
                         "  light section data, and ChunkDataEvent.Load are deferred back to the main thread.",
                         "FULL: the entire ChunkSerializer.read stays on the main thread (feature off for this dimension's",
                         "  loads, zero behavior delta). Use as a per-config safety fallback if a mod misbehaves.",
                         "There is intentionally no DISABLED: ChunkDataEvent.Load must always fire on the main thread,",
                         "  so the weakest mode is FULL (everything on main), never 'skip the event'.")
                .defineEnum("loadEventCompatMode", LoadCompatMode.PARTIAL);

        LOAD_MAX_RETRIES = BUILDER
                .comment("Times a load worker re-attempts ChunkSerializer.read on the worker thread after a parse",
                         "throws, before giving up and falling back to a vanilla main-thread read.",
                         "Off-thread parse failures are almost always transient (a Codec dispatch cache races a",
                         "concurrent decode despite the guard, or a DataFixer cache hiccup); a single retry usually",
                         "clears it without paying the main-thread fallback cost. 0 disables retry (fall back on first throw).",
                         "The terminal fallback re-reads the same region bytes on the main thread, so no data is lost",
                         "regardless of this value.")
                .defineInRange("loadMaxRetries", 1, 0, 5);

        LOAD_MAX_IN_FLIGHT = BUILDER
                .comment("v2.1: max chunk-load deserializations submitted to load workers concurrently. Caps how many",
                         "off-thread loads finish around the same time, so their main-thread POI/light replay + chunk",
                         "install can't all land in one tick (the burst that shows as 'Can't keep up' under fast flight +",
                         "high view-distance once parallel decoding feeds completions faster than the main thread installs).",
                         "Excess loads queue (workers stay fed) instead of flooding; the main thread drains replays over",
                         "ticks, trading a freeze for smooth per-chunk latency. Higher = more throughput but bigger per-tick",
                         "burst; lower = smoother but slower chunk arrival. Tune per server: raise until a burst reappears.")
                .defineInRange("maxInFlight", 128, 2, 1024);

        LOAD_POI_PREFETCH = BUILDER
                .comment("Tier A: read each loading chunk's POI region off-thread on the load worker, so the deferred",
                         "PoiManager.checkConsistencyWithBlocks no longer blocks the main thread on a synchronous POI",
                         "disk read during replay. The worker reads the POI bytes (IOWorker only, thread-safe) and hands",
                         "them to the main thread, which parses + populates the POI cache before replay; the consistency",
                         "check then hits the cache instead of reading disk. Profiled main-thread savings are large under",
                         "fast flight (the POI getOrLoad join wait is the dominant main-thread chunk-load cost).",
                         "Only takes effect when async load is on and mode is PARTIAL. Default true: set false to fall back",
                         "to vanilla main-thread POI reads if a POI-storage mod misbehaves. Hot-reloadable.")
                .define("asyncPoiPrefetch", true);

        BUILDER.pop();

        BUILDER.comment("Diagnostics").push("diagnostics");

        DIAGNOSTIC_LOGGING = BUILDER
                .comment("Periodically log queue depth, throughput, and latency percentiles to the server log.",
                         "For continuous monitoring prefer the Prometheus exporter below; set false to silence entirely.")
                .define("diagnosticLogging", true);

        DIAGNOSTIC_LOG_INTERVAL_TICKS = BUILDER
                .comment("How often diagnostic summaries are emitted, in server ticks (20 ticks = 1s).",
                         "Default 6000 (5 min). The dev-era default was 200 (10s) - far too chatty for production.")
                .defineInRange("diagnosticLogIntervalTicks", 6000, 20, 72000);

        SYNC_LOAD_DETECTION = BUILDER
                .comment("Detect and report main-thread synchronous chunk loads (a third-party mod calling",
                         "ServerChunkCache.getChunk for a chunk that is not resident stalls the server thread until",
                         "disk IO and generation finish).",
                         "Default true: the instrumented call site is only reached on a 4-slot cache miss, and a stack",
                         "is captured only when a single load exceeds syncLoadThresholdMs.",
                         "Detected stalls are usually caused by another mod's call pattern and do not by themselves",
                         "indicate a defect in that mod.",
                         "Hot-reloadable.")
                .define("syncLoadDetection", true);

        SYNC_LOAD_THRESHOLD_MS = BUILDER
                .comment("A single main-thread chunk load blocking at least this long (milliseconds) is recorded with",
                         "its call stack.",
                         "Default 50: one server tick is 50ms, so anything at or above this cost a full tick or more.",
                         "Lower values capture more events and more stacks; raise it on servers that legitimately",
                         "stream chunks from cold storage.",
                         "Hot-reloadable.")
                .defineInRange("syncLoadThresholdMs", 50, 1, 60_000);

        SYNC_LOAD_TRACK_LIMIT = BUILDER
                .comment("LRU eviction limit: max number of distinct (attribution, stack) pairs tracked simultaneously.",
                         "Default 64: distinct sync-load call sites are few; the limit exists to bound memory, not to sample.",
                         "TAKES EFFECT AT STARTUP: the tracker is constructed once when the server starts.")
                .defineInRange("syncLoadTrackLimit", 64, 8, 4096);

        SYNC_LOAD_STACK_DEPTH = BUILDER
                .comment("How many non-vanilla stack frames are retained per captured stall.",
                         "Frames in net.minecraft, java, jdk, sun, com.mojang, com.llamalad7, org.spongepowered and",
                         "BetterAutoSave's own diagnostic and mixin packages are skipped; the first remaining frame is",
                         "used as the attribution key.",
                         "Default 24: deep enough to cross an event-dispatch chain, shallow enough to keep log lines readable.",
                         "Hot-reloadable.")
                .defineInRange("syncLoadStackDepth", 24, 4, 128);

        TICK_GAP_DETECTION = BUILDER
                .comment("Detect long pauses that happen between server ticks (inside waitUntilNextTick), which are",
                         "invisible to MSPT and to most monitoring dashboards.",
                         "Default true: the cost is two System.nanoTime() calls per tick.",
                         "Hot-reloadable.")
                .define("tickGapDetection", true);

        TICK_GAP_THRESHOLD_MS = BUILDER
                .comment("An inter-tick gap of at least this long (milliseconds) is recorded.",
                         "Default 1000: a healthy server spends at most ~50ms between ticks, so one second already means",
                         "a whole second of wall clock unaccounted for by MSPT.",
                         "When tickGapDeepAttribution is on, one tenth of this value is also used as the per-task",
                         "threshold of the deep-attribution table.",
                         "Hot-reloadable.")
                .defineInRange("tickGapThresholdMs", 1000, 50, 600_000);

        TICK_GAP_DEEP_ATTRIBUTION = BUILDER
                .comment("Additionally time every individual task run by the server task queue, so a long inter-tick gap",
                         "can be attributed to the task that caused it.",
                         "Default false: this instruments MinecraftServer.doRunTask, which executes hundreds of times per",
                         "tick; enabling it adds two System.nanoTime() calls per task (a few microseconds per tick).",
                         "Turn it on only while investigating a gap already reported by tickGapDetection, then turn it",
                         "back off.",
                         "Hot-reloadable.")
                .define("tickGapDeepAttribution", false);

        TICK_GAP_DEEP_TRACK_LIMIT = BUILDER
                .comment("LRU eviction limit for the deep-attribution table (one entry per distinct task type).",
                         "Ignored unless tickGapDeepAttribution is true.",
                         "TAKES EFFECT AT STARTUP.")
                .defineInRange("tickGapDeepTrackLimit", 64, 8, 4096);

        BUILDER.pop();

        BUILDER.comment("v0.9: Prometheus metrics HTTP exporter").push("prometheus");

        PROMETHEUS_ENABLED = BUILDER
                .comment("Enable Prometheus metrics HTTP exporter.",
                         "Default false: opt-in. Toggle on if you want to scrape BAS metrics from Grafana / Prometheus.",
                         "When enabled, the server starts an HTTP listener at bindAddress:port serving GET /metrics",
                         "in Prometheus exposition format (text/plain).")
                .define("enabled", false);

        PROMETHEUS_BIND_ADDRESS = BUILDER
                .comment("HTTP server bind address.",
                         "Default 0.0.0.0: accept connections from any network interface (open by default for ease of setup).",
                         "Security note: BAS metrics expose chunk save counters / queue depth / latency histograms.",
                         "These are not directly sensitive but reveal world activity patterns.",
                         "If your server has a public IP, restrict access via firewall (iptables / cloud Security Group)",
                         "or set this to 127.0.0.1 so only local Prometheus can scrape.")
                .define("bindAddress", "0.0.0.0");

        PROMETHEUS_PORT = BUILDER
                .comment("HTTP server port. Default 9450 (avoids 9090 Prometheus / 9100 node_exporter / 25565 MC).",
                         "Pick any free port; Prometheus scrape config must point to this port.")
                .defineInRange("port", 9450, 1024, 65535);

        BUILDER.pop();

        BUILDER.comment("v0.9: hottest-chunks command (per-chunk latency tracking)").push("hottestChunks");

        HOTTEST_CHUNKS_WINDOW_SIZE = BUILDER
                .comment("Sliding window size: per-chunk latency samples retained for p99 calculation.",
                         "Larger window = more stable percentile, more memory. 100 samples per chunk = ~1.6 KB.")
                .defineInRange("windowSize", 100, 10, 1000);

        HOTTEST_CHUNKS_TRACK_LIMIT = BUILDER
                .comment("LRU eviction limit: max number of chunks tracked simultaneously.",
                         "When the limit is hit, the least-recently-saved chunk is evicted.",
                         "10000 covers loaded chunks for typical 60-100 player servers (~ a few MB total memory).",
                         "Raise if you run very large worlds with many active chunks.")
                .defineInRange("trackLimit", 10_000, 100, 1_000_000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private ConfigSpec() {
    }
}
