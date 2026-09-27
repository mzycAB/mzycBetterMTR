package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

/**
 * 拿着石斧右键扶梯后弹出的设置界面。
 *
 * <p>★★【09-27 六改】整页**五行**，版式与直梯一级菜单（{@code LiftToneSetupScreen} page 0）
 * **逐格同款**：左边一个 200 宽的按钮、中间一个画出来的标签、右边一个 70 宽的输入框。
 * <pre>
 *   [扶梯速度]      速度[ 0.5 ]    ← 左边按钮**点不动**（只当行名），右边框里编辑的是**速度**
 *   [阶梯速度]      速度[ 0.5 ]    ← 同上
 *   [声音设置…]     音量[ 100 ]    ← 按钮可点，进「自定义声音」子界面；框里编辑的是**底噪音量**
 *   [提示音(进入)]  音量[ 100 ]    ← 按钮可点，进「选择提示音」子界面（**进入扶梯**那一头）
 *   [提示音(离开)]  音量[ 100 ]    ← 按钮可点，进「选择提示音」子界面（**离开扶梯**那一头）
 * </pre>
 *
 * <p>用户原话（【09-27 六改】）：
 * <ul>
 *   <li>「扶梯ui的一级菜单的按钮大小调整为直梯ui一级菜单的按钮大小」——
 *       几何本来就是同一个 {@link #BTN_W}（200×20，反汇编两边的 {@code bounds} 都是
 *       {@code sipush 200 + bipush 20}）；真正不一样的是**速度行那两只是禁用态**，
 *       原版会画贴图里**灰的那一格**。现在它们改用 {@link RowNameButton}：
 *       借「启用态」那一格贴图 + 压掉 hover ⇒ 与直梯的按钮**逐像素同款**，
 *       但 {@code active} 仍是 {@code false}（点不动、不发声）；</li>
 *   <li>「"提示音设置"分裂为"提示音(进入)"和"提示音(离开)"2个按钮，所以2级菜单里的
 *       "设置端头"按钮删掉」—— 见上面第 4/5 两行；端头由**从哪一行进来**决定
 *       （{@link HelpAudioSetupScreen} 的构造参数），子界面里不再有「设置端头」。</li>
 * </ul>
 *
 * <p>★ 语义一格没混：上面两行的**速度框**编辑的是速度，下面三行的**音量框**编辑的是音量。
 *
 * <p>★ 提示音音量**只有一个值**（{@code /futihelploud} 那一路），所以第 4/5 行的两个框
 * 是**同一个值的两个入口**，靠 {@link #helpVolumeInInput} ⇄ {@link #helpVolumeOutInput}
 * 互相同步（改哪个都一样），落地时也只发一次包。
 *
 * <p>【1.9】声音部分：「声音设置…」行右边的框 —— 这条扶梯**运行底噪**的音量，输入 <b>1~1000</b>
 * （100 = 原始音量，1000 = 10× 放大），实际音量 = 距离衰减 × 这个百分比。
 *
 * <p>【1.16 / 1.18】无障碍部分：提示音的**总开关**已按用户点名删除 ——
 * 「选音乐 = 开、选不播 = 关」，不需要单独的开关（数据层 {@code helpEnabled} /
 * 指令 {@code /futihelp} 保留不动，只是界面上不再暴露）。
 *
 * <p>没有「确定」按钮：**按 ESC 退出界面时统一应用**（若都没改动则什么都不发）。
 * 点「声音设置…」/「提示音(进入)」/「提示音(离开)」进入子界面**之前**也会先把改动发出去，
 * 避免「刚填好音量就点了进子界面」导致丢失。
 */
public class EscalatorSpeedScreen extends Screen {

