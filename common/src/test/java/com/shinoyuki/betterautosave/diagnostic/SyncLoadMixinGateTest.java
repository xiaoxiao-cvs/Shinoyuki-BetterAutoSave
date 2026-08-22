package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SyncLoadMixinGate} 的判定规则。这条门控唯一的作用是阻止一次启动崩服, 因此每条断言都对着
 * 一个具体的崩溃或静默失效场景, 而不是接口形状。
 */
class SyncLoadMixinGateTest {

    @Test
    void only_the_sync_load_mixin_is_gated() {
        assertTrue(SyncLoadMixinGate.isSyncLoadMixin(
                "com.shinoyuki.betterautosave.mixin.ServerChunkCacheSyncLoadMixin"));
        assertTrue(SyncLoadMixinGate.isSyncLoadMixin("ServerChunkCacheSyncLoadMixin"));

        // 存盘侧 mixin 误被门控掉 = 装了 Harium 就丢掉整个异步存盘, 比崩服更糟 (静默退回 vanilla)。
        assertFalse(SyncLoadMixinGate.isSyncLoadMixin(
                "com.shinoyuki.betterautosave.mixin.ChunkMapSaveMixin"));
        assertFalse(SyncLoadMixinGate.isSyncLoadMixin(
                "com.shinoyuki.betterautosave.mixin.ChunkSerializerLoadMixin"));
        assertFalse(SyncLoadMixinGate.isSyncLoadMixin(null));
    }

    @Test
    void lithium_family_mod_ids_are_detected_as_conflicting() {
        // harium 是实证崩溃源; 其余三个与它同源同 mixin, 见 GET_CHUNK_MERGERS 注释。
        for (String id : List.of("harium", "radium", "canary", "lithium")) {
            assertEquals(id, SyncLoadMixinGate.firstConflictingModId(List.of("minecraft", id, "forge")),
                    "should have flagged " + id);
        }
    }

    @Test
    void mod_id_matching_is_case_insensitive() {
        // Forge 的 modId 规范是小写, 但 loader 侧取到的字符串来源不止一处, 大小写不该决定崩不崩。
        assertEquals("Harium", SyncLoadMixinGate.firstConflictingModId(List.of("Harium")));
        assertEquals("RADIUM", SyncLoadMixinGate.firstConflictingModId(List.of("RADIUM")));
    }

    @Test
    void unrelated_modpack_does_not_disable_the_detector() {
        // 误报的代价是白白丢掉 0.20 的核心诊断能力, 所以反向也要钉死。
        assertNull(SyncLoadMixinGate.firstConflictingModId(
                List.of("minecraft", "forge", "starlight", "ferritecore", "modernfix", "create", "tacz")));
        assertNull(SyncLoadMixinGate.firstConflictingModId(List.of()));
        assertNull(SyncLoadMixinGate.firstConflictingModId(null));
    }

    @Test
    void sync_load_detection_defaults_to_true_when_absent() {
        // 与 load.enabled 的 opt-in (缺省 false) 相反: 本开关配置默认值是 true, 首次启动尚无配置文件时
        // 若返回 false, 新装用户会永远拿不到同步加载诊断且毫无提示。
        assertTrue(SyncLoadMixinGate.parseSyncLoadDetection(null));
        assertTrue(SyncLoadMixinGate.parseSyncLoadDetection(List.of()));
        assertTrue(SyncLoadMixinGate.parseSyncLoadDetection(List.of("[diagnostics]", "diagnosticLogging = false")));
        assertTrue(SyncLoadMixinGate.parseSyncLoadDetection(List.of("[general]", "enabled = false")));
    }

    @Test
    void sync_load_detection_false_is_honoured() {
        assertFalse(SyncLoadMixinGate.parseSyncLoadDetection(List.of(
                "[diagnostics]",
                "\tsyncLoadDetection = false")));
    }

    @Test
    void only_the_diagnostics_section_key_counts() {
        // 去掉 section 跟踪后这条必挂: 别的段出现同名键时会被误读成本开关。
        assertTrue(SyncLoadMixinGate.parseSyncLoadDetection(List.of(
                "[general]",
                "syncLoadDetection = false",
                "[diagnostics]",
                "syncLoadDetection = true")));
        assertFalse(SyncLoadMixinGate.parseSyncLoadDetection(List.of(
                "[diagnostics]",
                "syncLoadDetection = false",
                "[prometheus]",
                "syncLoadDetection = true")));
    }

    @Test
    void comments_and_blank_lines_are_skipped() {
        assertFalse(SyncLoadMixinGate.parseSyncLoadDetection(List.of(
                "# syncLoadDetection = true",
                "",
                "[diagnostics]",
                "\t#Detect and report main-thread synchronous chunk loads",
                "\tsyncLoadDetection = false")));
    }

    @Test
    void missing_config_file_keeps_the_detector_on(@TempDir Path dir) {
        assertTrue(SyncLoadMixinGate.readSyncLoadDetection(dir.resolve("nope.toml")));
        assertTrue(SyncLoadMixinGate.readSyncLoadDetection(null));
        // 目录而非文件: isRegularFile 判否, 同样按默认值。
        assertTrue(SyncLoadMixinGate.readSyncLoadDetection(dir));
    }

    @Test
    void existing_config_file_is_read(@TempDir Path dir) throws IOException {
        Path toml = dir.resolve("common.toml");
        Files.write(toml, List.of("[diagnostics]", "\tsyncLoadDetection = false"), StandardCharsets.UTF_8);
        assertFalse(SyncLoadMixinGate.readSyncLoadDetection(toml));

        Files.write(toml, List.of("[diagnostics]", "\tsyncLoadDetection = true"), StandardCharsets.UTF_8);
        assertTrue(SyncLoadMixinGate.readSyncLoadDetection(toml));
    }

    @Test
    void the_two_gates_answer_independently() {
        // 组合矩阵: 只要任一条判否就不该注入。平台侧的短路顺序不同不影响结论。
        Set<String> clean = Set.of("minecraft", "forge");
        Set<String> dirty = Set.of("minecraft", "harium");
        List<String> on = List.of("[diagnostics]", "syncLoadDetection = true");
        List<String> off = List.of("[diagnostics]", "syncLoadDetection = false");

        assertNull(SyncLoadMixinGate.firstConflictingModId(clean));
        assertTrue(SyncLoadMixinGate.parseSyncLoadDetection(on));

        assertEquals("harium", SyncLoadMixinGate.firstConflictingModId(dirty));
        assertFalse(SyncLoadMixinGate.parseSyncLoadDetection(off));
    }
}
