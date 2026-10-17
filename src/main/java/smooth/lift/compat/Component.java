package smooth.lift.compat;

/**
 * 1.19+ 才有 {@code Component.literal(...)}；1.18.2 只有 {@code new TextComponent(...)}。
 *
 * <p>跨版本界面的源码用「{@code Component.literal(...)}」这一种写法（与 1.20.1 那些界面逐字一致，
 * 回归脚本也是按这个写法断言的），所以这里垫一个同名静态方法，把 1.18.2 的差异收进 compat。
 */
public final class Component {

    private Component() {
    }

    /** 与 1.19+ {@code Component.literal(String)} 同语义：不可翻译的纯文本组件。 */
    public static net.minecraft.network.chat.Component literal(String text) {
        return new net.minecraft.network.chat.TextComponent(text);
    }
}