package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.model.ItemKind;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.PathUtils;
import dev.sd_dt.instancemover.util.SizeFormatter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 扫描与体积估算，对应 mc_transfer.py v1.1 的 {@code scan_instance()} / {@code quick_size()} /
 * {@code size_note()} / {@code count_items()} / {@code count_files()} /
 * {@code looks_like_instance()} / {@code normalize_instance_path()}。
 *
 * <p>纯 Java，不依赖 Minecraft；界面直接用 {@link #scanInstance(Path)} 的结果渲染勾选列表。</p>
 */
public final class ScanService {

    /** quick_size 的文件数上限（py: max_files=3000）。 */
    public static final int QUICK_SIZE_MAX_FILES = 3000;

    /** quick_size 的时间上限，秒（py: max_seconds=0.5）。 */
    public static final double QUICK_SIZE_MAX_SECONDS = 0.5;

    /** 存档警告文案（新增能力，py 没有）。 */
    public static final String SESSION_LOCK_WARNING_FORMAT =
            "存档「%s」含 session.lock：若该实例的游戏正在运行，请先关闭再迁移，否则存档可能不完整。";

    /** session.lock 被认为「近期仍被写入」的时间窗（秒）；超过且没被占用就不再告警（审查 F4）。 */
    public static final long SESSION_LOCK_RECENT_SECONDS = 15L * 60L;

    /** 自动下钻提示（逐字照抄 py）。 */
    public static final String DOWNGRADE_NOTICE_FORMAT =
            "所选目录里没有实例文件，已自动改用子目录：%s";

    private ScanService() {
    }

    /** 条目在实例根目录下的完整路径。 */
    public static Path itemPath(Path instanceRoot, MigrateItem item) {
        return item.resolveIn(instanceRoot);
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 对应 py 的 {@code scan_instance(path)}：把老实例目录扫成 present / missing / extras。
     */
    public static ScanResult scanInstance(Path instanceRoot) {
        List<ScannedItem> present = new ArrayList<>();
        List<ScannedItem> missing = new ArrayList<>();
        List<ScannedItem> extras = new ArrayList<>();

        for (MigrateItem item : MigrateCatalog.DEFAULT_ITEMS) {
            Path path = itemPath(instanceRoot, item);
            boolean ok = item.kind() == ItemKind.DIR ? PathUtils.isDir(path) : PathUtils.isFile(path);
            if (ok) {
                present.add(describe(instanceRoot, item, true, null));
            } else {
                String reason = PathUtils.pathExists(path) ? "类型不符" : "不存在";
                missing.add(describe(instanceRoot, item, false, reason));
            }
        }

        for (MigrateItem item : MigrateCatalog.OPTIONAL_ITEMS) {
            Path path = itemPath(instanceRoot, item);
            boolean ok = item.kind() == ItemKind.DIR ? PathUtils.isDir(path) : PathUtils.isFile(path);
            if (ok) {
                extras.add(describe(instanceRoot, item, true, null));
            }
        }

        return new ScanResult(present, missing, extras);
    }

    private static ScannedItem describe(Path instanceRoot, MigrateItem item, boolean present, String reason) {
        Path path = itemPath(instanceRoot, item);
        if (!present) {
            return new ScannedItem(item, false, reason, 0L, 0L, false, item.label(), List.of());
        }
        QuickSize quick = quickSize(path);
        return new ScannedItem(item, true, null, quick.bytes(), quick.fileCount(), quick.truncated(),
                sizeNote(path, item.kind()), warningsFor(path, item));
    }

    private static List<String> warningsFor(Path path, MigrateItem item) {
        if (item.kind() == ItemKind.DIR && "saves".equals(item.name())) {
            return sessionLockWarnings(path);
        }
        return List.of();
    }

    /**
     * 新增能力：列出含 {@code session.lock} 的存档，给界面/报告打警告标记。
     *
     * @param savesDir 老实例的 {@code saves} 目录
     * @return 警告文案列表（按存档名排序），目录不存在或没有命中时返回空列表
     */
    public static List<String> sessionLockWarnings(Path savesDir) {
        List<String> warnings = new ArrayList<>();
        if (!PathUtils.isDir(savesDir)) {
            return List.copyOf(warnings);
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PathUtils.lp(savesDir))) {
            for (Path entry : stream) {
                try {
                    if (Files.isDirectory(entry) && isSaveProbablyInUse(entry)) {
                        names.add(PathUtils.fileName(entry));
                    }
                } catch (RuntimeException ignored) {
                    // 单个条目读不了就跳过
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return List.copyOf(warnings);
        }
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            warnings.add(SESSION_LOCK_WARNING_FORMAT.formatted(name));
        }
        return List.copyOf(warnings);
    }

    /**
     * 这个存档是不是「可能正在被游戏使用」。
     *
     * <p>收紧原因：Minecraft 正常退出后会把 {@code session.lock} 留在存档里，
     * 原实现只要看到该文件就告警，等于几乎每个存档都报警（审查 F4）。现在按两个信号判断：</p>
     * <ol>
     *   <li><b>锁是否被持有</b>：像原版 {@code SessionLock} 一样尝试对文件加排他锁；
     *       拿不到锁 / 打不开（Windows 上常表示被游戏独占）→ 判定「正在使用」→ 告警；</li>
     *   <li><b>是否近期被写过</b>：{@code session.lock} 的修改时间在最近
     *       {@value #SESSION_LOCK_RECENT_SECONDS} 秒内 → 刚开过 / 刚关过，可能还在退出中 → 告警。</li>
     * </ol>
     * <p>两个信号都为否（例如几周前正常退出留下的锁文件）就不再告警。</p>
     */
    public static boolean isSaveProbablyInUse(Path saveDir) {
        if (saveDir == null) {
            return false;
        }
        Path lock = saveDir.resolve("session.lock");
        if (!PathUtils.isFile(lock)) {
            return false;
        }
        if (isSessionLockHeld(lock)) {
            return true;
        }
        try {
            long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(PathUtils.lp(lock)).toMillis();
            return ageMillis >= 0L && ageMillis <= SESSION_LOCK_RECENT_SECONDS * 1000L;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** 尝试对 session.lock 加排他锁：拿得到 → 没人在用；拿不到/打不开 → 视为正在被占用。 */
    private static boolean isSessionLockHeld(Path lockFile) {
        Path path = PathUtils.lp(lockFile);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                return lock == null;
            }
        } catch (OverlappingFileLockException ex) {
            return true;
        } catch (IOException | RuntimeException ex) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 体积估算
    // ------------------------------------------------------------------

    /** 对应 py 的 {@code quick_size(path)}（默认上限 3000 文件 / 0.5 秒）。 */
    public static QuickSize quickSize(Path path) {
        return quickSize(path, QUICK_SIZE_MAX_FILES, QUICK_SIZE_MAX_SECONDS);
    }

    /**
     * 对应 py 的 {@code quick_size(path, max_files, max_seconds)}：超限即中止，
     * 返回 {@code (字节, 文件数, 是否截断)}。
     */
    public static QuickSize quickSize(Path path, int maxFiles, double maxSeconds) {
        return quickSize(path, maxFiles, maxSeconds, Set.of());
    }

    /**
     * 同上，但可以排除若干「直接子目录名」不参与统计。
     *
     * <p>用途：非隔离根目录（{@code .minecraft}）里往往还放着整个 {@code versions\}，
     * 统计它的体积时必须跳过 {@code versions}，否则会把每个版本实例重复算一遍。</p>
     */
    public static QuickSize quickSize(Path path, int maxFiles, double maxSeconds,
                                      Set<String> excludedDirectoryNames) {
        if (PathUtils.isFile(path)) {
            return new QuickSize(CopyEngine.sizeOf(path), 1L, false);
        }
        long total = 0L;
        long count = 0L;
        boolean truncated = false;
        long started = System.nanoTime();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(PathUtils.lp(path));
        while (!stack.isEmpty()) {
            Path current = stack.pop();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                for (Path entry : stream) {
                    try {
                        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                            if (excludedDirectoryNames.contains(PathUtils.fileName(entry))) {
                                continue;
                            }
                            stack.push(entry);
                        } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            total += Files.size(entry);
                            count++;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // 单个条目读不了就跳过（py: continue）
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 该目录读不了就跳过（py: continue）
            }
            if (count >= maxFiles || (System.nanoTime() - started) / 1_000_000_000.0 > maxSeconds) {
                truncated = true;
                break;
            }
        }
        return new QuickSize(total, count, truncated);
    }

    /**
     * 对应 py 的 {@code size_note(path, kind)}：
     * <ul>
     *   <li>文件：{@code 文件，1.2 MB}</li>
     *   <li>文件夹截断：{@code 文件夹，≥ 1.2 GB（未扫完）}</li>
     *   <li>文件夹正常：{@code 文件夹，约 1.2 GB / 128 个文件}</li>
     *   <li>路径不存在/类型不符：只返回 {@code 文件夹} 或 {@code 文件}</li>
     * </ul>
     */
    public static String sizeNote(Path path, ItemKind kind) {
        String kindText = kind.label();
        if (kind == ItemKind.DIR && !PathUtils.isDir(path)) {
            return kindText;
        }
        if (kind == ItemKind.FILE && !PathUtils.isFile(path)) {
            return kindText;
        }
        return sizeNote(kind, quickSize(path));
    }

    /** 同上，但用现成的体积估算结果（界面可以缓存 quick_size 结果后再生成文案）。 */
    public static String sizeNote(ItemKind kind, QuickSize quick) {
        String kindText = kind.label();
        if (kind == ItemKind.FILE) {
            return "%s，%s".formatted(kindText, SizeFormatter.humanSize(quick.bytes()));
        }
        if (quick.truncated()) {
            return "%s，≥ %s（未扫完）".formatted(kindText, SizeFormatter.humanSize(quick.bytes()));
        }
        return "%s，约 %s / %d 个文件"
                .formatted(kindText, SizeFormatter.humanSize(quick.bytes()), quick.fileCount());
    }

    // ------------------------------------------------------------------
    // 全量统计
    // ------------------------------------------------------------------

    /** 对应 py 的 {@code count_items(root, items)}：一组条目的文件总数与总字节数。 */
    public static CountResult countItems(Path instanceRoot, List<MigrateItem> items) {
        long total = 0L;
        long size = 0L;
        for (MigrateItem item : items) {
            Path path = itemPath(instanceRoot, item);
            if (item.kind() == ItemKind.DIR) {
                CountResult counted = countFiles(path);
                total += counted.fileCount();
                size += counted.bytes();
            } else if (PathUtils.isFile(path)) {
                total += 1L;
                size += CopyEngine.sizeOf(path);
            }
        }
        return new CountResult(total, size);
    }

    /** 对应 py 的 {@code count_files(root)}：完整遍历（读不了的目录跳过）。 */
    public static CountResult countFiles(Path root) {
        long total = 0L;
        long size = 0L;
        Iterator<DirEntry> walker = CopyEngine.walkDir(root);
        while (walker.hasNext()) {
            DirEntry entry = walker.next();
            if (entry.hasError()) {
                continue;
            }
            Path dir = entry.relative().isEmpty() ? root : root.resolve(entry.relative());
            for (String name : entry.files()) {
                total++;
                size += CopyEngine.sizeOf(dir.resolve(name));
            }
        }
        return new CountResult(total, size);
    }

    // ------------------------------------------------------------------
    // 实例识别
    // ------------------------------------------------------------------

    /**
     * 对应 py 的 {@code looks_like_instance(path)}：25 项里命中几项（只看存在性，不看类型）。
     */
    public static int looksLikeInstance(Path path) {
        if (path == null || !PathUtils.isDir(path)) {
            return 0;
        }
        int score = 0;
        for (MigrateItem item : MigrateCatalog.ALL_ITEMS) {
            if (PathUtils.pathExists(itemPath(path, item))) {
                score++;
            }
        }
        return score;
    }

    /**
     * 对应 py 的 {@code normalize_instance_path(path)}：用户可能选了含 {@code .minecraft}
     * 的上一层目录，命中就自动下钻并给出提示。
     */
    public static NormalizedPath normalizeInstancePath(Path path) {
        if (path == null) {
            return new NormalizedPath(null, null);
        }
        if (looksLikeInstance(path) > 0) {
            return new NormalizedPath(path, null);
        }
        Path inner = path.resolve(".minecraft");
        if (PathUtils.isDir(inner) && looksLikeInstance(inner) > 0) {
            return new NormalizedPath(inner, DOWNGRADE_NOTICE_FORMAT.formatted(inner));
        }
        return new NormalizedPath(path, null);
    }
}
