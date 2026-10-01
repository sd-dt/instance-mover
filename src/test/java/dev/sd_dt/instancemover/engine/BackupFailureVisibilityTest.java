package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.PathUtils;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * t15-F2「备份失败可见性」的**永久回归测试**（t21 补）。
 *
 * <p>被锁住的行为（生产代码见 {@code BackupService.runBackup} 与 {@link TransferReport#backupIncomplete()}）：</p>
 * <ol>
 *   <li>备份**整体失败**时不能再打印误导性的「备份完成：0 个文件」；</li>
 *   <li>改为打印「警告：备份不完整——…」并把每个失败原因逐条列出来；</li>
 *   <li>{@link TransferReport#backupIncomplete()} 必须为 {@code true}（结果页据此显示黄色警告）；</li>
 *   <li>备份失败**不阻断**转移：其余条目照常复制，统计各自记真实情况。</li>
 * </ol>
 *
 * <p><b>失败是真的，不是 mock</b>：用当前 JVM 自己持有的**排他文件锁**（{@code FileChannel.open(READ, WRITE)}
 * + {@code tryLock()}）锁住目标侧一个文件。Windows 上这是**强制锁**，后续 {@code Files.copy} 读该文件会直接
 * 抛 {@code FileSystemException}（本机实测：同进程的锁同样能挡住复制；只读文件属性和只读目录**挡不住**，
 * 所以这里不用它们）。锁住的目标文件在源实例里**不存在**，因此备份一定会复制它（失败），
 * 而转移阶段不会去动它（合并语义保留目标独有文件）。</p>
 */
class BackupFailureVisibilityTest {

    private static MigrateItem item(String name) {
        return MigrateCatalog.find(name).orElseThrow(() -> new AssertionError("清单里没有条目：" + name));
    }

    /** 收集日志的监听器（其余回调用默认空实现）。 */
    private static List<String> collectLogs() {
        return new ArrayList<>();
    }

    private static TransferListener listenerInto(List<String> logs) {
        return new TransferListener() {
            @Override
            public void onLog(String line) {
                logs.add(line);
            }
        };
    }

    private static List<String> namesIn(Path dir) throws Exception {
        if (!Files.isDirectory(PathUtils.lp(dir))) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(PathUtils.lp(dir))) {
            return stream.map(PathUtils::fileName).sorted().toList();
        }
    }

    @Test
    @DisplayName("备份整体失败：不打印「备份完成：」、改打「警告：备份不完整」、backupIncomplete=true，且转移照常完成")
    void backupFailureIsVisibleAndDoesNotBlockTransfer(@TempDir Path base) throws Exception {
        Path source = base.resolve("老实例");
        Path target = base.resolve("新实例");
        TestSupport.write(source.resolve("config/a.toml"), "老配置");
        TestSupport.write(source.resolve("options.txt"), "老设置");
        // 目标独有 + 会被备份 + 源里没有 → 备份必然复制它（失败），转移不会覆盖它
        Path locked = target.resolve("config/locked.bin");
        TestSupport.write(locked, "目标独有且被独占锁住的内容");

        List<String> logs = collectLogs();
        List<MigrateItem> items = List.of(item("config"), item("options.txt"));

        TransferReport report;
        try (FileChannel channel = FileChannel.open(PathUtils.lp(locked),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            assertNotNull(lock, "前置条件：本机必须能对该文件加排他锁，否则本用例不成立");
            report = new TransferRunner(source, target, items, true, listenerInto(logs)).run();
        }

        // 前置：备份目录建出来了，而且这次备份是「整体失败」（0 个文件成功）——正是当初打印误导文案的场景
        assertTrue(report.hasBackup(), "备份目录应该已经建出来：" + report.backupDir());
        assertEquals(0, report.backupStats().created(), "目标侧被锁住的文件不该复制成功");
        assertTrue(report.backupStats().failed() >= 1,
                "备份必须真的记到失败（不能静默跳过）：" + report.backupStats().errors());

        // (a) 不再出现误导性的「备份完成：」（修复前这里是「备份完成：0 个文件（约 0 B）。」）
        assertFalse(logs.stream().anyMatch(line -> line.startsWith("备份完成：")),
                "备份失败时不能再打印「备份完成：」：" + logs);
        // (b) 必须出现警告，并把失败原因带上
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("警告：备份不完整")),
                "备份失败时必须给出警告：" + logs);
        assertTrue(logs.stream().anyMatch(line -> line.contains("锁定") || line.contains(".bin")),
                "警告里应该能看到是哪个文件失败：" + logs);
        // (c) 界面据此显示黄色警告的开关
        assertTrue(report.backupIncomplete(), "backupIncomplete() 必须为 true");
        // (d) 转移不被阻断：其余条目照常复制，统计各记真实情况
        assertEquals(0, report.transferStats().failed(),
                "备份失败不应影响转移：" + report.transferStats().errors());
        assertEquals(2, report.transferStats().created(), "两个条目都该被复制过去");
        assertEquals(0, report.transferStats().skipped());
        assertEquals("老配置", TestSupport.read(target.resolve("config/a.toml")));
        assertEquals("老设置", TestSupport.read(target.resolve("options.txt")));
        // 目标独有的文件必须原样保留（合并语义：不删、不覆盖）
        assertEquals("目标独有且被独占锁住的内容", TestSupport.read(locked));
        // 自清理检查：实例树里除备份目录外不应留 .part / .tmp 临时文件
        assertEquals(List.of("a.toml", "locked.bin"), namesIn(target.resolve("config")),
                "config 目录里不该有临时文件残留");
        assertTrue(namesIn(target).stream().noneMatch(name -> name.endsWith(".part")),
                "实例根目录里不该有 .part 残留：" + namesIn(target));
    }

    @Test
    @DisplayName("备份全部成功时仍然打印「备份完成：」，不会误报警告（防分支反向回归）")
    void successfulBackupStillLogsCompletion(@TempDir Path base) throws Exception {
        Path source = base.resolve("老实例");
        Path target = base.resolve("新实例");
        TestSupport.write(source.resolve("config/a.toml"), "老配置");
        TestSupport.write(source.resolve("options.txt"), "老设置");
        TestSupport.write(target.resolve("config/keep.toml"), "保留的新实例独有文件");
        TestSupport.write(target.resolve("options.txt"), "新设置");

        List<String> logs = collectLogs();
        List<MigrateItem> items = List.of(item("config"), item("options.txt"));
        TransferReport report = new TransferRunner(source, target, items, true, listenerInto(logs)).run();

        assertTrue(report.hasBackup());
        assertTrue(report.backupStats().created() >= 1, "备份应该成功复制若干文件");
        assertEquals(0, report.backupStats().failed(), "这份 fixture 里备份不该失败");
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("备份完成：")),
                "备份成功时仍要有「备份完成：」：" + logs);
        assertFalse(logs.stream().anyMatch(line -> line.startsWith("警告：备份不完整")),
                "备份成功时不能报警告：" + logs);
        assertFalse(report.backupIncomplete(), "备份成功时 backupIncomplete() 必须为 false");
        assertEquals("老配置", TestSupport.read(target.resolve("config/a.toml")));
        assertEquals("保留的新实例独有文件", TestSupport.read(target.resolve("config/keep.toml")));
    }
}
