package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.InstanceMoverClient;
import dev.sd_dt.instancemover.config.MoverConfig;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 关于页：模组名 + 版本 + 作者声明（「该模组由 deepseek 和 sd_dt 编写」）+ 功能说明
 * + 「重新显示迁移引导」开关（t23，整合包分发用）。
 *
 * <p><b>语义（t23 拍定，方案 b）</b>：完成一次**真实成功**的迁移后，标记文件里
 * {@code firstRunDone=true}，下次启动不再弹（安静）；想重看引导时到这个页面点一下
 * 「重新显示迁移引导」，会把 {@code firstRunDone} 置回 {@code false} 并**立即落盘**，
 * 下次启动就会重新弹出。玩家/整合包作者都不需要手动删 {@code config\instance_mover.json}。</p>
 */
public final class AboutScreen extends Screen {

    private final Screen parent;

    /** 状态行（与配置文件里的实际值保持一致）。 */
    private StringWidget guideStatus;
    /** 点击后的可见反馈。 */
    private StringWidget guideFeedback;
    /** 开关按钮（点的就是它）。 */
    private Button guideButton;

    public AboutScreen(Screen parent) {
        super(Component.translatable("instance_mover.about.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - 180;
        addRenderableWidget(new StringWidget(left, 16, 360, 20, title, font));
        addRenderableWidget(new StringWidget(left, 42, 360, 14,
                Component.literal(InstanceMoverClient.MOD_NAME), font));
        addRenderableWidget(new StringWidget(left, 58, 360, 14,
                Component.translatable("instance_mover.about.version", InstanceMoverClient.version()), font));
        addRenderableWidget(new StringWidget(left, 74, 360, 14,
                Component.translatable("instance_mover.author_line"), font));

        // 正文：自己折行 + 行距 12px（t18 修复：MultiLineTextWidget 行距为 0 时会叠印）
        int bodyY = 98;
        int bodyHeight = TextBlock.add(Component.translatable("instance_mover.about.body"),
                left, bodyY, 360, font, this::addRenderableWidget);

        // 「模组 ID」按正文实际高度往下排，保证与正文末行有正常行距
        int idY = bodyY + bodyHeight + 16;
        addRenderableWidget(new StringWidget(left, idY, 360, 14,
                Component.translatable("instance_mover.about.mod_id", InstanceMoverClient.MOD_ID), font));

        // ---------- t23：重新显示迁移引导 ----------
        int guideY = idY + 26;
        guideStatus = addRenderableWidget(new StringWidget(left, guideY, 360, 14,
                Component.literal(guideStatusText()), font));
        guideButton = addRenderableWidget(Button.builder(
                        Component.translatable("instance_mover.about.guide.reset"),
                        ignored -> resetGuide())
                .pos(left, guideY + 18).size(300, 20).build());
        guideFeedback = addRenderableWidget(new StringWidget(left, guideY + 44, 360, 14,
                Component.empty(), font));
        TextBlock.add(Component.translatable("instance_mover.about.guide.note"),
                left, guideY + 62, 360, font, this::addRenderableWidget);

        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.back"), ignored -> onClose())
                .pos(width / 2 - 50, height - 32).size(100, 20).build());

        rebuildGuideWidgets();
    }

    /** 状态行文案：直接读当前配置值，保证「界面显示」和「实际配置」一致。 */
    private String guideStatusText() {
        MoverConfig config = currentConfig();
        if (config == null) {
            return Component.translatable("instance_mover.about.guide.status_unknown").getString();
        }
        return (config.isFirstRunDone()
                ? Component.translatable("instance_mover.about.guide.status_off")
                : Component.translatable("instance_mover.about.guide.status_on")).getString();
    }

    private MoverConfig currentConfig() {
        try {
            return InstanceMoverClient.config();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Path currentConfigFile() {
        try {
            return InstanceMoverClient.configFile();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 把状态行 / 按钮可用性刷成与配置一致。 */
    private void rebuildGuideWidgets() {
        MoverConfig config = currentConfig();
        if (guideStatus != null) {
            guideStatus.setMessage(Component.literal(guideStatusText()));
        }
        if (guideButton != null) {
            // 拿不到配置/路径时禁用（例如自检模式或还没初始化），避免点了没反应还搞不清原因
            guideButton.active = config != null && currentConfigFile() != null;
            guideButton.setMessage(Component.translatable("instance_mover.about.guide.reset"));
        }
    }

    /**
     * 点一下「重新显示迁移引导」：{@code firstRunDone=false} + **立即落盘** + 可见反馈。
     *
     * <p>落盘失败时把内存值回滚成原样，避免出现「界面说设置了、文件里其实没写」的不一致。</p>
     */
    private void resetGuide() {
        MoverConfig config = currentConfig();
        Path file = currentConfigFile();
        if (config == null || file == null) {
            setFeedback(Component.translatable("instance_mover.about.guide.save_failed").getString()
                    + "（拿不到配置文件路径）", ChatFormatting.RED);
            rebuildGuideWidgets();
            return;
        }
        boolean previous = config.isFirstRunDone();
        try {
            config.resetFirstRun();
            config.save(file);   // 立即落盘（抛异常才算失败）
            InstanceMoverClient.LOGGER.info("[{}] 关于页：已把 firstRunDone 置回 false 并落盘 {}（下次启动会重新弹出迁移引导）。",
                    InstanceMoverClient.MOD_ID, file);
            setFeedback(Component.translatable("instance_mover.about.guide.saved").getString(), ChatFormatting.GREEN);
        } catch (IOException | RuntimeException ex) {
            config.setFirstRunDone(previous);   // 回滚，保持界面与实际一致
            InstanceMoverClient.LOGGER.warn("[{}] 关于页：重置 firstRunDone 失败：{}",
                    InstanceMoverClient.MOD_ID, ex.toString());
            setFeedback(Component.translatable("instance_mover.about.guide.save_failed").getString()
                    + file, ChatFormatting.RED);
        }
        rebuildGuideWidgets();
    }

    private void setFeedback(String text, ChatFormatting color) {
        if (guideFeedback != null) {
            guideFeedback.setMessage(Component.literal(text).withStyle(color));
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
