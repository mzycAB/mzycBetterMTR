# -*- coding: utf-8 -*-
"""离线校验：【09-28】「讲述人的进站广播和自定义的进站广播不是一个广播，可以同时存在。」

这条断言跨了两轮用户点名：
  ① 分段一（1.29/09-28）：把 `/jsr` 从自定义那条链路里拆出来，两条各自判断开关 ——
     用户报「还是没有声音啊，是不是因为到站广播选择了『不播』导致讲述人广播被取消」；
  ② 分段二（09-28）：念什么 = 「乘客们，列车马上就要进站了，本次列车终点站：X，
     请乘客们在Y站台有序候车」（X=终点站，Y=站台编号，都取自同一条 ArrivalResponse）；
  ③ 分段三（09-28）：讲述人**取消借用**进站广播的时间窗口 + 石斧 UI 给它加「进站广播(讲述人)」
     一行（二级页左列空着，右列只有「关闭 / 开启」，各自只留一个「选择」按钮）。
  ④ 分段四（09-28 续）：二级页那个「开启」改名 **「开启(上海)」**，并**新增「开启(香港)」** ——
     于是这一项从「开/关」升级成**三档样式**（0 关 / 1 上海 / 2 香港，一个 int 存，互斥）；
     香港档的报站词 = 中英双语两行（用户点名）：
         前往XXX的列车即将到达，请先让车上的乘客下车
         The train to YYY is arriving, please let passengers exit first.
     XXX = 站台**中文名**、YYY = 站台**英文名**；只有一种语言时只念那一种；
     日文 / 韩文等**其它语言**只念英文句、且用站台名本身（用户给的 ZZZ）。

## 根因（分段一，代码链可复核）

1.28 的第一版把 `/jsr` 这个「列车报站」总开关**挂在了自定义进站广播那条链路里**：

    String audio = EscalatorSpeedManager.getDoorPsdArriveAudio(mc.level, runKey);
    if (EscalatorSpeedData.isPsdArriveOff(audio)) { arriveVoice.remove(runKey); continue; }   // ← 第一道闸
    ...  ← /jsr 的 `if (!TrainAnnounceSwitch.isEnabled()) return;` 就摆在这条链路的入口

于是「自定义那条设成不播」⇒ 整条链路在这一行就 continue 了，讲述人必然静默。

## 判据（改错了不报错，症状=「设了不播之后讲述人又没声音了」/「两条又绑在一起了」）

- 两条广播各判各的开关：③-A 只看 `customOff`、③-B 只看讲述人那两层开关（全局 /jsr + 每串门）；
- **没有**任何「/jsr 关着就整条 return」的整体短路（那是 1.28 的错法）；
- 讲述人走 MTR 报站的同一个入口 `com.mojang.text2speech.Narrator.getNarrator().say(text, true)`
  （**不查**游戏 设置→辅助功能→讲述人，也不是原版 GameNarrator）；
- 分段三起：**窗口 / 去重各自独立**（讲述人用 getDoorPsdNarrateSeconds + firedArrivalNarrate），
  只有**声源位置 / 射程**（`distance` / `inRange`）还共用 —— 本来就只有一个声源。
- 分段四起：播放端取的是**样式** `getDoorPsdNarrateMode`（不是 isDoorPsdNarrateOn）——
  只看开/关会把香港档也念成上海词；三档互斥用一个 int ⇒ 拼不出「关着但选了香港」。
"""

import os
import re
import sys
import glob
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
NARRATOR = os.path.join(CLIENT, "TrainAnnounceNarrator.java")
SWITCH = os.path.join(CLIENT, "TrainAnnounceSwitch.java")
DWELL = os.path.join(CLIENT, "MtrDwellAccess.java")
SMOOTHCLIENT = os.path.join(CLIENT, "SmoothLiftClient.java")
SCREEN = os.path.join(CLIENT, "PsdToneSetupScreen.java")
SL = os.path.join(MAIN, "SmoothLift.java")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(s):
    """剥掉 // 与 /* */ 注释 —— 只看真代码，注释里提到某个 token 不算数。"""
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    out = []
    for line in s.split("\n"):
        i = line.find("//")
        out.append(line if i < 0 else line[:i])
    return "\n".join(out)


player = strip_comments(load(PLAYER))
narrator = strip_comments(load(NARRATOR))
switch = strip_comments(load(SWITCH))
dwell = strip_comments(load(DWELL))
screen = strip_comments(load(SCREEN))
smoothclient = strip_comments(load(SMOOTHCLIENT))
sl = strip_comments(load(SL))
mgr = strip_comments(load(MGR))
data = strip_comments(load(DATA))

m_body = re.search(r"private static void tickArriveAnnounce\(Minecraft mc,"
                   r" List<PsdDoorTracker\.DoorView> doors\)\s*\{(.*?)\n    \}", player, flags=re.S)
check(m_body is not None, "抠得出 tickArriveAnnounce() 方法体")
body = m_body.group(1) if m_body else ""

# ======================================================================
# 1) 两条广播各判各的开关（09-28 分段一的核心）
# ======================================================================
print()
print("===== 1) 讲述人与自定义进站广播是两条独立广播 =====")
check(re.search(r"boolean narrateGlobal\s*=\s*TrainAnnounceSwitch\.isEnabled\(\);", body) is not None,
      "narrateGlobal 取自 /jsr（讲述人那一条的**全局**总闸）")
check(re.search(r"int narrateMode\s*=\s*narrateGlobal\s*\n?\s*\?\s*"
                r"EscalatorSpeedManager\.getDoorPsdNarrateMode\(mc\.level, runKey\)\s*\n?\s*:\s*"
                r"EscalatorSpeedData\.PSD_NARRATE_OFF;", body) is not None,
      "★ narrateMode = 全局 /jsr ? 这一串门自己的**样式** : 关闭"
      "（分段四：取样式而不是开/关，否则香港档会被念成上海词）")
check(re.search(r"boolean narrateOn\s*=\s*narrateMode\s*!=\s*EscalatorSpeedData\.PSD_NARRATE_OFF;",
                body) is not None,
      "★ narrateOn 由样式派生（开着 = 样式 ≠ 关闭）—— 「与」的口径没变")
check(re.search(r"if\s*\(!TrainAnnounceSwitch\.isEnabled\(\)\)\s*\{", body) is None,
      "★ 方法体里**没有**「/jsr 关着就整条 return」的整体短路（那是 1.28 的错法）")
check(re.search(r"if\s*\(!narrateGlobal\)\s*\{[^}]*return;", body) is None,
      "★ 也没有任何以 narrateGlobal 为条件的整条早退")
check("boolean customOff = EscalatorSpeedData.isPsdArriveOff(audio);" in body,
      "customOff 单独成变量（「不播」只关自定义那一条的前提）")
check(re.search(r"if\s*\(customOff && !narrateOn\)\s*\{[^}]*arriveVoice\.remove\(runKey\);"
                r"[^}]*continue;", body) is not None,
      "两条都关时才跳过这一串（老口径：顺手清状态）")
check("if (customOff) {" not in body.replace("if (customOff && !narrateOn) {", ""),
      "★ 没有「只要 customOff 就 continue」——不播不再连累讲述人")

# ③-A / ③-B 两个分支的条件
m_a = re.search(r"if\s*\(customTodo\)\s*\{(.*?)\n            \}", body, flags=re.S)
check(m_a is not None, "抠得出 ③-A 自定义进站广播分支（条件 = customTodo）")
# ★ 条件用 [^)]* 放宽：这样「往条件里塞 customOff」这种改动会被**下面的语义断言**抓住，
#   而不是变成「抠不出这一段」——差别的意义是：断言要指向语义，别指向排版。
m_b = re.search(r"if\s*\((narrateTodo[^)]*)\)\s*\{(.*?)\n            \}", body, flags=re.S)
check(m_b is not None, "抠得出 ③-B 讲述人进站广播分支（条件里必须有 narrateTodo）")
if m_a and m_b:
    a_body = m_a.group(1)
    b_cond, b_body = m_b.group(1), m_b.group(2)
    check("narrateGlobal" not in strip_comments(a_body) and "narrateOn" not in strip_comments(a_body),
          "★ ③-A 里**不出现**讲述人的开关 ⇒ 关讲述人不会掐掉自定义进站广播")
    check("customOff" not in strip_comments(b_cond) and "customOff" not in strip_comments(b_body),
          "★ ③-B 里**不出现** customOff ⇒ 设成不播不会掐掉讲述人（用户报的根因，这条是铁证）")
    check("gain(" in a_body and "play(mc, tone, door, volume" in a_body,
          "③-A 仍是「音频素材 + 音量系数 + 距离增益」那条老链路")
    check("volumeFactor" not in b_body,
          "★ ③-B 不看音量（text2speech 库根本没有音量参数，用户确认「只做开/关」）")
    check("gain(" not in b_body and "volumeFactor" not in b_body,
          "③-B 体内不算距离也不算音量 ⇒ 射程直接吃上面那份 inRange（与自定义那条同一片范围）")
    check("TrainAnnounceNarrator.speak(" in b_body, "③-B 真的去念")
    # ---- ★★【09-28 续 3】香港档的名源 = **终点站**（用户报的「香港预设终点站变成火车站」）----
    #   锚点选在**调用点的实参**上（arrival.destination 当第 2 个实参），而不是选在某个变量名上
    #   —— 变量名（stationName / platformName）改个名就假红，实参语义不会。
    check(re.search(r"TrainAnnounceNarrator\.arriveTextForStyle\(\s*"
                    r"narrateMode,\s*arrival\.destination,\s*arrival\.platformName\s*\)",
                    b_body) is not None,
          "★★ ③-B 只把**终点站**与**站台名**交给选句式入口（3 参）"
          "—— 香港档两档的名源都是终点站 arrival.destination")
    check("stationName" not in re.sub(r"//[^\n]*", "",
                                      b_body[b_body.find("String text ="):
                                             b_body.find("boolean spoke")]),
          "★★ ③-B **拼报站词**那两行里一个字都不提 stationName"
          " ⇒ 车站名不掺进报站词（香港档吃车站名是上一版的错，用户报的 bug 就是它）")
    # stationName 只能在「text 已经拼完」之后才取 = 结构上不可能成为拼句的输入（只为日志）
    _i_text = b_body.find("String text =")
    _i_sn = b_body.find("String stationName =")
    check(_i_text >= 0 and _i_sn > _i_text,
          "★★ ③-B 里 stationName **在拼完 text 之后才取**"
          " ⇒ 它只喂 LOGGER.info（排查用），不参与报站词")
    check("TrainAnnounceNarrator.arriveText(" not in b_body,
          "★ ③-B 不再直接调 arriveText（那样会把香港档也念成上海词）")
    check(re.search(r"text == null \? \"跳过（这一档拼不出话，例如终点站读不到）\"", b_body) is not None,
          "★ 香港档拼不出话（终点站读不到）时 text == null ⇒ 明说是「跳过」，不是「念不出」")
    check(re.search(r"boolean spoke = text != null\s*"
                    r"&& TrainAnnounceNarrator\.speak\(text, runKey, door\.x\(\), door\.y\(\), door\.z\(\)\);",
                    b_body) is not None,
          "★ 空文本不喂给 speak（否则会打一条「念不出」的假诊断）；"
          "★【09-29】speak 还带**声源身份**（runKey + 坐标）—— 越界即掐那条护栏要用它")
    check("state.firedArrivalNarrate = arrivalMs;" in b_body,
          "★ 念不出声也算处理过这一班车（否则每 tick 重试、日志刷屏）—— 记在**讲述人自己**那一格")

