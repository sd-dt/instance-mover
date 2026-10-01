package dev.sd_dt.instancemover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sd_dt.instancemover.engine.ScanService;
import dev.sd_dt.instancemover.engine.TransferListener;
import dev.sd_dt.instancemover.engine.TransferReport;
import dev.sd_dt.instancemover.engine.TransferRunner;
import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.InstanceScanner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 语言文件自检：zh_cn 与 en_us 键集合一致、JSON 合法、占位符一致、且关键文案与引擎里
 * 实际输出的中文对得上（避免界面显示的和引擎日志/校验文案两套说法）。
 *
 * <p><b>「仅中文」口径（t17 定调，用户要求「提示都使用中文」）</b>：本项目界面只提供中文，
 * {@code en_us.json} 仍然保留、但值改成与 {@code zh_cn.json} 完全相同的中文
 * —— 因为代码用的是 {@code Component.translatable(...)}，英文语言环境下若没有这份文件，
 * Minecraft 会直接显示原始键名（例如 {@code instance_mover.button.start}），比显示中文更糟。
 * 所以本测试断言「两份文件的值逐一相同」，而不是「en_us 必须是英文」。
 * 文件内部另有一条 {@code instance_mover.lang.note} 说明这件事。</p>
 */
class LangFilesTest {

    private static final String ZH = "/assets/instance_mover/lang/zh_cn.json";
    private static final String EN = "/assets/instance_mover/lang/en_us.json";
    private static final String KEY_PREFIX = "instance_mover.";

    private static final Pattern PLACEHOLDER = Pattern.compile("%[sd]");

