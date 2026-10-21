package smooth.lift;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.net.SLNet;

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
 *   <li><b>{@code Repair_Check=0}</b> ⇒ **立即不弹**，而且**连补丁查都不查**；</li>
 *   <li>否则，存档里**已经导入过补丁** ⇒ 不弹；</li>
 *   <li>否则（{@code Repair_Check=1} 且没有任何补丁）⇒ 弹。</li>
 * </ol>
 *
 * <p>★ 第 2 条的「导入过」看的是**整个存档**里任何一个维度有没有补丁素材
 * （见 {@link RepairStore#hasAnywhere}）。
 *
 * <h2>三、为什么判定在服务端</h2>
 * 存档文件夹与 {@code MBM_settings.txt} 都在**服务端**那台机器上（{@link RepairSettings}），
 * 客户端够不着。所以服务端算完把结论发给客户端；
 * 用户点「不再显示」时，客户端发一只空包回来，服务端把 {@code Repair_Check} 写成 {@code 0}。
 *
 * <h2>四、Forge1.18.2 的网络写法</h2>
 * 本工程用 {@link SLNet} 适配层（它的签名与 Fabric 那套逐字对齐），
 * 所以这里的 {@code sendTo} 与 Fabric 版形状几乎一样，只是发包换成
 * {@code SLNet.sendToPlayer(player, 频道, buf)}。
 *
 * <h2>五、与 MTR3 / MTR4 / 服务端的兼容性</h2>
 * <ul>
 *   <li><b>MTR3 / MTR4</b>：本类**一个 MTR 类型都不碰**（只用原版的 {@link ServerPlayer} /
 *       {@link ServerLevel}）⇒ 两个 MTR 版本各自跑同一份代码。</li>
 *   <li><b>专用服务器</b>：本类**不含任何 net.minecraft.client 类型**（SLNet 那边的
 *       {@code ToClient} 也守这条），所以专用服务器加载它不会被 RuntimeDistCleaner 拒绝。</li>
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
     */
    public record Status(boolean show, boolean checkEnabled, boolean hasPatch, boolean hasZip) {
    }

    private RepairNotice() {
    }

    /** 算一次结论（纯判定，不写文件、不发包）。{@code level} 为空时按「不弹」处理。 */
    public static Status evaluate(ServerLevel level) {
        if (level == null) {
            return new Status(false, true, false, false);
        }
        try {
            // ① Repair_Check=0 ⇒ 不弹，而且连补丁都不查（用户点名的「不会再检测」）。
            if (!RepairSettings.isRepairCheckEnabled(level)) {
                return new Status(false, false, false, false);
            }
            // ② 已经导入过补丁（任何一个维度有都算）⇒ 不弹。
            boolean hasPatch = RepairStore.hasAnywhere(level.getServer());
            // ③ 只剩「Repair_Check=1 且没有补丁」这一种会弹。
            boolean hasZip = !RepairStore.patchZips(level).isEmpty();
            return new Status(!hasPatch, true, hasPatch, hasZip);
        } catch (Throwable t) {
            // ★ 判定失败不能把游戏搞崩：按「不弹」处理 + 一条 warn。
            LOGGER.warn("[SmoothLift/Repair] 补丁提示判定失败，本次不弹：{}", t.toString());
            return new Status(false, true, false, false);
        }
    }

    /**
     * 算结论并把「要不要弹」发给这个玩家（进世界时 / 客户端主动问时各调一次）。
     *
     * <p>★ 套 try：JOIN 那一刻玩家对象可能还没完全挂好，这只提示包**绝不能**
     * 把玩家进世界搞崩；客户端随后还会主动请求一次。
     */
    public static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            Status status = evaluate((ServerLevel) player.getLevel());
            net.minecraft.network.FriendlyByteBuf buf = SLNet.buf();
            buf.writeBoolean(status.show());
            buf.writeBoolean(status.checkEnabled());
            buf.writeBoolean(status.hasPatch());
            buf.writeBoolean(status.hasZip());
            SLNet.sendToPlayer(player, SmoothLift.MBM_REPAIR_NOTICE_CHANNEL, buf);
            LOGGER.info("[SmoothLift/Repair] 补丁提示判定：弹窗={}（Repair_Check={}、已有补丁={}、文件夹里有 zip={}）",
                    status.show(), status.checkEnabled() ? "1" : "0", status.hasPatch(), status.hasZip());
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift/Repair] 发送补丁提示判定失败（下次请求会重试）：{}", t.toString());
        }
    }

    /**
     * 玩家点了「不再显示」：把存档里的 {@code MBM_settings.txt} 写成 {@code Repair_Check=0}。
     *
     * <p>★ 写失败（没权限）不打扰玩家 —— 只记日志。
     *
     * @return 是否真的写进去了
     */
    public static boolean dismiss(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return RepairSettings.setRepairCheck((ServerLevel) player.getLevel(), false);
    }

    /** 世界启动时补一次「设置文件存在且带那一行」——用户点名「需要模组新建这个文件」。 */
    public static void ensureSettingsFile(MinecraftServer server) {
        if (server == null) {
            return;
        }
        RepairSettings.ensureExists(server.overworld());
    }
}
