package smooth.lift.client;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.24】{@code /mtrxr} 子命令的处理器。
 *
 * <ul>
 *   <li>{@code /mtrxr}        —— 查询：只回值「引擎, 遮挡剔除」（各 {@code 1}/{@code 0}）；</li>
 *   <li>{@code /mtrxr on}     —— 动作：只回「指令执行成功 / 指令执行失败」；</li>
 *   <li>{@code /mtrxr off}    —— 同上；</li>
 *   <li>★【1.28】{@code /mtrxr occ on|off} —— 动作：同上
 *       （被墙 / 地形挡住的扶梯段整段不渲染。默认开，关掉是应急逃生口：
 *       万一某张图里扶梯因为段被判成遮挡而整片消失，用这一条立刻救回来）。</li>
 * </ul>
 *
 * <p>★★★【10-05 反馈铁规】玩家只看**短句**：动作类 → 「指令执行成功 / 指令执行失败」；
 * 查询类 → 只回**值**；状态名 / 性能统计（{@code statsLine()}）等长句**只进日志**。
 * 硬约束，换模型也继承 —— 别再往聊天栏贴长句。
 */
public final class EscalatorRenderModeCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 统一回执文案（与模组其它指令逐字一致）。 */
    private static final String CMD_OK = "指令执行成功";
    private static final String CMD_FAIL = "指令执行失败";

    private EscalatorRenderModeCommand() {
    }

    /**
     * {@code /mtrxr}（无参数）：**查询** —— 只回值「引擎, 遮挡剔除」。
     * 引擎：{@code 1} = SmoothLift 优化引擎（/mtrxr off），{@code 0} = MTR 原版渲染（/mtrxr on）；
     * 遮挡剔除：{@code 1} = 开，{@code 0} = 关。多值逗号并列（与模组查询口径一致）。
     *
     * <p>模式名与性能统计只进日志（{@code EscalatorStepRenderer.statsLine()}）：
     * 改渲染路径前后用同一张存档、同一个视角对比日志里那一行即可。
     */
    public static int show(CommandContext<FabricClientCommandSource> ctx) {
        String values = (EscalatorRenderMode.isOptimized() ? "1" : "0") + ", "
                + (EscalatorRenderMode.isOcclusionCulling() ? "1" : "0");
        ctx.getSource().sendFeedback(Component.literal(values));
        LOGGER.info("[SmoothLift] /mtrxr：{}｜{}", EscalatorRenderMode.name(),
                EscalatorStepRenderer.statsLine());
        return 1;
    }

    /**
     * 【1.28】{@code /mtrxr occ on|off}：**动作** —— 只回「指令执行成功 / 指令执行失败」。
     *
     * @param on true = 开启（被挡住的段整段不画）；false = 关闭（只按距离 + 视锥剔除）
     */
    public static int setOcclusion(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        ctx.getSource().sendFeedback(Component.literal(applyOcclusionMode(on)));
        return 1;
    }

    /**
     * 【09-30 续 2】开关遮挡剔除的**核心逻辑**（不经过 brigadier —— mbmhelp 的
     * 「兼容模式」弹窗直接调它）。
     *
     * @return 统一回执（「指令执行成功」）；细节只进日志
     */
    public static String applyOcclusionMode(boolean on) {
        boolean changed = EscalatorRenderMode.applyOcclusion(on);
        LOGGER.info("[SmoothLift] 遮挡剔除 {}：{}", on ? "开" : "关",
                changed ? "已切换" : "本来就是这个状态");
        return CMD_OK;
    }

    /**
     * {@code /mtrxr on|off}：**动作** —— 只回「指令执行成功 / 指令执行失败」。
     *
     * @param optimize true = 优化引擎（/mtrxr off）；false = MTR 原版（/mtrxr on）
     */
    public static int set(CommandContext<FabricClientCommandSource> ctx, boolean optimize) {
        ctx.getSource().sendFeedback(Component.literal(applyMode(optimize)));
        return 1;
    }

    /**
     * 【09-30 续 2】切换渲染引擎的**核心逻辑**（不经过 brigadier —— mbmhelp 的
     * 「兼容模式」弹窗直接调它）。
     *
     * @return 统一回执（「指令执行成功」）；细节只进日志
     */
    public static String applyMode(boolean optimize) {
        boolean changed = EscalatorRenderMode.apply(optimize);
        LOGGER.info("[SmoothLift] 扶梯阶梯渲染引擎 → {}：{}", EscalatorRenderMode.name(),
                changed ? "已切换（资源包正在重载）" : "本来就是这个模式");
        return CMD_OK;
    }
}
