package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.SmoothLift;
import smooth.lift.net.SLNet;

/**
 * 【1.37 / Forge1.18.2 移植】「未导入补丁」提示的**客户端控制器**：收服务端结论 → 稍等一下 → 弹窗。
 *
 * <h2>一、为什么要「稍等」</h2>
 * 那只 {@code MBM_REPAIR_NOTICE} 包是在**进世界**那一刻到的（JOIN 推送 + 客户端主动请求），
 * 而那一刻客户端的地形 / 加载界面往往还没让开。直接在网络回调里 {@code setScreen}
 * 会把窗弹在加载画面上、甚至被随后的加载界面盖掉 ⇒ 玩家根本看不见。
 *
 * <p>所以：包到了只**记一个待办**，真正弹窗在主线程的 tick 里做，并且要同时满足
 * <ul>
 *   <li>已经过了 {@link #DELAY_TICKS} 个 tick（世界基本站稳）；</li>
 *   <li>当前没有别的界面（{@code mc.screen == null}）—— 不抢玩家已经打开的界面。</li>
 * </ul>
 *
 * <h2>二、每个存档只弹一次</h2>
 * 待办一旦被消费 / 被「不再显示」清掉就置 false，直到**断开连接**才复位。
 *
 * <h2>三、注册时机（★ 这条与 Fabric 不同）</h2>
 * 本工程走 {@link SLNet} 适配层：客户端回调必须通过 {@code SLNet.registerClient} 注册，
 * 而且必须**在客户端侧**注册（这个类只会在客户端被加载，见 SlClientNet 的类注释）。
 * 所以 {@link #registerClients()} 由 {@code SmoothLiftClientEvents} 在客户端启动时调用一次。
 *
 * <p>★ 本类含 {@code net.minecraft.client} 类型 —— **只能**在客户端被加载，
 * 绝不许从任何公共类（尤其是 {@code SLNet} / {@code SmoothLift}）直接引用，
 * 否则专用服务器会在 mod 构造期被 RuntimeDistCleaner 拒绝（1.34 踩过）。
 */
public final class RepairNoticeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 包到之后等多少个 tick 再弹。50 tick ≈ 2.5 秒：够世界加载界面让开，
     * 又不至于让玩家等太久（等太久玩家就开始走动 / 开背包了，反而更难弹）。
     */
    private static final int DELAY_TICKS = 50;

    /** 有没有一个「该弹、还没弹」的待办。 */
    private static volatile boolean pending;
    /** 待办还剩几个 tick 可以被消费（包到了才开始倒数）。 */
    private static volatile int countdown;
    /** 本次会话已经点过「不再显示」—— 同一个世界里不再弹。 */
    private static volatile boolean dismissedThisSession;
    /** 本次会话**已经弹过**了 —— 之后服务端再发几次「要弹」都不理。 */
    private static volatile boolean shownThisSession;
    /** 注册过了就不再重复注册。 */
    private static boolean registered;

    private RepairNoticeClient() {
    }

    /** 客户端启动时调一次：把「收包」注册进 SLNet 的派发表。 */
    public static void registerClients() {
        if (registered) {
            return;
        }
        registered = true;
        SLNet.registerClient(SmoothLift.MBM_REPAIR_NOTICE_CHANNEL, (client, handler, buf, sender) -> {
            boolean show = buf.readBoolean();
            boolean checkEnabled = buf.readBoolean();
            boolean hasPatch = buf.readBoolean();
            boolean hasZip = buf.readBoolean();
            LOGGER.info("[SmoothLift/Repair] 收到补丁提示判定：弹窗={}（Repair_Check={}、已有补丁={}、"
                            + "文件夹里有 zip={}）",
                    show, checkEnabled ? "1" : "0", hasPatch, hasZip);
            if (!show || dismissedThisSession || shownThisSession) {
                pending = false;
                return;
            }
            pending = true;
            countdown = DELAY_TICKS;
        });
    }

    /** 进世界后主动问一次（JOIN 推送的兜底：时序下服务端推的包可能早于客户端频道就绪）。 */
    public static void requestOnJoin() {
        try {
            SLNet.sendToServer(SmoothLift.MBM_REPAIR_NOTICE_REQUEST_CHANNEL, SLNet.emptyBuf());
        } catch (RuntimeException e) {
            LOGGER.debug("[SmoothLift/Repair] 频道不可用，跳过补丁提示判定请求：{}", e.toString());
        }
    }

    /** 断开连接：所有状态复位（换世界 / 重进存档时要重新判定一次）。 */
    public static void onDisconnect() {
        pending = false;
        countdown = 0;
        dismissedThisSession = false;
        shownThisSession = false;
    }

    /** 玩家点了「不再显示」：本会话不再弹（服务端那边同时把 Repair_Check 写成 0）。 */
    public static void markDismissed() {
        dismissedThisSession = true;
        shownThisSession = true;
        pending = false;
    }

    /**
     * 每 tick 看一眼待办：倒计时到点 + 当前没有别的界面 ⇒ 弹。
     *
     * <p>★ 弹之前再确认一次 {@code mc.level != null}：断线 / 退出存档时那个包可能刚好到。
     */
    public static void onClientTick(Minecraft mc) {
        if (!pending || dismissedThisSession || shownThisSession) {
            return;
        }
        if (countdown > 0) {
            countdown--;
            return;
        }
        if (mc.player == null || mc.level == null) {
            // ★ 不要在这里把 pending 清掉：进世界的头几 tick 客户端可能还没挂上玩家 / 世界。
            return;
        }
        Screen current = mc.screen;
        if (current != null) {
            // 玩家正在别的界面里（背包 / 模板 / 其它模组）：不抢，下一 tick 再看。
            return;
        }
        pending = false;
        shownThisSession = true;
        LOGGER.info("[SmoothLift/Repair] 弹出「未导入补丁」提示窗");
        mc.setScreen(new RepairNoticeScreen(null));
    }
}
