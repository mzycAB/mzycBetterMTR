package smooth.lift;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
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
import org.jetbrains.annotations.Nullable;

/**
 * 【1.18.1204】地图图片方块：从 {@code shsubwaypicture}（map 图片模组）复刻。
 *
 * <p>12 个方块：4 个三维贴图方块（pic_tl/tr/bl/br，正面贴图、背面侧面白色）、
 * 4 个 1px 薄片方块（flat_tl/tr/bl/br）、4 个四角薄片方块（flat_*_small）。
 * 纹理在运行时通过 {@code /MBM picture} 更新（见 PictureManager 与客户端 PictureTextures）。
 */
public final class PictureBlocks {

    /** 与资源目录 {@code assets/shsubwaypicture} 一致的命名空间。 */
    public static final String MOD_ID = "shsubwaypicture";

    public static final Block PIC_TL = new PictureBlock(props());
    public static final Block PIC_TR = new PictureBlock(props());
    public static final Block PIC_BL = new PictureBlock(props());
    public static final Block PIC_BR = new PictureBlock(props());

    public static final Block FLAT_TL = new FlatPictureBlock(props());
    public static final Block FLAT_TR = new FlatPictureBlock(props());
    public static final Block FLAT_BL = new FlatPictureBlock(props());
    public static final Block FLAT_BR = new FlatPictureBlock(props());

    public static final Block FLAT_TL_SMALL = new SmallFlatPictureBlock(props(), 0);
    public static final Block FLAT_TR_SMALL = new SmallFlatPictureBlock(props(), 1);
    public static final Block FLAT_BL_SMALL = new SmallFlatPictureBlock(props(), 2);
    public static final Block FLAT_BR_SMALL = new SmallFlatPictureBlock(props(), 3);

    public static final CreativeModeTab ITEM_GROUP = FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.shsubwaypicture.main"))
            .icon(() -> new ItemStack(PIC_TL))
            .displayItems((displayContext, entries) -> {
                entries.accept(PIC_TL);
                entries.accept(PIC_TR);
                entries.accept(PIC_BL);
                entries.accept(PIC_BR);
                entries.accept(FLAT_TL);
                entries.accept(FLAT_TR);
                entries.accept(FLAT_BL);
                entries.accept(FLAT_BR);
                entries.accept(FLAT_TL_SMALL);
                entries.accept(FLAT_TR_SMALL);
                entries.accept(FLAT_BL_SMALL);
                entries.accept(FLAT_BR_SMALL);
            })
            .build();

    private PictureBlocks() {
    }

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().strength(1.5f).requiresCorrectToolForDrops();
    }

    /** 方块 + 物品 + 物品组注册（在 {@link SmoothLift#onInitialize} 里调用）。 */
    public static void register() {
        reg("pic_tl", PIC_TL);
        reg("pic_tr", PIC_TR);
        reg("pic_bl", PIC_BL);
        reg("pic_br", PIC_BR);
        reg("flat_tl", FLAT_TL);
        reg("flat_tr", FLAT_TR);
        reg("flat_bl", FLAT_BL);
        reg("flat_br", FLAT_BR);
        reg("flat_tl_small", FLAT_TL_SMALL);
        reg("flat_tr_small", FLAT_TR_SMALL);
        reg("flat_bl_small", FLAT_BL_SMALL);
        reg("flat_br_small", FLAT_BR_SMALL);

        // 防御性守卫：若旧版 SHSubwayPicture 等模组已注册同名物品组 ID，则跳过，避免 ID 冲突崩溃。
        if (!BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(id("main"))) {
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("main"), ITEM_GROUP);
        }
    }

    /**
     * 防御性注册：方块/物品 ID 已被其他模组占用时跳过（避免
     * {@code Attempted to register ID ... at different raw IDs} 崩溃）。
     */
    private static void reg(String name, Block block) {
        ResourceLocation id = id(name);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            Registry.register(BuiltInRegistries.BLOCK, id, block);
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()));
        }
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
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