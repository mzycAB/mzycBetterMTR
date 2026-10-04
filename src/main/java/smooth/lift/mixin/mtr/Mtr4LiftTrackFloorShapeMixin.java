package smooth.lift.mixin.mtr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 【1.44】把 MTR **4** 的「电梯楼层轨道」的**碰撞箱 / 选中框**收窄成 MTR3 那根 4px 窄柱。
 *
 * <h2>为什么必须改（用户报的 bug）</h2>
 *
 * 用户原话：「mtr3 样式的楼层轨道本身不透明，但是由于这个轨道只占了完整方块的一小部分，
 * 导致没有贴图的那部分方块碰撞箱透明了」。
 *
 * <p>我们只换掉了**模型**（{@code assets/mtr/models/block/lift_track_floor_1.json}
 * = MTR3 的 4px 窄柱几何），但**形状**是代码定义的、换不掉：
 *
 * <pre>
 *   MTR3  mtr.block.BlockLiftTrack.method_9530（= 原版 getShape / 选中框）
 *           = IBlock.getVoxelShapeByDirection( 6, 0, 0,  10, 16, 1, facing)   ← 就是那根柱子
 *   MTR4  org.mtr.mod.block.BlockLiftTrackFloor.getOutlineShape2
 *           = IBlock.getVoxelShapeByDirection( 0, 0, 0,  16, 16, 1, facing)   ← 全宽薄板
 * </pre>
 *
 * 而原版 {@code BlockBehaviour.getCollisionShape} 的默认实现就是
 * {@code state.getShape(level, pos)} —— **碰撞箱跟随选中框**。于是 MTR4 里就留下了
 * 一面「16px 宽 × 1px 厚，但只有中间 4px 有贴图」的**隐形墙**：玩家看着缝隙能过去，
 * 走过去却被空气挡住。这就是「没有贴图的那部分方块碰撞箱透明了」。
 *
 * <p>★ 顺带说明为什么 MTR3 上没这个问题：MTR3 的形状本来就是那根柱子，
 * 所以「看得见的」与「挡得住的」一致。本 mixin 就是把这个一致性补回 MTR4。
 *
 * <h2>怎么改</h2>
 *
 * <p>目标方法只有一条 {@code invokestatic IBlock.getVoxelShapeByDirection(DDDDDD…)}，
 * 六个 double 依次是 {@code minX, minY, minZ, maxX, maxY, maxZ}（单位是方块，0..16）。
 * 只需改其中两个：<b>minX 由 0 改成 6</b>、<b>maxX 由 16 改成 10</b>；
 * 其余（minY=0 / minZ=0 / maxY=16 / maxZ=1）原样保留。改完就是
 * {@code (6,0,0, 10,16,1)} —— 与 MTR3 字节码里的那六个常量**逐个相同**。
 *
 * <p><b>为什么用 {@link ModifyArg}+{@code index} 而不是 {@code @ModifyConstant}</b>：
 * <ul>
 *   <li>{@code @ModifyConstant} 只能按「常量的第几次出现」定位，而这里 {@code 0.0} 出现 3 次、
 *       {@code 16.0} 出现 2 次，MTR 只要调整一下指令顺序就会**悄悄**改错另一个参数
 *       （症状是形状变得莫名其妙，且不报错）；</li>
 *   <li>{@code index} 说的是「这个调用的第几个实参」，语义稳定，与常量出现次序无关；</li>
 *   <li>另外，{@code @ModifyConstant} 是别的模组（如 liftbatch）也在 MTR 上用的工具，
 *       能避开就避开。</li>
 * </ul>
 *
 * <h2>★ {@code @Pseudo} 与「类型不认识」这件麻烦事</h2>
 *
 * <p>本模组**不把 MTR 当编译依赖**，所以 {@code org.mtr.mod.block.BlockLiftTrackFloor}
 * 不在编译类路径上 —— {@code @Pseudo} 既让注解处理器放行（否则报
 * {@code Mixin target … could not be found} 直接编译失败），也让运行期在没装 MTR4 时**安全跳过**。
 *
 * <p>而 MTR4 这个方法签名里全是 **MTR 自己的类型**（{@code org.mtr.mapping.holder.BlockState}
 * / {@code VoxelShape} / {@code Direction}），不是 MC 类型，**没法靠 refmap 用原版名字写出来**，
 * 所以不能用 {@code @Inject}(handler 必须复刻目标签名) 那一套。
 * {@link ModifyArg} 的 handler 只跟**一个 double**打交道 —— 用原语类型就够了，
 * 全程不需要在编译期认识任何 MTR 类型。这也是本 mixin 能用 {@code @Pseudo} 写出来的原因。
 *
 * <p><b>门禁</b>：{@link Mtr3LiftMixinPlugin#shouldApplyMixin} 只在
 * {@code org.mtr.mod.block.BlockLiftTrackFloor} 这个类**存在**时才应用本 mixin
 * （即装了 MTR4），没装 MTR / 装 MTR3 时直接跳过。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.block.BlockLiftTrackFloor")
public abstract class Mtr4LiftTrackFloorShapeMixin {

    /**
     * 目标调用的完整描述符（与 MTR4 4.0.5 的字节码逐字一致）。
     *
     * <p>它是 {@code IBlock} 这个**接口上的静态方法**（{@code invokestatic}）。
     * 写成常量是为了让 {@code _tools/check-lift-track-look.py} 能同时校验
     * 「本文件里的字符串」与「真实 jar 里的那一行」是同一串 —— 改错一个字母就查得出来。
     */
    private static final String SHAPE_CALL =
            "Lorg/mtr/mod/block/IBlock;getVoxelShapeByDirection(DDDDDDLorg/mtr/mapping/holder/Direction;)"
                    + "Lorg/mtr/mapping/holder/VoxelShape;";

    /** 把 minX（第 0 个实参）由 0 改成 6 —— MTR3 窄柱的左边界。 */
    @ModifyArg(
            method = "getOutlineShape2",
            at = @At(value = "INVOKE", target = SHAPE_CALL),
            index = 0,
            remap = false)
    private double smoothlift$narrowMinX(double original) {
        return 6.0;
    }

    /** 把 maxX（第 3 个实参）由 16 改成 10 —— MTR3 窄柱的右边界。 */
    @ModifyArg(
            method = "getOutlineShape2",
            at = @At(value = "INVOKE", target = SHAPE_CALL),
            index = 3,
            remap = false)
    private double smoothlift$narrowMaxX(double original) {
        return 10.0;
    }
}
