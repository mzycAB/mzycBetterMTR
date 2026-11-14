package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.RawNet;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【1.39.1211】「电梯 BGM」选择界面 —— 版式**照抄**直梯开关门提示音那几页
 * （{@link LiftToneSetupScreen} 的二级页 / {@link SoundListLayout}）：左右两列列表、
 * 未导入存档 / 已导入存档、中间竖线、滚动条、返回 / 刷新。
 *
 * <h2>与开关门提示音那页**唯一**的差别</h2>
 * <ul>
 *   <li>这里是「电梯**移动时循环播放**」的 BGM（播放端 {@link LiftBgmPlayer}），
 *       不是门一跳变就播一下的事件音；</li>
 *   <li>★ **没有默认音频**：右列**只有「不播」+ 已导入的 ogg**，没有「默认」那一行
 *       （用户点名「这个没有默认音频，要用户自己导入」）。导入目录固定
 *       {@code <存档>/MBM_Audio/lift/liftbgm}。</li>
 * </ul>
 *
 * <h2>反馈 / 小字</h2>
 * 界面里**一个字的状态/提示都不画**（用户点名「不要加一堆没用的小字」）：
 * 每次操作只把成败记进服务端的「本次界面会话」，退出直梯界面时统一回一条
 * 「UI执行成功 / UI执行失败」；指令侧同理只回「指令执行成功 / 指令执行失败」。
 *
 * <p>本页从直梯一级菜单点「电梯BGM设置…」进入，关闭时回到那一页（由它发 UI_CLOSE）。
 * 万一被单独打开（parent == null），就自己发一条 UI_CLOSE，不会丢反馈。
 */
public class LiftBgmSetupScreen extends Screen {

    /** 本次界面会话里是否出现过失败。 */
    private boolean uiFailed;

    // 两列版式：**全部**取自 SoundListLayout（唯一来源）
    private static final int ROW_H = SoundListLayout.ROW_H;
    private static final int LIST_TOP = SoundListLayout.LIST_TOP;
    private static final int COL_W = SoundListLayout.COL_W;
    private static final int ROW_BTN_W = SoundListLayout.ROW_BTN_W;
    private static final int ROW_NAME_W = SoundListLayout.ROW_NAME_W;
    private static final int ROW_NAME_CHARS = SoundListLayout.ROW_NAME_CHARS;
    private static final int BTN_Y = SoundListLayout.BTN_Y;
    private static final int BOTTOM_RESERVE_LIST = SoundListLayout.BOTTOM_RESERVE_LIST;

    /** 右列特殊行数：只有第 0 行「不播」⇒ 已导入第 i 条落在第 i + 1 行。 */
    private static final int RIGHT_SPECIAL_ROWS = 1;

    private final Screen parent;

    private final List<String> stored = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();

    private int scroll;
    private int maxScroll;
    private int listBottom;
    private int listTop = LIST_TOP + ROW_H;

    private static volatile LiftBgmSetupScreen OPEN;

    public LiftBgmSetupScreen(Screen parent) {
        super(Component.literal("选择电梯BGM"));
        this.parent = parent;
    }

