package smooth.lift.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 【09-30 续 2】会**按宽度自动换行**的输入框（讲述人 UI 左侧那个大输入框）。
 *
 * <h2>为什么原版 EditBox 不够用</h2>
 * 原版 EditBox 只有一行：文字长了往左右卷（横向滚动），玩家看不到全文。
 * 用户点名「输入框里的文字顶到右侧边界自动换行」—— 但**只换行给人看**：
 * 存进去的字符串里没有任何换行符，真正播报时只有 {@code |} 才算换行
 * （见 {@link TrainAnnounceNarrator#expandUserTemplate}）。
 *
 * <h2>实现方式</h2>
 * 输入仍然全交给原版 EditBox 那一套（打字 / 中文输入法 / 光标键照旧），
 * 只是**绘制整个重写**（1.20.4 的 {@code render} 是 final，覆写钩子是
 * {@link #renderWidget}）：把 {@code getValue()} 按宽度切成若干行（逐字符贪心、
 * 无损 —— 各行连起来 == 原串）、竖向滚动让光标行可见、光标画在换行后的正确位置；
 * 点击定位按换行后的坐标反算字符下标（{@link #charIndexAt}）。
 * ★ 1.20.4 的 EditBox 读不到「可编辑」与「选区终点」（没有 {@code isEditable()} /
 * {@code getHighlightPos()}），这里自己记一份 editable、选区从 {@code getHighlighted()}
 * 反推。上下键不做（原版本来也没有）—— 换行纯粹是视觉层，正是用户点名的口径。
 */
public class WrappingEditBox extends EditBox {

    /** 文字到框边的内边距。 */
    private static final int PAD = 4;
    /** 渲染行高 = 字体行高 + 这个间隙。 */
    private static final int LINE_GAP = 2;

    private final Font font;

    /** 光标闪烁计数（原版的 frame 拿不稳，自己数；界面每 tick 调 {@link #tickBlink}）。 */
    private int blinkTick;

    /** 1.20.4 的 EditBox 只给 {@code setEditable} 不给读 ⇒ 这里自己记一份。 */
    private boolean textEditable = true;

    public WrappingEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        this.font = font;
    }

    @Override
    public void setEditable(boolean enabled) {
        super.setEditable(enabled);
        textEditable = enabled;
    }

    /** 当前可不可以编辑（{@code saveEditor} / 插入按钮用）。 */
    public boolean isTextEditable() {
        return textEditable;
    }

    /** 界面每 tick 调一次：推进光标闪烁。 */
    public void tickBlink() {
        blinkTick++;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        // 底 + 一圈边框（聚焦亮、失焦暗 —— 与原版输入框同款观感）
        guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF000000);
        guiGraphics.renderOutline(getX(), getY(), width, height, isFocused() ? 0xFFFFFFFF : 0xFF606060);

        String text = getValue();
        int innerW = width - PAD * 2;
        int innerH = height - PAD * 2;
        List<int[]> lines = wrapLines(text, innerW);
        int lineH = font.lineHeight + LINE_GAP;
        int cursor = Mth.clamp(getCursorPosition(), 0, text.length());
        int cursorLine = lineIndexOf(lines, cursor, text.length());
        int visibleLines = Math.max(1, innerH / lineH);
        int top = topLine(lines.size(), cursorLine, visibleLines);
        int textX = getX() + PAD;
        int textY = getY() + PAD;
        int color = textEditable ? 0xFFFFFF : 0xA0A0A0;

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1);

        // 选区底色（先画，字压在上面）。1.20.4 拿不到选区终点，从 getHighlighted() 反推：
        // 选中串 == 光标往后那一段 ⇒ 向前选；否则 == 光标往前那一段 ⇒ 向后选。
        String highlighted = getHighlighted();
        int selStart = cursor;
        int selEnd = cursor;
        if (highlighted != null && !highlighted.isEmpty()) {
            int len = highlighted.length();
            if (cursor + len <= text.length() && text.substring(cursor, cursor + len).equals(highlighted)) {
                selStart = cursor;
                selEnd = cursor + len;
            } else if (cursor - len >= 0 && text.substring(cursor - len, cursor).equals(highlighted)) {
                selStart = cursor - len;
                selEnd = cursor;
            }
        }
        for (int i = top; i < lines.size() && (i - top) * lineH <= innerH; i++) {
            int[] seg = lines.get(i);
            int y = textY + (i - top) * lineH;
            if (selStart != selEnd && selEnd > seg[0] && selStart < seg[1]) {
                int from = font.width(text.substring(seg[0], Math.max(seg[0], selStart)));
                int to = font.width(text.substring(seg[0], Math.min(seg[1], selEnd)));
                guiGraphics.fill(textX + from, y, textX + to, y + font.lineHeight, 0xFF0000FF);
            }
            guiGraphics.drawString(font, text.substring(seg[0], seg[1]), textX, y, color, false);
        }
        // 光标（聚焦 + 可编辑 + 闪烁时才画）
        if (isFocused() && textEditable && blinkTick / 6 % 2 == 0) {
            int[] seg = lines.get(cursorLine);
            int cx = font.width(text.substring(seg[0], Math.min(cursor, seg[1])));
            int cy = textY + (cursorLine - top) * lineH;
            guiGraphics.fill(textX + cx, cy, textX + cx + 1, cy + font.lineHeight, 0xFFFFFFFF);
        }
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean inside = mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height;
        if (!isFocused() && !inside) {
            return false;
        }
        // 仿原版：聚焦着的输入框把所有点击都吞掉（不漏给底下的屏幕）
        setFocused(true);
        if (button == 0 && inside && textEditable) {
            int idx = charIndexAt(mouseX, mouseY);
            setCursorPosition(idx);
            setHighlightPos(idx);
        }
        return true;
    }

    /** 把文本按 {@code maxWidth} 逐字符贪心切行，返回每行的 [start, end) —— 连起来 == 原串。 */
    private List<int[]> wrapLines(String text, int maxWidth) {
        List<int[]> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            String fit = font.plainSubstrByWidth(text.substring(start), maxWidth);
            int count = fit.length();
            if (count <= 0) {
                count = 1; // 单字超宽也硬切一个，防死循环
            }
            lines.add(new int[]{start, Math.min(start + count, text.length())});
            start += count;
        }
        if (lines.isEmpty()) {
            lines.add(new int[]{0, 0});
        }
        return lines;
    }

    /**
     * 光标（字符下标）落在第几行。行尾且后面还有字 ⇒ 归下一行行首（换行视图里更直观）。
     */
    private int lineIndexOf(List<int[]> lines, int cursor, int textLength) {
        for (int i = 0; i < lines.size(); i++) {
            int[] seg = lines.get(i);
            if (cursor < seg[1]) {
                return i;
            }
            if (cursor == seg[1]) {
                return cursor == textLength ? i : i + 1;
            }
        }
        return lines.size() - 1;
    }

    /** 渲染从第几行开始（自动跟滚：让光标行保持可见）。 */
    private int topLine(int lineCount, int cursorLine, int visibleLines) {
        if (lineCount <= visibleLines) {
            return 0;
        }
        return Mth.clamp(cursorLine - visibleLines / 2, 0, lineCount - visibleLines);
    }

    /** 点击坐标 → 字符下标（与 renderWidget 同一套换行 / 滚动口径）。 */
    private int charIndexAt(double mouseX, double mouseY) {
        String text = getValue();
        int innerW = width - PAD * 2;
        int innerH = height - PAD * 2;
        List<int[]> lines = wrapLines(text, innerW);
        int lineH = font.lineHeight + LINE_GAP;
        int cursor = Mth.clamp(getCursorPosition(), 0, text.length());
        int top = topLine(lines.size(), lineIndexOf(lines, cursor, text.length()), Math.max(1, innerH / lineH));
        int row = top + (int) ((mouseY - (getY() + PAD)) / lineH);
        row = Mth.clamp(row, 0, lines.size() - 1);
        int[] seg = lines.get(row);
        String line = text.substring(seg[0], seg[1]);
        String fit = font.plainSubstrByWidth(line, (int) (mouseX - (getX() + PAD)));
        return Mth.clamp(seg[0] + Math.max(0, fit.length()), seg[0], seg[1]);
    }
}
