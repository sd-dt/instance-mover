package dev.sd_dt.instancemover.engine;

import java.nio.file.Path;

/**
 * 实例路径规整结果，对应 mc_transfer.py v1.1 的 {@code normalize_instance_path()} 返回值。
 *
 * @param path   规整后的路径
 * @param notice 自动下钻时给用户的提示，未下钻为 {@code null}
 */
public record NormalizedPath(Path path, String notice) {

    public boolean changed() {
        return notice != null;
    }
}
