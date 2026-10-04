package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
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
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.RegisterEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 【1.18.1204】地图图片方块：从 {@code shsubwaypicture}（map 图片模组）复刻。
 *
 * <p>12 个方块：4 个三维贴图方块（pic_tl/tr/bl/br，正面贴图、背面侧面白色）、
 * 4 个 1px 薄片方块（flat_tl/tr/bl/br）、4 个四角薄片方块（flat_*_small）。
 * 纹理在运行时通过 {@code /MBM picture} 更新（见 PictureManager 与客户端 PictureTextures）。
 *
 * <p><b>Forge 移植注</b>：Fabric 版在 {@code onInitialize} 里直接 {@code Registry.register}；
 * Forge 的注册时机是 {@link RegisterEvent}（模组总线）—— 注册逻辑挂到 mod event bus 上，
 * 在方块 / 物品 / 创造模式物品组三个注册段各做一次「已占用就跳过」的防御式注册。
 */
public final class PictureBlocks {

    /** 与资源目录 {@code assets/shsubwaypicture} 一致的命名空间。 */
    public static final String MOD_ID = "shsubwaypicture";

    // ★★★ Forge 致命坑（本行注释就是那次崩溃的根因）：这些方块**绝对不能**写成
    //     「静态字段 = new XXXBlock(...)」。
    //   1.20.1 的 `Block` 构造器里有一句
    //      `this.builtInRegistryHolder = BuiltInRegistries.BLOCK.createIntrusiveHolder(this);`
    //    而 Forge 的 `NamespacedWrapper.m_203693_`（createIntrusiveHolder）第一件事就是
    //      `validateWrite()` —— 注册表**已冻结**时直接抛
    //      `IllegalStateException: Registry is already frozen`。
    //    Forge 只在「派发某个注册表的 `RegisterEvent`」那一小段对该注册表临时 `unfreeze()`
    //    （见 `GameData.postRegisterEvents`：unfreeze → postEvent → freeze），
    //    而**类静态初始化发生在模组构造期**（`SmoothLift()` 构造器里调 `register(...)` 会触发
    //    `PictureBlocks.<clinit>`）—— 那时 vanilla 的 BLOCK 注册表早已冻结
    //    ⇒ 必崩。正解：把 12 个方块的 new **推迟到 `onRegister` 的 BLOCK 段里现造现注册**。
    //   （Fabric 版没有这个约束，所以那边可以写成静态字段；这是 Forge 独有的注册时机差异。）
    public static Block PIC_TL;
    public static Block PIC_TR;
    public static Block PIC_BL;
    public static Block PIC_BR;

    public static Block FLAT_TL;
    public static Block FLAT_TR;
    public static Block FLAT_BL;
    public static Block FLAT_BR;

    public static Block FLAT_TL_SMALL;
    public static Block FLAT_TR_SMALL;
    public static Block FLAT_BL_SMALL;
    public static Block FLAT_BR_SMALL;

    /**
     * 现造物品组（在 CREATIVE_MODE_TAB 段里调用）。
     *
     * <p>★ 也不能做成静态字段：`CreativeModeTab.Builder.build()` 本身不碰注册表，
     * 但静态字段的初始化会连带把上面那批方块的静态初始化一起触发 —— 见上。
     * `icon` / `displayItems` 两个回调都是**惰性**的（真正建栏时才跑），那时方块早已就位。
     */
    private static CreativeModeTab itemGroup() {
        return CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.shsubwaypicture.main"))
                .icon(() -> new ItemStack(PIC_TL))
                .displayItems((displayContext, entries) -> {
                    for (Block block : new Block[]{PIC_TL, PIC_TR, PIC_BL, PIC_BR,
                            FLAT_TL, FLAT_TR, FLAT_BL, FLAT_BR,
                            FLAT_TL_SMALL, FLAT_TR_SMALL, FLAT_BL_SMALL, FLAT_BR_SMALL}) {
                        entries.accept(block);
                    }
                })
                .build();
    }

