package dev.sd_dt.instancemover;

import dev.sd_dt.instancemover.client.FirstRunGate;
import dev.sd_dt.instancemover.config.MoverConfig;
import dev.sd_dt.instancemover.engine.EngineSelfTest;
import dev.sd_dt.instancemover.gui.MigrateScreen;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例酱的搬家服务 —— 客户端入口。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>读标记文件 {@code config/instance_mover.json}；</li>
 *   <li>{@code -Dinstance_mover.selftest=true} 时在临时目录跑引擎自检，日志打出
 *       {@code SELFTEST PASS n/n}，不弹界面；</li>
 *   <li>客户端菜单界面就绪后（不在世界里、且不是加载类界面）弹一次 {@link MigrateScreen}；
 *       「稍后再说」本次游戏不再弹，「不再提示」写标记；</li>
 *   <li>{@code -Dinstance_mover.forceGui=true} 时无视标记、也无视界面类名判定强制弹（调界面用），
 *       但仍不在世界里弹；</li>
 *   <li>每次首启判定都会打「当前界面类名 + level + 各开关」的诊断日志（判定变化时打一条，去重防刷屏）。</li>
 * </ol>
 *
 * <p>26.2 的界面 API：{@code Minecraft.getInstance().gui.setScreen(...)} /
 * {@code Minecraft.getInstance().gui.screen()}（旧的 {@code Minecraft.setScreen} 已移除）。</p>
 */
public final class InstanceMoverClient implements ClientModInitializer {

    public static final String MOD_ID = "instance_mover";
    public static final String MOD_NAME = "实例酱的搬家服务";
    public static final String MOD_VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("instance_mover");

    private static MoverConfig config = new MoverConfig();
    private static Path configFile;
    private static Path gameDirectory;
    private static FirstRunGate gate = new FirstRunGate(false, false);
    private static boolean initialized;

    /** 本会话弹出的首启界面（用于确认它真的显示出来了；被顶掉就重弹）。 */
    private static MigrateScreen activePopup;
    /** 弹出尝试次数（被其它界面顶掉后重试的计数）。 */
    private static int popupAttempts;
    /** 首启界面连续在屏幕上多少个 tick 才算「真的弹出来了」（防止刚 setScreen 就被顶掉时误记）。 */
    private static int popupConfirmedTicks;
    private static final int POPUP_CONFIRM_TICKS = 2;
    /** 弹出尝试次数上限，防止极端整合包里被反复顶掉时无限重试。 */
    private static final int MAX_POPUP_ATTEMPTS = 20;
    /** 诊断日志去重表（避免逐 tick 刷屏）。 */
    private static final Set<String> loggedDecisionLines = new LinkedHashSet<>();
    /** 判定过的日志条数上限：超过后只在「弹/不弹」翻转时打。 */
    private static final int MAX_DIAGNOSTIC_LINES = 24;
    private static Boolean lastVerdict;
    private static boolean firstEvaluationLogged;

