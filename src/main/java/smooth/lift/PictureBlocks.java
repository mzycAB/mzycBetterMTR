package smooth.lift;

import java.util.function.Supplier;

import smooth.lift.compat.FabricItemGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

/**
 * 【1.18.1204】地图图片方块：从 {@code shsubwaypicture}（map 图片模组）复刻。
 *
 * <p>12 个方块：4 个三维贴图方块（pic_tl/tr/bl/br，正面贴图、背面侧面白色）、
 * 4 个 1px 薄片方块（flat_tl/tr/bl/br）、4 个四角薄片方块（flat_*_small）。
 * 纹理在运行时通过 {@code /MBM picture} 更新（见 PictureManager 与客户端 PictureTextures）。
 *
 * <p>【Forge 1.18.2】Forge 在模组构造之前就冻结了原版注册表（{@code Registry.BLOCK}），
 * 所以 Fabric 式的 {@code Registry.register} 以及直接 {@code new Block(...)} 都会抛
 * {@code IllegalStateException: Registry is already frozen}。这里改用 Forge 的
 * {@link DeferredRegister}，把方块的构造与注册推迟到 {@code RegistryEvent} 窗口内完成。
 */
public final class PictureBlocks {

    /** 与资源目录 {@code assets/shsubwaypicture} 一致的命名空间。 */
    public static final String MOD_ID = "shsubwaypicture";

    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Block> PIC_TL = block("pic_tl", () -> new PictureBlock(props()));
    public static final RegistryObject<Block> PIC_TR = block("pic_tr", () -> new PictureBlock(props()));
    public static final RegistryObject<Block> PIC_BL = block("pic_bl", () -> new PictureBlock(props()));
    public static final RegistryObject<Block> PIC_BR = block("pic_br", () -> new PictureBlock(props()));

    public static final RegistryObject<Block> FLAT_TL = block("flat_tl", () -> new FlatPictureBlock(props()));
    public static final RegistryObject<Block> FLAT_TR = block("flat_tr", () -> new FlatPictureBlock(props()));
    public static final RegistryObject<Block> FLAT_BL = block("flat_bl", () -> new FlatPictureBlock(props()));
    public static final RegistryObject<Block> FLAT_BR = block("flat_br", () -> new FlatPictureBlock(props()));

    public static final RegistryObject<Block> FLAT_TL_SMALL = block("flat_tl_small", () -> new SmallFlatPictureBlock(props(), 0));
    public static final RegistryObject<Block> FLAT_TR_SMALL = block("flat_tr_small", () -> new SmallFlatPictureBlock(props(), 1));
    public static final RegistryObject<Block> FLAT_BL_SMALL = block("flat_bl_small", () -> new SmallFlatPictureBlock(props(), 2));
    public static final RegistryObject<Block> FLAT_BR_SMALL = block("flat_br_small", () -> new SmallFlatPictureBlock(props(), 3));

    public static final CreativeModeTab ITEM_GROUP = FabricItemGroup.builder()
            .title(new net.minecraft.network.chat.TranslatableComponent("itemGroup.shsubwaypicture.main"))
            .icon(() -> new ItemStack(PIC_TL.get()))
            .displayItems((displayContext, entries) -> {
                entries.accept(new ItemStack(PIC_TL.get()));
                entries.accept(new ItemStack(PIC_TR.get()));
                entries.accept(new ItemStack(PIC_BL.get()));
                entries.accept(new ItemStack(PIC_BR.get()));
                entries.accept(new ItemStack(FLAT_TL.get()));
                entries.accept(new ItemStack(FLAT_TR.get()));
                entries.accept(new ItemStack(FLAT_BL.get()));
                entries.accept(new ItemStack(FLAT_BR.get()));
                entries.accept(new ItemStack(FLAT_TL_SMALL.get()));
                entries.accept(new ItemStack(FLAT_TR_SMALL.get()));
                entries.accept(new ItemStack(FLAT_BL_SMALL.get()));
                entries.accept(new ItemStack(FLAT_BR_SMALL.get()));
            })
            .build();

