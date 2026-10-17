package smooth.lift.mixin.mtr;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.Mtr3LiftAutoClose;

import java.util.Map;
import java.util.Set;

/**
 * 【1.42】修 MTR 3「直梯停在那一层的门一直不关」的 bug，并让它像 MTR 4 那样自动关门。
 *
 * <p>【1.45】起本 mixin 多担一件事：给「同一层按外呼 / 按本层就把门重新打开」
 * （{@link Mtr3LiftExternalCallMixin}、{@link Mtr3LiftPanelCallMixin}）**补一次数据同步** ——
 * 那两处是在 tick 之外改的门状态，MTR3 自己的同步条件（指令脏了 / 乘客数变了）看不到它。
 * 见下面 handler 里 `consumePendingSync` 那一段的注释。
 *
 * <p><b>挂在哪</b>：{@code mtr.data.LiftServer.tickServer(World, Map, Set)} 的 TAIL。
 * 选它而不是父类的 {@code tick(World, float)}，有三个理由：
 * <ol>
 *   <li>它是**服务端独有**的（客户端是 {@code LiftClient.tickClient}），所以规则只跑在权威侧，
 *       不会出现「客户端自己把门关了、服务端却还开着」的两端分叉；</li>
 *   <li>它的第三个参数就是 MTR 的「本轮要同步给玩家的直梯集合」（{@code dataSetToSync}）——
 *       我们关门后必须把这条直梯塞进去，否则**客户端根本收不到「门关了」**，
 *       玩家看到的还是开着的门（原版只在「指令脏了 / 玩家进出范围」时才发更新）；</li>
 *   <li>它每服务端刻正好被调一次（{@code RailwayData.simulateTrains} → {@code lifts.forEach(...)}），
 *       而它内部调 {@code tick(world, 1.0f)} 也是按「一刻 = delta 1.0」写的 —— 计时按调用次数数就等价于按 tick 数。</li>
 * </ol>
 *
 * <p>TAIL 的位置很关键：MTR3 自己的 {@code tick} 已经在 {@code tickServer} 的前半段跑完了，
 * 所以这里读到的 {@code doorOpen} / {@code doorValue} 是**本 tick 的最终值**，
 * 不会出现「刚开门就被我们判成空闲」这种偏一 tick 的错判。
 *
 * <p><b>只管 MTR3</b>：目标类 {@code mtr.data.LiftServer} 在 MTR4 里不存在
 * （实测 MTR4 的 jar 里 {@code mtr/data/Lift*} 一个都没有），所以本 mixin 在 MTR4 环境下
 * 由 {@link Mtr3LiftMixinPlugin} 直接跳过 ⇒ 满足「加载 mtr4 就不管这一条」。
 *
 * <p><b>为什么必须加 {@code @Pseudo}</b>（两个理由，缺一个都编译/启动不过）：
 * <ol>
 *   <li><b>编译期</b>：SmoothLift 不依赖 MTR，{@code mtr.data.LiftServer} 不在编译类路径上，
 *       Mixin 的注解处理器会报 {@code Mixin target mtr.data.LiftServer could not be found}
 *       直接编译失败。{@code @Pseudo} 明确告诉处理器「这是个可选目标，类不在也要放行」。</li>
 *   <li><b>运行期</b>：没装 MTR（或装的是 MTR4）时目标类不存在，Mixin 会**安全跳过**本 mixin
 *       而不是抛 {@code ClassNotFoundException} 把游戏带崩。这正是「可选依赖模组」的标准做法，
 *       与下面的 {@link Mtr3LiftMixinPlugin} 是两道独立保险（插件按版本判、{@code @Pseudo} 按类存在性判）。</li>
 * </ol>
 * ★ 注意 {@code @Pseudo} **不**影响注入目标方法名的重映射：我们用的是
 * {@code method = "tickServer"} 这种**不带描述符的纯方法名**（MTR 自己的方法，与 MC 映射无关），
 * 所以不存在「目标类不在类路径时签名没被 remap 到 intermediary」那个坑。
 */
@Pseudo
@Mixin(targets = "mtr.data.LiftServer")
public abstract class Mtr3LiftDoorMixin {

