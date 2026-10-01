package dev.sd_dt.instancemover.engine;

/**
 * 全量统计结果，对应 mc_transfer.py v1.1 的 {@code count_items()} / {@code count_files()}。
 *
 * @param fileCount 文件总数
 * @param bytes     总字节数
 */
public record CountResult(long fileCount, long bytes) {
}
