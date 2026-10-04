package smooth.lift.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.PictureBlocks;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 【1.18.1204】地图图片的客户端纹理侧：把服务端同步来的图片字节裁成 4 角、
 * 缩放到 1024×1024、按角落画 64px 灰色边框，然后写进方块图集
 * {@code shsubwaypicture:block/pic_*} 四个 sprite 的 CPU 侧像素，再重新上传。
 *
 * <p>为什么能「直接改图集」：原图贴图模板（mod-template 自带的 pic_*.png）恰好就是
 * 1024×1024，而 {@link TextureAtlasSprite} 的 {@code mainImage} 引用不能替换，
 * 但像素缓冲（{@code mainImage[0]}）本身可变 —— 用 {@link NativeImage#copyFrom}
 * 覆盖同一块缓冲即可，尺寸不变、引用不变，模型照常引用。改完重建 mip 链，
 * 绑定图集后调 {@link TextureAtlasSprite#uploadFirstFrame()} 把新像素推上 GPU。
 *
 * <p>空库 / 删除图片时（{@code currentPicture} 为空）画「全白 + 灰边」占位，
 * 对应「删除后正面改回白色、边框保留灰色」的需求（背面/侧面本来就不动）。
 *
 * <p>服务端数据见 {@link smooth.lift.EscalatorSpeedData#pictureLibrary} 与
 * {@link smooth.lift.EscalatorSpeedManager#sendPictureSyncTo}；接收器在
 * {@link SmoothLiftClient}。
 */
public final class PictureTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 单块边长（与原图贴图模板一致，也是生成尺寸）。 */
    public static final int TILE_SIZE = 1024;

    /** 灰边宽度（px），与 update-map.ps1 一致。 */
    public static final int BORDER = 64;

    /** 灰色边框 RGBA（128,128,128,255）。 */
    private static final int GRAY = 0xFF808080;

    /** 白色占位 RGBA（255,255,255,255）。 */
    private static final int WHITE = 0xFFFFFFFF;

    /** 4 个需要动态更新的 sprite 路径（不含命名空间前缀；方块模型全部复用它们）。 */
    private static final String[] CORNERS = {"pic_tl", "pic_tr", "pic_bl", "pic_br"};

    /** 客户端镜像：文件名 → 原始字节（与服务端 pictureLibrary 同构）。 */
    private static final Map<String, byte[]> LIBRARY = new HashMap<>();

    /** 当前显示图片文件名（空 = 无图，画白色+灰边占位）。 */
    private static String currentPicture = "";

    /** 上一次成功应用时各 sprite 的引用（用于检测资源重载后重贴）。 */
    private static TextureAtlasSprite[] appliedContents = null;

    /** 资源重载检测用的 tick 节流计数。 */
    private static int reloadCheckTick = 0;

    private PictureTextures() {
    }

    /** 应用服务端同步的整份图片数据（必须在渲染线程调用，见 SmoothLiftClient 接收器）。 */
    public static void applyData(Map<String, byte[]> library, String current) {
        LIBRARY.clear();
        LIBRARY.putAll(library);
        currentPicture = current == null ? "" : current;
        try {
            applyInternal();
        } catch (Exception e) {
            LOGGER.error("[SmoothLift/Picture] 图片纹理应用失败（current={}）", currentPicture, e);
        }
    }

    /** 断开连接：清空镜像与引用（残留上次世界的图片会串图）。 */
    public static void onDisconnect() {
        LIBRARY.clear();
        currentPicture = "";
        appliedContents = null;
    }

    /**
     * 每 tick 调一次（fabric END_CLIENT_TICK 回调带 Minecraft 参数）：fabric-api 0.96.1
     * 没有 ClientResourceReloadEvents，只能靠轮询发现「图集重载后 sprite 被重建」
     * （/reload 后 TextureAtlasSprite 是新对象）。每 20 tick（1 秒）比对一次引用，
     * 变了就把库里的图重新贴回去。
     */
    public static void onClientTick(Minecraft client) {
        if (LIBRARY.isEmpty()) {
            appliedContents = null;
            reloadCheckTick = 0;
            return;
        }
        if (++reloadCheckTick < 20) {
            return;
        }
        reloadCheckTick = 0;
        try {
            TextureAtlas atlas = getAtlas();
            if (atlas == null) {
                return;
            }
            for (int i = 0; i < CORNERS.length; i++) {
                TextureAtlasSprite sprite = atlas.getSprite(PictureBlocks.id("block/" + CORNERS[i]));
                if (appliedContents != null && appliedContents[i] == sprite) {
                    return; // 引用没变 = 图集没重载
                }
            }
            // 图集重载过了：重贴一次（应用内部会重建引用）
            LOGGER.info("[SmoothLift/Picture] 检测到资源重载，重新应用图片纹理（{}）", currentPicture);
            applyInternal();
        } catch (Exception e) {
            LOGGER.warn("[SmoothLift/Picture] 资源重载检测失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 核心：裁切 → 缩放 → 灰边 → 写图集 → 上传
    // ------------------------------------------------------------------

    private static void applyInternal() {
        TextureAtlas atlas = getAtlas();
        if (atlas == null) {
            LOGGER.warn("[SmoothLift/Picture] 方块图集尚未加载，跳过本次应用");
            return;
        }
        // 有当前图片 → 从库取字节解码；没有 → 全白占位（四角都画灰边）
        byte[] bytes = currentPicture.isEmpty() ? null : LIBRARY.get(currentPicture);
        NativeImage[] quads = new NativeImage[4];
        if (bytes == null) {
            for (int i = 0; i < 4; i++) {
                quads[i] = blankQuad(i);
            }
        } else {
            NativeImage src = null;
            boolean oversized = false;
            try {
                // 解码前先用文件头探测尺寸，拒绝超限图片：NativeImage 按 宽×高×4 在原生内存分配，
                // 12MB 压缩上限约束不住解码后的内存（曾导致 /MBM picture new 后 OOM）。
                int[] dim = EscalatorSpeedManager.probeImageSize(bytes);
                if (dim != null && (dim[0] > EscalatorSpeedManager.MAX_PICTURE_DIMENSION
                        || dim[1] > EscalatorSpeedManager.MAX_PICTURE_DIMENSION)) {
                    oversized = true;
                } else {
                    // 用 Java ImageIO 解码而不是 NativeImage.read：后者的 stb 路径走 LWJGL
                    // MemoryStack，崩溃报告里 /MBM picture new 后 Out of stack space 正是指向
                    // 这一行；ImageIO 是纯 Java 堆内存解码，彻底绕开 LWJGL 线程栈。
                    src = decodeImage(bytes);
                    // 解码后复查一遍（防旧存档里已有超限图片 / 头部探测失败的漏网之鱼）。
                    if (src.getWidth() > EscalatorSpeedManager.MAX_PICTURE_DIMENSION
                            || src.getHeight() > EscalatorSpeedManager.MAX_PICTURE_DIMENSION) {
                        oversized = true;
                    }
                }
                if (!oversized) {
                    quads = splitToQuads(src);
                }
            } catch (IOException e) {
                LOGGER.error("[SmoothLift/Picture] 图片字节解码失败（{}），改为白色占位", currentPicture, e);
                for (int i = 0; i < 4; i++) {
                    quads[i] = blankQuad(i);
                }
            } finally {
                if (src != null) {
                    src.close();
                }
            }
            if (oversized) {
                LOGGER.warn("[SmoothLift/Picture] 拒绝超大图片 {}（单边上限 {}px），改为白色占位",
                        currentPicture, EscalatorSpeedManager.MAX_PICTURE_DIMENSION);
                for (int i = 0; i < 4; i++) {
                    quads[i] = blankQuad(i);
                }
            }
        }
        // 四个 sprite 逐个写像素并上传（同一图集，绑一次纹理即可）
        RenderSystem.bindTexture(atlas.getId());
        TextureAtlasSprite[] newApplied = new TextureAtlasSprite[4];
        try {
            for (int i = 0; i < 4; i++) {
                TextureAtlasSprite sprite = atlas.getSprite(PictureBlocks.id("block/" + CORNERS[i]));
                newApplied[i] = sprite;
                uploadQuad(sprite, quads[i]);
                sprite.uploadFirstFrame();
            }
        } finally {
            for (NativeImage quad : quads) {
                if (quad != null) {
                    quad.close();
                }
            }
        }
        appliedContents = newApplied;
        long kb = 0L;
        for (byte[] value : LIBRARY.values()) {
            kb += value.length;
        }
        LOGGER.info("[SmoothLift/Picture] 图片纹理已应用（current={}，库 {} 张 {}KB）",
                currentPicture.isEmpty() ? "(无)" : currentPicture, LIBRARY.size(), kb / 1024);
    }

    /**
     * 把一张 1024×1024 的 NativeImage 覆盖进 sprite 的 CPU 像素并重建 mip 链。
     * 复用现有缓冲（copyFrom）而不是替换引用 —— mainImage 引用必须保持
     * 与原图集一致，否则模型烘焙时拿到的 UV/帧信息会对不上。
     */
    private static void uploadQuad(TextureAtlasSprite sprite, NativeImage quad) {
        // 1.18.2 的 mainImage 是 final 数组，只能就地覆盖各层像素（尺寸不变）。
        NativeImage[] mips = sprite.mainImage;
        int mipCount = mips.length - 1;
        // 覆盖 level 0（1024×1024 与模板同尺寸，copyFrom 直接整块复制）
        mips[0].copyFrom(quad);
        // 重建 mip 链：1.18.2 的 generateMipLevels 以「基础层 + 级别数」为参数，
        // 返回数组的 [0] 就是传入的那张基础图（同一引用）。
        NativeImage[] newMips = MipmapGenerator.generateMipLevels(mips[0], mipCount);
        for (int i = 1; i < mips.length && i < newMips.length; i++) {
            mips[i].copyFrom(newMips[i]);
            newMips[i].close();
        }
    }

    /**
     * 用 Java ImageIO 解码图片字节并转成 NativeImage。
     *
     * <p>为什么不用 {@link NativeImage#read}：它的 stb 解码路径要经过 LWJGL 的
     * {@code MemoryStack}（native 调用把参数压线程栈），崩溃报告里 /MBM picture new
     * 后的 {@code java.lang.OutOfMemoryError: Out of stack space} 正是
     * {@code MemoryStack.nmalloc} 抛在那一行；ImageIO 是纯 Java 堆内存解码，
     * 与 LWJGL 线程栈完全无关。像素以 ARGB 批量取出后转成 NativeImage 的
     * RGBA 字节序（{@code setPixelRGBA} 的 int 布局是 A|B|G|R，与 getRGB 的 A|R|G|B
     * 只差红蓝互换；灰边/白色常量 R=G=B 不受影响）。
     */
    private static NativeImage decodeImage(byte[] bytes) throws IOException {
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
        if (bi == null) {
            throw new IOException("ImageIO 无法识别该图片格式");
        }
        int w = bi.getWidth();
        int h = bi.getHeight();
        NativeImage img = new NativeImage(w, h, false);
        int[] argb = bi.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = argb[row + x];
                img.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c & 0x00FF0000) >>> 16) | ((c & 0x000000FF) << 16));
            }
        }
        return img;
    }

    /** 按 update-map.ps1 的算法把源图切成 4 角并各缩放到 1024×1024、画灰边。 */
    private static NativeImage[] splitToQuads(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int hw = w / 2;
        int hh = h / 2;
        NativeImage tl = new NativeImage(TILE_SIZE, TILE_SIZE, false);
        NativeImage tr = new NativeImage(TILE_SIZE, TILE_SIZE, false);
        NativeImage bl = new NativeImage(TILE_SIZE, TILE_SIZE, false);
        NativeImage br = new NativeImage(TILE_SIZE, TILE_SIZE, false);
        // 裁剪矩形与 update-map.ps1 完全一致：右/下用 w-hw / h-hh 兜奇数尺寸
        src.resizeSubRectTo(0, 0, hw, hh, tl);
        src.resizeSubRectTo(hw, 0, w - hw, hh, tr);
        src.resizeSubRectTo(0, hh, hw, h - hh, bl);
        src.resizeSubRectTo(hw, hh, w - hw, h - hh, br);
        // 灰边：tl 顶+左、tr 顶+右、bl 底+左、br 底+右，各 64px（与 update-map.ps1 一致）
        drawBorder(tl, true,  false, true,  false);
        drawBorder(tr, true,  false, false, true);
        drawBorder(bl, false, true,  true,  false);
        drawBorder(br, false, true,  false, true);
        return new NativeImage[]{tl, tr, bl, br};
    }

    /** 全白占位图（删除图片后的正面）；corner 0=tl 1=tr 2=bl 3=br（与 {@link #CORNERS} 一致）。
     *  灰边只画外边缘（tl=顶+左、tr=顶+右、bl=底+左、br=底+右，与 {@link #splitToQuads} 同规则），
     *  这样 4 块拼起来只有外圈一圈灰边、中间接缝处无灰线。 */
    private static NativeImage blankQuad(int corner) {
        NativeImage quad = new NativeImage(TILE_SIZE, TILE_SIZE, false);
        quad.fillRect(0, 0, TILE_SIZE, TILE_SIZE, WHITE);
        switch (corner) {
            case 0 -> drawBorder(quad, true, false, true, false);
            case 1 -> drawBorder(quad, true, false, false, true);
            case 2 -> drawBorder(quad, false, true, true, false);
            default -> drawBorder(quad, false, true, false, true);
        }
        return quad;
    }

    /** 画灰边：top/bottom 为横边、left/right 为竖边。 */
    private static void drawBorder(NativeImage img, boolean top, boolean bottom, boolean left, boolean right) {
        if (top) {
            img.fillRect(0, 0, TILE_SIZE, BORDER, GRAY);
        }
        if (bottom) {
            img.fillRect(0, TILE_SIZE - BORDER, TILE_SIZE, BORDER, GRAY);
        }
        if (left) {
            img.fillRect(0, 0, BORDER, TILE_SIZE, GRAY);
        }
        if (right) {
            img.fillRect(TILE_SIZE - BORDER, 0, BORDER, TILE_SIZE, GRAY);
        }
    }

    /** 取方块图集；未加载时返回 null。 */
    private static TextureAtlas getAtlas() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return null;
        }
        TextureManager tm = mc.getTextureManager();
        ResourceLocation loc = TextureAtlas.LOCATION_BLOCKS;
        if (tm.getTexture(loc) instanceof TextureAtlas atlas) {
            return atlas;
        }
        return null;
    }
}
