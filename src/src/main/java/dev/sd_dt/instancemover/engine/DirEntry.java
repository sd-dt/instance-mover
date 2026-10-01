package dev.sd_dt.instancemover.engine;

import java.util.List;

/**
 * 目录遍历的一个结果，对应 mc_transfer.py v1.1 的 {@code walk_dir()} 产出的
 * {@code (相对目录, [文件名], 错误信息)}。
 *
 * @param relative 相对于遍历起点的目录（起点本身是空串）
 * @param files    该目录下的文件名（已排序，符号链接不计入）
 * @param error    读取该目录失败时的原因，成功为 {@code null}
 */
public record DirEntry(String relative, List<String> files, String error) {

    public boolean hasError() {
        return error != null;
    }
}
