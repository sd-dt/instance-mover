package dev.sd_dt.instancemover.gui;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/**
 * 多行文本块 —— t18 修复「逐行叠印」的共用实现。
 *
 * <p><b>问题</b>：{@code MultiLineTextWidget.visitLines} 把行距写死成 9px（字节码里就是
 * {@code bipush 9}），正好等于字体高度，于是行与行**零行距**相贴：关于页正文、
 * 结果页金色标题压正文都表现为「叠印」。实测像素行带：关于页正文在 y=100/109/118，
 * 间距恰好 9px；结果页标题 y=592、正文首行 y=604，只有 5px 空隙，在缩略图/截图上看着就是叠的。</p>
 *
 * <p><b>做法</b>：自己按宽度折行（{@link Font#splitIgnoringLanguage}），每行一个
 * {@link StringWidget}，行距固定 {@link #LINE_HEIGHT} = 9px 字体 + 3px 空隙；
 * 返回占用高度，调用方据此摆放后续元素（块与块之间再留 8px 以上）。
 * 所有多行文本都必须走这里，不要再直接用 {@code MultiLineTextWidget}。</p>
 */
final class TextBlock {

    /** 行距：字体 9px + 3px 空隙（原来是 9，即零行距）。 */
    static final int LINE_HEIGHT = 12;

    private TextBlock() {
    }

    /** 按宽度折行，保留原文里的 {@code \n} 硬换行。 */
    static List<FormattedText> split(Font font, Component text, int maxWidth) {
        return font.splitIgnoringLanguage(text, maxWidth);
    }

    /** 文本块占用高度（行数 × 行距，至少一行）。 */
    static int height(List<FormattedText> lines) {
        return Math.max(LINE_HEIGHT, lines.size() * LINE_HEIGHT);
    }

    /** 折行 + 逐行加控件，返回占用高度；颜色沿用原文（金色标题等不会被吃掉）。 */
    static int add(Component text, int x, int y, int maxWidth, Font font, Consumer<AbstractWidget> adder) {
        List<FormattedText> lines = split(font, text, maxWidth);
        for (int index = 0; index < lines.size(); index++) {
            adder.accept(new StringWidget(x, y + index * LINE_HEIGHT, maxWidth, LINE_HEIGHT,
                    Component.literal(lines.get(index).getString()).withStyle(text.getStyle()), font));
        }
        return height(lines);
    }
}
