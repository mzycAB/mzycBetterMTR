package smooth.lift.compat;

import net.minecraft.core.Registry;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 垫片：{@code BuiltInRegistries} 是 1.19.2 才引入的类。
 *
 * <p>1.18.2 里这些静态注册表都直接挂在 {@link Registry} 上，这里原样转发，
 * 让 1.20.4 的源码可以照抄不改。
 */
public final class BuiltInRegistries {

    public static final Registry<Block> BLOCK = Registry.BLOCK;
    public static final Registry<Item> ITEM = Registry.ITEM;
    public static final Registry<SoundEvent> SOUND_EVENT = Registry.SOUND_EVENT;
    public static final Registry<BlockEntityType<?>> BLOCK_ENTITY_TYPE = Registry.BLOCK_ENTITY_TYPE;

    private BuiltInRegistries() {
    }
}