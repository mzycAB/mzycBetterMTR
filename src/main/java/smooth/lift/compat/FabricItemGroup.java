package smooth.lift.compat;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Forge 侧垫片：Fabric 的 {@code FabricItemGroup.builder()}。
 *
 * <p>1.18.2 没有 {@code Registry.CREATIVE_MODE_TAB}（那个注册表 1.19.3 才有），
 * 创造模式物品栏是一个静态数组 {@code CreativeModeTab.TABS}。所以这里 build 出来的
 * 标签页由 {@link #register(CreativeModeTab)} 追加进 TABS。
 */
public final class FabricItemGroup {

    private FabricItemGroup() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 把自建标签页追加到 {@code CreativeModeTab.TABS}（客户端 setup 时调用）。 */
    public static synchronized void register(CreativeModeTab tab) {
        CreativeModeTab[] old = CreativeModeTab.TABS;
        for (CreativeModeTab existing : old) {
            if (existing == tab) {
                return;
            }
        }
        CreativeModeTab[] grown = Arrays.copyOf(old, old.length + 1);
        grown[old.length] = tab;
        CreativeModeTab.TABS = grown;
    }

    public static final class Builder {

        private Component title = net.minecraft.network.chat.TextComponent.EMPTY;
        private Supplier<ItemStack> icon = () -> ItemStack.EMPTY;
        private BiConsumer<Object, Consumer<ItemStack>> displayItems = (ctx, out) -> {
        };

        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        public Builder icon(Supplier<ItemStack> icon) {
            this.icon = icon;
            return this;
        }

        public Builder displayItems(BiConsumer<Object, Consumer<ItemStack>> generator) {
            this.displayItems = generator;
            return this;
        }

        public CreativeModeTab build() {
            return new SimpleTab(title, icon, displayItems);
        }
    }

    private static final class SimpleTab extends CreativeModeTab {

        private final Component title;
        private final Supplier<ItemStack> icon;
        private final BiConsumer<Object, Consumer<ItemStack>> generator;

        private SimpleTab(Component title,
                          Supplier<ItemStack> icon,
                          BiConsumer<Object, Consumer<ItemStack>> generator) {
            super(CreativeModeTab.TABS.length, title.getString());
            this.title = title;
            this.icon = icon;
            this.generator = generator;
        }

        @Override
        public Component getDisplayName() {
            return title;
        }

        @Override
        public ItemStack makeIcon() {
            return icon.get();
        }

        @Override
        public void fillItemList(NonNullList<ItemStack> items) {
            generator.accept(null, items::add);
        }
    }
}