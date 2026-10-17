package smooth.lift.client;

/**
 * 「阶梯动画速度」的分组表 —— 静态几何缓存的地基之一。
 *
 * <h2>为什么需要「速度组」这个东西</h2>
 * 阶梯动画的帧号是 {@code floor((刻 + 部分刻) × 速度/原版速度)}，而帧号是**唯一**随时间变化的量。
 * 全量重写（【1.29】）的做法是把几何**缓存成静态顶点缓冲**，于是「帧号」就不能再焊在顶点里
 * —— 它只能通过「这一次绘制绑定哪张帧贴图」来表达。而贴图是按帧准备的（见
 * {@link EscalatorStepTextures}），所以绘制时只问一句：**这一组扶梯现在该显示第几帧**。
 *
 * <p>所以「速度组」就是「同一时刻帧号必然相同的一批扶梯」。把速度**量化成密集组号**之后：
 * <ul>
 *   <li>缓存可以按 {@code (16³ 段 × 组号 × 贴图族)} 建，一个槽一份顶点缓冲；</li>
 *   <li>同一组内的所有方块**帧号一定相同** ⇒ 一次贴图切换就能画完整组
 *       —— 这正是用户说的「同速度的算一组」，也是「复制 N 份渲染引擎」里那个 N；</li>
 *   <li>视野里没有的组**一次都不碰**（贴图不切、缓冲不画），
 *       即用户要求的「渲染范围外的扶梯组不加载」。</li>
 * </ul>
 *
 * <h2>为什么量化</h2>
 * 速度是浮点配置值。如果按「逐位相等」分组，两个配置上看起来都是 {@code 2.0} 的扶梯，
 * 只要有一条经过一次乘除就可能差 1 ulp，于是被拆成两组 ——
 * 表现是「同速的两条扶梯偶尔错开一帧」，而且会**静默**多花一份缓存。
 * 量化到 1/1000 之后，肉眼完全看不出差别（帧率差 0.1%），但分组是稳定的。
 *
 * <h2>组号是「追加」的，但组表变化会作废已建缓存</h2>
 * 新速度出现时只往后追加组号，已有组号不移动。不过「某条扶梯该归哪一组」这个映射变了，
 * 已经建好的缓存可能把它放在**旧组**的槽里，所以这里会让 {@link #epoch()} 前进一步 ——
 * 渲染端看到 epoch 变了就把缓存整片作废重建（最多多重建一帧，随后稳定）。
 * 速度配置是低频事件，这个代价可以忽略。
 */
public final class EscalatorStepGroups {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("smoothlift");

    /**
     * 最多同时跟踪多少个不同的动画速度。
     *
     * <p>这是一个**保护性上限**：分组数直接决定缓存槽位数与绘制批次数，不能让一个把
     * 每条扶梯都设成不同速度的存档把帧时间顶穿。一个地图上真正不同的扶梯速度通常只有 1–5 个，
     * 16 已经非常宽松；超过之后退化为「最近速度组」并只警告一次（见 {@link #indexFor}）。
     */
    public static final int MAX_GROUPS = 16;

    /** 每个组占 4 个缓存槽（平层/斜坡 × 上/下行），见 {@code EscalatorStepCache.slotOf}。 */
    public static final int SLOTS_PER_GROUP = 4;

    /** 缓存槽总数。 */
    public static final int SLOT_COUNT = MAX_GROUPS * SLOTS_PER_GROUP;

    /** 速度量化精度：1/1000 刻的帧率差别肉眼不可见，但足以把浮点抖动归一。 */
    private static final double QUANTUM = 1000.0;

    private static final double[] speeds = new double[MAX_GROUPS];
    private static int count;
    private static long epoch = 1L;
    private static boolean warnedOverflow;

    private EscalatorStepGroups() {
    }

    /** 已登记的组数（含固定为「不动」的第 0 组）。 */
    public static int count() {
        return count;
    }

    /**
     * 组表版本号。**任何会让「某条扶梯该归哪一组」发生变化的操作都必须让它前进**：
     * 新速度出现、速度配置被改、退出世界/换维度、资源重载。
     */
    public static long epoch() {
        return epoch;
    }

    /** 第 {@code index} 组的代表速度（渲染端据此算帧号）。 */
    public static double speedOf(int index) {
        return index >= 0 && index < count ? speeds[index] : 0.0;
    }

    /**
     * 取某个动画速度对应的组号（不在表里就登记一个新组）。
     *
     * <p>速度 {@code <= 0} / 非法值一律归到**第 0 组**：第 0 组的代表速度是 {@code 0.0}，
     * 于是它的帧号恒为 0 —— 这正好就是「被刷子刷停的扶梯阶梯固定显示第 0 帧」那条语义
     * （旧版是靠 {@code model.stopped()} 单独判一次的，现在它自然落在第 0 组里）。
     */
    public static int indexFor(double speed) {
        double key = quantize(speed);
        for (int i = 0; i < count; i++) {
            if (speeds[i] == key) {
                return i;
            }
        }
        if (count < MAX_GROUPS) {
            speeds[count] = key;
            count++;
            // 组表变了 ⇒ 已经建好的缓存可能把方块放在旧组的槽里，必须整片作废。
            epoch++;
            return count - 1;
        }
        // 到上限了：退化为「最近的那一组」。只警告一次，避免每帧刷日志。
        int nearest = nearest(key);
        if (!warnedOverflow) {
            warnedOverflow = true;
            LOGGER.warn(
                    "[SmoothLift] 扶梯动画速度的不同取值超过 {} 组，速度 {} 将按最接近的 {} 播放"
                            + "（想要完全精确请减少不同速度的数量）",
                    MAX_GROUPS, key, speeds[nearest]);
        }
        return nearest;
    }

    /** 退出世界 / 换维度：清空组表（第 0 组「不动」保留），并作废全部缓存。 */
    public static void reset() {
        speeds[0] = 0.0;
        count = 1;
        warnedOverflow = false;
        epoch++;
    }

    /**
     * 「已经建好的缓存不再可信」时前进 {@link #epoch()}。三种触发：
     * <ul>
     *   <li>速度配置被改动：组表本身不用清（同速扶梯的分组关系没变），
     *       但**帧号**会变，而且可能有扶梯换了速度；</li>
     *   <li>★ 模型被重烘（资源重载 / {@code /mtrxr} 切换）：顶点里烘焙的 uv 可能变了 ——
     *       静态几何缓存是跨帧复用的，它看不见这件事（段的 revision 没变），
     *       只能靠这里作废，否则会拿旧 uv 去采新贴图（错位 / 串帧）；</li>
     *   <li>{@link #indexFor} 登记了一个新速度（见那里的注释）。</li>
     * </ul>
     * 缓存端看到 epoch 变了就把每个可见段重建一遍（最多多重建一帧，随后稳定）。
     * 这些都是低频事件，代价可以忽略。
     */
    public static void invalidate() {
        epoch++;
    }

    private static double quantize(double speed) {
        if (Double.isNaN(speed) || speed <= 0.0) {
            return 0.0;
        }
        double quantized = Math.round(speed * QUANTUM) / QUANTUM;
        return quantized > 0.0 ? quantized : 0.0;
    }

    private static int nearest(double key) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double distance = Math.abs(speeds[i] - key);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    static {
        // 第 0 组必须是「不动」，indexFor 与 renderer 的语义都依赖它。
        speeds[0] = 0.0;
        count = 1;
    }
}
