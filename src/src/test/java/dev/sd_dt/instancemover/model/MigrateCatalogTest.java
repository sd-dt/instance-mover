package dev.sd_dt.instancemover.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 25 项清单的自检，对应对标 mc_transfer.py v1.1 的 {@code DEFAULT_ITEMS} / {@code OPTIONAL_ITEMS}
 * 断言（按用户决定：.voxy 与 Distant_Horizons_server_data 搬进默认项 → 11 / 14）。
 */
class MigrateCatalogTest {

    @Test
    @DisplayName("清单数量：默认 11 项、询问 14 项、合计 25 项")
    void countsMatchApprovedDecision() {
        assertEquals(11, MigrateCatalog.DEFAULT_ITEMS.size(), "默认项必须是 11 项（py 9 项 + .voxy + DH）");
        assertEquals(14, MigrateCatalog.OPTIONAL_ITEMS.size(), "询问项必须是 14 项（py 16 项 - .voxy - DH）");
        assertEquals(25, MigrateCatalog.ALL_ITEMS.size(), "清单合计必须是 25 项");
        assertEquals(25, MigrateCatalog.allNames().size(), "条目名不能重复");
    }

    @Test
    @DisplayName("默认项顺序与 v1.1 一致，.voxy / DH 已并入默认项")
    void defaultItemOrderAndMovedItems() {
        List<String> names = MigrateCatalog.DEFAULT_ITEMS.stream().map(MigrateItem::name).toList();
        assertEquals(List.of(
                "config", "resourcepacks", "saves", "schematics", "screenshots",
                "shaderpacks", "xaero", "options.txt", "servers.dat",
                ".voxy", "Distant_Horizons_server_data"), names);

        Set<String> optional = MigrateCatalog.OPTIONAL_ITEMS.stream()
                .map(MigrateItem::name).collect(Collectors.toSet());
        assertFalse(optional.contains(".voxy"), ".voxy 不该再出现在询问项里");
        assertFalse(optional.contains("Distant_Horizons_server_data"), "DH 不该再出现在询问项里");
        assertFalse(optional.contains("servers.dat"), "servers.dat 属于默认项（py 自检断言）");
        assertFalse(optional.contains("screenshots"), "screenshots 属于默认项（py 自检断言）");

        for (MigrateItem item : MigrateCatalog.DEFAULT_ITEMS) {
            assertTrue(item.defaultOn(), item.name() + " 属于默认项，必须默认勾选");
        }
    }

    @Test
    @DisplayName("询问项默认勾选：9 项勾选 + 5 项不勾选")
    void optionalDefaultOnSplit() {
        long on = MigrateCatalog.OPTIONAL_ITEMS.stream().filter(MigrateItem::defaultOn).count();
        long off = MigrateCatalog.OPTIONAL_ITEMS.size() - on;
        assertEquals(9, on, "默认勾选的询问项应为 9 项");
        assertEquals(5, off, "默认不勾的询问项应为 5 项");

        Set<String> offNames = MigrateCatalog.OPTIONAL_ITEMS.stream()
                .filter(item -> !item.defaultOn())
                .map(MigrateItem::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("essential", "mods", "defaultconfigs", "PCL", "hmcl.json"), offNames);

        Set<String> onNames = MigrateCatalog.OPTIONAL_ITEMS.stream()
                .filter(MigrateItem::defaultOn)
                .map(MigrateItem::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("journeymap", "XaeroPlus", "optionsof.txt", "optionsshaders.txt",
                "iris.properties", "replay_recordings", "CustomSkinLoader", "tacz",
                "command_history.txt"), onNames, "默认勾选的 9 项询问项必须与 M2 契约逐项一致");
    }

