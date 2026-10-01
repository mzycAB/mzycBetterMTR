package smooth.lift.client;

/**
 * 【10-01 二次订正】「名字掩码」：把 {@code %} 与它包裹的那一段抹掉。
 *
 * <h2>需求（用户点名）</h2>
 * MTR 的名字里可以写 {@code %} 把一段文字包起来（例：{@code 5%A%AB}）。
 * 这一段**在任何「被展示出来的」地方都不显示**；只有一处例外 —— MTR 自己那套
 * **站台 / 侧线 / 车站 / 车厂 / 线路设置界面**里照原样显示（那是用户唯一要看到、
 * 要编辑这个标记的地方）。于是：
 * <ul>
 *   <li>世界里画出来的一律隐藏：屏蔽门玻璃上的**线路图圆圈**、站名标志牌、
 *       PIDS 乘客资讯屏；报站讲述人、聊天框字幕、Web 地图，同样隐藏；</li>
 *   <li>设置界面的名字输入框照原样显示（{@code PlatformScreen} / {@code SidingScreen}
 *       继承 {@code SavedRailScreenBase}；{@code EditStationScreen} / {@code EditDepotScreen} /
 *       {@code EditRouteScreen} 继承 {@code EditNameColorScreenBase}）——
 *       否则一保存就把标记弄丢（{@code saveData()} 是无条件写回输入框里的文字的）。</li>
 * </ul>
 *
 * <p>★ 订正记录（为什么改在「名字的源头」掩）：首版只拦了「PIDS 渲染读站台名」那一条路径，
 * 结果用户看到屏蔽门上**线路图圆圈**里照旧显示 {@code %%} —— 圆圈画的是**车站名**，
 * 走 {@code RouteMapGenerator.getStationName(long)} / {@code Station.getName()}，
 * 与站台名是两条互不相干的路径。现在改成在源头掩（MTR4 一处
 * {@code NameColorDataBase.getName()} 就盖住全部名字），再在设置界面开「原样窗口」放行。
 *
 * <h2>{@code %} 的语义：成对的开关（toggle）</h2>
 * 从左到右扫，每遇到一个 {@code %} 就翻转一次「隐藏中」状态，隐藏中的字符不输出
 * （{@code %} 本身也不输出）。于是：
 * <pre>
 *   5%A%AB   → 5AB      （5 可见，% 开，A 隐藏，% 关，A B 可见）
 *   AB%C%DE  → ABDE
 *   5%AB     → 5        （落单的 % 之后整段按隐藏处理 —— 写得不成对时宁可少显示，
 *                        也不要漏出一段本该藏起来的字）
 *   5A       → 5A       （不含 % ⇒ 原样返回**同一个实例**，零分配）
 *   null/""  → 原样
 * </pre>
 *
 * <p>★ 为什么不用「找一对 {@code %} 再删中间」那套非贪心配对：那种写法在
 * {@code 5%A%B%C} 这种不配对的输入上会给出 {@code 5B%C}（漏出一个 {@code %}），
 * 而 toggle 给出 {@code 5B}，与「成对包裹」的直觉一致、且与 {@code 5%A%AB} 同解。
 * 判据只有一条：**序列里 {@code %} 的个数决定隐藏的区间**，没有第二种解释。
 *
 * <p>★ 幂等：掩过的值再掩必须还是它自己（{@code MtrDwellAccess} 的 MTR4 出口会在
 * getter 已被 mixin 掩过之后再掩一次）。掩码输出里不含 {@code %} ⇒ 天然幂等。
 */
public final class PlatformNameMask {

    private PlatformNameMask() {
    }

    /** 掩码字符：成对出现，包在中间的那一段不显示。 */
    private static final char MARK = '%';

    /**
     * 「原样窗口」的截止时刻（{@code System.currentTimeMillis()} 基准）；{@code 0} = 关闭。
     *
     * <p>★ 为什么用**表**而不是「真值 + 布尔开关」：设置界面的 {@code render} / {@code tick2}
     * 每帧（≈16ms）、每刻（50ms）都会刷新一次，所以界面开着时它一直开着；界面一关，
     * 最长 {@value #RAW_WINDOW_MS}ms 后自动过期。**不需要**去 {@code onClose2} 里配对关掉
     * ⇒ 崩溃 / 断线 / 换世界都不会把窗口漏成常开（漏成常开就意味着「到处都显示 %%」）。
     */
    private static volatile long rawUntilMillis;

    /** 原样窗口的长度：够覆盖「当前这一帧 / 这一刻」的整条调用链，又能很快自愈。 */
    private static final long RAW_WINDOW_MS = 500L;

    /** 开一次原样窗口（设置界面的每个每帧 / 每刻入口都调它）。 */
    public static void enterRawWindow() {
        rawUntilMillis = System.currentTimeMillis() + RAW_WINDOW_MS;
    }

    /**
     * 原样窗口现在开着吗。
     *
     * <p>够快：绝大多数时候 {@code rawUntilMillis == 0}，一次 volatile 读 + 一次比较就返回，
     * **不调时钟**；只有窗口开过之后才会走一次 {@code currentTimeMillis()}，过期即归零。
     */
    public static boolean rawWindowOpen() {
        long until = rawUntilMillis;
        if (until == 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            rawUntilMillis = 0L;
            return false;
        }
        return true;
    }

    /**
     * 把 {@code %} 包裹的那一段抹掉（见类注释的语义表）。
     *
     * @param name MTR 给的名字（站台 / 车站 / 线路 / 车厂 / 侧线共用这一个入口）；
     *             {@code null} / 空串 / 不含 {@code %} 时**原样返回入参**
     *             （同一个实例，调用方可以放心按引用比较做零分配捷径）
     * @return 该显示的名字；**原样窗口开着时（也就是在设置界面里）原样返回**，
     *         见 {@link #enterRawWindow()}
     */
    public static String mask(String name) {
        if (rawWindowOpen()) {
            return name;
        }
        if (name == null || name.isEmpty() || name.indexOf(MARK) < 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean hidden = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == MARK) {
                hidden = !hidden;
                continue;
            }
            if (!hidden) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