    private PictureBlocks() {
    }

    /** 注册一个方块，并同时注册对应的 {@link BlockItem}。 */
    private static RegistryObject<Block> block(String name, Supplier<Block> supplier) {
        RegistryObject<Block> block = BLOCKS.register(name, supplier);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    /**
     * 把延迟注册器挂到 Forge 的 mod 事件总线上（在 {@code SmoothLiftForge} 构造期调用）。
     *
     * <p>方块的构造与注册都会推迟到 {@code RegistryEvent.Register<Block>} 触发时执行，
     * 那时 Forge 已临时解冻注册表，不会再抛「Registry is already frozen」。
     */
    public static void init(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of(net.minecraft.world.level.material.Material.STONE)
                .strength(1.5f).requiresCorrectToolForDrops();
    }

    /** 三维贴图方块（对应 mod-template 的 ExampleBlock，正面朝向放置玩家的反向）。 */
    public static class PictureBlock extends Block {
        public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

        public PictureBlock(BlockBehaviour.Properties properties) {
            super(properties);
            this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Nullable
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        public BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        public BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }
    }

    /** 1px 薄片方块（贴墙挂画，碰撞箱靠 FACING 侧）。 */
    public static class FlatPictureBlock extends Block {
        public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

        private static final VoxelShape NORTH_SHAPE = Block.box(0, 0, 0, 16, 16, 1);
        private static final VoxelShape SOUTH_SHAPE = Block.box(0, 0, 15, 16, 16, 16);
        private static final VoxelShape EAST_SHAPE = Block.box(15, 0, 0, 16, 16, 16);
        private static final VoxelShape WEST_SHAPE = Block.box(0, 0, 0, 1, 16, 16);

        public FlatPictureBlock(BlockBehaviour.Properties properties) {
            super(properties);
            this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Nullable
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        public BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        public BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return switch (state.getValue(FACING)) {
                case SOUTH -> SOUTH_SHAPE;
                case EAST -> EAST_SHAPE;
                case WEST -> WEST_SHAPE;
                default -> NORTH_SHAPE;
            };
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return getShape(state, level, pos, context);
        }
    }

    /** 四角薄片方块（corner 0..3 对应 tl/tr/bl/br 的原图裁剪角）。 */
    public static class SmallFlatPictureBlock extends FlatPictureBlock {
        private final VoxelShape northShape, southShape, eastShape, westShape;

        public SmallFlatPictureBlock(BlockBehaviour.Properties properties, int corner) {
            super(properties);
            double s = 10.6667d;
            double g = 16.0d - s;
            double y1, y2;
            if (corner == 0 || corner == 1) {
                y1 = g;
                y2 = 16.0d;
            } else {
                y1 = s;
                y2 = 21.3333d;
            }

            // 模型 Y 旋转：y=90 (x,z)->(16-z,x)，y=180 ->(16-x,16-z)，y=270 ->(z,16-x)
            if (corner == 0 || corner == 2) {
                // 1/3 号：原始 x=g..16, z=0..1
                this.northShape = Block.box(g, y1, 0, 16.0, y2, 1);
                this.southShape = Block.box(0, y1, 15, s, y2, 16);
                this.eastShape = Block.box(15, y1, g, 16.0, y2, 16);
                this.westShape = Block.box(0, y1, 0, 1, y2, s);
            } else {
                // 2/4 号：原始 x=0..s, z=0..1
                this.northShape = Block.box(0, y1, 0, s, y2, 1);
                this.southShape = Block.box(g, y1, 15, 16.0, y2, 16);
                this.eastShape = Block.box(15, y1, 0, 16.0, y2, s);
                this.westShape = Block.box(0, y1, g, 1, y2, 16);
            }
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return switch (state.getValue(FACING)) {
                case SOUTH -> southShape;
                case EAST -> eastShape;
                case WEST -> westShape;
                default -> northShape;
            };
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return getShape(state, level, pos, context);
        }
    }
}