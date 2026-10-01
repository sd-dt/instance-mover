package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 引擎自检，对应 mc_transfer.py v1.1 的 {@code selftest()}。
 *
 * <p>启动参数 {@code -Dinstance_mover.selftest=true} 时在临时目录跑一遍真实引擎逻辑，
 * 把结果打成 {@code SELFTEST PASS n/n}（或 {@code SELFTEST FAIL x/n}）写进日志，
 * 不进界面、不碰玩家的真实实例。</p>
 *
 * <p>纯 Java，可直接单测。</p>
 */
public final class EngineSelfTest {

    /** 结果：总检查项、通过数、逐行输出。 */
    public record Result(boolean passed, int passedCount, int totalCount, List<String> lines) {

        /** {@code SELFTEST PASS 10/10} 这样的单行结论。 */
        public String summary() {
            return "SELFTEST %s %d/%d".formatted(passed ? "PASS" : "FAIL", passedCount, totalCount);
        }
    }

    private static final long FIXED_TIME = 1_700_000_000L;

    private final List<String> lines = new ArrayList<>();
    private int passedCount;
    private int totalCount;

    private EngineSelfTest() {
    }

    /** 跑一次自检（在系统临时目录里，结束后清理）。 */
    public static Result run() {
        EngineSelfTest selfTest = new EngineSelfTest();
        Path base = null;
        try {
            base = Files.createTempDirectory("instance_mover_selftest_");
            selfTest.execute(base);
        } catch (Exception ex) {
            selfTest.check(false, "自检出现异常：" + ex);
        } finally {
            if (base != null) {
                try {
                    PathUtils.deleteRecursively(base);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响结论
                }
            }
        }
        boolean passed = selfTest.passedCount == selfTest.totalCount && selfTest.totalCount > 0;
        String summary = "SELFTEST %s %d/%d".formatted(passed ? "PASS" : "FAIL",
                selfTest.passedCount, selfTest.totalCount);
        selfTest.lines.add(summary);
        return new Result(passed, selfTest.passedCount, selfTest.totalCount, List.copyOf(selfTest.lines));
    }

    private void check(boolean condition, String text) {
        totalCount++;
        if (condition) {
            passedCount++;
        }
        lines.add((condition ? "[通过] " : "[失败] ") + text);
    }

