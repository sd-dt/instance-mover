package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.InstanceMoverClient;
import dev.sd_dt.instancemover.config.MoverConfig;
import dev.sd_dt.instancemover.engine.TransferListener;
import dev.sd_dt.instancemover.engine.TransferReport;
import dev.sd_dt.instancemover.engine.TransferRunner;
import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.SizeFormatter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 进度页：进度条 + 阶段 + 已完成/总数 + 已复制体积 + [中止]。
 *
 * <p>转移跑在后台守护线程（{@link TransferRunner#runAsync()}），所有界面更新都用
 * {@code Minecraft.execute(...)} 切回主线程，所以不会冻结游戏。
 * 完成后自动切到 {@link ResultScreen}；转移过程中 ESC 不允许误关（必须点「中止」）。</p>
 *
 * <p><b>t23 语义（方案 b）</b>：只有「玩家在迁移界面点过『开始迁移』并真实成功」这一条路径会写
 * {@code firstRunDone=true}（本界面出现本身就意味着玩家见过界面并做出了选择）；写完下次启动不再弹，
 * 想重看就去关于页点「重新显示迁移引导」重置。绝不替「没看过界面」的玩家写这个标记。</p>
 */
public final class ProgressScreen extends Screen implements TransferListener {

    private static final int LOG_LINES = 6;

    private final Screen parent;
    private final Path source;
    private final Path target;
    private final List<MigrateItem> items;
    private final MoverConfig config;
    private final Path configFile;
    private final TransferRunner runner;
    private final CompletableFuture<TransferReport> future;

    private final List<String> logLines = new ArrayList<>();
    private String phaseText = "";
    private String currentLine = "";
    private long done;
    private long total;
    private long writtenBytes;
    private boolean cancelled;
    private boolean finished;

    private ProgressBarWidget bar;
    private StringWidget phaseWidget;
    private StringWidget currentWidget;
    private StringWidget counterWidget;
    /** 日志行控件（每行一个，行距 12px）。 */
    private final List<StringWidget> logWidgets = new ArrayList<>();
    private Button abortButton;

    public ProgressScreen(Screen parent, Path source, Path target, List<MigrateItem> items,
                          MoverConfig config, Path configFile) {
        super(Component.translatable("instance_mover.phase.transfer"));
        this.parent = parent;
        this.source = source;
        this.target = target;
        this.items = List.copyOf(items);
        this.config = config;
        this.configFile = configFile;
        this.runner = new TransferRunner(source, target, items, config.isBackupEnabled(), this,
                config.isSkipSameEnabled());
        this.future = runner.runAsync();
        this.future.whenComplete((report, error) -> onMainThread(() -> finish(report, error)));
    }

    private static void onMainThread(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            action.run();
            return;
        }
        try {
            minecraft.execute(action);
        } catch (RuntimeException ex) {
            action.run();
        }
    }

    @Override
    protected void init() {
        int left = width / 2 - 240;
        addRenderableWidget(new StringWidget(left, 12, 480, 20, title, font));

        phaseWidget = addRenderableWidget(new StringWidget(left, 40, 480, 14,
                Component.literal(phaseText), font));
        bar = addRenderableWidget(new ProgressBarWidget(left, 58, 480, 20,
                Component.translatable("instance_mover.progress.files", done, Math.max(total, 1))));
        bar.setProgress(done, Math.max(total, 1),
                Component.translatable("instance_mover.progress.files", done, Math.max(total, 1)));
        counterWidget = addRenderableWidget(new StringWidget(left, 82, 480, 14,
                Component.literal(counterText()), font));
        currentWidget = addRenderableWidget(new StringWidget(left, 98, 480, 14,
                Component.literal(currentLine), font));
        // 日志逐行一个控件（t18：单个 StringWidget 塞多行文本会把各行叠在同一位置）
        logWidgets.clear();
        for (int index = 0; index < LOG_LINES; index++) {
            logWidgets.add(addRenderableWidget(new StringWidget(left, 118 + index * TextBlock.LINE_HEIGHT,
                    480, 12, Component.empty(), font)));
        }
        refreshLog();

        abortButton = addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.abort"),
                        ignored -> requestCancel())
                .pos(width / 2 - 50, height - 28).size(100, 20).build());
        abortButton.active = !cancelled && !finished;
    }

    private String counterText() {
        return Component.translatable("instance_mover.progress.files", done, Math.max(total, 1)).getString()
                + "　" + Component.translatable("instance_mover.result.bytes",
                SizeFormatter.humanSize(writtenBytes)).getString();
    }

    private String logText() {
        if (logLines.isEmpty()) {
            return "";
        }
        return String.join("\n", logLines);
    }

    /** 把最近 {@link #LOG_LINES} 条日志逐行刷新到各自的控件上（t18：不再用多行字符串叠印）。 */
    private void refreshLog() {
        for (int index = 0; index < logWidgets.size(); index++) {
            int fromTail = logLines.size() - logWidgets.size() + index;
            String line = fromTail >= 0 && fromTail < logLines.size() ? logLines.get(fromTail) : "";
            logWidgets.get(index).setMessage(Component.literal(line));
        }
    }

    private void requestCancel() {
        if (cancelled || finished) {
            return;
        }
        cancelled = true;
        runner.cancel();
        if (abortButton != null) {
            abortButton.active = false;
            abortButton.setMessage(Component.literal(
                    Component.translatable("instance_mover.button.abort").getString() + "…"));
        }
    }

    private void finish(TransferReport report, Throwable error) {
        if (finished) {
            return;
        }
        finished = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (report != null && !report.cancelled() && !report.hasError()) {
            // t23 语义（方案 b）：完成一次**真实成功**的迁移后才写 firstRunDone=true，下次启动不再弹；
            // 这是允许写 true 的两条路径之一（另一条是玩家点「不再提示」），
            // 且随时能在关于页点「重新显示迁移引导」重置回 false。
            // 本界面只可能由玩家在迁移界面点「开始迁移」后出现，所以不会替「没看过界面」的玩家做决定。
            config.setFirstRunDone(true);
            config.setLastSource(source.toString());
            boolean saved = config.saveQuietly(configFile);
            InstanceMoverClient.LOGGER.info(
                    "[{}] 迁移成功 → 写入 firstRunDone=true（下次启动不再弹；想重看请到关于页点「重新显示迁移引导」），落盘{}：{}",
                    InstanceMoverClient.MOD_ID, saved ? "成功" : "失败", configFile);
        }
        minecraft.gui.setScreen(new ResultScreen(parent, report, error == null ? null : error.toString()));
    }

    // ------------------------------------------------------------------
    // 转移线程回调（全部切回主线程再动界面）
    // ------------------------------------------------------------------

    @Override
    public void onLog(String line) {
        onMainThread(() -> {
            logLines.add(line);
            while (logLines.size() > LOG_LINES) {
                logLines.removeFirst();
            }
            refreshLog();
            if (line.contains("→")) {
                currentLine = line;
                if (currentWidget != null) {
                    currentWidget.setMessage(Component.literal(currentLine));
                }
            }
        });
    }

    @Override
    public void onPhase(String text, boolean indeterminate) {
        onMainThread(() -> {
            phaseText = text;
            if (phaseWidget != null) {
                phaseWidget.setMessage(Component.literal(text));
            }
            if (currentLine.isBlank()) {
                currentLine = text;
                if (currentWidget != null) {
                    currentWidget.setMessage(Component.literal(currentLine));
                }
            }
        });
    }

    @Override
    public void onProgress(long doneCount, long totalCount) {
        onMainThread(() -> {
            done = doneCount;
            total = totalCount;
            if (bar != null) {
                bar.setProgress(done, Math.max(total, 1),
                        Component.translatable("instance_mover.progress.files", done, Math.max(total, 1)));
            }
            if (counterWidget != null) {
                counterWidget.setMessage(Component.literal(counterText()));
            }
        });
    }

    @Override
    public void onBytesWritten(long bytes) {
        onMainThread(() -> {
            writtenBytes = bytes;
            if (counterWidget != null) {
                counterWidget.setMessage(Component.literal(counterText()));
            }
        });
    }

    @Override
    public void onDone(TransferReport report) {
        onMainThread(() -> {
            phaseText = Component.translatable("instance_mover.result.title").getString();
            if (phaseWidget != null) {
                phaseWidget.setMessage(Component.literal(phaseText));
            }
        });
    }

    @Override
    public void onCancelled(TransferReport report) {
        onMainThread(() -> {
            phaseText = Component.translatable("instance_mover.result.cancelled_title").getString();
            if (phaseWidget != null) {
                phaseWidget.setMessage(Component.literal(phaseText));
            }
        });
    }

    @Override
    public void onError(Throwable error) {
        onMainThread(() -> {
            logLines.add(String.valueOf(error));
            while (logLines.size() > LOG_LINES) {
                logLines.removeFirst();
            }
            refreshLog();
        });
    }

    @Override
    public void onClose() {
        if (!finished) {
            requestCancel();
            return;
        }
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return finished;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