    /**
     * 「门已全开且空闲」已经持续了多少个服务端刻。
     *
     * <p>用 {@code @Unique} 而不是外部 Map：直梯被删除时这个计数器跟着对象一起消失，
     * 不会像 {@code IdentityHashMap} 那样留下永不回收的条目。
     */
    @Unique
    private int smoothlift$idleDwell;

    /**
     * 每服务端刻检查一次：门全开且停着超过 {@link Mtr3LiftAutoClose#IDLE_TICKS} 刻就关门，
     * 并把这条直梯标记为「需要同步给玩家」。
     *
     * <p><b>★ 为什么整个方法体包在 {@code try} 里（Forge 1.20.1 移植时加的守则）</b>：
     * Mixin **不会**给注入的 handler 包 try/catch —— handler 里逃出去的任何 {@code Throwable}
     * 都会直接落到**注入点所在的线程**上，而这里是 {@code mtr.data.LiftServer.tickServer}，
     * 也就是服务端 tick 主线程。那条线程一死，症状不是崩溃报告而是
     * 「玩家破坏方块 → 客户端本地先预测（方块消失 + 破坏粒子）→ 服务端没人处理 → 又被推回原样」，
     * 极难定位。而且**类解析期**的异常（例如引用了 mixin 包内的非 mixin 类触发的
     * {@code IllegalClassLoadError}）本来就在被调方法的 try/catch 之外，只有包在这一层才拦得住。
     *
     * @param world   服务端世界（参数名/类型必须与目标方法一致；本 mixin 只挂在服务端类上，用不到它）
     * @param riders  玩家 → 范围内直梯集合（MTR 的乘客挂载用，这里用不到）
     * @param toSync  **本轮要同步给玩家的直梯集合** —— 关门后必须把自己加进去
     */
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void smoothlift$autoCloseIdleDoor(Level world, Map<?, ?> riders, Set<Object> toSync,
                                              CallbackInfo ci) {
        try {
            // 【1.45】门外/轿厢内按了「同一层」时，门是我们**从 tick 之外**打开的
            // （见 Mtr3LiftAutoClose.onExternalCall / onPanelCall）。MTR3 只在
            // `指令脏了 || 乘客数变了` 时才把这台直梯塞进 dataSetToSync，
            // 所以那一次开门必须在这里补一次同步 —— 否则**服务端开了、客户端还关着**
            // （doorOpen/doorValue 都在 Lift.writePacket 的包里，客户端就是靠它俩画门的）。
            // 顺便把停站计时清零：重开的这一次门要拿到完整的 IDLE_TICKS 刻全开时间。
            // ★ 这一步要在 available() 判断**之前**做：它是纯记账（identity set），
            //   跟反射是否可用无关；而"能不能真的改门"由 reopenIfIdleAndParked 自己把关。
            if (Mtr3LiftAutoClose.consumePendingSync(this)) {
                smoothlift$idleDwell = 0;
                toSync.add(this);
            }
            if (!Mtr3LiftAutoClose.available()) {
                return;
            }
            if (!Mtr3LiftAutoClose.isDoorFullyOpen(this)) {
                // 门在动 / 门已关 / 有指令（原版自己会关）→ 计时归零
                smoothlift$idleDwell = 0;
                return;
            }
            if (++smoothlift$idleDwell < Mtr3LiftAutoClose.IDLE_TICKS) {
                return;
            }
            smoothlift$idleDwell = 0;
            Mtr3LiftAutoClose.closeDoor(this);
            // 关键：把「门已关」推给附近客户端。不加这一句，服务端门关了、客户端还画着开着的门。
            toSync.add(this);
        } catch (Throwable t) {
            // ★ catch 里**只准**用本 mixin 的 @Unique 字段 + SLF4J，绝不调本模组任何其它类
            //   —— 否则 catch 里再炸一次，异常又回到服务端 tick 循环。
            smoothlift$idleDwell = 0;
            org.slf4j.LoggerFactory.getLogger("smoothlift")
                    .error("[SmoothLift/Mtr3Fix] 直梯自动关门检查出错（本条直梯已放弃本次计时）", t);
        }
    }
}