    @Override
    public void onInitializeClient() {
        gate = FirstRunGate.fromSystemProperties();

        if (gate.isSelfTest()) {
            runSelfTest();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        gameDirectory = minecraft == null || minecraft.gameDirectory == null
                ? Path.of(".").toAbsolutePath().normalize()
                : minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        configFile = MoverConfig.defaultFile(gameDirectory);
        config = MoverConfig.load(configFile);
        initialized = true;

        ClientTickEvents.END_CLIENT_TICK.register(InstanceMoverClient::onEndClientTick);
        LOGGER.info("[{}] 已加载：标记文件 {}，firstRunDone={}，forceGui={}。",
                MOD_ID, configFile, config.isFirstRunDone(), gate.isForced());
    }

    /** 引擎自检（{@code -Dinstance_mover.selftest=true}）：临时目录跑一遍，日志打 SELFTEST。 */
    private void runSelfTest() {
        EngineSelfTest.Result result = EngineSelfTest.run();
        for (String line : result.lines()) {
            LOGGER.info("[自检] {}", line);
        }
        LOGGER.info(result.summary());
        System.out.println(result.summary());
    }

    private static void onEndClientTick(Minecraft minecraft) {
        if (!initialized || minecraft == null) {
            return;
        }
        ensureGameDirectory(minecraft);

        // 已经弹出过：确认它真的在屏幕上（连续两 tick）才记「本次已提示」；
        // 被别的界面顶掉（例如加载浮层）就允许重弹（t22 实测过这种顶掉）
        if (activePopup != null) {
            if (minecraft.gui.screen() == activePopup) {
                if (++popupConfirmedTicks >= POPUP_CONFIRM_TICKS) {
                    gate.markPrompted();
                }
                return;
            }
            popupConfirmedTicks = 0;
            if (gate.isPromptedThisSession()) {
                activePopup = null;
                return;
            }
            activePopup = null;
            LOGGER.info("[{}] 首启界面被其它界面顶掉（当前 {}），下次 tick 重试（已尝试 {} 次）。",
                    MOD_ID, minecraft.gui.screen() == null ? "(null)"
                            : minecraft.gui.screen().getClass().getName(), popupAttempts);
        }

        FirstRunGate.PromptContext context = promptContext(minecraft);
        FirstRunGate.Decision decision = gate.evaluate(config, context);
        logDecision(decision, context);
        if (!decision.prompt()) {
            return;
        }
        if (popupAttempts >= MAX_POPUP_ATTEMPTS) {
            if (loggedDecisionLines.add("放弃弹出")) {
                LOGGER.warn("[{}] 首启界面连续 {} 次被顶掉，本次会话放弃弹出（下次启动会重试）。",
                        MOD_ID, popupAttempts);
            }
            return;
        }
        Screen parent = minecraft.gui.screen();
        activePopup = new MigrateScreen(parent, config, gate, configFile, currentInstance());
        popupAttempts++;
        LOGGER.info("[{}] 弹出首启界面（第 {} 次尝试）：{}", MOD_ID, popupAttempts, decision.reason());
        minecraft.gui.setScreen(activePopup);
    }

    /**
     * 采集判定所需的环境快照（t22）。
     *
     * <p>不要求界面是原版 {@code TitleScreen}：整合包常把主界面换成自定义菜单，
     * 以前那种 {@code instanceof TitleScreen} 判定在 336 模组的环境里恒为 false，永远不会弹。</p>
     */
    private static FirstRunGate.PromptContext promptContext(Minecraft minecraft) {
        boolean inWorld = minecraft.level != null;
        Screen screen = minecraft.gui.screen();
        boolean overlay = false;
        try {
            overlay = minecraft.gui.overlay() != null;
        } catch (RuntimeException ignored) {
            // 26.2 的 GUI 状态在极早期可能还没准备好；取不到就当没有浮层
        }
        return FirstRunGate.PromptContext.of(
                safeIsRunning(minecraft), screen != null, overlay, inWorld,
                screen == null ? "(null)" : screen.getClass().getName());
    }

    private static boolean safeIsRunning(Minecraft minecraft) {
        try {
            return minecraft.isRunning();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * 诊断日志（t22）：首次评估与判定变化时各打一条，包含「当前界面类名 + level 是否为 null +
     * firstRunDone + 各开关」，这样任何环境出问题都能从日志一眼定位；同时用去重表避免刷屏。
     */
    private static void logDecision(FirstRunGate.Decision decision, FirstRunGate.PromptContext context) {
        boolean verdictChanged = lastVerdict == null || lastVerdict != decision.prompt();
        lastVerdict = decision.prompt();
        String line = "首启判定 → %s｜%s｜firstRunDone=%s，forceGui=%s，selftest=%s，本次已弹=%s"
                .formatted(decision.prompt() ? "弹" : "不弹", decision.reason(),
                        config.isFirstRunDone(), gate.isForced(), gate.isSelfTest(),
                        gate.isPromptedThisSession());
        if (verdictChanged) {
            firstEvaluationLogged = true;
            LOGGER.info("[{}] {}", MOD_ID, line);
            return;
        }
        if (!firstEvaluationLogged) {
            firstEvaluationLogged = true;
            LOGGER.info("[{}] 首次评估：{}", MOD_ID, line);
            return;
        }
        if (loggedDecisionLines.size() < MAX_DIAGNOSTIC_LINES && loggedDecisionLines.add(line)) {
            LOGGER.info("[{}] {}", MOD_ID, line);
        }
    }

    /** 初始化时拿不到游戏目录的话，第一次 tick 再补一次（配置路径要落到真正的实例目录里）。 */
    private static void ensureGameDirectory(Minecraft minecraft) {
        if (gameDirectory != null || minecraft.gameDirectory == null) {
            return;
        }
        gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        configFile = MoverConfig.defaultFile(gameDirectory);
        config = MoverConfig.load(configFile);
        LOGGER.info("[{}] 补读标记文件：{}", MOD_ID, configFile);
    }

    /** 模组版本：从 fabric.mod.json 读（单一真源是 gradle.properties）。 */
    public static String version() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(MOD_VERSION);
        } catch (RuntimeException ex) {
            return MOD_VERSION;
        }
    }

    /** 当前实例目录（= 游戏目录，也就是要覆盖进去的「新实例」）。 */
    public static Path currentInstance() {
        if (gameDirectory != null) {
            return gameDirectory;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.gameDirectory != null) {
            return minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }


    /** 当前实例目录（= 游戏目录，也就是要覆盖进去的「新实例」）。 */

    public static MoverConfig config() {
        return config;
    }

    public static Path configFile() {
        return configFile;
    }

    public static FirstRunGate gate() {
        return gate;
    }
}
