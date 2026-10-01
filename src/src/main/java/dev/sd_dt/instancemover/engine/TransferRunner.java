package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.PathUtils;
import dev.sd_dt.instancemover.util.SizeFormatter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 转移主流程，对应 mc_transfer.py v1.1 的 {@code TransferRunner}。
 *
 * <p>顺序完全照 py：</p>
 * <ol>
 *   <li>校验源 / 目标（相同或互为父子时拒绝执行）；</li>
 *   <li>{@code 正在统计文件…} → 全量统计 → {@code 本次转移 N 项，共 M 个文件（约 X）。}；</li>
 *   <li>可选备份（见 {@link BackupService}）；</li>
 *   <li>{@code 正在转移数据…} → 逐项合并覆盖，每项日志
 *       {@code → 转移 <名称>（文件夹/文件）} + {@code    <名称>：新增 …，覆盖 …，相同跳过 …}；</li>
 *   <li>结束前把进度推进到 {@code (total, max(total, 1))} → {@code onDone}。</li>
 * </ol>
 *
 * <p>取消 → 记一行「已取消（已经复制的内容不会回滚）。」并回调 {@code onCancelled}；
 * 未预期异常 → 回调 {@code onError}，绝不静默吞掉。</p>
 */
public final class TransferRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("instance_mover");

    private final Path source;
    private final Path target;
    private final List<MigrateItem> items;
    private final boolean doBackup;
    private final boolean skipSame;
    private final TransferListener listener;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    /**
     * @param source   老实例根目录
     * @param target   新实例（当前游戏目录）
     * @param items    本次要转移的条目（默认项 + 玩家勾选的询问项）
     * @param doBackup 转移前是否备份目标侧将被覆盖的内容
     * @param listener 进度 / 日志 / 结果回调（可为 {@code null}，等价于全空实现）
     */
    public TransferRunner(Path source, Path target, List<MigrateItem> items, boolean doBackup,
                          TransferListener listener) {
        this(source, target, items, doBackup, listener, true);
    }

    /**
     * 同上，另可指定是否跳过「内容完全相同」的文件（界面上的「跳过完全相同文件」开关）。
     *
     * @param skipSame {@code true}（默认，与 py 一致）内容相同就跳过；{@code false} 全部重写一遍
     */
    public TransferRunner(Path source, Path target, List<MigrateItem> items, boolean doBackup,
                          TransferListener listener, boolean skipSame) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        this.doBackup = doBackup;
        this.skipSame = skipSame;
        this.listener = listener == null ? new TransferListener() {
        } : listener;
    }

    /**
     * 校验源与目标：两者都必须存在、是文件夹，且**不能是同一个目录**。
     *
     * <p><b>互为父子是合法的</b>：版本隔离下最常见的两种布局就是
     * 「老实例 = {@code .minecraft} 根 / 当前实例 = {@code .minecraft\versions\新包}」及其反向，
     * 这里必须放行（否则玩家点开始迁移会一个文件都不复制）。真正危险的自我复制由
     * {@link #validatePaths(Path, Path, List)} 与逐条目守卫处理。</p>
     *
     * <p>「同一个目录」的判定容忍 {@code \\?\} 长路径前缀与大小写差异，见
     * {@link PathUtils#sameDirectory(Path, Path)}。</p>
     *
     * @throws IllegalArgumentException 校验不通过（消息为中文，可直接显示给玩家）
     */
    public static void validatePaths(Path source, Path target) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("源实例和目标实例路径都不能为空。");
        }
        Path src = source.toAbsolutePath().normalize();
        Path dst = target.toAbsolutePath().normalize();
        if (!PathUtils.isDir(src)) {
            throw new IllegalArgumentException("老实例目录不存在或不是文件夹：" + src);
        }
        if (!PathUtils.isDir(dst)) {
            throw new IllegalArgumentException("当前实例目录不存在或不是文件夹：" + dst);
        }
        if (PathUtils.sameDirectory(src, dst)) {
            throw new IllegalArgumentException("老实例与当前实例是同一个目录，已拒绝执行：" + src);
        }
    }

    /**
     * 在 {@link #validatePaths(Path, Path)} 的基础上，额外整体拒绝**唯一**那种自我复制：
     * 老实例根整个落在当前实例的**某个待迁移条目目录**内部
     * （例如有人把老实例选成了 {@code <当前实例>\config\backup}）。
     *
     * <p>此时把该条目复制过去 = 把整棵老实例复制进它自己的子树里，逐条目守卫救不了，必须整体拒绝。
     * 注意「老实例 = {@code .minecraft\versions\旧包}、当前实例 = {@code .minecraft} 根」这种反向布局
     * 不在拒绝之列：{@code versions} 不是迁移条目，源树不会落进任何条目目录，迁移是安全的。</p>
     *
     * <p><b>判断用真路径</b>（{@link PathUtils#isRealAncestorOf(Path, Path)}，内部含
     * {@code Files.isSameFile} 与 {@code toRealPath}），所以别名路径（junction / 符号链接 / 8.3 短名）
     * 绕不过去：例如 {@code D:\alias} 是指向 {@code D:\mc\config} 的 junction、源选成
     * {@code D:\alias\backup} 时，字符串判定会漏判，这里仍然会整体拒绝。</p>
     *
     * @throws IllegalArgumentException 命中自我复制（消息为中文）
     */
    public static void validatePaths(Path source, Path target, List<MigrateItem> items) {
        validatePaths(source, target);
        if (items == null || items.isEmpty()) {
            return;
        }
        Path src = PathUtils.withoutLongPathPrefix(source);
        Path dst = PathUtils.withoutLongPathPrefix(target);
        for (MigrateItem item : items) {
            Path dstItem = dst.resolve(item.name());
            if (PathUtils.sameDirectory(src, dstItem) || PathUtils.isRealAncestorOf(dstItem, src)) {
                throw new IllegalArgumentException(
                        "老实例位于当前实例的「%s」里面，迁移这一项会把整棵老实例复制进它自己，已拒绝执行：%s"
                                .formatted(item.name(), src));
            }
        }
    }

    /** 请求取消；正在进行的文件写完后停止（已经复制的内容不回滚）。 */
    public void cancel() {
        cancel.set(true);
    }

    public boolean isCancelled() {
        return cancel.get();
    }

    public Path source() {
        return source;
    }

    public Path target() {
        return target;
    }

    /** 后台守护线程跑一次，返回最终报告。 */
    public CompletableFuture<TransferReport> runAsync() {
        CompletableFuture<TransferReport> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                future.complete(run());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "instance-mover-transfer");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    /**
     * 同步执行一次转移。所有结果（含失败与取消）都通过 {@link TransferReport} 返回，
     * 不向调用方抛异常，方便界面直接调用。
     */
    public TransferReport run() {
        long startedNanos = System.nanoTime();
        TransferStats totals = new TransferStats();
        TransferStats transferTotals = new TransferStats();
        TransferStats backupTotals = new TransferStats();
        List<ItemResult> perItem = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long plannedFiles = 0L;
        long plannedBytes = 0L;
        Path backupDir = null;
        boolean cancelled = false;
        String error = null;

        try {
            validatePaths(source, target, items);

            listener.onPhase("正在统计文件…", true);
            CountResult counted = ScanService.countItems(source, items);
            plannedFiles = counted.fileCount();
            plannedBytes = counted.bytes();
            listener.onLog("本次转移 %d 项，共 %d 个文件（约 %s）。"
                    .formatted(items.size(), plannedFiles, SizeFormatter.humanSize(plannedBytes)));

            if (containsSaves()) {
                warnings.addAll(ScanService.sessionLockWarnings(source.resolve("saves")));
                for (String warning : warnings) {
                    listener.onLog("警告：" + warning);
                }
            }

            long[] done = {0L};
            long denominator = Math.max(plannedFiles, 1L);
            TransferStats[] currentItem = {null};
            Runnable onFile = () -> {
                done[0]++;
                listener.onProgress(done[0], denominator);
                TransferStats stats = currentItem[0];
                if (stats != null) {
                    // 已完成条目 + 当前条目 → 运行中的已写入字节数（界面「已复制体积」）
                    listener.onBytesWritten(transferTotals.bytes() + stats.bytes());
                }
            };

            CopyEngine engine = new CopyEngine(cancel::get, listener::onLog, onFile);

            if (doBackup) {
                CopyEngine backupEngine = new CopyEngine(cancel::get, listener::onLog, null);
                backupDir = BackupService.runBackup(target, items, backupEngine, backupTotals, listener)
                        .orElse(null);
                totals.merge(backupTotals);
            }

            listener.onPhase("正在转移数据…", false);
            for (MigrateItem item : items) {
                engine.checkCancelled();
                Path src = item.resolveIn(source);
                Path dst = item.resolveIn(target);
                boolean sourceMissing = !PathUtils.isDir(src) && !PathUtils.isFile(src);
                TransferStats itemStats = new TransferStats();
                currentItem[0] = itemStats;
                listener.onLog("→ 转移 %s（%s）".formatted(item.name(), item.label()));

                // 条目级嵌套守卫（真路径判定，抗别名）：目标位置落在源文件夹内部 → 只跳过这一项，不删不覆盖目标数据
                if (PathUtils.isRealAncestorOf(src, dst)) {
                    itemStats.incFailed();
                    itemStats.addError("已跳过：目标位置 %s 位于源文件夹 %s 内部，会把自己复制进自己（该条目未执行）。"
                            .formatted(dst, src));
                    transferTotals.merge(itemStats);
                    totals.merge(itemStats);
                    ItemResult skippedResult = new ItemResult(item.name(), item.kind(), itemStats,
                            item.restartRequired(), sourceMissing);
                    perItem.add(skippedResult);
                    listener.onLog("   " + skippedResult.summary());
                    listener.onLog("   " + itemStats.errors().getFirst());
                    continue;
                }

                engine.copyItem(src, dst, itemStats, skipSame);
                transferTotals.merge(itemStats);
                totals.merge(itemStats);
                ItemResult result = new ItemResult(item.name(), item.kind(), itemStats,
                        item.restartRequired(), sourceMissing);
                perItem.add(result);
                listener.onLog("   " + result.summary());
                if (sourceMissing) {
                    listener.onLog("   %s：老实例里没有这一项，已跳过。".formatted(item.name()));
                }
            }

            listener.onProgress(plannedFiles, denominator);
            TransferReport report = new TransferReport(source, target, totals, transferTotals, backupTotals,
                    perItem, backupDir, (System.nanoTime() - startedNanos) / 1_000_000L, plannedFiles,
                    plannedBytes, false, warnings, null);
            listener.onDone(report);
            return report;
        } catch (TransferCancelled ex) {
            cancelled = true;
            listener.onLog("已取消（已经复制的内容不会回滚）。");
        } catch (Exception ex) {
            error = "%s：%s".formatted(ex.getClass().getSimpleName(), ex.getMessage());
            LOGGER.error("转移过程出现未预期错误", ex);
            listener.onError(ex);
        }

        TransferReport report = new TransferReport(source, target, totals, transferTotals, backupTotals,
                perItem, backupDir, (System.nanoTime() - startedNanos) / 1_000_000L, plannedFiles,
                plannedBytes, cancelled, warnings, error);
        if (cancelled) {
            listener.onCancelled(report);
        }
        return report;
    }

    private boolean containsSaves() {
        return items.stream().anyMatch(item -> "saves".equals(item.name()));
    }
}