    private static JsonObject loadJson(String path) throws IOException {
        try (InputStream in = LangFilesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到语言文件：" + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }

    private static Map<String, String> flatten(JsonObject json, String path) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement value = entry.getValue();
            assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
                    "%s 里的 %s 不是字符串（语言文件必须是扁平的 key→字符串）".formatted(path, entry.getKey()));
            map.put(entry.getKey(), value.getAsString());
        }
        return map;
    }

    private static Map<String, String> lang(String path) throws IOException {
        return flatten(loadJson(path), path);
    }

    private static List<String> placeholders(String value) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    @DisplayName("两个语言文件都是合法 JSON 且键集合完全一致")
    void keySetsMatch() throws IOException {
        Map<String, String> zh = lang(ZH);
        Map<String, String> en = lang(EN);

        assertTrue(zh.size() >= 50, "中文语言文件键太少，疑似被截断：" + zh.size());
        assertEquals(new TreeSet<>(zh.keySet()), new TreeSet<>(en.keySet()),
                "zh_cn 与 en_us 的键必须一一对应");
        assertTrue(zh.keySet().stream().allMatch(key -> key.startsWith(KEY_PREFIX)),
                "所有键都要以 " + KEY_PREFIX + " 开头");
        for (Map.Entry<String, String> entry : zh.entrySet()) {
            assertFalse(entry.getValue().isBlank(), entry.getKey() + " 的中文值为空");
            assertFalse(en.get(entry.getKey()).isBlank(), entry.getKey() + " 的英文值为空");
            assertEquals(entry.getValue().trim(), entry.getValue(), entry.getKey() + " 中文值首尾有空白");
        }
    }

    @Test
    @DisplayName("占位符 %s/%d 在两个语言文件里逐键一致，且没有非法 % 字符")
    void placeholdersMatch() throws IOException {
        Map<String, String> zh = lang(ZH);
        Map<String, String> en = lang(EN);

        for (Map.Entry<String, String> entry : zh.entrySet()) {
            List<String> zhSlots = placeholders(entry.getValue());
            List<String> enSlots = placeholders(en.get(entry.getKey()));
            assertEquals(zhSlots, enSlots,
                    entry.getKey() + " 的占位符不一致：zh=" + zhSlots + " en=" + enSlots);
            for (String value : List.of(entry.getValue(), en.get(entry.getKey()))) {
                String residue = value.replace("%s", "").replace("%d", "").replace("%%", "");
                assertFalse(residue.contains("%"),
                        entry.getKey() + " 有非法 % 用法：" + value);
            }
        }
    }

    @Test
    @DisplayName("中文文案与模组定稿一致")
    void chineseStringsMatchSpec() throws IOException {
        Map<String, String> zh = lang(ZH);

        assertEquals("实例酱的搬家服务", zh.get("instance_mover.mod_name"));
        assertEquals("该模组由 deepseek 和 sd_dt 编写", zh.get("instance_mover.author_line"));
        assertEquals("玩家数据迁移  v1.0", zh.get("instance_mover.screen.title"));
        assertEquals("① 选择老实例", zh.get("instance_mover.section.source"));
        assertTrue(zh.get("instance_mover.source.detected").contains("版本实例"));
        assertTrue(zh.get("instance_mover.items.group.extra").contains("额外玩家信息"));
        assertTrue(zh.get("instance_mover.items.restart_badge").contains("重启"));
        assertEquals("开始迁移", zh.get("instance_mover.button.start"));
        assertEquals("稍后再说", zh.get("instance_mover.button.later"));
        assertEquals("不再提示", zh.get("instance_mover.button.never"));
        assertEquals("退出游戏", zh.get("instance_mover.button.quit_game"));
        assertEquals("需要重启游戏", zh.get("instance_mover.restart.title"));
        assertTrue(zh.get("instance_mover.restart.body").contains("配置类"));
        assertTrue(zh.get("instance_mover.result.title").contains("迁移完成"));
        assertEquals("已取消", zh.get("instance_mover.result.cancelled_title"));
    }

    @Test
    @DisplayName("「仅中文」口径：en_us 与 zh_cn 的值逐一相同，且不再出现英文文案")
    void englishFileIsChineseOnly() throws IOException {
        Map<String, String> zh = lang(ZH);
        Map<String, String> en = lang(EN);

        assertEquals(zh.keySet(), en.keySet(), "两份语言文件的键集合必须一致");

        List<String> different = new ArrayList<>();
        for (Map.Entry<String, String> entry : zh.entrySet()) {
            if (!entry.getValue().equals(en.get(entry.getKey()))) {
                different.add("%s：zh=「%s」/ en=「%s」"
                        .formatted(entry.getKey(), entry.getValue(), en.get(entry.getKey())));
            }
        }
        assertTrue(different.isEmpty(), "en_us 必须与 zh_cn 完全相同（仅中文），不同项：" + different);

        // 旧的半英文状态（曾断言 en 是英文）必须彻底消失
        assertEquals("实例酱的搬家服务", en.get("instance_mover.mod_name"));
        assertEquals("玩家数据迁移  v1.0", en.get("instance_mover.screen.title"));
        assertEquals("开始迁移", en.get("instance_mover.button.start"));
        assertEquals("需要重启游戏", en.get("instance_mover.restart.title"));
        assertEquals("关于", en.get("instance_mover.about.title"));

        // 不再有任何「看上去是英文句子」的值（模组名/作者行里的 deepseek、sd_dt、v1.0、%s 等不算）
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String value = entry.getValue();
            assertFalse(value.isBlank(), "语言值不能为空：" + entry.getKey());
            assertFalse(value.contains("\uFFFD"), "出现替换字符 U+FFFD：" + entry.getKey());
            assertFalse(value.matches(".*\\b(Instance Mover|Start migration|Restart required|About|"
                            + "Later|Never ask again|Player data migration|Open backup folder|Copy error list)\\b.*"),
                    "仍在 en_us 里出现英文文案：" + entry.getKey() + " = " + value);
            assertTrue(value.codePoints().anyMatch(cp -> cp > 0x2000) || value.contains("deepseek")
                            || value.contains("sd_dt") || !value.matches("[\\x00-\\x7F]*"),
                    "值看起来仍是纯 ASCII（可能没换成中文）：" + entry.getKey() + " = " + value);
        }
    }

    @Test
    @DisplayName("文件内有「仅中文」说明键，且两份一致")
    void hasChineseOnlyNoteKey() throws IOException {
        Map<String, String> zh = lang(ZH);
        Map<String, String> en = lang(EN);
        String note = zh.get("instance_mover.lang.note");
        assertNotNull(note, "语言文件里应有 instance_mover.lang.note 说明「仅中文」口径");
        assertTrue(note.contains("只提供中文"), note);
        assertTrue(note.contains("en_us"), note);
        assertEquals(note, en.get("instance_mover.lang.note"), "说明键两份必须一致");
    }

    @Test
    @DisplayName("语言文件与引擎实际文案对得上（阶段名 / 下钻提示 / session.lock 警告 / 校验原因）")
    void langMatchesEngineStrings(@TempDir Path base) throws Exception {
        Map<String, String> zh = lang(ZH);

        assertEquals("正在统计文件…", zh.get("instance_mover.phase.counting"));
        assertEquals("正在备份新实例中将被覆盖的内容…", zh.get("instance_mover.phase.backup"));
        assertEquals("正在转移数据…", zh.get("instance_mover.phase.transfer"));

        assertEquals(ScanService.DOWNGRADE_NOTICE_FORMAT, zh.get("instance_mover.source.notice.downgrade"));
        assertEquals(ScanService.SESSION_LOCK_WARNING_FORMAT, zh.get("instance_mover.warning.session_lock"));

        InstanceScanner.InstanceCandidate gone = InstanceScanner.inspect(base.resolve("没有这个目录"), base);
        assertEquals(zh.get("instance_mover.source.invalid.not_dir"), gone.rejectReason());
        TestSupport.write(base.resolve("只有日志/logs/latest.log"), "log");
        InstanceScanner.InstanceCandidate notInstance =
                InstanceScanner.inspect(base.resolve("只有日志"), base);
        assertEquals(zh.get("instance_mover.source.invalid.not_instance"), notInstance.rejectReason());

        assertEquals(3, placeholders(zh.get("instance_mover.source.status_ok")).size(),
                "status_ok 的三处占位符要对应 InstanceCandidate.statusText() 的三个参数");
    }

    @Test
    @DisplayName("界面用语言文件里的阶段名，与引擎真正回调的 phase 字符串一致")
    void phaseKeysCoverEngineCallbacks(@TempDir Path base) throws Exception {
        Map<String, String> zh = lang(ZH);

        Path src = base.resolve("老实例");
        Path dst = base.resolve("新实例");
        TestSupport.write(src.resolve("config/a.toml"), "老配置");
        TestSupport.write(src.resolve("saves/w/level.dat"), "存档");
        TestSupport.write(dst.resolve("config/a.toml"), "新配置");

        List<String> phases = new ArrayList<>();
        List<MigrateItem> items = List.of(
                MigrateCatalog.find("config").orElseThrow(),
                MigrateCatalog.find("saves").orElseThrow());
        TransferReport report = new TransferRunner(src, dst, items, true, new TransferListener() {
            @Override
            public void onPhase(String text, boolean indeterminate) {
                phases.add(text);
            }
        }).run();

        assertEquals(0, report.totals().failed());
        assertFalse(phases.isEmpty(), "引擎必须回调阶段名");
        List<String> known = List.of(
                zh.get("instance_mover.phase.counting"),
                zh.get("instance_mover.phase.backup"),
                zh.get("instance_mover.phase.transfer"));
        for (String phase : phases) {
            assertTrue(known.contains(phase),
                    "引擎回调的阶段名「%s」在语言文件里没有对应键".formatted(phase));
        }
        assertTrue(phases.contains(zh.get("instance_mover.phase.counting")));
        assertTrue(phases.contains(zh.get("instance_mover.phase.transfer")));
        assertTrue(phases.contains(zh.get("instance_mover.phase.backup")), "有备份时也要出现备份阶段");
    }

    @Test
    @DisplayName("语言文件在编译产物里（classpath 可读）且编码为 UTF-8 无乱码")
    void langFilesArePackaged() throws IOException {
        for (String path : List.of(ZH, EN)) {
            try (InputStream in = LangFilesTest.class.getResourceAsStream(path)) {
                assertNotNull(in, path + " 没有进 classpath");
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(text.contains("\uFFFD"), path + " 有乱码（U+FFFD）");
            }
        }
        Map<String, String> zh = lang(ZH);
        assertTrue(zh.get("instance_mover.mod_name").chars().anyMatch(c -> c > 0x4E00), "中文名要真的是中文");
    }
}
