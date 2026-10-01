package dev.sd_dt.instancemover.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.engine.CopyEngine;
import dev.sd_dt.instancemover.engine.TransferStats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 路径工具，对应 mc_transfer.py v1.1 的 {@code lp()}：Windows 下路径长度 ≥ 230 时加
 * {@code \\?\} 前缀（UNC 用 {@code \\?\UNC\}）。
 */
class PathUtilsTest {

    @Test
    @DisplayName("短路径：返回绝对路径，不加前缀")
    void shortPathUnchanged(@TempDir Path base) {
        Assumptions.assumeTrue(PathUtils.isWindows(), "长路径前缀只在 Windows 上有意义");
        String shortPath = base.resolve("config").toString();
        String extended = PathUtils.extendedPathString(shortPath);
        assertEquals(Path.of(shortPath).toAbsolutePath().normalize().toString(), extended);
        assertFalse(extended.startsWith("\\\\?\\"), "短路径不该加前缀");
    }

    @Test
    @DisplayName("长路径（≥230 字符）：加 \\\\?\\ 前缀")
    void longPathGetsPrefix() {
        Assumptions.assumeTrue(PathUtils.isWindows(), "长路径前缀只在 Windows 上有意义");
        String longPath = "C:\\" + "很长的一层目录名_".repeat(26) + "options.txt";
        assertTrue(longPath.length() >= PathUtils.LONG_PATH_THRESHOLD, "前置条件：路径足够长 " + longPath.length());

        String extended = PathUtils.extendedPathString(longPath);
        assertTrue(extended.startsWith("\\\\?\\"), extended);
        assertTrue(extended.endsWith("options.txt"));
        assertEquals("\\\\?\\" + Path.of(longPath).toAbsolutePath().normalize(), extended);
    }

    @Test
    @DisplayName("UNC 长路径：用 \\\\?\\UNC\\ 前缀")
    void uncLongPathGetsUncPrefix() {
        Assumptions.assumeTrue(PathUtils.isWindows(), "长路径前缀只在 Windows 上有意义");
        String unc = "\\\\server\\share\\" + "很长的一层目录名_".repeat(26) + "options.txt";
        assertTrue(unc.length() >= PathUtils.LONG_PATH_THRESHOLD, "前置条件：路径足够长 " + unc.length());

        String extended = PathUtils.extendedPathString(unc);
        assertTrue(extended.startsWith("\\\\?\\UNC\\"), extended);
        assertTrue(extended.endsWith("options.txt"));
        assertFalse(extended.contains("\\\\?\\UNC\\\\server"), "UNC 前缀后再拼 share 时只保留一个反斜杠：" + extended);
    }

    @Test
    @DisplayName("已经有 \\\\?\\ 前缀的路径原样返回")
    void alreadyExtendedPathIsKept() {
        Assumptions.assumeTrue(PathUtils.isWindows());
        String prefixed = "\\\\?\\C:\\" + "很长的目录名_".repeat(20);
        assertEquals(prefixed, PathUtils.extendedPathString(prefixed));
    }

    @Test
    @DisplayName("判断工具：不存在 / 类型不符 / 只读清理")
    void predicates(@TempDir Path base) throws Exception {
        Path dir = base.resolve("config");
        Path file = base.resolve("options.txt");
        TestSupport.write(file, "设置");
        Files.createDirectories(dir);

        assertTrue(PathUtils.isDir(dir));
        assertTrue(PathUtils.isFile(file));
        assertFalse(PathUtils.isDir(file));
        assertFalse(PathUtils.isFile(dir));
        assertTrue(PathUtils.pathExists(file));
        assertFalse(PathUtils.pathExists(base.resolve("不存在")));

        TestSupport.makeReadOnly(file);
        assertTrue(TestSupport.isReadOnly(file), "前置条件：文件应为只读");
        PathUtils.forceWritable(file);
        assertFalse(TestSupport.isReadOnly(file), "清除只读属性后必须可写");
    }

