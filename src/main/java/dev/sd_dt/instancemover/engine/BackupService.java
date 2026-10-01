package dev.sd_dt.instancemover.engine;

import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.PathUtils;
import dev.sd_dt.instancemover.util.SizeFormatter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 覆盖前备份，对应 mc_transfer.py v1.1 {@code TransferRunner.run()} 里的备份阶段。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>备份目录 = {@code <目标实例>\_转移备份_yyyyMMdd_HHmmss\}；同名已存在时追加 {@code _1}、{@code _2}…；</li>
 *   <li>只备份「目标侧已存在」的条目；一项都没有时只记一行日志、不建目录；</li>
 *   <li>备份时必须真拷：{@code skipSame=false}（即使内容相同也要写进备份）；</li>
 *   <li>备份统计并入总统计，日志 {@code 备份完成：%d 个文件（约 %s）。}（{@code %d} 取新增数）；
 *       <b>有失败条目时改打警告</b>（审查 F2），不再输出会造成误导的「备份完成：0 个文件」，
 *       备份失败不阻断后续覆盖。</li>
 * </ul>
 */
public final class BackupService {

    /** 备份目录前缀（注意是「转移备份」，不是「迁移备份」）。 */
    public static final String BACKUP_PREFIX = "_转移备份_";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Logger LOGGER = LoggerFactory.getLogger("instance_mover");

    private BackupService() {
    }

    /** 当前时间戳 {@code yyyyMMdd_HHmmss}（本地时区）。 */
    public static String timestamp() {
        return timestamp(LocalDateTime.now());
    }

    /** 指定时间的 {@code yyyyMMdd_HHmmss} 时间戳（便于单测）。 */
    public static String timestamp(LocalDateTime moment) {
        return moment.format(STAMP);
    }

    /** 备份目录名（不含序号）：{@code _转移备份_<时间戳>}。 */
    public static String backupDirName(String stamp) {
        return BACKUP_PREFIX + stamp;
    }

    /**
     * 分配一个不冲突的备份目录路径：{@code _转移备份_<时间戳>}，已存在则追加 {@code _1}、{@code _2}…
     *
     * <p>只计算路径，不创建目录（目录由复制过程创建）。</p>
     */
    public static Path allocateBackupDir(Path targetRoot, String stamp) {
        Path candidate = targetRoot.resolve(backupDirName(stamp));
        int index = 1;
        while (PathUtils.pathExists(candidate)) {
            candidate = targetRoot.resolve("%s_%d".formatted(backupDirName(stamp), index));
            index++;
        }
        return candidate;
    }

    /** 需要备份的条目 = 转移列表里、目标侧已存在的那些（对应 py 的 {@code todo}）。 */
    public static List<MigrateItem> itemsToBackup(Path targetRoot, List<MigrateItem> items) {
        List<MigrateItem> todo = new ArrayList<>();
        for (MigrateItem item : items) {
            if (PathUtils.pathExists(item.resolveIn(targetRoot))) {
                todo.add(item);
            }
        }
        return List.copyOf(todo);
    }

    /**
     * 执行备份阶段。
     *
     * @param targetRoot 目标实例根目录（新实例）
     * @param items      本次转移列表
     * @param engine     用于复制的引擎（**不要**传进度回调，py 的备份不推进进度条）
     * @param totals     总统计，备份统计会并入其中（对应 py 的 {@code merge_stats}）
     * @param listener   日志 / 阶段回调
     * @return 备份目录；无事可备时为空
     * @throws TransferCancelled 用户取消
     */
    public static Optional<Path> runBackup(Path targetRoot,
                                           List<MigrateItem> items,
                                           CopyEngine engine,
                                           TransferStats totals,
                                           TransferListener listener) throws TransferCancelled {
        List<MigrateItem> todo = itemsToBackup(targetRoot, items);
        if (todo.isEmpty()) {
            listener.onLog("新实例里没有与转移列表同名的内容，无需备份。");
            return Optional.empty();
        }
        Path backupDir = allocateBackupDir(targetRoot, timestamp());
        listener.onPhase("正在备份新实例中将被覆盖的内容…", true);
        listener.onLog("开始备份新实例中将被覆盖的 %d 项 → %s".formatted(todo.size(), backupDir));
        TransferStats backupStats = new TransferStats();
        for (MigrateItem item : todo) {
            engine.checkCancelled();
            engine.copyItem(item.resolveIn(targetRoot), backupDir.resolve(item.name()), backupStats, false);
        }
        totals.merge(backupStats);
        if (backupStats.hasFailures()) {
            // 审查 F2：备份整体失败时不能再打印「备份完成：0 个文件」这种会造成误导的文案
            LOGGER.warn("备份不完整：{} 项，其中 {} 个文件失败 → {}",
                    todo.size(), backupStats.failed(), backupDir);
            listener.onLog("警告：备份不完整——%d 个文件复制失败（已复制 %d 个），请先手动检查备份目录再继续：%s"
                    .formatted(backupStats.failed(), backupStats.created(), backupDir));
            for (String error : backupStats.errors()) {
                listener.onLog("   " + error);
            }
        } else {
            listener.onLog("备份完成：%d 个文件（约 %s）。"
                    .formatted(backupStats.created(), SizeFormatter.humanSize(backupStats.bytes())));
        }
        return Optional.of(backupDir);
    }
}
