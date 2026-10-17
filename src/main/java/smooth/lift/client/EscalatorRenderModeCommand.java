package smooth.lift.client;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 【1.24】{@code /mtrxr} 子命令的处理器。
 *
 * <ul>
 *   <li>{@code /mtrxr}        —— 查看当前扶梯阶梯渲染引擎；</li>
 *   <li>{@code /mtrxr on}     —— 切到 MTR 原版渲染（兼容性最好）；</li>
 *   <li>{@code /mtrxr off}    —— 切到 SmoothLift 优化渲染引擎（逐条扶梯独立阶梯动画）；</li>
 *   <li>★【1.28】{@code /mtrxr occ on|off} —— 开关<b>遮挡剔除</b>
 *       （被墙 / 地形挡住的扶梯段整段不渲染）。默认开，关掉是应急逃生口：
 *       万一某张图里扶梯因为段被判成遮挡而整片消失，用这一条立刻救回来。</li>
 * </ul>
 *
 * <p>实际切换逻辑全部在 {@link EscalatorRenderMode#apply(boolean)}：改内存态 → 存 config
 * → 清烘焙缓存 → {@code reloadResourcePacks()} 重烘模型。这里只负责把结果反馈给玩家。
 *
 * <p><b>★ Forge 与 Fabric 的唯一差别在这三处反馈调用</b>：
 * Fabric 的 {@code FabricClientCommandSource} 有 {@code sendFeedback(Component)}（只给玩家看），
 * 而 Forge 的客户端指令源是普通的 {@link CommandSourceStack}，只有
 * {@code sendSuccess(Supplier&lt;Component&gt;, boolean broadcastToOps)}。
 * 这些 `/mtrxr` 指令是**纯客户端**的，所以在单人游戏里走 sendSuccess 也不会真的广播给谁
 * （它由 {@code ClientCommandSourceStack} 实现，第二条参数在客户端语义下无效）。
 */
public final class EscalatorRenderModeCommand {

    private EscalatorRenderModeCommand() {
    }

    /** {@code /mtrxr}（无参数）：显示当前模式 + 实测性能计数。 */
    public static int show(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[SmoothLift] 当前扶梯阶梯渲染引擎：" + EscalatorRenderMode.name()
                        + "（/mtrxr on = MTR 原版渲染，/mtrxr off = SmoothLift 优化引擎；"
                        + "/mtrxr occ on|off = 遮挡剔除）"), false);
        // 【1.26】把「到底优化了多少」变成可核验的数字：索引规模 / 上一帧的剔除情况 /
        // 帧内耗时滚动均值。改渲染路径前后用同一张存档、同一个视角对比这一行即可。
        // 【1.28】这一行末尾还会带上遮挡剔除的实时状态。
        ctx.getSource().sendSuccess(() -> Component.literal(EscalatorStepRenderer.statsLine()), false);
        return 1;
    }

    /**
     * 【1.28】{@code /mtrxr occ on|off}：开关遮挡剔除。
     *
     * @param on true = 开启（被挡住的段整段不画）；false = 关闭（只按距离 + 视锥剔除）
     */
    public static int setOcclusion(CommandContext<CommandSourceStack> ctx, boolean on) {
        ctx.getSource().sendSuccess(() -> Component.literal(applyOcclusionMode(on)), false);
        return 1;
    }

    /**
     * {@code /mtrxr on|off}：切换模式。
     *
     * @param optimize true = 优化引擎（/mtrxr off）；false = MTR 原版（/mtrxr on）
     */
    public static int set(CommandContext<CommandSourceStack> ctx, boolean optimize) {
        ctx.getSource().sendSuccess(() -> Component.literal(applyMode(optimize)), false);
        return 1;
    }

    /**
     * 【09-30 续 2】切换渲染引擎的**核心逻辑**（不经过 brigadier —— mbmhelp 的
     * 「兼容模式」弹窗直接调它，反馈与 {@code /mtrxr on|off} 逐字一致）。
     *
     * @return 要给玩家看的反馈（含「无需切换」的情形）
     */
    public static String applyMode(boolean optimize) {
        if (!EscalatorRenderMode.apply(optimize)) {
            // 模式没变（apply 返回 false）：不需要重载资源，直接告知现状。
            return "[SmoothLift] 当前已经是 " + EscalatorRenderMode.name() + "，无需切换。";
        }
        return "[SmoothLift] 扶梯阶梯渲染引擎已切换为：" + EscalatorRenderMode.name()
                + "（资源包正在重载，稍等一两秒即可生效）";
    }

    /**
     * 【09-30 续 2】开关遮挡剔除的核心逻辑（不经过 brigadier —— mbmhelp 的「兼容模式」
     * 弹窗直接调它，反馈与 {@code /mtrxr occ on|off} 逐字一致）。
     *
     * @return 要给玩家看的反馈（含「无需切换」的情形）
     */
    public static String applyOcclusionMode(boolean on) {
        if (!EscalatorRenderMode.applyOcclusion(on)) {
            return "[SmoothLift] 遮挡剔除本来就是" + (on ? "开启" : "关闭") + "的，无需切换。";
        }
        return "[SmoothLift] 扶梯阶梯遮挡剔除已" + (on ? "开启" : "关闭")
                + "（立刻生效，已记住；用 /mtrxr 看当前状态）";
    }
}