    @Test
    @DisplayName("sameOrNested：相同 / 父子 / 无关")
    void sameOrNested(@TempDir Path base) {
        Path a = base.resolve("a");
        assertTrue(PathUtils.sameOrNested(a, a));
        assertTrue(PathUtils.sameOrNested(a, a.resolve("sub")));
        assertTrue(PathUtils.sameOrNested(a.resolve("sub"), a));
        assertFalse(PathUtils.sameOrNested(a, base.resolve("b")));
        assertFalse(PathUtils.sameOrNested(a, base.resolve("ab")), "前缀相同但不是父子目录");
    }

    @Test
    @DisplayName("端到端：>260 字符深路径也能成功读写（引擎内部走 lp()）")
    void deepPathRoundTrip(@TempDir Path base) throws Exception {
        Assumptions.assumeTrue(PathUtils.isWindows(), "长路径前缀只在 Windows 上有意义");
        StringBuilder deep = new StringBuilder(base.toString());
        List<String> components = new ArrayList<>();
        int level = 0;
        while (deep.length() < PathUtils.LONG_PATH_THRESHOLD + 60) {
            String name = "深路径目录_%02d_%s".formatted(level, "x".repeat(20));
            components.add(name);
            deep.append('\\').append(name);
            level++;
        }
        Path deepDir = Path.of(deep.toString());
        assertTrue(deepDir.toString().length() > PathUtils.LONG_PATH_THRESHOLD,
                "前置条件：目标路径超过 230 字符（实际 %d）".formatted(deepDir.toString().length()));

        Path src = base.resolve("src/options.txt");
        TestSupport.write(src, "深路径里的设置");
        Path dst = deepDir.resolve("options.txt");
        Path topLevel = base.resolve(components.get(0));

        try {
            TransferStats stats = new TransferStats();
            CopyEngine engine = new CopyEngine(() -> false, null, null);
            engine.copyItem(src, dst, stats, true);

            assertEquals(0, stats.failed(), "深路径复制不该失败：" + stats.errors());
            assertEquals("深路径里的设置", TestSupport.read(dst), "深路径下的内容要读得回来");

            TransferStats second = new TransferStats();
            engine.copyItem(src, dst, second, true);
            assertEquals(1, second.skipped(), "深路径下「相同跳过」也要生效（mtime 保留）");
            assertEquals(0, second.failed());
        } finally {
            PathUtils.deleteRecursively(topLevel);
        }
        assertFalse(Files.exists(PathUtils.lp(topLevel)), "长路径目录也必须能被清掉");
    }

    // ------------------------------------------------------------------
    // t16 加固二：真祖先判定（抗别名）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isRealAncestorOf：真祖先/后代/兄弟/相等/不存在 的基本语义")
    void isRealAncestorOfBasics(@TempDir Path base) throws Exception {
        Path root = base.resolve("mc");
        Path config = root.resolve("config");
        Path backup = config.resolve("backup");
        Files.createDirectories(backup);
        Files.createDirectories(root.resolve("saves"));

        assertTrue(PathUtils.isRealAncestorOf(root, backup), "root 是 backup 的祖先");
        assertTrue(PathUtils.isRealAncestorOf(config, backup));
        assertFalse(PathUtils.isRealAncestorOf(backup, config), "反向不是祖先");
        assertFalse(PathUtils.isRealAncestorOf(config, config), "相等不算严格祖先");
        assertFalse(PathUtils.isRealAncestorOf(root.resolve("saves"), backup), "兄弟目录不算祖先");
        assertFalse(PathUtils.isRealAncestorOf(base.resolve("不存在"), backup), "不存在的祖先不算");
        assertFalse(PathUtils.isRealAncestorOf(null, backup));
        assertFalse(PathUtils.isRealAncestorOf(root, null));

        // 含 .. 的写法会被规整掉，语义不变
        assertTrue(PathUtils.isRealAncestorOf(root, root.resolve("config/../config/backup")));
    }

