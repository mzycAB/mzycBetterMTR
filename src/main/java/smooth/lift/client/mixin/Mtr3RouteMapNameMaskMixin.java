package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import smooth.lift.client.PlatformNameMask;

/**
 * 【1.37.hotfix-2】MTR3 的「名字掩码」—— <b>屏蔽门玻璃上真正被画出来的那两处名字</b>。
 *
 * <h2>用户点名的问题</h2>
 * 站台名写成 {@code C%A%} 时，MTR3 的屏蔽门玻璃照旧显示 {@code C%A%}（MTR4 只显示 {@code C}）。
 *
 * <h2>★ 注入点是怎么定的（不是猜的，是从三个发行 jar 里 javap 出来的）</h2>
 * 三个 jar —— {@code MTR-fabric-1.20-3.2.2-hotfix-1}、{@code MTR-forge-1.18.2-3.2.2-hotfix-1}、
 * {@code MTR-forge-1.20.1-3.2.2-hotfix-2} —— 的 {@code mtr.client.RouteMapGenerator} 逐条比对过，
 * 这三个方法体的字节码**完全同构**（偏移都一模一样），所以同一份 mixin 三个版本通用：
 *
 * <h3>1) 屏蔽门方向箭头圆圈里的站台名 = {@code generateDirectionArrow}</h3>
 * <pre>
 *   偏移 190  IGui.mergeStations(destinations)          ← 「往X」的 X（走 getFormattedRouteDestination，已另掩）
 *   偏移 387  ClientCache.getTextPixels(...)            ← 第 1 次：画 X
 *   偏移 662  getfield mtr/data/Platform.name           ← ★ 站台名（用户看到的 C%A% 就是这里）
 *   偏移 701  ClientCache.getTextPixels(...)            ← 第 2 次：把站台名画进圆圈
 * </pre>
 * ⇒ 取 {@code ordinal = 1} 的那次 {@code getTextPixels} 的第 0 个实参。
 *
 * <h3>2) 屏蔽门线路图圆圈上的站名 = {@code lambda$generateRouteMap$5}</h3>
 * <pre>
 *   偏移 355  IGui.mergeStations(interchangeNames)      ← 换乘线路名（合并串，不在这里掩，见下）
 *   偏移 414  ClientCache.getTextPixels(...)            ← 第 1 次：画换乘线路名
 *   偏移 538  getfield mtr/data/Station.name            ← ★ 站名
 *   偏移 583  ClientCache.getTextPixels(...)            ← 第 2 次：把站名画到线路图圆圈旁
 * </pre>
 * ⇒ 同样取 {@code ordinal = 1}。{@code lambda$generateRouteMap$5} 这个**编译器生成的私有静态方法名**
 * 三个 jar 完全一致（{@code javap -p} 逐条核过），而且这个模组本来就有依赖 MTR3 lambda 名的先例
 * （{@code Mtr3PidsNameMixin} 的 {@code lambda$getSchedules$0}）。
 *
 * <h2>为什么不掩第 1 次（ordinal = 0）</h2>
 * 那两处传进去的都是**合并后的串**（{@code "A%B / C%D"}）。在合并之后掩会从第一个 {@code %} 一路截到结尾、
 * 只剩 {@code A}，把第二条线路整个吃掉。终点站那一条已经由 {@code Mtr3TextNameMaskMixin}
 * 在**每条线路各自**的出口掩过了（合并时串里已无 {@code %}），换乘线路名同理按「不制造新回归」的原则留给后续。
 *
 * <h2>★ 为什么不用 {@code @ModifyVariable} 做「总闸」</h2>
 * 上一版在 {@code ClientCache#getTextPixels} 上挂 {@code @ModifyVariable(argsOnly = true, at = HEAD)}，
 * 想一次盖住所有贴图文字，实机**没有生效**（用户回报屏蔽门照旧显示 {@code C%A%}；
 * 注入点缺失时 {@code require = 0} 是静默的 ⇒ 既不报错也不生效，最难查）。
 * 现在改用本仓库已在 MTR4 上验证过的 {@code @ModifyArg}（见 {@code Mtr4RouteArrowAliasMixin}）。
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>handler 里只出现 {@code String} 与 {@link PlatformNameMask}，**不出现任何 MTR / 原版类型**
 *       ⇒ 编译期不需要 MTR 依赖；{@code @At} 的 target 描述符只含 {@code java.lang.String}、
 *       {@code int[]} 与 MTR 自己的枚举 {@code mtr/data/IGui$HorizontalAlignment}（MTR 不参与原版混淆）
 *       ⇒ {@code remap = false} 在 dev / 生产两端都对得上；</li>
 *   <li>{@code require = 0}：MTR3 哪天改了这两个方法（或换了 javac 版本导致 lambda 改名），
 *       只是「这一处不掩码」，**绝不把启动带崩**；是否注进去了看
 *       {@code PsdDoorMixinPlugin#postApply} 的 {@code [SmoothLift/PidsName] mixin 已注入}；</li>
 *   <li>掩码对不含 {@code %} 的字符串**原样返回同一个实例** ⇒ 绝大多数调用零分配、零行为变化。</li>
 * </ul>
 *
 * <p>MTR4 里没有 {@code mtr.client.RouteMapGenerator} 这个类（MTR4 是 {@code org.mtr.mod.client.RouteMapGenerator}）
 * ⇒ MTR4 客户端上本 mixin 被门禁直接跳过。
 */
@Pseudo
@Mixin(targets = "mtr.client.RouteMapGenerator")
public abstract class Mtr3RouteMapNameMaskMixin {

    /** 屏蔽门方向箭头圆圈里的站台名（{@code generateDirectionArrow} 里第 2 次栅格化）。 */
    @ModifyArg(
            method = "generateDirectionArrow",
            at = @At(
                    value = "INVOKE",
                    target = "Lmtr/client/ClientCache;getTextPixels(Ljava/lang/String;[IIIIIILmtr/data/IGui$HorizontalAlignment;)[B",
                    ordinal = 1),
            index = 0,
            require = 0,
            remap = false)
    private static String smoothlift$maskPlatformName(String text) {
        return PlatformNameMask.mask(text);
    }

    /** 屏蔽门线路图圆圈旁的站名（{@code lambda$generateRouteMap$5} 里第 2 次栅格化）。 */
    @ModifyArg(
            method = "lambda$generateRouteMap$5",
            at = @At(
                    value = "INVOKE",
                    target = "Lmtr/client/ClientCache;getTextPixels(Ljava/lang/String;[IIIIIILmtr/data/IGui$HorizontalAlignment;)[B",
                    ordinal = 1),
            index = 0,
            require = 0,
            remap = false)
    private static String smoothlift$maskStationName(String text) {
        return PlatformNameMask.mask(text);
    }
}
