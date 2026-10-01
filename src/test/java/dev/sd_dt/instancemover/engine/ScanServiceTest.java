package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.model.ItemKind;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 扫描 / 体积估算 / 实例识别，对应 mc_transfer.py v1.1 的 {@code scan_instance()} /
 * {@code quick_size()} / {@code size_note()} / {@code count_items()} /
 * {@code looks_like_instance()} / {@code normalize_instance_path()}。
 */
class ScanServiceTest {

    /** 复刻 py 自检里的老实例 fixture（并把 .voxy / DH 按新清单视为默认项）。 */
    private static Path legacySource(Path base) {
        Path src = base.resolve("老实例");
        TestSupport.write(src.resolve("config/a.toml"), "新配置");
        TestSupport.write(src.resolve("config/sub/b.toml"), "子目录配置");
        TestSupport.write(src.resolve("saves/world/level.dat"), "存档数据");
        TestSupport.write(src.resolve("resourcepacks/pack.zip"), "资源包");
        TestSupport.write(src.resolve("shaderpacks/shader.zip"), "光影");
        TestSupport.write(src.resolve("options.txt"), "老实例的 options");
        TestSupport.write(src.resolve("servers.dat"), "服务器列表");
        TestSupport.write(src.resolve("journeymap/waypoints.json"), "路径点");
        TestSupport.write(src.resolve(".voxy/server1/lods.db"), "voxy 远景数据");
        TestSupport.write(src.resolve("Distant_Horizons_server_data/Srv/dim_overworld/d.sqlite"), "dh 远景数据");
        TestSupport.write(src.resolve("mods/x.jar"), "模组");
        return src;
    }

