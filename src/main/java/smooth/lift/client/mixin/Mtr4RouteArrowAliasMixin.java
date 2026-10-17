package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-03 五改】屏蔽门（PSD / APG 玻璃）上那句「<b>往X / to X</b>」的 X 用**别名**。
 *
 * <h2>用户点名</h2>
 * 名字写成 {@code 香港|HongKong%市区|CityCenter} 时，屏蔽门玻璃的**终点站方向箭头**要写
 * 「往市区 toCityCenter」，而不是「往香港 toHongKong」；其它任何地方（讲述人报站、
 * 地铁线路图、PIDS）都用 {@code %}**前面**的本名。
 *
 * <h2>挂哪（对 4.0.5 逐条 javap 核过）</h2>
 * 那一句的**唯一**产地是 {@code org.mtr.mod.client.RouteMapGenerator.generateDirectionArrow(...)}：
 * <pre>
 *   s = IGui.mergeStations(list)            // list 的每个元素 = SimplifiedRoutePlatform.getDestination()
 *   ...                                     // （circular 线路会带一个 temp_circular_marker 前缀）
 *   if (showTo) s = IGui.insertTranslation(GUI_MTR_TO_CJK, GUI_MTR_TO, 1, new String[]{s});
 *   byte[] px = DynamicTextureCache.instance.getTextPixels(s, ...);
 * </pre>
 * 所以这里 {@link ModifyArg} 改的就是那个 {@code new String[]{s}} 的**第 0 格**。
 * 用 {@code ordinal = 2} 挑「第三个 {@code insertTranslation} 调用」——前两个是
 * {@code GUI_MTR_CLOCKWISE_VIA} / {@code GUI_MTR_ANTICLOCKWISE_VIA}（环线用语），只有第三个是
 * 「往 / to」。{@code showTo} 为假时这一个调用**根本不会执行** ⇒ 不需要额外再判一次。
 *
 * <h2>射程（为什么选这里，而不是别的三处）</h2>
 * <ul>
 *   <li><b>PIDS 完全不受影响</b>：{@code RenderPIDS} 根本不走
 *       {@code getDirectionArrow} / {@code generateDirectionArrow}（它读
 *       {@code ArrivalResponse.getDestination()} / {@code SimplifiedRoutePlatform.getStationName()}）；</li>
 *   <li><b>铁路标志牌的箭头也不受影响</b>：{@code RenderRailwaySign.drawSign} 传
 *       {@code showTo = false} ⇒ 那一次 {@code insertTranslation} 不发生；</li>
 *   <li>★ <b>唯一的多余射程 = 线路标志牌（{@code RenderRouteSign}）</b>：它也走同一个
 *       {@code generateDirectionArrow(..., showTo = true, ...)}。两者在**字符串层**没有任何可分之处
 *       （产地是同一个私有静态方法，且在 {@code MainRenderer.WORKER_THREAD} 上生成贴图），
 *       所以「只有屏蔽门」在这一层做不到；真要做到只能连贴图缓存键一起改（4.0.5 的缓存键里
 *       不含文字，得再挂 {@code DynamicTextureCache} 两处）。
 *       现在的取舍：线路标志牌上的「往X」也跟着用别名 —— 与用户要的口径同源（终点站别名），
 *       不是错值；报告里明确写出来。</li>
 *   <li>不选 {@code SimplifiedRoutePlatform.getDestination()}：它是**唯一产地之上的公共入口**，
 *       连铁路标志牌（{@code showTo = false} 也画目的地列表）都会一起被改，射程更大。</li>
 * </ul>
 *
 * <h2>线程 / 缓存</h2>
 * 本 handler 跑在 {@code MainRenderer.WORKER_THREAD}（贴图在 worker 上生成）。
 * 它只读 {@link PlatformNameMask} 里那张 {@link java.util.concurrent.ConcurrentHashMap} 小表、
 * 只做字符串切分 ⇒ 无共享可变状态、不加锁。
 * <p>★ <b>贴图缓存</b>：4.0.5 的键是
 * {@code "direction_arrow_%s_%s_%s_%s_%s_%s_%s_%s_%s_%s"}（平台 id + 5 个参数，
 * <b>不含文字</b>）⇒ 同一个平台第一次生成完就缓存住了。改别名后要立刻生效，
 * 用户这边走的是「重新进世界 / {@code DynamicTextureCache.refresh()}」那条既有路径
 * （本模组不改 MTR 的缓存实现）。
 *
 * <p>handler 里只出现 {@code String[]} 与 {@link PlatformNameMask}，**不出现任何 MTR 类型**
 * ⇒ 编译期不需要 MTR 依赖；{@code @Pseudo} + {@code PsdDoorMixinPlugin} 门禁保证
 * 「没装 MTR4 / MTR4 换了包名」时安静跳过、绝不崩游戏。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.client.RouteMapGenerator")
public abstract class Mtr4RouteArrowAliasMixin {

    /** 「往X / to X」的 X（= {@code insertTranslation} 的第 3 个实参那个 varargs 数组的第 0 格）。 */
    @ModifyArg(
            method = "generateDirectionArrow(JZZLorg/mtr/mod/data/IGui$HorizontalAlignment;ZFFIII)"
                    + "Lorg/mtr/mapping/holder/NativeImage;",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/mtr/mod/data/IGui;insertTranslation("
                            + "Lorg/mtr/mod/generated/lang/TranslationProvider$TranslationHolder;"
                            + "Lorg/mtr/mod/generated/lang/TranslationProvider$TranslationHolder;"
                            + "I[Ljava/lang/String;)Ljava/lang/String;",
                    ordinal = 2),
            index = 3,
            require = 1,
            remap = false)
    private static String[] smoothlift$aliasTerminusOnArrow(String[] args) {
        if (args != null && args.length > 0 && args[0] != null) {
            args[0] = PlatformNameMask.aliasOrSame(args[0]);
        }
        return args;
    }
}
