package dev.sd_dt.instancemover.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 条目名防御（审查 F3）：{@link MigrateItem} 是 public record，会直接 {@code resolve} 到实例根目录上，
 * 所以必须挡住空名 / 绝对路径 / 带分隔符 / {@code .} 与 {@code ..} 这类会跑到实例外面的写法；
 * 同时 25 项现有清单名必须全部仍然可用。
 */
class MigrateItemTest {

    private static MigrateItem item(String name) {
        return new MigrateItem(name, ItemKind.DIR, "测试用条目", true, false);
    }

    @Test
    @DisplayName("25 项现有清单名全部仍然可用（不能被新校验误伤）")
    void catalogNamesStillValid() {
        assertEquals(25, MigrateCatalog.ALL_ITEMS.size());
        List<String> names = new ArrayList<>();
        for (MigrateItem item : MigrateCatalog.ALL_ITEMS) {
            assertDoesNotThrow(() -> item(item.name()), "清单名被判非法：" + item.name());
            assertDoesNotThrow(() -> new MigrateItem(item.name(), item.kind(), item.description(),
                    item.defaultOn(), item.restartRequired()));
            names.add(item.name());
        }
        assertTrue(names.contains(".voxy"), "带前导点的名字必须允许");
        assertTrue(names.contains("Distant_Horizons_server_data"), "带下划线的长名字必须允许");
        assertTrue(names.contains("options.txt"), "带点的文件名必须允许");
    }

    @Test
    @DisplayName("空名 / 纯空白被拒，并带中文原因")
    void blankNamesRejected() {
        for (String name : List.of("", " ", "\t", "   ")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> item(name),
                    "应拒绝：" + name);
            assertTrue(ex.getMessage().contains("条目名不能为空"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("含路径分隔符的名字被拒（/ 与 \\）")
    void separatorsRejected() {
        for (String name : List.of("config/saves", "config\\saves", "a/../b", "\\config", "/config",
                "config/", "config\\")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> item(name),
                    "应拒绝：" + name);
            assertTrue(ex.getMessage().contains("路径分隔符"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("盘符写法被拒（C: / D:config 这类「盘符相对」路径）")
    void driveLettersRejected() {
        for (String name : List.of("C:", "D:config", "c:config")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> item(name),
                    "应拒绝：" + name);
            assertTrue(ex.getMessage().contains("盘符"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("名字是 . 或 .. 路径段时被拒，但含 .. 的普通名字允许（例如 a..b）")
    void dotSegmentsRejected() {
        for (String name : List.of(".", "..")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> item(name),
                    "应拒绝：" + name);
            assertTrue(ex.getMessage().contains(".."), ex.getMessage());
        }
        assertDoesNotThrow(() -> item("a..b"), "「..」只要不是独立路径段就允许");
        assertDoesNotThrow(() -> item(".voxy"), "以点开头的正常名字允许");
    }

    @Test
    @DisplayName("resolveIn 仍然把名字拼到实例根目录上")
    void resolveInStillWorks(@org.junit.jupiter.api.io.TempDir Path base) {
        assertEquals(base.resolve("config"), item("config").resolveIn(base));
        assertEquals(base.resolve("options.txt"), item("options.txt").resolveIn(base));
    }

    @Test
    @DisplayName("非法字符（NUL）被拒且不会漏出 InvalidPathException")
    void invalidPathCharsWrapped() {
        String bad = "config\u0000x";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> item(bad));
        assertTrue(ex.getMessage().contains("条目名"), ex.getMessage());
        assertTrue(ex.getCause() instanceof InvalidPathException || ex.getCause() == null,
                "应把 InvalidPathException 包成带中文说明的 IllegalArgumentException");
    }

    @Test
    @DisplayName("空 kind / 空说明仍然被拒（requireNonNull 保留）")
    void nullComponentsRejected() {
        assertThrows(NullPointerException.class,
                () -> new MigrateItem("config", null, "说明", true, false));
        assertThrows(NullPointerException.class,
                () -> new MigrateItem("config", ItemKind.DIR, null, true, false));
        assertThrows(NullPointerException.class,
                () -> new MigrateItem(null, ItemKind.DIR, "说明", true, false));
    }
}
