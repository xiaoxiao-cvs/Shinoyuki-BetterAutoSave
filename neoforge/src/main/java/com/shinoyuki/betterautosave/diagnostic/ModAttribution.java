package com.shinoyuki.betterautosave.diagnostic;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * v0.20: 把归因栈顶的全限定类名反查成 modid。
 *
 * <p>索引懒构建: 只有第一次真的抓到超阈值栈才展开全部 mod 的 scan data。常态 (没有任何同步加载停顿)
 * 零内存零开销; 一个装了 200+ mod 的整合包首次展开可能有几十万条 ClassData, 这笔一次性成本只在
 * 已经发生了一次 50ms+ 主线程停顿之后才付。
 *
 * <p>刻意不用 {@code Class#getProtectionDomain().getCodeSource()} 做归因: 模块化类加载器下该 URL 的
 * 形态未经核实, 而 ModList 的 scan-data 反查链每一个方法签名都已核实存在。
 */
public final class ModAttribution {

    private static volatile Map<String, String> INDEX;
    private static final LongAdder LOOKUP_FAILURES = new LongAdder();

    /**
     * 命中返回 modid, 未命中返回 null。调用方必须自己兜底成 FQCN —— 索引构建失败 (例如未来 NeoForge
     * 换掉 scan-data API) 时本方法恒返回 null, 归因降级为全限定类名, 主流程不受影响。
     */
    public static String modIdOf(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) {
            return null;
        }
        Map<String, String> index = INDEX;
        if (index == null) {
            index = buildIndex();
        }
        return index.get(fqcn);
    }

    /**
     * 清空索引。由 {@code BetterAutoSaveCore.uninstall()} 调用: 客户端"连远程服 -> 退回单人"会换一批
     * mod 上下文之外, 索引本身也没有跨 server 实例保留的价值, 留着只是白占内存。
     */
    public static void invalidate() {
        INDEX = null;
    }

    /** 索引构建失败累计次数。规范 6 例外条款: 采集路径失败不得影响主流程, 但必须计数而不是静默。 */
    public static long lookupFailures() {
        return LOOKUP_FAILURES.sum();
    }

    /**
     * 清零索引构建失败计数。由 {@code /betterautosave diagnose reset} 调用 —— diagnose 把本计数与
     * {@code SyncLoadStackCapture.failures()} 打在同一行, 只清其中一个会让运维在 reset 之后仍看到非零
     * 数字, 误以为 reset 没有生效。
     */
    public static void resetLookupFailures() {
        LOOKUP_FAILURES.reset();
    }

    private static synchronized Map<String, String> buildIndex() {
        Map<String, String> existing = INDEX;
        if (existing != null) {
            return existing;
        }
        Map<String, String> built = new HashMap<>();
        try {
            for (IModInfo info : ModList.get().getMods()) {
                String modId = info.getModId();
                IModFile file = info.getOwningFile().getFile();
                ModFileScanData scan = file.getScanResult();
                if (scan == null) {
                    continue;
                }
                for (ModFileScanData.ClassData classData : scan.getClasses()) {
                    // 同一 jar 提供多个 modid 时先到先得: 归因只需要"这个类属于哪个 mod 包",
                    // 同 jar 内哪个 modid 胜出对定位没有区别。
                    built.putIfAbsent(classData.clazz().getClassName(), modId);
                }
            }
        } catch (Throwable t) {
            // 归因是诊断的锦上添花, 失败必须降级为只输出 FQCN 而不是把主线程的 getChunk 打断。
            // 保留已经建好的那部分而不是换成空 Map: 异常可能发生在遍历第 N 个 mod 时, 前 N-1 个 mod 的
            // 映射依然有效。发布非 null 的 built 同样满足"不再重试" (避免每次超阈值都重跑失败的全量扫描),
            // 但把绝大部分归因能力保住了。
            LOOKUP_FAILURES.increment();
        }
        INDEX = built;
        return built;
    }

    private ModAttribution() {
    }
}