    @Test
    @DisplayName("扫描：present / missing / extras 与 py 自检一致（.voxy 与 DH 已归默认项）")
    void scanMatchesLegacySelfTest(@TempDir Path base) {
        Path src = legacySource(base);
        ScanResult result = ScanService.scanInstance(src);

        Set<String> present = result.present().stream().map(ScannedItem::name).collect(Collectors.toSet());
        Set<String> missing = result.missing().stream().map(ScannedItem::name).collect(Collectors.toSet());
        Set<String> extras = result.extras().stream().map(ScannedItem::name).collect(Collectors.toSet());

        assertEquals(Set.of("config", "resourcepacks", "saves", "shaderpacks", "options.txt",
                "servers.dat", ".voxy", "Distant_Horizons_server_data"), present);
        assertEquals(Set.of("schematics", "screenshots", "xaero"), missing);
        assertEquals(Set.of("journeymap", "mods"), extras,
                ".voxy / Distant_Horizons_server_data 已并入默认项，不该再出现在 extras 里");

        assertEquals(11, result.present().size() + result.missing().size(), "默认项必须扫满 11 项");
        assertEquals(2, result.extras().size(), "询问项里只有 journeymap 与 mods 存在");
        assertEquals(10, result.transferable().size(), "可转移 = present(8) + extras(2)");
        assertEquals(13, result.all().size(), "全部 = present(8) + missing(3) + extras(2)");
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("扫描：缺失原因区分「不存在」与「类型不符」")
    void missingReasons(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        TestSupport.write(src.resolve("config/a.toml"), "配置");
        TestSupport.write(src.resolve("xaero"), "本该是文件夹，却是文件");
        TestSupport.write(src.resolve("options.txt"), "设置");

        ScanResult result = ScanService.scanInstance(src);
        String xaeroReason = result.missing().stream()
                .filter(item -> item.name().equals("xaero"))
                .map(ScannedItem::missingReason)
                .findFirst().orElseThrow();
        String savesReason = result.missing().stream()
                .filter(item -> item.name().equals("saves"))
                .map(ScannedItem::missingReason)
                .findFirst().orElseThrow();

        assertEquals("类型不符", xaeroReason);
        assertEquals("不存在", savesReason);
        assertNull(result.present().stream().filter(i -> i.name().equals("config"))
                .findFirst().orElseThrow().missingReason());
    }

    @Test
    @DisplayName("扫描：extras 带上默认勾选态与作用文案")
    void extrasCarryDefaults(@TempDir Path base) {
        Path src = legacySource(base);
        ScanResult result = ScanService.scanInstance(src);
        ScannedItem journeymap = result.extras().stream()
                .filter(i -> i.name().equals("journeymap")).findFirst().orElseThrow();
        ScannedItem mods = result.extras().stream()
                .filter(i -> i.name().equals("mods")).findFirst().orElseThrow();

        assertTrue(journeymap.defaultOn(), "journeymap 默认勾选");
        assertFalse(mods.defaultOn(), "mods 默认不勾");
        assertTrue(journeymap.description().contains("JourneyMap"));
        assertTrue(mods.description().contains("不建议勾选"));
        assertTrue(journeymap.sizeNote().startsWith("文件夹，约 "), "体积说明：" + journeymap.sizeNote());
        assertTrue(journeymap.fileCount() >= 1);
    }

    @Test
    @DisplayName("扫描：含 session.lock 的存档带警告标记")
    void sessionLockWarning(@TempDir Path base) throws Exception {
        Path src = legacySource(base);
        TestSupport.write(src.resolve("saves/world/session.lock"), "lock");
        ScanResult result = ScanService.scanInstance(src);

        ScannedItem saves = result.present().stream()
                .filter(i -> i.name().equals("saves")).findFirst().orElseThrow();
        assertTrue(saves.hasWarnings(), "saves 应带 session.lock 警告");
        assertTrue(saves.warnings().get(0).contains("session.lock"), saves.warnings().toString());
        assertTrue(saves.warnings().get(0).contains("world"), saves.warnings().toString());

        List<String> warnings = ScanService.sessionLockWarnings(src.resolve("saves"));
        assertEquals(1, warnings.size());
        assertTrue(ScanService.sessionLockWarnings(src.resolve("不存在")).isEmpty());
        assertTrue(ScanService.sessionLockWarnings(base.resolve("空目录没有")).isEmpty());
    }

    @Test
    @DisplayName("quickSize：文件直接返回大小；目录超文件数上限即截断")
    void quickSizeBehaviour(@TempDir Path base) throws Exception {
        Path file = base.resolve("options.txt");
        TestSupport.write(file, "12345678");
        QuickSize single = ScanService.quickSize(file);
        assertEquals(8, single.bytes());
        assertEquals(1, single.fileCount());
        assertFalse(single.truncated());

        Path dir = base.resolve("很多文件");
        TestSupport.write(dir.resolve("a.txt"), "aaaa");
        TestSupport.write(dir.resolve("b.txt"), "bbbb");
        TestSupport.write(dir.resolve("c.txt"), "cccc");
        QuickSize full = ScanService.quickSize(dir);
        assertEquals(3, full.fileCount());
        assertEquals(12, full.bytes());
        assertFalse(full.truncated(), "3 个文件远小于 3000，不该截断");

        QuickSize limited = ScanService.quickSize(dir, 1, 999);
        assertTrue(limited.truncated(), "文件数超过上限必须标记未扫完");
        assertEquals(3, limited.fileCount());
        assertEquals(12, limited.bytes());
    }

    @Test
    @DisplayName("sizeNote 文案：文件 / 文件夹 / 未扫完 / 不存在")
    void sizeNoteFormats(@TempDir Path base) throws Exception {
        Path file = base.resolve("options.txt");
        TestSupport.write(file, "1234567890");
        assertEquals("文件，10 B", ScanService.sizeNote(file, ItemKind.FILE));
        assertEquals("文件", ScanService.sizeNote(base.resolve("没有这个文件"), ItemKind.FILE));
        assertEquals("文件夹", ScanService.sizeNote(base.resolve("没有这个文件夹"), ItemKind.DIR));

        Path dir = base.resolve("配置");
        TestSupport.write(dir.resolve("a.toml"), "配置内容");
        assertTrue(ScanService.sizeNote(dir, ItemKind.DIR).matches("文件夹，约 \\d+(\\.\\d)? B / 1 个文件"),
                "实际：" + ScanService.sizeNote(dir, ItemKind.DIR));

        assertEquals("文件夹，≥ 1.2 GB（未扫完）",
                ScanService.sizeNote(ItemKind.DIR, new QuickSize(1_288_490_188L, 128, true)));
        assertEquals("文件夹，约 1.2 GB / 128 个文件",
                ScanService.sizeNote(ItemKind.DIR, new QuickSize(1_288_490_188L, 128, false)));
        assertEquals("文件，1.2 GB",
                ScanService.sizeNote(ItemKind.FILE, new QuickSize(1_288_490_188L, 1, false)));
    }

    @Test
    @DisplayName("humanSize 使用 1024 进制（B / KB / MB / GB / TB）")
    void humanSize() {
        assertEquals("0 B", dev.sd_dt.instancemover.util.SizeFormatter.humanSize(0));
        assertEquals("512 B", dev.sd_dt.instancemover.util.SizeFormatter.humanSize(512));
        assertEquals("1.0 KB", dev.sd_dt.instancemover.util.SizeFormatter.humanSize(1024));
        assertEquals("1.5 KB", dev.sd_dt.instancemover.util.SizeFormatter.humanSize(1536));
        assertEquals("1.0 MB", dev.sd_dt.instancemover.util.SizeFormatter.humanSize(1024L * 1024));
        assertEquals("2.5 GB", dev.sd_dt.instancemover.util.SizeFormatter.humanSize((long) (2.5 * 1024 * 1024 * 1024)));
        assertEquals("1.0 TB", dev.sd_dt.instancemover.util.SizeFormatter.humanSize(1024L * 1024 * 1024 * 1024));
        assertEquals("1024.0 TB",
                dev.sd_dt.instancemover.util.SizeFormatter.humanSize(1024L * 1024 * 1024 * 1024 * 1024),
                "到 TB 封顶（对齐 py）");
    }

    @Test
    @DisplayName("countItems：按条目统计文件数与字节数")
    void countItems(@TempDir Path base) {
        Path src = legacySource(base);
        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("saves").orElseThrow(),
                MigrateCatalog.find("options.txt").orElseThrow());
        CountResult counted = ScanService.countItems(src, items);

        assertEquals(4, counted.fileCount(), "config 2 个 + saves 1 个 + options.txt 1 个");
        long expected = TestSupport.size(src.resolve("config/a.toml"))
                + TestSupport.size(src.resolve("config/sub/b.toml"))
                + TestSupport.size(src.resolve("saves/world/level.dat"))
                + TestSupport.size(src.resolve("options.txt"));
        assertEquals(expected, counted.bytes());

        assertEquals(0, ScanService.countItems(src, List.of(MigrateCatalog.find("schematics").orElseThrow()))
                .fileCount(), "缺失条目不产生计数");
    }

