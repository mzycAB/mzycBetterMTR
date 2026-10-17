package smooth.lift.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时把 MTR 的扶梯阶梯动画贴图拆成 **每帧一张独立贴图**，并给每帧一个渲染类型。
 *
 * <h2>为什么必须是「每帧一张」而不是「一条竖排条带」</h2>
 * 这是【1.29】全量重写的**地基**。旧版（1.24–1.28）把整条 16 帧竖排图当成一张
 * {@code 320x5120} 的贴图，然后靠顶点里的 {@code v = (帧号 + 帧内v) / 16} 去选帧。
 * 那个做法把「帧号」**焊死在顶点数据里**，于是：
 * <ul>
 *   <li>只要帧号变了（而它每刻都在变），缓存好的几何就整份失效 ——
 *       这就是旧版**每帧都得把视野内每个阶梯方块的 380 个顶点全部重写一遍**的根本原因；</li>
 *   <li>几何没法缓存 ⇒ 帧时间随「视野内阶梯方块数」线性增长，扶梯一多就崩。</li>
 * </ul>
 *
 * <p>改成「每帧一张贴图」之后，顶点里只剩**帧内 uv**（与时间无关），几何就变成了**静态数据**：
 * 同一段扶梯的顶点只要没被编辑过，永远不用重算。每刻要变的只剩「这一次绘制绑定哪张帧贴图」，
 * 而那是**一次贴图切换**，不是几十万个顶点重算。
 *
 * <p>代价只有一次性成本：启动/重载时把竖排图**切成 16 张** {@code 320x320}。
 * 显存一点都不多花（16 × 320×320 与 320×5120 是同一份像素），见下面「★ 顺手省掉一半显存」。
 *
 * <h2>为什么不能靠「纹理矩阵偏移」省掉切图</h2>
 * 曾经考虑过一条更省的路：几何里存帧内 uv，绘制时用
 * {@code RenderSystem.setTextureMatrix(new Matrix4f().translation(0, band/16, 0))} 把 v 整体挪到
 * 那一帧上（{@code OffsetTexturingStateShard} 就是干这个的），这样**连贴图都不用切**。
 * <b>这条路在 1.20.4 上不成立</b>，已实测证伪：
 * <pre>
 *   逐个数过客户端 jar 里全部 181 个核心着色器 ——
 *   {@code TextureMat} 只出现在 glint 系（{@code rendertype_entity_glint.vsh} 等 4 个）里；
 *   而 {@code rendertype_cutout.vsh} 第 30 行是 {@code texCoord0 = UV0;}。
 * </pre>
 * 也就是说方块系的 cutout 着色器**根本不读纹理矩阵**，`VertexBuffer.drawWithShader` 把这个
 * uniform 传上去也没人用。要让它生效就得自带一份核心着色器（复制原版 cutout 再加一行），
 * 那会把「和光影包 / Sodium 的兼容性」押上去 —— 而这里只是省下一次贴图切换，不值得。
 * 所以最终选「每帧一张贴图」这条只用原版公开能力、坏不了画面的路。
 *
 * <h2>★ 顺手省掉一半显存：up / down 两张图其实是同一份像素</h2>
 * MTR 的 {@code escalator_up.png} 与 {@code escalator_down.png} 是**逐像素完全相同**的两张图，
 * 区别只在同名 {@code .mcmeta} 里的**帧序**（up 是 0..15，down 是 15..0）。旧版把两张都当
 * 竖排贴图各注册一份，等于**白占一份显存**（320x5120 RGBA = 6.25 MB）。
 *
 * <p>这里只按「上行」那张切帧，下行**复用同一批帧贴图**、只是用下行自己的帧序去索引
 * （见 {@link #bandFor}）。于是同一份像素同时服务两个方向。
 *
 * <p>但这条假设**不靠注释保证**：装载时会真的把两张图的像素比一遍
 * （{@link #pixelsEqual}，全量逐像素，不是抽样），<b>不相等就退回各自一份</b> ——
 * 也就是说「省显存」是**验证过才生效**的优化，不是赌出来的。
 *
 * <h2>MTR 3.x 与 4.x 的阶梯贴图不一样（这条坑踩过一次，保留）</h2>
 *
 * <table border="1">
 *   <tr><th>阶梯形状</th><th>MTR 3.x</th><th>MTR 4.x</th></tr>
 *   <tr><td>斜坡 slope_*</td>
 *       <td>{@code escalator_up|down.png} 256x4096（一帧 256x256），面 uv 是小窗口</td>
 *       <td>{@code escalator_up|down.png} 320x5120（一帧 320x320），面 uv 是小窗口</td></tr>
 *   <tr><td>平层 flat_* / transition_bottom_*</td>
 *       <td><b>另有一张</b> {@code escalator_flat_up|down.png} 64x1024，
 *           而且面 uv 是<b>整张贴图</b> {@code [0,0,16,16]}</td>
 *       <td>没有独立贴图，与斜坡共用 {@code escalator_up|down.png}，
 *           面 uv 变成小窗口（如 {@code [0,12.8,3.2,16]}）</td></tr>
 * </table>
 *
 * <p><b>为什么必须分开</b>：斜坡那张竖排图的每一帧里只稀疏地排着许多小台阶贴图块，
 * 实测每帧**只有约 6% 的像素是不透明的**；而平层那张每帧**100% 不透明**（整块传送带花纹）。
 * 一旦把「铺满整张 sprite」的平层面拿去斜坡图里采，采到的 94% 是透明空隙，在 cutout 层会被
 * alpha 裁剪丢掉，表现就是「**平层/过渡块的台阶面几乎整片不见**」。
 *
 * <p>MTR 4.x 没有 {@code escalator_flat_*.png}，所以平层族按**可选**处理：读不到就自动退回
 * 斜坡族（4.x 的平层 uv 本来就是小窗口，退回才是对的）。
 *
 * <p>贴图直接从 MTR 的资源包里读（不复制、不打包 MTR 的素材），因此只需要 MTR 在场。
 */
public final class EscalatorStepTextures {

    /**
     * 一条竖排帧贴图的帧数（MTR 3.x / 4.x 都是 16 帧，与 {@code .mcmeta} 里的帧序长度一致）。
     *
     * <p>它是**动画长度**的唯一来源：{@link EscalatorStepRenderer#frameOf} 对它取模，
     * 这里也按它切帧。两处必须一致，所以只留这一个常量。
     */
    public static final int FRAMES = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 贴图在运行时注册的名字前缀。 */
    private static final String DYNAMIC_PREFIX = "dynamic/escalator_step_";

    // ---- MTR 源贴图 ----
    private static final ResourceLocation MTR_SLOPE_UP =
            new ResourceLocation("mtr", "textures/block/escalator_up.png");
    private static final ResourceLocation MTR_SLOPE_DOWN =
            new ResourceLocation("mtr", "textures/block/escalator_down.png");
    private static final ResourceLocation MTR_FLAT_UP =
            new ResourceLocation("mtr", "textures/block/escalator_flat_up.png");
    private static final ResourceLocation MTR_FLAT_DOWN =
            new ResourceLocation("mtr", "textures/block/escalator_flat_down.png");

    /** 已经为哪个 ResourceManager 构建过（资源重载后会换实例，于是自动重建）。 */
    private static Object builtFor;
    /** 构建失败后，隔多少刻再重试一次，避免每帧都刷日志。 */
    private static int retryCooldown;

    /**
     * 一族的**一整套帧贴图**：16 张 {@code DynamicTexture} + 与它们一一对应的
     * {@code RenderType}。
     *
     * <p>「上行」与「下行」通常是**同一个实例**（像素相同，只是用不同帧序去索引），
     * 只有像素真的不同时才会各有一份。
     */
    private static final class FrameSet {
        /** 每帧一张；{@code DynamicTexture} 持有同一批 NativeImage（它负责上传）。 */
        final DynamicTexture[] textures;
        /** 每帧一个渲染类型，绑定到那一帧的贴图。 */
        final RenderType[] types;
        /** 用掉了几帧（正常等于 {@link #FRAMES}；源图帧数不足时会少于它，见 {@link #split}）。 */
        final int used;

        FrameSet(DynamicTexture[] textures, RenderType[] types, int used) {
            this.textures = textures;
            this.types = types;
            this.used = used;
        }

        RenderType type(int band) {
            if (band < 0) {
                return types[0];
            }
            // 源图帧数不足 FRAMES 时，多出来的帧在 split() 里被填成「最后一帧的副本」，
            // 所以夹到 used-1 才和那个语义一致 —— 返回 types[0] 会让动画末尾闪回开头。
            return types[band < used ? band : used - 1];
        }
    }

    /**
     * 一族阶梯贴图（斜坡 / 平层）。一族里「上行」「下行」两个方向各自有**帧序**，
     * 但帧贴图通常是共用的。
     */
    private static final class Family {
        /** 日志里用的中文名。 */
        final String label;
        /** 渲染类型名用的 ASCII 键（{@code RenderType.create} 的名字保持纯 ASCII）。 */
        final String key;
        final ResourceLocation pngUp;
        final ResourceLocation pngDown;
        /** 上行 / 下行的帧序（下标 = 动画帧号，值 = 该帧在竖排图里的原始序号）。 */
        int[] orderUp = identityOrder();
        int[] orderDown = identityOrder();
        /** 上行 / 下行各自实际使用的帧贴图集（像素相同时是同一个实例）。 */
        FrameSet setUp;
        FrameSet setDown;
        /** 帧贴图是否已经构建好。 */
        boolean ready;
        /** 两个方向是否共用了同一份像素（省显存的那条优化是否生效）。 */
        boolean shared;

        Family(String label, String key, ResourceLocation pngUp, ResourceLocation pngDown) {
            this.label = label;
            this.key = key;
            this.pngUp = pngUp;
            this.pngDown = pngDown;
        }

        FrameSet set(boolean up) {
            FrameSet set = up ? setUp : setDown;
            return set != null ? set : setUp;
        }

        int[] order(boolean up) {
            return up ? orderUp : orderDown;
        }
    }

    private static final Family SLOPE = new Family(
            "斜坡", "slope", MTR_SLOPE_UP, MTR_SLOPE_DOWN);
    private static final Family FLAT = new Family(
            "平层", "flat", MTR_FLAT_UP, MTR_FLAT_DOWN);

    private EscalatorStepTextures() {
    }

    /** 斜坡族（动画的**必需**条件）是否就绪。 */
    public static boolean isReady() {
        return SLOPE.ready;
    }

    /** 平层族是否可用（MTR 4.x 没有这两张图，此时为 false，会自动退回斜坡族）。 */
    public static boolean flatAvailable() {
        return FLAT.ready;
    }

    /** 动画帧总数（{@link EscalatorStepRenderer#frameOf} 取模用它）。 */
    public static int frameCount() {
        return FRAMES;
    }

    /**
     * 某个方向的某张「动画帧号」实际该采**第几帧的贴图**。
     *
     * <p>MTR 用 {@code .mcmeta} 的帧序表达方向：up 是 {@code 0..15}、down 是 {@code 15..0}，
     * 而两张图的像素是同一份（见类注释）。所以「上行 / 下行」的差别**只在帧序**，
     * 这里按各自方向自己的 {@code .mcmeta} 重排即可 —— 像素只有一份。
     *
     * @param frame 动画帧号（{@code 0..FRAMES-1}）
     * @return 该方向该采的帧贴图下标
     */
    public static int bandFor(boolean up, boolean flat, int frame) {
        Family family = family(flat);
        int[] order = family.order(up);
        int index = Math.floorMod(frame, order.length);
        return order[index];
    }

    /** 该族一共有多少帧可用（正常等于 {@link #FRAMES}）。 */
    public static int bandCount(boolean flat) {
        return family(flat).set(true).used;
    }

    /**
     * 取某个阶梯面该用的渲染类型。
     *
     * @param up   上行还是下行（决定用哪一套帧序 —— 贴图本身通常是同一份）
     * @param flat 是否是「铺满整张 sprite」的平层阶梯面（{@link EscalatorStepModels} 的 fullWindow）
     * @param band 实际要显示的帧贴图下标（由 {@link #bandFor} 算出来）
     */
    public static RenderType type(boolean up, boolean flat, int band) {
        return family(flat).set(up).type(band);
    }

    /**
     * 该阶梯面实际使用的族。
     *
     * <p>平层贴图缺失（MTR 4.x）时退回斜坡族：4.x 的平层 uv 本来就是小窗口，
     * 会走非 fullWindow 分支，用斜坡族才是对的。
     */
    private static Family family(boolean flat) {
        return flat && FLAT.ready ? FLAT : SLOPE;
    }

    /** 已注册的帧贴图总数（性能/显存计数用）。 */
    public static int textureCount() {
        int count = 0;
        count += countTextures(SLOPE);
        count += countTextures(FLAT);
        return count;
    }

    private static int countTextures(Family family) {
        if (!family.ready || family.setUp == null) {
            return 0;
        }
        // 两个方向共用同一份时只能算一次，否则会把省下来的那份又数回去。
        return family.shared || family.setDown == null
                ? family.setUp.used
                : family.setUp.used + family.setDown.used;
    }

    /**
     * 确保贴图已经构建。资源重载后会换 ResourceManager 实例，这里会自动重建。
     * 在渲染线程调用即可，构建只做一次。
     */
    public static void ensureLoaded() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        ResourceManager resources = minecraft.getResourceManager();
        if (resources == null) {
            return;
        }
        if (resources == builtFor) {
            return;
        }
        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }

        // 换 ResourceManager = 资源重载：旧的帧贴图可能还在贴图管理器里挂着，先放掉。
        release(minecraft);

        // 斜坡族是动画的必需条件；平层族可选（MTR 4.x 根本没有那两张图）。
        boolean slopeOk = load(minecraft, resources, SLOPE);
        boolean flatOk = load(minecraft, resources, FLAT);

        if (slopeOk) {
            builtFor = resources;
            LOGGER.info("[SmoothLift] 逐条扶梯阶梯动画已启用（每帧一张贴图：斜坡 {} 帧{}，平层 {}）",
                    framesOf(SLOPE),
                    SLOPE.shared ? "，上下行共用像素" : "，上下行各一份",
                    flatOk ? framesOf(FLAT) + " 帧" : "无（与斜坡共用，自动退回）");
        } else {
            retryCooldown = 100;
            LOGGER.error("[SmoothLift] 读取 MTR 扶梯阶梯贴图失败，暂时回退为静态阶梯（每 5 秒重试一次）");
        }
    }

    private static String framesOf(Family family) {
        return family.ready ? String.valueOf(family.set(true).used) : "0";
    }

    /** 退出世界 / 资源重载：把注册过的帧贴图全部撤掉（贴图管理器里不留孤儿）。 */
    public static void releaseAll() {
        release(Minecraft.getInstance());
        builtFor = null;
        retryCooldown = 0;
    }

    private static void release(Minecraft minecraft) {
        if (minecraft != null) {
            TextureManager manager = minecraft.getTextureManager();
            releaseFamily(manager, SLOPE);
            releaseFamily(manager, FLAT);
        }
        SLOPE.ready = false;
        SLOPE.setUp = null;
        SLOPE.setDown = null;
        FLAT.ready = false;
        FLAT.setUp = null;
        FLAT.setDown = null;
    }

    private static void releaseFamily(TextureManager manager, Family family) {
        if (family.setUp != null) {
            freeSet(manager, family, family.setUp);
        }
        // 共用像素时 setDown == setUp，上面已经放过了，不能放第二遍。
        if (family.setDown != null && family.setDown != family.setUp) {
            freeSet(manager, family, family.setDown);
        }
    }

    private static void freeSet(TextureManager manager, Family family, FrameSet set) {
        for (DynamicTexture texture : set.textures) {
            if (texture != null) {
                // 先让贴图管理器摘掉名字，再关掉 GPU 侧对象与像素。
                // GL 删除必须发生在渲染线程：releaseAll 由 Netty 连接线程的 onDisconnect
                // 触发，直接 close() 会报 "Rendersystem called from wrong thread"
                //（与 releaseBuffers 同一类问题）。延迟到渲染线程下一帧执行。
                final DynamicTexture tex = texture;
                try {
                    RenderSystem.recordRenderCall(() -> {
                        try {
                            tex.close();
                        } catch (Exception ignore) {
                        }
                    });
                } catch (Exception e) {
                    // 渲染循环已停止（如游戏关闭中）：交由驱动回收，不阻塞清理。
                    try {
                        tex.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /**
     * 读一族的源图、帧序，并切成每帧一张贴图。
     *
     * @return 是否成功（平层族读不到那两张图时返回 false 是**正常**的，调用方按可选处理）
     */
    private static boolean load(Minecraft minecraft, ResourceManager resources, Family family) {
        family.ready = false;
        family.shared = false;
        // 【1.18.2 移植】1.20.4 的 ResourceManager.getResource 返回 Optional<Resource>；
        // 1.18.2 直接返回 Resource，找不到为 null，且声明抛出受检 IOException
        //（1.20.4 不抛受检异常，原代码无 try-catch；这里捕获后与“找不到”同等处理）。
        Resource up;
        try {
            up = resources.getResource(family.pngUp);
        } catch (IOException e) {
            up = null;
        }
        if (up == null) {
            LOGGER.warn("[SmoothLift] 找不到贴图资源 {}（{}），该族阶梯将退回斜坡贴图",
                    family.pngUp, family.label);
            return false;
        }
        // 下行图可能不存在（例如某些资源包只提供上行）；不存在就退回上行图 + 上行帧序。
        Resource down = null;
        if (!family.pngDown.equals(family.pngUp)) {
            try {
                down = resources.getResource(family.pngDown);
            } catch (IOException e) {
                down = null;
            }
        }

        NativeImage upImage = readImage(up);
        if (upImage == null) {
            return false;
        }
        family.orderUp = readFrameOrder(up, upImage);
        family.orderDown = down != null ? readFrameOrder(down, upImage) : family.orderUp;

        FrameSet upSet = split(minecraft, family, "up", upImage);
        if (upSet == null) {
            upImage.close();
            LOGGER.error("[SmoothLift] {} 尺寸异常，无法切成逐帧贴图：{}",
                    family.pngUp, describe(upImage));
            return false;
        }
        family.setUp = upSet;

        NativeImage downImage = down != null ? readImage(down) : null;
        if (downImage == null) {
            // 没有下行图 ⇒ 直接用上行那份（像素本来就是同一份，见类注释）。
            family.setDown = upSet;
            family.shared = true;
        } else if (pixelsEqual(upImage, downImage)) {
            // ★ 实测逐像素相同：下行直接复用上行那批帧贴图，只用自己的帧序索引。
            family.setDown = upSet;
            family.shared = true;
            downImage.close();
        } else {
            LOGGER.warn("[SmoothLift] {} 与 {} 像素并不相同，下行将单独占一份帧贴图"
                            + "（不再共用，请把这条日志发给作者）",
                    family.pngUp.getPath(), family.pngDown.getPath());
            FrameSet downSet = split(minecraft, family, "down", downImage);
            if (downSet == null) {
                downImage.close();
                // 下行切不出来时退回共用：宁可方向观感有偏差，也不要整片台阶消失。
                family.setDown = upSet;
                family.shared = true;
            } else {
                family.setDown = downSet;
            }
        }
        upImage.close();

        family.ready = true;
        LOGGER.info("[SmoothLift] 阶梯贴图 {}（{}）切成逐帧贴图：{} 帧，{}",
                family.label, family.pngUp, upSet.used,
                family.shared ? "上下行共用像素" : "上下行各一份");
        return true;
    }

    private static NativeImage readImage(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            return NativeImage.read(stream);
        } catch (Throwable t) {
            LOGGER.error("[SmoothLift] 读取阶梯贴图像素失败", t);
            return null;
        }
    }

    private static String describe(NativeImage image) {
        return image.getWidth() + "x" + image.getHeight();
    }

    /**
     * 两张图的像素是不是**完全一样**（全量逐像素比较，不是抽样）。
     *
     * <p>为什么要全量：这条结论决定「下行能不能直接复用上行的帧贴图」，
     * 判错会让一半扶梯的台阶用错帧。抽样比较的漏判概率虽小，但这里根本不是性能敏感路径
     * （每次装载只跑一次），所以直接用最贵的确定性做法。
     */
    private static boolean pixelsEqual(NativeImage a, NativeImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        // 【1.18.2 移植】1.20.4 的 getPixelsRGBA() 数组方法在 1.18.2 不存在，改逐像素比较。
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getPixelRGBA(x, y) != b.getPixelRGBA(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 把一条竖排图切成 {@link #FRAMES} 张「一帧一张」的动态贴图，并给每帧建一个渲染类型。
     *
     * <p>源图帧数不足 {@link #FRAMES} 时，多出来的帧**重复最后一帧**（而不是读越界），
     * 这样坏素材也只是动画变短，不会崩、更不会让台阶整片消失。
     */
    private static FrameSet split(Minecraft minecraft, Family family, String directionKey,
                                  NativeImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height < width || height % width != 0) {
            return null;
        }
        int sourceFrames = height / width;
        int usable = Math.min(FRAMES, sourceFrames);

        TextureManager manager = minecraft.getTextureManager();
        DynamicTexture[] textures = new DynamicTexture[FRAMES];
        RenderType[] types = new RenderType[FRAMES];
        boolean copyOk = true;
        for (int frame = 0; frame < FRAMES; frame++) {
            int sourceFrame = Math.min(frame, usable - 1);
            NativeImage target = new NativeImage(source.format(), width, width, false);
            // 只拷一帧（320 行）而不是整条：这就是「每帧一张贴图」的全部代价，装载时一次性付清。
            // 【1.18.2 移植】1.20.4 的 copyRect 是 9 参、语义已变，统一走逐像素 manualCopy（慢但确定）。
            manualCopy(source, target, 0, sourceFrame * width, width);
            if (frame == 0 && !copyRectVerified(source, target, 0, 0, width)) {
                // copyRect 的「谁拷到谁」如果被弄反了，画面会是花的 —— 这里当场验一次，
                // 对不上就整族改用逐像素循环重切（慢但确定）。
                copyOk = false;
            }
            if (!copyOk) {
                manualCopy(source, target, 0, sourceFrame * width, width);
            }
            textures[frame] = createTexture(manager, family, directionKey, frame, target);
            types[frame] = createRenderType(family, directionKey, frame,
                    manager, textures[frame]);
        }
        if (!copyOk) {
            LOGGER.warn("[SmoothLift] 分段拷贝与源图不一致，已改用逐像素拷贝重切 {} 的 {}",
                    family.label, directionKey);
        }
        return new FrameSet(textures, types, usable);
    }

    /** 抽样验证 {@code source.copyRect(target, sx, sy, 0, 0, w, h, ...)} 真的把像素搬对了。 */
    private static boolean copyRectVerified(NativeImage source, NativeImage target,
                                            int sourceX, int targetX, int size) {
        int[] offsets = {0, size / 2, size - 1};
        for (int offset : offsets) {
            int sx = Math.min(sourceX + offset, source.getWidth() - 1);
            int tx = Math.min(targetX + offset, target.getWidth() - 1);
            for (int y = 0; y < size; y += Math.max(1, size / 8)) {
                if (source.getPixelRGBA(sx, y) != target.getPixelRGBA(tx, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 兜底的逐像素拷贝（{@code copyRect} 行为不符预期时的正确性保险）。 */
    private static void manualCopy(NativeImage source, NativeImage target,
                                   int sourceX, int sourceY, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                target.setPixelRGBA(x, y, source.getPixelRGBA(sourceX + x, sourceY + y));
            }
        }
    }

    private static DynamicTexture createTexture(TextureManager manager, Family family,
                                                String directionKey, int frame,
                                                NativeImage image) {
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(false, false);
        manager.register(new ResourceLocation("smoothlift",
                DYNAMIC_PREFIX + family.key + "_" + directionKey + "_" + frame), texture);
        return texture;
    }

    /** 从 png 同名 .mcmeta 里读出动画帧序；读不到就按顺序 0..n-1。 */
    private static int[] readFrameOrder(Resource resource, NativeImage image) {
        int sourceFrames = Math.max(1, image.getHeight() / Math.max(1, image.getWidth()));
        int[] order = identityOrder();
        try {
            // 【1.18.2 移植】1.20.4 的 metadata().getSection() 返回 Optional；
            // 1.18.2 的 getMetadata() 直接返回 T，读不到为 null。
            AnimationMetadataSection section =
                    resource.getMetadata(AnimationMetadataSection.SERIALIZER);
            if (section == null) {
                return order;
            }
            int[] cursor = {0};
            Map<Integer, Integer> temporary = new HashMap<>();
            section.forEachFrame((index, time) -> {
                if (cursor[0] < order.length) {
                    temporary.put(cursor[0], index);
                    cursor[0]++;
                }
            });
            for (int i = 0; i < order.length; i++) {
                Integer index = temporary.get(i);
                if (index == null || index < 0 || index >= sourceFrames) {
                    return identityOrder();
                }
                order[i] = index;
            }
        } catch (Throwable t) {
            // 【1.18.2 移植】1.20.4 叫 sourcePackId()；1.18.2 mojmap 里这个方法叫 getSourceName()。
            LOGGER.warn("[SmoothLift] 解析 {} 的动画帧序失败，按顺序播放", resource.getSourceName(), t);
        }
        return order;
    }

    private static int[] identityOrder() {
        int[] order = new int[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            order[i] = i;
        }
        return order;
    }

    /**
     * 造一个和原版 {@code cutout} 一样的渲染类型，只是换成「某一帧」的贴图。
     * 顶点格式必须是 BLOCK（位置/颜色/uv/光照/法线），因为阶梯模型是方块模型。
     *
     * <p>这里**不再需要多边形偏移**：MTR 原版那份静止台阶面已经被彻底隐藏了。
     * 原理：资源覆盖把 18 个阶梯模型的 {@code #step} 指向 SmoothLift 的底图，而这张底图是
     * **全透明**的（见 {@code _tools/gen_assets.py}）；MTR 的 ESCALATOR_STEP 注册在 cutout 层
     * （{@code RenderLayer.getCutout()}），cutout 会做 alpha 裁剪，全透明像素被直接丢弃 ——
     * 于是 MTR/Sodium 渲染时既不留颜色也不写深度，玩家只会看到我们重绘的这份。
     */
    private static RenderType createRenderType(Family family, String directionKey, int frame,
                                               TextureManager manager, DynamicTexture texture) {
        RenderStateShard.TextureStateShard textureState = new RenderStateShard.TextureStateShard(
                new ResourceLocation("smoothlift",
                        DYNAMIC_PREFIX + family.key + "_" + directionKey + "_" + frame),
                false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                .setTextureState(textureState)
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true);
        return RenderType.create(
                "smoothlift_escalator_step_" + family.key + "_" + directionKey + "_" + frame,
                DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 1536, true, false, state);
    }
}
