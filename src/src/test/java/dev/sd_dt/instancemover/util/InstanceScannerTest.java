package dev.sd_dt.instancemover.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.util.InstanceScanner.InstanceCandidate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 老实例自动探测（versions\ 隔离目录 + 非隔离根目录 + 手填校验 + 自动下钻）。
 *
 * <p>布局（{@code base\外层目录\} 下面）：</p>
 * <pre>
 * .minecraft\                     ← 非隔离根目录候选（只有 config）
 *   config\a.toml
 *   versions\
 *     老整合包-26.1\              ← 版本隔离候选（saves + config + options.txt）
 *       saves\w1\level.dat
 *       config\a.toml
 *       options.txt
 *     新整合包\                    ← 当前实例（config），必须被排除
 *     空版本\                     ← 只有 logs，不算实例，必须被排除
 * </pre>
 */
class InstanceScannerTest {

    /** 建好上面那套目录，返回 {外层目录, 当前实例}。 */
    private static Path[] layout(Path base) {
        Path outer = base.resolve("外层目录");
        Path minecraft = outer.resolve(".minecraft");
        TestSupport.write(minecraft.resolve("config/a.toml"), "根目录配置");

        Path old = minecraft.resolve("versions/老整合包-26.1");
        TestSupport.write(old.resolve("saves/w1/level.dat"), "存档");
        TestSupport.write(old.resolve("config/a.toml"), "老配置");
        TestSupport.write(old.resolve("options.txt"), "老设置");

        Path current = minecraft.resolve("versions/新整合包");
        TestSupport.write(current.resolve("config/b.toml"), "新配置");

        TestSupport.write(minecraft.resolve("versions/空版本/logs/latest.log"), "日志");
        return new Path[]{outer, current};
    }

    @Test
    @DisplayName("自动探测：列出 versions 下的版本实例与非隔离根目录，排除当前实例与空目录")
    void scanFindsVersionInstances(@TempDir Path base) {
        Path[] paths = layout(base);
        Path current = paths[1];

        List<InstanceCandidate> candidates = InstanceScanner.scan(current);

        Set<String> labels = candidates.stream().map(InstanceCandidate::label).collect(Collectors.toSet());
        assertEquals(Set.of("老整合包-26.1", ".minecraft"), labels,
                "应探测到版本实例 + 非隔离根目录，且排除当前实例与空版本目录");
        assertTrue(candidates.stream().noneMatch(c -> InstanceScanner.isSameInstance(c.path(), current)),
                "当前实例自身必须被排除");
        assertTrue(candidates.stream().noneMatch(c -> c.label().equals("空版本")),
                "只有 logs 的目录不算实例");
        assertTrue(candidates.stream().allMatch(InstanceCandidate::selectable), "候选都应可选");

        InstanceCandidate old = byLabel(candidates, "老整合包-26.1");
        assertTrue(old.isolated());
        assertTrue(old.hasSaves());
        assertTrue(old.hasConfig());
        assertEquals(1, old.savesCount(), "saves 下 1 个存档");
        assertEquals(1, old.configCount(), "config 下 1 个条目");
        assertEquals("versions\\老整合包-26.1", old.relativeOrigin());
        assertTrue(old.dropdownText().contains("versions\\老整合包-26.1"), old.dropdownText());
        assertTrue(old.dropdownText().contains("1 个存档"), "下拉项要显示是否有存档：" + old.dropdownText());
        assertTrue(old.dropdownText().contains("B"), "下拉项要显示体积：" + old.dropdownText());
        assertTrue(old.sizeNote().startsWith("文件夹，约 "), old.sizeNote());
        assertTrue(old.looksLikeScore() >= 3, "命中 config/saves/options.txt");
        assertTrue(old.statusText().startsWith("✔ 含 saves 1 个、config 1 项、共 "), old.statusText());

        InstanceCandidate root = byLabel(candidates, ".minecraft");
        assertFalse(root.isolated());
        assertFalse(root.hasSaves());
        assertTrue(root.hasConfig());
        assertEquals(root.path(), paths[0].resolve(".minecraft").toAbsolutePath().normalize());
        assertTrue(root.dropdownText().contains("根目录"), root.dropdownText());
        assertTrue(root.dropdownText().contains("无存档"), root.dropdownText());
    }