# 分段三：记账分开
check(re.search(r"boolean customTodo = customInWindow && "
                r"!sameTrainAs\(state\.firedArrival, arrivalMs\);", body) is not None,
      "★ customTodo 用 firedArrival（自定义那条自己的记账）")
check(re.search(r"boolean narrateTodo = narrateInWindow && "
                r"!sameTrainAs\(state\.firedArrivalNarrate, arrivalMs\);", body) is not None,
      "★ narrateTodo 用 firedArrivalNarrate（讲述人**自己**的记账 —— 两条各记各的）")
check("boolean fired = false;" not in body,
      "★ 没有合并语义的 `fired` 标志了（分段三把两条彻底拆开，不再共用一次记账）")

# ======================================================================
# 2) 时间窗口各自独立（分段三：取消借用）；只有声源位置共用
# ======================================================================
print()
print("===== 2) 窗口 / 去重各自独立，只有声源位置共用 =====")

i_custom_sec = body.find("getDoorPsdArriveSeconds(mc.level, runKey)")
i_narr_sec = body.find("getDoorPsdNarrateSeconds(mc.level, runKey)")
i_a = body.find("if (customTodo) {")
i_b = body.find("if (narrateTodo && narrateInRange && runKey == nearestNarrateRun) {")
check(i_custom_sec != -1 and i_narr_sec != -1 and i_a != -1 and i_b != -1,
      "四个锚点都在",
      "自定义秒数@%d 讲述人秒数@%d ③-A@%d ③-B@%d" % (i_custom_sec, i_narr_sec, i_a, i_b))
check(i_custom_sec < i_a and i_narr_sec < i_b,
      "★ 两个秒数各在自己的分支之前算好")
check(re.search(r"boolean customInWindow\s*=\s*!customOff && !late\s*"
                r"&& remainMs <= \(long\) \(-customThresholdSeconds\) \* 1000L;", body) is not None,
      "★ 自定义那条的窗口 = 它自己的 getDoorPsdArriveSeconds（(-∞,0]，口径不变）")
check(re.search(r"boolean narrateInWindow\s*=\s*narrateOn && !late\s*"
                r"&& remainMs <= \(long\) \(-narrateThresholdSeconds\) \* 1000L;", body) is not None,
      "★ 讲述人那条的窗口 = **它自己**的 getDoorPsdNarrateSeconds（用户点名「取消借用」）")
check("long thresholdMs" not in body,
      "★ 老的「一个 thresholdMs 两条共用」已经删掉（那正是「借用」的写法）")
check(body.count("Arrive state = arriveVoice.computeIfAbsent(runKey") == 1,
      "去重状态表只取一次（表本身仍按串锚点）")
check(body.count("double distance = player == null") == 1,
      "声源距离只算一次（两条共用 —— 本来就只有一个声源）")
check(body.count("boolean narrateInRange = !TrainAnnounceSwitch.isOutsideNarrateRange(") == 1,
      "★★ 讲述人的可闻范围**只算一次** = 它**自己**的 /jsr round AAA BBB"
      "（【09-29】不再借用 /pbmarriveround 那份）；讲述人没有音量项 ⇒ 不吃自定义那条的音量")
check(body.count("gain(distanceXz, distanceY, ROUND_ARRIVE)") == 1,
      "gain(双维距离, ROUND_ARRIVE) **只剩 ③-A 里那一处**（乘音量）"
      " —— ③-B 不再自己算一份增益（它的范围走 narrateInRange 那条 /jsr 判据）")
check(re.search(r"private static boolean sameTrainAs\(long recorded, long arrivalMs\)\s*\{", player)
      is not None,
      "★ 同一班车判定收成一个函数 sameTrainAs（两条各传各的记录，改容差只改一处）")
check(re.search(r"return recorded != Long\.MIN_VALUE && "
                r"Math\.abs\(arrivalMs - recorded\) <= ARRIVE_SAME_TRAIN_MS;", player) is not None,
      "sameTrainAs 的判据 = 记录非空且到站时刻差在容差内")
check(re.search(r"private long firedArrivalNarrate = Long\.MIN_VALUE;", player) is not None,
      "Arrive 里多一格 firedArrivalNarrate（讲述人自己的「这一班念过了」）")

# ======================================================================
# 3) 讲述人走 MTR 报站的入口（不是游戏那个讲述人）
# ======================================================================
print()
print("===== 3) 讲述人 = MTR 报站的同一个入口（text2speech，不查辅助功能设置） =====")

check("import com.mojang.text2speech.Narrator;" in narrator,
      "直接 import com.mojang.text2speech.Narrator（MTR narrateOrAnnounce 用的是它）")
check("Narrator.getNarrator()" in narrator, "取平台讲述人用 Narrator.getNarrator()")
check(re.search(r"n\.say\(text, true\);", narrator) is not None,
      "出声句式 = say(text, true)（true = 先清空再念，与 MTR 写法逐字一致）")
check("GameNarrator" not in narrator and "narrateOrAnnounce" not in narrator,
      "★ 没有退回原版 GameNarrator / 也没有反射调 MTR 自己的报站（两者都要额外的开关）")
check("!n.active()" in narrator,
      "取不到时按 Narrator.EMPTY（active()==false）判「没有引擎」，只打一条诊断")
check(re.search(r"if \(narrator != null\) \{\s*return narrator;", narrator) is not None
      and "if (tried) {" in narrator,
      "★ 讲述人只取一次并缓存（getNarrator() 每次都新建一个 COM 对象）")
check(re.search(r'ARRIVE_OPENING\s*=\s*"乘客们，列车马上就要进站了"', narrator) is not None,
      "报站词开头 = 「乘客们，列车马上就要进站了」（用户点名的句式）")
check(re.search(r'TERMINUS_LEAD\s*=\s*"，本次列车终点站："', narrator) is not None,
      "接「，本次列车终点站：X」（逐字核字面量，不是只看出现过）")
check(re.search(r'PLATFORM_LEAD\s*=\s*"，请乘客们在"', narrator) is not None
      and re.search(r'PLATFORM_TAIL\s*=\s*"站台有序候车"', narrator) is not None,
      "接「，请乘客们在Y站台有序候车」（两截字面量都逐字核）")
check(re.search(r'NO_PLATFORM_TAIL\s*=\s*"，请乘客们有序候车"', narrator) is not None,
      "站台编号读不到 ⇒ 尾句**整句**换成「请乘客们有序候车」（不留缺主语的半截话）")
check(re.search(r"platform\.contains\(\"站台\"\)\s*\?\s*PLATFORM_TAIL_BARE\s*:\s*PLATFORM_TAIL",
                narrator) is not None,
      "★ 站台名自带「站台」二字时走 PLATFORM_TAIL_BARE（否则会念成「1站台站台有序候车」）——"
      "★ 核的是**三元的取向**，不是「这两个常量出现过」")
check("trimToNull" in narrator,
      "空值/空白值统一走 trimToNull 按「读不到」处理（不念半截话）")
check(re.search(r"public static String arriveText\(String destination,\s*"
                r"String platformName\)", narrator) is not None,
      "arriveText(destination, platformName) —— 两个入参各自可缺，缺谁删谁那一小句")
check(re.search(r"String terminus = trimToNull\(destination\);\s*"
                r"if \(terminus != null\)\s*\{\s*"
                r"text\.append\(TERMINUS_LEAD\)\.append\(chineseOnlyName\(terminus\)\);",
                narrator) is not None,
      "终点站读不到就**整句不接**（不是接一个空值）；"
      "★【09-29】接的时候只取**中文名**（chineseOnlyName）—— 双语字段别把英文也念出来")
check(re.search(r"if \(platform == null\)\s*\{\s*text\.append\(NO_PLATFORM_TAIL\);", narrator)
      is not None,
      "站台名读不到走兜底句（两个分支真的分叉，不是「有值没值都接同一句」）")

# ---- 3.2 分段四：「开启(香港)」那一档 ----
print()
print("----- 3.2) 香港档：中英双语两句【10-01 起始终都念】 -------------------")
check(re.search(r'public static String arriveTextHongKong\(String destination\)', narrator) is not None,
      "★★【09-28 续 3】arriveTextHongKong(String destination) —— 入参是**终点站**"
      "（不是车站名、也不是站台名）")
