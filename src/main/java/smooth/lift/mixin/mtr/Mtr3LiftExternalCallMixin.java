package smooth.lift.mixin.mtr;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.Mtr3LiftAutoClose;

/**
 * 【1.45】修 MTR 3「外呼按钮和直梯在同一层时按了没反应」。
 *
 * <p><b>挂在哪</b>：{@code mtr.data.LiftInstructions.addInstruction(Level, BlockPos, boolean)}
 * 的 TAIL —— 这是**唯一**的外呼入口（{@code mtr.block.BlockLiftButtons.use} 里那段
 * {@code LiftInstructions.addInstruction(world, pos, hitY - floor(hitY) > 0.25)}，
 * 上半格 = 上行按钮、下半格 = 下行按钮），而且只在服务端跑。
 *
 * <p>为什么挂 TAIL 而不是 HEAD：原版那一段**不是纯粹的丢弃**，它在正常情形下要把
 * 「挑一台最近的直梯 + 按顺序插指令」做完（还会顺带把 {@code isDirty} 置上以触发同步）。
 * 我们只是**补一遍它刚刚丢掉的那种输入**，所以必须等它跑完，再看「是不是真的谁都没动」——
 * 具体判据（停在这一层 + 空闲 + 门没全开）和根因都写在
 * {@link Mtr3LiftAutoClose#onExternalCall} 与那个类的注释里。
 *
 * <p><b>为什么必须用完整描述符选方法</b>：{@code mtr.data.LiftInstructions} 上有**三个**
 * {@code addInstruction}
 * （{@code (int,boolean,int)V}、{@code (int,boolean,int,boolean,boolean,boolean)I}、
 * 以及本 mixin 要的 {@code (Level,BlockPos,boolean)V}）。
 * Mixin 用裸方法名选到重载会直接报「目标方法不唯一」而让**启动期**失败，
 * 所以这里写全描述符。描述符里的 {@code net.minecraft.*} 类名在 1.20.x
 * 各命名空间（named / srg）里是**同一个字符串**（只有成员名会被 reobf），所以不需要 refmap 映射。
 *
 * <p><b>为什么必须加 {@code @Pseudo}</b>：SmoothLift 不依赖 MTR，{@code mtr.*} 不在编译类路径上，
 * 不写 {@code @Pseudo} 的话 Mixin 的注解处理器会报「目标类找不到」直接编译失败；
 * 运行期没装 MTR（或装的是 MTR4）时它也让本 mixin **安全跳过**而不是抛 ClassNotFoundException。
 * 与 {@link Mtr3LiftMixinPlugin} 的版本门禁是两道独立保险（插件按版本判、{@code @Pseudo} 按类存在性判）。
 *
 * <p><b>★ 为什么整个方法体包在 {@code try} 里</b>：Mixin **不会**给注入的 handler 包 try/catch，
 * handler 里逃出去的 {@code Throwable} 会直接落到注入点所在的线程（这里是服务端主线程，
 * 玩家点方块的处理线程）⇒ 症状不是崩溃报告而是「点按钮没反应 / 方块被推回」。
 * catch 里**只准**用 SLF4J，绝不调本模组任何其它类（否则 catch 里再炸一次异常又跑掉）。
 * 这个坑在 {@link Mtr3LiftDoorMixin} 上已经踩过一次（当时是服务端 tick 线程死掉）。
 */
@Pseudo
@Mixin(targets = "mtr.data.LiftInstructions")
public abstract class Mtr3LiftExternalCallMixin {

    /**
     * 外呼按下之后补一次「同一层就把门重新打开」。
     *
     * <p>注意目标是**静态**方法，所以本 handler 也必须是 {@code static}（没有 {@code this}）。
     *
     * @param world 服务端世界（原版方法里也是用它去拿 {@code RailwayData} 的）
     * @param pos   按钮方块位置（原版那个 {@code blockPos}；{@code getY()} 就是按钮所在的层）
     * @param isUp  true = 上行按钮（命中点在本方块上半格），false = 下行按钮
     */
    @Inject(method = "addInstruction(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("TAIL"))
    private static void smoothlift$reopenDoorForSameFloorCall(Level world, BlockPos pos, boolean isUp,
                                                              CallbackInfo ci) {
        try {
            Mtr3LiftAutoClose.onExternalCall(world, pos);
        } catch (Throwable t) {
            // ★ 只准用 SLF4J（见类注释最后一段）
            org.slf4j.LoggerFactory.getLogger("smoothlift")
                    .error("[SmoothLift/Mtr3Fix] 外呼「同一层重开门」检查出错（这次外呼已放弃）", t);
        }
    }
}