    // ------------------------------------------------------------------
    // 【09-27 六改】本页每一行 = 直梯一级菜单**逐格同款**：[按钮] 标签 [输入框]
    //
    //   ★ 行几何与 LiftToneSetupScreen 的 BTN_W / MAIN_GAP / MAIN_LABEL_GAP / MAIN_INPUT_W
    //     **逐项同值同名**（用户点名「按钮大小调整为直梯ui一级菜单的按钮大小」）：
    //     按钮一律 200 宽、框一律 70 宽、中间是 10 + 标签 + 6。
    //   ★ 行宽只算**一次**（标签列宽取「速度」「音量」里更宽的那个）⇒ 五行严格左对齐，
    //     不会因为两个标签的字符宽度不同而错位（用户上一轮抱怨过「不一样」）。
    // ------------------------------------------------------------------
    /** 每行左边那个按钮的宽度（= 直梯一级菜单的 BTN_W）。 */
    private static final int BTN_W = 200;
    /** 按钮 → 中间标签的空隙（= 直梯 MAIN_GAP）。 */
    private static final int MAIN_GAP = 10;
    /** 中间标签 → 输入框的空隙（= 直梯 MAIN_LABEL_GAP）。 */
    private static final int MAIN_LABEL_GAP = 6;
    /** 每行输入框的宽度（= 直梯 MAIN_INPUT_W）。 */
    private static final int MAIN_INPUT_W = 70;
    /** 速度行中间那个标签的文字（画出来的，不是按钮）。 */
    private static final String SPEED_LABEL = "速度";
    /** 音量行中间那个标签的文字（= 直梯 VOLUME_LABEL，同一份字）。 */
    private static final String VOLUME_LABEL = "音量";
    /** 五行的 y（行距 = 直梯二级列表的 ROW_H = 22）。 */
    private static final int ROW1_Y = 40;
    private static final int ROW2_Y = 62;
    private static final int ROW3_Y = 84;
    private static final int ROW4_Y = 106;
    private static final int ROW5_Y = 128;

    private final BlockPos pos;

    private EditBox runInput;
    private EditBox stepInput;
    private EditBox volumeInput;
    /** 【六改】提示音音量（**进入扶梯**那一行的框）—— 与 {@link #helpVolumeOutInput} 是同一个值。 */
    private EditBox helpVolumeInInput;
    /** 【六改】提示音音量（**离开扶梯**那一行的框）—— 与 {@link #helpVolumeInInput} 是同一个值。 */
    private EditBox helpVolumeOutInput;
    /** 两个提示音量框互相同步时置位，避免来回触发。 */
    private boolean suppressHelpVolumeMirror;

    /** 打开界面时各框里显示的基准值，用来判断玩家到底改了哪一项。 */
    private double openRun;
    private double openStep;
    private int openVolume;
    /** 【1.18】打开界面时提示音音量框里的基准值，用来判断玩家有没有改。 */
    private int openHelpVolume;

    /** 玩家是否手动改过阶梯速度框；没改过时，改扶梯速度会把阶梯速度一起带着变。 */
    private boolean stepEdited;
    /** 程序内部回填阶梯速度框时置位，避免被误判成「玩家手动修改」。 */
    private boolean suppressStepResponder;

