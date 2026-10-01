package dev.sd_dt.instancemover.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.config.MoverConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 首启弹窗判定（纯逻辑）：只在菜单界面弹一次；「稍后再说」本次不再弹；「不再提示」写标记；
 * 强制开关与自检开关的行为。
 *
 * <p>t22 起判定不再要求原版 {@code TitleScreen}（整合包会把主界面换成自定义菜单），
 * 改为「客户端就绪 + 不在世界里 + 非加载类界面」，所以这里用「界面类名」构造上下文。</p>
 */
class FirstRunGateTest {

    /** 常见场景的上下文构造器：类名 + 是否在世界里 + 其余默认就绪。 */
    private static FirstRunGate.PromptContext title(String screenClass, boolean inWorld) {
        return FirstRunGate.PromptContext.of(true, true, false, inWorld, screenClass);
    }

    private static FirstRunGate.PromptContext title(String screenClass) {
        return title(screenClass, false);
    }

    private static final String VANILLA_TITLE = "net.minecraft.client.gui.screens.TitleScreen";
    /** 整合包里常见：主界面被模组换成自定义菜单（以前就是这种环境永远不弹）。 */
    private static final String MODDED_MENU = "com.example.fancymenu.CustomMainMenuScreen";

    @Test
    @DisplayName("第一次进整合包、停在菜单界面 → 弹")
    void promptsOnFirstRun() {
        FirstRunGate gate = new FirstRunGate(false, false);
        FirstRunGate.Decision decision = gate.evaluate(new MoverConfig(), title(VANILLA_TITLE));

        assertTrue(decision.prompt());
        assertTrue(decision.reason().contains("firstRunDone=false"), decision.reason());
    }

    @Test
    @DisplayName("★ t22：主界面被模组换掉（不是原版 TitleScreen）也必须弹")
    void promptsOnModdedMainMenu() {
        FirstRunGate gate = new FirstRunGate(false, false);
        FirstRunGate.Decision decision = gate.evaluate(new MoverConfig(), title(MODDED_MENU));

        assertTrue(decision.prompt(), "不能再要求 instanceof TitleScreen：" + decision.reason());
        assertTrue(decision.reason().contains(MODDED_MENU), "诊断日志里要能看到界面类名：" + decision.reason());
    }

    @Test
    @DisplayName("★ t22：加载类界面（26.2 真实类名）不弹，forceGui 时也照弹")
    void loadingScreensAreSkippedUnlessForced() {
        MoverConfig config = new MoverConfig();
        for (String loading : FirstRunGate.LOADING_SCREEN_CLASSES) {
            FirstRunGate normal = new FirstRunGate(false, false);
            FirstRunGate.Decision skipped = normal.evaluate(config, title(loading));
            assertFalse(skipped.prompt(), "加载类界面不该弹：" + loading);
            assertTrue(skipped.reason().contains("加载类界面"), skipped.reason());

            FirstRunGate forced = new FirstRunGate(true, false);
            assertTrue(forced.evaluate(config, title(loading)).prompt(),
                    "forceGui 不能被界面类名判定拦住：" + loading);
        }
    }

    @Test
    @DisplayName("★ t22：已经进了世界一律不弹（forceGui 也不行）")
    void neverPromptsInWorld() {
        MoverConfig config = new MoverConfig();
        for (boolean forced : new boolean[] {false, true}) {
            for (String screen : new String[] {VANILLA_TITLE, MODDED_MENU,
                    "net.minecraft.client.gui.screens.PauseScreen"}) {
                FirstRunGate gate = new FirstRunGate(forced, false);
                FirstRunGate.Decision decision = gate.evaluate(config, title(screen, true));
                assertFalse(decision.prompt(), "在世界里不能弹（forced=" + forced + "，" + screen + "）");
                assertTrue(decision.reason().contains("铁律"), decision.reason());
            }
        }
    }

