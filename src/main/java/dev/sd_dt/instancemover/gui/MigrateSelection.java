package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.engine.ScannedItem;
import dev.sd_dt.instancemover.model.MigrateItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 勾选状态（纯逻辑，可直接单测）：只保存「被勾选的条目名」。
 *
 * <p>能被勾选的只有老实例里真实存在的条目（默认项 present + 询问项 extras），
 * 缺失项永远不会出现在勾选区里（界面单独列出并说明自动跳过）。</p>
 */
public final class MigrateSelection {

    private final Set<String> checked = new LinkedHashSet<>();

    /** 默认勾选态：清单里 {@code defaultOn} 为真的那些（默认 11 项 + 默认勾选的询问项）。 */
    public static MigrateSelection defaults(List<ScannedItem> transferable) {
        MigrateSelection selection = new MigrateSelection();
        for (ScannedItem entry : transferable) {
            if (entry.defaultOn()) {
                selection.checked.add(entry.name());
            }
        }
        return selection;
    }

    public boolean isChecked(String name) {
        return checked.contains(name);
    }

    public void setChecked(String name, boolean value) {
        if (value) {
            checked.add(name);
        } else {
            checked.remove(name);
        }
    }

    /** 全选（只作用于可勾选列表）。 */
    public void selectAll(List<ScannedItem> transferable) {
        for (ScannedItem entry : transferable) {
            checked.add(entry.name());
        }
    }

    /** 全不选。 */
    public void clearAll() {
        checked.clear();
    }

    /** 反选。 */
    public void invert(List<ScannedItem> transferable) {
        for (ScannedItem entry : transferable) {
            if (!checked.remove(entry.name())) {
                checked.add(entry.name());
            }
        }
    }

    /** 仅保留默认勾选态。 */
    public void defaultsOnly(List<ScannedItem> transferable) {
        checked.clear();
        for (ScannedItem entry : transferable) {
            if (entry.defaultOn()) {
                checked.add(entry.name());
            }
        }
    }

    /** 勾选到的条目（保持界面顺序），直接交给 {@code TransferRunner}。 */
    public List<MigrateItem> selectedItems(List<ScannedItem> transferable) {
        List<MigrateItem> items = new ArrayList<>();
        for (ScannedItem entry : transferable) {
            if (checked.contains(entry.name())) {
                items.add(entry.item());
            }
        }
        return List.copyOf(items);
    }

    public int selectedCount(List<ScannedItem> transferable) {
        int count = 0;
        for (ScannedItem entry : transferable) {
            if (checked.contains(entry.name())) {
                count++;
            }
        }
        return count;
    }

    /** 勾选内容的体积合计（估算值可能被截断）。 */
    public long selectedBytes(List<ScannedItem> transferable) {
        long bytes = 0L;
        for (ScannedItem entry : transferable) {
            if (checked.contains(entry.name())) {
                bytes += entry.bytes();
            }
        }
        return bytes;
    }

    /** 勾选的条目名（只读，便于日志与测试）。 */
    public Set<String> checkedNames() {
        return Set.copyOf(checked);
    }
}