    private PictureBlocks() {
    }

    /** 把注册逻辑挂到模组事件总线（在 {@link SmoothLift#SmoothLift()} 构造器里调用）。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(PictureBlocks::onRegister);
    }

    /** RegisterEvent：方块 + 物品 + 物品组注册（各注册段防御式：ID 已被占用就跳过）。 */
    private static void onRegister(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            // ★ 现造现注册 —— 只有在这一段里 BLOCK 注册表才是 unfreeze 状态（见类顶那段注释）。
            PIC_TL = new PictureBlock(props());
            PIC_TR = new PictureBlock(props());
            PIC_BL = new PictureBlock(props());
            PIC_BR = new PictureBlock(props());
            FLAT_TL = new FlatPictureBlock(props());
            FLAT_TR = new FlatPictureBlock(props());
            FLAT_BL = new FlatPictureBlock(props());
            FLAT_BR = new FlatPictureBlock(props());
            FLAT_TL_SMALL = new SmallFlatPictureBlock(props(), 0);
            FLAT_TR_SMALL = new SmallFlatPictureBlock(props(), 1);
            FLAT_BL_SMALL = new SmallFlatPictureBlock(props(), 2);
            FLAT_BR_SMALL = new SmallFlatPictureBlock(props(), 3);
            regBlock(helper, "pic_tl", PIC_TL);
            regBlock(helper, "pic_tr", PIC_TR);
            regBlock(helper, "pic_bl", PIC_BL);
            regBlock(helper, "pic_br", PIC_BR);
            regBlock(helper, "flat_tl", FLAT_TL);
            regBlock(helper, "flat_tr", FLAT_TR);
            regBlock(helper, "flat_bl", FLAT_BL);
            regBlock(helper, "flat_br", FLAT_BR);
            regBlock(helper, "flat_tl_small", FLAT_TL_SMALL);
            regBlock(helper, "flat_tr_small", FLAT_TR_SMALL);
            regBlock(helper, "flat_bl_small", FLAT_BL_SMALL);
            regBlock(helper, "flat_br_small", FLAT_BR_SMALL);
        });
        event.register(Registries.ITEM, helper -> {
            regItem(helper, "pic_tl", PIC_TL);
            regItem(helper, "pic_tr", PIC_TR);
            regItem(helper, "pic_bl", PIC_BL);
            regItem(helper, "pic_br", PIC_BR);
            regItem(helper, "flat_tl", FLAT_TL);
            regItem(helper, "flat_tr", FLAT_TR);
            regItem(helper, "flat_bl", FLAT_BL);
            regItem(helper, "flat_br", FLAT_BR);
            regItem(helper, "flat_tl_small", FLAT_TL_SMALL);
            regItem(helper, "flat_tr_small", FLAT_TR_SMALL);
            regItem(helper, "flat_bl_small", FLAT_BL_SMALL);
            regItem(helper, "flat_br_small", FLAT_BR_SMALL);
        });
        // 防御性守卫：若旧版 SHSubwayPicture 等模组已注册同名物品组 ID，则跳过，避免 ID 冲突崩溃。
        event.register(Registries.CREATIVE_MODE_TAB, helper -> {
            if (!BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(id("main"))) {
                helper.register(id("main"), itemGroup());
            }
        });
    }

    private static void regBlock(RegisterEvent.RegisterHelper<Block> helper, String name, Block block) {
        ResourceLocation rid = id(name);
        if (!BuiltInRegistries.BLOCK.containsKey(rid)) {
            helper.register(rid, block);
        }
    }

    private static void regItem(RegisterEvent.RegisterHelper<Item> helper, String name, Block block) {
        ResourceLocation rid = id(name);
        if (!BuiltInRegistries.ITEM.containsKey(rid)) {
            helper.register(rid, new BlockItem(block, new Item.Properties()));
        }
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().strength(1.5f).requiresCorrectToolForDrops();
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
