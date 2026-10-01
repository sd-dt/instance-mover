package dev.sd_dt.instancemover.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.engine.ScannedItem;
import dev.sd_dt.instancemover.model.ItemKind;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 勾选状态（纯逻辑）：默认态、全选/反选/仅默认、勾选结果与体积合计。 */
class MigrateSelectionTest {

    private static ScannedItem entry(String name, boolean defaultOn, long bytes) {
        MigrateItem item = MigrateCatalog.find(name)
                .map(base -> new MigrateItem(base.name(), base.kind(), base.description(), defaultOn,
                        base.restartRequired()))
                .orElseGet(() -> new MigrateItem(name, ItemKind.DIR, "测试用条目：" + name, defaultOn, false));
        return new ScannedItem(item, true, null, bytes, 1L, false, "文件夹，约 " + bytes + " B / 1 个文件",
                List.of());
    }

    private static final List<ScannedItem> TRANSFERABLE = List.of(
            entry("config", true, 100L),
            entry("saves", true, 200L),
            entry("options.txt", true, 10L),
            entry("journeymap", true, 1000L),
            entry("mods", false, 5000L),
            entry("defaultconfigs", false, 50L));

    @Test
    @DisplayName("默认勾选态 = 清单里 defaultOn 为真的项（mods / defaultconfigs 不勾）")
    void defaultsFollowCatalog() {
        MigrateSelection selection = MigrateSelection.defaults(TRANSFERABLE);

        assertTrue(selection.isChecked("config"));
        assertTrue(selection.isChecked("saves"));
        assertTrue(selection.isChecked("journeymap"));
        assertFalse(selection.isChecked("mods"));
        assertFalse(selection.isChecked("defaultconfigs"));
        assertEquals(4, selection.selectedCount(TRANSFERABLE));
        assertEquals(1310L, selection.selectedBytes(TRANSFERABLE));
        assertEquals(List.of("config", "saves", "options.txt", "journeymap"),
                selection.selectedItems(TRANSFERABLE).stream().map(MigrateItem::name).toList());
    }

    @Test
    @DisplayName("全选 / 反选 / 仅默认 / 清空")
    void bulkActions() {
        MigrateSelection selection = MigrateSelection.defaults(TRANSFERABLE);
        assertEquals(4, selection.selectedCount(TRANSFERABLE));
        assertEquals(1310L, selection.selectedBytes(TRANSFERABLE));

        // 从默认态反选 → 只剩默认不勾的两项
        selection.invert(TRANSFERABLE);
        assertEquals(2, selection.selectedCount(TRANSFERABLE));
        assertEquals(List.of("mods", "defaultconfigs"),
                selection.selectedItems(TRANSFERABLE).stream().map(MigrateItem::name).toList());

        selection.selectAll(TRANSFERABLE);
        assertEquals(6, selection.selectedCount(TRANSFERABLE));
        assertEquals(6360L, selection.selectedBytes(TRANSFERABLE));

        // 从全选反选 → 一个不剩
        selection.invert(TRANSFERABLE);
        assertEquals(0, selection.selectedCount(TRANSFERABLE));
        assertEquals(0L, selection.selectedBytes(TRANSFERABLE));

        selection.defaultsOnly(TRANSFERABLE);
        assertEquals(4, selection.selectedCount(TRANSFERABLE));
        assertFalse(selection.isChecked("mods"));

        selection.clearAll();
        assertEquals(0, selection.selectedCount(TRANSFERABLE));
        assertTrue(selection.selectedItems(TRANSFERABLE).isEmpty());
    }

    @Test
    @DisplayName("单项勾选/取消，以及勾选结果保持界面顺序")
    void singleToggleKeepsOrder() {
        MigrateSelection selection = new MigrateSelection();
        selection.setChecked("options.txt", true);
        selection.setChecked("config", true);
        selection.setChecked("options.txt", false);

        assertEquals(List.of("config"), selection.checkedNames().stream().toList());
        assertEquals(List.of("config"), selection.selectedItems(TRANSFERABLE).stream()
                .map(MigrateItem::name).toList());
        assertFalse(selection.isChecked("options.txt"));
    }

    @Test
    @DisplayName("缺失项不在可勾选列表里，永远不会被选上")
    void missingItemsAreNeverSelected() {
        List<ScannedItem> transferable = List.of(entry("config", true, 100L));
        MigrateSelection selection = MigrateSelection.defaults(transferable);
        selection.selectAll(transferable);

        assertEquals(1, selection.selectedCount(transferable));
        assertFalse(selection.isChecked("schematics"), "缺失项（不在 transferable 里）不会被勾选");
    }
}