_style = re.search(r"public static String arriveTextForStyle\(([^)]*)\)", narrator)
check(_style is not None and _style.group(1).count(",") == 2,
      "★★ arriveTextForStyle 是 **3 参**（mode, destination, platformName）"
      " —— 两档只差句式不差名源，只有上海档多要一个站台名")
check(re.search(r'public static String arriveTextForStyle\(int mode, String destination,\s*'
                r'String platformName\)', narrator) is not None,
      "★★ arriveTextForStyle(mode, destination, platformName) —— 播放端唯一的选句式入口，"
      "**stationName 这个形参已经拿掉**（它曾是错的来源）")
check(re.search(r"return arriveTextHongKong\(destination\);", narrator) is not None,
      "★★ 香港档的名源**就是终点站**（destination）—— 上海档同一个字段，两档只差句式")
check(re.search(r"arriveTextHongKong\(stationName\)", narrator) is None
      and re.search(r"String name = \(stationName != null", narrator) is None,
      "★★ 香港档**不许**再出现「车站名优先 / 退回站台名」那套（上一版的错法，"
      "把「前往江苏北路」念成了「前往火车站」）")
check(re.search(r"if \(mode == EscalatorSpeedData\.PSD_NARRATE_HONGKONG\)", narrator) is not None
      and re.search(r"return arriveText\(destination, platformName\);", narrator) is not None,
      "★ 分派：香港档 → arriveTextHongKong(终点站)；其余（含上海档）→ arriveText(终点站, 站台名)，"
      "上海那条**一字未动**")
check(re.search(r'HK_CN_LEAD\s*=\s*"前往"', narrator) is not None
      and re.search(r'HK_CN_TAIL\s*=\s*"的列车即将到达，请先让车上的乘客下车"', narrator) is not None,
      "★ 中文句逐字核：「前往」+ XXX +「的列车即将到达，请先让车上的乘客下车」")
check(re.search(r'HK_EN_LEAD\s*=\s*"The train to "', narrator) is not None
      and re.search(r'HK_EN_TAIL\s*=\s*" is arriving, please let passengers exit first\."', narrator)
      is not None,
      "★ 英文句逐字核：「The train to 」+ YYY +「 is arriving, please let passengers exit first.」"
      "（句末句点也在字面量里）")
check(re.search(r'NAME_SPLIT_REGEX\s*=\s*"\\\\\|"', narrator) is not None,
      "★ 站台名按 MTR 自己的约定用 | 拆（正则 \\|，与 RenderPIDS 同款）")
check(re.search(r"raw\.split\(NAME_SPLIT_REGEX\)", narrator) is not None,
      "★ 拆的是站台名原文（split 用上面那个常量，不是各处再写一遍字面量）")
check(re.search(r"if \(isChinese\(piece\)\)\s*\{\s*if \(chinese == null\)\s*\{\s*chinese = piece;",
                narrator) is not None,
      "★ 中文段取**第一段**判为中文的（chinese == null 才写）")
check(re.search(r"\} else if \(foreign == null\)\s*\{\s*foreign = piece;", narrator) is not None,
      "★ 其余段同样只取第一段（英文 / 数字 / 日韩…都落这个桶）")
check(re.search(r"if \(chinese == null && foreign == null\)\s*\{\s*return null;", narrator) is not None,
      "★ 拆完两个桶都空 ⇒ null（站台名读不到 / 全是空白）")
# ★★【10-01 用户点名改规矩】香港档**始终**两句都念：缺段用 raw 兜底，两句**无条件**拼。
check(re.search(r"if \(chinese == null\)\s*\{\s*chinese = foreign;", narrator) is not None,
      "★ 缺中文段用另一段兜底（chinese = foreign —— 用户 10-01 改的规矩）")
check(re.search(r"if \(foreign == null\)\s*\{\s*foreign = chinese;", narrator) is not None,
      "★ 缺英文段用另一段兜底（foreign = chinese）")
check(re.search(r"text\.append\(HK_CN_LEAD\)\.append\(chinese\)\.append\(HK_CN_TAIL\);", narrator) is not None,
      "★ 中文句**无条件**接（不再有「只有英文名 ⇒ 中文句整句不接」的守卫，旧规矩②已废）")
check(re.search(r"text\.append\(HK_EN_LEAD\)\.append\(foreign\)\.append\(HK_EN_TAIL\);", narrator) is not None,
      "★ 英文句**无条件**接（不再有「只有中文名 ⇒ 英文句整句不接」的守卫，旧规矩①已废）")
check(re.search(r"if \(chinese != null\)\s*\{\s*text\.append\(HK_CN_LEAD\)", narrator) is None
      and re.search(r"if \(foreign != null\)\s*\{\s*if \(text\.length\(\) > 0\)", narrator) is None,
      "★ 两句都不许再被「有哪段才念哪段」的守卫包住（防回退到旧规矩①②）")
check(re.search(r'HK_LINE_BREAK\s*=\s*"\\n"', narrator) is not None,
      "两句之间的分隔 = 换行（用户把这两句写成了两行）")
check(re.search(r"private static boolean isChinese\(String text\)\s*\{(.*?)\n    \}", narrator,
                flags=re.S) is not None,
      "判「这一段是不是中文」收成一个函数 isChinese")
m_ic = re.search(r"private static boolean isChinese\(String text\)\s*\{(.*?)\n    \}", narrator,
                 flags=re.S)
if m_ic:
    icb = m_ic.group(1)
    check("isKana(codePoint) || isHangul(codePoint)" in icb,
          "★ 含假名 / 谚文 ⇒ 直接判**不是**中文段（把日文、韩文挡在外面，规矩③）")
    check("if (isHan(codePoint))" in icb and "return hasHan;" in icb,
          "★ 其余看有没有汉字（有汉字且无假名谚文 ⇒ 中文段）")
check(re.search(r"private static boolean isHan\(int codePoint\)", narrator) is not None
      and re.search(r"private static boolean isKana\(int codePoint\)", narrator) is not None
      and re.search(r"private static boolean isHangul\(int codePoint\)", narrator) is not None,
      "三个码位判据（汉字 / 假名 / 谚文）都在")
m_han = re.search(r"private static boolean isHan\(int codePoint\)\s*\{(.*?)\n    \}", narrator, flags=re.S)
if m_han:
    hb = m_han.group(1)
    check("0x4E00" in hb and "0x9FFF" in hb,
          "★ isHan 覆盖 CJK 统一表意文字主区（0x4E00-0x9FFF）")
    check("0x3400" in hb and "0x4DBF" in hb,
          "★ 也覆盖扩展 A（0x3400-0x4DBF，生僻字站名）+ 兼容区")
m_kana = re.search(r"private static boolean isKana\(int codePoint\)\s*\{(.*?)\n    \}", narrator,
                   flags=re.S)
if m_kana:
    kb = m_kana.group(1)
    check("0x3040" in kb and "0x30FF" in kb,
          "★ 假名覆盖平假名 0x3040-0x309F 与片假名 0x30A0-0x30FF")
m_hang = re.search(r"private static boolean isHangul\(int codePoint\)\s*\{(.*?)\n    \}", narrator,
                   flags=re.S)
if m_hang:
    gb = m_hang.group(1)
    check("0xAC00" in gb and "0xD7AF" in gb and "0x1100" in gb,
          "★ 谚文覆盖音节 0xAC00-0xD7AF 与字母 0x1100-0x11FF")
check("import smooth.lift.EscalatorSpeedData;" in narrator,
      "TrainAnnounceNarrator 引了 EscalatorSpeedData（分派要用 PSD_NARRATE_HONGKONG）")

check(re.search(r"public static boolean isEnabled\(\)", switch) is not None,
      "TrainAnnounceSwitch 仍是讲述人的**全局**总闸之一（配置项 /jsr 不变）")
check("smoothlift-jsr.properties" in switch,
      "1.28 写的配置文件沿用（键 trainArriveAnnounce 不变）")

# ======================================================================
# 4) 报站词要念的两个值：终点站 / 站台编号（都从 ArrivalResponse 读）
# ======================================================================
print()
print("===== 4) 终点站 / 站台编号从 MTR 的 ArrivalResponse 里读 =====")

# ---- 4.1 绑定：getArrival 必需；getDestination / getPlatformName 可选 ----
m_bind = re.search(r"private static boolean bindArrivals\(\)\s*\{(.*?)\n    \}", dwell, flags=re.S)
check(m_bind is not None, "抠得出 bindArrivals()（到达缓存那一层的绑定）")
if m_bind:
    bb = m_bind.group(1)
    check('response.getMethod("getArrival")' in bb,
          "getArrival 仍是**必需**绑定（拿不到它就整层不可用）")
    check('arrivalGetDestination = method(response, "getDestination")' in bb
          and 'arrivalGetPlatformName = method(response, "getPlatformName")' in bb,
          "★ 终点站 / 站台编号用 method(...) 取 ⇒ **可选**绑定：缺一个只少一句台词，"
          "不把「剩几秒到站」那条主链路一起拖挂")
    check("arrivalGetDestination = null;" in bb and "arrivalGetPlatformName = null;" in bb,
          "两个可选绑定在函数入口先清零（重绑定不留旧句柄）")

# ---- 4.2 取值：三条信息必须来自**同一条** ArrivalResponse ----
m_na = re.search(r"public static ArrivalInfo nearestArrival\(long platformId\)\s*\{(.*?)\n    \}",
                 dwell, flags=re.S)
