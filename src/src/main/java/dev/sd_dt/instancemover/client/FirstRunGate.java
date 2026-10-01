package dev.sd_dt.instancemover.client;

import dev.sd_dt.instancemover.config.MoverConfig;
import java.util.Set;

/**
 * 首次进入整合包时的弹窗判定（纯逻辑，可直接单测）。
 *
 * <p><b>t22 重要放宽</b>：以前要求 {@code screen instanceof TitleScreen}，结果在把主界面换成
 * FancyMenu / Drippy / 自定义菜单的整合包里（336 模组那种）判定恒为 false，玩家永远看不到弹窗，
 * 而且失败时没有任何日志，排查无门。现在改成
 * <b>「客户端就绪 + 不在世界里 + 当前界面不是加载类界面」</b>：</p>
 * <ul>
 *   <li>{@code minecraft.level != null}（已经进了存档/服务器）→ <b>一律不弹</b>（铁律）；</li>
 *   <li>客户端还没跑起来、还没有任何界面、或正被加载浮层盖着 → 先不弹，等在下一 tick 再看
 *       （浮层这条**对 forceGui 也不让路**：此时弹会被浮层直接顶掉，玩家什么都看不到，t22 实测过）；</li>
 *   <li>当前界面在 {@link #LOADING_SCREEN_CLASSES} 里（真正意义上的加载/连接/进度界面）→ 不弹
 *       （**这条对 forceGui 让路**）；其余任何界面（包括各种模组自定义主菜单）都允许弹；</li>
 *   <li>标记文件里 {@code firstRunDone=true} 后不再弹；</li>
 *   <li>「稍后再说」→ 本次游戏不再弹（{@link #markPrompted()}，不写文件）；</li>
 *   <li>「不再提示」→ 写标记（调用方把 {@code firstRunDone} 存进 {@link MoverConfig}）；</li>
 *   <li>{@code -Dinstance_mover.forceGui=true} 无视标记、也无视上面所有「界面类名」判定强制弹
 *       （调界面用），但**仍然不在世界里弹**；</li>
 *   <li>{@code -Dinstance_mover.selftest=true} 优先级最高：只跑引擎自检，不弹窗也不落盘。</li>
 * </ul>
 *
 * <p>每次判定都返回带原因的 {@link Decision}，客户端据此打诊断日志 —— 下次再出问题，
 * 日志里能直接看到「卡在哪一步、当时是什么界面」。</p>
 */
public final class FirstRunGate {

    /** 强制弹窗的系统属性。 */
    public static final String FORCE_GUI_PROPERTY = "instance_mover.forceGui";

    /** 引擎自检的系统属性。 */
    public static final String SELFTEST_PROPERTY = "instance_mover.selftest";

    /**
     * 「正在加载 / 等待」类界面（26.2 的**真实类名**，用 {@code javap} 对着 26.2 客户端 jar 核对过；
     * 26.2 里没有旧版本的 {@code ReceivingLevelScreen} / {@code DownloadingTerrainScreen}）。
     *
     * <p>刻意只列这些明确是过渡态的类：如果按「类名里带 Loading」之类的模糊规则去挡，
     * 又会把模组自定义的主界面误伤成「不弹」，重演 t22 要修的那个 bug。</p>
     */
    public static final Set<String> LOADING_SCREEN_CLASSES = Set.of(
            "net.minecraft.client.gui.screens.LoadingOverlay",
            "net.minecraft.client.gui.screens.LevelLoadingScreen",
            "net.minecraft.client.gui.screens.ProgressScreen",
            "net.minecraft.client.gui.screens.ConnectScreen",
            "net.minecraft.client.gui.screens.GenericWaitingScreen",
            "net.minecraft.client.gui.screens.GenericMessageScreen",
            "net.minecraft.client.gui.screens.worldselection.FileFixerProgressScreen");

    private final boolean forced;
    private final boolean selfTest;
    private boolean promptedThisSession;

    public FirstRunGate(boolean forced, boolean selfTest) {
        this.forced = forced;
        this.selfTest = selfTest;
    }

    /** 从系统属性构造（{@code -Dinstance_mover.forceGui=true} / {@code -Dinstance_mover.selftest=true}）。 */
    public static FirstRunGate fromSystemProperties() {
        return new FirstRunGate(
                Boolean.parseBoolean(System.getProperty(FORCE_GUI_PROPERTY, "false")),
                Boolean.parseBoolean(System.getProperty(SELFTEST_PROPERTY, "false")));
    }

