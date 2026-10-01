package dev.sd_dt.instancemover.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次转移的完整报告，对应 mc_transfer.py v1.1 {@code TransferRunner} 发出的
 * {@code done} / {@code cancelled} 消息，外加「需重启」标记与警告。
 *
 * <p>成功 / 跳过 / 失败的区分都在 {@link TransferStats}：
 * 成功 = {@code created + overwritten}，跳过 = {@code skipped}，失败 = {@code failed}。</p>
 */
public final class TransferReport {

    private final Path source;
    private final Path target;
    private final TransferStats totals;
    private final TransferStats transferStats;
    private final TransferStats backupStats;
    private final List<ItemResult> items;
    private final Path backupDir;
    private final long elapsedMillis;
    private final long plannedFileCount;
    private final long plannedBytes;
    private final boolean cancelled;
    private final List<String> warnings;
    private final String error;

    TransferReport(Path source,
                   Path target,
                   TransferStats totals,
                   TransferStats transferStats,
                   TransferStats backupStats,
                   List<ItemResult> items,
                   Path backupDir,
                   long elapsedMillis,
                   long plannedFileCount,
                   long plannedBytes,
                   boolean cancelled,
                   List<String> warnings,
                   String error) {
        this.source = source;
        this.target = target;
        this.totals = totals;
        this.transferStats = transferStats;
        this.backupStats = backupStats;
        this.items = List.copyOf(items);
        this.backupDir = backupDir;
        this.elapsedMillis = elapsedMillis;
        this.plannedFileCount = plannedFileCount;
        this.plannedBytes = plannedBytes;
        this.cancelled = cancelled;
        this.warnings = List.copyOf(warnings);
        this.error = error;
    }

    public Path source() {
        return source;
    }

    public Path target() {
        return target;
    }

    /** 总统计（**含备份**，对齐 py 的 {@code merge_stats}）。 */
    public TransferStats totals() {
        return totals;
    }

    /** 只统计「转移」阶段（不含备份）——结果页的「成功 / 跳过 / 失败」应该用这个。 */
    public TransferStats transferStats() {
        return transferStats;
    }

    /** 只统计「备份」阶段（没备份时全 0）。 */
    public TransferStats backupStats() {
        return backupStats;
    }

    /** 每个条目的结果（顺序与转移列表一致）。 */
    public List<ItemResult> items() {
        return items;
    }

    /** 备份目录（没备份时为 {@code null}）。 */
    public Path backupDir() {
        return backupDir;
    }

    public boolean hasBackup() {
        return backupDir != null;
    }

    /**
     * 备份是否不完整：确实做了备份（有备份目录），但备份阶段有文件失败（审查 F2）。
     *
     * <p>备份失败**不阻断**后续覆盖；结果页据此显示一条醒目警告，提示玩家先手动检查备份目录。</p>
     */
    public boolean backupIncomplete() {
        return backupDir != null && backupStats.hasFailures();
    }

    /** 耗时（毫秒）。 */
    public long elapsedMillis() {
        return elapsedMillis;
    }

    /** 统计阶段得出的计划文件总数（进度分母的来源）。 */
    public long plannedFileCount() {
        return plannedFileCount;
    }

    /** 统计阶段得出的计划总字节数。 */
    public long plannedBytes() {
        return plannedBytes;
    }

    /** 是否被用户取消。 */
    public boolean cancelled() {
        return cancelled;
    }

    /** 警告标记（例如老实例的存档里发现 session.lock）。 */
    public List<String> warnings() {
        return warnings;
    }

    /** 未预期错误（正常结束时为 {@code null}）。 */
    public String error() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }

    /** 需要重启游戏才生效、而且本次真的写入过的条目。 */
    public List<ItemResult> restartRequiredItems() {
        List<ItemResult> result = new ArrayList<>();
        for (ItemResult item : items) {
            if (item.restartRequired() && item.wroteAnything()) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    /** 本次是否需要提示玩家重启游戏。 */
    public boolean restartRequired() {
        return !restartRequiredItems().isEmpty();
    }

    /** 失败清单（转移 + 备份），方便结果页「复制失败原因」一栏直接展示。 */
    public List<String> errors() {
        return totals.errors();
    }

    /** 有失败文件的条目。 */
    public List<ItemResult> failedItems() {
        List<ItemResult> result = new ArrayList<>();
        for (ItemResult item : items) {
            if (item.hasFailures()) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    /** 被安全跳过的条目（源实例里没有）。 */
    public List<ItemResult> skippedItems() {
        List<ItemResult> result = new ArrayList<>();
        for (ItemResult item : items) {
            if (item.sourceMissing()) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    /** 人类可读的一行总结（成功 / 跳过 / 失败来自转移阶段，备份单独说明）。 */
    public String summaryText() {
        String base = "成功 %d（新增 %d / 覆盖 %d），跳过 %d，失败 %d，写入 %s，耗时 %.1f 秒"
                .formatted(
                        transferStats.successful(),
                        transferStats.created(),
                        transferStats.overwritten(),
                        transferStats.skipped(),
                        transferStats.failed(),
                        dev.sd_dt.instancemover.util.SizeFormatter.humanSize(transferStats.bytes()),
                        elapsedMillis / 1000.0);
        if (hasBackup()) {
            return base + "；备份 %d 个文件（约 %s）".formatted(
                    backupStats.successful(),
                    dev.sd_dt.instancemover.util.SizeFormatter.humanSize(backupStats.bytes()));
        }
        return base;
    }
}
