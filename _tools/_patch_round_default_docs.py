"""【10-05】把「扶梯提示音 / 直梯提示音 默认 4 格」的注释统一改为 10 格。

用户点名：「第一次加模组的默认：所有范围 10 5」⇒ EscalatorSpeedData 里
DEFAULT_HELP_ROUND / DEFAULT_LIFT_HELP_ROUND 已由 4 改成 10。
本脚本只同步**注释/文档**里仍然写着「默认 4」的地方（行为已由常量决定）。

只在每个锚点**恰好出现一次**时才替换；任何一条对不上就直接报错退出（不写半截）。
"""
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (相对路径, 旧串, 新串)
EDITS = [
    ("src/main/java/smooth/lift/EscalatorSpeedData.java",
     "             /futihelpround 设置，默认 {@link #DEFAULT_HELP_ROUND} = 4 格（点状音源）。",
     "             /futihelpround 设置，默认 {@link #DEFAULT_HELP_ROUND} = 10 格（★【10-05】原 4，统一成 10）。"),

    ("src/main/java/smooth/lift/EscalatorSpeedData.java",
     "        // 【1.47】直梯提示音淡入淡出范围：旧存档缺字段 → 默认 4 格（同「第一次载入」）",
     "        // 【1.47】直梯提示音淡入淡出范围：旧存档缺字段 → 默认 10 格（★【10-05】原 4；同「第一次载入」）"),

    ("src/main/java/smooth/lift/EscalatorSpeedData.java",
     "    //   底噪（/futiround，默认 16 格）与提示音（/futihelpround，默认 4 格）是**两套**数据，",
     "    //   底噪（/futiround）与提示音（/futihelpround）是**两套**数据（★【10-05】两套默认都已统一成 10/5），"),

    ("src/main/java/smooth/lift/EscalatorSpeedManager.java",
     "    //   底噪（/futiround）默认 16 格、提示音（/futihelpround）默认 4 格 —— 与 1.17 定的",
     "    //   底噪（/futiround）与提示音（/futihelpround）默认都是 10/5（★【10-05】原 4，已统一） —— 与 1.17 定的"),

    ("src/main/java/smooth/lift/SmoothLift.java",
     "    // 【1.24】/futihelpround —— 无障碍提示音的淡入淡出范围（默认 4 格）",
     "    // 【1.24】/futihelpround —— 无障碍提示音的淡入淡出范围（★【10-05】默认 10/5，原 4/5）"),

    ("src/main/java/smooth/lift/SmoothLift.java",
     "    //   ★ 与 /futiround 是**两件事**：本指令管的是端头**单块**那块提示音（默认 4 格），",
     "    //   ★ 与 /futiround 是**两件事**：本指令管的是端头**单块**那块提示音（默认 10 格），"),

    ("src/main/java/smooth/lift/SmoothLift.java",
     "        //   （默认 水平 10 / 垂直 5），/futihelpround 管端头**单块**的无障碍提示音（默认 水平 4 / 垂直 5）。",
     "        //   （默认 水平 10 / 垂直 5），/futihelpround 管端头**单块**的无障碍提示音（★【10-05】也已是 水平 10 / 垂直 5）。"),

    ("src/main/java/smooth/lift/SmoothLift.java",
     "        //   水平 xz 默认 4、垂直 y 默认 5）。",
     "        //   水平 xz 默认 10、垂直 y 默认 5）。"),

    ("src/main/java/smooth/lift/SmoothLift.java",
     "        //   淡入淡出范围（格）。★【10-03】双维：水平 xz 默认 4、垂直 y 默认 5。",
     "        //   淡入淡出范围（格）。★【10-03】双维：水平 xz 默认 10（★【10-05】原 4）、垂直 y 默认 5。"),

    ("src/client/java/smooth/lift/client/EscalatorAudioPlayer.java",
     "     * {@link EscalatorChimePlayer} 那边的默认 4 格（两者故意不同，见 1.17 / 1.24）。",
     "     * {@link EscalatorChimePlayer} 那边的默认（★【10-05】已与这里统一成 10 格）。"),

    ("src/client/java/smooth/lift/client/EscalatorChimePlayer.java",
     " * <p><b>【1.17】射程默认 4 格（不是 16）</b>：本提示音是**装在单个扶梯方块上**的点状音源，默认半径只有 4 格。",
     " * <p><b>【1.17】射程默认 4 格（不是 16）</b>：本提示音是**装在单个扶梯方块上**的点状音源。"
     "★【10-05】用户点名「所有范围 10 5」⇒ 默认半径已改为 **10 格**（原 4）。"),

    ("src/client/java/smooth/lift/client/EscalatorChimePlayer.java",
     " * {@link #gain(double, double)} 按「到**该端头**的距离」逐端算（默认 4 格内**按比例线性**衰减到 0，",
     " * {@link #gain(double, double)} 按「到**该端头**的距离」逐端算（默认 10 格内**按比例线性**衰减到 0，"),

    ("src/client/java/smooth/lift/client/EscalatorChimePlayer.java",
     "     * <p>★【10-03】拆双维：水平（xz）默认 4、垂直（y）默认 5（见 {@link #DEFAULT_RANGE_Y}），",
     "     * <p>★【10-03】拆双维：水平（xz）默认 10（★【10-05】原 4）、垂直（y）默认 5（见 {@link #DEFAULT_RANGE_Y}），"),

    ("src/client/java/smooth/lift/client/EscalatorChimePlayer.java",
     "        // 【1.24】可闻范围（格，★【10-03】水平默认 4 / 垂直默认 5）也逐 tick 现算：改完不用重进世界。",
     "        // 【1.24】可闻范围（格，★【10-03】双维；★【10-05】水平默认改为 10 / 垂直默认 5）也逐 tick 现算：改完不用重进世界。"),

    ("src/client/java/smooth/lift/client/EscalatorChimePlayer.java",
     "     * 距离增益：{@code range}（默认 4 格，可被 {@code /futihelpround} 改）内**按比例线性**衰减：",
     "     * 距离增益：{@code range}（默认 10 格，可被 {@code /futihelpround} 改）内**按比例线性**衰减："),

    ("src/client/java/smooth/lift/client/LiftChimePlayer.java",
     "        // ★【10-03】真实轿厢盒：**双维**（水平 xz 默认 4 / 垂直 y 默认 5），任一维越界即静音；",
     "        // ★【10-03】真实轿厢盒：**双维**（水平 xz 默认 10 —— ★【10-05】原 4 / 垂直 y 默认 5），任一维越界即静音；"),
]