    @Test
    @DisplayName("作用文案逐字对齐 v1.1（抽查默认项与询问项）")
    void descriptionsAreVerbatim() {
        assertEquals("所有模组的配置文件：模组开关、按键绑定、画面/音效选项、机器与玩法设置。"
                        + "转移后新实例的模组设置会变成老实例那一套",
                byName("config").description());
        assertEquals("单人存档：地图、进度、背包、成就。存档里还带着远景数据——"
                        + "Distant Horizons 在 saves/存档名/data/DistantHorizons.sqlite，"
                        + "Voxy 在 saves/存档名/voxy",
                byName("saves").description());
        assertEquals("【Voxy 远景 LOD 缓存】多人服务器上已生成的超远视距地形，按服务器分目录。"
                        + "转移后不用重新跑图就能立刻看到远景，不转移只是需要重新生成；"
                        + "体积可能有好几个 GB（单人存档的 Voxy 数据在 saves 里，随 saves 一起转移）",
                byName(".voxy").description());
        assertEquals("模组本体（.jar 文件）。转移后新实例会加载老实例的模组，"
                        + "若游戏版本或加载器不同可能直接启动崩溃，一般不建议勾选",
                byName("mods").description());
        assertEquals("聊天框输入历史：按 ↑ 键能翻出来的那些命令记录",
                byName("command_history.txt").description());

        for (MigrateItem item : MigrateCatalog.ALL_ITEMS) {
            assertNotNull(item.description(), item.name() + " 缺少作用说明");
            assertTrue(item.description().length() > 10, item.name() + " 的作用说明太短，疑似杜撰");
        }
    }

    @Test
    @DisplayName("类型标注与 v1.1 一致（文件夹 / 文件）")
    void kindsAreCorrect() {
        assertEquals(ItemKind.FILE, byName("options.txt").kind());
        assertEquals(ItemKind.FILE, byName("servers.dat").kind());
        assertEquals(ItemKind.FILE, byName("hmcl.json").kind());
        assertEquals(ItemKind.FILE, byName("iris.properties").kind());
        assertEquals(ItemKind.DIR, byName("config").kind());
        assertEquals(ItemKind.DIR, byName(".voxy").kind());
        assertEquals(ItemKind.DIR, byName("Distant_Horizons_server_data").kind());
        assertEquals(ItemKind.DIR, byName("tacz").kind());
    }

    @Test
    @DisplayName("需重启生效的条目只包含 6 个配置类条目")
    void restartRequiredSet() {
        assertEquals(Set.of("config", "options.txt", "optionsshaders.txt", "iris.properties",
                "optionsof.txt", "defaultconfigs"), MigrateCatalog.RESTART_REQUIRED_NAMES);
        assertTrue(byName("config").restartRequired());
        assertTrue(byName("options.txt").restartRequired());
        assertTrue(byName("iris.properties").restartRequired());
        assertTrue(byName("defaultconfigs").restartRequired());
        assertFalse(byName("saves").restartRequired(), "存档不需要重启");
        assertFalse(byName("screenshots").restartRequired(), "截图不需要重启");
        assertFalse(byName("mods").restartRequired(), "mods 需要重启游戏才能生效，但不在配置类提示清单里");
    }

    @Test
    @DisplayName("列表文案：LIST_TEXT / LIST_LINES 含全部默认项")
    void listTexts() {
        String listText = MigrateCatalog.listText();
        for (MigrateItem item : MigrateCatalog.DEFAULT_ITEMS) {
            assertTrue(listText.contains(item.name()), "列表文案缺少 " + item.name());
        }
        assertTrue(MigrateCatalog.listDirs().contains("config"));
        assertTrue(MigrateCatalog.listDirs().contains(".voxy"));
        assertTrue(MigrateCatalog.listFiles().contains("options.txt"));
        assertFalse(MigrateCatalog.listFiles().contains("config"));
        assertTrue(MigrateCatalog.listLines().startsWith("    文件夹："));
        assertTrue(MigrateCatalog.listLines().contains("\n    文件：  "));
    }

    @Test
    @DisplayName("find() 能取到全部 25 项")
    void findWorks() {
        for (MigrateItem item : MigrateCatalog.ALL_ITEMS) {
            assertTrue(MigrateCatalog.find(item.name()).isPresent(), "找不到 " + item.name());
        }
        assertTrue(MigrateCatalog.find("不存在的东西").isEmpty());
        assertTrue(MigrateCatalog.isDefaultName("config"));
        assertFalse(MigrateCatalog.isDefaultName("mods"));
    }

    private static MigrateItem byName(String name) {
        return MigrateCatalog.find(name).orElseThrow(() -> new AssertionError("清单里没有 " + name));
    }
}
