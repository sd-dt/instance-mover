package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.util.PathUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 复制 / 合并覆盖引擎，逐条对应 mc_transfer.py v1.1 的
 * {@code files_identical()} / {@code copy_one_file()} / {@code copy_item()} / {@code walk_dir()}。
 *
 * <p>纯 Java，不依赖 Minecraft，可直接单测。</p>
 *
 * <p>关键语义（不许改）：</p>
 * <ul>
 *   <li>合并覆盖：同名文件覆盖，目标独有的文件保留不删；</li>
 *   <li>相同判定：先比大小与「秒级」修改时间，只有相等且 ≤ 4 MB 才按 256 KB 分块比内容；
 *       超过 4 MB 一律不跳过（宁可重写，这是 v1.1 修过的真实缺陷）；</li>
 *   <li>类型冲突：源是文件夹而目标是文件 → 删掉目标文件按文件夹转移；
 *       源是文件而目标是文件夹 → 删掉目标文件夹按文件转移；删除失败计 failed 并中止该项；</li>
 *   <li>覆盖前先清目标只读属性；任何单个文件失败只记进报告、不中断整体流程；</li>
 *   <li>每个文件 / 每个目录之前检查取消标志，命中抛 {@link TransferCancelled}。</li>
 * </ul>
 *
 * <p>相对 py 的增强：写盘走「同目录临时文件 + 原子替换」，崩溃不会留下半截文件
 * （统计语义与跳过语义与 py 完全一致）。</p>
 */
public final class CopyEngine {

    /** 4 MB：只有不超过这个大小的文件才做内容比对，超过一律不跳过（py: VERIFY_LIMIT）。 */
    public static final int VERIFY_LIMIT = 4 * 1024 * 1024;

    /** 内容比对的块大小（py: 256 * 1024）。 */
    public static final int CHUNK_BYTES = 256 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger("instance_mover");

    private final BooleanSupplier cancelFlag;
    private final Consumer<String> log;
    private final Runnable onFile;

    /**
     * @param cancelFlag 取消标志（可为 {@code null}，表示不可取消）
     * @param log        日志回调（可为 {@code null}）；对应 py 的 {@code on_log}
     * @param onFile     进度回调，**每个文件处理完之后**调用一次（相同跳过、失败也算），
     *                   可为 {@code null}；对应 py 的 {@code on_file}
     */
    public CopyEngine(BooleanSupplier cancelFlag, Consumer<String> log, Runnable onFile) {
        this.cancelFlag = cancelFlag;
        this.log = log;
        this.onFile = onFile;
    }

    /** 只带取消标志的静默引擎（备份等场景用）。 */
    public static CopyEngine silent(BooleanSupplier cancelFlag) {
        return new CopyEngine(cancelFlag, null, null);
    }

    /** 当前是否已被取消。 */
    public boolean isCancelled() {
        return cancelFlag != null && cancelFlag.getAsBoolean();
    }

    /** 对应 py 里随处可见的 {@code if cancel.is_set(): raise TransferCancelled()}。 */
    public void checkCancelled() throws TransferCancelled {
        if (isCancelled()) {
            throw new TransferCancelled();
        }
    }

    private void fireOnFile() {
        if (onFile != null) {
            onFile.run();
        }
    }