check(m_na is not None, "MtrDwellAccess.nearestArrival(long) 存在且是 public")
if m_na:
    nb = m_na.group(1)
    # ★ 核的是「这两行在**选中那一班车**的分支里」，不是「这两行出现过」——
    #   把它们挪到 if 外面（= 永远取最后一条响应）是这类改动最隐蔽的写法，必须能抓住。
    m_sel = re.search(r"if \(best == Long\.MIN_VALUE \|\| remaining < best\)\s*\{([^}]*)\}", nb)
    check(m_sel is not None and
          "bestDestination = textOf(response, arrivalGetDestination);" in m_sel.group(1) and
          "bestPlatform = textOf(response, arrivalGetPlatformName);" in m_sel.group(1),
          "★ 终点站/站台名都在**同一个 if (remaining < best)** 块里取 ⇒ 不会拼出"
          "「A 车的终点站 + B 车的站台」（两条车几乎同时到时才会暴露的那个错）")
    check(re.search(r"if \(best == Long\.MIN_VALUE \|\| remaining < best\)\s*\{", nb) is not None,
          "「最近的一班」判据没变（仍是 remaining 最小 —— 时刻表那条语义不许被改写）")
    check(re.search(r"return best == Long\.MIN_VALUE \? null\s*:\s*"
                    r"new ArrivalInfo\(best, bestDestination, bestPlatform\);", nb) is not None,
          "三件事一起回给调用方（时间 + 终点站 + 站台名）")
    check("return null;" in nb, "读不到一律 return null（调用方静默跳过，不刷日志）")

m_ai = re.search(r"public static final class ArrivalInfo\s*\{(.*?)\n    \}", dwell, flags=re.S)
check(m_ai is not None, "ArrivalInfo 是 MtrDwellAccess 的公开嵌套类型")
if m_ai:
    ab = m_ai.group(1)
    check("public final long remainingMs;" in ab and "public final String destination;" in ab
          and "public final String platformName;" in ab,
          "ArrivalInfo = 剩余毫秒 + 终点站 + 站台编号")

# ---- 4.3 站名（报站日志里点名「本车站」用）仍在 ----
m_sn = re.search(r"public static String stationNameForPlatform\(long platformId\)\s*\{(.*?)\n    \}",
                 dwell, flags=re.S)
check(m_sn is not None, "MtrDwellAccess.stationNameForPlatform(long) 仍在（日志里点名「本车站」）")
if m_sn:
    sb = m_sn.group(1)
    check("platformsField.get(instance)" in sb and "getIdMethod.invoke(platform)" in sb,
          "按 platformId 在 MinecraftClientData.platforms 里找那条 Platform")
    check("stationNameOf(platform)" in sb, "找到后走 stationNameOf（area → Station.getName）")
    check("return null;" in sb, "读不到一律 return null（日志里就写「读不到」）")

# ======================================================================
# 5) 分段三：数据层 / 同步层 / 管理器（每串门自己的开关 + 秒数）
# ======================================================================
print()
print("===== 5) 数据层 / 同步层 / 管理器：每串门的开关 + 独立秒数 =====")

check(re.search(r"Integer narrate, Integer narrateSeconds\)\s*\{", data) is not None,
      "★ PsdToneAudio 多两格：**Integer** narrate（三档样式）+ Integer narrateSeconds"
      "（末尾追加，不动老字段序）")
check(re.search(r"public PsdToneAudio withNarrate\(Integer v\)", data) is not None
      and re.search(r"public PsdToneAudio withNarrateSeconds\(Integer v\)", data) is not None,
      "withNarrate / withNarrateSeconds 两个 copy-on-write 都在（前者已从 Boolean 改成 Integer）")
check(re.search(r"&& narrate == null && narrateSeconds == null;", data) is not None,
      "★ isEmpty() 也把这两格算进去（否则「只设了讲述人」的记录会被当空记录删掉）")
check(re.search(r"optNarrateMode\(entry, \"narrate\"\), optInt\(entry, \"narrateSeconds\"\)\)", data)
      is not None, "存档读：narrate（走 optNarrateMode，读出先夹三档）/ narrateSeconds")
_m_on = re.search(r"private static Integer optNarrateMode\(CompoundTag tag, String key\)\s*\{(.*?)\n    \}",
                  data, flags=re.S)
check(_m_on is not None, "★ optNarrateMode 仍在（每串门讲述人样式的可选读入口）")
if _m_on:
    _ob = _m_on.group(1)
    check(re.search(r"if \(!tag\.contains\(key\)\)\s*\{\s*return null;", _ob) is not None,
          "★ optNarrateMode：没这个键 ⇒ null（跟维度默认）")
    check(re.search(r"tag\.contains\(key, 1\)", _ob) is not None
          and "PSD_NARRATE_SHANGHAI" in _ob and "PSD_NARRATE_OFF" in _ob,
          "★★ 旧档布尔兼容：NBT byte（1.28.1204 存的 boolean）⇒ true=开启(上海)/false=关闭；"
          "不能直接 getInt（类型不符会静默返回 0 = 把「开启」读成「关闭」）")
    check(re.search(r"clampPsdNarrateMode\(tag\.getInt\(key\)\)", _ob) is not None,
          "★ 新档 int ⇒ 先夹一次三档（手改过的存档也能收住）")
check(re.search(r'putOptInt\(t, "narrate", v\.narrate\(\)\);', data) is not None
      and re.search(r'putOptInt\(t, "narrateSeconds", v\.narrateSeconds\(\)\);', data) is not None,
      "存档写：narrate / narrateSeconds 两键（都走 putOptInt，读侧同名同型）")
check(re.search(r"public int defaultPsdNarrateMode = DEFAULT_PSD_NARRATE_MODE;", data) is not None
      and re.search(r"public int defaultPsdNarrateSeconds = DEFAULT_PSD_NARRATE_LEAD_SECONDS;", data)
      is not None, "维度默认两格（defaultPsdNarrateMode（int 三档）/ defaultPsdNarrateSeconds（【10-01】起默认 -20））")
check(re.search(r'if \(tag\.contains\("defaultPsdNarrateMode"\)\)\s*\{\s*data\.defaultPsdNarrateMode\s*='
                r'\s*clampPsdNarrateMode\(tag\.getInt\("defaultPsdNarrateMode"\)\);', data) is not None
      and re.search(r'tag\.putInt\("defaultPsdNarrateMode", defaultPsdNarrateMode\);', data) is not None,
      "★ 维度默认样式：存档读写同名同型（新键 defaultPsdNarrateMode，旧档读不到就落默认=上海）")
check(re.search(r"public static int clampPsdNarrateSeconds\(int seconds\)\s*\{\s*"
                r"return Math\.min\(PSD_NARRATE_SECONDS_MAX, seconds\);", data) is not None,
      "★ clampPsdNarrateSeconds 与 clampPsdArriveSeconds 同款（上界 0，即 (-∞,0]）")
check(re.search(r"public static final int PSD_NARRATE_SECONDS_MAX = 0;", data) is not None,
      "PSD_NARRATE_SECONDS_MAX = 0（不许晚于到站那一刻）")

# ---- 三档样式的常量 + 夹取 + 中文名 ----
check(re.search(r"public static final int PSD_NARRATE_OFF = 0;", data) is not None
      and re.search(r"public static final int PSD_NARRATE_SHANGHAI = 1;", data) is not None
      and re.search(r"public static final int PSD_NARRATE_HONGKONG = 2;", data) is not None,
      "★ 三档样式常量 0 关闭 / 1 开启(上海) / 2 开启(香港)（一个 int 存，互斥）")
check(re.search(r"public static final int PSD_NARRATE_MODE_MAX = PSD_NARRATE_HONGKONG;", data)
      is not None,
      "★ 上界写成一个常量（加新样式时只改这一处，不散在 clamp 里）")
check(re.search(r"public static final int DEFAULT_PSD_NARRATE_MODE = PSD_NARRATE_HONGKONG;", data)
      is not None,
      "★ 默认档 = 开启(香港)（【10-01】用户点名：第一次加载模组＝香港预设；旧值上海）")
check(re.search(r"public static int clampPsdNarrateMode\(int mode\)\s*\{\s*"
                r"return Math\.max\(PSD_NARRATE_OFF, Math\.min\(PSD_NARRATE_MODE_MAX, mode\)\);", data)
      is not None,
      "★ clampPsdNarrateMode：两头都夹（0..2）")
check(re.search(r"public static String psdNarrateModeName\(int mode\)\s*\{", data) is not None
      and re.search(r'return "关闭";', data) is not None
      and re.search(r'return "开启\(香港\)";', data) is not None
      and re.search(r'return "开启\(上海\)";', data) is not None,
      "★ psdNarrateModeName 一处定义三个中文名（日志 / 状态行 / 指令回执共用）")
check("DEFAULT_PSD_NARRATE_ON" not in data,
      "★ 旧的布尔常量 DEFAULT_PSD_NARRATE_ON 已经**从代码里消失**（只允许留在注释里）")

# 管理器：门覆盖 > 维度默认
check(re.search(r"public static int getDoorPsdNarrateMode\(Level level, long key\)\s*\{(.*?)\n    \}",
                mgr, flags=re.S) is not None
      and re.search(r"public static int getDoorPsdNarrateSeconds\(Level level, long key\)\s*\{(.*?)\n    \}",
                    mgr, flags=re.S) is not None,
      "getDoorPsdNarrateMode / getDoorPsdNarrateSeconds 两个「门覆盖 > 维度默认」读数口都在")
m_gg = re.search(r"public static int getDoorPsdNarrateMode\(Level level, long key\)\s*\{(.*?)\n    \}",
                 mgr, flags=re.S)
if m_gg:
    check("psdDoorRecord(level, key).narrate()" in m_gg.group(1)
          and "getPsdNarrateMode(level)" in m_gg.group(1),
          "★ 样式走「门的覆盖值非 null 就用它，否则回落维度默认」")
check(re.search(r"public static boolean isDoorPsdNarrateOn\(Level level, long key\)\s*\{\s*"
                r"return getDoorPsdNarrateMode\(level, key\) != EscalatorSpeedData\.PSD_NARRATE_OFF;",
                mgr) is not None,
      "★ isDoorPsdNarrateOn 由样式派生（!= 关闭）—— 只保留给「只看开/关」的地方，"
      "播放端必须用 getDoorPsdNarrateMode")
m_gs = re.search(r"public static int getDoorPsdNarrateSeconds\(Level level, long key\)\s*\{(.*?)\n    \}",
                 mgr, flags=re.S)