    public EscalatorSpeedScreen(BlockPos pos) {
        super(Component.literal("扶梯设置"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        openRun = currentRunningSpeed(mc);
        openStep = currentStepSpeed(mc);
        openVolume = currentVolume(mc);
        openHelpVolume = currentHelpVolume(mc);

        // 行 1 / 2：[扶梯速度] 速度[框]  —— 左边按钮点不动（只当行名），右边框里编辑的是速度。
        addRenderableWidget(new RowNameButton(rowStartX(), ROW1_Y, Component.literal("扶梯速度")));
        runInput = new EditBox(this.font, rowInputX(), ROW1_Y, MAIN_INPUT_W, 20,
                Component.literal("扶梯速度"));
        runInput.setMaxLength(32);
        runInput.setValue(EscalatorSpeedData.format(openRun));
        runInput.setResponder(this::onRunEdited);
        addRenderableWidget(runInput);

        addRenderableWidget(new RowNameButton(rowStartX(), ROW2_Y, Component.literal("阶梯速度")));
        stepInput = new EditBox(this.font, rowInputX(), ROW2_Y, MAIN_INPUT_W, 20,
                Component.literal("阶梯速度"));
        stepInput.setMaxLength(32);
        stepInput.setValue(EscalatorSpeedData.format(openStep));
        stepInput.setResponder(this::onStepEdited);
        addRenderableWidget(stepInput);

        // 行 3：[声音设置…] 音量[框] —— 【1.9】底噪音量，1~1000（100 = 原始音量），只允许数字。
        addRenderableWidget(Button.builder(Component.literal("声音设置…"), button -> openAudioSetup())
                .bounds(rowStartX(), ROW3_Y, BTN_W, 20)
                .build());
        volumeInput = new EditBox(this.font, rowInputX(), ROW3_Y, MAIN_INPUT_W, 20,
                Component.literal("声音音量"));
        volumeInput.setMaxLength(4);
        volumeInput.setValue(String.valueOf(openVolume));
        volumeInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        addRenderableWidget(volumeInput);

        // 行 4 / 5：【六改】原来那一只「提示音设置…」按用户点名分裂成
        //   「提示音(进入)」与「提示音(离开)」——各自打开同一张子界面，但**预先锁定哪一头**
        //   （子界面里因此不再需要「设置端头」那一行）。
        //   右边的音量框 = 提示音音量（1~1000，只允许数字）；两行是**同一个值**，互相同步。
        addRenderableWidget(Button.builder(Component.literal("提示音(进入)"),
                        button -> openHelpAudioSetup(true))
                .bounds(rowStartX(), ROW4_Y, BTN_W, 20)
                .build());
        helpVolumeInInput = new EditBox(this.font, rowInputX(), ROW4_Y, MAIN_INPUT_W, 20,
                Component.literal("提示音音量"));
        helpVolumeInInput.setMaxLength(4);
        helpVolumeInInput.setValue(String.valueOf(openHelpVolume));
        helpVolumeInInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        helpVolumeInInput.setResponder(text -> mirrorHelpVolume(helpVolumeInInput, helpVolumeOutInput, text));
        addRenderableWidget(helpVolumeInInput);

        addRenderableWidget(Button.builder(Component.literal("提示音(离开)"),
                        button -> openHelpAudioSetup(false))
                .bounds(rowStartX(), ROW5_Y, BTN_W, 20)
                .build());
        helpVolumeOutInput = new EditBox(this.font, rowInputX(), ROW5_Y, MAIN_INPUT_W, 20,
                Component.literal("提示音音量"));
        helpVolumeOutInput.setMaxLength(4);
        helpVolumeOutInput.setValue(String.valueOf(openHelpVolume));
        helpVolumeOutInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        helpVolumeOutInput.setResponder(text -> mirrorHelpVolume(helpVolumeOutInput, helpVolumeInInput, text));
        addRenderableWidget(helpVolumeOutInput);

        setInitialFocus(runInput);

        // 【1.55】右上角「同步所有」：这一页是一级菜单，射程 = SYNC_TOP_LEVEL（服务端定义，**没变**）=
        //   速度 / 阶梯速度 / 声音音量 / 提示音音量 / 无障碍开关的**默认层**五项。
        //   ★【六改】无障碍开关那只按钮已按点名删除（选音乐=开、选不播=关），但**射程的定义没动** ——
        //   这一页的「同步所有」照旧会把开关的默认层一起同步。
        //   ★ beforeOpen 必须把输入框落地：弹窗会把本界面重建一次，而且服务端读
        //   「这条扶梯此刻的值」时读的是存档，没落地的新值读不到。
        addRenderableWidget(SyncPopupScreen.syncButton(this, "esc", SmoothLift.SYNC_TOP_LEVEL,
                pos.asLong(), this::applyChanges));
    }

    /**
     * 【六改】「行名按钮」：**外观与直梯一级菜单的按钮逐像素同款，但永远点不动**。
     *
     * <p>为什么需要它：{@code active = false} 的原版按钮画的是贴图里**禁用那一格**（灰的），
     * 与直梯那四个可点按钮（白/亮那一格）摆在一起就「看起来不是一个东西」。
     * 用户点名「按钮大小调整为直梯ui一级菜单的按钮大小」—— 本类索性把这两格统一：
     * 画的时候借**启用态**那一格贴图 + 白色字，同时把 hover 压掉（不许点亮），
     * 而 {@code active} 仍是 {@code false} ⇒ **点不动、不出声、拿不到焦点**。
     */
    private static final class RowNameButton extends Button {
        RowNameButton(int x, int y, Component label) {
            super(x, y, BTN_W, 20, label, button -> { }, DEFAULT_NARRATION);
            this.active = false;
        }

        /** 永远当作「鼠标没指着」⇒ 贴图落在「启用但不 hover」那一格（与直梯按钮同一格）。 */
        @Override
        public boolean isHovered() {
            return false;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            boolean wasActive = this.active;
            this.active = true; // 借启用态那一格贴图与白色字
            try {
                super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            } finally {
                this.active = wasActive;
            }
        }
    }

    /** 一行的总宽（按钮 + 空隙 + 标签列 + 空隙 + 输入框）—— 五行共用同一个值。 */
    private int rowWidth() {
        return BTN_W + MAIN_GAP + labelColumnWidth() + MAIN_LABEL_GAP + MAIN_INPUT_W;
    }

    /** 标签列宽：取两个标签里**更宽**的那个 ⇒ 两种标签的行也严格左对齐。 */
    private int labelColumnWidth() {
        return Math.max(this.font.width(SPEED_LABEL), this.font.width(VOLUME_LABEL));
    }

    /** 一行的起点 x（整行居中）。 */
    private int rowStartX() {
        return this.width / 2 - rowWidth() / 2;
    }

    /** 行内中间标签的 x（按钮右边）。 */
    private int rowLabelX() {
        return rowStartX() + BTN_W + MAIN_GAP;
    }

    /** 行内输入框的 x（标签列右边）。 */
    private int rowInputX() {
        return rowLabelX() + labelColumnWidth() + MAIN_LABEL_GAP;
    }

    /** 【六改】两个提示音量框互相同步（它们表示的是**同一个值**：`/futihelploud` 那一份）。 */
    private void mirrorHelpVolume(EditBox from, EditBox to, String text) {
        if (suppressHelpVolumeMirror || from == null || to == null) {
            return;
        }
        if (text.equals(to.getValue())) {
            return;
        }
        suppressHelpVolumeMirror = true;
        try {
            to.setValue(text);
        } finally {
            suppressHelpVolumeMirror = false;
        }
    }

    /**
     * 打开「自定义声音」子界面（本界面被替换掉，返回时由声音界面重建）。
     * 先把改动发出去：否则「填好音量 → 点声音设置 → 返回」会看到音量被重置回旧值。
     */
    private void openAudioSetup() {
        applyChanges();
        Minecraft.getInstance().setScreen(new AudioSetupScreen(pos));
    }

    /**
     * 【六改】打开「选择提示音」子界面（本界面被替换掉，返回时由提示音界面重建）。
     *
     * <p>★ {@code editIn} = 从哪一行进来的：{@code true} = 「提示音(进入)」那一行、
     * {@code false} = 「提示音(离开)」那一行。子界面拿它当「正在设置哪一头」，
     * 所以里面**不再有**「设置端头」那一行（用户点名删掉）。
     *
     * <p>同样先把改动发出去，理由与 {@link #openAudioSetup()} 一样。
     */
    private void openHelpAudioSetup(boolean editIn) {
        applyChanges();
        Minecraft.getInstance().setScreen(new HelpAudioSetupScreen(pos, editIn));
    }

    /** 这条扶梯当前的运行速度（未单独设置就是维度默认）。 */
    private double currentRunningSpeed(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_SPEED;
        }
        return EscalatorSpeedManager.getSpeed(mc.level, pos);
    }

    /** 这条扶梯当前的阶梯动画速度（单独设置 > /jietispeed 维度值 > 跟随运行速度）。 */
    private double currentStepSpeed(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_SPEED;
        }
        return EscalatorSpeedManager.getAnimationSpeed(mc.level, pos);
    }

