package smooth.lift.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * 逐条扶梯独立的阶梯动画渲染。
 *
 * <h2>它解决什么问题</h2>
 * MTR 的阶梯贴图是全地图共享的单相位 flipbook，所以「每条扶梯各自动各自的速度」在原版
 * 渲染路径下不可能实现。SmoothLift 的做法是：
 * <ol>
 *   <li>用 {@link EscalatorModelOverride} 在模型烘焙期把 18 个 MTR 阶梯模型的
 *       {@code #step} 贴图换成 SmoothLift 自己的**全透明**底图 —— MTR 的 ESCALATOR_STEP 在
 *       cutout 层，全透明像素会被 alpha 裁剪整片丢弃，等于把 MTR 原版那份**静止的台阶面
 *       彻底隐藏**（不会再和我们画的这份重叠）；</li>
 *   <li>自己画一遍会动的台阶面：几何全部来自缓存（{@link EscalatorStepCache}），
 *       每帧只是把「这一组扶梯该显示第几帧」换成绑定哪张帧贴图
 *       （{@link EscalatorStepTextures}）。</li>
 * </ol>
 *
 * <h2>【1.29】全量重写：从「每帧重算几何」改成「几何静态缓存 + 换贴图」</h2>
 * 旧版每帧把视野内每个阶梯方块（一个方块 380 个顶点）重写一遍，因为**帧号焊在顶点里**
 * （{@code v = (帧号 + 帧内v) / 帧数}），帧号一变几何就整份失效。
 *
 * <p>重写的关键一步是把帧号从顶点里**搬出去**：贴图改成「每帧一张」，帧号变成
 * 「这一次绘制绑哪张帧贴图」。于是顶点只剩帧内 uv，与时间无关 ⇒ 可以缓存成
 * {@code VertexBuffer}，每帧对不变的段一个方块都不碰。
 *
 * <p>绘制方式照抄原版 {@code LevelRenderer} 画区块那一趟（1.20.4 反汇编实测）：
 * {@code setupRenderState()} 一次 → {@code apply()} 一次 → 逐段
 * {@code set(ChunkOffset)+upload} → {@code bind()} → {@code draw()} → 循环外 {@code clear()}。
 * 分段局部坐标 ⟶ 世界坐标的平移全部交给 {@code ChunkOffset} uniform，
 * 没有矩阵栈推拉、没有逐顶点计算。
 *
 * <h2>为什么不能用「纹理矩阵偏移」来省掉换贴图（这条是实测证伪的，别再试）</h2>
 * 更省的一条路是：几何存帧内 uv，绘制时用 {@code RenderSystem.setTextureMatrix(...)} /
 * {@code OffsetTexturingStateShard} 把 v 整体挪到那一帧。**在 1.20.4 上不成立**：
 * 客户端 jar 里 181 个核心着色器中，{@code TextureMat} 只被 glint 系用到；
 * {@code rendertype_cutout.vsh} 第 30 行是 {@code texCoord0 = UV0;}，压根不读纹理矩阵。
 * 要让它生效只能自带一份核心着色器，把「和光影/Sodium 的兼容性」押上去 ——
 * 而收益只是省掉一次贴图切换。所以选「每帧一张贴图」这条只用原版公开能力的路。
 *
 * <h2>渲染范围</h2>
 * 因为静止的那份已经被隐藏，**没画到的地方就是空的（露空）**，所以绘制范围必须覆盖玩家的
 * 整个可视距离：{@link #drawDistance} 直接取客户端的有效渲染距离
 * （{@code Options.getEffectiveRenderDistance()}，会取服务端视距的较小值）再留一点余量。
 *
 * <h2>三条剔除判据（1.26–1.28 建立，本版保留）</h2>
 * <ol>
 *   <li><b>太远</b>：分段紧致包围盒到相机的距离超过可视距离；</li>
 *   <li><b>不在视野里</b>：视锥判定（拿不到视锥时退回「视线点积」）；</li>
 *   <li><b>被墙 / 地形挡住</b>（1.28）：借 {@link net.minecraft.client.renderer.LevelRenderer#visibleSections}
 *       —— 原版 {@code renderSectionLayer} 就是遍历它画地形的，所以它的定义正好是
 *       「原版这一帧真的要画的段」。集合拿不到就**当作判据不可用**并退回纯视锥剔除。</li>
 * </ol>
 * 现在这三条判据的收益比旧版更大：被剔掉的段不但省掉顶点计算，还省掉一次绘制调用。
 *
 * <h2>统计</h2>
 * {@code /mtrxr}（无参）打印一行可核验的摘要，把「剔除 / 重建 / 绘制」三段耗时分开 ——
 * 只看总耗时判断不了瓶颈在哪一边。其中「重建」是新版新增的一栏：
 * 它是随变化偶发的，混进别处会造成「进新区域那一帧看起来特别慢」的误判。
 */
public final class EscalatorStepRenderer {

    /** 颜色通道归一化：{@code (argb >> n) & 0xFF} 之后乘它得到 0..1。 */
    private static final float COLOR_CHANNEL_INV = 1.0F / 255.0F;

    /**
     * 绘制距离下限（格）。即使玩家把渲染距离调到最小，也至少画这么远，
     * 免得近处就出现空洞。
     */
    private static final double MIN_DRAW_DISTANCE = 64.0;
    /**
     * 在渲染距离之外多画一段（格）。渲染距离边缘本来就有雾，多画一点可以避免
     * 「区块边界 / 雾区边界」上恰好露空的观感问题。
     */
    private static final double DRAW_DISTANCE_MARGIN = 32.0;
    /** 相机背后多远以内仍然渲染（格）。只在拿不到视锥时用作兜底剔除。 */
    private static final double BEHIND_THRESHOLD = -8.0;
    /** 速度上限对应的帧倍率保护，避免极端数值算出无意义的大数。 */
    private static final double MAX_FRAME_FACTOR = 400.0;
    /** 性能计数的滚动窗口（帧）。 */
    private static final int STATS_WINDOW = 100;
    /** 原版 cutout 着色器里分段平移用的 uniform 名（{@code rendertype_cutout.vsh} 里用到）。 */
    private static final String CHUNK_OFFSET_UNIFORM = "ChunkOffset";
    /**
     * 采样器槽数。原版 {@code RenderSystem.shaderTextures} 的长度是 12，
     * 而 {@code VertexBuffer._drawWithShader} 与 {@code LevelRenderer} 画区块那一趟
     * 都是 {@code for (i = 0; i < 12; i++) setSampler("Sampler" + i, getShaderTexture(i))}
     * （字节码里就是 {@code bipush 12}）。两边取同一个数，别写各自的字面量。
     */
    private static final int SAMPLER_SLOT_COUNT = 12;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static long tickCounter;
    private static boolean loggedFailure;
    private static boolean loggedFirstSuccess;
    private static boolean loggedMissingChunkOffset;

    /** 上次看到的客户端世界实例（换世界/换维度时作废全部缓存）。 */
    private static ClientLevel lastLevel;

    // ---- 【1.26】可核验的性能计数（滚动均值） ----
    private static double statsNanosSum;
    private static int statsFrames;
    private static double statsMsAverage;
    private static int statsSections;
    private static int statsVisibleSections;
    private static int statsFrustumCulledSections;
    private static int statsDistanceCulledSections;
    /** 【1.28】上帧被「原版这帧根本不画它」剔掉的段数。 */
    private static int statsOccludedSections;
    /** 上帧原版可见段集合的规模（= {@code LevelRenderer.visibleSections.size()}）。 */
    private static int statsVanillaVisibleSections;
    /** 上帧是否真的用上了遮挡判据（false = 集合拿不到 / 为空，已回退到纯视锥剔除）。 */
    private static boolean statsOcclusionActive;
    // ---- 【1.29】把「剔除 / 自愈刷新 / 重建 / 绘制」四段耗时分开记，才能看出瓶颈在哪一边 ----
    private static double statsCullNanosSum;
    private static double statsRefreshNanosSum;
    private static double statsBuildNanosSum;
    private static double statsDrawNanosSum;
    private static double statsCullMsAverage;
    private static double statsRefreshMsAverage;
    private static double statsBuildMsAverage;
    private static double statsDrawMsAverage;
    /** 上帧真正交给 GPU 的顶点数（来自缓存，不再逐帧重写）。 */
    private static int statsDrawnVertices;
    /** 上帧的绘制调用次数（每段每槽一次）。 */
    private static int statsDrawCalls;
    /** 上帧缓存重建的段数与两个来源。 */
    private static int statsBuiltSections;
    private static int statsBuiltByRevision;
    private static int statsBuiltByRefresh;
    private static int statsVisibleCacheSections;

    /**
     * 本帧「原版要画的段」的段键集合。
     *
     * <p><b>复用同一个实例</b>（{@code clear()} 而不是 new）：这个集合每帧都要重填，
     * 视野里几千个段就是几千次插入，如果每帧新建一个，等于每帧丢掉几千个桶 —— 那正是
     * 本类一直在避免的「热路径里制造垃圾」。
     */
    private static final LongOpenHashSet vanillaVisibleKeys = new LongOpenHashSet(4096);
    /** 本帧哪些缓存槽真有内容（绘制时只对它们做一次状态设置）。 */
    private static final boolean[] slotPresent = new boolean[EscalatorStepGroups.SLOT_COUNT];

    private EscalatorStepRenderer() {
    }

    public static void register() {
        EscalatorStepIndex.register();
        WorldRenderEvents.AFTER_ENTITIES.register(EscalatorStepRenderer::onAfterEntities);
    }

    /** 每客户端刻：推进动画时钟、维护索引、处理资源重载与换世界带来的缓存作废。 */
    public static void onClientTick(Minecraft minecraft) {
        tickCounter++;
        handleContextChange(minecraft);
        if (EscalatorStepModels.tickReloadCheck()) {
            // 模型被重烘（资源重载 / /mtrxr 切换）⇒ 顶点里烘焙的 uv 可能变了。
            // 静态几何是跨帧复用的，它自己看不见这件事（段的 revision 没变、epoch 也没变），
            // 所以必须在这里让它整片失效 —— 否则会继续拿旧 uv 采新贴图（错位 / 串帧）。
            // 走 epoch 而不是直接清缓存，是为了让重建**惰性**发生：下一帧只有真正可见的段
            // 会被重建，而且顺带把「哪条扶梯归哪一组」也重算一遍。
            EscalatorStepGroups.invalidate();
        }
        EscalatorStepIndex.tick(minecraft == null ? null : minecraft.level);
    }

    /**
     * 处理「一个世界的缓存已经不能再用」的三种情况：换世界/换维度、资源重载。
     *
     * <p>为什么必须在客户端刻做、而不是渲染时顺手判：<b>作废要释放 GL 缓冲</b>，
     * 而释放必须在渲染线程。客户端刻与渲染线程在 Minecraft 里是同一个线程，
     * 但放在刻里能保证「作废」只发生一次（渲染路径每帧都会跑，判据写在那里等于每帧都判一次）。
     */
    private static void handleContextChange(Minecraft minecraft) {
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level != lastLevel) {
            lastLevel = level;
            // 换世界/换维度：缓存里的坐标、光照、组表全部失效，连 GL 缓冲一起放掉。
            EscalatorStepCache.invalidateAll();
            EscalatorStepGroups.reset();
        }
    }

    public static void onDisconnect() {
        tickCounter = 0L;
        loggedFirstSuccess = false;
        lastLevel = null;
        EscalatorStepIndex.reset();
        EscalatorStepModels.clear();
        EscalatorStepCache.invalidateAll();
        EscalatorStepGroups.reset();
        EscalatorStepTextures.releaseAll();
        resetStats();
    }

    /**
     * 这一帧最远要画到多少格。
     *
     * <p>用「有效渲染距离」（客户端设置与服务端视距取小）而不是固定的 128：静态台阶面已被隐藏，
     * 画不到就等于没有，所以必须和玩家真正能看到地形的范围对齐。格外再补一个区块加一点余量。
     */
    private static double drawDistance(Minecraft minecraft) {
        int chunks = minecraft.options.getEffectiveRenderDistance();
        if (chunks <= 0) {
            chunks = 8;
        }
        return Math.max(MIN_DRAW_DISTANCE, (chunks + 1) * 16.0 + DRAW_DISTANCE_MARGIN);
    }

    private static void onAfterEntities(WorldRenderContext context) {
        try {
            render(context);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.error("[SmoothLift] 逐条扶梯阶梯动画渲染出错，已回退为静态阶梯；"
                        + "请把这段堆栈发给作者。", t);
            }
        }
    }

    /**
     * 【1.28】把「原版这帧要画的段」收成一个段键集合，供本帧的遮挡判定查询。
     *
     * <p>判据来源是 {@link net.minecraft.client.renderer.LevelRenderer#visibleSections} —— 原版
     * {@code renderSectionLayer} 就是遍历它来画地形的，所以它的定义正好就是
     * 「原版这一帧真的要画的段」。
     *
     * <p><b>为什么要走一次 {@code blockToSectionCoord}</b>：{@code RenderSection.getOrigin()}
     * 返回的是**方块坐标**（{@code RenderSection.isAxisAlignedWith} 的字节码里就是拿
     * {@code SectionPos.blockToSectionCoord(origin.getX())} 去和参数比的）。
     * 直接把 origin 当段坐标用会整体偏 16 倍，于是**每一个**段都查不到 ——
     * 那不是「剔掉几个」，是整片扶梯消失。
     *
     * <h3>为什么这条判据是「保守」的（不会把本该画的剔掉）</h3>
     * 原版那侧的语义（1.20.4 字节码实测）：
     * <ul>
     *   <li>{@code applyFrustum(Frustum)} 先 {@code visibleSections.clear()} 再重填；</li>
     *   <li>但它只在「视锥需要更新」时才被调到，其余帧 {@code runPartialUpdate} <b>只加不删</b>
     *       ⇒ 两次重填之间这个列表是「本帧可见集」的<b>超集</b>；</li>
     *   <li>而且它用的是 {@code offsetFrustum(...)}（把相机周围 8 格立方纳入），比我们这里的
     *       {@code context.frustum()} 更宽松。</li>
     * </ul>
     * 两条都指向同一结论：查得到 ⟹ 一定该画；只会<b>少剔</b>、不会<b>多剔</b>。
     *
     * @return 集合是否可用。<b>为空时返回 false</b>（遮挡图还没准备好、刚进世界、模式被关掉），
     *         调用方必须退回纯视锥剔除 —— 把「查不到」当成「被挡住」会让整个世界一条扶梯都不画。
     */
    private static boolean refreshVanillaVisibleKeys(Minecraft minecraft) {
        vanillaVisibleKeys.clear();
        if (minecraft == null || minecraft.levelRenderer == null) {
            return false;
        }
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible =
                minecraft.levelRenderer.visibleSections;
        if (visible == null || visible.isEmpty()) {
            return false;
        }
        int size = visible.size();
        for (int i = 0; i < size; i++) {
            SectionRenderDispatcher.RenderSection section = visible.get(i);
            if (section == null) {
                continue;
            }
            BlockPos origin = section.getOrigin();
            if (origin == null) {
                continue;
            }
            vanillaVisibleKeys.add(SectionPos.asLong(
                    SectionPos.blockToSectionCoord(origin.getX()),
                    SectionPos.blockToSectionCoord(origin.getY()),
                    SectionPos.blockToSectionCoord(origin.getZ())));
        }
        return !vanillaVisibleKeys.isEmpty();
    }

    private static void render(WorldRenderContext context) {
        // /mtrxr on（MTR 原版渲染）时完全跳过自绘；索引与动画时钟仍由 onClientTick 维护。
        if (!EscalatorRenderMode.isOptimized()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = context.world();
        if (level == null || minecraft.player == null || minecraft.level != level) {
            return;
        }

        if (!EscalatorStepTextures.isReady()) {
            EscalatorStepTextures.ensureLoaded();
            if (!EscalatorStepTextures.isReady()) {
                return;
            }
        }

        Collection<EscalatorStepIndex.Section> sections = EscalatorStepIndex.sections();
        if (sections.isEmpty()) {
            return;
        }

        long frameStamp = tickCounter;
        EscalatorStepCache.beginFrame(frameStamp);

        long frameStartNanos = System.nanoTime();

        Camera camera = context.camera();
        Vec3 cameraPos = camera.getPosition();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;
        float pitch = (float) Math.toRadians(camera.getXRot());
        float yaw = (float) Math.toRadians(camera.getYRot());
        double lookX = -Math.sin(yaw) * Math.cos(pitch);
        double lookY = -Math.sin(pitch);
        double lookZ = Math.cos(yaw) * Math.cos(pitch);

        // 视锥是 WorldRenderer 每帧准备好的（和剔除区块、实体用的是同一个），坐标系是绝对世界坐标，
        // 所以能直接拿分段盒子去测。拿不到时退回「点积 + 距离」。
        Frustum frustum = context.frustum();
        double maxDistance = drawDistance(minecraft);
        double maxDistanceSq = maxDistance * maxDistance;
        float partialTick = context.tickDelta();
        long epoch = EscalatorStepGroups.epoch();

        // 【1.28】先把「原版这帧要画的段」收成集合。拿不到（遮挡图还没准备好 / 刚进世界）
        // 就退回纯视锥剔除 —— 见 refreshVanillaVisibleKeys 的返回值说明。
        boolean occlusionActive = EscalatorRenderMode.isOcclusionCulling()
                && refreshVanillaVisibleKeys(minecraft);

        // 1) 一趟分段剔除。命中就登记进缓存（缓存内部自己判「要不要重建」）。
        //    注意这里已经**没有任何逐方块、逐顶点的工作**了：被剔掉的段一个方块都不碰，
        //    没被剔掉但没变化的段也一个方块都不碰。
        int visibleSections = 0;
        int consideredSections = 0;
        int distanceCulledSections = 0;
        int occludedSections = 0;

        for (EscalatorStepIndex.Section section : sections) {
            consideredSections++;
            AABB box = section.box();
            if (distanceToBoxSqr(box, cameraX, cameraY, cameraZ) > maxDistanceSq) {
                distanceCulledSections++;
                continue;
            }
            // 视锥判定放在集合查询前面：它更便宜，而且用的是**紧致盒**（比原版那个 16³ 段盒
            // 贴合得多），先把明显不在视野里的甩掉，再做那次集合查询。
            if (frustum != null) {
                if (!frustum.isVisible(box)) {
                    continue;
                }
            } else {
                double dx = section.centerX() - cameraX;
                double dy = section.centerY() - cameraY;
                double dz = section.centerZ() - cameraZ;
                if (dx * lookX + dy * lookY + dz * lookZ < BEHIND_THRESHOLD) {
                    continue;
                }
            }
            // 【1.28】在视锥里也可能看不见：被墙 / 地形挡住的那部分。原版这一帧不画这个段，
            // 我们也不画 —— 判据和它用来画地形的那个列表是同一个。
            if (occlusionActive && !vanillaVisibleKeys.contains(section.key())) {
                occludedSections++;
                continue;
            }
            visibleSections++;
            EscalatorStepCache.touch(section, level, epoch);
        }
        long cullNanos = System.nanoTime() - frameStartNanos;

        // 2) 【1.29】轮转自愈刷新：顶点里烘焙了每方块一次的光照，而光照变化不会改方块状态、
        //    版本戳看不见它。这一遍用很小的固定预算（全量重建的 1/60）把这种漏判兜住。
        //    它必须单独计时：这段既不属「剔除」也不属「绘制」，混进哪边都会误判瓶颈。
        long refreshStartNanos = System.nanoTime();
        EscalatorStepCache.refreshVisible(level, epoch);
        long refreshNanos = System.nanoTime() - refreshStartNanos;

        // 3) 绘制：每（速度组 × 贴图族）设一次状态，然后逐段设 ChunkOffset、bind、画。
        long drawStartNanos = System.nanoTime();
        int drawCalls = draw(context, partialTick, cameraPos);
        long drawNanos = System.nanoTime() - drawStartNanos;

        recordStats(frameStartNanos, cullNanos, refreshNanos, drawNanos,
                consideredSections, visibleSections, distanceCulledSections,
                occludedSections, occlusionActive, drawCalls);
    }

    /**
     * 真正提交绘制。
     *
     * <p>结构是「<b>外层按槽（速度组 × 贴图族）、内层按段</b>」，因为同一槽内所有段的扶梯
     * 帧号必然相同：只要切换一次贴图就能把它们全部画掉。这正是「同速度的算一组、
     * 一组一次搞定」在代码上的样子，也是用户说的「复制 N 份渲染引擎」里的 N。
     *
     * <h3>★ 槽内形状：{@code setupRenderState()} → {@code apply()} → 逐段 {@code bind()+draw()}</h3>
     * 这是原版画区块那一趟的形状（1.20.4 {@code LevelRenderer} 反汇编实测，字节码序列
     * {@code shader.apply()} → 循环 { {@code chunkOffset.set(origin−cam); chunkOffset.upload();
     * buffer.bind(); buffer.draw();} } → 循环后 {@code chunkOffset.set(0,0,0); shader.clear();
     * VertexBuffer.unbind(); type.clearRenderState();}）。
     *
     * <p>注意是 {@code VertexBuffer.draw()}（public，只做一次 {@code RenderSystem.drawElements}），
     * <b>不是</b> {@code drawWithShader()}。原因：
     * <ul>
     *   <li>{@code drawWithShader()} 收尾会调 {@code ShaderInstance.clear()}，而 {@code clear()}
     *       的字节码是 {@code glUseProgram(0)} + 把每个 sampler 的纹理都解绑 ⇒ <b>它自带拆台</b>。
     *       所以「第一段 {@code drawWithShader} + 其余段 {@code draw()}」这种省事写法会让第一段
     *       之后的所有段都画在「没有着色器、没有贴图」的状态上。</li>
     *   <li>更要紧的是 <b>{@code ChunkOffset} 的上传时机</b>：{@code Uniform.upload()} 内部就是一次
     *       {@code glUniform3f(location, …)}，而 <b>GL 要求当前必须绑着一个着色器程序</b>。
     *       走「每段 {@code drawWithShader}」时，上一段收尾已经把程序解绑了，于是从第二段起
     *       {@code chunkOffset.upload()} 会报 {@code GL_INVALID_OPERATION: No active program}
     *       （实测 2026-09-26 的崩溃日志里就有这一条）。把这个上传放到 {@code apply()} 之后、
     *       {@code clear()} 之前，才是合法的时机。</li>
     *   <li>收益也不小：每槽只 {@code apply()+clear()} 一次，段与段之间只剩
     *       「一次 uniform + 一次 VAO 绑定 + 一次 glDrawElements」。
     *       若改成每段一次 {@code drawWithShader}，可见段 × 槽数 就是每帧成百上千次
     *       {@code glUseProgram / glUseProgram(0) / 采样器逐单元解绑} —— 在一个
     *       「目标就是省每帧开销」的重写里，这笔开销不能留。</li>
     * </ul>
     *
     * <p>{@link #applyLayerUniforms} 逐行对应 {@code VertexBuffer._drawWithShader} 里
     * 「设 sampler + 设 uniform + {@code setupShaderLights}」那一段 ——
     * <b>连 sampler 循环一起</b>，因为 {@code apply()} 只会绑 {@code samplerMap} 里有的东西
     * （见该方法的说明）。
     * 拿不到 {@code ChunkOffset} 的着色器（被别人换了资源包）走 {@code drawWithShader} 兜底路径，
     * 那条路上「自己 apply / 自己 clear」是自洽的。
     *
     * @return 实际发出的绘制调用次数
     */
    private static int draw(WorldRenderContext context, float partialTick, Vec3 cameraPos) {
        EscalatorStepCache.CachedSection[] visible = EscalatorStepCache.visibleArray();
        int count = EscalatorStepCache.visibleCount();
        if (count == 0) {
            EscalatorStepCache.recordDrawnVertices(0);
            return 0;
        }

        java.util.Arrays.fill(slotPresent, false);
        for (int i = 0; i < count; i++) {
            EscalatorStepCache.CachedSection cached = visible[i];
            int slots = cached.slotCount();
            for (int s = 0; s < slots; s++) {
                slotPresent[cached.slotAt(s)] = true;
            }
        }

        PoseStack poseStack = context.matrixStack();
        Matrix4f projection = context.projectionMatrix();
        int drawnVertices = 0;
        int drawCalls = 0;

        for (int slot = 0; slot < EscalatorStepGroups.SLOT_COUNT; slot++) {
            if (!slotPresent[slot]) {
                continue;
            }
            boolean up = EscalatorStepCache.upOfSlot(slot);
            boolean flat = EscalatorStepCache.flatOfSlot(slot);
            int group = EscalatorStepCache.groupOfSlot(slot);
            // 这一组现在该显示第几帧：整组共用一个答案（这就是分组的全部意义）。
            int band = EscalatorStepTextures.bandFor(up, flat,
                    frameOf(EscalatorStepGroups.speedOf(group), partialTick));
            RenderType type = EscalatorStepTextures.type(up, flat, band);
            if (type == null) {
                continue;
            }

            type.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) {
                // setupRenderState 之后拿不到着色器属于异常状态（别的模组换掉了渲染管线）。
                // 这时什么都画不了 —— 而且 ChunkOffset 的上传也需要一个已绑定的程序，
                // 所以直接跳过这一槽，别留下半套 GL 状态。
                type.clearRenderState();
                continue;
            }

            // ★ 每槽只 apply 一次：这一句同时做了两件事 —— 绑上程序、按 samplerMap 绑好贴图。
            //   后面每一段的 ChunkOffset 上传都在「程序已绑定」的前提下进行（见方法注释）。
            applyLayerUniforms(shader, poseStack.last().pose(), projection);
            shader.apply();

            Uniform chunkOffset = shader.getUniform(CHUNK_OFFSET_UNIFORM);
            if (chunkOffset == null && !loggedMissingChunkOffset) {
                loggedMissingChunkOffset = true;
                LOGGER.warn("[SmoothLift] 着色器里没有 {} uniform，本槽退回逐段推矩阵栈 + drawWithShader"
                        + "（画面正确，只是每段多一次矩阵上传）", CHUNK_OFFSET_UNIFORM);
            }

            for (int i = 0; i < count; i++) {
                EscalatorStepCache.CachedSection cached = visible[i];
                VertexBuffer buffer = cached.buffer(slot);
                if (buffer == null || buffer.isInvalid()) {
                    continue;
                }
                float dx = (float) (cached.originX() - cameraPos.x);
                float dy = (float) (cached.originY() - cameraPos.y);
                float dz = (float) (cached.originZ() - cameraPos.z);
                if (chunkOffset != null) {
                    // 顶点里存的是分段局部坐标，平移量交给 uniform —— 原版画区块就是这么做的。
                    chunkOffset.set(dx, dy, dz);
                    chunkOffset.upload();
                    buffer.bind();
                    buffer.draw();
                } else {
                    // 兜底：着色器里没有 ChunkOffset（资源包把它换掉了）时改用矩阵栈平移。
                    // 这条路上必须走 drawWithShader —— 它自己 apply / 自己 clear，
                    // 不依赖「循环开始时绑好的那个程序」。
                    poseStack.pushPose();
                    poseStack.translate(dx, dy, dz);
                    buffer.bind();
                    buffer.drawWithShader(poseStack.last().pose(), projection, shader);
                    poseStack.popPose();
                }
                drawnVertices += cached.vertexCount(slot);
                drawCalls++;
            }
            // 把 ChunkOffset 复位：原版画区块收尾也是这么做的（字节码里循环后紧跟一句
            // set(0,0,0)），免得把这一槽的平移量留给下一个用同一着色器的绘制。
            if (chunkOffset != null) {
                chunkOffset.set(0.0F, 0.0F, 0.0F);
            }
            // clear() 一次就够：解绑程序 + 把各采样器纹理解绑（原版也是循环外一次）。
            shader.clear();
            VertexBuffer.unbind();
            type.clearRenderState();
        }

        EscalatorStepCache.recordDrawnVertices(drawnVertices);
        return drawCalls;
    }

    /**
     * 把一个槽要用到的 uniform 设好（对应 {@code VertexBuffer._drawWithShader} 里的那一段）。
     *
     * <p><b>这份清单是从 {@code VertexBuffer._drawWithShader} 的字节码逐行抄来的</b>，顺序也一致
     * （ModelViewMat → ProjMat → InverseViewRotationMat → ColorModulator → GlintAlpha → FogStart
     * → FogEnd → FogColor → FogShape → TextureMat → GameTime → ScreenSize → LineWidth
     * → {@code RenderSystem.setupShaderLights()}），每一项都判空 —— 没有在 shader json 里声明的
     * uniform 就是 {@code null}。{@code rendertype_cutout.json}（1.20.4 实测）只声明了 8 项：
     * {@code ModelViewMat / ProjMat / ChunkOffset / ColorModulator / FogStart / FogEnd / FogColor /
     * FogShape}，所以后面那些判断在这条路上全部落到「跳过」。
     *
     * <p>唯一「看起来可以省、实际不能省」的是开头那个 sampler 循环：
     * {@code RenderSystem._setShaderTexture(i, texId)} 只是把纹理 id 存进
     * {@code RenderSystem.shaderTextures[i]}，它<b>不碰 {@code samplerMap}</b>；
     * 而 {@code ShaderInstance.apply()} 的采样器绑定是「按 {@code samplerMap} 逐项
     * {@code activeTexture + bindTexture}」，没有条目的采样器就<b>什么都不绑</b>。
     * 也就是说少写这个循环，方块图集与光照图压根不会被绑上去（画面会采到上一个绘制留下的纹理）。
     * 原版画区块那一趟自己也带着这个循环 —— {@code LevelRenderer} 字节码里就是
     * 「{@code for (i<12) setSampler("Sampler"+i, getShaderTexture(i));} → 设各 uniform
     * → setupShaderLights → apply() → 逐段 ChunkOffset + bind + draw」。
     *
     * <p>调用方必须紧接着调 {@code shader.apply()}（它负责真正上传并绑定程序），
     * 此后直到 {@code shader.clear()} 之前不能再让别的代码插手 GL 状态
     * —— 逐段的 {@code ChunkOffset.upload()} 依赖「程序已绑定」。
     *
     * @param modelView  已经含相机旋转/平移的模型视图矩阵（原版传的就是 PoseStack 栈顶）
     * @param projection 投影矩阵
     */
    private static void applyLayerUniforms(ShaderInstance shader, Matrix4f modelView,
                                           Matrix4f projection) {
        // ★ 这一循环不能省：见方法注释 —— 它是把「RenderSystem 里记着的纹理 id」
        //   搬进「这个着色器的 samplerMap」，apply() 才会真的逐个 bindTexture。
        for (int i = 0; i < SAMPLER_SLOT_COUNT; i++) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.GLINT_ALPHA != null) {
            shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }
        if (shader.SCREEN_SIZE != null) {
            Window window = Minecraft.getInstance().getWindow();
            shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        }
        // 线宽只对 LINES / LINE_STRIP 有意义。我们这一路几何永远是 QUADS，所以省掉原版
        // 那句 mode 判断：着色器真声明了才设值，而它只会影响画线的宽度。
        if (shader.LINE_WIDTH != null) {
            shader.LINE_WIDTH.set(RenderSystem.getShaderLineWidth());
        }
        RenderSystem.setupShaderLights(shader);
    }

    /** 相机到分段盒子的距离平方（盒子外为正值，盒子内为 0）。不分配对象。 */
    private static double distanceToBoxSqr(AABB box, double x, double y, double z) {
        double dx = Math.max(Math.max(box.minX - x, x - box.maxX), 0.0);
        double dy = Math.max(Math.max(box.minY - y, y - box.maxY), 0.0);
        double dz = Math.max(Math.max(box.minZ - z, z - box.maxZ), 0.0);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 这个速度当前应该播到第几帧。
     *
     * <p>帧号 = floor((刻计数 + 部分刻) × 倍率) mod 总帧数，倍率 = 速度 / 原版标定速度。
     * 用刻计数直接算而不是累加，是为了让「同速度的扶梯永远同步、不同速度的扶梯相位不同」，
     * 而且是无状态的、任何时刻都不会漂移。
     *
     * <p>这条「按速度算、不按组累加」的性质是分组安全的**前提**：把同一速度的扶梯归到一组
     * 之后，它们算出来的帧号本来就完全一样，所以画面与旧版逐方块计算时**逐帧一致**。
     */
    private static int frameOf(double speed, float partialTick) {
        double factor = speed / smooth.lift.EscalatorSpeedData.VANILLA_STEP;
        if (Double.isNaN(factor) || factor <= 0.0) {
            return 0;
        }
        if (factor > MAX_FRAME_FACTOR) {
            factor = MAX_FRAME_FACTOR;
        }
        long total = (long) Math.floor((tickCounter + partialTick) * factor);
        return (int) Math.floorMod(total, EscalatorStepTextures.frameCount());
    }

    // ------------------------------------------------------------------
    // 【1.26】性能计数：把「到底优化了多少」变成可核验的数字
    // 【1.29】拆成「剔除 / 自愈刷新 / 重建 / 绘制」四段 ——
    //         只看总耗时无法判断瓶颈在哪一边；四段里「重建」是另外三段里的子集（见下）。
    // ------------------------------------------------------------------

    /**
     * @param cullNanos    剔除趟的墙钟耗时（**含**这一趟里顺带发生的重建）
     * @param refreshNanos 自愈刷新趟的墙钟耗时（同样含它的重建）
     * @param drawNanos    绘制趟的墙钟耗时
     */
    private static void recordStats(long frameStartNanos, long cullNanos, long refreshNanos,
                                    long drawNanos,
                                    int consideredSections, int visibleSections,
                                    int distanceCulled, int occluded, boolean occlusionActive,
                                    int drawCalls) {
        // 「重建」是前面两趟里**顺带**做的事，所以它既是独立一栏、又是 cull/refresh 的子集：
        // 三段（剔除 + 刷新 + 绘制）相加 ≈ 总耗时，而「重建」单独看是为了回答
        // 「这一帧的额外开销是不是来自建几何」。
        long buildNanos = EscalatorStepCache.statsBuildNanos();

        statsNanosSum += System.nanoTime() - frameStartNanos;
        statsCullNanosSum += cullNanos;
        statsRefreshNanosSum += refreshNanos;
        statsDrawNanosSum += drawNanos;
        statsBuildNanosSum += buildNanos;
        statsFrames++;
        if (statsFrames >= STATS_WINDOW) {
            double frames = statsFrames;
            statsMsAverage = statsNanosSum / frames / 1.0E6;
            statsCullMsAverage = statsCullNanosSum / frames / 1.0E6;
            statsRefreshMsAverage = statsRefreshNanosSum / frames / 1.0E6;
            statsDrawMsAverage = statsDrawNanosSum / frames / 1.0E6;
            statsBuildMsAverage = statsBuildNanosSum / frames / 1.0E6;
            statsNanosSum = 0.0;
            statsCullNanosSum = 0.0;
            statsRefreshNanosSum = 0.0;
            statsDrawNanosSum = 0.0;
            statsBuildNanosSum = 0.0;
            statsFrames = 0;
        }
        statsSections = consideredSections;
        statsVisibleSections = visibleSections;
        // 「视野外」是**推**出来的（总数 − 太远 − 被挡住），所以这两个子计数加起来
        // 绝不能超过总数 —— 一旦超过，说明某个 continue 分支漏了一个计数或者重复计了。
        statsFrustumCulledSections = consideredSections - visibleSections;
        statsDistanceCulledSections = distanceCulled;
        statsOccludedSections = occluded;
        statsOcclusionActive = occlusionActive;
        statsVanillaVisibleSections = vanillaVisibleKeys.size();
        statsDrawnVertices = EscalatorStepCache.statsDrawnVertices();
        statsDrawCalls = drawCalls;
        statsVisibleCacheSections = EscalatorStepCache.visibleCount();
        statsBuiltByRevision = EscalatorStepCache.statsBuiltByRevision();
        statsBuiltByRefresh = EscalatorStepCache.statsBuiltByRefresh();
        statsBuiltSections = statsBuiltByRevision + statsBuiltByRefresh;
    }

    private static void resetStats() {
        statsNanosSum = 0.0;
        statsCullNanosSum = 0.0;
        statsRefreshNanosSum = 0.0;
        statsBuildNanosSum = 0.0;
        statsDrawNanosSum = 0.0;
        statsFrames = 0;
        statsMsAverage = 0.0;
        statsCullMsAverage = 0.0;
        statsRefreshMsAverage = 0.0;
        statsBuildMsAverage = 0.0;
        statsDrawMsAverage = 0.0;
        statsSections = 0;
        statsVisibleSections = 0;
        statsFrustumCulledSections = 0;
        statsDistanceCulledSections = 0;
        statsOccludedSections = 0;
        statsOcclusionActive = false;
        statsVanillaVisibleSections = 0;
        statsDrawnVertices = 0;
        statsDrawCalls = 0;
        statsBuiltSections = 0;
        statsBuiltByRevision = 0;
        statsBuiltByRefresh = 0;
        statsVisibleCacheSections = 0;
        vanillaVisibleKeys.clear();
    }

    /**
     * 一行可核验的性能摘要（{@code /mtrxr} 无参时打印）。
     *
     * <p>几个数一起看才有意义：
     * <ul>
     *   <li><b>索引</b>：已加载区块里登记了多少阶梯方块 / 多少个非空分段；</li>
     *   <li><b>剔除</b>：上一帧的分段怎么被剔的（剔掉的段里的方块一个都没碰），
     *       并区分「太远」「不在视野里」「被挡住」三种；</li>
     *   <li><b>缓存</b>：缓存了多少段 / 多少个顶点缓冲（GL 对象）/ 多少顶点；
     *       上帧重建了几个段，其中多少是「版本戳变了」、多少是「轮转自愈刷新」。
     *       ★ 稳态下「重建」应当接近 0（只留下那 1/60 的自愈量）——
     *       这正是本次重写要的效果，看这个数就能确认它有没有生效；</li>
     *   <li><b>绘制</b>：上帧交给 GPU 的顶点数与绘制调用次数（与旧的「逐帧重写顶点数」对比
     *       就能算出省了多少）；</li>
     *   <li><b>耗时</b>：最近 {@value #STATS_WINDOW} 帧均值（毫秒），拆成
     *       <b>剔除 / 自愈刷新 / 绘制</b>三段，再把「其中重建」单独列一行 ——
     *       重建是前两段里顺带发生的（属子集），单列是为了回答「这一帧的额外开销
     *       是不是来自建几何」；</li>
     *   <li><b>遮挡剔除状态</b>：这条判据到底有没有在干活。★ 必须打出来 ——
     *       它是三条判据里唯一<b>会因外部状态（原版遮挡图有没有准备好）而静默失效</b>的，
     *       不打出来就没法区分「真的没被挡住」和「判据根本没生效」。</li>
     * </ul>
     */
    public static String statsLine() {
        // 「视野外」是推出来的（总数 − 太远 − 被挡住），夹到 0 只是兜底：
        // 正常情况下三个子计数加起来必然 <= 总数，见 recordStats 的注释。
        int frustumOut = Math.max(0,
                statsFrustumCulledSections - statsDistanceCulledSections - statsOccludedSections);
        return String.format(
                "[SmoothLift] 阶梯渲染实测：索引 %d 个阶梯 / %d 个分段；"
                        + "上帧分段 %d（剔除 %d：太远 %d / 视野外 %d / 被挡住 %d）；"
                        + "静态几何缓存 %d 段 / %d 个缓冲 / %d 个顶点，上帧重建 %d（版本 %d + 自愈 %d）；"
                        + "绘制 %d 个顶点 / %d 次调用；"
                        + "帧内耗时 %.2f ms = 剔除 %.2f + 自愈刷新 %.2f + 绘制 %.2f"
                        + "（其中重建 %.2f；最近 %d 帧均值）；"
                        + "遮挡剔除 %s（本帧原版可见段 %d 个）",
                EscalatorStepIndex.stepCount(), EscalatorStepIndex.sectionCount(),
                statsSections, statsFrustumCulledSections,
                statsDistanceCulledSections, frustumOut, statsOccludedSections,
                EscalatorStepCache.cachedSectionCount(), EscalatorStepCache.bufferCount(),
                EscalatorStepCache.cachedVertexCount(),
                statsBuiltSections, statsBuiltByRevision, statsBuiltByRefresh,
                statsDrawnVertices, statsDrawCalls,
                statsMsAverage, statsCullMsAverage, statsRefreshMsAverage, statsDrawMsAverage,
                statsBuildMsAverage, STATS_WINDOW,
                occlusionStateText(), statsVanillaVisibleSections);
    }

    /** 遮挡剔除的当前状态（给 {@link #statsLine()} 用）。 */
    private static String occlusionStateText() {
        if (!EscalatorRenderMode.isOcclusionCulling()) {
            return "关（/mtrxr occ on 打开）";
        }
        if (statsOcclusionActive) {
            return "开（生效中）";
        }
        // 模式是开的但这一帧没取到集合：多半是刚进世界 / 遮挡图还在全量更新。
        // 这时已经退回纯视锥剔除，画面不会缺东西 —— 所以要显示成「回退」而不是「关」。
        return "开（本帧未取到原版可见段，已回退视锥剔除）";
    }

    /** 调试用：本帧可见的缓存段数（未启用时为 0）。 */
    public static int visibleCacheSections() {
        return statsVisibleCacheSections;
    }

    /** 颜色通道归一化常量（缓存写顶点时用。） */
    public static float colorChannelInv() {
        return COLOR_CHANNEL_INV;
    }
}
