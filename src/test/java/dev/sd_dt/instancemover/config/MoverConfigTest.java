package dev.sd_dt.instancemover.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 标记文件 config/instance_mover.json 的读写（纯 Java，可单测）。 */
class MoverConfigTest {

    @Test
    @DisplayName("默认值：文件不存在时 firstRunDone=false、两个开关都开")
    void defaultsWhenFileMissing(@TempDir Path base) {
        MoverConfig config = MoverConfig.load(base.resolve("config/instance_mover.json"));

        assertFalse(config.isFirstRunDone());
        assertEquals("", config.lastSource());
        assertTrue(config.isBackupEnabled(), "默认开启备份");
        assertTrue(config.isSkipSameEnabled(), "默认跳过完全相同的文件（与 py 一致）");
    }

    @Test
    @DisplayName("存盘 → 读回：四个字段都保持一致（含中文路径）")
    void saveThenLoadRoundTrip(@TempDir Path base) throws IOException {
        Path file = MoverConfig.defaultFile(base);
        assertEquals(base.resolve("config").resolve("instance_mover.json"), file);

        MoverConfig config = new MoverConfig()
                .setFirstRunDone(true)
                .setLastSource("D:\\1sd_dt\\老实例\\versions\\老整合包-26.1")
                .setBackupEnabled(false)
                .setSkipSameEnabled(false);
        config.save(file);

        assertTrue(Files.isRegularFile(file), "配置文件必须真的落盘");
        MoverConfig loaded = MoverConfig.load(file);
        assertTrue(loaded.isFirstRunDone());
        assertEquals("D:\\1sd_dt\\老实例\\versions\\老整合包-26.1", loaded.lastSource());
        assertFalse(loaded.isBackupEnabled());
        assertFalse(loaded.isSkipSameEnabled());
    }

    @Test
    @DisplayName("文件损坏 / 不是 JSON 对象 / 字段类型不对：一律回退默认值，不抛异常")
    void corruptFileFallsBackToDefaults(@TempDir Path base) throws IOException {
        Path file = MoverConfig.defaultFile(base);

        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ 这不是合法 JSON ", StandardCharsets.UTF_8);
        MoverConfig broken = MoverConfig.load(file);
        assertFalse(broken.isFirstRunDone());

        Files.writeString(file, "[1, 2, 3]", StandardCharsets.UTF_8);
        assertFalse(MoverConfig.load(file).isFirstRunDone(), "数组不是配置对象");

        Files.writeString(file, """
                {"firstRunDone": "yes", "lastSource": 42, "backupEnabled": null}
                """, StandardCharsets.UTF_8);
        MoverConfig wrongTypes = MoverConfig.load(file);
        assertFalse(wrongTypes.isFirstRunDone(), "字符串不该被当成布尔值");
        assertEquals("", wrongTypes.lastSource(), "数字不该被当成字符串");
        assertTrue(wrongTypes.isBackupEnabled(), "null 回退默认值");
    }

    @Test
    @DisplayName("未知字段被忽略，已知字段照常读取")
    void unknownFieldsAreIgnored(@TempDir Path base) throws IOException {
        Path file = MoverConfig.defaultFile(base);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"firstRunDone": true, "lastSource": "D:/x", "未来字段": {"a": 1}}
                """, StandardCharsets.UTF_8);

        MoverConfig config = MoverConfig.load(file);
        assertTrue(config.isFirstRunDone());
        assertEquals("D:/x", config.lastSource());
    }

    @Test
    @DisplayName("写入用临时文件 + 原子替换：不留临时文件，重复保存内容稳定")
    void atomicWriteLeavesNoTempFiles(@TempDir Path base) throws IOException {
        Path file = MoverConfig.defaultFile(base);
        MoverConfig config = new MoverConfig().setLastSource("D:/old");

        config.save(file);
        config.setLastSource("D:/new");
        config.save(file);

        try (var stream = Files.list(file.getParent())) {
            List<String> names = stream.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("instance_mover.json"), names, "config 目录里只该有标记文件本身");
        }
        assertEquals("D:/new", MoverConfig.load(file).lastSource());

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"firstRunDone\""));
        assertTrue(text.contains("\"lastSource\""));
        assertTrue(text.contains("\"backupEnabled\""));
        assertTrue(text.contains("\"skipSameEnabled\""));
    }

    @Test
    @DisplayName("saveQuietly：路径写不了时返回 false，不抛异常")
    void saveQuietlyNeverThrows(@TempDir Path base) throws IOException {
        Path blocker = base.resolve("blocker");
        Files.writeString(blocker, "这是个文件，不能当目录");
        Path impossible = blocker.resolve("instance_mover.json");

        assertFalse(new MoverConfig().saveQuietly(impossible), "写不进去必须返回 false");
        assertFalse(new MoverConfig().saveQuietly(null));
    }

    @Test
    @DisplayName("t23：resetFirstRun 置回未处理并落盘，读回仍是 false（关于页「重新显示迁移引导」的落盘路径）")
    void resetFirstRunPersistsFalse(@TempDir Path base) throws IOException {
        Path file = MoverConfig.defaultFile(base);

        // 先模拟「迁移成功一次」：写 true 落盘
        MoverConfig config = new MoverConfig().setLastSource("D:/old").setFirstRunDone(true);
        config.save(file);
        assertTrue(MoverConfig.load(file).isFirstRunDone(), "前置：文件里确实是 true");

        // 关于页点一下开关：resetFirstRun + 立即落盘
        config.resetFirstRun().save(file);

        assertFalse(config.isFirstRunDone(), "内存里立刻变 false");
        MoverConfig reloaded = MoverConfig.load(file);
        assertFalse(reloaded.isFirstRunDone(), "文件里也必须是 false");
        assertEquals("D:/old", reloaded.lastSource(), "重置引导不能把 lastSource 等状态弄丢");
    }

    @Test
    @DisplayName("t23：resetFirstRun 只改内存，是否落盘由调用方决定（保存失败可回滚）")
    void resetFirstRunDoesNotTouchDiskByItself(@TempDir Path base) throws IOException {
        Path file = MoverConfig.defaultFile(base);
        MoverConfig config = new MoverConfig().setFirstRunDone(true);
        config.save(file);

        config.resetFirstRun();
        assertFalse(config.isFirstRunDone());
        assertTrue(MoverConfig.load(file).isFirstRunDone(), "没调 save 之前，文件里还是 true（便于失败时回滚）");
    }

    @Test
    @DisplayName("defaultFile 指向 <游戏目录>/config/instance_mover.json")
    void defaultFileLocation(@TempDir Path base) {
        Path file = MoverConfig.defaultFile(base);
        assertNotNull(file.getParent());
        assertEquals("config", file.getParent().getFileName().toString());
        assertEquals(MoverConfig.FILE_NAME, file.getFileName().toString());
    }
}
