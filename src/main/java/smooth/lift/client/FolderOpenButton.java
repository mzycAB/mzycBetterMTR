package smooth.lift.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 【09-29】界面右上角那个「打开文件夹」按钮 —— 位置**紧挨在「同步所有」的左边**，
 * 点一下就在**本机**弹出这一页对应的**音频导入文件夹**窗口。
 *
 * <p>用户点名要的分工（一级菜单开「组」，二级页开「组里的分类」）：
 * <ul>
 *   <li>屏蔽门主界面 → {@code 存档/MBM_Audio/pbm}；屏蔽门「进站广播」页 → {@code .../pbm/arrive}；</li>
 *   <li>扶梯主界面 → {@code .../futi}；扶梯「运行底噪」/「无障碍提示音」两页 → {@code .../futi/music}、{@code .../futi/help}；</li>
 *   <li>直梯一级菜单 → {@code .../lift}；四个二级页 → {@code .../lift/up|down|open|close}；</li>
 *   <li>列车音效一级页 → {@code .../train}；五个二级页 → {@code .../train/run|round|switch|in|out}。</li>
 * </ul>
 * 图片那一族没有界面，走指令 {@code /MBM picture fold} —— 它落到的是同一个
 * {@link #open(String)}（服务端发一只包过来，见 {@link smooth.lift.SmoothLift#MBM_OPEN_FOLDER_CHANNEL}）。
 *
 * <h2>为什么路径要「客户端自己算」</h2>
 * 文件夹在**存档**里（{@code <存档根>/MBM_Audio/...}），而模组的数据层是**服务端**的
 * （{@link EscalatorSpeedManager#audioFolder} 拿的就是服务端的世界路径）。但「开一个文件夹窗口」
 * 只有客户端能做。单人游戏里两边是同一台机器、同一个存档目录，所以客户端用
 * {@link Minecraft#getSingleplayerServer()} 的 {@link MinecraftServer#getWorldPath(LevelResource)}
 * 算出来的路径与玩家在资源管理器里看到的**就是同一个**。
 *
 * <p>★ 多人（专用服务器 / 别人开的局域网）时这个文件夹在你**够不着**的那台机器上：
 * 这时不报错、不猜路径，只在聊天栏说明一句 —— 猜一个本地路径出来会「打开一个空文件夹」，
 * 那比不开更糟（见工程教训：{@code return null} 之前先问这句 null 是在说「永远不行」还是「现在还不行」）。
 */
public final class FolderOpenButton {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 按钮宽度：与右边那个「同步所有」入口按钮**同宽**（76），「打开文件夹」五个字放得下。 */
    public static final int W = SyncPopupScreen.ENTRY_W;

    /**
     * 允许打开的**相对文件夹**（相对存档根目录）白名单。
     *
     * <p>只放行 {@code MBM_Audio} / {@code MBM_Picture} 这两个模组自己的来源文件夹，
     * 以及它们下面**纯小写字母**的一层 / 两层子目录（{@code pbm/arrive}、{@code futi/music}…）。
     * 这样 {@code ..}、绝对路径、盘符、反斜杠全部进不来。
     *
     * <p>★ 服务端回包也要过这一道：{@link #open(String)} 是**唯一**入口，
     * 「包是服务端发来的」不构成无条件信任的理由（见工程教训：读别人的输入之前先问一句
     * 「这判据还会命中谁」）。
     */
    private static final Pattern SAFE_FOLDER = Pattern.compile("MBM_(Audio|Picture)(/[a-z]+)*");

    private FolderOpenButton() {
    }

    /**
     * 音频分类 → 存档里的相对路径（{@code MBM_Audio/<分类>}）。
     *
     * @param category 分类名（如 {@code pbm/arrive}）；传 {@code null} / 空串 = 那一组的**分组目录本身**
     *                 （如一级菜单要开的 {@code pbm}）
     */
    public static String audioPath(String category) {
        if (category == null || category.isEmpty()) {
            return EscalatorSpeedManager.AUDIO_FOLDER;
        }
        return EscalatorSpeedManager.AUDIO_FOLDER + "/" + category;
    }

    /**
     * 造出右上角「打开文件夹」按钮。
     *
     * <p>位置 = 「同步所有」的左邻：同步按钮占 {@code [width-4-76, width-4]}，
     * 本按钮占它左边再减一个间距。三个数（边距 / 宽度 / 间距）全部取自
     * {@link SyncPopupScreen} —— 右上角整排按钮的几何**只有那一处**定义，
     * 免得以后挪同步按钮时把这一只落在原地（工程教训：同一份规则出现在两处就是等着分叉）。
     *
     * @param host         宿主界面（只用它的宽度）
     * @param relativePath 相对存档根目录的文件夹（用 {@link #audioPath(String)} 拼）
     */
    public static Button of(Screen host, String relativePath) {
        return Button.builder(Component.literal("打开文件夹"), button -> open(relativePath))
                .bounds(host.width - SyncPopupScreen.ENTRY_MARGIN - SyncPopupScreen.ENTRY_W
                                - SyncPopupScreen.ENTRY_GAP - W,
                        SyncPopupScreen.ENTRY_Y, W, SyncPopupScreen.ENTRY_H)
                .build();
    }

    /**
     * 打开**本机**存档目录下的 {@code relativePath} 文件夹窗口；文件夹不存在就先建出来
     * （玩家点「打开文件夹」的预期就是「我马上能把 ogg 拖进去」）。
     *
     * @param relativePath 相对存档根目录的路径；不在白名单内直接拒绝（只写日志、不弹窗）
     */
    public static void open(String relativePath) {
        if (relativePath == null || !SAFE_FOLDER.matcher(relativePath).matches()) {
            LOGGER.warn("[SmoothLift/Folder] 拒绝打开可疑路径：{}", relativePath);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.hasSingleplayerServer() ? mc.getSingleplayerServer() : null;
        if (server == null) {
            message(mc, "只能在单人存档里打开文件夹（多人服务器上的文件夹不在你这台电脑上）");
            return;
        }
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve(relativePath);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift/Folder] 建不出文件夹 {}：{}", dir, e.toString());
        }
        try {
            Util.getPlatform().openFile(dir.toFile());
            LOGGER.info("[SmoothLift/Folder] 已打开文件夹 {}", dir);
        } catch (RuntimeException e) {
            // openFile 把 IOException 包成 RuntimeException（各平台实现不同）
            LOGGER.warn("[SmoothLift/Folder] 打开 {} 失败：{}", dir, e.toString());
            message(mc, "打开文件夹失败：" + dir);
        }
    }

    /** 把一句说明丢到玩家聊天栏（没有玩家 / 界面还没进世界就什么都不做）。 */
    private static void message(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