if m_gs:
    check("psdDoorRecord(level, key).narrateSeconds()" in m_gs.group(1)
          and "getPsdNarrateSeconds(level)" in m_gs.group(1),
          "★ 秒数同款两层（与样式各判各的）")
check(re.search(r"public static void setDoorPsdNarrate\(ServerLevel level, long key, int mode\)\s*\{",
                mgr) is not None,
      "setDoorPsdNarrate(level, key, **int mode**) —— 写入口从 boolean 改成三档 int")
check(re.search(r"updateDoor\(level, key, t -> t\.withNarrate\(EscalatorSpeedData\.clampPsdNarrateMode\(mode\)\)\);",
                mgr) is not None,
      "★ 写之前先夹一次（与其它项的写入口同款）")
check(re.search(r"public static void setDoorPsdNarrateSeconds\(ServerLevel level, long key, int seconds\)\s*\{",
                mgr) is not None,
      "setDoorPsdNarrateSeconds 仍在（都走 updateDoor）")
check(re.search(r"public static int getPsdNarrateMode\(Level level\)", mgr) is not None,
      "维度默认样式的读数口 getPsdNarrateMode(Level)（客户端读镜像）")
check(re.search(r"public static boolean isPsdNarrateOn\(Level level\)\s*\{\s*"
                r"return getPsdNarrateMode\(level\) != EscalatorSpeedData\.PSD_NARRATE_OFF;", mgr)
      is not None,
      "★ 维度那层同样由样式派生（一处口径，两个层级同构）")
check(re.search(r"public static void setDefaultPsdNarrate\(ServerLevel level, int mode, int seconds\)",
                mgr) is not None
      and re.search(r"public static int setDefaultPsdNarrateAll\(MinecraftServer server, int mode, int seconds\)",
                    mgr) is not None,
      "维度默认的 setDefaultPsdNarrate（同步所有）/ setDefaultPsdNarrateAll（强制同步）都在（都吃 int mode）")
m_all = re.search(r"public static int setDefaultPsdNarrateAll\(MinecraftServer server, int mode, int seconds\)"
                  r"\s*\{(.*?)\n    \}", mgr, flags=re.S)
if m_all:
    check("int clamped = EscalatorSpeedData.clampPsdNarrateMode(mode);" in m_all.group(1)
          and "data.defaultPsdNarrateMode = clamped;" in m_all.group(1),
          "★ 强制同步先夹一次再落到每个维度（夹在循环外，只算一次）")
check(re.search(r"remapPsdDoorOverrides\(data, t -> \(t\.narrate\(\) == null && "
                r"t\.narrateSeconds\(\) == null\)", mgr) is not None,
      "★ 强制同步时把按串覆盖一起抹回跟维度默认（-f 口径，与其它项同构）")

# 同步包写序 / 读序
m_tp = re.search(r"private static FriendlyByteBuf buildPsdTonePacket\(ServerLevel level\)\s*\{(.*?)\n    \}",
                 mgr, flags=re.S)
check(m_tp is not None, "抠得出 buildPsdTonePacket")
if m_tp:
    tp = m_tp.group(1)
    check("writeDoorOptInt(buf, tone.narrate());" in tp
          and "writeDoorOptInt(buf, tone.narrateSeconds());" in tp,
          "★ 按门包末尾两格都走 writeDoorOptInt（样式那一格由 OptBool 改成 OptInt）")
    check(tp.find("tone.arriveVolume()") < tp.find("tone.narrate()"),
          "★ 追加在 arriveVolume **之后**（末尾追加 ⇒ 老客户端读到这里正好读完，不会错位）")
check("Integer narrate = EscalatorSpeedManager.readDoorOptInt(buf);" in smoothclient
      and "Integer narrateSeconds = EscalatorSpeedManager.readDoorOptInt(buf);" in smoothclient,
      "★ 按门包读侧也读 narrate / narrateSeconds，**都走 readDoorOptInt**（读序与写序一致）")
check("Integer narrate = EscalatorSpeedManager.readDoorOptBool(buf);" not in smoothclient,
      "★ 读侧没有残留的 readDoorOptBool 那一格（类型错了会静默读歪）")
check("narrateMode, narrateSeconds);" in smoothclient,
      "维度包读侧把 narrateMode / narrateSeconds 传给 applyClientPsdChime")

m_cp = re.search(r"private static FriendlyByteBuf buildPsdChimePacket\(ServerLevel level\)\s*\{(.*?)\n    \}",
                 mgr, flags=re.S)
check(m_cp is not None, "抠得出 buildPsdChimePacket")
if m_cp:
    cp = m_cp.group(1)
    check("buf.writeVarInt(data.defaultPsdNarrateMode);" in cp
          and "buf.writeVarInt(data.defaultPsdNarrateSeconds);" in cp,
          "★ 维度包末尾两格：讲述人**样式**（VarInt）+ 秒数（格子数不变，第 1 格由 Boolean 改 VarInt）")
    check("buf.writeBoolean(data.defaultPsdNarrateOn);" not in cp,
          "★ 写侧也没有残留的 writeBoolean(defaultPsdNarrateOn)")
    check(cp.find("defaultPsdArriveRound") < cp.find("defaultPsdNarrateMode"),
          "★ 追加在 arriveRound **之后**（末尾追加，读侧同序）")
check("int narrateMode = buf.readVarInt();" in smoothclient,
      "★ 维度包读侧第 1 格用 readVarInt（与写侧 writeVarInt 成对）")
check("boolean narrateOn = buf.readBoolean();" not in smoothclient,
      "★ 读侧没有残留的 readBoolean 那一格")
check(re.search(r"int narrateMode, int narrateSeconds\)", mgr) is not None,
      "applyClientPsdChime 末尾两个入参（写序 = 入参序）")
check(re.search(r"data\.psdNarrateMode = EscalatorSpeedData\.clampPsdNarrateMode\(narrateMode\);", mgr)
      is not None
      and re.search(r"data\.psdNarrateSeconds = "
                    r"EscalatorSpeedData\.clampPsdNarrateSeconds\(narrateSeconds\);", mgr) is not None,
      "applyClientPsdChime 体内落地这两格（样式先夹）")
check(re.search(r"public int psdNarrateMode = EscalatorSpeedData\.DEFAULT_PSD_NARRATE_MODE;", mgr)
      is not None, "ClientDimensionData 多两格镜像（psdNarrateMode（int）/ psdNarrateSeconds）")

# 指令通道 + 同步页
check(re.search(r'new ResourceLocation\("smoothlift", "set_psd_narrate"\)', sl) is not None
      and re.search(r'new ResourceLocation\("smoothlift", "set_psd_narrate_lead"\)', sl) is not None,
      "两条新通道：SET_PSD_NARRATE_CHANNEL（开关）/ SET_PSD_NARRATE_LEAD_CHANNEL（秒数）")
check(re.search(r"static final int SYNC_PSD_NARRATE_PAGE = 5;", sl) is not None,
      "★ 同步页编号 SYNC_PSD_NARRATE_PAGE = 5")
check(re.search(r"if \(scope == SYNC_PSD_NARRATE_PAGE\)\s*\{(.*?)\n        \}", sl, flags=re.S)
      is not None, "syncPsd 里有 scope==5 的分支（右上角「同步所有」射程覆盖第 5 页）")
m_nb = re.search(r"if \(scope == SYNC_PSD_NARRATE_PAGE\)\s*\{(.*?)\n        \}", sl, flags=re.S)
if m_nb:
    nbb = m_nb.group(1)
    check("getDoorPsdNarrateMode(level, key)" in nbb and "getDoorPsdNarrateSeconds(level, key)" in nbb,
          "该分支读「这一串门生效的**样式** + 秒数」再写默认（同步按钮也搬三档，不是只搬开关）")
    check("setDefaultPsdNarrateAll(server, mode, seconds)" in nbb
          and "setDefaultPsdNarrate(level, mode, seconds)" in nbb,
          "force / 非 force 各走 *All / 单维度（与其它项同构）")
    check("EscalatorSpeedData.psdNarrateModeName(mode)" in nbb,
          "★ 回执文案用共用的 psdNarrateModeName（会显示「开启(香港)」而不是笼统的「开启」）")
check(re.search(r'ServerPlayNetworking\.registerGlobalReceiver\(SET_PSD_NARRATE_CHANNEL,', sl)
      is not None
      and re.search(r'ServerPlayNetworking\.registerGlobalReceiver\(SET_PSD_NARRATE_LEAD_CHANNEL,', sl)
      is not None,
      "两个服务端接收器都注册了")

# ======================================================================
# 6) 分段三：石斧 UI（主界面一行 + 二级页）
# ======================================================================
print()
print("===== 6) 石斧 UI：主界面「进站广播(讲述人)」一行 + 二级页 =====")

check(re.search(r'Component\.literal\("进站广播\(讲述人\)"\)', screen) is not None,
      "★ 主界面按钮文案 = 「进站广播(讲述人)」（用户点名）")
check(re.search(r"int narrateY = LIST_TOP \+ 10 \+ ROW_H \* 4;", screen) is not None,
      "★ 那一行落在 ROW_H*4 这一格（进站广播 ROW_H*3 的**下一行**，用户点名「放在下面」）")
check(re.search(r"narrateLeadInput = new EditBox\(", screen) is not None,
      "主界面那一行有一个秒数框 narrateLeadInput")
check(re.search(r"getDoorPsdNarrateSeconds\(mcLevel\(\), runKey\)", screen) is not None,
      "秒数框初值 = 这一串门生效的讲述人秒数")
check(re.search(r'if \(page == 5\) \{\s*buildNarratePage\(\);', screen) is not None,
      "★ buildUi 里 page==5 走 buildNarratePage（不是落到 buildTonePage 的 else）")
check(re.search(r'if \(page == 5\) \{\s*renderNarratePage\(guiGraphics\);', screen) is not None,
      "★ render 里 page==5 走 renderNarratePage")
