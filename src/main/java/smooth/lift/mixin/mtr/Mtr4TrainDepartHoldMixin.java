package smooth.lift.mixin.mtr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.PsdDepartHold;

/**
 * 【10-03 五改】让「**关门后等待 X 秒发车**」真的落到 MTR 的列车上（用户点名「真的改变列车发车」）。
 *
 * <h2>挂哪：{@code org.mtr.core.data.Vehicle.startUp(JJ)V} 的 HEAD</h2>
 * 对 4.0.5 逐条 javap 核过，这一处是**唯一的发车闸门**：
 * <pre>
 *   Vehicle.simulate(...)
 *     doorCooldown = (门目标为开 || 司机覆盖) ? 4200L : max(0, doorCooldown - elapsedTime)
 *     ...
 *     Vehicle.simulateStopped(...)
 *       elapsed &gt;= closeAt 时:
 *         if (railBlockedDistance(...) &lt; 0) {
 *             if (doorCooldown == 0) { railProgress = stopDist; ... }
 *             startUp(departureIndex, sidingDepartureTime);      &lt;== 就是这里
 *         }
 *   Vehicle.startUp(JJ)V:
 *     closeDoors();
 *     if (doorCooldown != 0) return;      &lt;== 发车闸门（倒计时没走完就每 tick 早退）
 *     railProgress += 4e-6; speed = 4e-6; elapsedDwellTime = 0; ...
 * </pre>
 * ⇒ 在 HEAD 上把 {@code doorCooldown} 改成 {@code max(0, doorCooldown - 1000 + X*1000)}，
 * 发车时刻就整体挪 X 秒（{@code X > 0} 等更久、{@code X < 0} 更早，够大时门还没关完就走）。
 * 推导与边界见 {@link PsdDepartHold} 的类注释。
 *
 * <h2>为什么用 {@code @Shadow} 而不是反射写字段</h2>
 * {@code doorCooldown} 是 {@code Vehicle} 自己的 **private long** —— 类型是原语、
 * 字段名两端一致（MTR 不参与原版混淆），{@code @Shadow} 是编译期就核对得住的最短路径，
 * 比「反射 + setAccessible」快得多，也少一层运行期失败面。
 * ★ 组合铁律：{@code @Shadow} 只影子**目标类自己的**成员（{@code startUp} 与
 * {@code doorCooldown} 都声明在 {@code org.mtr.core.data.Vehicle} 上，javap 核过），
 * 不跨到父类去，免得语义一变就是启动期崩溃。
 *
 * <h2>为什么只在「关门那一刻」动手（每站一次）</h2>
 * {@code startUp} 在 {@code elapsed >= closeAt} 之后的**每一 tick** 都会被调（被闸门早退），
 * 所以必须一次成型、不能每 tick 叠加（叠一次就多等 X 秒）。判据用
 * {@code doorCooldown == 4200L}：关门指令那一 tick，{@code simulate} 顶部读到的
 * 「门目标」还是开着的（{@code openDoors()} 是上一 tick 调的）⇒ 倒计时正好是满值 4200，
 * 而**同一站的后续 tick 一律小于 4200**（{@code closeDoors()} 之后开始递减）。
 * 于是「满值」天然就是「这是这一站的第一次」：
 * <ul>
 *   <li>被信号憋住的列车第一次进到这里时倒计时已经不足 4200 ⇒ 直接不动（宁可原样，也不按错的基准乱挪）；</li>
 *   <li>下一站会重新看到满值 ⇒ 每站各自生效，不需要任何按车 / 按站的表。</li>
 * </ul>
 *
 * <h2>★ 铁律：本 mixin 的注入体里只许出现「原语 + {@link PsdDepartHold}」</h2>
 * 不许出现任何 MTR 类型（编译期没有 MTR 依赖），也不许在注入体里读存档
 * （MTR 的列车模拟可以被服务器配置搬到自己的线程上 ⇒ 只读一个 long 字段，取值交给
 * {@link PsdDepartHold}，见那边的说明）。{@code @Pseudo} + {@code Mtr3LiftMixinPlugin}
 * 的门禁保证「没装 MTR4 / MTR4 换了包名」时安静跳过、绝不崩游戏。
 *
 * <p>★ 顺带一条同名规矩（与 {@code Mtr3LiftAutoClose} 完全一致）：**本 mixin 的辅助类
 * 绝不能放在 {@code smooth.lift.mixin.mtr} 这个包里**（那个包只许放 {@code @Mixin} 类）
 * —— {@link PsdDepartHold} 因此住在 {@code smooth.lift}。
 */
@Pseudo
@Mixin(targets = "org.mtr.core.data.Vehicle")
public abstract class Mtr4TrainDepartHoldMixin {

    /** MTR 自己的发车倒计时（private long，声明在 {@code Vehicle} 上）。 */
    @Shadow
    private long doorCooldown;

    /**
     * 关门那一刻（{@code startUp} 第一次被调、倒计时还是满值 4200）把倒计时改成
     * 「门走完 + X 秒」；其余每一 tick 只是原样早退，不碰任何东西。
     *
     * @param departureIndex        MTR 自己的两个参数，本 mixin 用不到（签名必须逐字一致）
     * @param sidingDepartureTime   同上
     */
    @Inject(method = "startUp(JJ)V", at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$holdAfterDoorsClosed(long departureIndex, long sidingDepartureTime,
                                                 CallbackInfo ci) {
        // MTR 的满值倒计时 = 4200（DOOR_MOVE_TIME 3200 + DOOR_DELAY 1000）；不是满值 ⇒ 不是这一站的第一次。
        if (doorCooldown != 4200L) {
            return;
        }
        long offsetMs = PsdDepartHold.offsetMsFor(this);
        if (offsetMs == 0L) {
            return; // 没配 / 配成 0 / 读不到 —— 一个字节都不写（MTR 原样）
        }
        // 倒计时的 4200 里含 DOOR_DELAY(1000)：要量到「门走完」那一刻，得先减掉它。
        long next = Math.max(0L, doorCooldown - PsdDepartHold.DOOR_DELAY_MS + offsetMs);
        doorCooldown = next;
        PsdDepartHold.noteApplied(next);
    }
}
