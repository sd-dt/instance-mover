package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 备份阶段，对应 mc_transfer.py v1.1 {@code TransferRunner} 里的备份逻辑：
 * 备份目录 <目标>\_转移备份_yyyyMMdd_HHmmss\、已存在时追加 _1/_2、只备份目标侧已存在的条目、
 * skip_same=False 必须真拷。
 */
class BackupServiceTest {

    private final List<String> logs = new ArrayList<>();

    private TransferListener listener() {
        return new TransferListener() {
            @Override
            public void onLog(String line) {
                logs.add(line);
            }
        };
    }

    private static MigrateItem item(String name) {
        return MigrateCatalog.find(name).orElseThrow(() -> new AssertionError("清单里没有 " + name));
    }

    @Test
    @DisplayName("时间戳格式 yyyyMMdd_HHmmss")
    void timestampFormat() {
        assertTrue(BackupService.timestamp().matches("\\d{8}_\\d{6}"), BackupService.timestamp());
        assertEquals("20260102_030405",
                BackupService.timestamp(java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        assertEquals("_转移备份_20260102_030405", BackupService.backupDirName("20260102_030405"));
    }

    @Test
    @DisplayName("备份目录重名时追加 _1 / _2 序号")
    void allocateBackupDirAppendsSuffix(@TempDir Path target) throws Exception {
        Path first = BackupService.allocateBackupDir(target, "20260102_030405");
        assertEquals(target.resolve("_转移备份_20260102_030405"), first);
        Files.createDirectories(first);

        Path second = BackupService.allocateBackupDir(target, "20260102_030405");
        assertEquals(target.resolve("_转移备份_20260102_030405_1"), second);
        Files.createDirectories(second);

        Path third = BackupService.allocateBackupDir(target, "20260102_030405");
        assertEquals(target.resolve("_转移备份_20260102_030405_2"), third);
    }

    @Test
    @DisplayName("只备份「目标侧已存在」的条目")
    void itemsToBackupOnlyExistingTargets(@TempDir Path target) {
        TestSupport.write(target.resolve("config/a.toml"), "配置");
        TestSupport.write(target.resolve("options.txt"), "设置");

        List<MigrateItem> todo = BackupService.itemsToBackup(target,
                List.of(item("config"), item("saves"), item("options.txt"), item("mods")));

        assertEquals(List.of("config", "options.txt"), todo.stream().map(MigrateItem::name).toList());
    }

    @Test
    @DisplayName("备份流程：建目录、真拷内容、日志文案与统计并入总统计")
    void runBackupCopiesAndMergesStats(@TempDir Path target) throws Exception {
        TestSupport.write(target.resolve("config/a.toml"), "旧配置");
        TestSupport.write(target.resolve("config/keep.toml"), "新实例独有");
        TestSupport.write(target.resolve("options.txt"), "新实例的 options");

        List<MigrateItem> items = List.of(item("config"), item("options.txt"), item("saves"));
        TransferStats totals = new TransferStats();
        CopyEngine engine = new CopyEngine(() -> false, logs::add, null);

        Optional<Path> backupDir = BackupService.runBackup(target, items, engine, totals, listener());

        assertTrue(backupDir.isPresent(), "有内容可备份时必须建备份目录");
        Path dir = backupDir.orElseThrow();
        assertTrue(dir.getFileName().toString().startsWith("_转移备份_"), dir.toString());
        assertEquals(dir.getParent(), target, "备份目录必须建在目标实例下");

        assertEquals("旧配置", TestSupport.read(dir.resolve("config/a.toml")), "备份里是被覆盖前的旧内容");
        assertEquals("新实例独有", TestSupport.read(dir.resolve("config/keep.toml")));
        assertEquals("新实例的 options", TestSupport.read(dir.resolve("options.txt")));
        assertFalse(TestSupport.exists(dir.resolve("saves")), "目标里没有的条目不该被备份");

        assertEquals(3, totals.created(), "备份统计并入总统计");
        assertEquals(0, totals.failed());
        assertTrue(totals.bytes() > 0);
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("开始备份新实例中将被覆盖的 2 项 → ")), logs.toString());
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("备份完成：3 个文件（约 ")), logs.toString());
    }

    @Test
    @DisplayName("目标里没有任何同名内容：只记一行日志、不建备份目录")
    void runBackupNoopWhenNothingToBackup(@TempDir Path target) throws Exception {
        Files.createDirectories(target);
        TransferStats totals = new TransferStats();
        CopyEngine engine = new CopyEngine(() -> false, logs::add, null);

        Optional<Path> backupDir = BackupService.runBackup(target,
                List.of(item("config"), item("saves")), engine, totals, listener());

        assertTrue(backupDir.isEmpty());
        assertEquals(0, totals.processed());
        assertEquals(List.of("新实例里没有与转移列表同名的内容，无需备份。"), logs);
        try (var stream = Files.list(target)) {
            assertEquals(0, stream.count(), "不该产生任何备份目录");
        }
    }

    @Test
    @DisplayName("备份统计并入 totals 时不为覆盖项（备份目录是新建的，全部算新增）")
    void backupAlwaysCountsAsCreated(@TempDir Path target) throws Exception {
        TestSupport.write(target.resolve("config/a.toml"), "旧配置");
        TransferStats totals = new TransferStats();
        CopyEngine engine = new CopyEngine(() -> false, logs::add, null);

        BackupService.runBackup(target, List.of(item("config")), engine, totals, listener());

        assertEquals(1, totals.created());
        assertEquals(0, totals.overwritten());
        assertEquals(0, totals.skipped(), "备份必须真拷，不许跳过");
        assertNotNull(totals.errors());
    }
}
