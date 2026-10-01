package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 转移主流程，把 mc_transfer.py v1.1 的 {@code selftest()} 断言逐条搬成 JUnit 用例：
 * 扫描 → 合并覆盖 → 目标独有文件保留 → 未勾选 mods 不转移 → 缺失项安全跳过 → 幂等 →
 * 备份内容 → 取消中止。
 */
class TransferRunnerTest {

    /** 复刻 py 自检的老实例 / 新实例 fixture。 */
    private static Path[] legacyFixture(Path base) {
        Path src = base.resolve("老实例");
        Path dst = base.resolve("新实例");
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

        TestSupport.write(dst.resolve("config/a.toml"), "旧配置");
        TestSupport.write(dst.resolve("config/keep.toml"), "新实例独有");
        TestSupport.write(dst.resolve("options.txt"), "新实例的 options");
        TestSupport.write(dst.resolve("saves/other/level.dat"), "新实例自己的存档");
        return new Path[]{src, dst};
    }

    /** py 自检的转移列表：present + extras（去掉未勾选的 mods）。 */
    private static List<MigrateItem> legacyItems(Path src) {
        ScanResult scan = ScanService.scanInstance(src);
        List<MigrateItem> items = new ArrayList<>(scan.present().stream().map(ScannedItem::item).toList());
        scan.extras().stream()
                .filter(entry -> !"mods".equals(entry.name()))
                .map(ScannedItem::item)
                .forEach(items::add);
        return List.copyOf(items);
    }

    private static final class RecordingListener implements TransferListener {
        final List<String> logs = new ArrayList<>();
        final List<String> phases = new ArrayList<>();
        final List<long[]> progress = new ArrayList<>();
        java.util.function.BiConsumer<Long, Long> onProgressCallback;
        TransferReport done;
        TransferReport cancelled;
        Throwable error;

        @Override
        public void onLog(String line) {
            logs.add(line);
        }

        @Override
        public void onPhase(String text, boolean indeterminate) {
            phases.add(text);
        }

        @Override
        public void onProgress(long doneCount, long total) {
            progress.add(new long[]{doneCount, total});
            if (onProgressCallback != null) {
                onProgressCallback.accept(doneCount, total);
            }
        }

        @Override
        public void onDone(TransferReport report) {
            done = report;
        }

        @Override
        public void onCancelled(TransferReport report) {
            cancelled = report;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }
    }

