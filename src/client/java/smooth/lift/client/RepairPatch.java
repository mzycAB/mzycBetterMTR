package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.RepairStore;
import smooth.lift.SmoothLift;

/**
 * 【补丁】**客户端**那一半：发请求 / 开文件夹 / 取同步过来的补丁字节。
 *
 * <h2>一、补丁在哪（用户点名的口径）</h2>
 * <blockquote>
 *   「补丁导入之后是直接把那些补丁音频**融入存档**的，而不是修补 jar 文件…
 *     点击删除补丁按钮之后删除融入存档的补丁音频，玩家自己导入的其他 ogg 不要动
 *     …补丁不要改模组 jar 文件内容！只修改存档！」
 * </blockquote>
 * 所以客户端这一侧**什么都不写**：
 * <ul>
 *   <li>「导入 / 删除」只是发一只空包给服务端，真正的读写都在 {@link RepairStore}
 *       （服务端读 `<存档>/MBM_Repair/Repair.zip`、写存档的
 *       {@link smooth.lift.EscalatorSpeedData#repairAssets}）；</li>
 *   <li>字节回到客户端靠**已有的整库音频同步**（分块），镜像在
 *       {@link EscalatorSpeedManager#applyClientRepairData}；</li>
 *   <li>{@link #bytes} 只读镜像 —— 播放端 {@code EscalatorAudioPlayer.bundledBytes}
 *       会**优先**取它，没有才退回 jar 内随包分发的自合成素材。</li>
 * </ul>
 *
 * <h2>二、反馈（模组铁规）</h2>
 * 界面里**不写任何操作反馈小字**。成败由服务端记进「本次界面会话」（
 * {@code SmoothLift.noteUiResult}），退出界面时统一回一条
 * 「UI执行成功 / UI执行失败」到聊天栏。所以这里的方法**返回 boolean 只是给界面用来标失败**，
 * 服务端那边失败（补丁包不在 / 解不开 / 没补丁可删）同样会把会话标成失败。
 */
public final class RepairPatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private RepairPatch() {
    }

    /**
     * 「补丁文件夹」：在本机把 `<存档>/MBM_Repair` 弹出来。
     *
     * <p>常量直接用 {@link RepairStore#FOLDER}（一份真相，别在两处各写一遍字符串）。
     */
    public static void openFolder() {
        FolderOpenButton.open(RepairStore.FOLDER);
    }

    /**
     * 「导入补丁」：请服务端去读补丁文件夹（`<存档>/MBM_Repair`）里**唯一那个 `.zip`** 并**融入存档**。
     *
     * <p>★ 2026-10-15 用户点名：那个 zip **名字不限、也不需要密码** —— 玩家自己录的 ogg 直接
     * 压进去就行（条目名用 {@code doorclose.ogg} 这种）。**缺哪个条目，哪个就回模组默认**；
     * 但文件夹里 zip **多于一个**时服务端**什么都不做、也不给任何提示**。
     *
     * @return 请求有没有发出去（不在世界里 = 没发，界面会记失败）
     */
    public static boolean importPatch() {
        if (!inWorld()) {
            LOGGER.warn("[SmoothLift/Repair] 不在世界里，忽略「导入补丁」");
            return false;
        }
        ClientPlayNetworking.send(SmoothLift.MBM_REPAIR_IMPORT_CHANNEL, PacketByteBufs.empty());
        LOGGER.info("[SmoothLift/Repair] 已请服务端导入补丁（读 {} 文件夹里唯一那个 .zip）",
                RepairStore.FOLDER);
        return true;
    }

    /**
     * 「删除补丁」：请服务端把**已经融入存档**的补丁素材删掉。
     *
     * <p>只清补丁那一张表；玩家自己导入的 ogg 不受影响（服务端那边保证，见 {@link RepairStore}）。
     */
    public static boolean deletePatch() {
        if (!inWorld()) {
            LOGGER.warn("[SmoothLift/Repair] 不在世界里，忽略「删除补丁」");
            return false;
        }
        ClientPlayNetworking.send(SmoothLift.MBM_REPAIR_DELETE_CHANNEL, PacketByteBufs.empty());
        LOGGER.info("[SmoothLift/Repair] 已请服务端删除存档里的补丁素材");
        return true;
    }

    /**
     * 取某个内置素材的**补丁字节**（服务端同步过来的镜像）。
     *
     * @param builtinKey `up` / `down` / `liftmusic` / `dooropen` / `doorclose` / `mdoorclose`
     * @return 补丁里的 ogg 字节；**没有导入过补丁**（或这个键不在补丁里）时返回 {@code null}
     */
    public static byte[] bytes(String builtinKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return null;
        }
        return EscalatorSpeedManager.getRepairBytes(mc.level, builtinKey);
    }

    /** 当前维度有没有补丁素材（诊断用）。 */
    public static boolean hasPatch() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null
                && EscalatorSpeedManager.hasRepairData(mc.level);
    }

    /** 在不在世界里（发包的前提；不在世界里发出去会被连接层丢掉并刷日志）。 */
    private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null && mc.player != null && mc.getConnection() != null;
    }
}
