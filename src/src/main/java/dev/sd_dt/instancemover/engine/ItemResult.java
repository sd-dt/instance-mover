package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.model.ItemKind;

/**
 * 单个条目的转移结果，对应 mc_transfer.py v1.1 里 {@code per_item} 的一项
 * （{@code (名称, 单条统计)}）。
 *
 * @param name            条目名
 * @param kind            文件夹 / 文件
 * @param stats           该条目的统计（新增 / 覆盖 / 相同跳过 / 失败 / 字节）
 * @param restartRequired 覆盖后是否需要重启游戏才生效
 * @param sourceMissing   源实例里没有这一项（安全跳过，目标未被改动）
 */
public record ItemResult(String name, ItemKind kind, TransferStats stats, boolean restartRequired, boolean sourceMissing) {

    public boolean hasFailures() {
        return stats.failed() > 0;
    }

    /** 该条目是否真的写入了内容（成功新增或覆盖过文件）。 */
    public boolean wroteAnything() {
        return stats.successful() > 0;
    }

    /** 一行中文摘要，例如 {@code config：新增 3，覆盖 1，相同跳过 2}。 */
    public String summary() {
        String base = "%s：新增 %d，覆盖 %d，相同跳过 %d"
                .formatted(name, stats.created(), stats.overwritten(), stats.skipped());
        return stats.failed() > 0 ? base + "，失败 %d".formatted(stats.failed()) : base;
    }
}
