package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.model.ItemKind;
import dev.sd_dt.instancemover.model.MigrateItem;
import java.util.List;

/**
 * 扫描结果里的一条（界面直接用它渲染勾选列表），对应 mc_transfer.py v1.1
 * {@code scan_instance()} 里的 present / missing / extras 三个元组。
 *
 * @param item          清单条目（名称、类型、作用文案、默认勾选、是否需重启）
 * @param present       在老实例里是否存在（类型必须匹配）
 * @param missingReason 不存在的原因：{@code "不存在"} 或 {@code "类型不符"}；存在时为 {@code null}
 * @param bytes         体积估算（快速估算，可能被截断）
 * @param fileCount     文件数估算（快速估算，可能被截断）
 * @param sizeTruncated 体积估算是否未扫完
 * @param sizeNote      界面直接显示的体积说明，例如 {@code 文件夹，约 1.2 GB / 128 个文件}
 * @param warnings      警告标记（例如存档里有 session.lock）
 */
public record ScannedItem(
        MigrateItem item,
        boolean present,
        String missingReason,
        long bytes,
        long fileCount,
        boolean sizeTruncated,
        String sizeNote,
        List<String> warnings) {

    public ScannedItem {
        warnings = List.copyOf(warnings);
    }

    public String name() {
        return item.name();
    }

    public ItemKind kind() {
        return item.kind();
    }

    /** 类型中文名（「文件夹」/「文件」）。 */
    public String label() {
        return item.label();
    }

    /** 作用说明（逐字照抄 v1.1）。 */
    public String description() {
        return item.description();
    }

    /** 是否默认勾选。 */
    public boolean defaultOn() {
        return item.defaultOn();
    }

    /** 覆盖后是否需要重启游戏才生效。 */
    public boolean restartRequired() {
        return item.restartRequired();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