    @Test
    @DisplayName("★ t22：客户端没就绪 / 还没有界面 / 加载浮层盖着 → 先不弹，日志给出原因")
    void waitsUntilClientReady() {
        MoverConfig config = new MoverConfig();

        FirstRunGate.Decision notRunning = new FirstRunGate(false, false)
                .evaluate(config, FirstRunGate.PromptContext.of(false, true, false, false, VANILLA_TITLE));
        assertFalse(notRunning.prompt());
        assertTrue(notRunning.reason().contains("还没就绪"), notRunning.reason());

        FirstRunGate.Decision noScreen = new FirstRunGate(false, false)
                .evaluate(config, FirstRunGate.PromptContext.of(true, false, false, false, "(null)"));
        assertFalse(noScreen.prompt());
        assertTrue(noScreen.reason().contains("还没有任何界面"), noScreen.reason());

        FirstRunGate.Decision overlay = new FirstRunGate(false, false)
                .evaluate(config, FirstRunGate.PromptContext.of(true, true, true, false, VANILLA_TITLE));
        assertFalse(overlay.prompt());
        assertTrue(overlay.reason().contains("加载浮层"), overlay.reason());

        // 浮层这条对 forceGui 也不让路：此时弹会被浮层直接顶掉（t22 真机实测），等到菜单再弹
        FirstRunGate.Decision forcedOverlay = new FirstRunGate(true, false)
                .evaluate(config, FirstRunGate.PromptContext.of(true, true, true, false, VANILLA_TITLE));
        assertFalse(forcedOverlay.prompt(), "浮层还没结束时弹会被顶掉，forceGui 也要等");
        assertTrue(forcedOverlay.reason().contains("加载浮层"), forcedOverlay.reason());

        // 浮层一消失（菜单出来了）forceGui 立刻弹
        FirstRunGate.Decision forcedAfterOverlay = new FirstRunGate(true, false)
                .evaluate(config, FirstRunGate.PromptContext.of(true, true, false, false, MODDED_MENU));
        assertTrue(forcedAfterOverlay.prompt());
    }

    @Test
    @DisplayName("已经弹过一次 → 本次游戏不再弹（「稍后再说」的效果）")
    void promptsOnlyOncePerSession() {
        FirstRunGate gate = new FirstRunGate(false, false);
        assertTrue(gate.shouldPrompt(new MoverConfig(), title(VANILLA_TITLE)));

        gate.markPrompted();
        assertTrue(gate.isPromptedThisSession());
        FirstRunGate.Decision second = gate.evaluate(new MoverConfig(), title(VANILLA_TITLE));
        assertFalse(second.prompt(), "同一次游戏里只弹一次");
        assertTrue(second.reason().contains("已经弹过"), second.reason());
    }

    @Test
    @DisplayName("标记文件 firstRunDone=true → 不再弹（原因里写清楚）")
    void respectsFirstRunDone() {
        FirstRunGate gate = new FirstRunGate(false, false);
        MoverConfig config = new MoverConfig().setFirstRunDone(true);

        FirstRunGate.Decision decision = gate.evaluate(config, title(MODDED_MENU));
        assertFalse(decision.prompt());
        assertTrue(decision.reason().contains("firstRunDone=true"), decision.reason());
    }

    @Test
    @DisplayName("-Dinstance_mover.forceGui=true → 无视标记强制弹（调界面用），但仍不在世界里弹")
    void forceGuiOverridesMarker() {
        FirstRunGate gate = new FirstRunGate(true, false);
        MoverConfig config = new MoverConfig().setFirstRunDone(true);

        assertTrue(gate.isForced());
        FirstRunGate.Decision decision = gate.evaluate(config, title(VANILLA_TITLE));
        assertTrue(decision.prompt());
        assertTrue(decision.reason().contains("forceGui"), decision.reason());
        assertFalse(gate.evaluate(config, title(VANILLA_TITLE, true)).prompt(), "强制弹窗也不能在世界里弹");

        gate.markPrompted();
        assertFalse(gate.evaluate(config, title(VANILLA_TITLE)).prompt(), "同一 session 仍然只弹一次");
    }

