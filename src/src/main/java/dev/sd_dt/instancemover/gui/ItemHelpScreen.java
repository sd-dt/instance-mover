package dev.sd_dt.instancemover.gui;

import dev.sd_dt.instancemover.model.MigrateCatalog;
import dev.sd_dt.instancemover.model.MigrateItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「查看每项作用」：把 25 项清单的名称、类型与作用文案（逐字照抄 v1.1）分页列出来。
 */
public final class ItemHelpScreen extends Screen {

    private static final int ROWS_PER_PAGE = 3;

    private final Screen parent;
    private final Pagination pagination = new Pagination(ROWS_PER_PAGE);

    public ItemHelpScreen(Screen parent) {
        super(Component.translatable("instance_mover.about.item_help_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(new StringWidget(width / 2 - 160, 10, 320, 20, title, font));

        int total = MigrateCatalog.ALL_ITEMS.size();
        pagination.clamp(total);
        int first = pagination.firstIndex(total);
        int last = pagination.lastIndex(total);

        int y = 34;
        for (int index = first; index < last; index++) {
            MigrateItem item = MigrateCatalog.ALL_ITEMS.get(index);
            Component heading = Component.literal("%s（%s%s）".formatted(
                    item.name(),
                    item.label(),
                    item.restartRequired()
                            ? Component.translatable("instance_mover.items.restart_badge").getString()
                            : ""));
            addRenderableWidget(new StringWidget(width / 2 - 220, y, 440, 12, heading, font));
            // 作用文案：自己折行 + 行距 12px（t18：不再用行距为 0 的 MultiLineTextWidget）
            int descriptionHeight = TextBlock.add(Component.literal(item.description()),
                    width / 2 - 220, y + 14, 440, font, this::addRenderableWidget);
            if (item.defaultOn()) {
                addRenderableWidget(new StringWidget(width / 2 + 150, y, 70, 12,
                        Component.translatable("instance_mover.items.default_badge"), font));
            }
            // 行距按实际占用动态堆叠：长文案也不会压到下一项
            y += 14 + descriptionHeight + 10;
        }

        int footerY = height - 28;
        Button previous = Button.builder(Component.translatable("instance_mover.page.prev"),
                        ignored -> {
                            pagination.previous();
                            rebuildWidgets();
                        })
                .pos(width / 2 - 154, footerY).size(88, 20).build();
        previous.active = pagination.hasPrevious();
        addRenderableWidget(previous);

        addRenderableWidget(new StringWidget(width / 2 - 60, footerY + 6, 120, 12,
                Component.translatable("instance_mover.page.indicator",
                        pagination.page() + 1, pagination.pageCount(total)), font));

        Button next = Button.builder(Component.translatable("instance_mover.page.next"),
                        ignored -> {
                            pagination.next(total);
                            rebuildWidgets();
                        })
                .pos(width / 2 + 66, footerY).size(88, 20).build();
        next.active = pagination.hasNext(total);
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(Component.translatable("instance_mover.button.back"), ignored -> onClose())
                .pos(width / 2 - 50, height - 52).size(100, 20).build());
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