check(re.search(r"private void buildNarratePage\(\)\s*\{", screen) is not None
      and re.search(r"private void renderNarratePage\(GuiGraphics guiGraphics\)\s*\{", screen) is not None,
      "buildNarratePage / renderNarratePage 都在")

m_np = re.search(r"private void buildNarratePage\(\)\s*\{(.*?)\n    \}", screen, flags=re.S)
check(m_np is not None, "抠得出 buildNarratePage 方法体")
if m_np:
    npb = m_np.group(1)
    check(re.search(r'String\[\] names = \{"关闭", "开启\(香港\)", "开启\(上海\)"\};', npb) is not None,
          "★★ 右列三行自上而下 = 关闭 / 开启(香港) / 开启(上海)"
          "（用户点名「香港按钮放在上海上面」；原来那个「开启」已改名「开启(上海)」）")
    check(re.search(r"int\[\] modes = \{EscalatorSpeedData\.PSD_NARRATE_OFF,\s*"
                    r"EscalatorSpeedData\.PSD_NARRATE_HONGKONG,\s*"
                    r"EscalatorSpeedData\.PSD_NARRATE_SHANGHAI\};", npb) is not None,
          "★★ 调显示次序**只许动这两个数组的次序**；三档常量仍是 0/1/2"
          "（那是**存档格式**，与页面顺序无关，不许动）")
    # ★ 反向对照：names 与 modes 必须**同序同长** —— 只换一个数组会让点「香港」去设「上海」档
    m_names = re.search(r'String\[\] names = \{([^}]*)\};', npb)
    m_modes = re.search(r'int\[\] modes = \{([^}]*)\};', npb)
    check(m_names is not None and m_modes is not None
          and m_names.group(1).index("香港") < m_names.group(1).index("上海")
          and m_modes.group(1).index("HONGKONG") < m_modes.group(1).index("SHANGHAI")
          and m_names.group(1).count(",") == m_modes.group(1).count(","),
          "★★ names/modes **同序同长**，且「香港」都排在「上海」之前"
          "（只换一个数组 = 点香港却设成上海，是最容易犯的错）")
    check("Component.literal((mode == target ? \"✓\" : \"\") + names[row])" in npb,
          "★ ✓ 标在**当前生效的那一档**上（三行互斥，只有一个对）")
    check("pickNarrate(target)" in npb,
          "★ 点名字与点「选择」都走同一个 pickNarrate(target)")
    check(npb.count('Component.literal("选择")') == 1 and npb.count("names.length") >= 1,
          "★ 每一行只有一个「选择」按钮（用户点名），行数由 names.length 决定")
    check('"删除"' not in npb and '"选用"' not in npb,
          "★ 没有「删除」也没有「选用」（不是照抄到站/进站页的三按钮版式）")
    check("pending" not in npb and "stored" not in npb and "importPending" not in npb,
          "★ 左列不列任何待导入 / 已导入（用户点名「左侧列表空着就行，因为不用导入什么」）")
    check("getDoorPsdNarrateMode(mcLevel(), runKey)" in npb,
          "★ 当前档取自 getDoorPsdNarrateMode（不是 isDoorPsdNarrateOn —— 那样标不出上海/香港）")

m_rp = re.search(r"private void renderNarratePage\(GuiGraphics guiGraphics\)\s*\{(.*?)\n    \}",
                 screen, flags=re.S)
if m_rp:
    rpb = m_rp.group(1)
    check(re.search(r'"进站广播\(讲述人\)"', rpb) is not None, "页标题 = 「进站广播(讲述人)」")
    check("未导入存档" not in rpb and "已导入存档" not in rpb,
          "★ 不画「未导入 / 已导入」表头（没有列表这回事）")
    check("psdNarrateModeName" not in rpb,
          "★【1.30】讲述人页底部状态行已按点名删（psdNarrateModeName 不再画在 renderNarratePage；"
          "三档中文名只留在指令回执/日志里）")

check(re.search(r"private void sendSetNarrateLead\(int lead\)", screen) is not None
      and re.search(r"private void sendSetNarrate\(int mode\)", screen) is not None,
      "sendSetNarrateLead / sendSetNarrate 两个发包口都在（后者已改成 int mode）")
check(re.search(r"private void sendSetNarrate\(boolean on\)", screen) is None,
      "★ 没有残留的 sendSetNarrate(boolean)")
m_sn = re.search(r"private void sendSetNarrate\(int mode\)\s*\{(.*?)\n    \}", screen, flags=re.S)
if m_sn:
    snb = m_sn.group(1)
    check("buf.writeVarInt(mode);" in snb,
          "★ 发出去那一格是 writeVarInt（与 SmoothLift 接收端的 readVarInt 成对）")
    check("buf.writeBoolean(on)" not in snb,
          "★ 发送侧没有残留的 writeBoolean（那一格已是三档 int）")
    check("EscalatorSpeedData.psdNarrateModeName(mode)" in snb,
          "状态提示用共用的中文名")
check(re.search(r"t -> t\.withNarrateSeconds\(EscalatorSpeedData\.clampPsdNarrateSeconds\(lead\)\)",
                screen) is not None,
      "★ 发秒数后本地回显走 withNarrateSeconds（不回显就要等一个来回才响）")
check(re.search(r"t -> t\.withNarrate\(EscalatorSpeedData\.clampPsdNarrateMode\(mode\)\)", screen)
      is not None,
      "★ 发样式后本地回显走 withNarrate(夹过的 mode)")
check(re.search(r"lead != EscalatorSpeedManager\.getDoorPsdNarrateSeconds\(mcLevel\(\), runKey\)",
                screen) is not None,
      "★ applyMainInputs 里秒数没改动就不发包（与进站报站同一口径）")

