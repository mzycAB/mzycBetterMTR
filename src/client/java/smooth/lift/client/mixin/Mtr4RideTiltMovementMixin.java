package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.TrainTiltView;

/**
 * 【1.31.1204】在 <b>MTR 4</b> 的骑乘链路上认「玩家真的被列车带着走」这一帧，并把
 * {@link Mtr4RideTiltPositionMixin} 刚记下的列车俯仰角<b>立刻快照</b>下来
 * （{@code /mtrqx on|off} = 玩家视角随列车倾斜）。
 *
 * <h2>挂哪三处（对 4.0.5 的 {@code org/mtr/mod/client/VehicleRidingMovement.class} 逐条 javap 核过）</h2>
 * <ul>
 *   <li>{@code movePlayer(DDD)V} 的 HEAD —— 真正的「把玩家搬到新坐标」那一步。
 *       它是 {@code private static void}，反汇编里<b>只有两处调用点</b>，都在公开的
 *       {@code movePlayer(long,long,int,…,PositionAndRotation)} 里，且都<b>紧跟在一条 transform 之后</b>：
 *       <pre>
 *         1025 transformForwards(...)  →  1048 movePlayer(DDD)      （正常骑乘）
 *          561 transformBackwards(...) →  576 movePlayer(DDD)      （车厢连接处）
 *       </pre>
 *       ⇒ 在 HEAD 上取值，拿到的必然是「这辆车此刻的俯仰角」，不会被同一帧后面渲染别的车覆盖。</li>
 *   <li>{@code sendUpdate(Z)V} 的 HEAD，只为读那个 boolean —— MTR 自己在 3 处解除骑乘时都调
 *       {@code sendUpdate(true)}（tick 里松开 Shift 下车、以及 movePlayer 里两条
 *       「人走丢了 / 位置算不出来」的收尾），全部是「不再骑乘」。
 *       ⇒ 在这里通知 {@link TrainTiltView#onRideEnd()}，让渲染侧立刻停止倾斜/横滚
 *       （本模组不写玩家视角，所以这里<b>不需要</b>「还回去」，只是认下车）。
 *       <b>只认 true</b>：{@code sendUpdate(false)} 是「正在骑、顺手通报一下」，与下车无关。</li>
 * </ul>
 *
 * <h2>为什么处理器都是 static（且只收 CallbackInfo）</h2>
 * ★ 组合铁律（工程里踩过）：<b>{@code @Inject} 处理器的 {@code static} 必须与目标方法一致</b>
 * —— 目标 {@code movePlayer(DDD)V} / {@code sendUpdate(Z)V} 都是 {@code static}，所以处理器也必须
 * {@code static}；不一致时 Mixin 会拒收注入，而 {@code require = 0} 又让它<b>完全静默</b>
 * （不崩、也不生效），极难定位。
 *
 * <p>{@code sendUpdate(Z)V} 的处理器写了 {@code (boolean dismount, CallbackInfo ci)}：参数是原语，
 * 属于「全参数逐位匹配」那条通路，合法。{@code movePlayer(DDD)V} 那边本模组不需要坐标，
 * 所以只收 {@code CallbackInfo}（Mixin 允许参数省略）。
 *
 * <h2>★ 铁律：注入体里只许出现「原语 + {@link TrainTiltView}」</h2>
 * 不许出现任何 MTR 类型（编译期没有 MTR 依赖）。{@code @Pseudo} + {@code PsdDoorMixinPlugin}
 * 的门禁保证没装 MTR4 时安静跳过。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.client.VehicleRidingMovement")
public abstract class Mtr4RideTiltMovementMixin {

    /**
     * 「玩家确实被列车带着走」了这一帧 —— 让 {@link TrainTiltView} 把刚记下的俯仰角快照下来。
     *
     * <p>目标方法名 {@code movePlayer} 在 {@code VehicleRidingMovement} 里有<b>两个重载</b>
     * （{@code (DDD)V} 与 {@code (JJIL…;…)V}），所以这里必须写全描述符，不能只写名字。
     */
    @Inject(method = "movePlayer(DDD)V", at = @At("HEAD"), require = 0, remap = false)
    private static void smoothlift$noteRideMove(CallbackInfo ci) {
        TrainTiltView.noteRideMove();
    }

    /**
     * MTR 自己解除骑乘（{@code sendUpdate(true)}）—— 告诉渲染侧「已经不在车上了」。
     *
     * @param dismount MTR 的入参：true = 不再骑乘；false = 「正在骑」时的顺带通报，与下车无关
     */
    @Inject(method = "sendUpdate(Z)V", at = @At("HEAD"), require = 0, remap = false)
    private static void smoothlift$noteRideEnd(boolean dismount, CallbackInfo ci) {
        if (dismount) {
            TrainTiltView.onRideEnd();
        }
    }
}
