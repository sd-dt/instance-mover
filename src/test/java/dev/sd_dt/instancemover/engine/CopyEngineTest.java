package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 合并覆盖引擎，对应 mc_transfer.py v1.1 的 {@code copy_item()} / {@code copy_one_file()} /
 * {@code walk_dir()} 行为与自检断言。
 */
class CopyEngineTest {

    private static final long FIXED_TIME = 1_700_000_000L;

    private final List<String> logs = new ArrayList<>();
    private int onFileCalls;

    private CopyEngine engine() {
        return new CopyEngine(() -> false, logs::add, () -> onFileCalls++);
    }

    // ------------------------------------------------------------------
    // 合并覆盖 / 目标独有文件 / 子目录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合并覆盖：同名覆盖、目标独有文件保留、子目录正确新增（对齐 py 自检第一组断言）")
    void mergeOverwriteKeepsTargetOnlyFiles(@TempDir Path base) throws Exception {
        Path src = base.resolve("老实例");
        Path dst = base.resolve("新实例");
        TestSupport.write(src.resolve("config/a.toml"), "新配置");
        TestSupport.write(src.resolve("config/sub/b.toml"), "子目录配置");
        TestSupport.write(src.resolve("saves/world/level.dat"), "存档数据");
        TestSupport.write(src.resolve("options.txt"), "老实例的 options");
        TestSupport.write(dst.resolve("config/a.toml"), "旧配置");
        TestSupport.write(dst.resolve("config/keep.toml"), "新实例独有");
        TestSupport.write(dst.resolve("options.txt"), "新实例的 options");
        TestSupport.write(dst.resolve("saves/other/level.dat"), "新实例自己的存档");

        TransferStats stats = new TransferStats();
        CopyEngine engine = engine();
        engine.copyItem(src.resolve("config"), dst.resolve("config"), stats, true);
        engine.copyItem(src.resolve("saves"), dst.resolve("saves"), stats, true);
        engine.copyItem(src.resolve("options.txt"), dst.resolve("options.txt"), stats, true);

        assertEquals("新配置", TestSupport.read(dst.resolve("config/a.toml")), "config/a.toml 已覆盖");
        assertEquals("子目录配置", TestSupport.read(dst.resolve("config/sub/b.toml")), "子目录文件已新增");
        assertTrue(TestSupport.exists(dst.resolve("config/keep.toml")), "新实例独有文件未被删除");
        assertEquals("新实例独有", TestSupport.read(dst.resolve("config/keep.toml")), "独有文件内容不该变");
        assertEquals("老实例的 options", TestSupport.read(dst.resolve("options.txt")), "options.txt 已覆盖");
        assertEquals("存档数据", TestSupport.read(dst.resolve("saves/world/level.dat")), "存档已转移");
        assertTrue(TestSupport.exists(dst.resolve("saves/other/level.dat")), "新实例存档保留");

        assertEquals(2, stats.created(), "新增：config/sub/b.toml 与 saves/world/level.dat");
        assertEquals(2, stats.overwritten(), "覆盖：config/a.toml 与 options.txt");
        assertEquals(0, stats.skipped());
        assertEquals(0, stats.failed(), "不应有失败文件");
    }

    @Test
    @DisplayName("幂等：内容完全相同再跑一次全部跳过，失败 0")
    void idempotentSecondRun(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "配置");
        TestSupport.write(src.resolve("config/sub/b.toml"), "子配置");
        TestSupport.write(dst.resolve("config/a.toml"), "旧配置");