    @Test
    @DisplayName("有存档的候选排前面，然后是有配置的，最后按名字")
    void scanSortsMostLikelyFirst(@TempDir Path base) {
        Path[] paths = layout(base);
        List<InstanceCandidate> candidates = InstanceScanner.scan(paths[1]);
        assertEquals("老整合包-26.1", candidates.get(0).label(), "有存档的老整合包应排第一");
        assertEquals(".minecraft", candidates.get(1).label());
    }

    @Test
    @DisplayName("includeCurrent=true 时当前实例也在列表里，并标记 current")
    void scanCanIncludeCurrent(@TempDir Path base) {
        Path[] paths = layout(base);
        List<InstanceCandidate> candidates = InstanceScanner.scan(paths[1], true);
        assertEquals(3, candidates.size());
        InstanceCandidate mine = byLabel(candidates, "新整合包");
        assertTrue(mine.current());
        assertFalse(mine.selectable(), "当前实例不可选");
    }

    @Test
    @DisplayName("体积估算限时限量：maxFiles=1 时标记截断，绝不无上限遍历")
    void scanRespectsQuickSizeBudget(@TempDir Path base) {
        Path[] paths = layout(base);
        List<InstanceCandidate> candidates = InstanceScanner.scan(paths[1], false, 1, 300.0);
        assertTrue(candidates.stream().allMatch(InstanceCandidate::sizeTruncated),
                "超出文件数上限必须标记未扫完（界面显示 ≥ X（未扫完））");
        assertTrue(candidates.stream().allMatch(c -> c.sizeNote().contains("未扫完")), "体积文案要体现截断");
    }

    @Test
    @DisplayName("存档含 session.lock：候选带上警告标记")
    void sessionLockWarningOnCandidate(@TempDir Path base) {
        Path[] paths = layout(base);
        TestSupport.write(paths[0].resolve(".minecraft/versions/老整合包-26.1/saves/w1/session.lock"), "lock");

        InstanceCandidate old = byLabel(InstanceScanner.scan(paths[1]), "老整合包-26.1");

        assertFalse(old.warnings().isEmpty());
        assertTrue(old.warnings().get(0).contains("session.lock"));
    }

    @Test
    @DisplayName("手填路径校验：不存在 / 不是实例 / 当前实例自身")
    void inspectValidatesManualPath(@TempDir Path base) {
        Path[] paths = layout(base);
        Path current = paths[1];

        InstanceCandidate missing = InstanceScanner.inspect(base.resolve("没有这个目录"), current);
        assertFalse(missing.usable());
        assertEquals("目录不存在或不是文件夹", missing.rejectReason());

        InstanceCandidate notInstance = InstanceScanner.inspect(
                paths[0].resolve(".minecraft/versions/空版本"), current);
        assertFalse(notInstance.usable());
        assertEquals("没有找到 saves 或 config，不像一个游戏实例", notInstance.rejectReason());

        InstanceCandidate self = InstanceScanner.inspect(current, current);
        assertTrue(self.usable(), "当前实例本身是合法实例目录");
        assertTrue(self.current());
        assertFalse(self.selectable(), "但不能选它自己");
        assertNull(self.rejectReason());

        InstanceCandidate ok = InstanceScanner.inspect(paths[0].resolve(".minecraft/versions/老整合包-26.1"), current);
        assertTrue(ok.selectable());
        assertTrue(ok.hasSaves());
        assertTrue(ok.statusText().contains("saves 1 个"));
    }

