package smooth.lift.client;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.31.1204】{@code /mtrqx} 子命令的处理器。
 *
 * <ul>
 *   <li>{@code /mtrqx}      —— 查看当前状态；</li>
 *   <li>{@code /mtrqx on}   —— 开启：列车上下坡时整个画面跟着旋转（窗外窗框/地板保持水平）；</li>
 *   <li>{@code /mtrqx off}  —— 关闭：与 MTR 原版逐位一致，画面不随列车倾斜。</li>
 * </ul>
 *
 * <p>切换逻辑全部在 {@link TrainTiltView#apply(boolean)}：改内存态 → 存 config
 * （{@code config/smoothlift-view.properties}）。这里只负责把结果反馈给玩家。
 * 与 {@link EscalatorRenderModeCommand}（{@code /mtrxr}）同一套结构：
 * {@code show} / {@code set} / 「不经过 brigadier 的核心逻辑」三段。
 */
public final class TrainTiltViewCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private TrainTiltViewCommand() {
    }

    /**
     * {@code /mtrqx}（无参数）：显示当前状态 + 两个取值的含义 + <b>诊断串</b>。
     *
     * <p>★【1.31.1204】诊断串（{@link TrainTiltView#diagnostics()}）同时进聊天栏与日志：
     * 「装了没反应」时不必翻日志文件，直接在游戏里打一次 {@code /mtrqx} 就能看出
     * 是「注入点没通」还是「车体没有俯仰」还是「开关关了」。这行日志也便于事后追溯。
     */
    public static int show(CommandContext<FabricClientCommandSource> ctx) {
        String state = TrainTiltView.name();
        String diag = TrainTiltView.diagnostics();
        ctx.getSource().sendFeedback(Component.literal(
                "[SmoothLift] 列车倾斜视角：" + state
                        + "（/mtrqx on = 画面随列车倾斜旋转、窗外窗框与地板保持水平，/mtrqx off = 与 MTR 原版一致）"));
        ctx.getSource().sendFeedback(Component.literal("[SmoothLift] 诊断：" + diag));
        LOGGER.info("[SmoothLift/TiltView] /mtrqx 状态查询：{}｜{}", state, diag);
        return 1;
    }

    /**
     * {@code /mtrqx on|off}：切换。
     *
     * @param on true = 开启（视角随列车倾斜）；false = 关闭（MTR 原版行为）
     */
    public static int set(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        ctx.getSource().sendFeedback(Component.literal(apply(on)));
        return 1;
    }

    /**
     * 切换的**核心逻辑**（不经过 brigadier —— 以后若要做成设置界面的开关，也直接调它）。
     *
     * @return 要给玩家看的反馈（含「无需切换」的情形）
     */
    public static String apply(boolean on) {
        if (!TrainTiltView.apply(on)) {
            return "[SmoothLift] 列车倾斜视角本来就是" + (on ? "开启" : "关闭") + "的，无需切换。";
        }
        return "[SmoothLift] 列车倾斜视角已" + (on ? "开启" : "关闭")
                + "（立刻生效，已记住；用 /mtrqx 看当前状态）";
    }
}
