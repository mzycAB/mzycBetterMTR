package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每个扶梯阶梯 BlockState 的「可动画模型」缓存。
 *
 * <p>拿到 MTR 那个 state 的 baked model，把其中用我们静态底图（
 * {@code smoothlift:block/step_static_up|down}）的那些面挑出来 —— 也就是会动的阶梯面 ——
 * 烘焙期就把它们**预拆成渲染可直接消费的浮点数据**（见 {@link StepModel}）：
 * <ul>
 *   <li>顶点坐标：从 {@code BakedQuad} 的 int 顶点数组解出三组 {@code float}（方块相对坐标）；</li>
 *   <li>帧内 uv：把 atlas uv 归一化成帧内坐标 {@code u∈[0,1], v∈[0,1]}；</li>
 *   <li>法线：按面的方向查常量表（阶梯面全是轴对齐平面，无需逐顶点解包）；</li>
 *   <li>明暗系数：按面的方向查原版同款方向 shade（UP 1.0 / DOWN 0.5 / 南北 0.8 /
 *       东西 0.6）；染色槽位：{@code tintIndex} 原样保留。</li>
 * </ul>
 * 渲染时（{@link EscalatorStepCache} 建静态几何、{@link EscalatorStepRenderer} 绘制）
 * 不再有任何「解码 int 顶点 → 写 uv → 走 {@code ModelBlockRenderer.tesselateBlock}」的通用路径，
 * 而是每像素块只做 1 次光照查询，然后逐个顶点直接调用 {@code BufferBuilder.vertex(14 参)} 落数据。
 * 因为帧号已不由顶点承载（见 {@link #bakeQuad}），这份数据**与时间无关**，
 * 于是只需在「段内容变了」时写一次，之后一直复用。
 *
 * <p>用 {@code #particle}（静态侧板/外壳）的面不归我们管，留给 MTR 自己按原版渲染。
 *
 * <p>注意 MTR 3.x 的阶梯面其实是**两族贴图**：斜坡（{@code slope_*}）用
 * {@code mtr:block/escalator_up|down}（面 uv 是小窗口），平层（{@code flat_*} /
 * {@code transition_bottom_*}）用的是**另一张** {@code mtr:block/escalator_flat_up|down}
 * （面 uv 铺满整张 sprite）。资源覆盖把两族模型的 {@code #step} 都换成了同一个标记 sprite，
 * 靠 sprite 名字分不出来，所以这里额外记下「这个模型的面是不是铺满整张 sprite」
 * （{@code flat} 标志），由渲染方据此去挑对应的贴图族（见 {@link EscalatorStepTextures}）。
 * uv 的帧内换算两族完全一致，不需要任何特殊处理。
 */
public final class EscalatorStepModels {
    /**
     * 由 {@code gen_assets.py} 生成的底图标记。它现在是**全透明**的 —— 内容不重要，
     * 存在的意义是「占住一个 sprite 槽位 + 提供有效 uv」，让这里能靠 sprite **名字**认出
     * MTR 阶梯模型里哪些面是台阶面；同时 MTR 原版渲染它时会被 cutout alpha 裁剪整片丢弃，
     * 于是原版那份静止台阶面被彻底隐藏，只剩我们重绘的这份。
     */
    /** 同包 {@link EscalatorModelOverride} 在烘模型前注入的就是这两个标记 sprite。 */
    static final ResourceLocation MARKER_UP =
            new ResourceLocation("smoothlift", "block/step_static_up");
    static final ResourceLocation MARKER_DOWN =
            new ResourceLocation("smoothlift", "block/step_static_down");

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 方块模型里的方向槽位：0..5 是六个方向，6 是「无方向」那组（没有 cullface 的面）。 */
    private static final int NO_DIRECTION = 6;

    private static final Map<BlockState, StepModel> CACHE = new HashMap<>();
    private static final Set<BlockState> NOT_ANIMATED = new HashSet<>();
    private static final RandomSource BAKE_RANDOM = RandomSource.create(0L);

    /** 六个方向的（单位）法线常量表，按下标 {@code Direction.get3DDataValue()} 取。 */
    private static final float[][] DIR_NORMALS = new float[6][3];

    static {
        for (Direction direction : Direction.values()) {
            DIR_NORMALS[direction.get3DDataValue()] = new float[]{
                    direction.getStepX(), direction.getStepY(), direction.getStepZ()};
        }
    }

    private static Object builtFor;

    private EscalatorStepModels() {
    }

    /** 返回这个 state 的阶梯面动画模型；不是「会动的阶梯」则返回 null。 */
    public static StepModel get(BlockState state) {
        if (state == null) {
            return null;
        }
        StepModel cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        if (NOT_ANIMATED.contains(state)) {
            return null;
        }
        StepModel built = build(state);
        if (built == null) {
            NOT_ANIMATED.add(state);
            return null;
        }
        CACHE.put(state, built);
        return built;
    }

    /**
     * 资源重载后贴图集里的 sprite 会换新，已缓存的 uv 必须丢掉重建。
     *
     * @return 本次是否真的清了缓存。<b>调用方必须处理 true</b>：模型重烘意味着顶点里的
     *         uv 可能变了，而【1.29】的静态几何缓存是**跨帧复用**的 —— 它自己看不见
     *         「模型被重烘」这件事（段的 revision 没变、组表 epoch 也没变）。
     *         不处理的话，画面会拿**旧 uv 去采新贴图**（错位 / 串帧），
     *         而且这种错在换资源包之前都不会自愈。
     */
    public static boolean tickReloadCheck() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        Object current = minecraft.getResourceManager();
        if (current == builtFor) {
            return false;
        }
        builtFor = current;
        clear();
        return true;
    }

    public static void clear() {
        CACHE.clear();
        NOT_ANIMATED.clear();
    }

    private static StepModel build(BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        net.minecraft.client.resources.model.BakedModel source;
        try {
            source = minecraft.getModelManager().getBlockModelShaper().getBlockModel(state);
        } catch (Throwable t) {
            LOGGER.error("[SmoothLift] 取方块模型失败: {}", state, t);
            return null;
        }
        if (source == null) {
            return null;
        }

        // 先扫一遍：收集所有阶梯面，同时定 up/down/平层族。
        List<float[]> vertices = new ArrayList<>();
        List<Float> shades = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        boolean up = false;
        boolean down = false;
        boolean allFullWindow = true;
        boolean anyQuad = false;

        for (int slot = 0; slot <= NO_DIRECTION; slot++) {
            Direction direction = slot < NO_DIRECTION ? Direction.from3DDataValue(slot) : null;
            List<BakedQuad> sourceQuads = source.getQuads(state, direction, BAKE_RANDOM);
            if (sourceQuads == null) {
                continue;
            }
            for (BakedQuad quad : sourceQuads) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite == null || sprite.contents() == null) {
                    continue;
                }
                ResourceLocation id = sprite.contents().name();
                boolean isUp = MARKER_UP.equals(id);
                boolean isDown = MARKER_DOWN.equals(id);
                if (!isUp && !isDown) {
                    continue;
                }
                if (isUp) {
                    up = true;
                } else {
                    down = true;
                }

                float[] quadData = bakeQuad(quad, sprite);
                if (quadData == null) {
                    continue;
                }
                anyQuad = true;
                vertices.add(quadData);
                // 方向明暗（和原版 {@code ModelBlockRenderer} 的 simple 路径一致：UP 1.0、
                // DOWN 0.5、南北 0.8、东西 0.6）。不做原版的逐顶点 AO —— 那是每顶点查多个
                // 邻块光照的昂贵操作，而阶梯面是密集小平面，用统一的方向 shade 已足够立体。
                shades.add(directionShade(quad.getDirection()));
                tints.add(quad.getTintIndex());
                if (!fullWindow(quad, sprite)) {
                    allFullWindow = false;
                }
            }
        }

        if (!anyQuad) {
            return null;
        }
        if (up && down) {
            LOGGER.warn("[SmoothLift] 同一个 state 里同时出现了上行和下行阶梯贴图: {}", state);
        }
        // 整个模型属于哪一族贴图：所有阶梯面都「铺满整张 sprite」才是平层族
        // （MTR 3.x 的 flat_* / transition_bottom_*，它们的 #step 面 uv 是 [0,0,16,16]）。
        // 斜坡模型里几十个阶梯面全是小窗口，所以一定不是平层族。
        //
        // 【1.26】「刷子刷停（status=false）固定显示第 0 帧」这条判据也在这里算好：
        // status 是**方块状态的一部分**，而本缓存就是按 BlockState 建的，
        // 所以它跟模型一样是「这个状态"的常量 —— 渲染热路径不必再逐属性扫一遍
        // EscalatorUtil.getBooleanProperty（那要遍历 state.getProperties() 并逐个比名字）。
        boolean stopped = !EscalatorUtil.getBooleanProperty(state, "status", true);
        return new StepModel(up, allFullWindow, stopped, vertices, shades, tints);
    }

    /**
     * 面的方向明暗系数（原版 {@code BlockColors.getShade} / simple 渲染路径的语义）：
     * 顶面最亮，底面最暗，水平侧面居中。
     */
    private static float directionShade(Direction direction) {
        if (direction == null) {
            return 1.0F;
        }
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case EAST, WEST -> 0.6F;
        };
    }

    /**
     * 把一个阶梯面拆成渲染快路径要用的 32 个 float（4 顶点 ×
     * {@code x,y,z,u,v,nx,ny,nz}）：
     * <ul>
     *   <li>{@code x,y,z}：方块相对坐标（0..1 或负值）；</li>
     *   <li>{@code u,v}：<b>帧内</b>归一化坐标（{@code u,v ∈ [0,1]}）。<b>这里不再做任何
     *       「折进条带」的换算</b> —— 贴图在 {@link EscalatorStepTextures} 里已经被切成
     *       「每帧一张 320×320」，所以 {@code v} 直接就是那张帧贴图里的纵向位置。
     *       旧版（1.28 及以前）这里要 {@code (band + v) / bandCount}，帧号焊在顶点里 ⇒
     *       几何与时间绑死；现在帧号由「绑哪张帧贴图」表达，顶点因此与时间无关、可缓存。</li>
     *   <li>{@code nx,ny,nz}：单位法线（阶梯面全是轴对齐平面，直接按面的方向取常量）。</li>
     * </ul>
     */
    private static float[] bakeQuad(BakedQuad quad, TextureAtlasSprite sprite) {
        int[] vertices = quad.getVertices();
        if (vertices.length != 32) {
            return null;
        }
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float du = u1 - u0;
        float dv = v1 - v0;
        if (du == 0.0F || dv == 0.0F) {
            return null;
        }

        Direction direction = quad.getDirection();
        float[] normal = direction == null
                ? DIR_NORMALS[Direction.UP.get3DDataValue()]
                : DIR_NORMALS[direction.get3DDataValue()];

        float[] data = new float[32];
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            int o = i * 8;
            // xyz：float 直接解包（顶点数据低位在前，与 Float.floatToRawIntBits 互逆）。
            data[base] = Float.intBitsToFloat(vertices[o]);
            data[base + 1] = Float.intBitsToFloat(vertices[o + 1]);
            data[base + 2] = Float.intBitsToFloat(vertices[o + 2]);
            // uv：atlas 坐标归一化到帧内坐标。
            data[base + 3] = (Float.intBitsToFloat(vertices[o + 4]) - u0) / du;
            data[base + 4] = (Float.intBitsToFloat(vertices[o + 5]) - v0) / dv;
            // 法线：同一面的四个顶点共用（轴对齐平面）。
            data[base + 5] = normal[0];
            data[base + 6] = normal[1];
            data[base + 7] = normal[2];
        }
        return data;
    }

    /** 这个面是不是「铺满整张 sprite」的平层阶梯面（决定该采哪一族条带贴图）。 */
    private static boolean fullWindow(BakedQuad quad, TextureAtlasSprite sprite) {
        int[] vertices = quad.getVertices();
        float minU = 1.0F, maxU = 0.0F, minV = 1.0F, maxV = 0.0F;
        float u0 = sprite.getU0(), v0 = sprite.getV0();
        float du = sprite.getU1() - u0, dv = sprite.getV1() - v0;
        if (du == 0.0F || dv == 0.0F) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            float nu = (Float.intBitsToFloat(vertices[i * 8 + 4]) - u0) / du;
            float nv = (Float.intBitsToFloat(vertices[i * 8 + 5]) - v0) / dv;
            minU = Math.min(minU, nu);
            maxU = Math.max(maxU, nu);
            minV = Math.min(minV, nv);
            maxV = Math.max(maxV, nv);
        }
        return (maxU - minU > 0.9F) && (maxV - minV > 0.9F);
    }

    /**
     * 每个 state 一份；只暴露预烘焙的阶梯面数据，静态面不参与本次绘制。
     *
     * <p>数据全部是「烘焙期定死」的常量（相对坐标、帧内 uv、法线、阴影系数、染色槽位），
     * 渲染热路径只读它们、不分配、不解码。
     */
    public static final class StepModel {
        /** 每个顶点 8 个 float：x,y,z,u,v,nx,ny,nz；每个面 4 个顶点 = 32 个 float。 */
        static final int FLOATS_PER_VERTEX = 8;
        static final int FLOATS_PER_QUAD = 32;

        private final boolean up;
        private final boolean flat;
        /** 【1.26】这个状态是不是「被刷子刷停」（{@code status=false}）—— 烘焙期判定、渲染只读。 */
        private final boolean stopped;
        private final int quadCount;
        /** 交错的预烘焙顶点数据（{@code FLOATS_PER_QUAD × quadCount}）。 */
        private final float[] data;
        /** 每面的方向明暗系数（UP 1.0 / DOWN 0.5 / 南北 0.8 / 东西 0.6）。 */
        private final float[] shades;
        /** 每面的染色槽位（-1 = 不染色）。 */
        private final int[] tints;

        private StepModel(boolean up, boolean flat, boolean stopped, List<float[]> vertices,
                          List<Float> shades, List<Integer> tints) {
            this.up = up;
            this.flat = flat;
            this.stopped = stopped;
            this.quadCount = vertices.size();
            int total = this.quadCount * FLOATS_PER_QUAD;
            this.data = new float[total];
            this.shades = new float[this.quadCount];
            this.tints = new int[this.quadCount];
            for (int q = 0; q < this.quadCount; q++) {
                System.arraycopy(vertices.get(q), 0, this.data, q * FLOATS_PER_QUAD, FLOATS_PER_QUAD);
                this.shades[q] = shades.get(q);
                this.tints[q] = tints.get(q);
            }
        }

        /** 这条扶梯用的是上行贴图还是下行贴图（决定用哪个渲染类型）。 */
        public boolean up() {
            return up;
        }

        /**
         * 这个模型属于平层贴图族（MTR 3.x 的 {@code escalator_flat_up|down}）还是斜坡贴图族。
         * 渲染时要按它去挑纹理不同的渲染类型。
         */
        public boolean flat() {
            return flat;
        }

        /**
         * 这个状态是不是「停止状态」（MTR 的 {@code status=false}，被刷子刷停的扶梯）。
         *
         * <p>停止的扶梯阶梯是**静止**的：固定显示第 0 帧，不按速度推进 —— 否则扶梯虽然停了，
         * 单独设置过的速度仍会让阶梯继续滚动。
         *
         * <p>【1.26】这个判断跟模型一样，是「这个 BlockState 的常量」，烘焙期算一次即可；
         * 旧版渲染热路径里每帧每方块都要 {@code EscalatorUtil.getBooleanProperty} 一次
         * （遍历该方块的全部属性并逐个比名字），纯属重复劳动。
         */
        public boolean stopped() {
            return stopped;
        }

        public int quadCount() {
            return quadCount;
        }

        /** 预烘焙顶点数据（只读）。 */
        public float[] data() {
            return data;
        }

        /** 每面阴影系数（只读）。 */
        public float[] shades() {
            return shades;
        }

        /** 每面染色槽位（只读）。 */
        public int[] tints() {
            return tints;
        }
    }
}