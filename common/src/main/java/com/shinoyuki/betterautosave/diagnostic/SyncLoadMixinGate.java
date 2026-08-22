package com.shinoyuki.betterautosave.diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * v0.20.1: {@code ServerChunkCacheSyncLoadMixin} 的应用期门控。
 *
 * <p><b>为什么 {@code require = 0} 不够</b>: 该 mixin 用 {@code @WrapOperation} 包
 * {@code ServerChunkCache.getChunk} 内的 {@code managedBlock} INVOKE, 并以 {@code require = 0} 声明
 * "注入点消失时静默跳过"。但 Mixin 0.8.5 在 {@code Injector.findTargetNodes} 里还有一条更早的判定:
 * 注入器的优先级<b>不高于</b>已经 merge (即 {@code @Overwrite}) 该目标方法的 mixin 时, 直接抛
 * {@code InvalidInjectionException}, 而这条判定发生在 require 的命中数检查之前 —— require 根本没被问到。
 * 结果是 {@code MixinTransformerError} 冒到 {@code MinecraftServer}, 启动崩服。
 *
 * <p>实测异常原文 (Harium 2.0 + BAS 0.20.0, 1.20.1):
 * <pre>
 * @At("INVOKE") on ServerChunkCache::betterautosave$measureSyncChunkLoad with priority 1000
 * cannot inject into ServerChunkCache::m_7587_(IILChunkStatus;Z)LChunkAccess;
 * merged by me.jellysquid.mods.lithium.mixin.world.chunk_access.ServerChunkManagerMixin with priority 1000
 * </pre>
 *
 * <p>提高本 mixin 的 priority 能绕过这条判定, 但那样 BAS 会先于对方应用, 对方的 {@code @Overwrite} 随后
 * 整段覆盖掉本注入 —— 崩溃风险转移给对方且本功能照样失效, 没有实际收益。因此改为"检测到会 merge
 * {@code getChunk} 的 mod 在场就整条跳过", 把失败模式拉回该 mixin 文档声明的原意: 检测不生效, 而不是崩服。
 */
public final class SyncLoadMixinGate {

    /** 受本门控约束的 mixin 简单类名。双平台同名, 故放 common。 */
    public static final String SYNC_LOAD_MIXIN_SIMPLE_NAME = "ServerChunkCacheSyncLoadMixin";

    /**
     * 已知会 {@code @Overwrite} {@code ServerChunkCache.getChunk} 的 mod id。
     *
     * <p>{@code harium} 是本地复现实证 (Harium-mc1.20.1-2.0-cumulative-hotfix-v2 + BAS 0.20.0 启动崩服)。
     * 其余三个是同源推断而非逐个实证: 崩溃栈里的 merger 是
     * {@code me.jellysquid.mods.lithium.mixin.world.chunk_access.ServerChunkManagerMixin} —— Lithium 的原始包名,
     * 而 Radium / Radium Re-Reforged (modId 同为 radium) / Canary 与 Harium 都是同一份 Lithium 代码的移植或 fork,
     * 该 mixin 在四者中同名同位且默认开启, 故一并列入。宁可多跳过一个诊断功能, 不可少防一次崩服。
     *
     * <p>未列入者仍有 {@link #readSyncLoadDetection} 这条逃生通道: 把 {@code syncLoadDetection} 置 false 并重启,
     * 本 mixin 同样不再应用。
     */
    private static final Set<String> GET_CHUNK_MERGERS = Set.of(
            "harium",
            "radium",
            "canary",
            "lithium");

    private SyncLoadMixinGate() {
    }

    /** 已知会 merge {@code getChunk} 的 mod id, 不可变。平台侧若只能逐个查在场性时用它遍历。 */
    public static Set<String> conflictingModIds() {
        return GET_CHUNK_MERGERS;
    }

    /** mixinClassName (FQCN 或简单名) 是否是受本门控约束的那一个。 */
    public static boolean isSyncLoadMixin(String mixinClassName) {
        if (mixinClassName == null) {
            return false;
        }
        int dot = mixinClassName.lastIndexOf('.');
        String simple = dot >= 0 ? mixinClassName.substring(dot + 1) : mixinClassName;
        return SYNC_LOAD_MIXIN_SIMPLE_NAME.equals(simple);
    }

    /**
     * 在场 mod 里第一个会 merge {@code getChunk} 的 mod id; 没有则返回 null。
     * 返回 id 而不是 boolean, 是为了让平台侧能把"因为谁而跳过"打进启动日志 —— 静默跳过会让
     * "诊断功能怎么不工作了"变成无从查起的问题。
     */
    public static String firstConflictingModId(Collection<String> presentModIds) {
        if (presentModIds == null) {
            return null;
        }
        for (String id : presentModIds) {
            if (id != null && GET_CHUNK_MERGERS.contains(id.toLowerCase(java.util.Locale.ROOT))) {
                return id;
            }
        }
        return null;
    }

    /**
     * 从 common.toml 的行读 {@code [diagnostics].syncLoadDetection}。section-aware, 只认 diagnostics 段下的键。
     *
     * <p>缺文件 / 缺段 / 缺键一律返回 true: 该开关的配置默认值就是 true, 首次启动尚未生成配置时必须保持
     * 功能开启, 不能与 {@code load.enabled} 那条 opt-in 门控混为一谈 (后者缺省是 false)。
     */
    public static boolean parseSyncLoadDetection(List<String> tomlLines) {
        if (tomlLines == null) {
            return true;
        }
        String section = "";
        for (String raw : tomlLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (section.equals("diagnostics")) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals("syncLoadDetection")) {
                    return Boolean.parseBoolean(line.substring(eq + 1).trim());
                }
            }
        }
        return true;
    }

    /** 读磁盘 common.toml 判 syncLoadDetection; 文件不存在 / 读失败均按默认值 true。 */
    public static boolean readSyncLoadDetection(Path tomlPath) {
        if (tomlPath == null || !Files.isRegularFile(tomlPath)) {
            return true;
        }
        try {
            return parseSyncLoadDetection(Files.readAllLines(tomlPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return true;
        }
    }
}
