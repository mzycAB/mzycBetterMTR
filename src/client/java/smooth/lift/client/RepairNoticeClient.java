package smooth.lift.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.SmoothLift;

/**
 * 【1.37】「未导入补丁」提示的**客户端控制器**：收服务端结论 → 稍等一下 → 弹窗。
 *
 * <h2>一、为什么要「稍等」</h2>
 * 那只 {@code MBM_REPAIR_NOTICE} 包是在**进世界**那一刻到的（JOIN 推送 + 客户端主动请求，
 * 见 {@link SmoothLift#MBM_REPAIR_NOTICE_REQUEST_CHANNEL}），而那一刻客户端的
 * 地形 / 加载界面往往还没让开。直接在网络回调里 {@code setScreen} 会把窗弹在加载画面上、
 * 甚至被随后的加载界面盖掉 ⇒ 玩家根本看不见。
 *
 * <p>所以：包到了只**记一个待办**，真正弹窗在主线程的 tick 里做，并且要同时满足
 * <ul>
 *   <li>已经过了 {@link #DELAY_TICKS} 个 tick（世界基本站稳）；</li>
 *   <li>当前没有别的界面（{@code mc.screen == null}）—— 不抢玩家已经打开的界面
 *       （模板 / 背包 / 别的模组的界面）。</li>
 * </ul>
 *
 * <h2>二、每个存档只弹一次</h2>
 * {@link #pending} 一旦被消费 / 被「不再显示」清掉就置 false，直到**断开连接**才复位 ——
 * 所以「进世界弹一次、在里面走来走去 / 换维度」不会再弹第二次（用户要的是
 * 「每一次启动游戏时」弹，不是「每次过传送门」弹）。
 *
 * <h2>三、与「不再显示」的关系（★ 这条最要紧）</h2>
 * 点「不再显示」时，客户端**立刻**把 {@link #dismissedThisSession} 置 true
 * 并清掉待办，**不等**服务端写盘的结果。理由：写盘是异步的（服务端主线程），
 * 而界面必须马上收掉；万一写盘失败（只读存档 / 没权限），下一次进世界还会照常弹
 * —— 这比「先弹一句已经不再提示、其实没写进去」要诚实。
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
    /** 本次会话已经点过「不再显示」—— 同一个世界里不再弹（下次启动由服务端写盘后的值决定）。 */
    private static volatile boolean dismissedThisSession;
    /**
     * 本次会话**已经弹过**了 —— 之后服务端再发几次「要弹」都不理。
     *
     * <p>★ 为什么需要它：进世界时服务端会推一次（JOIN）、客户端又会问一次
     * （{@link #requestOnJoin()}）；两包在**弱网 / 卡顿**下可能一前一后到得很开，
     * 只靠 {@link #pending} 的话第二个包会把已经弹过的窗**再弹一次**。
     * 用户要的是「每一次启动游戏时」弹，不是「弹两次」。
     */
    private static volatile boolean shownThisSession;
    /** 注册过了就不再重复注册（{@code onInitializeClient} 只会跑一次，这个护栏留给测试 / 重载）。 */
    private static boolean registered;

    private RepairNoticeClient() {
    }

    /** 客户端初始化时调一次：挂上收包 + tick。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 服务端算好的结论：show=true 才排队弹窗；后面几个布尔只打日志用。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.MBM_REPAIR_NOTICE_CHANNEL,
                (client, handler, buf, responseSender) -> {
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
                    client.execute(() -> {
                        if (dismissedThisSession || shownThisSession) {
                            return;
                        }
                        pending = true;
                        countdown = DELAY_TICKS;
                    });
                });

        ClientTickEvents.END_CLIENT_TICK.register(RepairNoticeClient::onClientTick);
    }

    /** 进世界后主动问一次（JOIN 推送的兜底：时序下服务端推的包可能早于客户端频道就绪）。 */
    public static void requestOnJoin() {
        if (!ClientPlayNetworking.canSend(SmoothLift.MBM_REPAIR_NOTICE_REQUEST_CHANNEL)) {
            // 服务端没装本模组 / 频道还没注册好：静默跳过（问了也没人答，硬发会刷日志）。
            LOGGER.debug("[SmoothLift/Repair] 频道不可用，跳过补丁提示判定请求");
            return;
        }
        ClientPlayNetworking.send(SmoothLift.MBM_REPAIR_NOTICE_REQUEST_CHANNEL, PacketByteBufs.empty());
    }

    /** 断开连接：所有状态复位（换世界 / 重进存档时要重新判定一次）。 */
    public static void onDisconnect() {
        pending = false;
        countdown = 0;
        dismissedThisSession = false;
        shownThisSession = false;
    }

    /**
     * 玩家点了「不再显示」：本会话不再弹（服务端那边同时把 Repair_Check 写成 0）。
     *
     * <p>★ 从弹窗里点的话 {@link #shownThisSession} 已经是 true；这里把它一并置上，
     * 是为了「万一弹窗是在 shown 之前被别的路径造出来」时也不会再弹第二次。
     */
    public static void markDismissed() {
        dismissedThisSession = true;
        shownThisSession = true;
        pending = false;
    }

    /**
     * 每 tick 看一眼待办：倒计时到点 + 当前没有别的界面 ⇒ 弹。
     *
     * <p>★ 弹之前再确认一次 {@code mc.level != null}：断线 / 退出存档时那个包可能刚好到，
     * 此时弹窗会盖在「正在保存世界」的画面上。
     */
    private static void onClientTick(Minecraft mc) {
        if (!pending || dismissedThisSession || shownThisSession) {
            return;
        }
        if (countdown > 0) {
            countdown--;
            return;
        }
        if (mc.player == null || mc.level == null) {
            // ★ **不要**在这里把 pending 清掉：进世界的头几 tick，客户端可能还没把玩家 / 世界
            //   挂上（那个包是 JOIN 前后到的）。清掉就再也不会弹了。
            //   pending 不会泄漏：断线时 {@link #onDisconnect()} 一定会复位。
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
