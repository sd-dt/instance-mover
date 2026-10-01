package dev.sd_dt.instancemover.engine;

/**
 * 快速体积估算结果，对应 mc_transfer.py v1.1 的 {@code quick_size()} 三元组。
 *
 * @param bytes     已扫描到的字节数
 * @param fileCount 已扫描到的文件数
 * @param truncated 是否因为「文件数上限 / 时间上限」而提前中止（未扫完）
 */
public record QuickSize(long bytes, long fileCount, boolean truncated) {
}