# ======================================================================
# 7) 字节码
# ======================================================================
print()
print("===== 7) 字节码 =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        nar = z.read("smooth/lift/client/TrainAnnounceNarrator.class")
        pl = z.read("smooth/lift/client/PsdChimePlayer.class")
        dw = z.read("smooth/lift/client/MtrDwellAccess.class")
        sc = z.read("smooth/lift/client/PsdToneSetupScreen.class")
        manager = z.read("smooth/lift/EscalatorSpeedManager.class")
        main = z.read("smooth/lift/SmoothLift.class")
        dt = z.read("smooth/lift/EscalatorSpeedData.class")
        check(b"com/mojang/text2speech/Narrator" in nar,
              "TrainAnnounceNarrator.class 里真的引用了 text2speech/Narrator")
        check(b"say" in nar and b"getNarrator" in nar, "say / getNarrator 都在常量池里")
        check(b"TrainAnnounceNarrator" in pl and b"TrainAnnounceSwitch" in pl,
              "PsdChimePlayer.class 同时引用讲述人实现与开关（两条链路都编进去了）")
        check(b"stationNameForPlatform" in dw,
              "MtrDwellAccess.class 里编进了 stationNameForPlatform")
        check(b"getDestination" in dw and b"getPlatformName" in dw,
              "★ MtrDwellAccess.class 里引用了 getDestination / getPlatformName"
              "（终点站与站台编号，与 MTR 的 RenderPIDS 同一对字段）")
        check(b"nearestArrival" in dw and b"ArrivalInfo" in dw,
              "MtrDwellAccess.class 里编进了 nearestArrival / ArrivalInfo")
        check(b"nearestArrival" in pl, "PsdChimePlayer.class 走 nearestArrival 拿时刻表信息")
        check(b"getDoorPsdNarrateMode" in pl and b"getDoorPsdNarrateSeconds" in pl,
              "★ PsdChimePlayer.class 引用了 getDoorPsdNarrateMode / getDoorPsdNarrateSeconds"
              "（样式与窗口真的接上了 —— 取的是**样式**，不是开/关）")
        check(b"arriveTextForStyle" in pl and b"arriveTextForStyle" in nar,
              "★ 选句式入口 arriveTextForStyle 编进了玩家与讲述人两个类")
        check(b"arriveTextHongKong" in nar,
              "★ 香港档的拼句方法 arriveTextHongKong 编进了 TrainAnnounceNarrator")
        check(b"firedArrivalNarrate" in pl,
              "★ PsdChimePlayer.class 里编进了 firedArrivalNarrate（讲述人自己的记账）")
        check(b"isDoorPsdNarrateOn" in manager and b"getDoorPsdNarrateMode" in manager
              and b"getDoorPsdNarrateSeconds" in manager
              and b"setDoorPsdNarrate" in manager and b"setDefaultPsdNarrateAll" in manager,
              "EscalatorSpeedManager.class 里编进了讲述人的读写口与同步口")
        check(b"psdNarrateModeName" in manager or b"psdNarrateModeName" in dt,
              "★ 三档中文名 psdNarrateModeName 编进了数据层（一处定义的落点）")
        check(b"narrate" in manager and b"narrateSeconds" in manager,
              "★ 两个新字段名真的编进了管理器（同步包读写靠它）")
        check(b"set_psd_narrate" in main and b"set_psd_narrate_lead" in main,
              "SmoothLift.class 里编进了两条新通道名")
        check(b"narrate" in dt and b"narrateSeconds" in dt,
              "EscalatorSpeedData.class 里编进了 narrate / narrateSeconds")
        check(b"buildNarratePage" in sc and b"renderNarratePage" in sc,
              "PsdToneSetupScreen.class 里编进了讲述人页的 build / render")
        check(b"sendSetNarrate" in sc and b"sendSetNarrateLead" in sc,
              "PsdToneSetupScreen.class 里编进了两个发包口")
        # ★ static final 常量会被内联（1.23 的坑），所以不拿常量的**值**做断言，
        #   只查它在常量池里的名字。
        check(b"ARRIVE_OPENING" in nar, "ARRIVE_OPENING 字段名在（值被内联是常态）")

# ======================================================================
# 8) 纯逻辑搬 Python：两档报站词的拼装（照抄 Java，逐字对比）
# ======================================================================
print()
print("===== 8) 纯逻辑搬 Python：arriveText（上海）/ arriveTextHongKong（香港） =====")

# ---- 8.1 先核字面量：Java 侧必须逐字就是这几句（否则下面的用例是「自己考自己」） ----
raw_narrator = load(NARRATOR)  # 这一节要**带注释**读：常量定义在 Javadoc 后面也能核
LITERALS = {
    "ARRIVE_OPENING": "乘客们，列车马上就要进站了",
    "TERMINUS_LEAD": "，本次列车终点站：",
    "PLATFORM_LEAD": "，请乘客们在",
    "PLATFORM_TAIL": "站台有序候车",
    "PLATFORM_TAIL_BARE": "有序候车",
    "NO_PLATFORM_TAIL": "，请乘客们有序候车",
    "HK_CN_LEAD": "前往",
    "HK_CN_TAIL": "的列车即将到达，请先让车上的乘客下车",
    "HK_EN_LEAD": "The train to ",
    "HK_EN_TAIL": " is arriving, please let passengers exit first.",
}
for name, value in LITERALS.items():
    check(('%s = "%s"' % (name, value)) in raw_narrator,
          "%s 与 Java 侧逐字一致：%r" % (name, value))


# ---- 8.2 照抄一遍 Java 的判据（码位判据 + 拼句） ----
def j_is_han(cp):
    return (0x3400 <= cp <= 0x4DBF) or (0x4E00 <= cp <= 0x9FFF) or (0xF900 <= cp <= 0xFAFF) \
        or cp in (0x3005, 0x3007)


def j_is_kana(cp):
    return (0x3040 <= cp <= 0x30FF) or (0x31F0 <= cp <= 0x31FF) or (0xFF66 <= cp <= 0xFF9D)


def j_is_hangul(cp):
    return (0x1100 <= cp <= 0x11FF) or (0x3130 <= cp <= 0x318F) or (0xA960 <= cp <= 0xA97F) \
        or (0xAC00 <= cp <= 0xD7AF) or (0xFFA0 <= cp <= 0xFFDC)


def j_is_chinese(text):
    has_han = False
    for ch in text:
        cp = ord(ch)
        if j_is_kana(cp) or j_is_hangul(cp):
            return False
        if j_is_han(cp):
            has_han = True
    return has_han


def j_trim_to_null(text):
    if text is None:
        return None
    t = text.strip()
    return None if t == "" else t


def j_shanghai(destination, platform_name):
    """照 Java 的 arriveText(destination, platformName)。"""
    text = LITERALS["ARRIVE_OPENING"]
    terminus = j_trim_to_null(destination)
    if terminus is not None:
        text += LITERALS["TERMINUS_LEAD"] + terminus
    platform = j_trim_to_null(platform_name)
    if platform is None:
        text += LITERALS["NO_PLATFORM_TAIL"]
    else:
        text += LITERALS["PLATFORM_LEAD"] + platform
        text += LITERALS["PLATFORM_TAIL_BARE"] if "站台" in platform else LITERALS["PLATFORM_TAIL"]
    return text


def j_hongkong(destination):
    """照 Java 的 arriveTextHongKong(String destination)。

    ★ 入参是**本次列车终点站**（MTR 的 {@code 中文|English} 字段），既不是车站名、也不是站台名
      —— 见 §8.6。
    ★★【10-01 用户点名改规矩】香港档**始终**两句都念：拆完段后缺哪段就用**另一段**兜底
      （`if chinese is None: chinese = foreign` / `if foreign is None: foreign = chinese`），
      再**无条件**拼「中文句 + 换行 + 英文句」。旧规矩①②（缺哪半只念哪半）已废。
    ★ Java 的 split 会砍掉**结尾的空段**、Python 的不会；这里之所以不影响结果：
      两边的空段都会被「逐段 trimToNull + 空就 continue」吃掉（下面几个用例专门盯这一点）。
    """
    raw = j_trim_to_null(destination)
    if raw is None:
        return None
    chinese = None
    foreign = None
    for part in raw.split("|"):
        piece = j_trim_to_null(part)
        if piece is None:
            continue
        if j_is_chinese(piece):
            if chinese is None:
                chinese = piece
        elif foreign is None:
            foreign = piece
    if chinese is None and foreign is None:
        return None
    if chinese is None:
        chinese = foreign
    if foreign is None:
        foreign = chinese
    return (LITERALS["HK_CN_LEAD"] + chinese + LITERALS["HK_CN_TAIL"]
            + "\n"
            + LITERALS["HK_EN_LEAD"] + foreign + LITERALS["HK_EN_TAIL"])


HK_CN = LITERALS["HK_CN_LEAD"] + "%s" + LITERALS["HK_CN_TAIL"]
HK_EN = LITERALS["HK_EN_LEAD"] + "%s" + LITERALS["HK_EN_TAIL"]

# ---- 8.3 上海档（沿用上一轮的用例，防止改香港档时把手上的那句改坏） ----
SHANGHAI_CASES = [
    # (destination, platformName, 期望)
    ("XXX", "Y",
     "乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们在Y站台有序候车"),
    (None, "Y", "乘客们，列车马上就要进站了，请乘客们在Y站台有序候车"),
    ("XXX", None, "乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们有序候车"),
    ("XXX", "1站台", "乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们在1站台有序候车"),
    (None, None, "乘客们，列车马上就要进站了，请乘客们有序候车"),
    ("  XXX  ", "  Y  ", "乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们在Y站台有序候车"),
]
for dest, plat, want in SHANGHAI_CASES:
    got = j_shanghai(dest, plat)
    check(got == want, "上海档 (%r, %r) → %r" % (dest, plat, got),
          "" if got == want else "期望 %r" % want)

# ---- 8.4 香港档：用户点名的【10-01 改】规矩「始终两句都念」+ 各种边界 ----
#   ★★【09-28 续 3】表里这些名字 = **本次列车终点站**（与上海档同一个字段）。
#     名字本身长什么样与「它叫什么」无关 —— 这一节只核**拆分**行为；名源对不对见 §8.6。
#   ★★【10-01 用户点名改规矩】香港档**始终**两句都念：缺哪段就用**另一段**兜底
#     （不再「缺哪半只念哪半」）。⇒ 单语言名（「上海站」「Shanghai Station」「1」）
#     都拼出两句，缺的那半用现有那段顶上。
HONGKONG_CASES = [
    # 用户原话的完整句式（中英都有）
    ("上海站|Shanghai Station", HK_CN % "上海站" + "\n" + HK_EN % "Shanghai Station",
     "中英双语两句，中间换行（各取各段）"),
    # 【10-01 改】只有中文名 ⇒ 两句都念，英文句用中文名兜底
    ("上海站", HK_CN % "上海站" + "\n" + HK_EN % "上海站",
     "只有中文名 ⇒ 两句都念（英文句用中文名兜底）"),
    # 【10-01 改】只有英文名 ⇒ 两句都念，中文句用英文名兜底
    ("Shanghai Station", HK_CN % "Shanghai Station" + "\n" + HK_EN % "Shanghai Station",
     "只有英文名 ⇒ 两句都念（中文句用英文名兜底）"),
    # 其它语言（韩文 / 日文）落「非中文段」桶 ⇒ 两句都念、都用名字本身
    ("서울", HK_CN % "서울" + "\n" + HK_EN % "서울", "韩文（谚文）⇒ 两句都念、用名字本身"),
    ("도쿄", HK_CN % "도쿄" + "\n" + HK_EN % "도쿄", "韩文（谚文）⇒ 同上"),
    ("東京タワー", HK_CN % "東京タワー" + "\n" + HK_EN % "東京タワー",
     "日文含假名 ⇒ 两句都念、用名字本身"),
    ("とうきょう", HK_CN % "とうきょう" + "\n" + HK_EN % "とうきょう", "纯平假名 ⇒ 两句都念"),
    # ★ 数字名字（站台编号、线路号都长这样）—— **用户报的那一例**：MTR3 目的地「1」
    ("1", HK_CN % "1" + "\n" + HK_EN % "1",
     "★★ 纯数字（MTR3 单语言目的地「1」）⇒ 中文句+英文句都念，都用「1」"),
    # 中文段带数字 / 单位：含汉字就算中文段
    ("1站台|Platform 1", HK_CN % "1站台" + "\n" + HK_EN % "Platform 1", "中文段含汉字即算中文"),
    # 已知边界：纯汉字日文名与中文无从区分 ⇒ 会被当中文段（写进 skill 的上限）
    ("東京|Tokyo", HK_CN % "東京" + "\n" + HK_EN % "Tokyo",
     "★ 已知边界：纯汉字日文名按中文段处理（字形判据的固有上限）"),
    # 只给一段、另一段是空的 ⇒ 空段被吃掉，缺的那半用现有那段兜底
    ("上海站|", HK_CN % "上海站" + "\n" + HK_EN % "上海站", "后面空段被吃掉 ⇒ 英文句用中文名兜底"),
    ("|Shanghai", HK_CN % "Shanghai" + "\n" + HK_EN % "Shanghai", "前面空段被吃掉 ⇒ 中文句用名字兜底"),
    ("上海站 | Shanghai", HK_CN % "上海站" + "\n" + HK_EN % "Shanghai", "两段各自去首尾空白"),
    # 读不到 / 全是空
    (None, None, "终点站读不到 ⇒ null（调用方跳过这一条）"),
    ("", None, "空串 ⇒ null"),
    ("   ", None, "全空白 ⇒ null"),
    ("|", None, "只有分隔符 ⇒ 两个桶都空 ⇒ null"),
]
for name, want, why in HONGKONG_CASES:
    got = j_hongkong(name)
    check(got == want, "香港档 %r ⇒ %r  （%s）" % (name, got, why),
          "" if got == want else "期望 %r" % want)

# ---- 8.5 分派：样式 → 句式 ----
check(re.search(r"public static String arriveTextForStyle\(int mode", narrator) is not None
      and re.search(r"mode == EscalatorSpeedData\.PSD_NARRATE_HONGKONG", narrator) is not None,
      "★ 分派只看「是不是香港档」，其余（含关闭/上海）都走上海句式"
      "（关闭档在播放端根本不会走到这里 —— narrateTodo 已经是 false）")

# ---- 8.6 ★★ 两档的名源：都吃**终点站**（用户实测 LOG10 那一组数据） ----
#
# 用户报的 bug（LOG10/latest.log 原文，可逐字核）：
#   香港档 → 念出「前往火车站的列车即将到达，请先让车上的乘客下车 / The train to Train Station…」
#            （终点站 江苏北路|North JiangSu Road、站台 4A、本车站 火车站|Train Station）
#   上海档 → 念出「…本次列车终点站：江苏北路|North JiangSu Road，请乘客们在4A站台有序候车」
#   ⇒ 4 号线终点站就是江苏北路。上海档对、香港档错。
#
# 三个名字在 MTR 里是**三个不同字段**（字节码铁证，不是猜）：
#   终点站 = ArrivalResponse.getDestination()   ← 双语「中文|English」在这个字段上（上海档一直用它）
#   车站名 = Station.getName()                  ← 玩家**脚下那个站**的名字
#   站台名 = Platform.getName() / ArrivalResponse.getPlatformName()
#            ← 那一个站台自己的名字（通常就是编号）
#   ⇒ 「前往XX的列车」/「The train to XX」的 XX 只能是**终点站**：念车站名就变成
#     「有一班车要去你所在的这个站」，那是另一句话（就是把江苏北路念成火车站的那个错）。
def j_for_style(is_hongkong, destination, platform_name):
    """照 Java 的 arriveTextForStyle(mode, destination, platformName) —— 3 参。"""
    if is_hongkong:
        return j_hongkong(destination)
    return j_shanghai(destination, platform_name)


# ★ 用户那一班车的真实数据（终点站 江苏北路、站台 4A）——两档的名源必须是**同一个**
_USER_DEST = "江苏北路|North JiangSu Road"
_USER_PLATFORM = "4A"
check(j_for_style(True, _USER_DEST, _USER_PLATFORM)
      == HK_CN % "江苏北路" + "\n" + HK_EN % "North JiangSu Road",
      "★★ 香港档念的是**江苏北路**（终点站双语都念出来）—— 用户报的 bug 就是这里念成了「火车站」")
check(j_for_style(False, _USER_DEST, _USER_PLATFORM)
      == "乘客们，列车马上就要进站了，本次列车终点站：" + _USER_DEST
         + "，请乘客们在4A站台有序候车",
      "★★ 上海档一字未动（用户说「上海预设正常输出终点站为江苏北路」）")
check(HK_CN % "江苏北路" in j_for_style(True, _USER_DEST, _USER_PLATFORM)
      and "火车站" not in j_for_style(True, _USER_DEST, _USER_PLATFORM),
      "★★ 香港档的输出里**不许出现**当前站名（火车站）—— 这是用户报的那个 bug 的直接判据")
# 两档名源相同 ⇒ 只换句式：把 destination 固定，香港第 1 句去掉首尾壳应等于终点站中文段
check(j_for_style(True, _USER_DEST, _USER_PLATFORM).startswith(HK_CN % "江苏北路"),
      "★ 两档**只差句式不差名源**：名源换成非终点站的值就会当场变红（见下条反向对照）")
# 反向对照的判据（写死在脚本里，供人工注入 mutation 时对照）：
# ★【10-01 改】香港档现在**始终两句都念**：单语言名「4A」⇒ 中文句与英文句都用「4A」
check(j_for_style(True, _USER_PLATFORM, _USER_PLATFORM) == HK_CN % "4A" + "\n" + HK_EN % "4A",
      "★ 名源若是站台名（「4A」单语）⇒ 中文句+英文句都念「4A」"
      "（与真值「江苏北路|North JiangSu Road」一眼可辨；旧规矩下这里只剩英文半句）")
check(j_for_style(True, None, _USER_PLATFORM) is None,
      "★ 香港档：终点站读不到 ⇒ null（这一条跳过；不会退回站台名瞎念）")

# ======================================================================
# 9) 【09-28 续 2】/pbmnarrate：讲述人样式的**文字指令**
#    —— 有了它，「港铁预设」才能把这一项写成**一条普通指令**（预设 = 一串指令）。
# ======================================================================
print()
print("===== 9) /pbmnarrate 指令 + 「-f 只改样式、不动各维度秒数」 =====")

check(re.search(r'Commands\.literal\("pbmnarrate"\)', sl) is not None,
      "★ 注册了根指令 /pbmnarrate")
check(re.search(r'pbmNarrateStyle\("off", EscalatorSpeedData\.PSD_NARRATE_OFF\)', sl) is not None
      and re.search(r'pbmNarrateStyle\("shanghai", EscalatorSpeedData\.PSD_NARRATE_SHANGHAI\)', sl)
      is not None
      and re.search(r'pbmNarrateStyle\("hongkong", EscalatorSpeedData\.PSD_NARRATE_HONGKONG\)', sl)
      is not None,
      "★ 三个样式是**字面量**分支 off / shanghai / hongkong（打错当场被 Brigadier 拒绝，补全里也有）")
check(re.search(r'\.then\(pbmNarrateForce\("-f"\)\)', sl) is not None
      and re.search(r'private static LiteralArgumentBuilder<CommandSourceStack> pbmNarrateForce\('
                    r'String literal\)\s*\{', sl) is not None,
      "★ -f 分支 = 所有维度（与其它 PSD setter 同口径）")

# 本维度那条：**秒数原样保留**（别顺手清零 —— 指令只谈样式）
m_ng = re.search(r"private static int pbmNarrateGlobal\(CommandContext<CommandSourceStack> context,"
                 r" int mode\)\s*\{(.*?)\n    \}", sl, flags=re.S)
check(m_ng is not None, "抠得出 pbmNarrateGlobal 方法体")
if m_ng:
    ngb = m_ng.group(1)
    check(re.search(r"setDefaultPsdNarrate\(level, mode,\s*\n?\s*"
                    r"EscalatorSpeedManager\.getPsdNarrateSeconds\(level\)\)", ngb) is not None,
          "★★ 本维度只改样式：秒数传回**当前值**（不是 0）—— 否则 /pbmnarrate 会顺手清掉玩家调的提前量")
    check("syncPsdChimeToAll" in ngb, "★ 改完要同步给客户端（否则在游戏里看不到变化）")

# -f 那条：走 setDefaultPsdNarrateModeAll（**不是** setDefaultPsdNarrateAll）
m_nf = re.search(r"private static int pbmNarrateForceAll\(CommandContext<CommandSourceStack> context,"
                 r" int mode\)\s*\{(.*?)\n    \}", sl, flags=re.S)
check(m_nf is not None, "抠得出 pbmNarrateForceAll 方法体")
if m_nf:
    check("setDefaultPsdNarrateModeAll" in m_nf.group(1),
          "★★ -f 走 setDefaultPsdNarrateModeAll 而不是 setDefaultPsdNarrateAll"
          "（后者要求同时给秒数 ⇒ 会把其它维度各自调好的秒数一起冲掉）")

m_modeall = re.search(r"public static int setDefaultPsdNarrateModeAll\(MinecraftServer server, int mode\)"
                      r"\s*\{(.*?)\n    \}", mgr, flags=re.S)
check(m_modeall is not None, "抠得出 EscalatorSpeedManager.setDefaultPsdNarrateModeAll")
if m_modeall:
    mb = m_modeall.group(1)
    check("defaultPsdNarrateMode" in mb and "defaultPsdNarrateSeconds" not in mb,
          "★★ setDefaultPsdNarrateModeAll **一行都不碰** defaultPsdNarrateSeconds"
          "（「副产物最小」：改样式不该动时间）")
    check("t.withNarrate(null)" in mb and "withNarrateSeconds(null)" not in mb,
          "★★ 抹按串覆盖时**只抹 narrate 那一格**（narrateSeconds 是另一回事，留着）")

# 客户端总闸：预设按钮用得到的**两个**入口（经典港铁开、简单港铁/空白关）
tsw = switch
check(re.search(r"public static boolean enableForPreset\(\)\s*\{", tsw) is not None,
      "★ TrainAnnounceSwitch.enableForPreset() 存在（客户端那一侧开总闸，服务端指令碰不到 config）")
check(re.search(r"enableForPreset\(\)\s*\{.*?if \(enabled\)\s*\{\s*return false;", tsw, flags=re.S)
      is not None,
      "★ 本来就是开的 ⇒ 直接返回 false、**不写盘**（与模组其它界面「没改动就什么都不做」同一条约定）")
check(re.search(r"public static boolean disableForPreset\(\)\s*\{", tsw) is not None,
      "★★【09-28 续 4】TrainAnnounceSwitch.disableForPreset() 存在"
      "（简单港铁 / 空白预设要「关闭讲述人」⇒ 总闸也得关）")
check(re.search(r"disableForPreset\(\)\s*\{.*?if \(!enabled\)\s*\{\s*return false;", tsw, flags=re.S)
      is not None
      and re.search(r"disableForPreset\(\)\s*\{.*?enabled = false;\s*save\(\);", tsw, flags=re.S) is not None,
      "★★ disableForPreset 与 enableForPreset **严格对称**：本来就是关的 ⇒ 返回 false 不写盘；"
      "否则置 false 并 save()")

# /jsr show 的说明必须与「三档定死」一致，且不能残留「香港档吃车站名」那条旧说法
check("经典港铁预设＝开启(香港)" in tsw and "简单港铁预设 / 空白预设＝**关闭**" in tsw,
      "★★ /jsr show 里写明三个预设的讲述人取向（经典＝香港，简单/空白＝关闭）")
check("**车站名**按 MTR" not in tsw and "X / Y = **车站名**" not in tsw,
      "★★ /jsr show 不许再写「X / Y = 车站名」（续 39 那条错法已被续 40 订正）")
check("本次列车终点站**按 MTR" in tsw,
      "★★ /jsr show 的香港句式说明改为「X = 本次列车终点站」")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