    @Test
    @DisplayName("-Dinstance_mover.selftest=true → 完全不弹（只跑引擎自检），优先级高于 forceGui")
    void selfTestNeverPrompts() {
        FirstRunGate gate = new FirstRunGate(true, true);

        assertTrue(gate.isSelfTest());
        FirstRunGate.Decision decision = gate.evaluate(new MoverConfig(), title(VANILLA_TITLE));
        assertFalse(decision.prompt());
        assertTrue(decision.reason().contains("selftest"), decision.reason());
    }

    @Test
    @DisplayName("「不再提示」：写标记 + 本次也不再弹")
    void markNeverAskAgain() {
        FirstRunGate gate = new FirstRunGate(false, false);
        MoverConfig config = new MoverConfig();

        gate.markNeverAskAgain(config);

        assertTrue(config.isFirstRunDone(), "标记要写进配置（由调用方落盘）");
        assertTrue(gate.isPromptedThisSession());
        assertFalse(gate.shouldPrompt(config, title(VANILLA_TITLE)));
    }

    @Test
    @DisplayName("系统属性解析：默认全关，置位后生效")
    void fromSystemProperties() {
        String forceKey = FirstRunGate.FORCE_GUI_PROPERTY;
        String selfKey = FirstRunGate.SELFTEST_PROPERTY;

        FirstRunGate plain = FirstRunGate.fromSystemProperties();
        assertFalse(plain.isForced());
        assertFalse(plain.isSelfTest());

        System.setProperty(forceKey, "true");
        try {
            assertTrue(FirstRunGate.fromSystemProperties().isForced());
            assertFalse(FirstRunGate.fromSystemProperties().isSelfTest());
        } finally {
            System.clearProperty(forceKey);
        }

        System.setProperty(selfKey, "true");
        try {
            FirstRunGate gate = FirstRunGate.fromSystemProperties();
            assertTrue(gate.isSelfTest());
        } finally {
            System.clearProperty(selfKey);
        }

        assertFalse(FirstRunGate.fromSystemProperties().isForced());
        assertFalse(FirstRunGate.fromSystemProperties().isSelfTest());
    }

    @Test
    @DisplayName("迁移完成后再次启动：标记已写 → 不弹")
    void afterSuccessfulMigrationNoPrompt() {
        MoverConfig config = new MoverConfig();
        FirstRunGate firstSession = new FirstRunGate(false, false);
        assertTrue(firstSession.shouldPrompt(config, title(VANILLA_TITLE)));

        // 迁移完成：界面把 firstRunDone 写进配置
        config.setFirstRunDone(true);

        FirstRunGate nextSession = new FirstRunGate(false, false);
        assertFalse(nextSession.shouldPrompt(config, title(VANILLA_TITLE)), "下次进游戏不再弹");
    }

    @Test
    @DisplayName("加载类界面名单：只认 26.2 真实类名，不做模糊猜测（否则又会误伤模组主界面）")
    void loadingScreenListIsExact() {
        assertTrue(FirstRunGate.isLoadingScreen("net.minecraft.client.gui.screens.LevelLoadingScreen"));
        assertTrue(FirstRunGate.isLoadingScreen("net.minecraft.client.gui.screens.ConnectScreen"));
        assertFalse(FirstRunGate.isLoadingScreen(VANILLA_TITLE));
        assertFalse(FirstRunGate.isLoadingScreen(MODDED_MENU));
        assertFalse(FirstRunGate.isLoadingScreen("com.example.LoadingMainMenu"),
                "类名里带 Loading 的自定义主界面不能被当成加载界面");
        assertFalse(FirstRunGate.isLoadingScreen(null));
        assertEquals(7, FirstRunGate.LOADING_SCREEN_CLASSES.size(), "名单条数变了要同步文档说明");
    }

