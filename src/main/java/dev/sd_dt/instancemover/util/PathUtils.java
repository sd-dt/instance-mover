package dev.sd_dt.instancemover.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 路径工具，逐条对应 mc_transfer.py v1.1 的 {@code lp()} / {@code path_exists()} /
 * {@code is_dir()} / {@code is_file()} / {@code _force_writable()}。
 *
 * <p><b>关于长路径（据实说明，2026-10 本机实测）</b>：py 的 {@code lp()} 会在 Windows 下给
 * ≥ 230 字符的路径加 {@code \\?\} 前缀来绕开 260 字符限制。本类照抄了这套语义（纯字符串函数，
 * 便于与 py 对齐、也便于单测），但**必须知道**：在 JDK 25 上，
 * {@code Path.of("\\\\?\\C:\\...")} 与 {@code new File("\\\\?\\C:\\...")} 都会把这个前缀
 * <b>归一化掉</b>，所以前缀并不会真的进入 {@link Path} 对象。真正让长路径能用的是 JDK/系统的
 * 长路径能力：本机实测（{@code LongPathsEnabled=0}、java.exe 无 longPathAware 清单）用**裸路径**
 * 依然可以创建、写入并读回 284 字符的深路径（见 {@code PathUtilsLongPathTest}）。
 * 因此这里的 {@link #lp(Path)} 主要是与 py 对齐 + 统一入口，**不要**指望它自己解决 260 限制。</p>
 */
public final class PathUtils {

    /** py 里的阈值：`if len(p) < 230: return p`。 */
    public static final int LONG_PATH_THRESHOLD = 230;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private PathUtils() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /**
     * 对应 py 的 {@code lp(path)}：非 Windows 原样返回；Windows 下返回绝对路径，
     * 长度 ≥ {@value #LONG_PATH_THRESHOLD} 时加 {@code \\?\} 前缀（UNC 用 {@code \\?\UNC\}）。
     *
     * <p>返回的是**字符串**（纯函数，便于单测）。注意：把它的结果再交给
     * {@link Path#of(String)} / {@link File} 时，JDK 会把 {@code \\?\} 前缀归一化掉
     * —— 见类注释里的实测说明。</p>
     */
    public static String extendedPathString(String path) {
        if (!WINDOWS || path == null || path.isEmpty()) {
            return path;
        }
        if (path.startsWith("\\\\?\\")) {
            return path;
        }
        String absolute;
        try {
            absolute = Path.of(path).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException ex) {
            return path;
        }
        if (absolute.startsWith("\\\\?\\")) {
            return absolute;
        }
        if (absolute.length() < LONG_PATH_THRESHOLD) {
            return absolute;
        }
        if (absolute.startsWith("\\\\")) {
            return "\\\\?\\UNC\\" + absolute.substring(2);
        }
        return "\\\\?\\" + absolute;
    }

    /** 对应 py 的 {@code lp()}，但返回 {@link Path}。 */
    public static Path lp(Path path) {
        if (path == null) {
            return null;
        }
        String extended = extendedPathString(path.toString());
        if (extended.equals(path.toString())) {
            return path;
        }
        try {
            return Path.of(extended);
        } catch (InvalidPathException ex) {
            return path;
        }
    }

    /** 对应 py 的 {@code path_exists()}。 */
    public static boolean pathExists(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.exists(lp(path));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 对应 py 的 {@code is_dir()}（跟随符号链接）。 */
    public static boolean isDir(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.isDirectory(lp(path));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 对应 py 的 {@code is_file()}（跟随符号链接）。 */
    public static boolean isFile(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.isRegularFile(lp(path));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 对应 py 的 {@code _force_writable()}：清掉目标的只读属性，失败静默忽略。
     *
     * <p>Windows 走 DOS 属性视图（{@code dos:readonly}），其他平台兜底用
     * {@link File#setWritable(boolean, boolean)}。</p>
     */
    public static void forceWritable(Path path) {
        if (path == null) {
            return;
        }
        Path target = lp(path);
        try {
            Files.setAttribute(target, "dos:readonly", false);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // 非 Windows 或权限不足：交给下面的兜底，静默忽略
        }
        try {
            File file = target.toFile();
            if (file.exists()) {
                file.setWritable(true, false);
            }
        } catch (RuntimeException ignored) {
            // 静默忽略
        }
    }

    /** 目录条目是否真实存在（不跟随符号链接）。 */
    public static boolean existsNoFollow(Path path) {
        return path != null && Files.exists(lp(path), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 对应 py 的 {@code shutil.rmtree()}：递归删除（不跟随符号链接）。
     *
     * <p>遇到只读文件先清只读属性再删一次。</p>
     */
    public static void deleteRecursively(Path root) throws IOException {
        Path target = lp(root);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            deleteOne(target);
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(target)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path each : paths) {
            deleteOne(each);
        }
    }

    private static void deleteOne(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (AccessDeniedException ex) {
            forceWritable(path);
            Files.deleteIfExists(path);
        }
    }

    /**
     * 源与目标是否相同或互为父子（用于拒绝自我迁移）。
     */
    public static boolean sameOrNested(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        Path left = a.toAbsolutePath().normalize();
        Path right = b.toAbsolutePath().normalize();
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    /** 目录名（用于日志），空路径返回 {@code ""}。 */
    public static String fileName(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * 去掉 Windows 的 {@code \\?\} / {@code \\?\UNC\} 长路径前缀，返回可用于比较的规整绝对路径。
     */
    public static Path withoutLongPathPrefix(Path path) {
        if (path == null) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        String text = absolute.toString();
        if (!WINDOWS) {
            return absolute;
        }
        if (text.startsWith("\\\\?\\UNC\\")) {
            text = "\\\\" + text.substring("\\\\?\\UNC\\".length());
        } else if (text.startsWith("\\\\?\\")) {
            text = text.substring("\\\\?\\".length());
        }
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            return absolute;
        }
    }

    /**
     * 两个路径是否指向**同一个目录**：容忍 {@code \\?\} 长路径前缀写法、Windows 上的大小写差异，
     * 以及符号链接/短文件名等文件系统层面的等价形式（{@link Files#isSameFile}）。
     */
    public static boolean sameDirectory(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        Path left = withoutLongPathPrefix(a);
        Path right = withoutLongPathPrefix(b);
        if (left.equals(right) || left.toString().equalsIgnoreCase(right.toString())) {
            return true;
        }
        Path lpLeft = lp(left);
        Path lpRight = lp(right);
        if (lpLeft.toString().equalsIgnoreCase(lpRight.toString())) {
            return true;
        }
        try {
            return Files.isSameFile(lpLeft, lpRight);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * {@code child} 是否位于 {@code ancestor} **内部**（不比相等）。
     *
     * <p>逐段比较，Windows 上大小写不敏感；两边都先去掉 {@code \\?\} 前缀，避免同一个目录
     * 因为前缀写法不同而比较失败。</p>
     *
     * <p><b>注意</b>：这是纯字符串判定，**不认识别名路径**（junction / 符号链接 / 8.3 短名）。
     * 需要防别名时用 {@link #isRealAncestorOf(Path, Path)}。</p>
     */
    public static boolean isInside(Path child, Path ancestor) {
        if (child == null || ancestor == null) {
            return false;
        }
        Path inner = withoutLongPathPrefix(child);
        Path outer = withoutLongPathPrefix(ancestor);
        if (inner.getNameCount() <= outer.getNameCount()) {
            return false;
        }
        if (!Objects.equals(inner.getRoot(), outer.getRoot())) {
            return false;
        }
        for (int index = 0; index < outer.getNameCount(); index++) {
            if (!nameEquals(outer.getName(index), inner.getName(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean nameEquals(Path left, Path right) {
        String a = left.toString();
        String b = right.toString();
        return a.equals(b) || (WINDOWS && a.equalsIgnoreCase(b));
    }

    /**
     * {@code child} 是否**真的**位于 {@code ancestor} 内部（不含相等），能识破别名路径
     * （junction / 符号链接 / 8.3 短名）。
     *
     * <p>两道保险：</p>
     * <ol>
     *   <li>两侧都取真实路径（{@link Path#toRealPath()}）后直接判断包含关系；</li>
     *   <li>再从 {@code child} 的各级父目录自下往上，用 {@link #sameDirectory(Path, Path)}
     *       （内部是 {@link Files#isSameFile}）与 {@code ancestor} 比较。</li>
     * </ol>
     *
     * <p>为什么需要它：{@link #isInside(Path, Path)} 只比字符串，遇到
     * 「{@code D:\alias} 是指向 {@code D:\mc\config} 的 junction、源是 {@code D:\alias\backup}」
     * 这类写法会漏判（首段就不同），于是整棵源树会被复制进它自己的子树。代价是每层一次
     * syscall；调用方按条目调用（目录层级个位数），开销可接受。</p>
     */
    public static boolean isRealAncestorOf(Path ancestor, Path child) {
        if (ancestor == null || child == null) {
            return false;
        }
        Path ancestorAbs = ancestor.toAbsolutePath().normalize();
        Path childAbs = child.toAbsolutePath().normalize();

        // ① 真实路径直接比对：能同时解析两侧的 junction / 符号链接 / 8.3 短名
        try {
            Path realChild = childAbs.toRealPath();
            Path realAncestor = ancestorAbs.toRealPath();
            if (!realChild.equals(realAncestor) && realChild.startsWith(realAncestor)) {
                return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // 任一侧不存在（例如目标条目目录还没建）→ 交给下面的逐级比较
        }

        // ② 从 child 的各级父目录向上，用 sameDirectory（含 Files.isSameFile）比对
        Path probe = childAbs.getParent();
        while (probe != null) {
            if (sameDirectory(probe, ancestorAbs)) {
                return true;
            }
            probe = probe.getParent();
        }
        return false;
    }
}