    // ------------------------------------------------------------------
    // 完整流程（py 自检主体）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整流程：覆盖 / 新增 / 目标独有保留 / 未勾选 mods 不转移 / 缺失项安全跳过")
    void fullFlowMatchesLegacySelfTest(@TempDir Path base) throws Exception {
        Path[] fixture = legacyFixture(base);
        Path src = fixture[0];
        Path dst = fixture[1];
        List<MigrateItem> items = legacyItems(src);
        assertEquals(9, items.size(), "present 8 项 + 勾选的 journeymap");

        RecordingListener listener = new RecordingListener();
        TransferRunner runner = new TransferRunner(src, dst, items, true, listener);
        TransferReport report = runner.run();

        assertNotNull(listener.done, "必须回调 onDone");
        assertNull(listener.error, "不该有未预期错误");
        assertNull(listener.cancelled, "不该被取消");
        assertFalse(report.cancelled());
        assertFalse(report.hasError());
        assertEquals(report, listener.done);

        // ---- py 自检第一组内容断言 ----
        assertEquals("新配置", TestSupport.read(dst.resolve("config/a.toml")), "config/a.toml 已覆盖");
        assertEquals("子目录配置", TestSupport.read(dst.resolve("config/sub/b.toml")), "子目录文件已新增");
        assertTrue(TestSupport.exists(dst.resolve("config/keep.toml")), "新实例独有文件未被删除");
        assertEquals("老实例的 options", TestSupport.read(dst.resolve("options.txt")), "options.txt 已覆盖");
        assertEquals("存档数据", TestSupport.read(dst.resolve("saves/world/level.dat")), "存档已转移");
        assertTrue(TestSupport.exists(dst.resolve("saves/other/level.dat")), "新实例存档保留");
        assertEquals("服务器列表", TestSupport.read(dst.resolve("servers.dat")), "默认项 servers.dat 已转移");
        assertEquals("voxy 远景数据", TestSupport.read(dst.resolve(".voxy/server1/lods.db")),
                "默认项 .voxy 远景缓存已转移");
        assertEquals("dh 远景数据",
                TestSupport.read(dst.resolve("Distant_Horizons_server_data/Srv/dim_overworld/d.sqlite")),
                "默认项 Distant_Horizons_server_data 已转移");
        assertFalse(TestSupport.exists(dst.resolve("mods")), "未勾选的 mods 未被转移");
        assertFalse(TestSupport.exists(dst.resolve("schematics")), "缺失项被安全跳过，不产生空目录");
        assertEquals(0, report.totals().failed(), "无失败文件：" + report.totals().errors());

        // ---- 进度 / 统计 ----
        assertEquals(10, report.plannedFileCount(), "计划文件数：9 项共 10 个文件");
        long[] last = listener.progress.get(listener.progress.size() - 1);
        assertEquals(10, last[0], "进度必须推进到 total");
        assertEquals(10, last[1]);
        assertEquals(10, report.items().stream().mapToInt(item -> item.stats().processed()).sum(),
                "每个文件都要被处理一次（含相同跳过）");
        assertTrue(listener.phases.contains("正在统计文件…"));
        assertTrue(listener.phases.contains("正在备份新实例中将被覆盖的内容…"));
        assertTrue(listener.phases.contains("正在转移数据…"));
        assertTrue(listener.logs.contains("本次转移 9 项，共 10 个文件（约 %s）。"
                .formatted(dev.sd_dt.instancemover.util.SizeFormatter.humanSize(report.plannedBytes()))));
        assertTrue(listener.logs.contains("→ 转移 config（文件夹）"), listener.logs.toString());

        ItemResult configResult = resultOf(report, "config");
        assertEquals(1, configResult.stats().overwritten(), "config/a.toml 覆盖");
        assertEquals(1, configResult.stats().created(), "config/sub/b.toml 新增");
        assertTrue(listener.logs.contains("   config：新增 1，覆盖 1，相同跳过 0"), listener.logs.toString());

        // ---- 备份 ----
        assertTrue(report.hasBackup(), "doBackup=true 时必须生成备份");
        Path backupDir = report.backupDir();
        assertTrue(backupDir.getFileName().toString().startsWith("_转移备份_"), backupDir.toString());
        assertEquals("旧配置", TestSupport.read(backupDir.resolve("config/a.toml")), "备份里是覆盖前的旧内容");
        assertEquals("新实例的 options", TestSupport.read(backupDir.resolve("options.txt")));
        assertEquals("新实例独有", TestSupport.read(backupDir.resolve("config/keep.toml")));
        assertEquals("新实例自己的存档", TestSupport.read(backupDir.resolve("saves/other/level.dat")));
        assertFalse(TestSupport.exists(backupDir.resolve("servers.dat")), "目标里没有的条目不该备份");

        // ---- 需重启标记 ----
        Set<String> restart = report.restartRequiredItems().stream().map(ItemResult::name).collect(Collectors.toSet());
        assertEquals(Set.of("config", "options.txt"), restart, "只有真正写过的配置类条目才提示重启");
        assertTrue(report.restartRequired());
        assertEquals(10, report.transferStats().successful(), "转移阶段成功 10 个文件");
        assertEquals(4, report.backupStats().successful(), "备份阶段拷了 4 个文件");
        assertEquals(14, report.totals().successful(), "总统计 = 转移 + 备份（对齐 py 的 merge_stats）");
        assertTrue(report.summaryText().startsWith("成功 10"), report.summaryText());
        assertTrue(report.warnings().isEmpty(), "没有 session.lock 时不该有警告");
    }

    @Test
    @DisplayName("幂等：第二次运行全部命中「相同跳过」，失败 0，不再提示重启")
    void secondRunIsIdempotent(@TempDir Path base) throws Exception {
        Path[] fixture = legacyFixture(base);
        Path src = fixture[0];
        Path dst = fixture[1];
        List<MigrateItem> items = legacyItems(src);

        TransferReport first = new TransferRunner(src, dst, items, true, null).run();
        TransferReport second = new TransferRunner(src, dst, items, true, null).run();

        assertTrue(first.transferStats().overwritten() > 0, "第一次必然有覆盖");
        assertEquals(0, second.transferStats().overwritten(), "第二次不该再有覆盖（相同内容跳过）");
        assertEquals(0, second.transferStats().failed(), "第二次不该有失败");
        assertEquals(10, second.items().stream().mapToInt(item -> item.stats().skipped()).sum(),
                "10 个文件全部命中相同跳过");
        assertFalse(second.restartRequired(), "没有任何文件被写入时不该提示重启");
        assertNotNull(first.backupDir());
        assertNotNull(second.backupDir());
        assertFalse(first.backupDir().equals(second.backupDir()), "第二次备份目录必须另起一个");
    }