    private void fireLog(String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    // ------------------------------------------------------------------
    // 目录遍历
    // ------------------------------------------------------------------

    /** 对应 py 的 {@code walk_dir(root)}：惰性深度优先遍历，产出每个目录的 (相对路径, 文件名, 错误)。 */
    public static Iterator<DirEntry> walkDir(Path root) {
        return new DirWalker(root);
    }

    private static final class DirWalker implements Iterator<DirEntry> {

        private final Path rootLp;
        private final Deque<String> stack = new ArrayDeque<>();
        private DirEntry next;

        DirWalker(Path root) {
            this.rootLp = PathUtils.lp(root);
            this.stack.push("");
            advance();
        }

        private void advance() {
            next = null;
            while (!stack.isEmpty()) {
                String relative = stack.pop();
                Path current = relative.isEmpty() ? rootLp : rootLp.resolve(relative);
                List<String> files = new ArrayList<>();
                List<String> dirs = new ArrayList<>();
                String error = null;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                    for (Path entry : stream) {
                        try {
                            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                                dirs.add(PathUtils.fileName(entry));
                            } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                                files.add(PathUtils.fileName(entry));
                            }
                            // 符号链接：py 用 follow_symlinks=False，既不算目录也不算文件 → 跳过
                        } catch (RuntimeException ignored) {
                            // 单个条目读不了就跳过
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    error = ex.toString();
                }
                Collections.sort(files);
                Collections.sort(dirs);
                for (String dir : dirs) {
                    stack.push(relative.isEmpty() ? dir : relative + java.io.File.separator + dir);
                }
                next = new DirEntry(relative, List.copyOf(files), error);
                return;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public DirEntry next() {
            if (next == null) {
                throw new java.util.NoSuchElementException("walkDir 已经遍历完毕");
            }
            DirEntry current = next;
            advance();
            return current;
        }
    }

    // ------------------------------------------------------------------
    // 相同判定 / 单文件复制
    // ------------------------------------------------------------------

    /**
     * 对应 py 的 {@code files_identical(a, b)}：
     * 先比大小与秒级修改时间，不相等直接 false；≤ 4 MB 才按 256 KB 分块比内容；&gt; 4 MB 一律 false。
     */
    public static boolean filesIdentical(Path a, Path b) {
        try {
            Path left = PathUtils.lp(a);
            Path right = PathUtils.lp(b);
            long sizeA = Files.size(left);
            long sizeB = Files.size(right);
            if (sizeA != sizeB) {
                return false;
            }
            long timeA = Files.getLastModifiedTime(left).to(TimeUnit.SECONDS);
            long timeB = Files.getLastModifiedTime(right).to(TimeUnit.SECONDS);
            if (timeA != timeB) {
                return false;
            }
            if (sizeA > VERIFY_LIMIT) {
                return false;
            }
            try (InputStream inA = Files.newInputStream(left); InputStream inB = Files.newInputStream(right)) {
                byte[] bufferA = new byte[CHUNK_BYTES];
                byte[] bufferB = new byte[CHUNK_BYTES];
                while (true) {
                    int readA = readFully(inA, bufferA);
                    int readB = readFully(inB, bufferB);
                    if (readA != readB) {
                        return false;
                    }
                    if (readA <= 0) {
                        return true;
                    }
                    if (!Arrays.equals(bufferA, 0, readA, bufferB, 0, readB)) {
                        return false;
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == 0 ? -1 : total;
    }

    /** 对应 py 的 {@code _size_of(p)}：读不到返回 0。 */
    public static long sizeOf(Path path) {
        try {
            return Files.size(PathUtils.lp(path));
        } catch (IOException | RuntimeException ex) {
            return 0L;
        }
    }

    /**
     * 对应 py 的 {@code copy_one_file()}：目标已存在则覆盖（相同则跳过），不存在则建父目录后复制。
     *
     * <p>写盘走「同目录临时文件 + 原子替换」，并保留源文件的修改时间（保证「相同判定」幂等）。</p>
     * <p>异常只记进 {@code stats}，不抛出（对应 py 的 {@code except Exception}）。</p>
     */
    public void copyOneFile(Path src, Path dst, TransferStats stats, boolean skipSame) {
        try {
            if (PathUtils.pathExists(dst)) {
                if (skipSame && filesIdentical(src, dst)) {
                    stats.incSkipped();
                    fireOnFile();
                    return;
                }
                PathUtils.forceWritable(dst);
                copyPreservingAttributes(src, dst);
                stats.incOverwritten(sizeOf(src));
            } else {
                Path parent = dst.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(PathUtils.lp(parent));
                }
                copyPreservingAttributes(src, dst);
                stats.incCreated(sizeOf(src));
            }
            fireOnFile();
        } catch (Exception ex) {
            stats.incFailed();
            stats.addError("复制失败：%s → %s（%s）".formatted(src, dst, ex));
            LOGGER.warn("复制失败：{} → {}（{}）", src, dst, ex.toString());
            fireOnFile();
        }
    }

    /**
     * 把 {@code src} 复制到 {@code dst}：先写同目录临时文件，再 {@code Files.move} 原子替换，
     * 并保留源文件的最后修改时间。
     */
    public static void copyPreservingAttributes(Path src, Path dst) throws IOException {
        Path source = PathUtils.lp(src);
        Path target = PathUtils.lp(dst);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("目标路径没有父目录：" + dst);
        }
        Path temp = Files.createTempFile(parent, ".instance_mover_", ".part");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                FileTime modified = Files.getLastModifiedTime(source);
                Files.setLastModifiedTime(temp, modified);
            } catch (IOException ignored) {
                // 保时间戳失败不影响内容正确性，只是下次不会走「相同跳过」
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件残留不影响结果
            }
        }
    }

    // ------------------------------------------------------------------
    // 条目级合并
    // ------------------------------------------------------------------

    /**
     * 对应 py 的 {@code copy_item()}：把源条目（文件或文件夹）合并复制到目标条目。
     *
     * <p>源不存在时直接跳过（不产生空目录、不动目标）——这是「缺失项安全跳过」的落地方式。</p>
     */
    public void copyItem(Path srcItem, Path dstItem, TransferStats stats, boolean skipSame)
            throws TransferCancelled {
        checkCancelled();

        boolean sourceIsDir = PathUtils.isDir(srcItem);
        boolean sourceIsFile = !sourceIsDir && PathUtils.isFile(srcItem);
        if (!sourceIsDir && !sourceIsFile) {
            // 缺失项：安全跳过（py 会对不存在的源尝试复制并记 failed，这里更安全：不碰目标）
            return;
        }

        if (sourceIsDir) {
            if (PathUtils.isFile(dstItem)) {
                try {
                    Files.delete(PathUtils.lp(dstItem));
                    fireLog("提示：目标位置 %s 原本是文件，已删除后按文件夹转移。".formatted(dstItem));
                } catch (IOException | RuntimeException ex) {
                    stats.incFailed();
                    stats.addError("无法删除同名文件：%s（%s）".formatted(dstItem, ex));
                    return;
                }
            }
            createDirectories(dstItem);
            Iterator<DirEntry> walker = walkDir(srcItem);
            while (walker.hasNext()) {
                checkCancelled();
                DirEntry entry = walker.next();
                if (entry.hasError()) {
                    stats.incFailed();
                    Path bad = entry.relative().isEmpty() ? srcItem : srcItem.resolve(entry.relative());
                    String message = "读取文件夹失败：%s（%s）".formatted(bad, entry.error());
                    stats.addError(message);
                    fireLog(message);
                    continue;
                }
                if (!entry.relative().isEmpty()) {
                    Path targetDir = dstItem.resolve(entry.relative());
                    try {
                        Files.createDirectories(PathUtils.lp(targetDir));
                    } catch (IOException | RuntimeException ex) {
                        stats.incFailed();
                        stats.addError("创建文件夹失败：%s（%s）".formatted(targetDir, ex));
                        continue;
                    }
                }
                for (String name : entry.files()) {
                    checkCancelled();
                    Path source = entry.relative().isEmpty()
                            ? srcItem.resolve(name)
                            : srcItem.resolve(entry.relative()).resolve(name);
                    Path target = entry.relative().isEmpty()
                            ? dstItem.resolve(name)
                            : dstItem.resolve(entry.relative()).resolve(name);
                    copyOneFile(source, target, stats, skipSame);
                }
            }
        } else {
            if (PathUtils.isDir(dstItem)) {
                try {
                    PathUtils.deleteRecursively(dstItem);
                    fireLog("提示：目标位置 %s 原本是文件夹，已删除后按文件转移。".formatted(dstItem));
                } catch (IOException | RuntimeException ex) {
                    stats.incFailed();
                    stats.addError("无法删除同名文件夹：%s（%s）".formatted(dstItem, ex));
                    return;
                }
            }
            copyOneFile(srcItem, dstItem, stats, skipSame);
        }
    }

    private void createDirectories(Path dir) {
        try {
            Files.createDirectories(PathUtils.lp(dir));
        } catch (IOException | RuntimeException ex) {
            // 建目录失败不强中断（比 py 更稳）：随后的文件复制会各自记一条「复制失败」
            fireLog("创建文件夹失败：%s（%s）".formatted(dir, ex));
        }
    }
}
