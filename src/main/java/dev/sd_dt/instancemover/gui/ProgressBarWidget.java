package dev.sd_dt.instancemover.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * 只读进度条。
 *
 * <p>用 {@link AbstractSliderButton} 的滑块底色当进度条画（26.2 的界面渲染改成了
 * {@code GuiGraphicsExtractor}，直接复用原版控件最稳当），不可交互、只显示进度。</p>
 */
public final class ProgressBarWidget extends AbstractSliderButton {

    private long done;
    private long total;
    private Component label;

    public ProgressBarWidget(int x, int y, int width, int height, Component label) {
        super(x, y, width, height, label, 0.0);
        this.label = label;
        this.active = false;   // 纯展示：不接受点击/拖拽
    }

    /** 更新进度（done/total，total<=0 时按 0 处理）。 */
    public void setProgress(long done, long total, Component label) {
        this.done = Math.max(0L, done);
        this.total = Math.max(0L, total);
        this.label = label;
        setValue(this.total <= 0 ? 0.0 : Math.clamp((double) this.done / (double) this.total, 0.0, 1.0));
    }

    public double progress() {
        return this.value;
    }

    @Override
    protected void updateMessage() {
        setMessage(label == null ? Component.empty() : label);
    }

    @Override
    protected void applyValue() {
        // 只读：拖动不会改变任何状态
    }
}
