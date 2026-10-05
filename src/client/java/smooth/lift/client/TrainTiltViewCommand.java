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
 *   <li>{@code /mtrqx}      —— 查询：只回值 {@code 1}/{@code 0}；</li>
 *   <li>{@code /mtrqx on}   —— 动作：只回「指令执行成功 / 指令执行失败」；</li>
 *   <li>{@code /mtrqx off}  —— 同上。</li>
 * </ul>
 *
 * <p>切换逻辑全部在 {@link TrainTiltView#apply(boolean)}：改内存态 → 存 config
 * （{@code config/smoothlift-view.properties}）。
 *
 * <p>★★★【10-05 反馈铁规】玩家只看**短句**：
 * <ul>
 *   <li><b>动作类指令</b> → 只回「指令执行成功」/「指令执行失败」；</li>
 *   <li><b>查询类指令</b> → 只回**值**（开关 → {@code 1}/{@code 0}）；</li>
 *   <li>所有本来写给人看的**长句状态 / 诊断串一律只进日志**（{@code LOGGER.info}），不再回聊天栏。</li>
 * </ul>
 * 这条是用户点名的硬约束，换模型也必须继承 —— 新功能一律照此，别再往聊天栏贴长句。
 * （诊断串本身没丢：{@code /mtrqx} 打一次，聊天栏只给 {@code 1}/{@code 0}，完整诊断进
 * {@code logs/latest.log}。）
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
     *
     * <p>长句状态与诊断串（{@link TrainTiltView#diagnostics()}）只进日志：
     * 「装了没反应」时不必翻日志文件，在游戏里打一次 {@code /mtrqx} 即可同时拿到值，
     * 完整诊断去 {@code logs/latest.log} 看那一行「/mtrqx 诊断」。
     */
    public static int show(CommandContext<FabricClientCommandSource> ctx) {
        String state = TrainTiltView.name();
        String diag = TrainTiltView.diagnostics();
        ctx.getSource().sendFeedback(Component.literal(TrainTiltView.isEnabled() ? "1" : "0"));
        LOGGER.info("[SmoothLift/TiltView] /mtrqx 诊断：{}｜{}", state, diag);
        return 1;
    }

    /**
     * {@code /mtrqx on|off}：**动作** —— 只回「指令执行成功 / 指令执行失败」。
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
     * @return 统一回执（「指令执行成功」；细节只进日志）
     */
    public static String apply(boolean on) {
        boolean changed = TrainTiltView.apply(on);
        LOGGER.info("[SmoothLift/TiltView] /mtrqx {}：{}", on ? "on" : "off",
                changed ? "已切换" : "本来就是这个状态");
        return CMD_OK;
    }
}