def main() -> int:
    # 先全量校验：每条锚点必须在目标文件里恰好出现一次
    problems = []
    for rel, old, _new in EDITS:
        p = ROOT / rel
        if not p.is_file():
            problems.append("文件不存在: %s" % rel)
            continue
        text = p.read_text(encoding="utf-8")
        n = text.count(old)
        if n != 1:
            problems.append("锚点命中 %d 次（应为 1）: %s :: %s" % (n, rel, old[:60]))
    if problems:
        for x in problems:
            print("[FAIL] " + x, file=sys.stderr)
        return 1

    # 再逐文件串行写回（同文件多处编辑合并成一次读写）
    by_file = {}
    for rel, old, new in EDITS:
        by_file.setdefault(rel, []).append((old, new))

    for rel, pairs in by_file.items():
        p = ROOT / rel
        text = p.read_text(encoding="utf-8")
        for old, new in pairs:
            text = text.replace(old, new)
        p.write_text(text, encoding="utf-8", newline="")
        # 落地后回读校验
        back = p.read_text(encoding="utf-8")
        for old, new in pairs:
            if new not in back:
                print("[FAIL] 回读校验失败: %s :: %s" % (rel, new[:60]), file=sys.stderr)
                return 1
        print("[OK] %s  (%d 处)" % (rel, len(pairs)))

    print("全部 %d 处注释已更新" % len(EDITS))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