    @Test
    @DisplayName("looksLikeInstance：25 项里命中几项（只看存在性）")
    void looksLikeInstance(@TempDir Path base) throws Exception {
        Path src = legacySource(base);
        assertEquals(10, ScanService.looksLikeInstance(src),
                "命中：config/resourcepacks/saves/shaderpacks/options.txt/servers.dat/.voxy/DH/journeymap/mods");
        assertEquals(0, ScanService.looksLikeInstance(base.resolve("不存在")));

        Path empty = base.resolve("空目录");
        Files.createDirectories(empty);
        assertEquals(0, ScanService.looksLikeInstance(empty));

        Path onlyVoxy = base.resolve("只有远景缓存");
        TestSupport.write(onlyVoxy.resolve(".voxy/a.db"), "x");
        assertEquals(1, ScanService.looksLikeInstance(onlyVoxy), ".voxy 现在是默认项，也要参与打分");
    }

    @Test
    @DisplayName("normalizeInstancePath：选了含 .minecraft 的上一层会自动下钻并给提示")
    void normalizeInstancePath(@TempDir Path base) throws Exception {
        Path outer = base.resolve("版本目录");
        TestSupport.write(outer.resolve(".minecraft/config/a.toml"), "配置");

        NormalizedPath normalized = ScanService.normalizeInstancePath(outer);
        assertEquals(outer.resolve(".minecraft"), normalized.path());
        assertTrue(normalized.changed());
        assertEquals("所选目录里没有实例文件，已自动改用子目录：%s".formatted(outer.resolve(".minecraft")),
                normalized.notice());

        Path real = base.resolve("真正的实例");
        TestSupport.write(real.resolve("config/a.toml"), "配置");
        NormalizedPath untouched = ScanService.normalizeInstancePath(real);
        assertEquals(real, untouched.path());
        assertFalse(untouched.changed());

        NormalizedPath missing = ScanService.normalizeInstancePath(base.resolve("什么都没有"));
        assertNull(missing.notice());
        assertEquals(base.resolve("什么都没有"), missing.path());
    }

    @Test
    @DisplayName("itemPath 解析到实例根下的相对路径")
    void itemPathResolves(@TempDir Path base) {
        Path path = ScanService.itemPath(base, MigrateCatalog.find("config").orElseThrow());
        assertEquals(base.resolve("config"), path);
        assertNotNull(ScanService.itemPath(base, MigrateCatalog.find("options.txt").orElseThrow()));
    }
}