    @Test
    @DisplayName("源缺失条目：计入报告但目标不受影响")
    void missingItemsAreReportedNotCopied(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        Files.createDirectories(src);
        Files.createDirectories(dst);
        TestSupport.write(src.resolve("config/a.toml"), "配置");

        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("schematics").orElseThrow());
        TransferReport report = new TransferRunner(src, dst, items, false, null).run();

        assertTrue(resultOf(report, "schematics").sourceMissing(), "缺失条目要标出来");
        assertEquals(0, resultOf(report, "schematics").stats().processed());
        assertFalse(TestSupport.exists(dst.resolve("schematics")), "不产生空目录");
        assertEquals(List.of("schematics"), report.skippedItems().stream().map(ItemResult::name).toList());
        assertEquals(0, report.totals().failed());
        assertEquals(1, resultOf(report, "config").stats().created());
    }

    @Test
    @DisplayName("不备份时 report.backupDir() 为空，且日志不出现备份文案")
    void noBackupWhenDisabled(@TempDir Path base) throws Exception {
        Path[] fixture = legacyFixture(base);
        Path src = fixture[0];
        Path dst = fixture[1];
        RecordingListener listener = new RecordingListener();

        TransferReport report = new TransferRunner(src, dst, legacyItems(src), false, listener).run();

        assertFalse(report.hasBackup());
        assertNull(report.backupDir());
        assertTrue(listener.logs.stream().noneMatch(line -> line.contains("备份")), listener.logs.toString());
    }

    @Test
    @DisplayName("取消：能中止转移（已复制的文件保留、不回滚）")
    void cancelStopsTransfer(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        Files.createDirectories(dst);
        for (int i = 0; i < 20; i++) {
            TestSupport.write(src.resolve("saves/world/file_%02d.dat".formatted(i)), "存档 " + i);
        }
        List<MigrateItem> items = List.of(MigrateCatalog.find("saves").orElseThrow());

        RecordingListener listener = new RecordingListener();
        TransferRunner runner = new TransferRunner(src, dst, items, false, listener);
        listener.onProgressCallback = (done, total) -> {
            if (done >= 3) {
                runner.cancel();
            }
        };
        TransferReport report = runner.run();

        assertTrue(report.cancelled(), "必须报告已取消");
        assertNotNull(listener.cancelled, "必须回调 onCancelled");
        assertTrue(listener.logs.contains("已取消（已经复制的内容不会回滚）。"), listener.logs.toString());
        long copied;
        try (var stream = Files.list(dst.resolve("saves/world"))) {
            copied = stream.count();
        }
        assertEquals(3, copied, "取消后只保留已复制的文件");
        assertTrue(copied < 20, "确实中止了后续文件");
    }

    @Test
    @DisplayName("开始前就取消：一个文件都不复制")
    void cancelBeforeStart(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "配置");
        Files.createDirectories(dst);

        RecordingListener listener = new RecordingListener();
        TransferRunner runner = new TransferRunner(src, dst,
                List.of(MigrateCatalog.find("config").orElseThrow()), true, listener);
        runner.cancel();
        TransferReport report = runner.run();

        assertTrue(report.cancelled());
        assertFalse(TestSupport.exists(dst.resolve("config")), "取消后不该有任何写入");
        assertFalse(TestSupport.exists(dst.resolve("config/a.toml")));
        assertNotNull(listener.cancelled);
    }

    @Test
    @DisplayName("源实例存档含 session.lock：报告里带警告（新增能力）")
    void sessionLockWarningInReport(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("saves/world/level.dat"), "存档");
        TestSupport.write(src.resolve("saves/world/session.lock"), "lock");
        Files.createDirectories(dst);

        RecordingListener listener = new RecordingListener();
        TransferReport report = new TransferRunner(src, dst,
                List.of(MigrateCatalog.find("saves").orElseThrow()), false, listener).run();

        assertEquals(1, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().get(0).contains("session.lock"));
        assertTrue(listener.logs.stream().anyMatch(line -> line.startsWith("警告：")), listener.logs.toString());
    }

    // ------------------------------------------------------------------
    // 路径校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validatePaths：只拒绝同一个目录（含 \\\\?\\ 前缀与大小写等价形式）、不存在的目录；互为父子必须放行")
    void validatePathsRejectsOnlySameDirectory(@TempDir Path base) throws Exception {
        Path src = base.resolve("老实例");
        Path dst = base.resolve("新实例");
        Files.createDirectories(src.resolve("versions/旧包"));
        Files.createDirectories(dst);

        // ① 同一个目录：原样、大小写差异、\\?\ 前缀三种等价写法都要拒
        IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
                () -> TransferRunner.validatePaths(src, src));
        assertTrue(same.getMessage().contains("同一个目录"), same.getMessage());

        IllegalArgumentException sameCase = assertThrows(IllegalArgumentException.class,
                () -> TransferRunner.validatePaths(src, Path.of(src.toString().toUpperCase(java.util.Locale.ROOT))),
                "大小写不同的同一个目录也要拒（Windows 路径大小写不敏感）");
        assertTrue(sameCase.getMessage().contains("同一个目录"), sameCase.getMessage());

        // \\?\ 长路径前缀写法的同一个目录（手工拼前缀，短路径也能测）
        String prefixed = "\\\\?\\" + src.toAbsolutePath().normalize();
        IllegalArgumentException samePrefixed = assertThrows(IllegalArgumentException.class,
                () -> TransferRunner.validatePaths(src, Path.of(prefixed)),
                "带 \\\\?\\ 长路径前缀的同一目录也要拒");
        assertTrue(samePrefixed.getMessage().contains("同一个目录"), samePrefixed.getMessage());

        // ② 目录不存在
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> TransferRunner.validatePaths(base.resolve("不存在"), dst));
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        IllegalArgumentException missingTarget = assertThrows(IllegalArgumentException.class,
                () -> TransferRunner.validatePaths(src, base.resolve("目标不存在")));
        assertTrue(missingTarget.getMessage().contains("当前实例目录不存在"), missingTarget.getMessage());

        // ③ 互为父子（两种主流版本隔离布局）必须放行，不能抛异常
        Path root = base.resolve(".minecraft");
        Path newPack = root.resolve("versions/新包");
        Path oldPack = root.resolve("versions/旧包");
        Files.createDirectories(newPack);
        Files.createDirectories(oldPack);
        Files.createDirectories(root.resolve("config"));

        TransferRunner.validatePaths(root, newPack);
        TransferRunner.validatePaths(oldPack, root);
        TransferRunner.validatePaths(src, dst);
    }

    @Test
    @DisplayName("validatePaths(带条目)：只有「源根落在某个条目目录内部」才整体拒绝（自我复制）")
    void validatePathsWithItemsRejectsSourceInsideTargetItem(@TempDir Path base) throws Exception {
        Path root = base.resolve(".minecraft");
        Path newPack = root.resolve("versions/新包");
        Path backup = root.resolve("config/backup");
        Files.createDirectories(newPack);
        Files.createDirectories(backup);
        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("saves").orElseThrow());

        // 老实例 = 当前实例\config\backup → 复制 config 等于把整棵源树复制进它自己 → 整体拒绝
        IllegalArgumentException selfCopy = assertThrows(IllegalArgumentException.class,
                () -> TransferRunner.validatePaths(backup, root, items));
        assertTrue(selfCopy.getMessage().contains("复制进它自己"), selfCopy.getMessage());
        assertTrue(selfCopy.getMessage().contains("已拒绝执行"), selfCopy.getMessage());

        // 主流布局（老实例 = .minecraft 根 / 当前实例 = versions\新包）必须放行
        TransferRunner.validatePaths(root, newPack, items);
        // 反向布局（老实例 = versions\旧包 / 当前实例 = .minecraft 根）也必须放行：
        // versions 不是迁移条目，源树不会落进任何条目目录
        Path oldPack = root.resolve("versions/旧包");
        Files.createDirectories(oldPack);
        TransferRunner.validatePaths(oldPack, root, items);
    }

    @Test
    @DisplayName("条目级嵌套守卫：目标位置在源文件夹内部 → 只跳过该项、记 failed、不动目标数据，其余条目照常")
    void itemLevelNestingIsRejectedWithoutTouchingTarget(@TempDir Path base) throws Exception {
        Path source = base.resolve("老实例");
        TestSupport.write(source.resolve("saves/world/level.dat"), "老存档");
        TestSupport.write(source.resolve("config/a.toml"), "老配置");
        // 目标实例故意放在源的 saves 里面：saves 这一条必然自我复制，config 不受影响
        Path target = source.resolve("saves/子实例");
        TestSupport.write(target.resolve("config/keep.toml"), "新实例独有");

        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("saves").orElseThrow());
        RecordingListener listener = new RecordingListener();

        TransferReport report = new TransferRunner(source, target, items, false, listener).run();

        ItemResult saves = report.items().stream().filter(r -> r.name().equals("saves")).findFirst().orElseThrow();
        assertEquals(1, saves.stats().failed(), "嵌套条目必须记 1 条失败");
        assertTrue(saves.stats().errors().getFirst().contains("内部"), saves.stats().errors().toString());
        assertTrue(saves.stats().errors().getFirst().contains("自己复制进自己"), saves.stats().errors().toString());
        assertFalse(TestSupport.exists(target.resolve("saves")), "被拒绝的条目不产生任何写入");
        assertEquals("老存档", TestSupport.read(source.resolve("saves/world/level.dat")), "源数据原样保留");

        ItemResult config = report.items().stream().filter(r -> r.name().equals("config")).findFirst().orElseThrow();
        assertEquals(1, config.stats().created(), "其余条目照常迁移");
        assertEquals("老配置", TestSupport.read(target.resolve("config/a.toml")));
        assertEquals("新实例独有", TestSupport.read(target.resolve("config/keep.toml")), "目标独有文件保留");
        assertTrue(listener.logs.stream().anyMatch(line -> line.contains("会把自己复制进自己")),
                listener.logs.toString());
    }

    @Test
    @DisplayName("端到端：老实例=.minecraft 根 / 当前实例=versions\\新包（互为父子）迁移成功且幂等")
    void isolatedLayoutMigrationEndToEnd(@TempDir Path base) throws Exception {
        Path root = base.resolve(".minecraft");
        TestSupport.write(root.resolve("config/a.toml"), "老配置");
        TestSupport.write(root.resolve("saves/world/level.dat"), "老存档");
        TestSupport.write(root.resolve(".voxy/server1/lods.db"), "voxy 数据");
        Path target = root.resolve("versions/新包");
        TestSupport.write(target.resolve("config/keep.toml"), "新实例独有");

        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("saves").orElseThrow(),
                MigrateCatalog.find(".voxy").orElseThrow());
        RecordingListener listener = new RecordingListener();

        TransferReport first = new TransferRunner(root, target, items, true, listener).run();

        assertFalse(first.hasError(), "互为父子的合法布局不该报错：" + first.error());
        assertFalse(first.cancelled());
        assertEquals(0, first.totals().failed(), "不该有失败文件：" + first.errors());
        // 新建文件数：config/a.toml + saves/world/level.dat + .voxy/server1/lods.db = 3
        assertEquals(3, first.transferStats().created(), first.transferStats().toString());
        assertEquals(0, first.transferStats().overwritten());
        assertEquals("老配置", TestSupport.read(target.resolve("config/a.toml")));
        assertEquals("老存档", TestSupport.read(target.resolve("saves/world/level.dat")));
        assertEquals("voxy 数据", TestSupport.read(target.resolve(".voxy/server1/lods.db")));
        assertEquals("新实例独有", TestSupport.read(target.resolve("config/keep.toml")), "目标独有文件保留");
        assertEquals(0, countTempFiles(target), "不该有 .part / 临时文件残留");

        TransferReport second = new TransferRunner(root, target, items, true, null).run();

        assertEquals(0, second.transferStats().overwritten(), "第二次全部走「相同跳过」");
        assertEquals(0, second.transferStats().failed(), "第二次不该有失败");
        assertEquals(3, second.transferStats().skipped(), "3 个文件都应命中相同跳过");
        assertEquals(0, countTempFiles(target), "第二次跑完也不该有临时文件残留");
    }

    @Test
    @DisplayName("端到端（反向）：老实例=versions\\旧包 / 当前实例=.minecraft 根（互为父子）迁移成功且幂等")
    void reverseIsolatedLayoutMigrationEndToEnd(@TempDir Path base) throws Exception {
        Path root = base.resolve(".minecraft");
        TestSupport.write(root.resolve("config/keep.toml"), "新实例独有");
        TestSupport.write(root.resolve("saves/other/level.dat"), "新实例自己的存档");
        Path source = root.resolve("versions/旧包");
        TestSupport.write(source.resolve("config/a.toml"), "老配置");
        TestSupport.write(source.resolve("saves/world/level.dat"), "老存档");
        TestSupport.write(source.resolve(".voxy/server1/lods.db"), "voxy 数据");

        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("saves").orElseThrow(),
                MigrateCatalog.find(".voxy").orElseThrow());
        RecordingListener listener = new RecordingListener();

        TransferRunner.validatePaths(source, root, items);

        TransferReport first = new TransferRunner(source, root, items, true, listener).run();

        assertFalse(first.hasError(), "反向布局不该报错：" + first.error());
        assertEquals(0, first.transferStats().failed(), "不该有失败文件：" + first.errors());
        assertEquals(3, first.transferStats().created(), first.transferStats().toString());
        assertEquals(0, first.transferStats().overwritten());
        assertEquals("老配置", TestSupport.read(root.resolve("config/a.toml")));
        assertEquals("老存档", TestSupport.read(root.resolve("saves/world/level.dat")));
        assertEquals("voxy 数据", TestSupport.read(root.resolve(".voxy/server1/lods.db")));
        assertEquals("新实例独有", TestSupport.read(root.resolve("config/keep.toml")), "目标独有文件保留");
        assertEquals("新实例自己的存档", TestSupport.read(root.resolve("saves/other/level.dat")));
        assertEquals("老存档", TestSupport.read(source.resolve("saves/world/level.dat")), "源树必须完好");
        assertEquals("voxy 数据", TestSupport.read(source.resolve(".voxy/server1/lods.db")), "源树必须完好");
        assertEquals(0, countTempFiles(root), "不该有 .part / 临时文件残留");

        TransferReport second = new TransferRunner(source, root, items, true, null).run();

        assertEquals(0, second.transferStats().overwritten(), "第二次全部走「相同跳过」");
        assertEquals(0, second.transferStats().failed(), "第二次不该有失败");
        assertEquals(3, second.transferStats().skipped());
        assertEquals(0, countTempFiles(root), "第二次跑完也不该有临时文件残留");
    }

    /** 统计目录下残留的临时文件（引擎写盘用的是 .instance_mover_*.part）。 */
    private static long countTempFiles(Path root) throws java.io.IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(".instance_mover_") || name.endsWith(".part");
                    })
                    .count();
        }
    }

    @Test
    @DisplayName("路径非法时 run() 不抛异常，走 onError 并返回带错误的报告")
    void invalidPathsProduceErrorReport(@TempDir Path base) throws Exception {
        Path src = base.resolve("老实例");
        Files.createDirectories(src);
        RecordingListener listener = new RecordingListener();

        TransferReport report = new TransferRunner(src, src, List.of(), false, listener).run();

        assertTrue(report.hasError(), "必须把错误写进报告");
        assertNotNull(listener.error, "必须回调 onError");
        assertFalse(report.cancelled());
        assertNull(listener.done, "出错时不该回调 onDone");
        assertTrue(listener.error instanceof IllegalArgumentException);
    }

    @Test
    @DisplayName("异步执行：runAsync 返回同一个报告")
    void runAsyncReturnsReport(@TempDir Path base) throws Exception {
        Path[] fixture = legacyFixture(base);
        TransferReport report = new TransferRunner(fixture[0], fixture[1], legacyItems(fixture[0]), false, null)
                .runAsync()
                .get();
        assertEquals(0, report.totals().failed());
        assertEquals("新配置", TestSupport.read(fixture[1].resolve("config/a.toml")));
    }

    private static ItemResult resultOf(TransferReport report, String name) {
        return report.items().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有条目 " + name));
    }
}
