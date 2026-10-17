package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.TrainTiltView;

/**
 * 【1.31.1204】把 <b>MTR 4</b> 列车自己的俯仰角（{@code PositionAndRotation.pitch}）喂给
 * {@link TrainTiltView}（{@code /mtrqx on|off}）。
 *
 * <h2>为什么挂在 {@code transformForwards} 与 {@code transformBackwards} 上</h2>
 * {@code PositionAndRotation} 是「车身局部坐标 ↔ 世界坐标」的唯一入口，两个方向各一个方法，
 * 对 4.0.5 的 {@code org/mtr/mod/render/PositionAndRotation.class} 逐条 javap 核过：
 * <pre>
 *   transformForwards (T, Rotate, Rotate, Translate)
 *       Rotate.apply(rotate 1, (float) pitch)     &lt;== this.pitch 就是这个
 *       Rotate.apply(rotate 2, (float) yaw)
 *       Translate.apply(..., position.x, position.y, position.z)
 *   transformBackwards(T, Rotate, Rotate, Translate)
 *       基本上是把上面那三步**倒着**做一遍（yaw、pitch 各取负），this.pitch 仍然是同一个字段
 * </pre>
 * 也就是说：<b>两个方法里的 {@code this.pitch} 是同一个值</b>，任取其一都拿得到「这辆车的俯仰角」。
 * 之所以两个都要挂，是因为「玩家被列车带着走」的两条链路各用一个：
 * <pre>
 *   VehicleRidingMovement.movePlayer(…, PositionAndRotation) 反汇编：
 *     正常骑乘那条      : 1025 rotate.transformForwards(...)  →  1048 movePlayer(DDD)
 *     站台间隙（isOnGangway）那条 : 561 rotate.transformBackwards(...) → 576 movePlayer(DDD)
 * </pre>
 * 只挂一个的话，站在车厢连接处（车门之间那块）就读不到值、视角不会跟着坡走。
 *
 * <h2>★★★ 为什么处理器收的是 {@link CallbackInfoReturnable} 而不是 {@code CallbackInfo}</h2>
 * 这是本轮真踩过的坑，日志原话：
 * <pre>
 *   Mixin apply failed … :Mtr4RideTiltPositionMixin … Invalid descriptor on
 *   …@Inject::smoothlift$noteForwards(L…/callback/CallbackInfo;)V!
 *   CallbackInfoReturnable is required!
 * </pre>
 * 规则：<b>{@code @Inject} 处理器的回调参数必须与目标方法的「返回值形态」一致</b>
 * —— 目标方法<b>有返回值</b>（这里是泛型 {@code <T> T}，擦除后描述符返回 {@code Object}），
 * 就必须用 {@link CallbackInfoReturnable}（泛型实参写擦除后的 {@code Object}）；
 * 目标方法 <b>{@code void}</b> 才用 {@code CallbackInfo}。写错时 Mixin 直接判
 * {@code InvalidInjectionException}，而 {@code require = 0} 让整条 mixin 被<b>静默拒收</b>
 * （不崩、也不生效）—— 症状就是「功能完全没反应、日志里一行有用的都没有」。
 *
 * <h2>为什么参数可以只留回调（不逐位写全）</h2>
 * 目标方法带泛型与 MTR 自己的内嵌接口（{@code PositionAndRotation$Rotate} /
 * {@code $Translate}），编译期根本写不出那些参数类型；Mixin 0.15.5 允许「只留回调参数」
 * 这一条通路（目标方法的入参可省略），故这里只收 {@link CallbackInfoReturnable}。
 *
 * <p><b>★ 本 mixin 不在 {@code require = 0} 下沉寂</b>：一旦注入被拒，{@code PsdDoorMixinPlugin.postApply}
 * 不会打印「mixin 已注入」的 beacon，{@link TrainTiltView} 也会在骑乘心跳里报
 * 「骑乘链路在跑，但列车俯仰角从未采到」—— 这两条就是本坑的探针。
 *
 * <h2>为什么不需要 refmap 操心</h2>
 * 类名走 {@code targets} 字符串、字段名走 {@code @Shadow}：MTR **不参与原版混淆**
 * （{@code org.mtr.*} 是模组自己的包，且它在 {@code org.mtr.mod.render} 这个带 mapping 层的包里
 * 名字两端一致），所以这里写什么运行期就是什么。方法名 / 字段名都取不到原版混淆表，
 * 因此不写 remap（与工程里 {@code Mtr4PsdDoorMixin}、{@code Mtr4PidsNameMixin} 同一套做法）。
 *
 * <h2>★ 铁律：注入体里只许出现「原语 + {@link TrainTiltView}」</h2>
 * 不许出现任何 MTR 类型（编译期没有 MTR 依赖）—— 这里读的是 {@code @Shadow} 出来的两个 double，
 * 全部按原语传递，取值与判断都交给 {@link TrainTiltView}。
 * {@code @Pseudo} + {@code PsdDoorMixinPlugin} 的门禁保证「没装 MTR4 / MTR4 换了包名」时
 * <b>安静跳过</b>，绝不因为「目标类不存在」把游戏带崩。
 *
 * <p>★ 顺带一条同名规矩（与 {@code Mtr4TrainDepartHoldMixin} 完全一致）：<b>本 mixin 的辅助类
 * 绝不能放在 mixin 包里</b> —— {@link TrainTiltView} 因此住在 {@code smooth.lift.client}。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.render.PositionAndRotation")
public abstract class Mtr4RideTiltPositionMixin {

    /** MTR 自己的偏航角（public final double，声明在 {@code PositionAndRotation} 上）。 */
    @Shadow
    @Final
    private double yaw;

    /** MTR 自己的俯仰角（public final double）—— 本功能要的就是它。 */
    @Shadow
    @Final
    private double pitch;

    /**
     * 车身局部 → 世界（正常骑乘那条链路会走到这里，紧跟着就是 {@code movePlayer(DDD)}）。
     *
     * <p>★ 目标方法签名是泛型 {@code <T> T transformForwards(T, Rotate, Rotate, Translate)}
     * —— <b>有返回值</b>，所以回调参数必须是 {@link CallbackInfoReturnable}（实参 = 擦除后的
     * {@code Object}），写 {@code CallbackInfo} 会被 Mixin 判 {@code InvalidInjectionException}。
     * 目标方法的入参省略不写（泛型 + MTR 内嵌接口，编译期写不出来）。
     */
    @Inject(method = "transformForwards", at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$noteForwards(CallbackInfoReturnable<Object> cir) {
        TrainTiltView.noteTransform(this.pitch, this.yaw);
    }

    /** 世界 → 车身局部（站在车厢连接处那条链路会走到这里，同样紧跟着 {@code movePlayer(DDD)}）。 */
    @Inject(method = "transformBackwards", at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$noteBackwards(CallbackInfoReturnable<Object> cir) {
        TrainTiltView.noteTransform(this.pitch, this.yaw);
    }
}
