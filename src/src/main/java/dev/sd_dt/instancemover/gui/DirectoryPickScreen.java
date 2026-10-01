package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.util.PathUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 游戏内目录选择器（「浏览…」按钮）。
 *
 * <p>26.2 原版没有目录选择对话框，而 AWT/Swing 的文件选择器与 LWJGL 混用有卡死/崩溃风险，
 * 所以这里做纯游戏内的浏览：显示当前目录、列出子文件夹（点一下进入）、[上一级]、
 * [选择此目录]。任何读取失败都只提示、不抛异常。</p>
 */
public final class DirectoryPickScreen extends Screen {

    private static final int ROWS_PER_PAGE = 7;

    private final Screen parent;
    private final Consumer<Path> onPick;
    private final Pagination pagination = new Pagination(ROWS_PER_PAGE);

    private Path current;
    private List<Path> children = List.of();
    private String error;
    /** 目录列表是否正在后台读取（审查 F5'：渲染线程不做文件系统遍历）。 */
    private boolean loading;
    /** 是否已经触发过首次载入。 */
    private boolean loadedOnce;
    /** 导航代际号：快速连续进入/上级时只接受最新一次目录列表结果。 */
    private long requestGeneration;

    public DirectoryPickScreen(Screen parent, Path start, Consumer<Path> onPick) {
        super(Component.translatable("instance_mover.browse.title"));
        this.parent = parent;
        this.onPick = onPick;
        this.current = startDirectory(start);
        // 构造器里不读盘：等 init() 之后在后台线程载入（F5'）
    }

    /** 起始目录：给定值 → 它的上一层 → 实例目录 → 用户主目录 → 盘根。 */
    private static Path startDirectory(Path start) {
        if (start != null && Files.isDirectory(PathUtils.lp(start))) {
            return start.toAbsolutePath().normalize();
        }
        Path current = gameDirectory();
        if (current != null) {
            Path parent = current.getParent();
            if (parent != null && Files.isDirectory(PathUtils.lp(parent))) {
                return parent;
            }
            return current;
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path homePath = Path.of(home);
            if (Files.isDirectory(PathUtils.lp(homePath))) {
                return homePath;
            }
        }
        return Path.of("").toAbsolutePath();
    }

    private static Path gameDirectory() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.gameDirectory != null) {
                return minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // 没启动游戏（单测）时忽略
        }
        return null;
    }

    /**
     * 后台读取当前目录的子文件夹，结果回主线程刷新界面（审查 F5'）。
     *
     * <p>大目录 / 慢盘上不再卡渲染帧；载入期间界面显示占位文字，导航用代际号防止旧结果覆盖新目录。</p>
     */
    private void refreshAsync() {
        long generation = ++requestGeneration;
        loading = true;
        error = null;
        rebuildWidgets();
        Thread thread = new Thread(() -> {
            List<Path> found = new ArrayList<>();
            String failure = null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(PathUtils.lp(current))) {
                for (Path entry : stream) {
                    try {
                        if (Files.isDirectory(entry)) {
                            found.add(entry);
                        }
                    } catch (RuntimeException ignored) {
                        // 单个条目读不了就跳过
                    }
                }
            } catch (IOException | RuntimeException ex) {
                failure = ex.toString();
            }
            found.sort(Comparator.comparing(PathUtils::fileName, String.CASE_INSENSITIVE_ORDER));
            List<Path> result = List.copyOf(found);
            String errorText = failure;
            Minecraft minecraft = Minecraft.getInstance();
            Runnable apply = () -> {
                if (generation != requestGeneration) {
                    return;   // 已经切到别的目录了，丢弃过期结果
                }
                children = result;
                error = errorText;
                loading = false;
                rebuildWidgets();
            };
            if (minecraft == null) {
                apply.run();
                return;
            }
            try {
                minecraft.execute(apply);
            } catch (RuntimeException ex) {
                apply.run();
            }
        }, "instance-mover-browse");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void added() {
        super.added();
        if (!loadedOnce) {
            loadedOnce = true;
            refreshAsync();   // 首次载入放后台线程（F5'）
        }
    }

    private void enter(Path dir) {
        current = dir.toAbsolutePath().normalize();
        pagination.setPage(0);
        children = List.of();
        refreshAsync();
    }

    private void goUp() {
        Path parent = current.getParent();
        if (parent != null) {
            enter(parent);
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(new StringWidget(width / 2 - 240, 8, 480, 20, title, font));
        addRenderableWidget(new StringWidget(width / 2 - 240, 28, 480, 14,
                Component.translatable("instance_mover.browse.current", current.toString()), font));
        if (error != null) {
            addRenderableWidget(new StringWidget(width / 2 - 240, 44, 480, 14,
                    Component.literal(error), font));
        }

        pagination.clamp(children.size());
        int first = pagination.firstIndex(children.size());
        int last = pagination.lastIndex(children.size());

        int y = 60;
        if (loading) {
            // 载入中占位（目录读取在后台线程，见 refreshAsync）
            addRenderableWidget(new StringWidget(width / 2 - 240, y, 480, 14,
                    Component.literal("正在读取目录…"), font));
        } else if (children.isEmpty()) {
            addRenderableWidget(new StringWidget(width / 2 - 240, y, 480, 14,
                    Component.translatable("instance_mover.browse.empty"), font));
        }
        for (int index = first; index < last; index++) {
            Path dir = children.get(index);
            Button button = Button.builder(
                            Component.literal(PathUtils.fileName(dir)),
                            ignored -> enter(dir))
                    .pos(width / 2 - 240, y).size(480, 20).build();
            addRenderableWidget(button);
            y += 22;
        }

        int footerY = height - 30;
        Button up = Button.builder(Component.translatable("instance_mover.browse.up"), ignored -> goUp())
                .pos(width / 2 - 240, footerY - 24).size(90, 20).build();
        up.active = current.getParent() != null;
        addRenderableWidget(up);

        Button previous = Button.builder(Component.translatable("instance_mover.page.prev"), ignored -> {
            pagination.previous();
            rebuildWidgets();
        }).pos(width / 2 - 140, footerY - 24).size(70, 20).build();
        previous.active = pagination.hasPrevious();
        addRenderableWidget(previous);

        addRenderableWidget(new StringWidget(width / 2 - 60, footerY - 19, 120, 12,
                Component.translatable("instance_mover.page.indicator",
                        pagination.page() + 1, pagination.pageCount(children.size())), font));

        Button next = Button.builder(Component.translatable("instance_mover.page.next"), ignored -> {
            pagination.next(children.size());
            rebuildWidgets();
        }).pos(width / 2 + 70, footerY - 24).size(70, 20).build();
        next.active = pagination.hasNext(children.size());
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(Component.translatable("instance_mover.browse.choose"),
                        ignored -> choose())
                .pos(width / 2 - 160, footerY).size(150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.back"), ignored -> onClose())
                .pos(width / 2 + 10, footerY).size(150, 20).build());
    }

    private void choose() {
        Path picked = current;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
        if (onPick != null) {
            try {
                onPick.accept(picked);
            } catch (RuntimeException ignored) {
                // 回填失败不影响游戏
            }
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
