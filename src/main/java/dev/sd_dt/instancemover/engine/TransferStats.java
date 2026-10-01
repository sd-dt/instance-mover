package dev.sd_dt.instancemover.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次复制 / 一次转移的统计，对应 mc_transfer.py v1.1 的
 * {@code {"new", "over", "same", "failed", "bytes", "errors"}}。
 *
 * <p>字段对应关系：{@code created↔new}、{@code overwritten↔over}、{@code skipped↔same}、
 * {@code failed↔failed}、{@code bytes↔bytes}、{@code errors↔errors}。</p>
 */
public final class TransferStats {

    private int created;
    private int overwritten;
    private int skipped;
    private int failed;
    private long bytes;
    private final List<String> errors = new ArrayList<>();

    /** 目标原本不存在 → 新增。 */
    public int created() {
        return created;
    }

    /** 目标已存在且内容不同（或超大文件保守重写）→ 覆盖。 */
    public int overwritten() {
        return overwritten;
    }

    /** 目标已存在且内容完全相同 → 跳过。 */
    public int skipped() {
        return skipped;
    }

    /** 失败（不中断整体流程）。 */
    public int failed() {
        return failed;
    }

    /** 实际写入的字节数（只算新增 + 覆盖）。 */
    public long bytes() {
        return bytes;
    }

    /** 失败原因清单，例如 {@code 复制失败：<源> → <目标>（<原因>）}。 */
    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    /** 成功文件数 = 新增 + 覆盖（界面「成功 N」）。 */
    public int successful() {
        return created + overwritten;
    }

    /** 处理过的文件总数 = 成功 + 跳过 + 失败。 */
    public int processed() {
        return created + overwritten + skipped + failed;
    }

    public boolean hasFailures() {
        return failed > 0;
    }

    void incCreated(long size) {
        created++;
        bytes += size;
    }

    void incOverwritten(long size) {
        overwritten++;
        bytes += size;
    }

    void incSkipped() {
        skipped++;
    }

    void incFailed() {
        failed++;
    }

    void addError(String message) {
        errors.add(message);
    }

    /** 对应 py 的 {@code merge_stats()}。 */
    void merge(TransferStats other) {
        created += other.created;
        overwritten += other.overwritten;
        skipped += other.skipped;
        failed += other.failed;
        bytes += other.bytes;
        errors.addAll(other.errors);
    }

    @Override
    public String toString() {
        return "新增 %d，覆盖 %d，相同跳过 %d，失败 %d（%d 字节）"
                .formatted(created, overwritten, skipped, failed, bytes);
    }
}
