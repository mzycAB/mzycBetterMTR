package smooth.lift.client;

/**
 * 【1.57】「两列音频列表」版式的**唯一来源** —— 屏蔽门那几个二级页与列车音效的二级页共用这一份。
 *
 * <h2>为什么要有这个类</h2>
 *
 * 用户点名：列车音效点进去的二级界面要和屏蔽门那个**「使用相同设计」**，
 * 就是**左右两列列表**那一种。这句话的工程含义是：**同一套几何只能有一份数字**。
 * 两份各写一遍的话，将来任何一次「列宽调一下」都会只调一边
 * ——表现是「列车那一页比屏蔽门那一页窄一点」，而且**不报错**。
 *
 * <h2>★ 与 {@code PsdToneSetupScreen} 的关系：别名，不是抄一份</h2>
 *
 * {@code PsdToneSetupScreen} 里那些同名常量的**声明值**已经改成指向本类
 * （{@code private static final int COL_W = SoundListLayout.COL_W;}），
 * 于是全工程只有一个地方写 {@code 190}、{@code 16} 这些数字，
 * 而屏蔽门那份界面**一个用法都不用改**（编译期常量，javac 直接内联）。
 * 回归里有一条断言：那几个裸数字只许出现在本文件里。
 *
 * <h2>版式的构成（改任何一项前先读这里）</h2>
 *
 * <pre>
 *   两列各 {@link #COL_W} 宽，中间留 {@link #COL_GAP} 给一条竖线；
 *   右列每行 = [名字][选用][删除]，三格的宽度/间隔见 {@link #ROW_NAME_W} 那一族；
 *   右列**第 0 行是特殊项**（屏蔽门 = 「不播」、列车音效 = 「MTR自带音效」），
 *     它**只有「选用」、没有「删除」**（它不是一个音频文件，删无可删）；
 *   所以「已导入存档」的第 i 条落在第 {@code i + }{@link #RIGHT_COL_FIRST_STORED_ROW} 行。
 * </pre>
 *
 * <p>★ 列宽 {@link #COL_W} 与 {@link #COL_GAP} 是**兼容量出来的**（见
 * {@code PsdToneSetupScreen} 里那段注释）：两列 + 竖线 = 396 px，
 * 在 427 宽的画布（854×480 窗口 / GUI 缩放 2）上左列仍有 15 px 余量；再宽就会在小窗口上顶出去。
 */
public final class SoundListLayout {

    private SoundListLayout() {
    }

    // ------------------------------------------------------------------
    // 两列列表的公共几何
    // ------------------------------------------------------------------

    /** 行高。 */
    public static final int ROW_H = 22;
    /** 列表区顶部（列表页还会再让出一行表头，见 {@code buildUi} 里的 {@code listTop}）。 */
    public static final int LIST_TOP = 40;
    /** 每列宽度。 */
    public static final int COL_W = 190;
    /** 两列之间留给竖线的空白。 */
    public static final int COL_GAP = 16;
    /** 行内两个小按钮各自的宽度。 */
    public static final int ROW_BTN_W = 44;
    /** 行内按钮与名字之间、两个按钮之间的间隔。 */
    public static final int ROW_BTN_GAP = 4;
    /** 行内名字按钮的宽度（剩下的部分）；宽度按 6 px/字符反算可容字符数。 */
    public static final int ROW_NAME_W = COL_W - 2 * ROW_BTN_W - 2 * ROW_BTN_GAP;
    /** 右列名字的截断上限（中文字符）。 */
    public static final int ROW_NAME_CHARS = 14;
    /** 右列**第 0 行**是特殊项（不播 / MTR自带音效）⇒ 已导入第 i 条落在第 i + 1 行。 */
    public static final int RIGHT_COL_FIRST_STORED_ROW = 1;

    // ------------------------------------------------------------------
    // 列表页底部固定区
    // ------------------------------------------------------------------

    /** 「返回 / 刷新」这一行的 y（相对 {@code height}，负数往上）。 */
    public static final int BTN_Y = -30;
    /** 列表页状态行的 y（相对 {@code height}）。 */
    public static final int STATUS_Y_LIST = -42;
    /** 列表页底部给「状态行 + 返回/刷新」预留的高度。 */
    public static final int BOTTOM_RESERVE_LIST = 44;

    // ------------------------------------------------------------------
    // 几何：控件与绘制**必须**都从这里取，别再各算一遍
    // ------------------------------------------------------------------

    /** 左列左上角 x。 */
    public static int leftColX(int width) {
        return width / 2 - COL_GAP / 2 - COL_W;
    }

    /** 右列左上角 x。 */
    public static int rightColX(int width) {
        return width / 2 + COL_GAP / 2;
    }

    /** 中间那条竖线的 x（画布正中）。 */
    public static int dividerX(int width) {
        return width / 2;
    }

    /** 滚动条的 x。 */
    public static int scrollBarX(int width) {
        return rightColX(width) + COL_W + 8;
    }

    /** 第 {@code index} 行在屏幕上的 y（已算上滚动偏移）。 */
    public static int rowY(int listTop, int index, int scroll) {
        return listTop + index * ROW_H - scroll;
    }

    /** 右列行内「选用」按钮的 x。 */
    public static int rowPickX(int width) {
        return rightColX(width) + ROW_NAME_W + ROW_BTN_GAP;
    }

    /** 右列行内「删除」按钮的 x。 */
    public static int rowDeleteX(int width) {
        return rightColX(width) + COL_W - ROW_BTN_W;
    }

    /**
     * 两列行数 → 滚动范围。
     *
     * <p>★ 口径**只在这里写一次**：两列各画各的、但共用一个滚动偏移，
     * 所以取两者较大者。写两遍的话，「右列多一行」这件事迟早有一处漏改
     * （表现：滚动条比例不对 / 最后一行滚不到）。
     *
     * @param leftCount  左列行数
     * @param rightCount 右列行数（**含**第 0 行那个特殊项）
     */
    public static int maxScroll(int listTop, int listBottom, int leftCount, int rightCount) {
        int total = Math.max(leftCount, rightCount) * ROW_H;
        int visible = Math.max(ROW_H, listBottom - listTop);
        return Math.max(0, total - visible);
    }
}