    /**
     * 判定所需的环境快照（由客户端每 tick 采集；纯数据，便于单测）。
     *
     * @param running       客户端是否在运行（{@code Minecraft.isRunning()}）
     * @param screenPresent 当前是否有界面（{@code gui.screen() != null}）；启动早期没有界面
     * @param overlayActive 是否有加载浮层（{@code gui.overlay() != null}）
     * @param inWorld       是否已经在世界里（{@code minecraft.level != null}）—— 铁律判据
     * @param screenClass   当前界面类名（没有界面时给占位串，只用于日志）
     */
    public record PromptContext(boolean running, boolean screenPresent, boolean overlayActive,
                                boolean inWorld, String screenClass) {

        public static PromptContext of(boolean running, boolean screenPresent, boolean overlayActive,
                                       boolean inWorld, String screenClass) {
            return new PromptContext(running, screenPresent, overlayActive, inWorld,
                    screenClass == null || screenClass.isBlank() ? "(unknown)" : screenClass);
        }

        /** 是否命中「加载类界面」名单（只按类名精确匹配，不做模糊猜测）。 */
        public boolean loadingScreen() {
            return LOADING_SCREEN_CLASSES.contains(screenClass);
        }

        /** 日志用的一行环境描述。 */
        public String describe() {
            return "screen=%s，level=%s，浮层=%s，running=%s".formatted(
                    screenClass, inWorld ? "非 null（在世界里）" : "null", overlayActive, running);
        }
    }

    /**
     * 判定结果。
     *
     * @param prompt 是否现在就该弹
     * @param reason 人类可读原因（打进日志，用于追溯；无论弹不弹都有）
     */
    public record Decision(boolean prompt, String reason) {
    }

    public boolean isForced() {
        return forced;
    }

    public boolean isSelfTest() {
        return selfTest;
    }

    public boolean isPromptedThisSession() {
        return promptedThisSession;
    }

    /** 静态便捷方法：类名是否属于「加载类界面」。 */
    public static boolean isLoadingScreen(String screenClass) {
        return screenClass != null && LOADING_SCREEN_CLASSES.contains(screenClass);
    }

    /**
     * 现在是否应该弹窗（带原因）。
     *
     * <p>判定顺序即优先级：自检 &gt; 已在世界 &gt; 客户端未就绪 &gt; 无界面/加载浮层 / 加载类界面
     * （后两者对 {@code forceGui} 让路）&gt; 本次已弹 &gt; 标记文件。</p>
     */
    public Decision evaluate(MoverConfig config, PromptContext context) {
        PromptContext ctx = context == null
                ? PromptContext.of(false, false, false, false, "(unknown)")
                : context;
        if (selfTest) {
            return new Decision(false, "自检模式（selftest 优先：不弹窗、不落盘）；" + ctx.describe());
        }
        if (ctx.inWorld()) {
            return new Decision(false, "已经进入世界 → 不弹（铁律：不在进存档时弹）；" + ctx.describe());
        }
        if (!ctx.running()) {
            return new Decision(false, "客户端还没就绪（isRunning=false）；" + ctx.describe());
        }
        if (!ctx.screenPresent()) {
            return new Decision(false, "当前还没有任何界面（仍在启动/加载）；" + ctx.describe());
        }
        if (ctx.overlayActive()) {
            // 加载浮层还在盖着时弹，界面会被浮层替换掉（t22 实测：刚 setScreen 就被顶掉，玩家什么都看不到），
            // 所以这条对 forceGui 也不让路 —— 它不是「界面类名」判定，而是「客户端还没真正就绪」。
            return new Decision(false, "加载浮层还没结束（此时弹会被顶掉）→ 等到菜单；" + ctx.describe());
        }
        if (!forced && ctx.loadingScreen()) {
            return new Decision(false, "当前是加载类界面 → 不弹；" + ctx.describe());
        }
        if (promptedThisSession) {
            return new Decision(false, "本次游戏已经弹过（「稍后再说」或已弹）；" + ctx.describe());
        }
        if (config != null && config.isFirstRunDone() && !forced) {
            return new Decision(false, "标记文件 firstRunDone=true → 不再弹（调试可加 -D"
                    + FORCE_GUI_PROPERTY + "=true 强制）；" + ctx.describe());
        }
        String why = forced
                ? "forceGui 强制弹出（无视标记与界面类名判定）"
                : "首次运行未完成（firstRunDone=false）→ 弹";
        return new Decision(true, why + "；" + ctx.describe());
    }

    /** 兼容旧调用：只要「弹不弹」。 */
    public boolean shouldPrompt(MoverConfig config, PromptContext context) {
        return evaluate(config, context).prompt();
    }

    /** 记下「本次已经弹过」（弹过就要调一次，包含「稍后再说」）。 */
    public void markPrompted() {
        promptedThisSession = true;
    }

    /** 「不再提示」：把标记写进配置（真正落盘由调用方 save）。 */
    public void markNeverAskAgain(MoverConfig config) {
        promptedThisSession = true;
        config.setFirstRunDone(true);
    }
}
