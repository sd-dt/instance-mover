package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.engine.ItemResult;
import dev.sd_dt.instancemover.engine.TransferReport;
import dev.sd_dt.instancemover.util.SizeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * 结果页：成功 / 跳过 / 失败计数、失败清单（可复制）、[打开备份目录]、[完成]，
 * 涉及需重启生效的配置类条目时给出「需重启生效」提示与 [退出游戏]。
 */
public final class ResultScreen extends Screen {

    private static final int MAX_ERROR_LINES = 6;

    private final Screen parent;
    private final TransferReport report;
    private final String errorText;

    /** 独立的反馈行（打开备份目录 / 复制失败清单的结果），不依赖其它控件是否存在。 */
    private StringWidget feedbackWidget;

    public ResultScreen(Screen parent, TransferReport report, String errorText) {
        super(titleFor(report, errorText));
        this.parent = parent;
        this.report = report;
        this.errorText = errorText;
    }

    private static Component titleFor(TransferReport report, String errorText) {
        if (report == null || errorText != null || report.hasError()) {
            return Component.translatable("instance_mover.error.title");
        }
        if (report.cancelled()) {
            return Component.translatable("instance_mover.result.cancelled_title");
        }
        return Component.translatable("instance_mover.result.title");
    }

    @Override
    protected void init() {
        int left = width / 2 - 240;
        addRenderableWidget(new StringWidget(left, 12, 480, 20, title, font));

        if (report == null) {
            TextBlock.add(Component.literal(errorText == null ? "" : errorText),
                    left, 40, 480, font, this::addRenderableWidget);
            addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.done"),
                            ignored -> onClose())
                    .pos(width / 2 - 50, height - 28).size(100, 20).build());
            return;
        }

        var stats = report.transferStats();
        addRenderableWidget(new StringWidget(left, 36, 480, 14,
                Component.translatable("instance_mover.result.success",
                        stats.successful(), stats.created(), stats.overwritten()), font));
        addRenderableWidget(new StringWidget(left, 52, 480, 14,
                Component.translatable("instance_mover.result.skipped", stats.skipped()), font));
        addRenderableWidget(new StringWidget(left, 68, 480, 14,
                Component.translatable("instance_mover.result.failed", stats.failed()), font));
        addRenderableWidget(new StringWidget(left, 84, 480, 14,
                Component.translatable("instance_mover.result.bytes", SizeFormatter.humanSize(stats.bytes())), font));
        addRenderableWidget(new StringWidget(left, 100, 480, 14,
                Component.translatable("instance_mover.result.elapsed",
                        "%.1f".formatted(report.elapsedMillis() / 1000.0)), font));

        int y = 120;
        if (report.hasBackup()) {
            addRenderableWidget(new StringWidget(left, y, 480, 14,
                    Component.translatable("instance_mover.result.backup",
                            report.backupDir() == null ? "" : report.backupDir().toString()), font));
            y += 18;
        }

        List<String> warnings = report.warnings();
        for (String warning : warnings) {
            addRenderableWidget(new StringWidget(left, y, 480, 14,
                    Component.literal(Component.translatable("instance_mover.result.warnings").getString()
                            + "：" + warning), font));
            y += 14;
        }

        List<String> skipped = report.skippedItems().stream().map(ItemResult::name).toList();
        if (!skipped.isEmpty()) {
            addRenderableWidget(new StringWidget(left, y, 480, 14,
                    Component.translatable("instance_mover.result.skipped_items",
                            String.join("、", skipped)), font));
            y += 14;
        }

        List<String> errors = report.errors();
        if (errors.isEmpty()) {
            addRenderableWidget(new StringWidget(left, y, 480, 14,
                    Component.translatable("instance_mover.result.no_errors"), font));
        } else {
            String shown = errors.stream().limit(MAX_ERROR_LINES).collect(Collectors.joining("\n"));
            if (errors.size() > MAX_ERROR_LINES) {
                shown = shown + "\n…（共 %d 条）".formatted(errors.size());
            }
            // 自己折行 + 行距 12px（t18：MultiLineTextWidget 行距为 0，多行会互相压）
            TextBlock.add(Component.literal(shown), left, y, 480, font, this::addRenderableWidget);
        }

        // 备份不完整警告（审查 F2）：备份阶段有失败文件时给一条可见的醒目提示
        if (report.backupIncomplete()) {
            addRenderableWidget(new StringWidget(left, y, 480, 14,
                    Component.literal("警告：备份不完整——%d 个文件未能备份，覆盖已继续，请先手动检查备份目录：%s"
                                    .formatted(report.backupStats().failed(),
                                            report.backupDir() == null ? "" : report.backupDir().toString()))
                            .withStyle(ChatFormatting.GOLD), font));
            y += 16;
        }

        // 先算「需重启」这块的几何（正文行数自适应），反馈行再摆到它上方的空隙里
        List<ItemResult> restartItems = report.restartRequiredItems();
        Component restartBodyText = null;
        int restartBodyY = 0;
        int restartTitleY = 0;
        int feedbackY = height - 142;
        if (!restartItems.isEmpty()) {
            String names = restartItems.stream().map(ItemResult::name).collect(Collectors.joining("、"));
            restartBodyText = Component.translatable("instance_mover.restart.body", names)
                    .withStyle(ChatFormatting.GOLD);
            int bodyHeight = TextBlock.height(TextBlock.split(font, restartBodyText, 480));
            int hintY = height - 70;                       // 提示行
            restartBodyY = hintY - 10 - bodyHeight;        // 正文块自下而上
            restartTitleY = restartBodyY - 18;             // 标题与正文之间留 18px
            feedbackY = Math.min(feedbackY, restartTitleY - 20);
        }

        // 独立反馈行：不依赖「需重启」那块的控件是否存在（审查 F8'）
        feedbackWidget = addRenderableWidget(new StringWidget(left, feedbackY, 480, 14,
                Component.empty(), font));

        // 需重启提示：正文（含条目名清单）必须**可见**，不能只挂在 tooltip 上（审查 F2'）
        if (!restartItems.isEmpty()) {
            TextBlock.add(restartBodyText, left, restartBodyY, 480, font, this::addRenderableWidget);
            addRenderableWidget(new StringWidget(left, restartTitleY, 480, 14,
                    Component.translatable("instance_mover.restart.title").withStyle(ChatFormatting.GOLD), font));
            addRenderableWidget(new StringWidget(left, height - 70, 480, 14,
                    Component.translatable("instance_mover.restart.hint"), font));
            addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.quit_game"),
                            ignored -> Minecraft.getInstance().stop())
                    .pos(width / 2 - 155, height - 28).size(150, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.done"),
                            ignored -> onClose())
                    .pos(width / 2 + 5, height - 28).size(150, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.done"),
                            ignored -> onClose())
                    .pos(width / 2 - 50, height - 28).size(100, 20).build());
        }

        if (report.hasBackup()) {
            addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.open_backup"),
                            ignored -> openBackup())
                    .pos(width / 2 - 155 + (restartItems.isEmpty() ? 0 : 160), height - 52)
                    .size(150, 20).build());
        }
        if (!report.errors().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.copy_errors"),
                            ignored -> copyErrors())
                    .pos(width / 2 - 155 + (restartItems.isEmpty() ? 160 : 320), height - 52)
                    .size(150, 20).build());
        }
    }

    private void openBackup() {
        if (report == null || report.backupDir() == null) {
            return;
        }
        try {
            Util.getPlatform().openPath(report.backupDir());
            setFeedback(Component.translatable("instance_mover.button.open_backup")
                    .getString() + "：" + report.backupDir());
        } catch (RuntimeException ex) {
            setFeedback(Component.translatable("instance_mover.result.open_failed",
                    report.backupDir().toString()).getString());
        }
    }

    private void copyErrors() {
        if (report == null) {
            return;
        }
        String text = String.join(System.lineSeparator(), report.errors());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(text);
        }
        setFeedback(Component.translatable("instance_mover.result.copied").getString()
                + "（%d 条）".formatted(report.errors().size()));
    }

    /** 反馈始终写进独立的反馈行，不会把「需重启」标题顶掉（审查 F8'）。 */
    private void setFeedback(String text) {
        if (feedbackWidget != null) {
            feedbackWidget.setMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
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
