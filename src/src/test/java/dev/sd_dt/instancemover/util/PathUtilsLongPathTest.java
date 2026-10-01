package dev.sd_dt.instancemover.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 长路径的真实行为（审查 O3 的落实）。
 *
 * <p><b>实测结论（本机 JDK 25 / Windows，{@code LongPathsEnabled=0}、java.exe 无 longPathAware 清单）</b>：
 * {@code Path.of("\\\\?\\C:\\...")} 与 {@code new File("\\\\?\\C:\\...")} 都会把 {@code \\?\} 前缀
 * 归一化掉，所以 {@link PathUtils#extendedPathString(String)} 产出的前缀并不会留在 {@link Path} 对象里；
 * 真正让长路径能用的是 JDK 自己的路径处理 —— 用**裸路径**就能创建、写入、读回 284 字符的深目录。
 * 本测试同时锁住两件事：① 纯函数仍按 py 的方式产出前缀字符串；② 真实深路径读写确实可用并能自清理。</p>
 */
class PathUtilsLongPathTest {

    @Test
    @DisplayName("纯函数：长路径加 \\\\?\\ 前缀、UNC 用 \\\\?\\UNC\\、短路径不动、已有前缀原样返回")
    void extendedPathStringContract() {
        Assumptions.assumeTrue(PathUtils.isWindows(), "前缀只在 Windows 上有意义");

        String longPath = "C:\\" + "很深很长的一层目录名_".repeat(24) + "options.txt";
        assertTrue(longPath.length() >= PathUtils.LONG_PATH_THRESHOLD, "前置条件：" + longPath.length());
        assertTrue(PathUtils.extendedPathString(longPath).startsWith("\\\\?\\"),
                PathUtils.extendedPathString(longPath));

        String unc = "\\\\server\\share\\" + "很深很长的一层目录名_".repeat(24) + "options.txt";
        assertTrue(PathUtils.extendedPathString(unc).startsWith("\\\\?\\UNC\\"), PathUtils.extendedPathString(unc));

        Path shortPath = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertEquals(shortPath.toString(), PathUtils.extendedPathString(shortPath.toString()));

        String alreadyPrefixed = "\\\\?\\C:\\" + "很长的一层目录名_".repeat(24);
        assertEquals(alreadyPrefixed, PathUtils.extendedPathString(alreadyPrefixed), "已有前缀原样返回");
    }

    @Test
    @DisplayName("实测事实：JDK 会把 \\\\?\\ 前缀归一化掉，长路径靠 JDK 自身能力（不是靠这个前缀）")
    void jdkNormalizesLongPathPrefix(@TempDir Path base) {
        Assumptions.assumeTrue(PathUtils.isWindows(), "前缀只在 Windows 上有意义");

        Path deep = deepPath(base, "前缀归一化", PathUtils.LONG_PATH_THRESHOLD + 60);
        String prefixedString = "\\\\?\\" + deep.toAbsolutePath();
        Path prefixedPath = Path.of(prefixedString);

        // 实测：前缀不会留在 Path 里（若将来某个 JDK 保留它，这条断言也不会误伤功能——只是记录差异）
        boolean prefixKept = prefixedPath.toString().startsWith("\\\\?\\");
        System.out.println("[长路径实测] Path.of(\\?\\...) 保留前缀 = " + prefixKept
                + "；PathUtils.extendedPathString 产出前缀 = "
                + PathUtils.extendedPathString(deep.toString()).startsWith("\\\\?\\"));
        assertEquals(deep.toAbsolutePath().normalize().toString(), prefixedPath.toAbsolutePath().normalize().toString(),
                "无论前缀是否被保留，Path 都必须指向同一个目录");
    }

    @Test
    @DisplayName("真实长路径（≥270 字符）：创建 → 写入 → 读回一致 → lp() 指同一个文件 → 递归删除自清理")
    void longPathRoundTripAndSelfCleanup(@TempDir Path base) throws Exception {
        Path top = base.resolve("长路径测试根");
        Files.createDirectories(top);
        Path deep = deepPath(top, "层", PathUtils.LONG_PATH_THRESHOLD + 40);
        assertTrue(deep.toString().length() >= 270, "前置条件：深路径至少 270 字符，实际 " + deep.toString().length());

        Path file = deep.resolve("probe.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "深路径内容-ok");
            assertEquals("深路径内容-ok", Files.readString(file), "写入后必须能读回一致的内容");
            assertTrue(Files.isRegularFile(file));

            // lp() 的产物必须指向同一个真实文件（前缀被 JDK 归一化也好、保留也好）
            assertTrue(Files.isSameFile(PathUtils.lp(file), file), "lp() 必须指向同一个文件");
            assertEquals("深路径内容-ok", Files.readString(PathUtils.lp(file)));
            assertEquals(PathUtils.fileName(file), "probe.txt");
        } finally {
            PathUtils.deleteRecursively(top);
        }

        assertFalse(Files.exists(PathUtils.lp(top)), "递归删除后长路径目录必须真的消失（自清理）");
    }

    /** 在 base 下逐层拼出 ≥ targetLength 字符的深路径（每层名字足够短，不会撞 255 字符的单段上限）。 */
    private static Path deepPath(Path base, String tag, int targetLength) {
        StringBuilder deep = new StringBuilder(base.toString());
        List<String> components = new ArrayList<>();
        int level = 0;
        while (deep.length() < targetLength) {
            String name = "%s%02d_%s".formatted(tag, level, "x".repeat(16));
            components.add(name);
            deep.append('\\').append(name);
            level++;
        }
        return Path.of(deep.toString());
    }
}
