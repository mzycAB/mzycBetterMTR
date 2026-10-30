package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.PlatformNameMask;

/**
 * 【1.37.hotfix-3】MTR3 的「名字掩码」（{@code %} 成对包住的那一段不显示，其余照常显示）—— <b>终点站那一条</b>。
 *
 * <h2>用户点名的问题</h2>
 * 站台名写成 {@code C%A%} 时，MTR4 的屏蔽门玻璃只显示 {@code C}；MTR3 却把 {@code C%A%} 原样画出来。
 *
 * <h2>为什么 MTR3 不能像 MTR4 那样「在名字源头掩一次」</h2>
 * MTR4 的名字有唯一 getter {@code org.mtr.core.data.NameColorDataBase#getName()}；
 * <b>MTR3 没有</b> —— {@code mtr.data.NameColorDataBase.name} 是 public 字段（三个发行 jar 的
 * {@code javap -p} 都确认过），读名字的地方全是 {@code getfield}，没有任何方法可以挂。
 * 所以 MTR3 只能「在画出来的那一刻」按**产地**逐个掩。
 *
 * <h2>本条管什么</h2>
 * {@code ClientCache#getFormattedRouteDestination(Route, int, String)} —— **每条线路的终点站名**。
 * 屏蔽门箭头那句「往X / to X」就是它拼出来的：
 * <pre>
 *   RouteMapGenerator.generateDirectionArrow  (javap 偏移 190)
 *     IGui.mergeStations(destinations)        ← 每个元素 = 一次 getFormattedRouteDestination
 *     ... insertTranslation("gui.mtr.to", X)
 * </pre>
 * <b>为什么必须在这里掩、而不是等合并完再掩</b>：合并后的字符串形如 {@code "A%B / C%D"}，
 * 再掩就会从第一个 {@code %} 一路截到结尾、只剩 {@code A}，把第二条线路整个吃掉；
 * 在**每条线路各自**的出口先掩，合并时字符串里已经没有 {@code %}，得到 {@code "A / C"} —— 与 MTR4 口径一致。
 * 顺带把 PIDS 上的「终点站」也掩上（PIDS 走 {@code IDrawing.drawStringWithFont}，不经栅格化）。
 *
 * <h2>★ 为什么不再用 {@code @ModifyVariable}</h2>
 * 上一版（1.37.xxxx.hotfix）在 {@code ClientCache#getTextPixels} 上用
 * {@code @ModifyVariable(argsOnly = true, at = HEAD)} 想做「所有贴图文字的总闸」，实机**没有生效**
 * （用户回报：MTR3 屏蔽门照旧显示 {@code C%A%}；注入点缺失时 {@code require = 0} 是静默的，所以既不报错也不生效）。
 * 现在改成**逐个产地 {@code @ModifyArg}**（本仓库在 MTR4 的 {@code Mtr4RouteArrowAliasMixin} 上已验证过的写法），
 * 注入点直接用 {@code javap} 从三个发行 jar 里核对过：
 * <ul>
 *   <li>本类：{@code getFormattedRouteDestination} 的返回值（终点站名）；</li>
 *   <li>{@code Mtr3RouteMapNameMaskMixin}：屏蔽门箭头圆圈里的站台名 + 线路图圆圈上的站名。</li>
 * </ul>
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>{@link PlatformNameMask#mask} 对不含 {@code %} 的字符串**原样返回同一个实例**（绝大多数调用零分配、
 *       零行为变化）；输出里不含 {@code %} ⇒ 重复掩码幂等；</li>
 *   <li>只改「画出来给别人看」的那一刻，**不动** {@code name} 字段本身 ⇒ 设置界面照旧能看到并保存完整的
 *       {@code C%A%}；</li>
 *   <li>handler 里只出现 {@code String} 与 {@link PlatformNameMask}，**不出现任何 MTR 类型**
 *       ⇒ 编译期不需要 MTR 依赖；{@code @Pseudo} + {@code PsdDoorMixinPlugin} 门禁保证
 *       「没装 MTR3 / MTR3 换了包名」时安静跳过、绝不崩游戏。</li>
 * </ul>
 *
 * <p>MTR4 里没有 {@code mtr.client.ClientCache} 这个类 ⇒ MTR4 客户端上本 mixin 被门禁直接跳过。
 */
@Pseudo
@Mixin(targets = "mtr.client.ClientCache")
public abstract class Mtr3TextNameMaskMixin {

    /**
     * 「这条线路的终点站」——按**每条线路**分别掩，合并（{@code IGui.mergeStations}）之后才不会
     * 从第一个 {@code %} 截到结尾（详见类注释）。
     *
     * <p>方法名**故意不写描述符**：MTR3 哪天改了参数类型也不会因为描述符对不上而**崩启动**；
     * {@code require = 0} 同理 —— 注入点缺失时只是「不掩码」，不是「开不了游戏」。
     * 是否真的注进去了，看 {@code PsdDoorMixinPlugin#postApply} 打的
     * {@code [SmoothLift/PidsName] mixin 已注入} 那一行。
     */
    @Inject(method = "getFormattedRouteDestination", at = @At("RETURN"),
            cancellable = true, require = 0, remap = false)
    private void smoothlift$maskFormattedDestination(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(PlatformNameMask.mask(cir.getReturnValue()));
    }
}
