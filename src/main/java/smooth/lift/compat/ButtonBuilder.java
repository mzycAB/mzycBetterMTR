package smooth.lift.compat;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 1.18.2 没有 {@code Button.builder(Component, OnPress).bounds(x,y,w,h).build()} 这套链式 API
 * （它是 1.19 才加进来的，1.18.2 只有 {@code new Button(x, y, w, h, msg, onPress)}）。
 *
 * <p>这个垫片把 1.20.4 的链式写法原样保留下来，最后 {@link #build()} 时落到 1.18.2 的构造函数，
 * 这样 100 多处按钮构建代码不用逐个改写、也不会因为参数顺序调换而出错。
 */
public final class ButtonBuilder {

    private final Component message;
    private final Button.OnPress onPress;
    private int x;
    private int y;
    private int width = 150;
    private int height = 20;

    private ButtonBuilder(Component message, Button.OnPress onPress) {
        this.message = message;
        this.onPress = onPress;
    }

    public static ButtonBuilder builder(Component message, Button.OnPress onPress) {
        return new ButtonBuilder(message, onPress);
    }

    public ButtonBuilder bounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public ButtonBuilder pos(int x, int y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public ButtonBuilder size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public ButtonBuilder width(int width) {
        this.width = width;
        return this;
    }

    public Button build() {
        return new Button(x, y, width, height, message, onPress);
    }
}