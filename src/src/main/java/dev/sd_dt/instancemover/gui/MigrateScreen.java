package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.InstanceMoverClient;
import dev.sd_dt.instancemover.client.FirstRunGate;
import dev.sd_dt.instancemover.config.MoverConfig;
import dev.sd_dt.instancemover.engine.ScanResult;
import dev.sd_dt.instancemover.engine.ScannedItem;
import dev.sd_dt.instancemover.engine.ScanService;
import dev.sd_dt.instancemover.engine.TransferRunner;
import dev.sd_dt.instancemover.model.MigrateItem;
import dev.sd_dt.instancemover.util.InstanceScanner;
import dev.sd_dt.instancemover.util.InstanceScanner.InstanceCandidate;
import dev.sd_dt.instancemover.util.SizeFormatter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 主界面：① 选择老实例（自动探测的版本实例下拉 + 手填/浏览路径 + 检测）
 * ② 勾选要迁移的内容（默认项 / 额外项 / 缺失项分三段列出）③ 选项 + 底部按钮。
 *
 * <p>探测与扫描都放在后台线程（体积估算有 3000 文件 / 0.5 秒上限），结果回到主线程刷新，
 * 打开界面和点击按钮不会卡住游戏。</p>
 */
public final class MigrateScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int CONTENT_WIDTH = 480;

    private final Screen parent;
    private final MoverConfig config;
    private final FirstRunGate gate;
    private final Path configFile;
    private final Path target;

    private List<InstanceCandidate> candidates = List.of();
    private int candidateIndex = -1;
    private Path selectedSource;
    private ScanResult scan;
    private MigrateSelection selection = new MigrateSelection();
    private final Pagination pagination = new Pagination(8);
    private final List<Row> rows = new ArrayList<>();

    private boolean detectionStarted;
    private boolean busy;
    /** 异步请求代际号：只有最新一次请求的结果会被应用（审查 F6' 竞态保护）。 */
    private long requestGeneration;
    private String statusText = "";
    private String noticeText = "";
    private String errorText = "";

    private Button candidateButton;
    private Button detectButton;
    private Button startButton;
    private EditBox pathField;
    private Checkbox backupToggle;
    private Checkbox skipSameToggle;
    private StringWidget detectedWidget;
    private StringWidget statusWidget;
    private StringWidget noticeWidget;
    private StringWidget errorWidget;
    private StringWidget selectionWidget;
    private StringWidget pageWidget;

    /** 列表里的一行：段标题 / 可勾选条目 / 缺失条目。 */
    private record Row(String header, ScannedItem item, boolean missing) {
    }

    public MigrateScreen(Screen parent, MoverConfig config, FirstRunGate gate, Path configFile, Path target) {
        super(Component.translatable("instance_mover.screen.title"));
        this.parent = parent;
        this.config = config;
        this.gate = gate;
        this.configFile = configFile;
        this.target = target;
        this.statusText = Component.translatable("instance_mover.source.none").getString();
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        int left = width / 2 - CONTENT_WIDTH / 2;
        int y = 8;
        addRenderableWidget(new StringWidget(left, y, CONTENT_WIDTH, 18, title, font));
        addRenderableWidget(new StringWidget(left, y + 16, CONTENT_WIDTH, 12,
                Component.translatable("instance_mover.author_line"), font));

        // ---------- ① 选择老实例 ----------
        y = 40;
        addRenderableWidget(new StringWidget(left, y, CONTENT_WIDTH, 12,
                Component.translatable("instance_mover.section.source"), font));

        candidateButton = addRenderableWidget(Button.builder(candidateLabel(), ignored -> cycleCandidate())
                .pos(left, y + 14).size(CONTENT_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("instance_mover.source.switch_hint")))
                .build());
        candidateButton.active = candidates.size() > 1;

        detectedWidget = addRenderableWidget(new StringWidget(left, y + 36, CONTENT_WIDTH, 12,
                Component.literal(detectedText()), font));

        addRenderableWidget(new StringWidget(left, y + 50, 60, 16,
                Component.translatable("instance_mover.source.path_label"), font));
        pathField = new EditBox(font, left + 40, y + 50, CONTENT_WIDTH - 40 - 130, 18,
                Component.translatable("instance_mover.source.path_label"));
        pathField.setMaxLength(1024);
        pathField.setHint(Component.translatable("instance_mover.source.manual_hint"));
        if (selectedSource != null) {
            pathField.setValue(selectedSource.toString());
        }
        addRenderableWidget(pathField);

        addRenderableWidget(Button.builder(Component.translatable("instance_mover.source.browse"),
                        ignored -> openBrowser())
                .pos(left + CONTENT_WIDTH - 126, y + 49).size(60, 20).build());
        detectButton = addRenderableWidget(Button.builder(Component.translatable("instance_mover.source.detect"),
                        ignored -> detectFromField())
                .pos(left + CONTENT_WIDTH - 62, y + 49).size(62, 20).build());
        detectButton.active = !busy;

        statusWidget = addRenderableWidget(new StringWidget(left, y + 72, CONTENT_WIDTH, 12,
                Component.literal(statusText), font));
        noticeWidget = addRenderableWidget(new StringWidget(left, y + 86, CONTENT_WIDTH, 12,
                Component.literal(noticeText), font));
        errorWidget = addRenderableWidget(new StringWidget(left, y + 100, CONTENT_WIDTH, 12,
                Component.literal(errorText), font));

        // ---------- ② 勾选内容 ----------
        int itemsY = y + 118;
        addRenderableWidget(new StringWidget(left, itemsY, CONTENT_WIDTH, 12,
                Component.translatable("instance_mover.section.items"), font));
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.items.select_all"),
                        ignored -> changeSelection(selection::selectAll))
                .pos(left, itemsY + 14).size(90, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.items.invert"),
                        ignored -> changeSelection(selection::invert))
                .pos(left + 94, itemsY + 14).size(90, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.items.defaults_only"),
                        ignored -> changeSelection(selection::defaultsOnly))
                .pos(left + 188, itemsY + 14).size(90, 18).build());

        Button previous = Button.builder(Component.translatable("instance_mover.page.prev"), ignored -> {
            pagination.previous();
            rebuildWidgets();
        }).pos(left + CONTENT_WIDTH - 150, itemsY + 14).size(60, 18).build();
        previous.active = pagination.hasPrevious();
        addRenderableWidget(previous);
        pageWidget = addRenderableWidget(new StringWidget(left + CONTENT_WIDTH - 88, itemsY + 18, 60, 12,
                Component.translatable("instance_mover.page.indicator",
                        pagination.page() + 1, pagination.pageCount(rows.size())), font));
        Button next = Button.builder(Component.translatable("instance_mover.page.next"), ignored -> {
            pagination.next(rows.size());
            rebuildWidgets();
        }).pos(left + CONTENT_WIDTH - 26, itemsY + 14).size(26, 18).build();
        next.active = pagination.hasNext(rows.size());
        addRenderableWidget(next);

        int listTop = itemsY + 36;
        int listBottom = Math.max(listTop + ROW_HEIGHT * 2, height - 128);
        int rowsPerPage = Math.max(2, Math.min(12, (listBottom - listTop) / ROW_HEIGHT));
        pagination.setPageSize(rowsPerPage);
        addRows(left, listTop);

        // ---------- ③ 选项 ----------
        int optionsY = height - 96;
        backupToggle = addRenderableWidget(Checkbox.builder(Component.translatable("instance_mover.option.backup"), font)
                .pos(left, optionsY).selected(config.isBackupEnabled())
                .onValueChange((checkbox, value) -> config.setBackupEnabled(value))
                .maxWidth(CONTENT_WIDTH)
                .build());
        skipSameToggle = addRenderableWidget(Checkbox.builder(Component.translatable("instance_mover.option.skip_same"), font)
                .pos(left, optionsY + 20).selected(config.isSkipSameEnabled())
                .onValueChange((checkbox, value) -> config.setSkipSameEnabled(value))
                .maxWidth(CONTENT_WIDTH)
                .build());

        selectionWidget = addRenderableWidget(new StringWidget(left, optionsY + 42, CONTENT_WIDTH, 12,
                Component.literal(selectionText()), font));

        // ---------- 底部按钮 ----------
        int footerY = height - 26;
        int buttonWidth = 92;
        int gap = 6;
        int totalWidth = buttonWidth * 5 + gap * 4;
        int x = width / 2 - totalWidth / 2;
        startButton = addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.start"),
                        ignored -> start())
                .pos(x, footerY).size(buttonWidth, 20).build());
        startButton.active = !busy;
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.later"), ignored -> later())
                .pos(x + buttonWidth + gap, footerY).size(buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.never"), ignored -> never())
                .pos(x + 2 * (buttonWidth + gap), footerY).size(buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.help"),
                        ignored -> Minecraft.getInstance().gui.setScreen(new ItemHelpScreen(this)))
                .pos(x + 3 * (buttonWidth + gap), footerY).size(buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.about"),
                        ignored -> Minecraft.getInstance().gui.setScreen(new AboutScreen(this)))
                .pos(x + 4 * (buttonWidth + gap), footerY).size(buttonWidth, 20).build());

        if (!detectionStarted) {
            detectionStarted = true;
            startDetection();
        }
    }

    private void addRows(int left, int listTop) {
        Pagination pagination = this.pagination;
        pagination.clamp(rows.size());
        int first = pagination.firstIndex(rows.size());
        int last = pagination.lastIndex(rows.size());
        int y = listTop;
        for (int index = first; index < last; index++) {
            Row row = rows.get(index);
            if (row.header() != null) {
                addRenderableWidget(new StringWidget(left, y + 4, CONTENT_WIDTH, 12,
                        Component.literal(row.header()), font));
            } else if (row.missing()) {
                String text = Component.translatable("instance_mover.items.missing.line",
                        row.item().name(), reasonText(row.item())).getString();
                StringWidget widget = addRenderableWidget(new StringWidget(left, y + 4, CONTENT_WIDTH, 12,
                        Component.literal(text), font));
                widget.setTooltip(Tooltip.create(Component.literal(row.item().description())));
            } else {
                ScannedItem entry = row.item();
                Checkbox checkbox = Checkbox.builder(Component.literal(labelOf(entry)), font)
                        .pos(left, y)
                        .selected(selection.isChecked(entry.name()))
                        .onValueChange((source, value) -> {
                            selection.setChecked(entry.name(), value);
                            if (selectionWidget != null) {
                                selectionWidget.setMessage(Component.literal(selectionText()));
                            }
                        })
                        .tooltip(Tooltip.create(Component.literal(entry.description())))
                        .maxWidth(CONTENT_WIDTH)
                        .build();
                addRenderableWidget(checkbox);
            }
            y += ROW_HEIGHT;
        }
        if (pageWidget != null) {
            pageWidget.setMessage(Component.translatable("instance_mover.page.indicator",
                    pagination.page() + 1, pagination.pageCount(rows.size())));
        }
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    private Component candidateLabel() {
        if (candidates.isEmpty()) {
            return Component.translatable("instance_mover.source.none");
        }
        InstanceCandidate candidate = candidates.get(Math.max(0, candidateIndex));
        return Component.literal(candidate.dropdownText() + "  ▾");
    }

    private String detectedText() {
        return Component.translatable("instance_mover.source.detected", candidates.size()).getString();
    }

    private String selectionText() {
        List<ScannedItem> transferable = transferable();
        return Component.translatable("instance_mover.items.selected",
                selection.selectedCount(transferable),
                SizeFormatter.humanSize(selection.selectedBytes(transferable))).getString();
    }

    private static String reasonText(ScannedItem entry) {
        String reason = entry.missingReason();
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case "不存在" -> Component.translatable("instance_mover.items.missing.reason.missing").getString();
            case "类型不符" -> Component.translatable("instance_mover.items.missing.reason.wrong_type").getString();
            default -> reason;
        };
    }

    private static String labelOf(ScannedItem entry) {
        String restart = entry.restartRequired()
                ? Component.translatable("instance_mover.items.restart_badge").getString()
                : "";
        // t18：sizeNote 本身已以类型词开头（「文件夹，约 4B / 1 个文件」），不要再拼 label()，
        // 否则会出现「config（文件夹，文件夹，约 4B / 1 个文件）」这种重复。
        return "%s（%s）%s".formatted(entry.name(), entry.sizeNote(), restart);
    }

    private List<ScannedItem> transferable() {
        return scan == null ? List.of() : scan.transferable();
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    private void changeSelection(Consumer<List<ScannedItem>> action) {
        action.accept(transferable());
        rebuildWidgets();
    }

    private void cycleCandidate() {
        if (candidates.size() <= 1) {
            return;
        }
        candidateIndex = (candidateIndex + 1) % candidates.size();
        applyCandidate(candidates.get(candidateIndex));
        rebuildWidgets();
    }

    private void openBrowser() {
        Path start = selectedSource != null ? selectedSource : target;
        Minecraft.getInstance().gui.setScreen(new DirectoryPickScreen(this, start, picked -> {
            pathField.setValue(picked.toString());
            detect(picked);
        }));
    }

    private void detectFromField() {
        String value = pathField.getValue() == null ? "" : pathField.getValue().trim();
        if (value.isEmpty()) {
            errorText = Component.translatable("instance_mover.warn.no_source").getString();
            rebuildWidgets();
            return;
        }
        try {
            detect(Path.of(value));
        } catch (RuntimeException ex) {
            errorText = Component.translatable("instance_mover.source.invalid.not_dir").getString();
            rebuildWidgets();
        }
    }

    private void startDetection() {
        busy = true;
        statusText = Component.translatable("instance_mover.phase.counting").getString();
        runAsync(() -> InstanceScanner.scan(target), found -> {
            busy = false;
            candidates = found == null ? List.of() : found;
            candidateIndex = preferredCandidate(candidates);
            if (candidates.isEmpty()) {
                statusText = Component.translatable("instance_mover.source.none").getString();
            } else {
                applyCandidate(candidates.get(candidateIndex));
            }
            rebuildWidgets();
        });
    }

    /** 优先选上次用过的老实例，其次是第一个候选项。 */
    private int preferredCandidate(List<InstanceCandidate> found) {
        if (found.isEmpty()) {
            return -1;
        }
        String last = config.lastSource();
        if (last != null && !last.isBlank()) {
            for (int index = 0; index < found.size(); index++) {
                if (found.get(index).path().toString().equals(last)) {
                    return index;
                }
            }
        }
        return 0;
    }

    private void applyCandidate(InstanceCandidate candidate) {
        if (candidate == null) {
            return;
        }
        selectedSource = candidate.path();
        if (pathField != null) {
            pathField.setValue(selectedSource.toString());
        }
        noticeText = candidate.notice() == null ? "" : candidate.notice();
        errorText = "";
        statusText = Component.translatable("instance_mover.source.status_label", candidate.statusText()).getString();
        if (!candidate.warnings().isEmpty()) {
            statusText = statusText + "  " + String.join("；", candidate.warnings());
        }
        scanSource(candidate.path());
    }

    /** 探测/检测失败：清掉已选中的老实例，避免「报错了却还能用旧选择开跑」的歧义。 */
    private void clearSelection(String reason) {
        errorText = reason == null ? "" : reason;
        selectedSource = null;
        scan = null;
        rows.clear();
    }

    private void detect(Path path) {
        busy = true;
        errorText = "";
        noticeText = "";
        statusText = Component.translatable("instance_mover.phase.counting").getString();
        runAsync(() -> InstanceScanner.inspect(path, target), candidate -> {
            busy = false;
            if (candidate == null) {
                clearSelection(Component.translatable("instance_mover.source.invalid.not_dir").getString());
            } else if (!candidate.usable()) {
                clearSelection(candidate.rejectReason());
            } else if (candidate.current()) {
                clearSelection(Component.translatable("instance_mover.source.invalid.same").getString());
            } else {
                applyCandidate(candidate);
            }
            rebuildWidgets();
        });
    }

    /** 扫描老实例里有哪些条目（后台线程，回来后重建列表与勾选态）。 */
    private void scanSource(Path source) {
        runAsync(() -> ScanService.scanInstance(source), result -> {
            scan = result;
            selection = MigrateSelection.defaults(transferable());
            rebuildRows();
            rebuildWidgets();
        });
    }

    private void rebuildRows() {
        rows.clear();
        if (scan == null) {
            return;
        }
        if (!scan.present().isEmpty()) {
            rows.add(new Row(Component.translatable("instance_mover.items.group.default",
                    scan.present().size()).getString(), null, false));
            for (ScannedItem entry : scan.present()) {
                rows.add(new Row(null, entry, false));
            }
        }
        if (!scan.extras().isEmpty()) {
            rows.add(new Row(Component.translatable("instance_mover.items.group.extra").getString(), null, false));
            for (ScannedItem entry : scan.extras()) {
                rows.add(new Row(null, entry, false));
            }
        }
        if (!scan.missing().isEmpty()) {
            rows.add(new Row(Component.translatable("instance_mover.items.group.missing").getString(), null, true));
            for (ScannedItem entry : scan.missing()) {
                rows.add(new Row(null, entry, true));
            }
        }
    }

    private void start() {
        if (selectedSource == null) {
            errorText = Component.translatable("instance_mover.warn.no_source").getString();
            rebuildWidgets();
            return;
        }
        List<MigrateItem> items = selection.selectedItems(transferable());
        if (items.isEmpty()) {
            errorText = Component.translatable("instance_mover.warn.no_selection").getString();
            rebuildWidgets();
            return;
        }
        try {
            // 带条目重载：B 情形（老实例落在某个待迁移条目目录内部）在这里就拦下，
            // 玩家留在主界面看红字，不会被先推进进度页再在结果页看到「出错了」。
            TransferRunner.validatePaths(selectedSource, target, items);
        } catch (IllegalArgumentException ex) {
            errorText = ex.getMessage();
            rebuildWidgets();
            return;
        }
        config.setLastSource(selectedSource.toString());
        config.saveQuietly(configFile);
        Minecraft.getInstance().gui.setScreen(
                new ProgressScreen(parent, selectedSource, target, items, config, configFile));
    }

    private void later() {
        gate.markPrompted();
        onClose();
    }

    private void never() {
        gate.markNeverAskAgain(config);
        config.saveQuietly(configFile);
        onClose();
    }

    /**
     * 后台跑一次探测/扫描，结果回主线程处理。
     *
     * <p><b>代际保护（审查 F6'）</b>：每次请求都自增 {@link #requestGeneration}，回调里比对该代际号，
     * 只有「最新一次请求」的结果才会被应用 —— 否则连点「检测」或快速切换候选时，
     * 先发出去的旧结果可能后回来，把新选中的源对应的勾选清单覆盖掉。</p>
     */
    private <T> void runAsync(Supplier<T> work, Consumer<T> done) {
        long generation = ++requestGeneration;
        Thread thread = new Thread(() -> {
            T value;
            try {
                value = work.get();
            } catch (Throwable throwable) {
                InstanceMoverClient.LOGGER.warn("实例探测/扫描失败：{}", throwable.toString());
                value = null;
            }
            T result = value;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                applyIfCurrent(generation, result, done);
                return;
            }
            try {
                minecraft.execute(() -> applyIfCurrent(generation, result, done));
            } catch (RuntimeException ex) {
                applyIfCurrent(generation, result, done);
            }
        }, "instance-mover-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** 只接受最新一次请求的结果；过期结果直接丢弃（不打日志，避免刷屏）。 */
    private <T> void applyIfCurrent(long generation, T result, Consumer<T> done) {
        if (generation != requestGeneration) {
            InstanceMoverClient.LOGGER.debug("丢弃过期的探测/扫描结果（代际 {} ≠ {}）", generation, requestGeneration);
            return;
        }
        done.accept(result);
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