    @Test
    @DisplayName("自动下钻：选了含 .minecraft 的上一层时改用子目录并给提示")
    void inspectDrillsIntoDotMinecraft(@TempDir Path base) {
        Path[] paths = layout(base);
        Path outer = paths[0];

        InstanceCandidate candidate = InstanceScanner.inspect(outer, paths[1]);

        assertEquals(outer.resolve(".minecraft").toAbsolutePath().normalize(), candidate.path());
        assertNotNull(candidate.notice());
        assertTrue(candidate.notice().startsWith("所选目录里没有实例文件，已自动改用子目录："), candidate.notice());
        assertTrue(candidate.usable());
        assertFalse(candidate.isolated(), ".minecraft 是非隔离根目录");
    }

    @Test
    @DisplayName("没有 versions 目录时探测结果为空，不抛异常")
    void scanWithoutVersionsDirIsEmpty(@TempDir Path base) {
        Path lonely = base.resolve("单机实例");
        TestSupport.write(lonely.resolve("config/a.toml"), "配置");

        assertTrue(InstanceScanner.scan(lonely).isEmpty());
        assertTrue(InstanceScanner.versionsDirOf(lonely).isEmpty());
        assertTrue(InstanceScanner.sharedRootOf(lonely).isEmpty());
        assertTrue(InstanceScanner.inspect(lonely, lonely).usable(), "自身仍是合法实例目录");
    }

    @Test
    @DisplayName("目录定位：当前在 versions 下、当前就是根目录、当前就是 versions")
    void versionsAndSharedRootResolution(@TempDir Path base) {
        Path[] paths = layout(base);
        Path minecraft = paths[0].resolve(".minecraft");
        Path versions = minecraft.resolve("versions");
        Path current = paths[1];

        assertEquals(versions.toAbsolutePath().normalize(), InstanceScanner.versionsDirOf(current).orElseThrow());
        assertEquals(minecraft.toAbsolutePath().normalize(), InstanceScanner.sharedRootOf(current).orElseThrow());

        assertEquals(versions.toAbsolutePath().normalize(),
                InstanceScanner.versionsDirOf(minecraft).orElseThrow(), "当前是根目录时，versions 在它下面");
        assertEquals(minecraft.toAbsolutePath().normalize(), InstanceScanner.sharedRootOf(minecraft).orElseThrow());

        assertEquals(versions.toAbsolutePath().normalize(),
                InstanceScanner.versionsDirOf(versions).orElseThrow(), "当前就是 versions 目录时直接用");
        assertEquals(minecraft.toAbsolutePath().normalize(), InstanceScanner.sharedRootOf(versions).orElseThrow());
    }

    @Test
    @DisplayName("isIsolated：versions 下一层为真，根目录与 versions 本身为假")
    void isIsolatedDetection(@TempDir Path base) {
        Path[] paths = layout(base);
        assertTrue(InstanceScanner.isIsolated(paths[1]));
        assertFalse(InstanceScanner.isIsolated(paths[0].resolve(".minecraft")));
        assertFalse(InstanceScanner.isIsolated(paths[0].resolve(".minecraft/versions")));
    }

    @Test
    @DisplayName("isSameInstance：同一路径为真，不同路径为假（含规整差异）")
    void isSameInstanceCheck(@TempDir Path base) {
        Path[] paths = layout(base);
        assertTrue(InstanceScanner.isSameInstance(paths[1], paths[1]));
        assertTrue(InstanceScanner.isSameInstance(paths[1], paths[1].resolve(".")));
        assertFalse(InstanceScanner.isSameInstance(paths[1], paths[0].resolve(".minecraft")));
        assertFalse(InstanceScanner.isSameInstance(null, paths[1]));
    }

    @Test
    @DisplayName("探测不会跟着 versions 里的符号链接乱跑（只关心目录）")
    void scanIgnoresFilesInVersionsDir(@TempDir Path base) throws Exception {
        Path[] paths = layout(base);
        Files.writeString(paths[0].resolve(".minecraft/versions/说明.txt"), "不是目录");

        List<InstanceCandidate> candidates = InstanceScanner.scan(paths[1]);

        assertEquals(2, candidates.size(), "versions 下的普通文件不该被当成版本实例");
        assertTrue(candidates.stream().noneMatch(c -> c.label().endsWith(".txt")));
    }