    // ------------------------------------------------------------------
    // t23：整合包分发相关（关于页重置开关 + 迁移成功后不隐式锁死）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("t23：关于页重置后，下一次启动（新 gate）必须重新弹")
    void resetOnAboutScreenRePromptsNextSession(@TempDir Path base) throws Exception {
        Path file = MoverConfig.defaultFile(base);
        MoverConfig config = new MoverConfig().setFirstRunDone(true);
        config.save(file);
        assertFalse(new FirstRunGate(false, false).shouldPrompt(config, title(VANILLA_TITLE)),
                "前置：已提示过时不该弹");

        // 关于页点「重新显示迁移引导」：resetFirstRun + 立即落盘
        config.resetFirstRun().save(file);

        // 下次启动：重新读文件 + 新 gate（模拟真实的“再启动一次”）
        MoverConfig reloaded = MoverConfig.load(file);
        assertFalse(reloaded.isFirstRunDone());
        FirstRunGate nextSession = new FirstRunGate(false, false);
        assertTrue(nextSession.shouldPrompt(reloaded, title(MODDED_MENU)),
                "重置后下次启动必须（在自定义主菜单上也）重新弹出来");
    }

    @Test
    @DisplayName("t23：迁移成功后写入的 firstRunDone 只是「暂时安静」，随时可被关于页开关重置（不隐式锁死）")
    void migrationSuccessIsResettableNotLocked(@TempDir Path base) throws Exception {
        Path file = MoverConfig.defaultFile(base);

        // ① 第一次进：弹 → 玩家完成一次真实迁移 → ProgressScreen 写 true 落盘
        MoverConfig config = new MoverConfig();
        assertTrue(new FirstRunGate(false, false).shouldPrompt(config, title(VANILLA_TITLE)));
        config.setFirstRunDone(true).setLastSource("D:/old");
        config.save(file);

        // ② 下次启动：安静（方案 b 的预期）
        assertFalse(new FirstRunGate(false, false).shouldPrompt(MoverConfig.load(file), title(VANILLA_TITLE)));

        // ③ 玩家想重看：关于页开关重置 → 再下次启动又能弹（说明没有被“锁死”）
        MoverConfig afterReset = MoverConfig.load(file).resetFirstRun();
        afterReset.save(file);
        assertTrue(new FirstRunGate(false, false).shouldPrompt(MoverConfig.load(file), title(VANILLA_TITLE)),
                "重置开关必须真的能把引导叫回来");
        assertEquals("D:/old", MoverConfig.load(file).lastSource(), "重置引导不该清掉 lastSource");
    }

    @Test
    @DisplayName("t23：首启判定从不自己写标记 —— 「稍后再说」不落盘，也绝不替玩家写 firstRunDone=true")
    void laterNeverPersistsAndGateNeverWritesMarker(@TempDir Path base) throws Exception {
        Path file = MoverConfig.defaultFile(base);
        MoverConfig config = new MoverConfig();

        // 「稍后再说」= markPrompted（只记本次会话，不写文件）
        FirstRunGate gate = new FirstRunGate(false, false);
        assertTrue(gate.shouldPrompt(config, title(VANILLA_TITLE)));
        gate.markPrompted();
        assertTrue(gate.isPromptedThisSession());
        assertFalse(gate.shouldPrompt(config, title(VANILLA_TITLE)), "本次不再弹");

        assertFalse(config.isFirstRunDone(), "「稍后再说」不能改配置值");
        assertFalse(Files.exists(file), "「稍后再说」不能落盘（标记文件都不该出现）");

        // 多来几次判定 / 强制弹，也不该写出 true
        FirstRunGate forced = new FirstRunGate(true, false);
        forced.evaluate(config, title(MODDED_MENU));
        forced.markPrompted();
        forced.evaluate(config, title(MODDED_MENU));
        assertFalse(config.isFirstRunDone(), "首启判定自己不持有写标记的权力（只有「不再提示」与真实迁移能写）");
        assertFalse(Files.exists(file));

        // 判定层唯一能写 true 的入口是「不再提示」，而且只改内存、由调用方落盘
        FirstRunGate never = new FirstRunGate(false, false);
        never.markNeverAskAgain(config);
        assertTrue(config.isFirstRunDone(), "「不再提示」是显式选择，可以写 true");
        assertFalse(Files.exists(file), "落盘仍由调用方负责");
    }
}
