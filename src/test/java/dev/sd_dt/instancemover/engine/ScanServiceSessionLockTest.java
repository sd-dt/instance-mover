package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import dev.sd_dt.instancemover.util.PathUtils;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * session.lock 告警收紧（审查 F4）：正常退出后留下的旧 lock 不再告警，
 * 但**确实在用的存档仍然必须告警**（这把功能改废的反向要求）。
 */
class ScanServiceSessionLockTest {

    private static final long OLD_EPOCH_SECONDS = Instant.parse("2020-01-01T00:00:00Z").getEpochSecond();

    /** 建一个存档目录；lockMtime 为 null 表示不创建 session.lock。 */
    private static Path save(Path savesDir, String name, Long lockMtimeEpochSeconds) {
        Path world = savesDir.resolve(name);
        TestSupport.write(world.resolve("level.dat"), "存档 " + name);
        if (lockMtimeEpochSeconds != null) {
            Path lock = world.resolve("session.lock");
            TestSupport.write(lock, "lock");
            try {
                Files.setLastModifiedTime(PathUtils.lp(lock),
                        FileTime.from(Instant.ofEpochSecond(lockMtimeEpochSeconds)));
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }
        return world;
    }

    @Test
    @DisplayName("很久以前（正常退出遗留）的 session.lock 不再告警")
    void oldSessionLockIsSilent(@TempDir Path base) {
        save(base, "老存档", OLD_EPOCH_SECONDS);
        save(base, "另一个老存档", OLD_EPOCH_SECONDS - 86400L * 400L);

        List<String> warnings = ScanService.sessionLockWarnings(base);

        assertTrue(warnings.isEmpty(), "旧的 session.lock 不该再刷告警：" + warnings);
        assertFalse(ScanService.isSaveProbablyInUse(base.resolve("老存档")));
    }

    @Test
    @DisplayName("★ 确实在用的存档（新近 mtime 的 session.lock）仍然必须告警")
    void freshSessionLockStillWarns(@TempDir Path base) {
        save(base, "正在玩的存档", Instant.now().getEpochSecond());

        List<String> warnings = ScanService.sessionLockWarnings(base);

        assertEquals(1, warnings.size(), "新近写过的 session.lock 必须告警：" + warnings);
        assertTrue(warnings.getFirst().contains("正在玩的存档"), warnings.toString());
        assertEquals(ScanService.SESSION_LOCK_WARNING_FORMAT.formatted("正在玩的存档"), warnings.getFirst(),
                "告警文案必须与语言文件里的键一致（LangFilesTest 也校验这一条）");
        assertTrue(ScanService.isSaveProbablyInUse(base.resolve("正在玩的存档")));
    }

    @Test
    @DisplayName("★ 锁被别的进程/线程持有时，即使 mtime 很老也必须告警（真·正在游玩）")
    void heldSessionLockStillWarnsEvenIfOld(@TempDir Path base) throws Exception {
        Path world = save(base, "被锁住的存档", OLD_EPOCH_SECONDS);
        Path lock = world.resolve("session.lock");

        // 模拟「游戏正在运行」：像原版 SessionLock 一样持有排他锁
        try (FileChannel channel = FileChannel.open(PathUtils.lp(lock), StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            assertNotNull(ignored, "前置条件：本进程应能拿到排他锁");
            List<String> warnings = ScanService.sessionLockWarnings(base);
            assertEquals(1, warnings.size(), "锁被持有时必须告警（mtime 再老也要报）：" + warnings);
            assertTrue(ScanService.isSaveProbablyInUse(world));
        }

        // 释放锁后：又变回「旧 lock」→ 不再告警
        assertTrue(ScanService.sessionLockWarnings(base).isEmpty(), "锁释放后旧的 session.lock 应静默");
    }

    @Test
    @DisplayName("没有 session.lock 的存档从不告警；saves 目录不存在也不炸")
    void withoutSessionLockNoWarning(@TempDir Path base) {
        save(base, "干净存档", null);

        assertTrue(ScanService.sessionLockWarnings(base).isEmpty());
        assertTrue(ScanService.sessionLockWarnings(base.resolve("不存在的 saves")).isEmpty());
        assertTrue(ScanService.sessionLockWarnings(null).isEmpty());
        assertFalse(ScanService.isSaveProbablyInUse(null));
        assertFalse(ScanService.isSaveProbablyInUse(base.resolve("干净存档")), "没有 lock 文件 → 不在使用中");
    }

    @Test
    @DisplayName("scanInstance 的 saves 条目按同一口径给警告标记")
    void scanInstanceUsesTightenedRule(@TempDir Path base) {
        Path instance = base.resolve("老实例");
        save(instance.resolve("saves"), "旧存档", OLD_EPOCH_SECONDS);
        save(instance.resolve("saves"), "新存档", Instant.now().getEpochSecond());
        TestSupport.write(instance.resolve("config/a.toml"), "配置");

        var saves = ScanService.scanInstance(instance).present().stream()
                .filter(entry -> entry.name().equals("saves"))
                .findFirst()
                .orElseThrow();

        assertEquals(1, saves.warnings().size(), "只该告警新近的那个存档：" + saves.warnings());
        assertTrue(saves.warnings().getFirst().contains("新存档"), saves.warnings().toString());
    }

    @Test
    @DisplayName("时间窗常量是收紧过的（15 分钟），且 15 分钟前的 lock 不再告警")
    void recentWindowBoundary(@TempDir Path base) throws Exception {
        assertEquals(15L * 60L, ScanService.SESSION_LOCK_RECENT_SECONDS);

        Path world = save(base, "刚过窗口的存档", null);
        Path lock = world.resolve("session.lock");
        TestSupport.write(lock, "lock");
        long justOutside = Instant.now().minus(ScanService.SESSION_LOCK_RECENT_SECONDS + 60L, ChronoUnit.SECONDS)
                .getEpochSecond();
        Files.setLastModifiedTime(PathUtils.lp(lock), FileTime.from(Instant.ofEpochSecond(justOutside)));

        assertTrue(ScanService.sessionLockWarnings(base).isEmpty(), "超出时间窗就不该再告警");
    }
}