    /**
     * 【1.9】这条扶梯当前的声音音量。
     * 用 {@code getVolumeForScreen}：会顺着扶梯链找同一条扶梯上已设过的音量，
     * 所以在这条扶梯的任意一个方块上打开界面，看到的都是同一个值。
     */
    private int currentVolume(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
        }
        return EscalatorSpeedManager.getVolumeForScreen(mc.level, pos);
    }

    /**
     * 【1.18】这条扶梯当前的**提示音**音量（1~1000，100 = 原始音量）。
     * 用 {@code getHelpVolume}：会顺着扶梯链找同一条扶梯上已设过的音量，
     * 所以在这条扶梯的任意一个方块上打开界面，看到的都是同一个值。
     * （注意与 {@link #currentVolume}（底噪音量）是两套独立数据。）
     */
    private int currentHelpVolume(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_HELP_VOLUME;
        }
        return EscalatorSpeedManager.getHelpVolume(mc.level, pos);
    }

    /** 改扶梯速度：只要玩家没自己动过阶梯速度框，就把阶梯速度框同步成一样的值。 */
    private void onRunEdited(String value) {
        if (suppressStepResponder || stepEdited || stepInput == null) {
            return;
        }
        suppressStepResponder = true;
        try {
            stepInput.setValue(value);
        } finally {
            suppressStepResponder = false;
        }
    }

    /** 改阶梯速度：标记玩家动过它，之后改扶梯速度就不再自动覆盖阶梯速度框。 */
    private void onStepEdited(String value) {
        if (suppressStepResponder) {
            return;
        }
        stepEdited = true;
    }

    /** 按 ESC（或回车）退出时统一应用改动。 */
    @Override
    public void onClose() {
        applyChanges();
        // Screen.onClose() 内部就是 minecraft.setScreen(null)。
        super.onClose();
    }

    /** 把改动过的值发出去（速度 / 底噪音量 / 提示音量各自独立，没改的不发）。 */
    private void applyChanges() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && runInput != null && stepInput != null) {
            Double run = parse(runInput.getValue());
            Double step = parse(stepInput.getValue());

            boolean runChanged = run != null && !nearly(run, openRun);
            boolean stepChanged = step != null && !nearly(step, openStep);
            // 阶梯速度正好等于（新的）扶梯速度时不必单独发送：设置扶梯速度会清掉单独设置，
            // 阶梯速度自然就跟随扶梯速度了，数据也更干净。
            boolean stepIsJustRun = runChanged && step != null && run != null && nearly(step, run);

            if (runChanged) {
                sendApply(true, EscalatorSpeedData.clamp(run),
                        stepChanged && !stepIsJustRun,
                        step == null ? 0.0 : EscalatorSpeedData.clamp(step));
                openRun = run;
                openStep = step != null ? step : openStep;
            } else if (stepChanged) {
                sendApply(false, 0.0, true, EscalatorSpeedData.clamp(step));
                openStep = step;
            }
        }
        // 【1.9】声音音量：1~1000，越界自动夹取
        if (mc.level != null && volumeInput != null) {
            Integer volume = parseVolume(volumeInput.getValue());
            if (volume != null && volume != openVolume) {
                FriendlyByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(pos);
                buf.writeVarInt(volume);
                ClientPlayNetworking.send(SmoothLift.SET_VOLUME_CHANNEL, buf);
                openVolume = volume;
            }
        }
        // 【1.18 / 六改】无障碍提示音音量（独立于上面的底噪音量）。
        //   两个框是同一个值 ⇒ 只读「进入」那一行、只发一次包（框里内容由镜像保证一致）。
        if (mc.level != null && helpVolumeInInput != null) {
            Integer volume = parseVolume(helpVolumeInInput.getValue());
            if (volume != null && volume != openHelpVolume) {
                FriendlyByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(pos);
                buf.writeVarInt(volume);
                ClientPlayNetworking.send(SmoothLift.SET_HELP_VOLUME_CHANNEL, buf);
                openHelpVolume = volume;
            }
        }
    }

    private void sendApply(boolean setRun, double run, boolean setStep, double step) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeBoolean(setRun);
        buf.writeDouble(run);
        buf.writeBoolean(setStep);
        buf.writeDouble(step);
        ClientPlayNetworking.send(SmoothLift.APPLY_CHAIN_CHANNEL, buf);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        // 【六改】五行：左按钮 + 中间标签 + 右边输入框（标签是画出来的，不是按钮）。
        drawRowLabel(guiGraphics, SPEED_LABEL, ROW1_Y);
        drawRowLabel(guiGraphics, SPEED_LABEL, ROW2_Y);
        drawRowLabel(guiGraphics, VOLUME_LABEL, ROW3_Y);
        drawRowLabel(guiGraphics, VOLUME_LABEL, ROW4_Y);
        drawRowLabel(guiGraphics, VOLUME_LABEL, ROW5_Y);

        // 【七改】按用户点名：这一页下方原来那**四行灰色小字**（改速度/阶梯速度说明、声音与提示音的
        //   射程说明、进/出两头说明、ESC 与坐标）**整段删掉** —— 一级菜单只留「标题 + 五行控件」。
    }

    /** 在某一行的标签列上画那个小标签（与直梯一级菜单一样，是画出来的、不是按钮）。 */
    private void drawRowLabel(GuiGraphics guiGraphics, String label, int rowY) {
        guiGraphics.drawString(this.font, Component.literal(label), rowLabelX(), rowY + 6, 0xFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 解析输入框内容；不是合法数字返回 null（视为未改动）。 */
    private static Double parse(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析音量输入框（1~1000，越界夹取）；空/非法返回 null（视为未改动）。 */
    private static Integer parseVolume(String text) {
        try {
            return EscalatorSpeedData.clampVolume(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean nearly(double a, double b) {
        return Math.abs(a - b) < 1.0E-6;
    }
}
