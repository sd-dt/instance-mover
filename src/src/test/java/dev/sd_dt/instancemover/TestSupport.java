package dev.sd_dt.instancemover;

import dev.sd_dt.instancemover.util.PathUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

/** 单测公用的小工具：建文件、读内容、改时间戳、清只读属性（文件操作统一走 PathUtils.lp）。 */
public final class TestSupport {

    private TestSupport() {
    }

    public static void write(Path file, String text) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(PathUtils.lp(file).getParent());
            }
            Files.writeString(PathUtils.lp(file), text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static void writeBytes(Path file, byte[] bytes) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(PathUtils.lp(file).getParent());
            }
            Files.write(PathUtils.lp(file), bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static String read(Path file) {
        try {
            return Files.readString(PathUtils.lp(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(PathUtils.lp(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static boolean exists(Path path) {
        return Files.exists(PathUtils.lp(path));
    }

    public static long size(Path file) {
        try {
            return Files.size(PathUtils.lp(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 把修改时间设成「秒级整数」，用于复现「大小与时间相同、内容不同」的陷阱。 */
    public static void setMtime(Path file, long epochSeconds) {
        try {
            Files.setLastModifiedTime(PathUtils.lp(file), FileTime.from(Instant.ofEpochSecond(epochSeconds)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static long mtimeSeconds(Path file) {
        try {
            return Files.getLastModifiedTime(PathUtils.lp(file)).toInstant().getEpochSecond();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 只读文件（Windows 走 DOS 属性）。 */
    public static void makeReadOnly(Path file) {
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Files.setAttribute(PathUtils.lp(file), "dos:readonly", true);
            } else {
                Set<PosixFilePermission> permissions =
                        EnumSet.copyOf(Files.getPosixFilePermissions(PathUtils.lp(file)));
                permissions.remove(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(PathUtils.lp(file), permissions);
            }
        } catch (IOException | UnsupportedOperationException ex) {
            throw new UncheckedIOException(new IOException(ex));
        }
    }

    /**
     * 是否只读。
     *
     * <p>注意：本机沙箱里 {@link Files#isWritable} 对任何文件都返回 {@code false}
     * （已实测：刚建好的可写文件也是 false），所以这里直接读 DOS 只读属性。</p>
     */
    public static boolean isReadOnly(Path file) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                return Boolean.TRUE.equals(Files.getAttribute(PathUtils.lp(file), "dos:readonly"));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return !Files.isWritable(PathUtils.lp(file));
    }

    public static boolean isWritable(Path file) {
        return !isReadOnly(file);
    }

    /** 递归清掉只读属性（给 JUnit 的临时目录清理兜底）。 */
    public static void makeWritableRecursively(Path root) {
        if (!Files.exists(PathUtils.lp(root))) {
            return;
        }
        try (Stream<Path> stream = Files.walk(PathUtils.lp(root))) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.setAttribute(path, "dos:readonly", false);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 非 Windows：忽略
                }
                try {
                    path.toFile().setWritable(true, false);
                } catch (RuntimeException ignored) {
                    // 忽略
                }
            });
        } catch (IOException ignored) {
            // 清理兜底失败不影响用例结论
        }
    }
}
