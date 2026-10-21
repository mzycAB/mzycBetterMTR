package smooth.lift;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.37】「补丁提示」的**服务端判定 + 回包**：每次进世界时算一次「要不要弹那个窗」。
 *
 * <h2>一、用户点名的口径</h2>
 * <blockquote>
 *   「每一次启动游戏时如果检测到存档未导入补丁包，就弹窗提示：
 *     未导入补丁！某些功能可能被限制！补丁下载网站:mzyc.pages.dev
 *     …点击『不再显示』以后启动游戏不会再检测是否导入补丁（其实就是把 Repair_Check 改为 0）
 *     …『确定』点击后关闭弹窗，但是不更改 Repair_Check 的值，下次启动游戏还弹窗
 *     …如果玩家导入补丁之后就默认不弹窗」
 * </blockquote>
 *
 * <h2>二、判定顺序（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>{@code Repair_Check=0}</b> ⇒ **立即不弹**，而且**连补丁查都不查**
 *       （用户点名的「不会再检测」）；</li>
 *   <li>否则，存档里**已经导入过补丁** ⇒ 不弹（用户点名的「导入补丁之后就默认不弹窗」）；</li>
 *   <li>否则（{@code Repair_Check=1} 且没有任何补丁）⇒ 弹。</li>
 * </ol>
 *
 * <p>★ 第 2 条的「导入过」看的是**整个存档**里任何一个维度有没有补丁素材
 * （见 {@link RepairStore#hasAnywhere}）—— 玩家常在主世界玩、去下界建线，若只看当前维度，
 * 从主世界进下界那一刻就会误弹一次。</p>
 *
 * <h2>三、为什么判定在服务端</h2>
 * 存档文件夹与 {@code MBM_settings.txt} 都在**服务端**那台机器上（{@link RepairSettings}），
 * 客户端够不着。所以：
 * <ul>
 *   <li>服务端算完把结论**回包**给客户端（{@link SmoothLift#MBM_REPAIR_NOTICE_CHANNEL}，
 *       有效载荷第一位就是「要不要弹」；后面三位只是日志用的旁证）；</li>
 *   <li>用户点「不再显示」时，客户端发一只空包回来，服务端把 {@code Repair_Check} 写成 {@code 0}。</li>
 * </ul>
 *
 * <h2>四、与 MTR3 / MTR4 / 服务端的兼容性</h2>
 * <ul>
 *   <li><b>MTR3 / MTR4</b>：本类**一个 MTR 类型都不碰**（只用原版的 {@link ServerPlayer} /
 *       {@link ServerLevel}）⇒ 两个 MTR 版本各自跑同一份代码，不需要按版本分支。</li>
 *   <li><b>专用服务器</b>：服务端、客户端各装本模组时照常工作（判定在服务端、弹窗在客户端）；
 *       客户端没装 / 服务端没装时，{@code ServerPlayNetworking.canSend} / {@code ClientPlayNetworking.canSend}
 *       会拦住发包（见过的老问题：往不存在的频道发包会被连接层丢掉并刷日志）。</li>
 *   <li><b>单人游戏</b>：两边是同一台机器、同一个存档目录 ⇒ 弹窗与文件都落在玩家自己的存档里。</li>
 * </ul>
 */
public final class RepairNotice {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 补丁下载网站的地址（用户点名：{@code mzyc.pages.dev}）。 */
    public static final String WEBSITE = "mzyc.pages.dev";

    /** 弹窗正文三行（客户端只负责排版，文案集中在服务端这一处，改字只改这里）。 */
    public static final String TITLE = "未导入补丁！";
    public static final String LINE_1 = "某些功能可能被限制！";
    public static final String LINE_2 = "补丁下载网站:" + WEBSITE;

    /**
     * 一次「要不要弹窗」的结论。
     *
     * @param show         要不要弹（{@code Repair_Check=1} 且存档里没有补丁）
     * @param checkEnabled {@code Repair_Check} 的口径（{@code true} = 1）
     * @param hasPatch     存档里有没有补丁素材
     * @param hasZip       补丁文件夹（{@code MBM_Repair}）里有没有待导入的 zip —— **只用来打日志**
     *                     （玩家已经把包放进去了但还没点「导入补丁」，仍然算「未导入」）
     */
    public record Status(boolean show, boolean checkEnabled, boolean hasPatch, boolean hasZip) {
    }

    private RepairNotice() {
    }

    /**
     * 算一次结论（纯判定，不写文件、不发包）。{@code level} 为空时按「不弹」处理。
     *
     * <p>★ 这个方法是**幂等**的：算几次结果都一样，所以「JOIN 推一次 + 客户端再问一次」
     * 不会互相矛盾（两边拿到的都是同一份判定）。
     */
    public static Status evaluate(ServerLevel level) {
        if (level == null) {
            return new Status(false, true, false, false);
        }
        try {
            // ① Repair_Check=0 ⇒ 不弹，而且连补丁都不查（用户点名的「不会再检测」）。
            if (!RepairSettings.isRepairCheckEnabled(level)) {
                return new Status(false, false, false, false);
            }
            // ② 已经导入过补丁（任何一个维度有都算）⇒ 不弹（用户点名的「导入补丁之后就默认不弹窗」）。
            //   ★ 这里用「整个存档」而不是「当前维度」：玩家在主世界导入过补丁、走进下界那一刻
            //     当前维度是空的，只看当前维度会误弹一次（见 RepairStore.hasAnywhere）。
            boolean hasPatch = RepairStore.hasAnywhere(level.getServer());
            // ③ 只剩「Repair_Check=1 且没有补丁」这一种会弹。
            //   hasZip 只用来打日志：玩家把包放进 MBM_Repair 但还没点「导入补丁」时，
            //   存档里依然**没有**补丁素材 ⇒ 依然算「未导入」、依然要提示（否则他会以为已经好了）。
            boolean hasZip = !RepairStore.patchZips(level).isEmpty();
            return new Status(!hasPatch, true, hasPatch, hasZip);
        } catch (Throwable t) {
            // ★ 判定失败**不能**把游戏搞崩，也**不能**静默吞掉：按「不弹」处理 + 打一条 warn。
            //   为什么不按「弹」：这里抛异常多半是世界正在卸载 / 路径不可用，
            //   那种时候弹窗反而会盖在「正在保存世界」的画面上。
            LOGGER.warn("[SmoothLift/Repair] 补丁提示判定失败，本次不弹：{}", t.toString());
            return new Status(false, true, false, false);
        }
    }

    /**
     * 算结论并把「要不要弹」发给这个玩家（进世界时 / 客户端主动问时各调一次）。
     *
     * <p>★ 只在频道可用时发（{@code canSend}）：客户端没装本模组 / 还没握手完时直接跳过。
     * 客户端那一侧会**再问一次**（{@code MBM_REPAIR_NOTICE_REQUEST}），
     * 因为 JOIN 时序下服务端推送可能早于客户端频道就绪（本项目音频同步踩过这个坑）。
     */
    public static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            Status status = evaluate(player.serverLevel());
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(status.show());
            buf.writeBoolean(status.checkEnabled());
            buf.writeBoolean(status.hasPatch());
            buf.writeBoolean(status.hasZip());
            // 【1.21.1】1.21 的 Fabric 网络只认 CustomPacketPayload，走 RawNet 适配层
            //（字节布局与 1.20.4 逐字节一致，见 RawNet 类注释）。
            ServerPlayNetworking.send(player, RawNet.s2c(SmoothLift.MBM_REPAIR_NOTICE_CHANNEL, buf));
            LOGGER.info("[SmoothLift/Repair] 补丁提示判定：弹窗={}（Repair_Check={}、已有补丁={}、文件夹里有 zip={}）",
                    status.show(), status.checkEnabled() ? "1" : "0", status.hasPatch(), status.hasZip());
        } catch (Throwable t) {
            // JOIN 那一刻玩家对象可能还没完全挂好（serverLevel 为空等）——
            // 这只提示包**绝不能**把玩家进世界搞崩。客户端随后还会主动请求一次。
            LOGGER.warn("[SmoothLift/Repair] 发送补丁提示判定失败（下次请求会重试）：{}", t.toString());
        }
    }

    /**
     * 玩家点了「不再显示」：把存档里的 {@code MBM_settings.txt} 写成 {@code Repair_Check=0}。
     *
     * <p>★ 写失败（没权限）不打扰玩家 —— 只记日志；下一次进世界还会照常弹，
     * 比「弹一个假的『已经不再提示』」要诚实。
     *
     * @return 是否真的写进去了
     */
    public static boolean dismiss(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return RepairSettings.setRepairCheck(player.serverLevel(), false);
    }

    /** 世界启动时补一次「设置文件存在」——用户点名「需要模组新建这个文件如果存档文件夹里面没有」。 */
    public static void ensureSettingsFile(MinecraftServer server) {
        if (server == null) {
            return;
        }
        RepairSettings.ensureExists(server.overworld());
    }
}
