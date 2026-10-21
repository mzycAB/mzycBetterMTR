package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.37 / Forge 移植】「未导入补丁」提示的**客户端控制器**：收服务端结论 → 稍等一下 → 弹窗。
 *
 * <h2>一、为什么要「稍等」</h2>
 * 那只 {@code MbmRepairNoticePacket} 是**进世界**那一刻到的（JOIN 推送 + 客户端主动请求），
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
 * 待办一旦被消费 / 被「不再显示」清掉就置 false，直到**断开连接**才复位 ——
 * 所以「进世界弹一次、在里面走来走去 / 换维度」不会再弹第二次。
 *
 * <h2>三、与「不再显示」的关系（★ 这条最要紧）</h2>
 * 点「不再显示」时，客户端**立刻**把 {@link #dismissedThisSession} 置 true
 * 并清掉待办，**不等**服务端写盘的结果：写盘是异步的，而界面必须马上收掉；
 * 万一写盘失败（只读存档 / 没权限），下一次进世界还会照常弹。
 *
 * <p>★ Forge 与 Fabric 的差别只有「怎么收包」：这里由 {@code MbmRepairNoticePacket.handle}
 * 调 {@link #onNotice}，tick 由 {@code SmoothLiftClientEvents.onClientTick} 调
 * {@link #onClientTick}；判定与文案仍只有一份（{@code smooth.lift.RepairNotice}）。
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

    private RepairNoticeClient() {
    }

    /**
     * 收到服务端那只「要不要弹」的包（由 {@code MbmRepairNoticePacket} 在主线程转发进来）。
     *
     * <p>★ 只认 {@code show}；其余三个布尔只打日志 —— 判定只有服务端一份，
     * 免得两边各算一套、出现「服务端说弹、客户端说不用」这种分叉。
     */
    public static void onNotice(boolean show, boolean checkEnabled, boolean hasPatch, boolean hasZip) {
        LOGGER.info("[SmoothLift/Repair] 收到补丁提示判定：弹窗={}（Repair_Check={}、已有补丁={}、"
                        + "文件夹里有 zip={}）",
                show, checkEnabled ? "1" : "0", hasPatch, hasZip);
        if (!show || dismissedThisSession || shownThisSession) {
            pending = false;
            return;
        }
        pending = true;
        countdown = DELAY_TICKS;
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
     * <p>★ 弹之前再确认一次 {@code mc.level != null}：断线 / 退出存档时那个包可能刚好到，
     * 此时弹窗会盖在「正在保存世界」的画面上。
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
            //   pending 不会泄漏：断线时 onDisconnect() 一定会复位。
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
