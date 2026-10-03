package smooth.lift.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 【1.15 · 第八轮】MTR **时刻表（站台停留时长）**的只读跨版本访问层 —— 「不要靠猜」的那一半。
 *
 * <h2>为什么要有这个类</h2>
 *
 * 用户要的是「关门提示音的**结尾**正好落在门关上那一刻」（见 {@code PsdChimePlayer} 类注释）。
 * 素材 10.8 秒、门自己只走 4 秒，所以必须在门**开始关之前** 6.8 秒起播 —— 而 MTR
 * **没有任何关门预警信号**，这个提前量只能自己预测。
 *
 * <p>原来的预测是**实测学习**：记住这一扇门上一轮「开门 → 全关」跑了多少 tick，下一轮照着排。
 * 学习有两个先天缺陷：
 * <ol>
 *   <li><b>第一次永远没参照</b> —— 刚进世界、或刚走到这个站，第一轮只能退化成「只嘀嘀」；</li>
 *   <li><b>跨站借用会串味</b> —— 停站时长是**每个站台各自的**属性（A 站 10 秒、B 站 20 秒），
 *       拿 A 站学到的值去排 B 站的提前量，必然错一截。</li>
 * </ol>
 * 而**这个数字 MTR 本来就知道**：它是站台数据里的一项，客户端手里就有。本类把它读出来。
 *
 * <h2>★ 读的不是「站台停留时长」，而是「开门 → 全关」的周期</h2>
 *
 * 朴素做法是「读停站时长，减素材时长」—— 那样会**差 1.5 秒以上**。真正的关系在
 * {@code org.mtr.core.data.Vehicle.simulateStopped} 的字节码里（MTR 4.0.5，已逐条核过）：
 *
 * <pre>
 *   D  = PathData.getDwellTime()            // 这一个停靠点的目标停留时长（ms）
 *                                            //  ← 来源见下：站台数据里的 dwellTime
 *   门开：elapsedDwellTime >= 1000 （DOOR_DELAY）       才开始开 ——「门全开」≈ 停稳 + 1000ms
 *   关门：elapsedDwellTime >= max(D/2, D - 4200)       才发车 ⇒ 门开始关
 *                                            4200 = DOOR_MOVE_TIME(3200) + DOOR_DELAY(1000)
 *                                            另一半 D/2 是下限：停站太短时至少留一半时间开门
 *   所以「门开始关 → 计划发车」= 4200ms，而「全开 → 门开始关」= max(D/2, D-4200) - 1000
 * </pre>
 *
 * <p>⇒ <b>开门那一瞬到门全关</b> = {@code max(D/2, D-4200) - 1000 + 门程}。
 * {@link #cycleTicksForDwell} 就是这个式子。
 *
 * <h2>★ 「站台数据里的 dwellTime」确实就是那个 D</h2>
 *
 * {@code SidingPathFinder} 构造每一段 {@code PathData} 时（字节码）：
 * <pre>
 *   savedRailBaseId = endSavedRail.getId()
 *   dwellTime       = (endSavedRail instanceof Platform) ? platform.getDwellTime() : 1L
 * </pre>
 * 即轨道线路的停靠点 D **就是** {@code Platform.getDwellTime()}。所以这里读站台即可，
 * 不必去翻列车的 {@code immutablePath}。
 *
 * <p><b>⚠ 反面教材（差点踩进去）</b>：{@code Platform.getDwellTime()} 之外，
 * MTR 里还有 {@code RouteStation.getDwellTime()}、{@code PathData.getDwellTime()}，
 * 而 {@code Vehicle.eleapedDwellTime} 真正累加的那个 D 只来自 {@code PathData}。
 * 全 jar 扫过一遍：{@code Platform.getDwellTime()} 在 MTR4 里的调用者只有
 * {@code SidingPathFinder}（建路径）与 {@code DirectionsFinder}（出行建议），
 * **不是**「另一个无关的站台属性」，读它就是对的那一个。
 *
 * <h2>怎么找到「这扇门属于哪个站台」</h2>
 *
 * MTR 的屏蔽门方块实体（{@code BlockPSDAPGDoorBase$BlockEntityBase}）里**只有**门值，
 * 不含任何站台引用 —— 所以门 → 站台只能**按几何**认。站台的「中点坐标」由 MTR 自己算好放在
 * {@code Data.platforms} 里（{@code Platform.getMidPosition()}，{@code Data.sync()} 里
 * 还顺手塞进了 {@code platformIdToPosition}），于是：
 *
 * <ol>
 *   <li>门到站台**中轴线段**的垂直距离最近的（拿不到两端点时退回「到中点」的水平距离）；</li>
 *   <li>横向必须落在 {@link #MAX_LATERAL}（中轴线段模式）/ {@link #MAX_HORIZONTAL}（中点模式）格以内；
 *       纵向必须落在 {@link #MAX_DY} 格以内，避免把楼上/楼下的站台认成同一个；</li>
 *   <li>优先挑**停留时长有效**（&gt; 0）的那一个 —— MTR 里没配过的站台读出来是 0，拿它算周期只会算出 -1；</li>
 *   <li>认不出来（读不到 / 一个候选都没有）⇒ 返回 {@code -1}，调用方**原样退回实测学习**。</li>
 * </ol>
 *
 * <p>【第十二轮】每次「选中的站台变了」除了报选中项，还会报**被上限挡掉的那个最近的站台**；
 * 进入世界后的第一份日志里还会把**全部**站台（车站名 + 坐标 + 停留时长）列成一张表。
 * 这两样是给「改了停留时间却读不到」用的（详见 {@link #dumpPlatforms}）。
 *
 * <h2>反射与降级</h2>
 *
 * 本模组的 build.gradle **不依赖 MTR**，编译期没有任何 MTR 类型 —— 所以这里全程反射
 * （与 {@link MtrLiftAccess} 同一套写法）。<b>任何一步失败都只是「读不到」</b>：
 * 返回 {@code -1}、打一条日志、之后彻底静默，播放器照旧走实测学习那条路，
 * 行为与加这个类之前**完全一样**。绝不会把反射异常抛进客户端 tick。
 *
 * <p>★ 双版本支持（2026-10 补充）：原来只绑 MTR4（{@code org.mtr.*，4.0.x}），装 MTR3
 * （{@code mtr.*，3.2.2-hotfix-1}）时 {@code platformIdAt} 会返回哨兵 {@link #PLATFORM_ID_NONE}
 * ⇒ {@code PsdDoorTracker} 的 runKey 回落成连通串 ⇒ {@code PsdChimePlayer} 在「读配置之前」就把
 * 站台讲述人 / 进站讲述人 continue 掉，整段静默。这一版把 MTR3 也当成与 MTR4 并列的第二条
 * 反射绑定路径接进来（同样全程反射、同样「任何一步失败只打日志不打异常」）。两条路径共用同一套
 * 对外接口，调用方无感；判据阈值（{@link #MAX_LATERAL} / {@link #MAX_HORIZONTAL} / {@link #MAX_DY}、
 * 「优先挑 dwellTime &gt; 0 的」）也完全同构。历史说明：本类最初（1.15 第八轮）确实只为 MTR4 而写，
 * MTR3 那句「没有 org.mtr.core.data.Platform ⇒ 整体停用」已作废，改为上面的双版本事实。
 */
public final class MtrDwellAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * MTR4 {@code Vehicle.DOOR_DELAY} = 1000ms（常量池实测 {@code Integer 1000}）。
     * 从「门该开了」到门真的开始动之间的延迟。
     */
    public static final int DOOR_DELAY_MS = 1000;

    /**
     * MTR4 {@code Vehicle.DOOR_MOVE_TIME} = 3200ms（常量池实测 {@code Integer 3200}）。
     * MTR 认为门从全开到全关要走这么久（用它倒推「该提前多久发关门指令」）。
     */
    public static final int DOOR_MOVE_TIME_MS = 3200;

    /** 关门提前量 = {@code DOOR_MOVE_TIME + DOOR_DELAY}（字节码里的 {@code D - 4200} 那个 4200）。 */
    public static final int CLOSE_LEAD_MS = DOOR_MOVE_TIME_MS + DOOR_DELAY_MS;

    /**
     * 门程（门开始关 → 全关）的**兜底** tick 数 = 80。
     *
     * <p>来路：{@code BlockPSDAPGDoorBase$BlockEntityBase.tick(F)} 里
     * {@code doorValue} 每次走 {@code partialTick*20/3200*2} = 1/80，两端夹在 0..1
     * ⇒ 若按「每 tick 走一格」算是 80 tick = 4000ms。但**实测不是这个数**：
     * {@code PsdChimePlayer} 自己学到的 {@code globalTravelTicks} 稳定在 ~29 tick（1450ms）。
     *
     * <p>★★【1.15 · 第十二轮】两者差 2.7 倍的原因是 {@code tick(F)} 挂在**渲染器每帧**
     * 那条路径上（mixin 挂的 {@code getDoorValue()} 就是 {@code RenderPSDAPGDoor.render}
     * 每帧调一次的那个口）—— 也就是说**门速跟帧率走**，根本不存在一个「正确常数」。
     * 所以这个 80 只当**极端兜底**（它偏**乐观**：算出来的周期比真实的长），
     * **一旦实测学到就立刻改用它**（见 {@code PsdChimePlayer.planClose} 里的
     * {@code globalTravelTicks > 0 ? globalTravelTicks : DEFAULT_TRAVEL_TICKS}）。
     * 想收窄这个兜底，得先在游戏里量「不同帧率下的门程」，别凭字节码改。
     */
    public static final int DEFAULT_TRAVEL_TICKS = 80;

    /**
     * 认站台时允许的纵向偏差（格）。
     *
     * <p>取 {@value} 的理由：屏蔽门方块通常紧贴在站台方块**上方或旁边**一格（±1），
     * 而站台的「中点」是站台方块自己的坐标；给到 {@value} 是留出半格楼梯 / 装饰层的余量。
     * 再大就会把楼上（高架站）或楼下（地下站）**不同层**的站台拉进来当候选 ——
     * 那种错认会安静地读到一个错的停站时长，比读不到更坏。
     */
    private static final double MAX_DY = 8.0;

    /**
     * 【1.15 · 第十轮】门到站台**中轴线段**的横向容许偏差（格）—— 正常布局下的主判据。
     *
     * <p>★ 为什么改成「到线段」而不是「到中点」：站台是**长条**。用中点距离挑「最近的那个站台」
     * 有个致命形态 —— 门在站台**这一端**、而**对面方向**那个站台的中点恰好更近，
     * 于是安静地读到**隔壁站台**的停站时长（那两个站台的停站时长经常不一样）。
     * 改用「门到站台两端点连线的垂直距离」之后，门踩在哪条站台上就是哪条，
     * 与站台多长、门挂在哪一端都无关。
     *
     * <p>取 {@value} 的理由：屏蔽门方块就建在站台方块上/紧邻（垂直距离 ≈ 0~1 格），
     * 留到 {@value} 格已经覆盖「门建在宽站台外沿」这类布局。**故意取得小**：
     * 宁可「读不到」（上游会原样退回「本扇门实测」，代价只是第一次停站没人声），
     * 也不要「安静地读到隔壁站台」（那会让停站时长怎么改都没用，且看不出原因）。
     */
    private static final double MAX_LATERAL = 4.0;

    /**
     * 【1.15 · 第十轮】退路模式（读不到站台两端点时）允许的**中点**水平距离上限（格）。
     *
     * <p>★ 以前这里**根本没有上限**：「横向最近的那个站台」全靠一个无界 {@code min} 来挑 ⇒
     * 屏蔽门离任何站台都很远时（站台还没同步完 / 门挂在别处 / 玩家在另一个车站），
     * 会安静地读到**几百格以外**那个车站的停站时长，于是「改这一站的停站时间完全没用」。
     * 加上限之后，够不着就返回「读不到」，行为可解释。
     *
     * <p>取 {@value} 的理由：长条站台的门到**中点**最多是站台长度的一半，
     * {@value} 格够覆盖 128 格的超长站台，又能挡住「几百格以外那个站」这种假匹配。
     */
    private static final double MAX_HORIZONTAL = 64.0;

    /**
     * 【09-28 续 6】「这个 MTR 站台 id 还没认出来」的**唯一哨兵**。
     *
     * <h2>为什么必须专门给一个值，不能用 0 / -1 当哨兵</h2>
     * MTR4 的 id 是 {@code new Random().nextLong()}（反汇编
     * {@code org.mtr.core.generated.data.NameColorDataBaseSchema} 的构造器，铁证：
     * {@code aload_0; new java/util/Random; invokespecial <init>; invokevirtual nextLong; putfield id:J}）
     * ⇒ 它均匀覆盖**全部 64 位**，于是：
     * <ul>
     *   <li>**站台 id 有一半是负数**（不是异常值，是常态）；</li>
     *   <li>{@code 0} 和 {@code -1} 同样都是**合法**的 id。</li>
     * </ul>
     * 以前到处用 {@code > 0} / {@code <= 0} 当「认到 / 没认到」，后果是
     * **凡是 id 为负的站台一律被当成「认不到」**：
     * <ol>
     *   <li>{@link PsdDoorTracker} 不给它站台身份 ⇒ 那一排门的 {@code runKey} 回落成连通串；</li>
     *   <li>{@code PsdChimePlayer} 的进站报站/到站播报在「读配置之前」就被挡住 {@code continue}；</li>
     * </ol>
     * 现场 LOG12 就长这样（同一个车站、两个方向、同一秒）：
     * <pre>
     *   屏蔽门 @[17,29,277] 认到   MTR 站台 id= 4708666155642935879 ⇒ 正常播放
     *   屏蔽门 @[16,29,265] 认不到 MTR 站台   id=-2292798687084117839 ⇒ 整串静默
     * </pre>
     * 用户报的「4 号线开往江苏北路的进站播报正常，开往南区南方向的就没有」就是它 ——
     * 两个方向的站台各有一个随机 id，正的那个能用、负的那个被哨兵判死。
     *
     * <h2>为什么选 {@code Long.MIN_VALUE}</h2>
     * 因为 id 空间是**全域**的，任何基本类型取值都可能是一个真 id（连 {@code Long.MIN_VALUE}
     * 本身的概率也是 2^-64，只是极小）。选它的理由：
     * <ul>
     *   <li>它**不是** 0/-1 这种「顺手」的值，逼着每个判断点显式写
     *       {@link #isPlatformKnown(long)}，语义一眼可读；</li>
     *   <li>{@code PsdDoorTracker.platformKey} 用的偏移量本来就是 {@code Long.MIN_VALUE}
     *       ⇒ 哨兵在**编码后**恰好落在 0，与「连通串锚点」也是同一个命名空间里最不易撞的一点；</li>
     *   <li>万一真有站台的 id 正好是 {@code Long.MIN_VALUE}（概率 2^-64），它的表现只是
     *       「这一个站台没有报站」—— 与修复前**所有负 id 站台**的表现相同，属于 fail-safe，
     *       不会错认到别的站台上去。</li>
     * </ul>
     * ★★ 铁规矩：**站台 id 一律原样透传**（可为负），只在「有没有认出来」这一件事上比哨兵。
     * 回归脚本里有一条「不许再用 {@code > 0} / {@code <= 0} 判站台 id」的断言盯着这件事。
     */
    public static final long PLATFORM_ID_NONE = Long.MIN_VALUE;

    /** 这个站台 id 是不是「认出来了」的（唯一的判据，见 {@link #PLATFORM_ID_NONE}）。 */
    public static boolean isPlatformKnown(long platformId) {
        return platformId != PLATFORM_ID_NONE;
    }

    // ------------------------------------------------------------------
    // 反射绑定（只做一次）
    // ------------------------------------------------------------------

    private static boolean initialised;
    private static boolean bound;
    /** 反射链路出问题时只打一条日志，之后彻底静默（避免每 tick 刷屏）。 */
    private static boolean broken;
    /** 「站台集合是空的」只提示一次。 */
    private static boolean warnedEmpty;
    /** 首次成功读到站台集合时打一条（只打一次）：日志里能一眼看出客户端到底有没有站台数据。 */
    private static boolean loggedCount;
    /** 【第十轮】「一个站台都够不着」只提示一次。 */
    private static boolean warnedNoMatch;
    /**
     * 【第十轮】记住上一条日志里「选中了哪个站台、读到多少」——
     * 只在**选择发生变化**时打日志，既能看见「换站了」，又不会每 tick 刷屏。
     */
    private static String lastPick = "";

    /**
     * 当前绑定的 MTR 大版本：{@link #MODE_NONE}=未绑定，{@link #MODE_MTR4}=MTR4（org.mtr.*），
     * {@link #MODE_MTR3}=MTR3（mtr.*，3.2.2-hotfix-1）。两种实现互不共存（运行期只可能装一种），
     * 探测一次后缓存。所有「对外只读接口」都按这个 mode 分发到下面两条并列的反射绑定，
     * 调用方完全无感（签名 / 语义不变）。
     */
    private static int mode;
    private static final int MODE_NONE = 0;
    private static final int MODE_MTR4 = 4;
    private static final int MODE_MTR3 = 3;

    // ------------------------------------------------------------------
    // MTR3 绑定（mtr.* 前缀，3.2.2-hotfix-1）—— 与 MTR4 并列的第二条路径
    //
    //   ★ 为什么要有它：原来只绑 MTR4，装 MTR3 时 platformIdAt 返回哨兵
    //   PLATFORM_ID_NONE ⇒ PsdDoorTracker 的 runKey 回落成连通串 ⇒
    //   PsdChimePlayer 在「读配置之前」就把站台讲述人/进站讲述人 continue 掉 ⇒ 全静默。
    //   现在把 MTR3 也当成一等公民绑进来（同样是全程反射、同样「任何一步失败只打日志不抛异常」）。
    //
    //   ★ 与 MTR4 的关键差异（已在 _mtr322 里逐条 javap 核过）：
    //     1. 站台集合是 {@code mtr.client.ClientData} 的 **public static final** 字段
    //        （PLATFORMS / STATIONS / ROUTES / SCHEDULES_FOR_PLATFORM / DATA_CACHE），
    //        没有 MTR4 那种 MinecraftClientData.getInstance()；
    //     2. {@code SavedRailBase} **没有** position1/position2 字段 ⇒ 认站台只能用「中点模式」
    //        （MAX_HORIZONTAL / MAX_DY），不走高版本的「到中轴线段」模式（MAX_LATERAL）；
    //     3. id / name 是 {@code NameColorDataBase} 的 **字段**（不是 getId()/getName()）；
    //     4. getMidPos() 返回的是 Minecraft 的 BlockPos（mid 坐标用 getX/getY/getZ）；
    //     5. platform→station 没有现成链接，用几何：station.inArea(中点X, 中点Z) 命中即所属车站；
    //     6. 时刻表在 {@code SCHEDULES_FOR_PLATFORM}，终点站用
    //        {@code ClientCache.DATA_CACHE.getFormattedRouteDestination(Route, int, String)}
    //        （站台显示屏 PIDS 用的同一个函数）。
    // ------------------------------------------------------------------

    /** 【MTR3】{@code mtr.client.ClientData.PLATFORMS}（public static final Set<Platform>）。 */
    private static Field mtr3PlatformsField;
    /** 【MTR3】{@code mtr.client.ClientData.STATIONS}（public static final Set<Station>）。 */
    private static Field mtr3StationsField;
    /** 【MTR3】{@code mtr.client.ClientData.SCHEDULES_FOR_PLATFORM}（Map<Long,Set<ScheduleEntry>>）。 */
    private static Field mtr3SchedulesField;
    /** 【MTR3】{@code mtr.client.ClientData.ROUTES}（public static final Set<Route>，站台→线路用）。 */
    private static Field mtr3RoutesField;
    /** 【MTR3】{@code mtr.client.ClientData.DATA_CACHE}（public static final ClientCache，拼终点站）。 */
    private static Field mtr3DataCacheField;
    /** 【MTR3】{@code SavedRailBase.getMidPos()}（public，返回 BlockPos）。 */
    private static Method mtr3GetMidPos;
    /** 【MTR3】{@code SavedRailBase.getDwellTime()}（public int）。 */
    private static Method mtr3GetDwellTime;
    /** 【MTR3】{@code NameColorDataBase.id}（public final long **字段**，不是 getId()）。 */
    private static Field mtr3IdField;
    /** 【MTR3】{@code NameColorDataBase.name}（public String **字段**，不是 getName()）。 */
    private static Field mtr3NameField;
    /** 【MTR3】{@code AreaBase.getCenter()}（public，返回 BlockPos，几何找车站备用）。 */
    private static Method mtr3GetCenter;
    /** 【MTR3】{@code AreaBase.inArea(int,int)}（public，platform 中点是否落在车站范围内）。 */
    private static Method mtr3InArea;
    /** 【MTR3】BlockPos.getX/getY/getZ（getReturnType 拿，避免硬编码 MC 映射名 class_2338）。 */
    private static Method mtr3PosGetX;
    private static Method mtr3PosGetY;
    private static Method mtr3PosGetZ;
    /** 【MTR3】{@code Route.platformIds}（public final List<RoutePlatform>，站台→线路用）。 */
    private static Field mtr3RoutePlatformIdsField;
    /** 【MTR3】{@code Route$RoutePlatform.platformId}（public final long）。 */
    private static Field mtr3RoutePlatformIdField;
    /** 【MTR3】{@code ScheduleEntry.arrivalMillis}（public final long，到站时刻，epoch ms）。 */
    private static Field mtr3ArrivalMillisField;
    /** 【MTR3】{@code ScheduleEntry.routeId}（public final long）。 */
    private static Field mtr3ScheduleRouteIdField;
    /** 【MTR3】{@code ScheduleEntry.currentStationIndex}（public final int）。 */
    private static Field mtr3ScheduleStationIndexField;
    /** 【MTR3】{@code ClientCache.getFormattedRouteDestination(Route, int, String)}（PIDS 同函数）。 */
    private static Method mtr3GetFormattedDest;

    /** {@code MinecraftClientData.getInstance()}（MTR4）。 */
    private static Method getInstance;
    /** {@code Data.platforms}（public final，声明在 {@code Data} 上，MTR4）。 */
    private static Field platformsField;
    /** {@code SavedRailBase.getMidPosition()}（public，{@code Platform} 继承）。 */
    private static Method getMidPosition;
    /** {@code Platform.getDwellTime()}（public long）。 */
    private static Method getDwellTime;
    /**
     * 【第十轮】站台的两个端点（{@code SavedRailBase.position1/position2}，protected）。
     * <b>可选</b>：读不到就退回「中点距离」模式（见 {@link #MAX_HORIZONTAL}），
     * 不让一个可选优化把整个功能拖挂。
     */
    private static Field position1Field;
    private static Field position2Field;
    /**
     * 【1.15 · 第十二轮】站台所属**车站**（{@code SavedRailBase.area}，public 字段，对 Platform 而言就是
     * {@code Station}）+ {@code NameColorDataBase.getName()}（public final）。
     * <b>可选</b>：只为日志好看 —— 用户报「改了停留时间没用」时，
     * 「认到的是哪个车站的站台」比一组坐标好认得多。
     */
    private static Field areaField;
    private static Method getNameMethod;
    /** 【09-30 续 9】可选绑定：{@code Station.getId()} —— 门串身份升到「车站级」要用
     *  （{@code platform.area → Station}，取车站自己的 id）。 */
    private static Method stationIdMethod;
    /**
     * 【09-30 续 2】**可选**绑定：{@code Data.routes}（线路集合）+ {@code Route.routeData}
     * （线路-站台关系列表）+ {@code RouteData.getPlatformId()} —— 讲述人自定义词的
     * {@code |LC|} / {@code |LE|}（当前线路名中英文）用。任一格读不到都只是占位符替空。
     */
    private static Field routesField;
    private static Field routeDataField;
    private static Method routeDataGetPlatformId;
    /** {@code Position.getX/getY/getZ()}（public）。 */
    private static Method posGetX;
    private static Method posGetY;
    private static Method posGetZ;
    /** {@code Platform.getId()}（MTR4，public）—— 拿它去查时刻表。 */
    private static Method getIdMethod;

    // ------------------------------------------------------------------
    // 【1.21】时刻表「下一班到站」这一层（进站报站 /pbmarrive 用）
    //
    //   ★ 用户点名「**看时刻表啊，不要猜**，可以借鉴关门音频的代码，应该有相似之处」。
    //   关门音频那一套读的是 `Data.platforms` 里的 `Platform.getDwellTime()`（停站时长），
    //   「下一班什么时候到」在同一份 MTR 数据里，由 **MTR 自己的到达缓存**给出 ——
    //   就是 PIDS（站台显示屏）与「列车时刻表传感器」用的那一个：
    //
    //     ArrivalsCacheClient.INSTANCE.requestArrivals(LongCollection platformIds)
    //       → 每个 ArrivalResponse: getArrival() - getMillisOffset() - System.currentTimeMillis()
    //       → / 1000 就是「还有几秒到站」
    //
    //   （对 `org/mtr/mod/block/BlockTrainScheduleSensor$BlockEntity` 逐条 javap 抄下来的，
    //    见 _tools 里那条探针；RenderPIDS 也是同一条路。）
    //
    //   ★ 为什么整层都是**可选**的：没装 MTR / MTR 改了这个内部类，
    //   只该让「进站报站」不响，不该把停站时长的读取一起拖挂 ⇒ bind() 里单独 try。
    // ------------------------------------------------------------------

    /** {@code org.mtr.mod.data.ArrivalsCacheClient.INSTANCE}（public static final）。 */
    private static Object arrivalsCache;
    /** {@code ArrivalsCacheClient.requestArrivals(LongCollection)}（声明在父类，public final）。 */
    private static Method requestArrivals;
    /** {@code ArrivalsCacheClient.getMillisOffset()}（把服务器时刻表对齐到真实时间）。 */
    private static Method getMillisOffset;
    /** {@code ArrivalResponse.getArrival()}（到站时刻，ms）。 */
    private static Method arrivalGetArrival;
    /**
     * 【09-28】{@code ArrivalResponse.getDestination()} —— **本次列车终点站**。
     *
     * <p>★ 取这个字段不是猜的：MTR 自己的站台显示屏 {@code org.mtr.mod.render.RenderPIDS}
     * 就是把 {@code getDestination()} 印在「开往/终点站」那一位上（对它逐条 javap 核过）。
     * 所以它 = 玩家在站台上那块屏里看到的终点站。
     *
     * <p><b>可选</b>：绑定不到（MTR 改了内部结构）只让报站词少「本次列车终点站：X」那一句，
     * 不影响「剩几秒到站」那条主链路。
     */
    private static Method arrivalGetDestination;
    /**
     * 【09-28】{@code ArrivalResponse.getPlatformName()} —— **站台名/编号**。
     *
     * <p>它的值就是 {@code Platform.getName()}（对 {@code ArrivalResponse} 构造函数
     * 逐条 javap 核过：{@code aload 13 → Platform.getName()}），同样是 {@code RenderPIDS}
     * 印出来的那一个 — 即玩家眼中「这是几站台」。
     *
     * <p><b>可选</b>，同 {@link #arrivalGetDestination}。
     */
    private static Method arrivalGetPlatformName;
    /** {@code org.mtr.libraries...LongArrayList} 的无参构造 + {@code add(long)}（构造入参用）。 */
    private static java.lang.reflect.Constructor<?> longListCtor;
    private static Method longListAdd;

    /**
     * 【1.21】算「**最近的一班**列车」时，**已经过站**超过这么多毫秒的条目就不再算数（ms）。
     *
     * <p>★ 取值必须**远小于**相邻两班的间隔：刚进站的那班在时刻表里会短暂地还挂着一个过去的
     * {@code arrival}，若把它也算成候选，它就会一直把**后面那班**压住（密集时刻表下整段漏播）。
     * 留 5 秒只是为了「车刚停稳时玩家才走进视距」还能补上这一班，不要再放大。
     *
     * <p>★ 播放端判「是不是已经晚了」用的是**同一个常数** —— 同一件事只留一个数字。
     */
    public static final long ARRIVAL_PAST_MS = 5_000L;
    /**
     * 【第十二轮】站台清单只打印一次（数量变了会再打一次）——
     * 这份清单是给「我明明改了停留时间，怎么读出来还是默认值」用的：
     * 把**每一个**站台的车站 + 坐标 + 停留时长都摆出来，用户一眼就能看出自己改的是哪一条、
     * 而屏蔽门读到的又是哪一条。
     */
    /**
     * 【1.15 · 第十二轮】站台清单只在**内容变了**的时候重打 —— 签名 = 「每个站台的坐标 + 停留时长」。
     *
     * <p>★ 以前只按「数量」去重，于是**停留时长被改了却看不出来**：
     * 用户在仪表盘里把某个站台的停留时间改成 30 秒，数量还是 6 ⇒ 清单不再打印，
     * 日志里就永远停在「6 个站台，全是 10000ms（默认）」那个印象上，
     * 分不清「改动没到客户端」还是「改动到了但没被用上」。
     */
    private static String dumpedPlatformSignature = "";

    private MtrDwellAccess() {
    }

    /** 这一层能不能用（首次调用时探测，之后缓存）。 */
    public static boolean available() {
        if (!initialised) {
            initialised = true;
            bound = bind();
        }
        return bound && !broken;
    }

    /**
     * 探测当前运行期装的是哪一版 MTR：先试 MTR4（org.mtr.*），没有再试 MTR3（mtr.*）。
     * 两个都失败才返回 false（既没装 MTR 也没装兼容版本）。
     */
    private static boolean bind() {
        if (bindMtr4()) {
            mode = MODE_MTR4;
            return true;
        }
        // 没装 MTR4（装的是 MTR3 或压根没装 MTR）—— 试着走 MTR3 那条并列绑定。
        try {
            if (bindMtr3()) {
                mode = MODE_MTR3;
                return true;
            }
        } catch (Throwable t) {
            LOGGER.info("[SmoothLift/PsdChime] 读不到 MTR3 站台数据层（{}），"
                    + "停站时长只能靠上一轮实测学习（功能照常，只是第一次停站没人声）", t.toString());
        }
        return false;
    }

    /**
     * 【MTR3】探测并绑定 {@code mtr.client.ClientData} 这一整套（mtr.*，3.2.2-hotfix-1）。
     *
     * <p>与 MTR4 同一套「任何一步失败都只打日志、不抛异常」的降级风格；返回 true 即启用
     * 站台讲述人 / 进站讲述人 / 停站时长预测。没装 MTR3（或 MTR 改了内部类名）时返回 false，
     * 下游照旧走实测学习那条路。
     */
    private static boolean bindMtr3() {
        try {
            Class<?> clientData = Class.forName("mtr.client.ClientData");
            mtr3PlatformsField = clientData.getField("PLATFORMS");
            mtr3StationsField = clientData.getField("STATIONS");
            mtr3SchedulesField = clientData.getField("SCHEDULES_FOR_PLATFORM");
            mtr3RoutesField = clientData.getField("ROUTES");
            mtr3DataCacheField = clientData.getField("DATA_CACHE");
            setAccessible(mtr3PlatformsField, mtr3StationsField, mtr3SchedulesField,
                    mtr3RoutesField, mtr3DataCacheField);
            Class<?> savedRail = Class.forName("mtr.data.SavedRailBase");
            mtr3GetMidPos = method(savedRail, "getMidPos");
            mtr3GetDwellTime = method(savedRail, "getDwellTime");
            if (mtr3GetMidPos == null || mtr3GetDwellTime == null) {
                throw new NoSuchMethodException("SavedRailBase.getMidPos/getDwellTime");
            }
            // ★ 拿到 BlockPos 的真实 Class（运行期可能是 class_2338，也可能是 Mojang 名），避免硬编码映射。
            Class<?> blockPos = mtr3GetMidPos.getReturnType();
            mtr3PosGetX = blockPos.getMethod("getX");
            mtr3PosGetY = blockPos.getMethod("getY");
            mtr3PosGetZ = blockPos.getMethod("getZ");
            setAccessible(mtr3PosGetX, mtr3PosGetY, mtr3PosGetZ);
            Class<?> named = Class.forName("mtr.data.NameColorDataBase");
            mtr3IdField = fieldInHierarchy(named, "id");
            mtr3NameField = fieldInHierarchy(named, "name");
            if (mtr3IdField == null || mtr3NameField == null) {
                throw new NoSuchFieldException("NameColorDataBase.id/name");
            }
            setAccessible(mtr3IdField, mtr3NameField);
            Class<?> area = Class.forName("mtr.data.AreaBase");
            mtr3GetCenter = method(area, "getCenter");
            mtr3InArea = method(area, "inArea", int.class, int.class);
            Class<?> route = Class.forName("mtr.data.Route");
            mtr3RoutePlatformIdsField = fieldInHierarchy(route, "platformIds");
            Class<?> routePlatform = Class.forName("mtr.data.Route$RoutePlatform");
            mtr3RoutePlatformIdField = fieldInHierarchy(routePlatform, "platformId");
            Class<?> scheduleEntry = Class.forName("mtr.data.ScheduleEntry");
            mtr3ArrivalMillisField = fieldInHierarchy(scheduleEntry, "arrivalMillis");
            mtr3ScheduleRouteIdField = fieldInHierarchy(scheduleEntry, "routeId");
            mtr3ScheduleStationIndexField = fieldInHierarchy(scheduleEntry, "currentStationIndex");
            Class<?> clientCache = Class.forName("mtr.client.ClientCache");
            mtr3GetFormattedDest = method(clientCache, "getFormattedRouteDestination", route, int.class, String.class);
            LOGGER.info("[SmoothLift/PsdChime] 检测到 MTR3 站台数据层（mtr.* 3.2.2）—— "
                    + "可以直接读时刻表的停站时长；认站台方式 = 门到站台中点的水平距离（≤ "
                    + MAX_HORIZONTAL + " 格，MTR3 无 position1/position2 端点字段，不走高版本的「中轴线段」模式）");
            return true;
        } catch (Throwable t) {
            LOGGER.info("[SmoothLift/PsdChime] 读不到 MTR3 站台数据层（{}），"
                    + "停站时长只能靠上一轮实测学习（功能照常，只是第一次停站没人声）", t.toString());
            return false;
        }
    }

    private static void setAccessible(java.lang.reflect.AccessibleObject... members) {
        for (java.lang.reflect.AccessibleObject m : members) {
            if (m != null) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // 某些环境禁止 setAccessible，跳过多读一次无妨（绑定已成功，只是读可能失败）
                }
            }
        }
    }

    private static boolean bindMtr4() {
        try {
            Class<?> mcd = Class.forName("org.mtr.mod.client.MinecraftClientData");
            getInstance = method(mcd, "getInstance");
            if (getInstance == null) {
                throw new NoSuchMethodException("MinecraftClientData.getInstance");
            }
            Class<?> data = Class.forName("org.mtr.core.data.Data");
            platformsField = fieldInHierarchy(data, "platforms");
            if (platformsField == null) {
                throw new NoSuchFieldException("org.mtr.core.data.Data.platforms");
            }
            Class<?> platform = Class.forName("org.mtr.core.data.Platform");
            getMidPosition = method(platform, "getMidPosition");
            getDwellTime = method(platform, "getDwellTime");
            if (getMidPosition == null || getDwellTime == null) {
                throw new NoSuchMethodException("Platform.getMidPosition/getDwellTime");
            }
            Class<?> position = Class.forName("org.mtr.core.data.Position");
            posGetX = method(position, "getX");
            posGetY = method(position, "getY");
            posGetZ = method(position, "getZ");
            if (posGetX == null || posGetY == null || posGetZ == null) {
                throw new NoSuchMethodException("Position.getX/getY/getZ");
            }
            // 【第十轮】**可选**绑定：站台两端点。拿得到就用「门到站台中轴线段」认亲（见 MAX_LATERAL），
            //   拿不到就退回「到中点」模式（见 MAX_HORIZONTAL）——绝不让这个优化把功能拖挂。
            try {
                Class<?> savedRail = Class.forName("org.mtr.core.data.SavedRailBase");
                position1Field = fieldInHierarchy(savedRail, "position1");
                position2Field = fieldInHierarchy(savedRail, "position2");
            } catch (Throwable ignored) {
                position1Field = null;
                position2Field = null;
            }
            // 【第十二轮】**可选**绑定：站台 → 所属车站（只为日志能点名车站）。
            try {
                Class<?> savedRail = Class.forName("org.mtr.core.data.SavedRailBase");
                areaField = fieldInHierarchy(savedRail, "area");
                Class<?> named = Class.forName("org.mtr.core.data.NameColorDataBase");
                getNameMethod = method(named, "getName");
                // 【09-30 续 9】车站 id（同上，门串身份升「车站级」用）。
                Class<?> station = Class.forName("org.mtr.core.data.Station");
                stationIdMethod = method(station, "getId");
            } catch (Throwable ignored) {
                areaField = null;
                getNameMethod = null;
                stationIdMethod = null;
            }
            // 【09-30 续 2】**可选**绑定：站台 → 所属**线路**（讲述人自定义词的 |LC| / |LE| 占位符）。
            //   找法 = 扫每条 Route 的 routeData，谁的 platformId 等于目标站台就是哪条线；
            //   Route extends NameColorDataBase ⇒ 线路名用上面同一个 getNameMethod。
            //   任一格绑定失败都只是 |LC|/|LE| 替成空串，不影响其它功能。
            try {
                routesField = fieldInHierarchy(data, "routes");
                Class<?> route = Class.forName("org.mtr.core.data.Route");
                routeDataField = fieldInHierarchy(route, "routeData");
                Class<?> routeData = Class.forName("org.mtr.core.data.RouteData");
                routeDataGetPlatformId = method(routeData, "getPlatformId");
                if (routesField == null || routeDataField == null || routeDataGetPlatformId == null) {
                    throw new NoSuchMethodException("Route.routeData / RouteData.getPlatformId");
                }
            } catch (Throwable ignored) {
                routesField = null;
                routeDataField = null;
                routeDataGetPlatformId = null;
            }
            // 【1.21】**可选**绑定：站台 id（进站报站要靠它去查时刻表）。
            getIdMethod = method(platform, "getId");
            // 【1.21】**可选**绑定：到达缓存。失败只让进站报站不响，不影响停站时长。
            bindArrivals();
            boolean segmentMode = position1Field != null && position2Field != null;
            LOGGER.info("[SmoothLift/PsdChime] 检测到 MTR4 站台数据层 —— "
                            + "可以直接读时刻表的停站时长（不必再靠上一轮实测去猜）；"
                            + "认站台方式 = {}",
                    segmentMode ? "门到站台中轴线段的垂直距离（≤ " + MAX_LATERAL + " 格）"
                            : "门到站台中点的水平距离（≤ " + MAX_HORIZONTAL + " 格，端点字段读不到）");
            return true;
        } catch (Throwable t) {
            // 不是错误路径：没装 MTR4 / 装了别的版本 / MTR 改了内部结构，都走到这里。
            // 屏蔽门提示音本来就有「实测学习」那条完整可用的路，读不到只是少一份参照。
            LOGGER.info("[SmoothLift/PsdChime] 读不到 MTR 站台数据（{}），"
                    + "停站时长只能靠上一轮实测学习（功能照常，只是第一次停站没人声）", t.toString());
            return false;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field fieldInHierarchy(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /** 容器形态兜底：fastutil 的 Set 是 {@code java.util.Set}（Iterable），fastutil 的 Map 只能取 values()。 */
    private static Iterable<?> elementsOf(Object raw) {
        if (raw instanceof java.util.Map<?, ?> map) {
            return map.values();
        }
        if (raw instanceof Iterable<?> iterable) {
            return iterable;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 双版本数据访问代理（对外只读接口不变，按 mode 分发到 MTR4 / MTR3）
    // ------------------------------------------------------------------

    /** 当前是不是「门到站台中轴线段」的认亲模式（只有 MTR4 且有端点字段时才成立）。 */
    private static boolean segmentMode() {
        return mode == MODE_MTR4 && position1Field != null && position2Field != null;
    }

    /** 取出「客户端手里的全部站台」集合（MTR4 走 getInstance().platforms；MTR3 走静态字段）。 */
    private static Object mtrPlatforms() {
        try {
            if (mode == MODE_MTR3) {
                return mtr3PlatformsField.get(null);
            }
            Object instance = getInstance.invoke(null);
            return instance == null ? null : platformsField.get(instance);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 站台中点坐标对象（MTR4 = Position；MTR3 = BlockPos），调用方再用 px/py/pz 取分量。 */
    private static Object midPosOf(Object platform) {
        try {
            return (mode == MODE_MTR3 ? mtr3GetMidPos : getMidPosition).invoke(platform);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 中点坐标的 X 分量（按版本选 Position / BlockPos 的 getX）。 */
    private static double px(Object pos) {
        try {
            return ((Number) (mode == MODE_MTR3 ? mtr3PosGetX : posGetX).invoke(pos)).doubleValue();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 中点坐标的 Y 分量。 */
    private static double py(Object pos) {
        try {
            return ((Number) (mode == MODE_MTR3 ? mtr3PosGetY : posGetY).invoke(pos)).doubleValue();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 中点坐标的 Z 分量。 */
    private static double pz(Object pos) {
        try {
            return ((Number) (mode == MODE_MTR3 ? mtr3PosGetZ : posGetZ).invoke(pos)).doubleValue();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 站台 id（MTR4 = getId()；MTR3 = id 字段；都可为负，原样透传）。 */
    private static long idOf(Object platform) {
        try {
            if (mode == MODE_MTR3) {
                Object id = mtr3IdField.get(platform);
                return id instanceof Number n ? n.longValue() : PLATFORM_ID_NONE;
            }
            Object id = getIdMethod.invoke(platform);
            return id instanceof Number n ? n.longValue() : PLATFORM_ID_NONE;
        } catch (Throwable t) {
            return PLATFORM_ID_NONE;
        }
    }

    /**
     * 【MTR3】按几何找「这个站台属于哪个车站」：取站台中点，遍历 STATIONS 命中
     * {@code Station.inArea(中点X, 中点Z)} 的那个就是（MTR3 没有 platform.area 链接）。
     */
    private static String mtr3StationNameOf(Object platform) {
        if (mtr3StationsField == null || mtr3GetMidPos == null || mtr3InArea == null
                || mtr3PosGetX == null || mtr3PosGetZ == null) {
            return null;
        }
        try {
            Object mid = mtr3GetMidPos.invoke(platform);
            if (mid == null) {
                return null;
            }
            int mx = (int) Math.floor(px(mid));
            int mz = (int) Math.floor(pz(mid));
            Iterable<?> stations = elementsOf(mtr3StationsField.get(null));
            if (stations == null) {
                return null;
            }
            for (Object st : stations) {
                if (st == null) {
                    continue;
                }
                Object res = mtr3InArea.invoke(st, mx, mz);
                if (res instanceof Boolean b && b) {
                    Object name = mtr3NameField.get(st);
                    return name == null ? null : name.toString();
                }
            }
        } catch (Throwable ignored) {
            // 读不到只是没站名可念，不影响报站本身
        }
        return null;
    }

    /**
     * 【MTR3】站台 id → 所属车站 id：与 {@link #mtr3StationNameOf} 同一套几何命中，
     * 命中后取 {@code Station.id} 字段。
     */
    private static long mtr3StationIdForPlatform(long platformId) {
        if (mtr3StationsField == null || mtr3PlatformsField == null
                || mtr3GetMidPos == null || mtr3InArea == null) {
            return PLATFORM_ID_NONE;
        }
        try {
            Iterable<?> platforms = elementsOf(mtr3PlatformsField.get(null));
            if (platforms == null) {
                return PLATFORM_ID_NONE;
            }
            for (Object platform : platforms) {
                if (platform == null || idOf(platform) != platformId) {
                    continue;
                }
                Object mid = mtr3GetMidPos.invoke(platform);
                if (mid == null) {
                    break;
                }
                int mx = (int) Math.floor(px(mid));
                int mz = (int) Math.floor(pz(mid));
                Iterable<?> stations = elementsOf(mtr3StationsField.get(null));
                if (stations != null) {
                    for (Object st : stations) {
                        if (st == null) {
                            continue;
                        }
                        Object res = mtr3InArea.invoke(st, mx, mz);
                        if (res instanceof Boolean b && b) {
                            Object sid = mtr3IdField.get(st);
                            return sid instanceof Number n ? n.longValue() : PLATFORM_ID_NONE;
                        }
                    }
                }
                break;
            }
        } catch (Throwable ignored) {
            // 读不到只是拿不到车站 id，不影响其它功能
        }
        return PLATFORM_ID_NONE;
    }

    /**
     * 【MTR3】站台 id → 线路名：扫 ROUTES，谁的 platformIds 里含这个站台 id，那条 Route 就是答案
     * （一条站台被多条线共用时取扫到的第一条；共线段各有各的说法，挑哪条都算对）。
     */
    private static String mtr3LineNameForPlatform(long platformId) {
        if (mtr3RoutesField == null || mtr3RoutePlatformIdsField == null
                || mtr3RoutePlatformIdField == null || mtr3NameField == null) {
            return null;
        }
        try {
            Iterable<?> routes = elementsOf(mtr3RoutesField.get(null));
            if (routes == null) {
                return null;
            }
            for (Object route : routes) {
                if (route == null) {
                    continue;
                }
                Iterable<?> rps = elementsOf(mtr3RoutePlatformIdsField.get(route));
                if (rps == null) {
                    continue;
                }
                for (Object rp : rps) {
                    if (rp == null) {
                        continue;
                    }
                    Object pid = mtr3RoutePlatformIdField.get(rp);
                    if (pid instanceof Number n && n.longValue() == platformId) {
                        Object name = mtr3NameField.get(route);
                        return name == null ? null : name.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到只是没线路名可念，不影响报站本身
        }
        return null;
    }

    /** 【MTR3】站台 id → 站台名（platform.name 字段，讲述人报站词要念的「几站台」）。 */
    private static String mtr3PlatformNameForPlatform(long platformId) {
        if (mtr3PlatformsField == null || mtr3NameField == null) {
            return null;
        }
        try {
            Iterable<?> platforms = elementsOf(mtr3PlatformsField.get(null));
            if (platforms == null) {
                return null;
            }
            for (Object platform : platforms) {
                if (platform == null) {
                    continue;
                }
                if (idOf(platform) == platformId) {
                    Object name = mtr3NameField.get(platform);
                    return name == null ? null : name.toString();
                }
            }
        } catch (Throwable ignored) {
            // 读不到只是没站台名可念
        }
        return null;
    }

    /**
     * 【MTR3】下一班车信息：从 {@code SCHEDULES_FOR_PLATFORM} 取这一站的时刻表，
     * 算「还有几秒到站」；终点站走 {@code ClientCache.getFormattedRouteDestination}（PIDS 同函数）。
     */
    private static ArrivalInfo mtr3NearestArrival(long platformId) {
        if (mtr3SchedulesField == null || mtr3ArrivalMillisField == null) {
            LOGGER.info("[SmoothLift/PsdChime] MTR3 读不到 SCHEDULES_FOR_PLATFORM（时刻表），"
                    + "/pbmarrive（进站报站）不会响；其余功能不受影响");
            return null;
        }
        try {
            Object map = mtr3SchedulesField.get(null);
            if (!(map instanceof java.util.Map<?, ?> scheduleMap)) {
                return null;
            }
            Object set = scheduleMap.get(platformId);
            if (!(set instanceof Iterable<?> entries)) {
                return null;
            }
            long now = System.currentTimeMillis();
            long best = Long.MIN_VALUE;
            String bestDest = null;
            // 【10-01】站台名过一遍 PIDS 掩码：MTR3 的名字是 public 字段 `Platform.name`，
            //   **不经过任何 getter** ⇒ Mtr3PidsNameMixin 只拦得下 PIDS 那次 Map.put，
            //   拦不住我们自己念的报站词。用户点名「讲述人念的站台名要跟屏蔽门一致」，
            //   所以在这里（ArrivalInfo.platformName 的唯一出口）掩一次。
            String bestPlatform = PlatformNameMask.mask(mtr3PlatformNameForPlatform(platformId));
            for (Object entry : entries) {
                if (entry == null) {
                    continue;
                }
                Object am = mtr3ArrivalMillisField.get(entry);
                if (!(am instanceof Number n)) {
                    continue;
                }
                long remaining = n.longValue() - now;
                if (remaining < -ARRIVAL_PAST_MS) {
                    continue;
                }
                if (best == Long.MIN_VALUE || remaining < best) {
                    best = remaining;
                    // 【10-03 五改】与 MTR4 那一支同一个出口：终点站名掩一次（讲述人只念本名）。
                    bestDest = PlatformNameMask.mask(mtr3DestinationFor(entry));
                }
            }
            return best == Long.MIN_VALUE ? null : new ArrivalInfo(best, bestDest, bestPlatform);
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift/PsdChime] MTR3 查时刻表失败，/pbmarrive 暂不响：{}", t.toString());
            return null;
        }
    }

    /** 【MTR3】ScheduleEntry → 终点站名（PIDS 同款：routeId 命中 ROUTES，再格式化）。 */
    private static String mtr3DestinationFor(Object entry) {
        if (mtr3DataCacheField == null || mtr3GetFormattedDest == null || mtr3RoutesField == null
                || mtr3ScheduleRouteIdField == null || mtr3ScheduleStationIndexField == null) {
            return null;
        }
        try {
            Object routeId = mtr3ScheduleRouteIdField.get(entry);
            long rid = routeId instanceof Number n ? n.longValue() : -1L;
            Object idx = mtr3ScheduleStationIndexField.get(entry);
            int stationIndex = idx instanceof Number n ? n.intValue() : 0;
            Iterable<?> routes = elementsOf(mtr3RoutesField.get(null));
            if (routes == null) {
                return null;
            }
            for (Object route : routes) {
                if (route == null) {
                    continue;
                }
                Object id = mtr3IdField.get(route);
                if (id instanceof Number n && n.longValue() == rid) {
                    Object cache = mtr3DataCacheField.get(null);
                    if (cache == null) {
                        return null;
                    }
                    Object dest = mtr3GetFormattedDest.invoke(cache, route, stationIndex, "");
                    if (dest == null) {
                        return null;
                    }
                    String text = dest.toString().trim();
                    return text.isEmpty() ? null : text;
                }
            }
        } catch (Throwable ignored) {
            // 读不到只是少念一句「终点站」，其它照常
        }
        return null;
    }

    /**
     * 【1.21】**可选**绑定 MTR 的到达缓存（PIDS / 时刻表传感器用的同一个）。
     *
     * <p>失败是**正常路径**（没装 MTR / 换了 MTR 版本 / 被别的模组换了实现），
     * 只打一条 info，整层保持 {@code null} ⇒ {@link #nearestArrival} 直接返回 {@code null}，
     * 「进站报站」静默不响，其它功能照常。
     */
    private static boolean bindArrivals() {
        arrivalsCache = null;
        requestArrivals = null;
        getMillisOffset = null;
        arrivalGetArrival = null;
        arrivalGetDestination = null;
        arrivalGetPlatformName = null;
        longListCtor = null;
        longListAdd = null;
        try {
            Class<?> acc = Class.forName("org.mtr.mod.data.ArrivalsCacheClient");
            arrivalsCache = acc.getField("INSTANCE").get(null);
            Class<?> longCollection = Class.forName(
                    "org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection");
            requestArrivals = acc.getMethod("requestArrivals", longCollection);
            getMillisOffset = acc.getMethod("getMillisOffset");
            Class<?> response = Class.forName("org.mtr.core.operation.ArrivalResponse");
            arrivalGetArrival = response.getMethod("getArrival");
            // 【09-28】终点站 / 站台名 —— 讲述人报站词要念的两个值（RenderPIDS 用的同一对）。
            //   用 method(...) 取（它自己吞异常）⇒ 这两个是**可选**的：缺了只少一句台词。
            arrivalGetDestination = method(response, "getDestination");
            arrivalGetPlatformName = method(response, "getPlatformName");
            Class<?> longList = Class.forName(
                    "org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList");
            longListCtor = longList.getConstructor();
            longListAdd = longList.getMethod("add", long.class);
            if (arrivalsCache == null || longListCtor == null || longListAdd == null) {
                throw new NoSuchFieldException("ArrivalsCacheClient.INSTANCE");
            }
            LOGGER.info("[SmoothLift/PsdChime] 已接上 MTR 到达缓存 ⇒ /pbmarrive 可以按时刻表在"
                    + "「最近一班车还剩 N 秒到站」时播进站报站");
            return true;
        } catch (Throwable t) {
            arrivalsCache = null;
            LOGGER.info("[SmoothLift/PsdChime] 读不到 MTR 到达缓存（{}），"
                    + "/pbmarrive（进站报站）不会响；其余功能不受影响", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 【1.21】这一串屏蔽门在哪一条**站台**上（拿去查时刻表）。
     *
     * <p>认亲规则与 {@link #dwellMsAt} **完全一致**（同一套 {@code MAX_LATERAL} /
     * {@code MAX_HORIZONTAL} / {@code MAX_DY} 与「优先挑停站时长有效的站台」），
     * 这样「进站报站读的时刻表」与「关门提示音读的停站时长」永远是**同一条站台**的数据。
     *
     * @return 站台 id（**可为负** —— MTR 的 id 是 {@code Random().nextLong()}，
     *         见 {@link #PLATFORM_ID_NONE}）；{@link #PLATFORM_ID_NONE} = 认不到
     *         （没装 MTR / 附近没有够得着的站台）
     */
    public static long platformIdAt(double x, double y, double z) {
        if (!available()) {
            return PLATFORM_ID_NONE;
        }
        try {
            Iterable<?> platforms = elementsOf(mtrPlatforms());
            if (platforms == null) {
                return PLATFORM_ID_NONE;
            }
            boolean segmentMode = segmentMode();
            Object bestUsable = null;
            double bestUsableDist = Double.MAX_VALUE;
            Object bestAny = null;
            double bestAnyDist = Double.MAX_VALUE;
            for (Object platform : platforms) {
                if (platform == null) {
                    continue;
                }
                double distance = matchDistance(platform, x, y, z, segmentMode);
                if (Double.isNaN(distance)) {
                    continue;
                }
                if (distance < bestAnyDist) {
                    bestAnyDist = distance;
                    bestAny = platform;
                }
                if (dwellOf(platform) > 0L && distance < bestUsableDist) {
                    bestUsableDist = distance;
                    bestUsable = platform;
                }
            }
            Object chosen = bestUsable != null ? bestUsable : bestAny;
            if (chosen == null) {
                return PLATFORM_ID_NONE;
            }
            // ★【09-28 续 6】原样透传：id 可以是负数（Random().nextLong()），**不做任何正负判断**。
            Object id = idOf(chosen);
            return id instanceof Number n ? n.longValue() : PLATFORM_ID_NONE;
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[SmoothLift/PsdChime] 读 MTR 站台 id 失败，/pbmarrive 改用「认不到就跳过」：{}",
                    t.toString());
            return PLATFORM_ID_NONE;
        }
    }

    /**
     * 【1.21】站台位置匹配：门到这条站台的距离；够不着（超过上限 / 高差太大）返回 {@code NaN}。
     *
     * <p>与 {@link #dwellMsAt} 里那段循环同一套判据，只是**不记诊断**（那是 dwellMsAt 的活）——
     * 这个方法跑在「每 tick 每串门一次」的路径上，必须便宜。
     */
    private static double matchDistance(Object platform, double x, double y, double z,
                                        boolean segmentMode) {
        try {
            if (segmentMode) {
                double[] seg = segmentXZ(platform);
                if (seg == null) {
                    return Double.NaN;
                }
                if (Math.abs(seg[1] - y) > MAX_DY && Math.abs(seg[4] - y) > MAX_DY) {
                    return Double.NaN;
                }
                double distance = pointToSegmentXZ(x, z, seg[0], seg[2], seg[3], seg[5]);
                return distance > MAX_LATERAL ? Double.NaN : distance;
            }
            Object mid = midPosOf(platform);
            if (mid == null) {
                return Double.NaN;
            }
            double pxv = px(mid);
            double pyv = py(mid);
            double pzv = pz(mid);
            if (Double.isNaN(pxv) || Double.isNaN(pyv) || Double.isNaN(pzv)) {
                return Double.NaN;
            }
            if (Math.abs(pyv - y) > MAX_DY) {
                return Double.NaN;
            }
            double dx = pxv - x;
            double dz = pzv - z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            return distance > MAX_HORIZONTAL ? Double.NaN : distance;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /**
     * 【1.28】诊断：这扇门**最近**的 MTR 站台有多远（不受 {@value #MAX_LATERAL} 上限约束）。
     *
     * <p>配合 {@code PsdDoorTracker} 的认站台失败日志用 —— 用户报「某排屏蔽门的
     * 到站 / 进站报站不响」时，这行能当场区分三种情况：读不到站台数据 / 附近根本没站台 /
     * 有站台但离门超过 4 格（差多远）。三种的修法完全不同，不能混在一句「认不到」里。
     */
    public static String nearestPlatformExplain(double x, double y, double z) {
        if (!available()) {
            return "读不到 MTR 站台数据";
        }
        try {
            Iterable<?> platforms = elementsOf(mtrPlatforms());
            if (platforms == null) {
                return "读不到 MTR 站台数据";
            }
            boolean segmentMode = segmentMode();
            Object best = null;
            double bestDist = Double.MAX_VALUE;
            for (Object platform : platforms) {
                if (platform == null) {
                    continue;
                }
                double d = rawMatchDistance(platform, x, y, z, segmentMode);
                if (!Double.isNaN(d) && d < bestDist) {
                    bestDist = d;
                    best = platform;
                }
            }
            if (best == null) {
                return "附近一个站台都读不到位置";
            }
            Object id = idOf(best);
            // ★【09-28 续 6】id 可以是负数，原样打出来（**不要**拿 -1 之类的哨兵去"美化"它）。
            String idText = id instanceof Number n ? Long.toString(n.longValue()) : "读不到";
            return "最近的站台 id=" + idText
                    + "，距门 " + String.format("%.1f", bestDist) + " 格（横向上限 4.0 格）";
        } catch (Throwable t) {
            return "读 MTR 站台位置失败（" + t.getClass().getSimpleName() + "）";
        }
    }

    /**
     * 【1.28】只看「门到站台的距离」，**不卡任何上限** —— 认亲要卡（{@link #matchDistance}），
     * 诊断不要（它要回答的就是「差几格才够不着」）。
     */
    private static double rawMatchDistance(Object platform, double x, double y, double z,
                                           boolean segmentMode) {
        try {
            if (segmentMode) {
                double[] seg = segmentXZ(platform);
                if (seg == null) {
                    return Double.NaN;
                }
                if (Math.abs(seg[1] - y) > MAX_DY && Math.abs(seg[4] - y) > MAX_DY) {
                    return Double.NaN;
                }
                return pointToSegmentXZ(x, z, seg[0], seg[2], seg[3], seg[5]);
            }
            Object mid = midPosOf(platform);
            if (mid == null) {
                return Double.NaN;
            }
            double pxv = px(mid);
            double pyv = py(mid);
            double pzv = pz(mid);
            if (Double.isNaN(pxv) || Double.isNaN(pyv) || Double.isNaN(pzv)) {
                return Double.NaN;
            }
            // 诊断与认亲同一套 Y 上限：认到的门肯定过得了这关，这里只回答「XZ 上差多远」。
            if (Math.abs(pyv - y) > MAX_DY) {
                return Double.NaN;
            }
            double dx = pxv - x;
            double dz = pzv - z;
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /**
     * 【09-28】**最近的一班**车的到站信息（时间 + 终点站 + 站台名）。
     *
     * <p>这是 {@link #nearestArrival} 的返回体。三个字段都来自**同一个** {@code ArrivalResponse}
     * —— 也就是 MTR 站台显示屏正在显示的那一条，所以「念出来的终点站 / 站台」与玩家抬头看到
     * 的那块屏**永远一致**（不会出现「屏幕写 A、广播念 B」）。
     */
    public static final class ArrivalInfo {

        /** 还有多少毫秒到站（只可能落在 {@code [-ARRIVAL_PAST_MS, +∞)}：车刚停稳那几秒是小的负数）。 */
        public final long remainingMs;

        /** 本次列车**终点站**（{@code ArrivalResponse.getDestination()}）；读不到 = {@code null}。 */
        public final String destination;

        /** **站台名/编号**（{@code ArrivalResponse.getPlatformName()}）；读不到 = {@code null}。 */
        public final String platformName;

        private ArrivalInfo(long remainingMs, String destination, String platformName) {
            this.remainingMs = remainingMs;
            this.destination = destination;
            this.platformName = platformName;
        }
    }

    /**
     * 【1.21】这个站台**最近的一班**列车：还有多少毫秒到站，以及它的终点站 / 站台名。
     *
     * <p>对时刻表里每一条算 {@code arrival - millisOffset - System.currentTimeMillis()}，
     * 丢掉「已经过站超过 {@link #ARRIVAL_PAST_MS}」的条目（它们不再算「最近的一班」），
     * 取剩下里**最小**的那一个 —— 也就是下一班车还有多久到站。
     *
     * <p>★【09-28】终点站与站台名**只从被选中的那一条**上取（不是各自再扫一遍）：
     * 三条信息同属一班车，分开取会在「两条车几乎同时到时」拼出「A 车的终点站 + B 车的站台」。
     *
     * @return 最近一班车的信息；{@code null} = 读不到
     *         （没装 MTR4 / 还没同步 / 调用失败 / 时刻表里一条有效条目都没有）
     */
    public static ArrivalInfo nearestArrival(long platformId) {
        // ★【09-28 续 6】判据是 isPlatformKnown（**不是 platformId <= 0**）—— 站台 id 可以是负数。
        if (!isPlatformKnown(platformId)) {
            return null;
        }
        if (mode == MODE_MTR3) {
            return mtr3NearestArrival(platformId);
        }
        if (!available() || arrivalsCache == null
                || requestArrivals == null || getMillisOffset == null || arrivalGetArrival == null) {
            return null;
        }
        try {
            Object ids = longListCtor.newInstance();
            longListAdd.invoke(ids, platformId);
            Object list = requestArrivals.invoke(arrivalsCache, ids);
            if (!(list instanceof Iterable<?> responses)) {
                return null;
            }
            long offset = ((Number) getMillisOffset.invoke(arrivalsCache)).longValue();
            long now = System.currentTimeMillis();
            long best = Long.MIN_VALUE;
            String bestDestination = null;
            String bestPlatform = null;
            for (Object response : responses) {
                if (response == null) {
                    continue;
                }
                Object arrival = arrivalGetArrival.invoke(response);
                if (!(arrival instanceof Number n)) {
                    continue;
                }
                long remaining = n.longValue() - offset - now;
                if (remaining < -ARRIVAL_PAST_MS) {
                    continue;
                }
                if (best == Long.MIN_VALUE || remaining < best) {
                    best = remaining;
                    // 【10-03 五改】终点站名也过一遍掩码：讲述人念的是 `%` **前面**的本名
                    //   （用户点名「只有屏蔽门上的终点站名字用 % 后面的别名」）。
                    //   单机里服务端拿到的 getName() 已被 Mtr4NameMaskMixin 掩过（mask 幂等），
                    //   专用服务器（服务端没装本模组）拿到的还是原样名字 ⇒ 这一处兜住第二种。
                    bestDestination = PlatformNameMask.mask(textOf(response, arrivalGetDestination));
                    // 【10-01】与 MTR3 那一支走**同一个出口**：MTR4 的 getPlatformName 其实
                    //   已被 Mtr4PidsNameMixin 掩过（mask 幂等：不含 % 时原样返回同一实例，
                    //   这里等于零成本），再掩一次只是让「有没有装 MTR / 装的是哪版」都不影响
                    //   讲述人念出来的站台名 —— 用户点名「跟屏蔽门一致」。
                    bestPlatform = PlatformNameMask.mask(textOf(response, arrivalGetPlatformName));
                }
            }
            return best == Long.MIN_VALUE ? null : new ArrivalInfo(best, bestDestination, bestPlatform);
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[SmoothLift/PsdChime] 查 MTR 时刻表失败，/pbmarrive 暂不响：{}", t.toString());
            return null;
        }
    }

    /**
     * 【09-28】对一个对象调一个**可选**的 getter，把结果规整成「非空字符串或 {@code null}」。
     *
     * <p>规整的三件事：getter 没绑上 → {@code null}；返回 null → {@code null}；
     * 只有空白 → {@code null}（拼报站词时少念一句，而不是念出「终点站： 」这种半截话）。
     */
    private static String textOf(Object target, Method getter) {
        if (getter == null) {
            return null;
        }
        try {
            Object value = getter.invoke(target);
            if (value == null) {
                return null;
            }
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 某个世界坐标（屏蔽门锚点）所属站台的**停站时长**。
     *
     * <p>【1.15 · 第十轮】认亲方式改成「**门到站台中轴线段**的垂直距离」（拿得到站台两端点时，
     * 见 {@link #MAX_LATERAL}），退路才是「到中点的水平距离」（见 {@link #MAX_HORIZONTAL}）。
     * 另外**优先挑停站时长有效的站台**：MTR 里没配过停站时间的站台读出来是 0，
     * 拿它算周期只会算出 -1，等于白认一次亲。
     *
     * <p>只在「选中的站台变了」时打一条日志 —— 用户报「改停站时间没用」时，
     * 一眼就能看出到底认到了哪个站台、读到多少。
     *
     * @return 毫秒；{@code <= 0} = 读不到（没装 MTR4 / 反射坏了 / 附近没有够得着的站台）
     */
    public static long dwellMsAt(double x, double y, double z) {
        if (!available()) {
            return -1L;
        }
        try {
            Iterable<?> platforms = elementsOf(mtrPlatforms());
            if (platforms == null) {
                return -1L;
            }
            boolean segmentMode = segmentMode();
            Object bestAny = null;
            double bestAnyDist = Double.MAX_VALUE;
            String bestAnyAt = "";
            Object bestUsable = null;
            double bestUsableDist = Double.MAX_VALUE;
            String bestUsableAt = "";
            long bestUsableDwell = 0L;
            // 【第十二轮】「次近的有效站台」—— 用来抓**另一类**「改了没用」：
            //   两条站台都落在 4 格以内时（岛式站台 / 门正卡在两条之间），我们只会认**最近的那条**，
            //   而用户改的可能是另一条。这种情形**不会**触发下面的「被上限挡掉」，
            //   所以必须单独记一个「次近」，否则照样是一笔糊涂账。
            Object secondUsable = null;
            double secondUsableDist = Double.MAX_VALUE;
            String secondUsableAt = "";
            long secondUsableDwell = 0L;
            // 【第十二轮】离这扇门最近、但**被 4 格上限挡在外面**的那个站台 —— 专门用来回答
            //   「我明明把停留时间改大了，怎么读出来还是 10000ms」：多半是改在了另一条站台上，
            //   而这一条恰好就在 4 格线外一点点，被静默排除掉了。必须把它报出来。
            Object bestRejected = null;
            double bestRejectedDist = Double.MAX_VALUE;
            String bestRejectedAt = "";
            long bestRejectedDwell = 0L;
            int seen = 0;
            for (Object platform : platforms) {
                if (platform == null) {
                    continue;
                }
                seen++;
                double distance;
                if (segmentMode) {
                    double[] seg = segmentXZ(platform);
                    if (seg == null) {
                        continue;
                    }
                    if (Math.abs(seg[1] - y) > MAX_DY && Math.abs(seg[4] - y) > MAX_DY) {
                        continue;
                    }
                    distance = pointToSegmentXZ(x, z, seg[0], seg[2], seg[3], seg[5]);
                    if (distance > MAX_LATERAL) {
                        if (distance < bestRejectedDist) {
                            bestRejectedDist = distance;
                            bestRejected = platform;
                            bestRejectedAt = midText(platform);
                            bestRejectedDwell = dwellOf(platform);
                        }
                        continue;
                    }
                } else {
                    Object mid = midPosOf(platform);
                    if (mid == null) {
                        continue;
                    }
                    double pxv = px(mid);
                    double pyv = py(mid);
                    double pzv = pz(mid);
                    if (Double.isNaN(pxv) || Double.isNaN(pyv) || Double.isNaN(pzv)) {
                        continue;
                    }
                    if (Math.abs(pyv - y) > MAX_DY) {
                        continue;
                    }
                    double dx = pxv - x;
                    double dz = pzv - z;
                    distance = Math.sqrt(dx * dx + dz * dz);
                    if (distance > MAX_HORIZONTAL) {
                        if (distance < bestRejectedDist) {
                            bestRejectedDist = distance;
                            bestRejected = platform;
                            bestRejectedAt = midText(platform);
                            bestRejectedDwell = dwellOf(platform);
                        }
                        continue;
                    }
                }
                if (distance < bestAnyDist) {
                    bestAnyDist = distance;
                    bestAny = platform;
                    bestAnyAt = midText(platform);
                }
                long dwell = dwellOf(platform);
                if (dwell > 0L) {
                    if (distance < bestUsableDist) {
                        // 原来的「最近」降级成「次近」（记下来才好回答「附近还有没有别的站台」）。
                        secondUsable = bestUsable;
                        secondUsableDist = bestUsableDist;
                        secondUsableAt = bestUsableAt;
                        secondUsableDwell = bestUsableDwell;
                        bestUsableDist = distance;
                        bestUsable = platform;
                        bestUsableAt = midText(platform);
                        bestUsableDwell = dwell;
                    } else if (distance < secondUsableDist) {
                        secondUsableDist = distance;
                        secondUsable = platform;
                        secondUsableAt = midText(platform);
                        secondUsableDwell = dwell;
                    }
                }
            }
            if (seen == 0) {
                if (!warnedEmpty) {
                    warnedEmpty = true;
                    LOGGER.warn("[SmoothLift/PsdChime] MTR 的站台集合是空的 ⇒ 读不到时刻表停站时长"
                            + "（多半是这一刻还没同步完；同步完成后会自动生效，不用重启）");
                }
                return -1L;
            }
            if (!loggedCount) {
                loggedCount = true;
                LOGGER.info("[SmoothLift/PsdChime] MTR 站台数据读到 {} 个站台 —— "
                        + "屏蔽门按「这扇门所在站台」的停站时长排提前量", seen);
            }
            // 【第十二轮】站台清单：每帧都会走到这里，但内部按「数量变了才重打」自我节流。
            dumpPlatforms(platforms, seen, segmentMode);
            boolean usable = bestUsable != null;
            Object chosen = usable ? bestUsable : bestAny;
            if (chosen == null) {
                if (!warnedNoMatch) {
                    warnedNoMatch = true;
                    LOGGER.warn("[SmoothLift/PsdChime] 附近 {} 个站台里**一个都够不着**这扇门 ⇒ "
                                    + "本扇门读不到时刻表停站时长（要求：{}），改回「本扇门实测」"
                                    + "（这一站第一次停站没有人声，下一次就正常）",
                            seen, segmentMode
                                    ? "门到站台中轴线的垂直距离 ≤ " + MAX_LATERAL + " 格"
                                    : "门到站台中点的水平距离 ≤ " + MAX_HORIZONTAL + " 格");
                }
                return -1L;
            }
            String chosenAt = usable ? bestUsableAt : bestAnyAt;
            double chosenDist = usable ? bestUsableDist : bestAnyDist;
            long chosenDwell = usable ? bestUsableDwell : dwellOf(chosen);
            if (chosenDwell <= 0L) {
                // 认到了站台，但那一站**没配过停站时间**（MTR 里读出来是 0）。
                // 这种「认到个 0」比「认不到」更容易让人以为读到了，必须点名说清。
                if (!warnedNoMatch) {
                    warnedNoMatch = true;
                    LOGGER.warn("[SmoothLift/PsdChime] 离这扇门最近的那个站台 @{}（{} 格）"
                                    + "**没配过停站时间**（读出来 {}ms）⇒ 读不到有效时长，"
                                    + "改回「本扇门实测」。想让它生效请去**站台界面**设停站时间",
                            chosenAt, String.format("%.1f", chosenDist), chosenDwell);
                }
                return -1L;
            }
            String pick = chosenAt + "|" + chosenDwell + "|" + String.format("%.1f", chosenDist);
            if (!pick.equals(lastPick)) {
                lastPick = pick;
                String st = stationNameOf(chosen);
                LOGGER.info("[SmoothLift/PsdChime] 认到站台 {}@{} —— 离这扇门 {} 格，停站时长 {}ms"
                                + "（{}，{}）",
                        st == null ? "" : "「" + st + "」", chosenAt,
                        String.format("%.1f", chosenDist), chosenDwell,
                        usable ? "①优先有效值" : "①只有这一个候选",
                        segmentMode ? "按中轴线" : "按中点");
                // 【第十二轮】把「就在旁边、却被认亲上限挡掉」的那个站台也报出来 ——
                //   「改了停留时间没用」的绝大多数现场都在这一条上（改到了隔壁那条站台）。
                if (bestRejected != null && bestRejectedDist <= 16.0) {
                    String rst = stationNameOf(bestRejected);
                    LOGGER.warn("[SmoothLift/PsdChime] ↳ 但：离这扇门只有 {} 格的站台 {}@{}"
                                    + "（停站时长 {}ms）**在认亲上限 {} 格之外**，没被选中。"
                                    + "若你改的是那一条，说明这扇门离它太远（它多半是隔壁轨道/另一层）",
                            String.format("%.1f", bestRejectedDist),
                            rst == null ? "" : "「" + rst + "」", bestRejectedAt, bestRejectedDwell,
                            segmentMode ? String.format("%.0f", MAX_LATERAL) : String.format("%.0f", MAX_HORIZONTAL));
                }
                // 【第十二轮】还有个**认不出对错**的情形：两条站台都够得着，我们只认最近的那条。
                //   两条停留时长不同时把「次近」也报出来 —— 用户改的若是那一条，这里就能对上号。
                if (secondUsable != null && secondUsableDwell != chosenDwell) {
                    String sst = stationNameOf(secondUsable);
                    LOGGER.warn("[SmoothLift/PsdChime] ↳ 注意：附近**还有一条够得着的站台** {}@{}"
                                    + "（离这扇门 {} 格，停站时长 {}ms）。这一轮认的是更近的那条（{}格 / {}ms）——"
                                    + "两条都够得着时只按距离挑，若你改的是那一条，请确认这扇门到底挂在哪条站台上",
                            sst == null ? "" : "「" + sst + "」", secondUsableAt,
                            String.format("%.1f", secondUsableDist), secondUsableDwell,
                            String.format("%.1f", chosenDist), chosenDwell);
                }
            }
            return chosenDwell;
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[SmoothLift/PsdChime] 读 MTR 站台停站时长失败，改回「实测学习」：{}", t.toString());
            return -1L;
        }
    }

    /** 读这个站台的停站时长；读不出来返回 {@link Long#MIN_VALUE}（不是 0 —— 0 是「没配过」）。 */
    private static long dwellOf(Object platform) {
        try {
            Object dwell = (mode == MODE_MTR3 ? mtr3GetDwellTime : getDwellTime).invoke(platform);
            return dwell instanceof Number n ? n.longValue() : Long.MIN_VALUE;
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }

    /** 站台两端点 → {@code {ax, ay, az, bx, by, bz}}；读不到返回 {@code null}。 */
    private static double[] segmentXZ(Object platform) {
        try {
            Object a = position1Field.get(platform);
            Object b = position2Field.get(platform);
            if (a == null || b == null) {
                return null;
            }
            return new double[]{
                    ((Number) posGetX.invoke(a)).doubleValue(),
                    ((Number) posGetY.invoke(a)).doubleValue(),
                    ((Number) posGetZ.invoke(a)).doubleValue(),
                    ((Number) posGetX.invoke(b)).doubleValue(),
                    ((Number) posGetY.invoke(b)).doubleValue(),
                    ((Number) posGetZ.invoke(b)).doubleValue()};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 点到**线段**在水平面上的最近距离。
     *
     * <p>★ 站台是长条，认亲必须按线段算：按中点算的话，门站在站台**这一端**时，
     * **对面方向**那个站台的中点很可能更近 ⇒ 安静地读到隔壁站台的停站时长。
     */
    private static double pointToSegmentXZ(double px, double pz,
                                           double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double len2 = vx * vx + vz * vz;
        double t = len2 <= 1.0E-9 ? 0.0 : ((px - ax) * vx + (pz - az) * vz) / len2;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        double dx = px - (ax + t * vx);
        double dz = pz - (az + t * vz);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 日志用的「这个站台在哪」——读它的中点坐标。 */
    private static String midText(Object platform) {
        try {
            Object mid = midPosOf(platform);
            if (mid == null) {
                return "?";
            }
            return String.format("%.0f,%.0f,%.0f", px(mid), py(mid), pz(mid));
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * 【09-28】按**站台 id** 查它属于哪个车站（讲述人报站要念站名，手里只有 platformId）。
     *
     * <p>{@code platformId} 是「身份链」那一侧算好的（{@code DoorView.platformId}），
     * 这里只是拿它去 {@code MinecraftClientData.getInstance().platforms} 里找到那条
     * {@code Platform}，再走 {@link #stationNameOf} 取车站名 —— 与
     * {@link #nearestPlatformExplain} 是同一份数据源。
     *
     * <p>★ 只在**报站要出口那一刻**调用（不是每 tick）：站台少，一次全扫的代价可以忽略。
     *
     * <p>★【09-28 续 6】{@code platformId} **可以是负数**（MTR 的 id 是 {@code Random().nextLong()}），
     * 所以「认不到」只认 {@link #PLATFORM_ID_NONE}，不认正负 —— 见 {@link #PLATFORM_ID_NONE}。
     *
     * @return 车站名；读不到（没装 MTR / 站台还没同步 / 该站台不属于任何车站）返回 {@code null}
     */
    public static String stationNameForPlatform(long platformId) {
        // ★【09-28 续 6】判据是 isPlatformKnown（**不是 platformId <= 0**）—— 站台 id 可以是负数。
        if (!isPlatformKnown(platformId) || !available()) {
            return null;
        }
        try {
            Iterable<?> platforms = elementsOf(mtrPlatforms());
            if (platforms == null) {
                return null;
            }
            for (Object platform : platforms) {
                if (platform == null) {
                    continue;
                }
                if (idOf(platform) == platformId) {
                    return stationNameOf(platform);
                }
            }
        } catch (Throwable ignored) {
            // 与整条「可选绑定」一个口径：读不到只是没站名可念，不影响报站本身
        }
        return null;
    }

    /** 这个站台属于哪个**车站**（{@code platform.area → Station.getName()}）；读不到返回 {@code null}。 */
    private static String stationNameOf(Object platform) {
        if (mode == MODE_MTR3) {
            return mtr3StationNameOf(platform);
        }
        if (areaField == null || getNameMethod == null) {
            return null;
        }
        try {
            Object area = areaField.get(platform);
            if (area == null) {
                return null;
            }
            Object name = getNameMethod.invoke(area);
            return name == null ? null : name.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 【09-30 续 9】站台 id → 它所属**车站**的 id（{@code platform.area → Station.getId()}）。
     *
     * <p>门串身份升「车站级」用：MTR 把一个车站的**每一侧**建成独立的站台对象
     * （LOG11 现场：同名「世纪广场」的两个站台 @(-26,-21,41) 与 @(-33,-21,34)，
     * 各对应一侧门线）—— 以站台 id 当门串身份，两侧就永远各自一半（用户点名的 bug）。
     * 以**车站**为身份，两侧门串自然合并；而时刻表查询仍按每侧自己的站台 id
     * （{@code DoorView.platformId}，见 PsdChimePlayer），上 / 下行到站信息不会串。
     *
     * <p>结果按站台 id 缓存（映射稳定；{@code onDisconnect} 不清 —— 同一世界的映射不变）。
     *
     * @return 车站 id；读不到（没装 MTR / 站台不在任何车站里 / 绑定失败）返回 {@link #PLATFORM_ID_NONE}
     */
    public static long stationIdForPlatform(long platformId) {
        if (!isPlatformKnown(platformId) || !available()) {
            return PLATFORM_ID_NONE;
        }
        if (mode == MODE_MTR3) {
            return mtr3StationIdForPlatform(platformId);
        }
        if (getIdMethod == null || areaField == null || stationIdMethod == null) {
            return PLATFORM_ID_NONE;
        }
        Long cached = STATION_ID_CACHE.get(platformId);
        if (cached != null) {
            return cached;
        }
        long result = PLATFORM_ID_NONE;
        try {
            Object instance = getInstance.invoke(null);
            if (instance == null) {
                return PLATFORM_ID_NONE;
            }
            Iterable<?> platforms = elementsOf(platformsField.get(instance));
            if (platforms != null) {
                for (Object platform : platforms) {
                    if (platform == null) {
                        continue;
                    }
                    Object id = getIdMethod.invoke(platform);
                    if (id instanceof Number n && n.longValue() == platformId) {
                        Object area = areaField.get(platform);
                        if (area != null) {
                            Object stationId = stationIdMethod.invoke(area);
                            if (stationId instanceof Number sn) {
                                result = sn.longValue();
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与整条「可选绑定」一个口径：读不到只是拿不到车站 id，不影响其它功能
        }
        STATION_ID_CACHE.put(platformId, result);
        return result;
    }

    /** 【09-30 续 9】站台 id → 车站 id 的缓存（映射稳定，进程内有效）。 */
    private static final java.util.Map<Long, Long> STATION_ID_CACHE = new java.util.HashMap<>();

    /**
     * 【09-30 续 2】按**站台 id** 查它属于哪条**线路**（讲述人自定义词的 {@code |LC|} / {@code |LE|}
     * 占位符要念线路名，手里只有 platformId）。
     *
     * <p>找法：扫 {@code Data.routes} 里每条 {@code Route} 的 {@code routeData} 列表，
     * 谁的 {@code RouteData.getPlatformId()} 等于目标站台，那条 {@code Route} 就是答案
     * （一条站台被多条线共用时取**扫到的第一条** —— 共线段本来就各有各的说法，挑哪条都算对）。
     * 线路名走 {@code NameColorDataBase.getName()}（与车站名同一个方法），双语仍是 MTR 的
     * {@code 中文|English} 约定，拆中英段的事交给 {@code TrainAnnounceNarrator.namePart}。
     *
     * <p>★ 只在**报站要出口那一刻**调用（不是每 tick）；绑定失败 / 数据没同步时返回
     * {@code null}（占位符替成空串），不影响报站本身。
     *
     * @return 线路名；读不到返回 {@code null}
     */
    public static String lineNameForPlatform(long platformId) {
        if (!isPlatformKnown(platformId) || !available()) {
            return null;
        }
        if (mode == MODE_MTR3) {
            return mtr3LineNameForPlatform(platformId);
        }
        if (routesField == null || routeDataField == null
                || routeDataGetPlatformId == null || getNameMethod == null) {
            return null;
        }
        try {
            Object instance = getInstance.invoke(null);
            if (instance == null) {
                return null;
            }
            Iterable<?> routes = elementsOf(routesField.get(instance));
            if (routes == null) {
                return null;
            }
            for (Object route : routes) {
                if (route == null) {
                    continue;
                }
                Iterable<?> routeData = elementsOf(routeDataField.get(route));
                if (routeData == null) {
                    continue;
                }
                for (Object rd : routeData) {
                    if (rd == null) {
                        continue;
                    }
                    Object id = routeDataGetPlatformId.invoke(rd);
                    if (id instanceof Number n && n.longValue() == platformId) {
                        Object name = getNameMethod.invoke(route);
                        return name == null ? null : name.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与整条「可选绑定」一个口径：读不到只是没线路名可念，不影响报站本身
        }
        return null;
    }

    /**
     * 【1.15 · 第十二轮】把**每一个**站台都摆出来（车站名 + 坐标 + 两端点 + 停留时长），只打一次。
     *
     * <p>存在的唯一理由：用户报「停留时间明明改成 30 秒了，读出来还是 10000ms」时，
     * 光看「认到站台 @x,y,z」这一条**认不出**自己改的是哪一条。把整张表摆出来之后，
     * 三种情况一眼可分：
     * <ol>
     *   <li>表里有一条 30000ms —— 那你改的是**另一条站台**（看它挂在哪个车站 / 哪个坐标）；</li>
     *   <li>表里**全部**都是 10000ms（标了「MTR 默认值」）—— 那这个改动**根本没存进世界**
     *       （改完没确认 / 没权限 / 改的是别的存档）；</li>
     *   <li>表里根本没有你想改的那条 —— 它还没同步到这个客户端。</li>
     * </ol>
     */
    private static void dumpPlatforms(Iterable<?> platforms, int seen, boolean segmentMode) {
        // 先算「内容签名」：数量 + 每个站台的（坐标=停留时长）。只比数量会漏掉「改了停留时间」。
        StringBuilder sig = new StringBuilder();
        for (Object platform : platforms) {
            if (platform == null) {
                continue;
            }
            sig.append(midText(platform)).append('=').append(dwellOf(platform)).append(';');
        }
        String signature = seen + "|" + sig;
        if (signature.equals(dumpedPlatformSignature)) {
            return;
        }
        boolean changed = !dumpedPlatformSignature.isEmpty();
        dumpedPlatformSignature = signature;
        if (changed) {
            LOGGER.info("[SmoothLift/PsdChime] ★ MTR 站台数据**变了**（位置或停留时长有更新）"
                    + "—— 重新列一遍，请对着看你要改的那一条现在是多少：");
        }
        // MTR 的 Platform 构造函数把 dwellTime 写成 10000L（字节码 ldc2_w 10000l），
        // 所以「没被改过」的站台一律读 10000 —— 用它当「默认值」的判据。
        final long mtrDefault = 10000L;
        int index = 0;
        for (Object platform : platforms) {
            if (platform == null) {
                continue;
            }
            index++;
            long dwell = dwellOf(platform);
            String st = stationNameOf(platform);
            StringBuilder sb = new StringBuilder();
            sb.append("[SmoothLift/PsdChime]   站台 #").append(index).append(' ');
            if (st != null) {
                sb.append('「').append(st).append("」 ");
            }
            sb.append('@').append(midText(platform));
            if (segmentMode) {
                double[] seg = segmentXZ(platform);
                if (seg != null) {
                    sb.append(String.format("  (%.0f,%.0f,%.0f)→(%.0f,%.0f,%.0f)",
                            seg[0], seg[1], seg[2], seg[3], seg[4], seg[5]));
                    double len = Math.sqrt(Math.pow(seg[3] - seg[0], 2)
                            + Math.pow(seg[4] - seg[1], 2) + Math.pow(seg[5] - seg[2], 2));
                    sb.append(String.format("  长 %.0f 格", len));
                }
            }
            if (dwell == Long.MIN_VALUE) {
                sb.append("  停留时长读不到");
            } else {
                sb.append("  停留 ").append(dwell).append("ms");
                if (dwell == mtrDefault) {
                    sb.append("（MTR 默认值，从没改过）");
                }
            }
            LOGGER.info(sb.toString());
        }
    }

    /**
     * 「门开始开 → 门全关」的周期（tick）—— 从停站时长算出来，**不用等上一轮实测**。
     *
     * <p>推导全在类注释里，这里只写算式（与 MTR4 字节码一一对应）：
     * <pre>
     *   closeAt        = max(D/2, D - 4200)      // 门开始关（= 发车指令）的时刻，相对「停稳」
     *   全开 → 开始关  = closeAt - 1000
     *   开门瞬间 → 全关 = (closeAt - 1000) + 门程
     * </pre>
     *
     * @param dwellMs     站台停站时长（{@link #dwellMsAt} 的结果）
     * @param travelTicks 门程（{@code > 0} 时用实测学到的 {@code globalTravelTicks}，
     *                    否则用 {@link #DEFAULT_TRAVEL_TICKS}）
     * @return tick 数；{@code dwellMs <= 0} 时返回 {@code -1}（读不到）
     */
    public static long cycleTicksForDwell(long dwellMs, long travelTicks) {
        if (dwellMs <= 0L) {
            return -1L;
        }
        long closeAt = Math.max(dwellMs / 2L, dwellMs - CLOSE_LEAD_MS);
        long openToClose = closeAt - DOOR_DELAY_MS;
        if (openToClose < 0L) {
            // 停站太短：MTR 还没等门全开就要发车了。这里按「门一开始动就往回走」算，
            // 不能让它变成负数（那会让上游的 `cycle <= leadTicks` 判据误判成「塞得下」）。
            openToClose = 0L;
        }
        long travel = travelTicks > 0L ? travelTicks : DEFAULT_TRAVEL_TICKS;
        return (openToClose + 49L) / 50L + travel;
    }

    /**
     * 【1.15 · 第十二轮】想让**整条素材**塞进周期里，这一站**至少**要把停留时长设到多少毫秒。
     *
     * <p>给日志用：以前只报「周期 8800ms 塞不下整条素材 10806ms」，用户拿到这两个数字还得自己
     * 反推「那我该设多少秒」—— 而这两个数之间隔着 {@link #cycleTicksForDwell} 那条折线，
     * 手算极易算错（实测：素材 10806ms 时，门程按默认 80 tick 算要 13000ms，
     * 按实测学到的 29 tick 算要 15000ms —— **同一个素材，答案随门程而变**）。
     * 所以这个数字必须由**同一套公式**反解出来，不能靠用户心算。
     *
     * @param materialMs  整条提示音素材的时长（ms）
     * @param travelTicks 门程 tick（与 {@link #cycleTicksForDwell} 用同一个值，才能保证自洽）
     * @return 毫秒；{@code materialMs <= 0} 或算不出（要求超过 10 分钟）时返回 {@code -1}
     */
    public static long minDwellForMaterialMs(long materialMs, long travelTicks) {
        if (materialMs <= 0L) {
            return -1L;
        }
        long leadTicks = (materialMs + 49L) / 50L;             // 素材 → tick（向上取整）
        long travel = travelTicks > 0L ? travelTicks : DEFAULT_TRAVEL_TICKS;
        // 先解不等式取一个下界：D 大时 closeAt = D - 4200，
        //   cycle = (D - 4200 - 1000 + 49)/50 + travel ≥ leadTicks + 1
        //   ⇒ D ≥ 5200 + 50 * (leadTicks + 1 - travel)
        long d = 5200L + 50L * (leadTicks + 1L - travel);
        if (d < 1000L) {
            d = 1000L;
        }
        d = ((d + 999L) / 1000L) * 1000L;                      // 向上取整到整秒（站台界面就是按秒设的）
        // 再拿真式子校正（整数除法 + max(D/2, …) 那半支会让下界偶尔差一档）。
        while (cycleTicksForDwell(d, travel) < leadTicks + 1L) {
            d += 1000L;
            if (d > 600_000L) {
                return -1L;                                    // 10.8 秒的素材不可能要到这一步
            }
        }
        return d;
    }
}
