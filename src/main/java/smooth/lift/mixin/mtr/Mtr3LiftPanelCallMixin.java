package smooth.lift.mixin.mtr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.Mtr3LiftAutoClose;

/**
 * 【1.45】修 MTR 3「站在轿厢里按自己所在的这一层没反应」—— 和
 * {@link Mtr3LiftExternalCallMixin 外呼那条} 是**同一个根因**的另一条入口。
 *
 * <p><b>挂在哪</b>：{@code mtr.data.Lift.pressButton(int floor)} 的 TAIL。
 * 调用链是客户端面板 → {@code PacketPressLiftButton} →
 * {@code PacketTrainDataGuiServer.receivePressLiftButtonC2S}
 * → {@code DataCache.liftsServerIdMap.get(id).pressButton(floorIndex)}，
 * 所以它只在服务端跑，且 {@code this} 就是要操作的那条直梯。
 *
 * <p>它内部调的是 {@code liftInstructions.addInstruction(floor(currentPositionY) | ceil(currentPositionY),
 * isUp, floor)} —— 同样会在「按的层 == 自己停的层」时被那个私有核心的
 * {@code if (startFloor == endFloor) return 0;} 丢掉（根因见 {@link Mtr3LiftAutoClose} 的类注释）。
 *
 * <p>外呼那条入口在早期版本里其实也走不到这里（{@code BlockLiftButtons} 直接调
 * {@code LiftInstructions} 的静态方法），所以两条入口要各挂一个。
 *
 * <p><b>为什么用完整描述符</b>：{@code mtr.data.Lift} 上 {@code pressButton} 只有一个重载，
 * 但写全描述符更保险（Mixin 对裸名字的重载判定是「找到多个就报错」，
 * 将来 MTR 加一个重载就会变成启动期失败）。描述符里没有 vanilla 成员名，不需要 refmap 映射。
 *
 * <p><b>★ try / catch 与 {@code @Pseudo} 的理由</b>同 {@link Mtr3LiftExternalCallMixin}：
 * 逃出去的异常会落到收包处理线程上，而且 catch 里只准用 SLF4J。
 */
@Pseudo
@Mixin(targets = "mtr.data.Lift")
public abstract class Mtr3LiftPanelCallMixin {

    /**
     * 轿厢面板按下某层之后补一次「按的就是本层就把门重新打开」。
     *
     * @param floor 按下的楼层（MTR3 的面板传的是**层的 y 坐标**，不是序号 ——
     *              它被原版直接拿去当 {@code endFloor} 跟 {@code currentPositionY} 比）
     */
    @Inject(method = "pressButton(I)V", at = @At("TAIL"))
    private void smoothlift$reopenDoorForSameFloorPanelPress(int floor, CallbackInfo ci) {
        try {
            Mtr3LiftAutoClose.onPanelCall(this, floor);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("smoothlift")
                    .error("[SmoothLift/Mtr3Fix] 轿厢面板「同一层重开门」检查出错（这次按键已放弃）", t);
        }
    }
}