    @Test
    @DisplayName("★ 别名加固：junction 指向条目目录内部时，字符串判定漏判、真祖先判定命中（B 情形整体拒绝）")
    void junctionAliasIsCaughtByRealAncestor(@TempDir Path base) throws Exception {
        Assumptions.assumeTrue(PathUtils.isWindows(), "junction 只在 Windows 上有意义");

        // 当前实例 D:\...\mc，其 config 目录里放一个 backup（就是 B 情形的源树）
        Path mc = base.resolve("mc");
        Path config = mc.resolve("config");
        Files.createDirectories(config.resolve("backup"));
        Files.createDirectories(mc.resolve("saves"));
        TestSupport.write(config.resolve("a.toml"), "活动配置");

        // alias: <base>\alias 是指向 <mc>\config 的 junction
        Path alias = base.resolve("alias");
        Assumptions.assumeTrue(createJunction(alias, config), "本机无法创建 junction，跳过别名用例");

        Path src = alias.resolve("backup");   // = mc\config\backup（真实路径）
        try {
            assertTrue(Files.isDirectory(src), "通过 junction 也要能看见 backup");

            // ① 字符串判定漏判（这就是审查指出的绕过路径）
            assertFalse(PathUtils.isInside(src, config),
                    "前置确认：字符串分段判定确实漏判别名（首段就不同）");
            assertFalse(PathUtils.isInside(src, mc));

            // ② 真祖先判定命中
            assertTrue(PathUtils.isRealAncestorOf(config, src),
                    "junction 别名必须被真祖先判定识别为「落在 config 内部」");
            assertTrue(PathUtils.isRealAncestorOf(mc, src));

            // ③ 端到端：B 情形整体拒绝（validatePaths 三参）
            List<dev.sd_dt.instancemover.model.MigrateItem> items = List.of(
                    dev.sd_dt.instancemover.model.MigrateCatalog.find("config").orElseThrow(),
                    dev.sd_dt.instancemover.model.MigrateCatalog.find("saves").orElseThrow());
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> dev.sd_dt.instancemover.engine.TransferRunner.validatePaths(src, mc, items),
                    "别名路径下的 B 情形必须整体拒绝");
            assertTrue(rejected.getMessage().contains("复制进它自己"), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("config"), rejected.getMessage());

            // ④ A 情形（源在 dst\versions\... 下，不在任何条目目录里）仍必须放行，别名加固不能误伤
            Path versionsOld = mc.resolve("versions/旧包");
            Files.createDirectories(versionsOld.resolve("config"));
            dev.sd_dt.instancemover.engine.TransferRunner.validatePaths(versionsOld, mc, items);
        } finally {
            Files.deleteIfExists(alias);   // 只删链接，不删目标内容
        }
        assertTrue(Files.isDirectory(config.resolve("backup")), "删掉 junction 不应影响真实目录");
    }

    @Test
    @DisplayName("isRealAncestorOf 与 isInside 在普通路径上结论一致（别名加固没有改变语义）")
    void realAncestorMatchesInsideForPlainPaths(@TempDir Path base) throws Exception {
        Path root = base.resolve("mc");
        Path deep = root.resolve("config/sub/inner");
        Files.createDirectories(deep);

        assertEquals(PathUtils.isInside(deep, root), PathUtils.isRealAncestorOf(root, deep));
        assertEquals(PathUtils.isInside(deep, root.resolve("config")),
                PathUtils.isRealAncestorOf(root.resolve("config"), deep));
        assertEquals(PathUtils.isInside(root, deep), PathUtils.isRealAncestorOf(deep, root));
    }

    /** 用 {@code cmd /c mklink /J} 建 junction（无需管理员权限）；失败返回 false。 */
    private static boolean createJunction(Path link, Path target) {
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return Files.isDirectory(link) && Files.isSameFile(link, target);
        } catch (Exception ex) {
            return false;
        }
    }
}