    private void execute(Path base) throws IOException, TransferCancelled {
        Path src = base.resolve("老实例");
        Path dst = base.resolve("新实例");
        write(src.resolve("config/a.toml"), "老配置");
        write(src.resolve("config/sub/b.toml"), "子目录配置");
        write(src.resolve("saves/world/level.dat"), "存档数据");
        write(src.resolve("resourcepacks/pack.zip"), "资源包");
        write(src.resolve("options.txt"), "老实例的 options");
        write(src.resolve(".voxy/server1/lods.db"), "voxy 数据");
        write(src.resolve("Distant_Horizons_server_data/Srv/d.sqlite"), "dh 数据");
        write(src.resolve("journeymap/waypoints.json"), "路径点");
        write(src.resolve("mods/x.jar"), "模组");

        write(dst.resolve("config/a.toml"), "新配置");
        write(dst.resolve("config/keep.toml"), "新实例独有");
        write(dst.resolve("options.txt"), "新实例的 options");
        write(dst.resolve("saves/other/level.dat"), "新实例自己的存档");

        // ---- 1. 清单数量（按用户决定：默认 11 / 询问 14） ----
        check(MigrateCatalog.DEFAULT_ITEMS.size() == 11 && MigrateCatalog.OPTIONAL_ITEMS.size() == 14,
                "清单数量：默认 %d 项、询问 %d 项"
                        .formatted(MigrateCatalog.DEFAULT_ITEMS.size(), MigrateCatalog.OPTIONAL_ITEMS.size()));
        Set<String> defaultNames = MigrateCatalog.DEFAULT_ITEMS.stream()
                .map(MigrateItem::name).collect(Collectors.toSet());
        check(defaultNames.contains(".voxy") && defaultNames.contains("Distant_Horizons_server_data"),
                ".voxy 与 Distant_Horizons_server_data 已并入默认项");

        // ---- 2. 扫描 ----
        ScanResult scan = ScanService.scanInstance(src);
        Set<String> present = scan.present().stream().map(ScannedItem::name).collect(Collectors.toSet());
        Set<String> missing = scan.missing().stream().map(ScannedItem::name).collect(Collectors.toSet());
        Set<String> extras = scan.extras().stream().map(ScannedItem::name).collect(Collectors.toSet());
        check(present.equals(Set.of("config", "saves", "resourcepacks", "options.txt",
                        ".voxy", "Distant_Horizons_server_data")),
                "扫描到的默认项正确：" + present);
        check(extras.equals(Set.of("journeymap", "mods")), "扫描到的询问项正确：" + extras);
        check(missing.contains("schematics") && missing.contains("screenshots") && missing.contains("xaero"),
                "缺失项识别正确：" + missing);

        // ---- 3. 合并覆盖 ----
        List<MigrateItem> items = new ArrayList<>(scan.present().stream().map(ScannedItem::item).toList());
        scan.extras().stream().filter(entry -> !"mods".equals(entry.name()))
                .map(ScannedItem::item).forEach(items::add);
        TransferStats stats = new TransferStats();
        CopyEngine engine = CopyEngine.silent(() -> false);
        for (MigrateItem item : items) {
            engine.copyItem(item.resolveIn(src), item.resolveIn(dst), stats, true);
        }
        check("老配置".equals(read(dst.resolve("config/a.toml"))), "config/a.toml 已覆盖");
        check("子目录配置".equals(read(dst.resolve("config/sub/b.toml"))), "子目录文件已新增");
        check(Files.exists(dst.resolve("config/keep.toml")), "新实例独有文件未被删除");
        check(Files.exists(dst.resolve("saves/other/level.dat")), "新实例自己的存档保留");
        check("voxy 数据".equals(read(dst.resolve(".voxy/server1/lods.db"))), "默认项 .voxy 已转移");
        check(!Files.exists(dst.resolve("mods")), "未勾选的 mods 未被转移");
        check(stats.failed() == 0, "无失败文件（失败 %d）".formatted(stats.failed()));

        // ---- 4. 缺失项安全跳过 ----
        TransferStats missingStats = new TransferStats();
        engine.copyItem(src.resolve("schematics"), dst.resolve("schematics"), missingStats, true);
        check(!Files.exists(dst.resolve("schematics")), "源缺失时不产生空目录");

        // ---- 5. 幂等 ----
        TransferStats again = new TransferStats();
        for (MigrateItem item : items) {
            engine.copyItem(item.resolveIn(src), item.resolveIn(dst), again, true);
        }
        check(again.overwritten() == 0 && again.failed() == 0,
                "重复转移安全（覆盖 %d，失败 %d）".formatted(again.overwritten(), again.failed()));

        // ---- 6. 大小与时间相同、内容不同必须覆盖（v1.1 修过的真实缺陷） ----
        Path trapA = base.resolve("t1");
        Path trapB = base.resolve("t2");
        int[] sizes = {1024, CopyEngine.VERIFY_LIMIT + 4096};
        for (int size : sizes) {
            byte[] a = new byte[size];
            byte[] b = new byte[size];
            java.util.Arrays.fill(a, (byte) 'A');
            java.util.Arrays.fill(b, (byte) 'B');
            writeBytes(trapA.resolve("f_%d.bin".formatted(size)), a, FIXED_TIME);
            writeBytes(trapB.resolve("f_%d.bin".formatted(size)), b, FIXED_TIME);
        }
        TransferStats trapStats = new TransferStats();
        engine.copyItem(trapA, trapB, trapStats, true);
        boolean allOverwritten = true;
        for (int size : sizes) {
            byte[] content = Files.readAllBytes(trapB.resolve("f_%d.bin".formatted(size)));
            allOverwritten &= content[0] == 'A';
        }
        check(allOverwritten, "大小与时间相同但内容不同时仍然正确覆盖");

        TransferStats trapAgain = new TransferStats();
        engine.copyItem(trapA, trapB, trapAgain, true);
        check(trapAgain.skipped() == 1 && trapAgain.overwritten() == 1,
                "内容真相同跳过、超大文件保守重写（跳过 %d，覆盖 %d）"
                        .formatted(trapAgain.skipped(), trapAgain.overwritten()));

        // ---- 7. 备份 ----
        Path backupTarget = base.resolve("备份验证");
        write(backupTarget.resolve("config/a.toml"), "将被覆盖的旧内容");
        TransferStats backupStats = new TransferStats();
        TransferListener listener = new TransferListener() {
        };
        var backupDir = BackupService.runBackup(backupTarget, List.of(MigrateCatalog.find("config").orElseThrow()),
                CopyEngine.silent(() -> false), backupStats, listener);
        check(backupDir.isPresent()
                        && "将被覆盖的旧内容".equals(read(backupDir.orElseThrow().resolve("config/a.toml")))
                        && backupDir.orElseThrow().getFileName().toString().startsWith(BackupService.BACKUP_PREFIX),
                "备份目录与备份内容正确：" + backupDir.map(Path::getFileName).orElse(null));

        // ---- 8. 取消能中止 ----
        Path cancelSrc = base.resolve("取消源");
        Path cancelDst = base.resolve("取消目标");
        for (int i = 0; i < 30; i++) {
            write(cancelSrc.resolve("saves/world/f%02d.dat".formatted(i)), "存档 " + i);
        }
        Files.createDirectories(cancelDst);
        AtomicBoolean cancel = new AtomicBoolean(false);
        int[] copied = {0};
        CopyEngine cancelEngine = new CopyEngine(cancel::get, null, () -> {
            copied[0]++;
            if (copied[0] >= 3) {
                cancel.set(true);
            }
        });
        boolean cancelled = false;
        try {
            cancelEngine.copyItem(cancelSrc.resolve("saves"), cancelDst.resolve("saves"), new TransferStats(), true);
        } catch (TransferCancelled ex) {
            cancelled = true;
        }
        check(cancelled, "取消标志能中止转移");

        // ---- 9. 长路径前缀 ----
        Path deep = base.resolve("深".repeat(80)).resolve("目录".repeat(80)).resolve("options.txt");
        check(PathUtils.isWindows()
                        ? PathUtils.extendedPathString(deep.toString()).startsWith("\\\\?\\")
                        : PathUtils.extendedPathString(deep.toString()).equals(deep.toString()),
                "超长路径自动加 \\\\?\\ 前缀");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void writeBytes(Path file, byte[] bytes, long mtimeSeconds) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                java.time.Instant.ofEpochSecond(mtimeSeconds)));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            return "<读取失败：" + ex + ">";
        }
    }
}
