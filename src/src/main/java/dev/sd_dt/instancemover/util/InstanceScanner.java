package dev.sd_dt.instancemover.util;

import dev.sd_dt.instancemover.engine.NormalizedPath;
import dev.sd_dt.instancemover.engine.QuickSize;
import dev.sd_dt.instancemover.engine.ScanService;
import dev.sd_dt.instancemover.model.ItemKind;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 老实例自动探测：扫描当前实例所在的 {@code versions\} 隔离目录，列出所有版本实例
 * （以及非隔离的 {@code .minecraft} 根目录本身），供界面下拉选择；同时提供手填路径的校验。
 *
 * <p>纯 Java，不依赖 Minecraft，可直接单测。体积估算沿用
 * {@link ScanService#quickSize(Path, int, double)}：**超过 3000 个文件或 0.5 秒即中止并标记截断**，
 * 绝不把界面卡住（界面建议仍在后台线程调用）。</p>
 *
 * <p>分层说明：py 的 {@code looks_like_instance()} / {@code normalize_instance_path()} /
 * {@code quick_size()} / {@code size_note()} 这几个原语在 {@code engine.ScanService} 里实现
 * （M1 已交付并有单测），本类只做「按版本目录批量探测 + 候选封装」，不重复实现一遍。</p>
 */
public final class InstanceScanner {

    /** 版本隔离目录名（PCL2 / HMCL / 官方启动器都用它）。 */
    public static final String VERSIONS_DIR_NAME = "versions";

    /** 非隔离实例根目录的常见名字。 */
    public static final String DOT_MINECRAFT = ".minecraft";

    /** 每个候选的体积估算文件数上限（py: max_files=3000）。 */
    public static final int MAX_FILES_PER_CANDIDATE = ScanService.QUICK_SIZE_MAX_FILES;

    /** 每个候选的体积估算秒数上限（py: max_seconds=0.5）。 */
    public static final double MAX_SECONDS_PER_CANDIDATE = ScanService.QUICK_SIZE_MAX_SECONDS;

    private InstanceScanner() {
    }

    /**
     * 一个候选实例（界面下拉项 / 手填校验结果的统一载体）。
     *
     * @param path           实例根目录（已规整为绝对路径）
     * @param label          显示名：版本文件夹名（非隔离根目录就是它的目录名，例如 {@code .minecraft}）
     * @param isolated       是否位于 {@code versions\<名>\} 下（版本隔离）
     * @param current        是否就是当前实例自身（是的话不可选）
     * @param hasSaves       是否存在 {@code saves} 文件夹
     * @param hasConfig      是否存在 {@code config} 文件夹
     * @param savesCount     {@code saves} 下的存档个数（只数一层子目录）
     * @param configCount    {@code config} 下的条目个数（只数一层）
     * @param looksLikeScore 25 项清单里的命中数（py 的 {@code looks_like_instance()}）
     * @param bytes          体积估算（可能被截断）
     * @param fileCount      文件数估算（可能被截断）
     * @param sizeTruncated  体积估算是否未扫完
     * @param sizeNote       体积说明，例如 {@code 文件夹，约 1.2 GB / 128 个文件}
     * @param warnings       警告标记（例如存档含 session.lock）
     * @param rejectReason   不可用原因（中文）；可用时为 {@code null}
     * @param notice         自动下钻提示（选了含 .minecraft 的上一层时）；未下钻为 {@code null}
     */
    public record InstanceCandidate(
            Path path,
            String label,
            boolean isolated,
            boolean current,
            boolean hasSaves,
            boolean hasConfig,
            int savesCount,
            int configCount,
            int looksLikeScore,
            long bytes,
            long fileCount,
            boolean sizeTruncated,
            String sizeNote,
            List<String> warnings,
            String rejectReason,
            String notice) {

        public InstanceCandidate {
            warnings = List.copyOf(warnings);
        }

        /** 路径本身是否像一个可选的老实例（不看「是不是当前实例」）。 */
        public boolean usable() {
            return rejectReason == null;
        }

        /** 能否被勾选为老实例：既可用、又不是当前实例自身。 */
        public boolean selectable() {
            return usable() && !current;
        }

        /** 显示用的来源：{@code versions\名字} 或 {@code .}（非隔离根目录）。 */
        public String relativeOrigin() {
            return isolated ? VERSIONS_DIR_NAME + "\\" + label : ".";
        }

        /**
         * 界面「状态」一行的中文摘要，对齐 工作流.md 的线框：
         * {@code ✔ 含 saves 3 个、config 42 项、共 1.8 GB} / {@code ✘ 没有找到 saves 或 config}。
         *
         * <p>非隔离根目录的体积口径要说清楚：统计时**跳过了 {@code versions\} 子树**（O1），
         * 否则每个版本实例都会被重复算一遍，这里补一句提示。</p>
         */
        public String statusText() {
            if (!usable()) {
                return "✘ " + rejectReason;
            }
            String suffix = isolated ? "" : "（体积不含 versions\\）";
            return "✔ 含 saves %d 个、config %d 项、共 %s%s"
                    .formatted(savesCount, configCount, sizeNote, suffix);
        }

        /**
         * 下拉项文案：版本名（来源路径，是否有存档，体积）。
         * 例：{@code 老整合包-26.1（versions\老整合包-26.1，1 个存档，1.8 GB）}。
         */
        public String dropdownText() {
            String origin = isolated ? relativeOrigin() : "根目录（非隔离）";
            String saves = hasSaves ? "%d 个存档".formatted(savesCount) : "无存档";
            String size = SizeFormatter.humanSize(bytes) + (sizeTruncated ? "（未扫完）" : "");
            if (!isolated) {
                size = size + "，不含 versions\\";
            }
            return "%s（%s，%s，%s）".formatted(label, origin, saves, size);
        }
    }

    // ------------------------------------------------------------------
    // 自动探测
    // ------------------------------------------------------------------

    /** 探测当前实例旁边的所有版本实例（排除当前实例自身，每候选最多 3000 文件 / 0.5 秒）。 */
    public static List<InstanceCandidate> scan(Path currentInstance) {
        return scan(currentInstance, false, MAX_FILES_PER_CANDIDATE, MAX_SECONDS_PER_CANDIDATE);
    }

    /** 同上，{@code includeCurrent=true} 时把当前实例本身也放进结果（{@code current()==true}）。 */
    public static List<InstanceCandidate> scan(Path currentInstance, boolean includeCurrent) {
        return scan(currentInstance, includeCurrent, MAX_FILES_PER_CANDIDATE, MAX_SECONDS_PER_CANDIDATE);
    }

    /** 同上，可自定义体积估算上限（单测用）。 */
    public static List<InstanceCandidate> scan(Path currentInstance,
                                               boolean includeCurrent,
                                               int maxFiles,
                                               double maxSeconds) {
        List<InstanceCandidate> candidates = new ArrayList<>();
        if (currentInstance == null) {
            return List.copyOf(candidates);
        }
        Path current = absolute(currentInstance);
        if (current == null) {
            return List.copyOf(candidates);
        }

        Optional<Path> sharedRoot = sharedRootOf(current);
        Optional<Path> versionsDir = versionsDirOf(current);

        // ① 非隔离的 .minecraft 根目录本身
        if (sharedRoot.isPresent() && PathUtils.isDir(sharedRoot.get())) {
            InstanceCandidate candidate = build(sharedRoot.get(), false, current, maxFiles, maxSeconds);
            if (candidate.selectable() || (includeCurrent && candidate.usable() && candidate.current())) {
                candidates.add(candidate);
            }
        }

        // ② versions\ 下的每个版本实例
        if (versionsDir.isPresent()) {
            for (Path versionDir : listDirectories(versionsDir.get())) {
                InstanceCandidate candidate = build(versionDir, true, current, maxFiles, maxSeconds);
                if (candidate.selectable() || (includeCurrent && candidate.usable() && candidate.current())) {
                    candidates.add(candidate);
                }
            }
        }

        // 最像「老整合包」的排前面：有存档 > 有配置 > 名字
        candidates.sort(Comparator
                .comparing(InstanceCandidate::hasSaves).reversed()
                .thenComparing(Comparator.comparing(InstanceCandidate::hasConfig).reversed())
                .thenComparing(InstanceCandidate::label));
        return List.copyOf(candidates);
    }

    /** 单个路径的探测（手填 / 粘贴路径用）：存在性、saves/config、体积、是否当前实例、自动下钻。 */
    public static InstanceCandidate inspect(Path candidate, Path currentInstance) {
        return inspect(candidate, currentInstance, MAX_FILES_PER_CANDIDATE, MAX_SECONDS_PER_CANDIDATE);
    }

    /** 同上，可自定义体积估算上限（单测用）。 */
    public static InstanceCandidate inspect(Path candidate,
                                            Path currentInstance,
                                            int maxFiles,
                                            double maxSeconds) {
        if (candidate == null) {
            return new InstanceCandidate(null, "", false, false, false, false, 0, 0, 0, 0L, 0L,
                    false, "文件夹", List.of(), "目录不存在或不是文件夹", null);
        }
        // py 的 normalize_instance_path()：选了含 .minecraft 的上一层时自动下钻
        NormalizedPath normalized = ScanService.normalizeInstancePath(candidate);
        Path path = normalized.path();
        InstanceCandidate built = build(path, isIsolated(path), absolute(currentInstance), maxFiles, maxSeconds);
        if (normalized.changed()) {
            return new InstanceCandidate(built.path(), built.label(), built.isolated(), built.current(),
                    built.hasSaves(), built.hasConfig(), built.savesCount(), built.configCount(),
                    built.looksLikeScore(), built.bytes(), built.fileCount(), built.sizeTruncated(),
                    built.sizeNote(), built.warnings(), built.rejectReason(), normalized.notice());
        }
        return built;
    }

    // ------------------------------------------------------------------
    // 目录定位
    // ------------------------------------------------------------------

    /** 当前实例旁边的 {@code versions\} 目录（不存在或找不到时为空）。 */
    public static Optional<Path> versionsDirOf(Path currentInstance) {
        if (currentInstance == null) {
            return Optional.empty();
        }
        Path current = absolute(currentInstance);
        if (current == null) {
            return Optional.empty();
        }
        if (VERSIONS_DIR_NAME.equals(PathUtils.fileName(current)) && PathUtils.isDir(current)) {
            return Optional.of(current);
        }
        Path parent = current.getParent();
        if (parent != null && VERSIONS_DIR_NAME.equals(PathUtils.fileName(parent))) {
            return Optional.of(parent);
        }
        Path nested = current.resolve(VERSIONS_DIR_NAME);
        if (PathUtils.isDir(nested)) {
            return Optional.of(nested);
        }
        return Optional.empty();
    }

    /**
     * 当前实例对应的非隔离根目录（{@code .minecraft} 那层）。
     *
     * <p>当前实例在 {@code versions\<名>\} 下 → 根目录是 {@code versions\} 的上一层；
     * 当前实例本身就是根目录 → 返回它自己（通常等于当前实例，会被排除）。</p>
     */
    public static Optional<Path> sharedRootOf(Path currentInstance) {
        if (currentInstance == null) {
            return Optional.empty();
        }
        Path current = absolute(currentInstance);
        if (current == null) {
            return Optional.empty();
        }
        if (VERSIONS_DIR_NAME.equals(PathUtils.fileName(current))) {
            return Optional.ofNullable(current.getParent());
        }
        Path parent = current.getParent();
        if (parent != null && VERSIONS_DIR_NAME.equals(PathUtils.fileName(parent))) {
            return Optional.ofNullable(parent.getParent());
        }
        if (PathUtils.isDir(current.resolve(VERSIONS_DIR_NAME))) {
            return Optional.of(current);
        }
        return Optional.empty();
    }

    /**
     * py 的 {@code looks_like_instance(path)}：对全部 25 项清单打分，&gt;0 即像一个实例。
     *
     * <p>实现在 {@link ScanService#looksLikeInstance(Path)}（M1 已交付并有单测），这里直接转发，
     * 方便界面只 import 一个类。</p>
     */
    public static int looksLikeInstance(Path path) {
        return ScanService.looksLikeInstance(path);
    }

    /**
     * py 的 {@code normalize_instance_path(path)}：用户选了含 {@code .minecraft} 的上一层目录时
     * 自动下钻一层并返回提示。
     *
     * <p>实现在 {@link ScanService#normalizeInstancePath(Path)}，这里直接转发。</p>
     */
    public static NormalizedPath normalizeInstancePath(Path path) {
        return ScanService.normalizeInstancePath(path);
    }

    /** 某个路径是不是「版本隔离实例」（在 versions\ 下一层）。 */
    public static boolean isIsolated(Path path) {
        if (path == null) {
            return false;
        }
        Path absolute = absolute(path);
        if (absolute == null) {
            return false;
        }
        Path parent = absolute.getParent();
        if (parent == null) {
            return false;
        }
        return VERSIONS_DIR_NAME.equals(PathUtils.fileName(parent))
                && !VERSIONS_DIR_NAME.equals(PathUtils.fileName(absolute));
    }

    /** 两个路径是否指向同一个目录（先比规整路径，再比真实路径）。 */
    public static boolean isSameInstance(Path a, Path b) {
        Path left = absolute(a);
        Path right = absolute(b);
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        try {
            return Files.isSameFile(PathUtils.lp(left), PathUtils.lp(right));
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static InstanceCandidate build(Path rawPath, boolean isolated, Path currentInstance,
                                           int maxFiles, double maxSeconds) {
        Path path = absolute(rawPath);
        boolean isDir = path != null && PathUtils.isDir(path);
        Path saves = isDir ? path.resolve("saves") : null;
        Path config = isDir ? path.resolve("config") : null;
        boolean hasSaves = saves != null && PathUtils.isDir(saves);
        boolean hasConfig = config != null && PathUtils.isDir(config);
        int score = isDir ? ScanService.looksLikeInstance(path) : 0;
        boolean isCurrent = isDir && currentInstance != null && isSameInstance(path, currentInstance);

        // 非隔离根目录里通常还有整个 versions\，统计它的体积时必须跳过，否则每个版本实例会被重复算一遍
        Set<String> excluded = isolated ? Set.of() : Set.of(VERSIONS_DIR_NAME);
        QuickSize quick = isDir
                ? ScanService.quickSize(path, maxFiles, maxSeconds, excluded)
                : new QuickSize(0L, 0L, false);
        int savesCount = hasSaves ? countChildren(saves, true) : 0;
        int configCount = hasConfig ? countChildren(config, false) : 0;
        List<String> warnings = hasSaves ? ScanService.sessionLockWarnings(saves) : List.of();

        // 准入规则：必须真的含 saves 或 config（只有 mods 的版本目录不算「能搬家的老实例」）
        String rejectReason = null;
        if (!isDir) {
            rejectReason = "目录不存在或不是文件夹";
        } else if (!hasSaves && !hasConfig) {
            rejectReason = "没有找到 saves 或 config，不像一个游戏实例";
        }

        return new InstanceCandidate(path, path == null ? "" : PathUtils.fileName(path), isolated, isCurrent,
                hasSaves, hasConfig, savesCount, configCount, score, quick.bytes(), quick.fileCount(),
                quick.truncated(), ScanService.sizeNote(ItemKind.DIR, quick), warnings, rejectReason, null);
    }

    private static int countChildren(Path dir, boolean directoriesOnly) {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PathUtils.lp(dir))) {
            for (Path entry : stream) {
                if (!directoriesOnly || Files.isDirectory(entry)) {
                    count++;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return 0;
        }
        return count;
    }

    private static List<Path> listDirectories(Path dir) {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PathUtils.lp(dir))) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    dirs.add(entry);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return List.copyOf(dirs);
        }
        dirs.sort(Comparator.comparing(PathUtils::fileName));
        return List.copyOf(dirs);
    }

    private static Path absolute(Path path) {
        if (path == null) {
            return null;
        }
        return path.toAbsolutePath().normalize();
    }
}