    @Test
    @DisplayName("转发 py 原语：looks_like_instance 与 normalize_instance_path")
    void delegatesPyPrimitives(@TempDir Path base) {
        Path[] paths = layout(base);
        Path old = paths[0].resolve(".minecraft/versions/老整合包-26.1");

        assertEquals(3, InstanceScanner.looksLikeInstance(old), "config / saves / options.txt 三项命中");
        assertEquals(0, InstanceScanner.looksLikeInstance(base.resolve("不存在")));
        assertEquals(old.toAbsolutePath().normalize(),
                InstanceScanner.normalizeInstancePath(old).path(), "本身就是实例目录，不下钻");
        assertTrue(InstanceScanner.normalizeInstancePath(old).notice() == null);

        Path outer = paths[0];
        assertTrue(InstanceScanner.normalizeInstancePath(outer).changed());
        assertEquals(outer.resolve(".minecraft").toAbsolutePath().normalize(),
                InstanceScanner.normalizeInstancePath(outer).path());
    }

    @Test
    @DisplayName("非隔离根目录的体积必须跳过 versions\\，否则版本实例会被重复计算")
    void rootCandidateSizeSkipsVersionsDir(@TempDir Path base) {
        Path[] paths = layout(base);
        Path minecraft = paths[0].resolve(".minecraft");
        // 往版本实例里堆文件：只应体现在版本候选身上，不能算进根目录体积
        for (int i = 0; i < 20; i++) {
            TestSupport.write(paths[1].resolve("saves/world/chunk_%02d.mca".formatted(i)), "x".repeat(100));
        }

        List<InstanceCandidate> candidates = InstanceScanner.scan(paths[1], true, 3000, 300.0);
        InstanceCandidate root = byLabel(candidates, ".minecraft");
        InstanceCandidate version = byLabel(candidates, "新整合包");

        assertEquals(1, root.fileCount(), "根目录只统计自己的 config/a.toml，不含 versions\\ 下任何文件");
        assertEquals(TestSupport.size(minecraft.resolve("config/a.toml")), root.bytes());
        assertFalse(root.sizeNote().contains("未扫完"), "根目录体积不该被 versions 撑到截断：" + root.sizeNote());
        assertTrue(version.fileCount() >= 21, "版本实例自己的文件照常统计：" + version.fileCount());
    }

    @Test
    @DisplayName("只有 mods 的版本目录不可选（准入必须含 saves 或 config）")
    void modsOnlyDirectoryIsNotSelectable(@TempDir Path base) {
        Path[] paths = layout(base);
        Path modsOnly = paths[0].resolve(".minecraft/versions/只有模组");
        TestSupport.write(modsOnly.resolve("mods/example.jar"), "jar");
        TestSupport.write(modsOnly.resolve("options.txt"), "设置");

        List<InstanceCandidate> candidates = InstanceScanner.scan(paths[1]);

        assertTrue(candidates.stream().noneMatch(candidate -> candidate.label().equals("只有模组")),
                "只有 mods（没有 saves/config）的目录不该出现在候选里");
        InstanceCandidate inspected = InstanceScanner.inspect(modsOnly, paths[1]);
        assertFalse(inspected.usable());
        assertFalse(inspected.selectable());
        assertEquals("没有找到 saves 或 config，不像一个游戏实例", inspected.rejectReason());
        assertTrue(inspected.looksLikeScore() > 0, "25 项打分仍算出命中（mods/options.txt），但不作为准入依据");
    }

    private static InstanceCandidate byLabel(List<InstanceCandidate> candidates, String label) {
        return candidates.stream()
                .filter(candidate -> candidate.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有探测到 " + label + "，实际：" + candidates));
    }
}
