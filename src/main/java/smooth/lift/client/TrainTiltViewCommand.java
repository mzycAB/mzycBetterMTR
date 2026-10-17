package smooth.lift.client;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.31.11201 移植】{@code /mtrqx} 子命令的处理器（Forge 1.20.1 侧）。
 *
 * <ul>
 *   <li>{@code /mtrqx}      —— 查询：只回值 {@code 1}/{@code 0}；</li>
 *   <li>{@code /mtrqx on}   —— 动作：只回「指令执行成功 / 指令执行失败」；</li>
 *   <li>{@code /mtrqx off}  —— 同上。</li>
 * </ul>
 *
 * <p>切换逻辑全部在 {@link TrainTiltView#apply(boolean)}：改内存态 → 存 config
 * （{@code config/smoothlift-view.properties}）。
 *
 * <p>★★★【10-05 反馈铁规】玩家只看**短句**：动作类只回「指令执行成功/失败」、
 * 查询类只回**值**、长句状态/诊断串一律只进日志。
 *
 * <p>★【Forge 移植注】客户端指令源是普通 {@link CommandSourceStack}，用
 * {@code sendSuccess(Supplier<Component>, false)} 反馈（客户端语义下第二条参数无效），
 * 与 {@code EscalatorRenderModeCommand} 同一套写法。
 */
public final class TrainTiltViewCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 统一回执文案（与模组其它指令逐字一致）。 */
    private static final String CMD_OK = "指令执行成功";
    private static final String CMD_FAIL = "指令执行失败";

    private TrainTiltViewCommand() {
    }

    /**
     * {@code /mtrqx}（无参数）：**查询** —— 只回值 {@code 1}（开）/ {@code 0}（关）。
     */
    public static int show(CommandContext<CommandSourceStack> ctx) {
        String state = TrainTiltView.name();
        String diag = TrainTiltView.diagnostics();
        ctx.getSource().sendSuccess(() -> Component.literal(
                TrainTiltView.isEnabled() ? "1" : "0"), false);
        LOGGER.info("[SmoothLift/TiltView] /mtrqx 诊断：{}｜{}", state, diag);
        return 1;
    }

    /**
     * {@code /mtrqx on|off}：**动作** —— 只回「指令执行成功 / 指令执行失败」。
     *
     * @param on true = 开启（视角随列车倾斜）；false = 关闭（MTR 原版行为）
     */
    public static int set(CommandContext<CommandSourceStack> ctx, boolean on) {
        ctx.getSource().sendSuccess(() -> Component.literal(apply(on)), false);
        return 1;
    }

    /**
     * 切换的**核心逻辑**（不经过 brigadier —— 以后若要做成设置界面的开关，也直接调它）。
     *
     * @return 统一回执（「指令执行成功」；细节只进日志）
     */
    public static String apply(boolean on) {
        boolean changed = TrainTiltView.apply(on);
        LOGGER.info("[SmoothLift/TiltView] /mtrqx {}：{}", on ? "on" : "off",
                changed ? "已切换" : "本来就是这个状态");
        return CMD_OK;
    }
}