        TransferStats first = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), first, true);
        assertEquals(1, first.overwritten(), "第一次应覆盖 config/a.toml");
        assertEquals(1, first.created(), "第一次应新增 config/sub/b.toml");

        TransferStats second = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), second, true);
        assertEquals(0, second.overwritten(), "第二次不该再有覆盖");
        assertEquals(0, second.failed(), "第二次不该有失败");
        assertEquals(2, second.skipped(), "两个文件都应命中「内容相同跳过」（修改时间被保留）");
    }

    @Test
    @DisplayName("源缺失项：安全跳过、不产生空目录、不动目标")
    void missingSourceIsSkipped(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        Files.createDirectories(src);
        Files.createDirectories(dst);

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("schematics"), dst.resolve("schematics"), stats, true);

        assertFalse(TestSupport.exists(dst.resolve("schematics")), "源缺失时不该产生空目录");
        assertEquals(0, stats.processed(), "源缺失不该计入任何统计（比 py 更安全：不碰目标）");
    }

    @Test
    @DisplayName("源缺失但目标是同名文件夹：不删除目标文件夹（安全跳过）")
    void missingSourceNeverDeletesTargetDir(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        Files.createDirectories(src);
        TestSupport.write(dst.resolve("schematics/keep.litematic"), "蓝图");

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("schematics"), dst.resolve("schematics"), stats, true);

        assertTrue(TestSupport.exists(dst.resolve("schematics/keep.litematic")), "目标内容必须原样保留");
        assertEquals(0, stats.failed());
    }

    // ------------------------------------------------------------------
    // 类型冲突
    // ------------------------------------------------------------------

    @Test
    @DisplayName("类型冲突：源是文件夹而目标是文件 → 删文件、建文件夹、日志文案逐字一致")
    void dirSourceFileTarget(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "配置");
        TestSupport.write(dst.resolve("config"), "目标原本是个文件");

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), stats, true);

        assertTrue(Files.isDirectory(dst.resolve("config")), "目标应该变成文件夹");
        assertEquals("配置", TestSupport.read(dst.resolve("config/a.toml")));
        assertEquals(0, stats.failed());
        assertEquals(1, stats.created());
        String expected = "提示：目标位置 %s 原本是文件，已删除后按文件夹转移。".formatted(dst.resolve("config"));
        assertTrue(logs.contains(expected), "缺少类型冲突提示，实际日志：" + logs);
    }

    @Test
    @DisplayName("类型冲突：源是文件而目标是文件夹 → 删文件夹、按文件转移、日志文案逐字一致")
    void fileSourceDirTarget(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("options.txt"), "老 OPTIONS");
        TestSupport.write(dst.resolve("options.txt/inside.txt"), "目标原本是文件夹");

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("options.txt"), dst.resolve("options.txt"), stats, true);

        assertTrue(Files.isRegularFile(dst.resolve("options.txt")), "目标应该变成文件");
        assertEquals("老 OPTIONS", TestSupport.read(dst.resolve("options.txt")));
        assertEquals(0, stats.failed());
        assertEquals(1, stats.created());
        String expected = "提示：目标位置 %s 原本是文件夹，已删除后按文件转移。".formatted(dst.resolve("options.txt"));
        assertTrue(logs.contains(expected), "缺少类型冲突提示，实际日志：" + logs);
    }

    @Test
    @DisplayName("目标父路径被文件占住：记 failed + 失败原因，不抛异常")
    void unwritableTargetRecordsFailure(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("options.txt"), "老 OPTIONS");
        TestSupport.write(dst.resolve("blocker"), "这是个文件");
        Path impossible = dst.resolve("blocker/options.txt");   // 父路径是文件 → 必然失败

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("options.txt"), impossible, stats, true);

        assertEquals(0, stats.created(), "写不进去就不该计入新增");
        assertEquals(1, stats.failed(), "必须计入 failed");
        assertFalse(stats.errors().isEmpty(), "必须记录失败原因");
        assertTrue(stats.errors().get(0).startsWith("复制失败："), "失败文案：" + stats.errors());
    }

    @Test
    @DisplayName("单个子目录失败不中断整体流程（其余文件照常复制）")
    void subDirFailureDoesNotAbort(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "配置 A");
        TestSupport.write(src.resolve("config/sub/b.toml"), "子目录配置");
        TestSupport.write(dst.resolve("config/sub"), "占位文件，挡住子目录");   // 子目录位置被文件占住

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), stats, true);

        assertEquals("配置 A", TestSupport.read(dst.resolve("config/a.toml")), "其他文件必须照常复制");
        assertEquals(1, stats.failed(), "被挡住的子目录记一条失败");
        assertTrue(stats.errors().stream().anyMatch(e -> e.startsWith("创建文件夹失败：")),
                "失败文案：" + stats.errors());
    }

    @Test
    @DisplayName("目录合并里的深层类型冲突：目标同名位置是文件夹 → 记失败、不静默删数据（对齐 py）")
    void nestedTypeConflictIsRecorded(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "老实例的文件");
        TestSupport.write(dst.resolve("config/a.toml/inside.toml"), "新实例在这里是个文件夹");

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), stats, true);

        // 实测行为（Windows MoveFileEx 不能把文件夹换成文件）：记一条失败，目标数据原样保留
        assertEquals(1, stats.failed(), "必须记一条失败：" + stats.errors());
        assertTrue(stats.errors().get(0).startsWith("复制失败："), stats.errors().toString());
        assertTrue(TestSupport.exists(dst.resolve("config/a.toml/inside.toml")),
                "记失败时目标数据必须原样保留（不静默删除）");
        assertEquals(0, stats.created(), "失败的复制不该计入新增");
    }

    // ------------------------------------------------------------------
    // 只读 / 失败不中断 / 字节统计 / 进度回调
    // ------------------------------------------------------------------

    @Test
    @DisplayName("目标只读：先清只读属性再覆盖")
    void readOnlyTargetIsOverwritten(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "新配置");
        TestSupport.write(dst.resolve("config/a.toml"), "旧配置");
        TestSupport.setMtime(dst.resolve("config/a.toml"), FIXED_TIME - 100);
        TestSupport.makeReadOnly(dst.resolve("config/a.toml"));
        assertTrue(TestSupport.isReadOnly(dst.resolve("config/a.toml")), "前置条件：目标应为只读");

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), stats, true);

        assertEquals("新配置", TestSupport.read(dst.resolve("config/a.toml")), "只读文件也必须被覆盖");
        assertEquals(0, stats.failed(), "不该失败");
        assertEquals(1, stats.overwritten());
        assertFalse(TestSupport.isReadOnly(dst.resolve("config/a.toml")),
                "覆盖后目标不该还是只读（临时文件继承的是源文件属性）");
        TestSupport.makeWritableRecursively(base);
    }

    @Test
    @DisplayName("统计与字节数：新增 / 覆盖 / 相同跳过 / failed 各自记账")
    void statsAccounting(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("dir/new.txt"), "12345");
        TestSupport.write(src.resolve("dir/same.txt"), "相同内容");
        TestSupport.write(src.resolve("dir/over.txt"), "新的内容");
        TestSupport.write(dst.resolve("dir/same.txt"), "相同内容");
        TestSupport.write(dst.resolve("dir/over.txt"), "旧的内容");
        TestSupport.setMtime(dst.resolve("dir/same.txt"), TestSupport.mtimeSeconds(src.resolve("dir/same.txt")));
        TestSupport.setMtime(dst.resolve("dir/over.txt"), FIXED_TIME - 100);

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("dir"), dst.resolve("dir"), stats, true);

        assertEquals(1, stats.created(), "new.txt 应算新增");
        assertEquals(1, stats.overwritten(), "over.txt 应算覆盖");
        assertEquals(1, stats.skipped(), "same.txt 应算相同跳过");
        assertEquals(0, stats.failed());
        assertEquals(TestSupport.size(src.resolve("dir/new.txt")) + TestSupport.size(src.resolve("dir/over.txt")),
                stats.bytes(), "字节数只统计真正写入的两个文件");
        assertEquals(3, stats.processed());
        assertEquals(2, stats.successful());
    }

    @Test
    @DisplayName("skipSame=false（备份语义）：内容相同也要真拷一次")
    void skipSameFalseAlwaysCopies(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "同样的内容");
        TestSupport.write(dst.resolve("config/a.toml"), "同样的内容");
        TestSupport.setMtime(dst.resolve("config/a.toml"), TestSupport.mtimeSeconds(src.resolve("config/a.toml")));

        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), stats, false);

        assertEquals(0, stats.skipped(), "skipSame=false 时不许跳过");
        assertEquals(1, stats.overwritten(), "必须真拷（算覆盖）");
        assertEquals("同样的内容", TestSupport.read(dst.resolve("config/a.toml")));
    }

    @Test
    @DisplayName("进度回调：每个文件之后自增一次，跳过与失败也算")
    void onFileCallbackCountsEveryFile(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("d/one.txt"), "1");
        TestSupport.write(src.resolve("d/two.txt"), "2");
        TestSupport.write(src.resolve("d/three.txt"), "3");
        TestSupport.write(dst.resolve("d/one.txt"), "1");
        TestSupport.setMtime(dst.resolve("d/one.txt"), TestSupport.mtimeSeconds(src.resolve("d/one.txt")));

        onFileCalls = 0;
        engine().copyItem(src.resolve("d"), dst.resolve("d"), new TransferStats(), true);
        assertEquals(3, onFileCalls, "3 个文件 → 回调 3 次（含相同跳过）");
    }

    @Test
    @DisplayName("原子替换：不残留临时文件")
    void noTempFilesLeftBehind(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "内容");
        TransferStats stats = new TransferStats();
        engine().copyItem(src.resolve("config"), dst.resolve("config"), stats, true);
        try (var stream = Files.list(dst.resolve("config"))) {
            List<String> names = stream.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("a.toml"), names, "目标目录里不该有临时文件残留");
        }
    }

    @Test
    @DisplayName("复制后修改时间与源保持一致（保证下次「相同跳过」幂等）")
    void mtimeIsPreserved(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "内容");
        TestSupport.setMtime(src.resolve("config/a.toml"), FIXED_TIME);
        engine().copyItem(src.resolve("config"), dst.resolve("config"), new TransferStats(), true);
        assertEquals(FIXED_TIME, TestSupport.mtimeSeconds(dst.resolve("config/a.toml")));
    }

    // ------------------------------------------------------------------
    // 遍历 / 取消
    // ------------------------------------------------------------------

    @Test
    @DisplayName("源目录不存在时 walkDir 产出一条错误条目，不抛异常")
    void walkDirReportsError(@TempDir Path base) {
        var walker = CopyEngine.walkDir(base.resolve("不存在"));
        assertTrue(walker.hasNext());
        DirEntry entry = walker.next();
        assertTrue(entry.hasError(), "应当给出读取失败原因");
        assertTrue(entry.files().isEmpty());
    }

    @Test
    @DisplayName("walkDir：根目录 + 子目录都产出，文件条目齐全")
    void walkDirListsDirectories(@TempDir Path base) throws IOException {
        TestSupport.write(base.resolve("a.txt"), "a");
        TestSupport.write(base.resolve("sub/b.txt"), "b");
        TestSupport.write(base.resolve("sub/deep/c.txt"), "c");
        List<String> dirs = new ArrayList<>();
        int files = 0;
        var walker = CopyEngine.walkDir(base);
        while (walker.hasNext()) {
            DirEntry entry = walker.next();
            dirs.add(entry.relative());
            files += entry.files().size();
        }
        List<String> expected = new ArrayList<>(List.of("", "sub", Path.of("sub", "deep").toString()));
        expected.sort(String::compareTo);
        assertEquals(expected, dirs.stream().sorted().toList());
        assertEquals(3, files, "三个文件都要被列出");
    }

    @Test
    @DisplayName("取消：每个文件/目录之前检查取消标志，命中抛 TransferCancelled")
    void cancellationThrows(@TempDir Path base) throws Exception {
        Path src = base.resolve("src");
        Path dst = base.resolve("dst");
        TestSupport.write(src.resolve("config/a.toml"), "配置");
        AtomicBoolean cancel = new AtomicBoolean(true);
        CopyEngine engine = new CopyEngine(cancel::get, logs::add, () -> onFileCalls++);

        assertThrows(TransferCancelled.class,
                () -> engine.copyItem(src.resolve("config"), dst.resolve("config"), new TransferStats(), true));
        assertFalse(TestSupport.exists(dst.resolve("config/a.toml")), "取消后不该有文件写入");
        assertTrue(engine.isCancelled());
    }

    @Test
    @DisplayName("sizeOf：不存在返回 0，存在返回真实大小")
    void sizeOfBehaviour(@TempDir Path base) {
        Path file = base.resolve("a.txt");
        TestSupport.write(file, "12345");
        assertEquals(5, CopyEngine.sizeOf(file));
        assertEquals(0, CopyEngine.sizeOf(base.resolve("nope.txt")));
        assertNotNull(CopyEngine.walkDir(base));
    }
}
