package smooth.lift.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【1.37.hotfix-3】名字里的 {@code %} = <b>成对开关</b>：<b>两个 {@code %} 之间的字符不显示，后面的照常显示</b>。
 *
 * <h2>需求（用户点名，2026-10-30 第三次订正）</h2>
 * <pre>
 *   C%A%        → C        （两个 % 之间的 A 藏掉，后面的……没有后面）
 *   C%A%B       → CB       ★ 第二个 % 之后照常显示（这正是本次订正要修的）
 *   %A%BC       → BC
 *   a%b%c%d%e   → ace      （成对翻转，可以写多组）
 *   C%A         → C        （只有一个 % ⇒ 它后面全算「未闭合的隐藏段」）
 *   C           → C        （不含 % ⇒ 原样返回**同一个实例**，零分配）
 *   null / ""   → 原样
 * </pre>
 *
 * <h2>★ 与上一版（10-03 五改，第一版 hotfix 用的就是它）的差别</h2>
 * 上一版把**第一个** {@code %} 当「本名 / 别名」的分隔符，它后面的一切（含还写着的 {@code %}）
 * 全部不显示 ⇒ {@code C%A%B} 会变成 {@code C}，把 {@code B} 一起吃掉。
 * 用户点名要的是**成对开关**（{@code %…%} 包住的那一段才藏），所以本版恢复 toggle 语义：
 * 遇到 {@code %} 就翻转「隐藏中」，只有隐藏中的字符被丢掉，其余**全部照原样保留**。
 *
 * <h2>本名 / 别名怎么来（屏蔽门玻璃上「往X」那一处要用别名）</h2>
 * <ul>
 *   <li><b>本名</b> = {@link #mask} 的输出（藏掉 {@code %…%} 之后剩下的那串）——
 *       线路图圆圈、站名标志牌、PIDS、报站讲述人、聊天字幕全都用它；</li>
 *   <li><b>别名</b> = <b>第一段被藏起来的内容</b>（第一个 {@code %} 到与它配对的 {@code %} 之间；
 *       没有配对的 {@code %} 就一直到串尾）。{@code 香港|HongKong%市区|CityCenter} 的别名是
 *       {@code 市区|CityCenter} —— 与上一版结果一致，老存档显示不变；</li>
 *   <li>唯一用别名的地方仍是屏蔽门（PSD / APG 玻璃）上那个终点站方向箭头的「往X / to X」
 *       （{@code Mtr4RouteArrowAliasMixin}）；</li>
 *   <li>只有 MTR 自己那套**设置界面**里照原样显示（{@code 本名%别名} 两段一起看得见），
 *       否则一保存就把标记弄丢（那些界面的 {@code saveData()} 是无条件写回输入框文字的）。</li>
 * </ul>
 *
 * <h2>★ 别名怎么送到屏蔽门那一处（为什么要一张「显示名 → 别名」表）</h2>
 * 屏蔽门玻璃上那句「往X」的 X 来自 {@code org.mtr.core.data.SimplifiedRoutePlatform.getDestination()}，
 * 而它**在服务端就拼好了**（{@code Route.getDestination()} → {@code Station.getName()}）并随
 * 数据包发到客户端。本模组是**客户端模组**：单机里同一个 JVM 的 {@code getName()} 也被本类掩过
 * ⇒ 到达箭头时那个字符串**已经不含 {@code %}**，从字符串本身再也推不出别名。
 * 所以 {@link #mask} 在掩掉的同时把「显示名 → 别名」记进 {@link #ALIAS_BY_SHOWN}
 * （名字的数量级是几十~几百条，表很小）；箭头那边用 {@link #aliasOrSame} 查。
 * <p>
 * 两条路都覆盖：字符串里**还带** {@code %}（服务端没装本模组 / 用户自定义目的地）⇒ 直接按
 * 成对规则取第一段隐藏内容；已经掩过（单机、或本模组装在同一 JVM）⇒ 查表。两条路都推不出 ⇒ 原样返回
 * （行为与加这个功能之前**完全一样**）。
 *
 * <h2>★ 幂等</h2>
 * 掩过的值再掩必须还是它自己（{@code MtrDwellAccess} 的两个出口会在 getter 已被 mixin 掩过之后
 * 再掩一次）。掩码输出里不含 {@code %}（只要它来自本类）⇒ 天然幂等。
 *
 * <h2>原样窗口（设置界面）</h2>
 * {@link #enterRawWindow()} 开一个 500ms 的「原样窗口」：MTR 自己的设置界面里每帧 / 每刻都会调它，
 * 所以界面开着时窗口一直开着；界面一关最长 500ms 后自动过期 ⇒ 崩溃 / 断线 / 换世界都不会漏成常开。
 */
public final class PlatformNameMask {

    private PlatformNameMask() {
    }

    /** 开关标记：每遇到一个就翻转一次「隐藏中」（成对使用）。 */
    private static final char MARK = '%';

    /**
     * 「显示名 → 别名」小表（见类注释：服务端已经拼好的目的地字符串里没有 {@code %}，
     * 只能靠掩码那一刻顺手记下来）。
     *
     * <p>★ 用 {@link ConcurrentHashMap}：{@link #mask} 既在渲染线程被调，也可能在
     * MTR 生成线路图贴图的那个 worker 线程被调（{@code MainRenderer.WORKER_THREAD}）。
     */
    private static final Map<String, String> ALIAS_BY_SHOWN = new ConcurrentHashMap<>();

    /** 表的自我保护上界：名字数量级是几百，真到这么多说明有别的东西在灌 —— 直接清空重来。 */
    private static final int ALIAS_TABLE_MAX = 8192;

    /**
     * 「原样窗口」的截止时刻（{@code System.currentTimeMillis()} 基准）；{@code 0} = 关闭。
     *
     * <p>★ 为什么用**表**而不是「真值 + 布尔开关」：设置界面的 {@code render} / {@code tick2}
     * 每帧（≈16ms）、每刻（50ms）都会刷新一次，所以界面开着时它一直开着；界面一关，
     * 最长 {@value #RAW_WINDOW_MS}ms 后自动过期。**不需要**去 {@code onClose2} 里配对关掉
     * ⇒ 崩溃 / 断线 / 换世界都不会把窗口漏成常开（漏成常开就意味着「到处都显示原名」）。
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
     * 把 {@code %} <b>成对包住</b>的那几段切掉，其余照原样保留（见类注释的语义表）。
     *
     * <p>实现就是一次线性扫描 + 一个「隐藏中」开关：遇到 {@code %} 翻转并跳过它本身，
     * 隐藏中的字符丢掉，其它字符原样抄进结果。所以
     * {@code C%A%} → {@code C}、{@code C%A%B} → {@code CB}、{@code a%b%c%d%e} → {@code ace}。
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
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.indexOf(MARK) < 0) {
            return name;
        }

        final StringBuilder shown = new StringBuilder(name.length());
        boolean hidden = false;
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == MARK) {
                hidden = !hidden;
                continue;
            }
            if (!hidden) {
                shown.append(c);
            }
        }
        final String result = shown.toString();

        final String alias = firstHiddenSegment(name);
        if (!alias.isEmpty()) {
            if (ALIAS_BY_SHOWN.size() >= ALIAS_TABLE_MAX) {
                ALIAS_BY_SHOWN.clear();
            }
            ALIAS_BY_SHOWN.put(result, alias);
        }
        return result;
    }

    /**
     * 第一段被 {@code %} 藏起来的内容（= 别名）；没有就返回空串。
     *
     * <p>与 {@link #mask} 共用同一套成对规则：从第一个 {@code %} 开始收集，遇到下一个 {@code %}
     * 就收工；只有一个 {@code %} 时一直收到串尾。{@code mask} 与 {@link #aliasOrSame} 都走这里，
     * 两边口径才不会错位。
     */
    private static String firstHiddenSegment(String name) {
        boolean hidden = false;
        boolean closed = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == MARK) {
                if (hidden) {
                    closed = true;
                }
                hidden = !hidden;
                continue;
            }
            if (hidden && !closed) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 把一个「经过名字 getter 的字串」换成它的**别名**（第一段被藏起来的内容）；推不出来就原样返回。
     *
     * <p>唯一调用方 = 屏蔽门（PSD / APG 玻璃）上那句「往X / to X」的目的地
     * （{@code Mtr4RouteArrowAliasMixin}）。判据两条（见类注释）：
     * <ol>
     *   <li>字串里**还带** {@code %} ⇒ 直接取第一段隐藏内容
     *       （服务端没装本模组时目的地是原样名字，走这一条）；</li>
     *   <li>否则查 {@link #ALIAS_BY_SHOWN}（名字 getter 已经被掩过，走这一条）。</li>
     * </ol>
     * 两条都不成立 ⇒ 原样返回入参（**同一个实例**）。
     *
     * <p>★ 只在这里**读**表、绝不写表：写表只在 {@link #mask} 那一处（一个入口，才不会出现
     * 「两边记的键不一样」这种只在运行期才看得见的错位）。
     */
    public static String aliasOrSame(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.indexOf(MARK) >= 0) {
            final String alias = firstHiddenSegment(name);
            return alias.isEmpty() ? name : alias;
        }
        final String alias = ALIAS_BY_SHOWN.get(name);
        return alias != null ? alias : name;
    }

    /** 换世界 / 断线：把「显示名 → 别名」表清掉（{@code PsdDoorTracker.clear()} 那一路调）。 */
    public static void clear() {
        ALIAS_BY_SHOWN.clear();
        rawUntilMillis = 0L;
    }
}