    /** 服务端同步回来时刷新列表（界面还开着的情况下）。 */
    public static void notifyBgmDataChanged() {
        LiftBgmSetupScreen s = OPEN;
        if (s == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (OPEN == s) {
                s.init();
            }
        });
    }

    @Override
    protected void init() {
        OPEN = this;
        Minecraft mc = Minecraft.getInstance();
        stored.clear();
        pending.clear();
        if (mc.level != null) {
            stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level, EscalatorSpeedManager.CAT_LIFT_BGM));
            pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level, EscalatorSpeedManager.CAT_LIFT_BGM));
        }
        Collections.sort(stored);
        Collections.sort(pending);
        buildUi();
    }

    private void buildUi() {
        clearWidgets();
        listBottom = Math.max(LIST_TOP + ROW_H, this.height - BOTTOM_RESERVE_LIST);
        listTop = LIST_TOP + ROW_H;
        maxScroll = SoundListLayout.maxScroll(listTop, listBottom,
                pending.size(), RIGHT_SPECIAL_ROWS + stored.size());
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button ->
                        ClientPlayNetworking.send(RawNet.c2s(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty())))
                .bounds(cx + 4, this.height + BTN_Y, 96, 20).build());
        // 右上角「打开文件夹」：本页读的就是 MBM_Audio/lift/liftbgm，按钮开同一个目录。
        addRenderableWidget(FolderOpenButton.of(this,
                FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_LIFT_BGM)));

        // 左列：本分类文件夹里还没入库的 OGG —— 点一下 = 导入存档并设为 BGM。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importPending(id))
                    .bounds(SoundListLayout.leftColX(this.width), y, COL_W, 20).build());
        }

        String cur = currentValue();
        // 右列第 0 行：「不播」。它不是一个文件 ⇒ 只有「选用」、没有「删除」。
        //   ★ 本功能没有默认音频，所以**没有「默认」行**（用户点名）。
        int yOff = rowY(0);
        if (fullyVisible(yOff)) {
            boolean offNow = EscalatorSpeedData.LIFT_BGM_OFF.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((offNow ? "✓" : "") + "不播"),
                            button -> pick(EscalatorSpeedData.LIFT_BGM_OFF))
                    .bounds(SoundListLayout.rightColX(this.width), yOff, ROW_NAME_W, 20).build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pick(EscalatorSpeedData.LIFT_BGM_OFF))
                    .bounds(SoundListLayout.rowPickX(this.width), yOff, ROW_BTN_W, 20).build());
        }

        // 右列：已存入存档的音频 —— 名字 / 选用 / 删除。
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(i + RIGHT_SPECIAL_ROWS);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = id.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> pick(id))
                    .bounds(SoundListLayout.rightColX(this.width), y, ROW_NAME_W, 20).build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> pick(id))
                    .bounds(SoundListLayout.rowPickX(this.width), y, ROW_BTN_W, 20).build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteStored(id))
                    .bounds(SoundListLayout.rowDeleteX(this.width), y, ROW_BTN_W, 20).build());
        }
    }

    /** 当前这一维度**生效**的 BGM（界面拿它打 ✓）。 */
    private String currentValue() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null
                ? EscalatorSpeedData.LIFT_BGM_OFF
                : EscalatorSpeedManager.getLiftBgmAudio(mc.level);
    }

    /** 点右列：把本维度 BGM 设为 {「不播」/ 某段已导入的 ogg}。 */
    private void pick(String audioId) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(audioId, 128);
        ClientPlayNetworking.send(RawNet.c2s(SmoothLift.SET_LIFT_BGM_CHANNEL, buf));
        // 本地镜像立即跟上（服务端同步回来会再用权威值校准）。
        if (mcLevel() != null) {
            EscalatorSpeedManager.applyClientLiftBgmLocal(mcLevel().dimension(), audioId);
        }
        init();
    }

    /** 点左列：把文件夹里的 ogg 导入存档并直接设为 BGM（复用直梯那套导入通道）。 */
    private void importPending(String audioId) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(0L);                 // BGM 按维度存，不按竖井列；key 占位
        buf.writeUtf("bgm", 32);
        buf.writeUtf(audioId, 128);
        ClientPlayNetworking.send(RawNet.c2s(SmoothLift.IMPORT_FOLDER_LIFT_TONE_CHANNEL, buf));
        pending.remove(audioId);
        if (mcLevel() != null) {
            EscalatorSpeedManager.applyClientLiftBgmLocal(mcLevel().dimension(), audioId);
        }
        init();
    }

    /** 从存档删掉一段本分类的音频（服务端会同时把引用它的 BGM 回落成「不播」）。 */
    private void deleteStored(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(EscalatorSpeedManager.CAT_LIFT_BGM, 64);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(RawNet.c2s(SmoothLift.DELETE_AUDIO_CHANNEL, buf));
        stored.remove(id);
        init();
    }

    @Override
    public void onClose() {
        if (OPEN == this) {
            OPEN = null;
        }
        if (parent != null) {
            // 回到直梯一级菜单：由它在最终退出时统一回一条「UI执行成功 / UI执行失败」。
            Minecraft.getInstance().setScreen(parent);
        } else {
            SmoothLiftClient.sendUiClose(!uiFailed);
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        // 两列表头（与直梯 / 屏蔽门那几页逐字相同）
        guiGraphics.drawCenteredString(this.font, Component.literal("未导入存档"),
                SoundListLayout.leftColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("已导入存档"),
                SoundListLayout.rightColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        // 中间竖线 + 滚动条；★ 不画任何状态 / 反馈小字（用户点名）
        int cx = this.width / 2;
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);
        int rowCount = Math.max(pending.size(), RIGHT_SPECIAL_ROWS + stored.size());
        if (maxScroll > 0 && rowCount > 0) {
            int barX = SoundListLayout.scrollBarX(this.width);
            int trackH = Math.max(ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, listTop, barX + 4, listTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * ROW_H));
            int thumbY = listTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }
    }

    private net.minecraft.world.level.Level mcLevel() {
        return Minecraft.getInstance().level;
    }

    private int rowY(int index) {
        return SoundListLayout.rowY(listTop, index, scroll);
    }

    private boolean fullyVisible(int y) {
        return y >= listTop && y + 20 <= listBottom;
    }

    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }
}
