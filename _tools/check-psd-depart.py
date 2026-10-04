# -*- coding: utf-8 -*-
"""离线校验：【10-03 五改】用户点名的「关门后等待 X 秒发车」功能（含第二版订正）。

## 用户原话（逐句对应）

1. 屏蔽门 UI 增加「**关门后等待 X 秒发车**」一行（按钮点不动 + 右侧输入框，(-∞,+∞)，
   负数 = 门还没关完就发车）。★ 用户点名「**真的改变列车发车**」。

## 第二版订正（用户实测后报的，本脚本全部钉住）

- ★「按不了的按钮只是按了没反应和不会出现候选框，**而不是黑色，颜色和其他按钮一样**」
  ⇒ 必须是 `LabelButton`（覆写 `isHoveredOrFocused()` 恒 false + 画的时候临时 `active = true`
  用普通贴图 + 白字），**不是** MC 原生禁用态。
- ★「等待发车时间是**每个屏蔽门串**（连在一起的屏蔽门，包括门，幕墙，幕墙尾部）独有的，
  不要修改一个屏蔽门就全局同步」
  ⇒ 键 = **门串锚点**（不是车站级 runKey），另起一张 `psdRunSettings` 表 + 另走一条同步段。

## 还查什么（不开游戏）

- 数据层：`PsdRunSetting`（两项可空：发车等待 / 站台 id）、`psdRunSettings` 表、
  NBT 写读成对、clamp（恒等）、站台 id 哨兵只比哨兵不比正负。
- 信道：一条 ResourceLocation + 一个接收器（**门串锚点 + 站台 id + 值**）落到 setter。
- UI：一行「点不动的按钮 + 右侧输入框」、解析判据、退出界面落地、键用 `runAnchor`。
- 发车等待：`PsdDepartHold` 的取值层（先按**站台 id** 查、失败才回落车站级；±1 小时硬上界；
  任一步失败只打一条日志）+ 注入体只碰 `doorCooldown`、只在满值 4200 那一刻动手。
- 反向对照：把「只在满值动手」那条判据改坏 ⇒ 断言当场变红；再还原并用 md5 证还原。

用法：`python _tools/check-psd-depart.py`（退出码 0 = 全部通过）
"""

import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedData.java")
MGR = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedManager.java")
HOLD = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "PsdDepartHold.java")
DEPART_MIXIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                            "Mtr4TrainDepartHoldMixin.java")
MTR_JSON = os.path.join(ROOT, "src", "main", "resources", "smoothlift.mtr.mixins.json")
LIFT_PLUGIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                           "Mtr3LiftMixinPlugin.java")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
UI = os.path.join(CLIENT, "PsdToneSetupScreen.java")
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "SmoothLift.java")
CLIENT_NET = os.path.join(CLIENT, "SmoothLiftClient.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src):
    """剥掉块注释与行注释 —— 断言只认**语义**，不认文档里的字面量。"""
    return re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", src, flags=re.S), flags=re.S)


def md5_of(p):
    with open(p, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


data = load(DATA)
mgr = load(MGR)
hold = load(HOLD)
depart_mixin = load(DEPART_MIXIN)
ui = load(UI)
main = load(MAIN)
client_net = load(CLIENT_NET)

# ======================================================================
# 1) 数据层：门串级那张表（不是车站级 PsdToneAudio）
# ======================================================================
print()
print("===== 1) 数据层：门串级设置（关门后等待发车） =====")

check("DEFAULT_PSD_DEPART_DELAY_SECONDS = 0" in data,
      "departDelaySeconds 默认 0（= MTR 原样）")
check(re.search(r"clampPsdDepartDelaySeconds\(int seconds\)\s*\{\s*return seconds;",
                data) is not None,
      "★ clampPsdDepartDelaySeconds = **恒等**（用户点名 (-∞,+∞)，负数是「门没关完就发车」）")
# ★ 第二版订正：这一项**不能**待在车站级的 PsdToneAudio 里（那会让同站各串互相串味）。
check("Integer departDelaySeconds, Integer blinkCount) {" not in data
      and "withDepartDelaySeconds(Integer v)" not in data.split("record PsdRunSetting")[0],
      "★★ 这一项**不在**车站级的 PsdToneAudio record 里（同站两侧共用一条 ⇒ 会「改一串整站变」）")
check("public record PsdRunSetting(Integer departDelaySeconds, Long platformId)"
      in data,
      "★★ 另起 `PsdRunSetting` 记录（发车等待 / **站台 id**）")
check("public final Map<Long, PsdRunSetting> psdRunSettings = new HashMap<>();" in data,
      "另起一张 `psdRunSettings` 表（键 = 门串锚点，与车站级那张**同域不同表**）")
check("public static final long PSD_PLATFORM_ID_NONE = Long.MIN_VALUE;" in data
      and "platformId != PSD_PLATFORM_ID_NONE" in data,
      "站台 id 哨兵 = Long.MIN_VALUE，判「认到没认到」**只比哨兵**（id 可正可负）")
check(re.search(r"public boolean isEmpty\(\)\s*\{\s*return departDelaySeconds == null;", data)
      is not None,
      "isEmpty() = 没设过发车等待 ⇒ 调用方把记录删掉")
check(re.search(r"public PsdRunSetting withDepartDelaySeconds\(Integer v\)\s*\{.*?new PsdRunSetting"
                r"\(v, platformId\)", data, re.S) is not None
      and "withPlatformId(Long v)" in data,
      "两个 copy-on-write 的 with*（改一项不动其余）")
check('tag.contains("psdRunSettings", 9)' in data
      and 'data.psdRunSettings.put(key, v);' in data
      and 'tag.put("psdRunSettings", runList);' in data,
      "NBT 写读成对（第三条列表 `psdRunSettings`；老存档没有它 ⇒ 空表）")
check("data.psdRunSettings.merge(key, legacy," in data
      and 'PsdRunSetting legacy = new PsdRunSetting(' in data
      and 'entry.contains("departDelaySeconds")' in data,
      "★★ 旧档迁移：第一版写在车站级 psdToneAudio 里的值，读档时搬进门串级那张表"
      "（旧键 = 车站级 runKey；新版读侧先查门串锚点、查不到再回落它）")

for fn in ("setPsdRunDepartDelaySeconds",
           "getPsdRunDepartDelaySeconds",
           "getPsdRunDepartDelaySecondsResolved",
           "getPsdDepartDelayForPlatform", "applyClientPsdRunLocal", "applyClientPsdRun"):
    check(fn in mgr, "管理端有 %s" % fn)
check("data.psdRunSettings.remove(runAnchor)" in mgr and "data.psdRunSettings.put(runAnchor, now)" in mgr,
      "updatePsdRun：改空就删记录（与 updateDoor 同一套「表越干净越好查」）")

# ======================================================================
# 2) 信道：门串锚点 + 站台 id + 值
# ======================================================================
print()
print("===== 2) 新信道（门串锚点 + 站台 id + 值） =====")

check('new ResourceLocation("smoothlift", "set_psd_depart_delay")' in main,
      "SET_PSD_DEPART_DELAY_CHANNEL = smoothlift:set_psd_depart_delay")
blk = re.search(r"registerGlobalReceiver\(SET_PSD_DEPART_DELAY_CHANNEL,(.*?)\n        \}\)\;", main, re.S)
body = blk.group(1) if blk else ""
check("long runAnchor = buf.readLong();" in body and "long platformId = buf.readLong();" in body
      and "buf.readVarInt()" in body,
      "SET_PSD_DEPART_DELAY_CHANNEL 接收器：门串锚点(long) → 站台 id(long) → 值(varInt)")
check("setPsdRunDepartDelaySeconds" in body, "SET_PSD_DEPART_DELAY_CHANNEL 接收器落到 setter")

# 同步包：车站级那张表之后**追加一段门串级**
pk = re.search(r"private static FriendlyByteBuf buildPsdTonePacket\(ServerLevel level\)\s*\{(.*?)\n    \}",
               mgr, re.S)
pkbody = pk.group(1) if pk else ""
check("buf.writeVarInt(data.psdRunSettings.size());" in pkbody
      and "buf.writeLong(v.platformId() != null" in pkbody
      and "EscalatorSpeedData.PSD_PLATFORM_ID_NONE" in pkbody,
      "★ 同步包末尾追加门串级那一段（条数 → 锚点 + 站台 id + 一个可选值）")
check("applyClientPsdRun(dimKey, runSettings)" in client_net
      and "buf.readVarInt();" in client_net
      and "runSettings.put(runAnchor, new EscalatorSpeedData.PsdRunSetting(" in client_net,
      "客户端读端与写端成对（读序同写序；两个键空间分别落地）")

# ======================================================================
# 3) UI：一行「点不动的按钮 + 右侧输入框」（外观与普通按钮一致）
# ======================================================================
print()
print("===== 3) 屏蔽门 UI 那一行 =====")

check("private static final class LabelButton extends Button" in ui
      and "public boolean isHoveredOrFocused()" in ui
      and re.search(r"public boolean isHoveredOrFocused\(\)\s*\{\s*return false;", ui) is not None,
      "★★ 按钮外观 = 普通按钮（覆写 isHoveredOrFocused 恒 false ⇒ **不出现候选框**）")
check(re.search(r"protected void renderWidget\(GuiGraphics guiGraphics, int mouseX, int mouseY,"
                r" float partialTick\)\s*\{\s*boolean wasActive = this\.active;\s*this\.active = true;",
                ui) is not None,
      "★★ 画的时候临时 active = true ⇒ **普通贴图 + 白字**（不是 MC 原生的灰按钮/灰字）")
check("button.active = false;" in ui,
      "★ 仍然 active = false ⇒ 按了没反应、也不进焦点顺序")
check('disabledLabelButton("关门后等待发车", departY)' in ui,
      "这一行的按钮文案 = 用户点名的功能名")
check("MAIN_ROW_Y + ROW_H * 6" in ui
      and "MAIN_ROW_Y = LIST_TOP - 10" in ui,
      "这一行在固定区最下面（MAIN_ROW_Y + ROW_H*6；整块从 LIST_TOP-10 起 = 给底部按钮让出 20px）")
check("Integer sec = parseAnyInt(departDelayInput.getValue());" in ui,
      "发车等待框：接受任意 int（(-∞,+∞)）")
check("sendSetDepartDelay(sec)" in ui,
      "发车等待框在 applyMainInputs 里落地（换页 / 关界面统一收口）")

# ★ 第二版订正：键必须是**门串锚点**，不是车站级 runKey
check("private final long runAnchor;" in ui and "private final long runPlatformId;" in ui
      and "PsdDoorTracker.runAnchorOf(level, pos)" in ui
      and "PsdDoorTracker.platformIdOf(level, pos)" in ui,
      "★★ UI 的键 = **门串锚点**（`runAnchor`）+ 站台 id（`runPlatformId`）—— 不是车站级 runKey")
check("EscalatorSpeedManager.getPsdRunDepartDelaySecondsResolved(mcLevel(), runAnchor, runKey)" in ui,
      "这一行按门串锚点读（旧档车站级值作为回落也读得到）")
check("buf.writeLong(runAnchor);" in ui and "buf.writeLong(runPlatformId);" in ui,
      "包带上门串锚点 + 站台 id（列车那侧要按站台 id 反查）")
check("applyClientPsdRunLocal(level.dimension(), runAnchor," in ui,
      "本地回显也走门串级那张表")

# ======================================================================
# 4) 发车等待：取值层 + 注入体
# ======================================================================
print()
print("===== 4) 关门后等待 X 秒发车 =====")

check("public static final long DOOR_DELAY_MS = 1000L;" in hold,
      "DOOR_DELAY_MS = 1000（发车倒计时 4200 里那 1000ms 的门延迟）")
check("MAX_HOLD_SECONDS" in hold and "Math.max(-MAX_HOLD_SECONDS, Math.min(MAX_HOLD_SECONDS,"
      " seconds))" in hold,
      "★ 硬上界（±1 小时）：clamp 是恒等的，不夹住的话一个 2e9 秒就把列车钉死在站台")
check("MinecraftServer current = server;" in hold and "if (current == null || vehicle == null)" in hold,
      "取值层：服务端实例没准备好 / 对象为空 ⇒ 返回 0（不生效，但绝不抛进列车 tick）")
check("available = false;" in hold and "LOGGER.info" in hold,
      "★ 反射绑定失败只打一条 info 并整层停用（「任何一步失败都只是这一项不生效」）")
check("getThisPlatformId" in hold and "getPsdDepartDelayForPlatform" in hold,
      "★★ 先按**站台 id** 查（门串级设置里存了它）")
check("lookupByRunKey" in hold and "getPsdRunDepartDelaySeconds(level, stationKey)" in hold,
      "★ 兜底 = 按**车站级旧键**直接查门串级那张表（旧档迁移值挂旧键；不是按站台 id 扫）")
check("getThisStationId" in hold and "Long.MIN_VALUE + stationId" in hold,
      "★ 车站级 runKey = Long.MIN_VALUE + stationId（与 PsdDoorTracker.stationRunKey 同构）")
check(re.search(r"getPsdDepartDelayForPlatform\(ServerLevel level, long platformId\)\s*\{.*?"
                r"for \(EscalatorSpeedData\.PsdRunSetting v : getServerData\(level\)\.psdRunSettings"
                r"\.values\(\)\)", mgr, re.S) is not None,
      "服务端按站台 id 门串记录里反查（表只有几十条，每站查一次）")

check("@Shadow" in depart_mixin and "private long doorCooldown;" in depart_mixin,
      "@Shadow doorCooldown（MTR 的发车倒计时，private long，名字两端一致）")
check("if (doorCooldown != 4200L) {" in depart_mixin,
      "★ 只在**满值 4200** 那一刻动手 == 每站一次（关门指令那一 tick 门目标还是开着的）")
check("doorCooldown - PsdDepartHold.DOOR_DELAY_MS + offsetMs" in depart_mixin
      and "Math.max(0L, " in depart_mixin,
      "新倒计时 = max(0, 4200 - 1000 + X*1000) ⇒ 发车 = MTR 的「门走完」+ X 秒")
check("require = 0" in depart_mixin,
      "★ require = 0：MTR 改了方法名/结构 ⇒ 本功能静默失效，绝不崩游戏")
body = strip_comments(depart_mixin)
# ★ 注解里的 targets = "org.mtr.core.data.Vehicle" 是**目标名字符串**，不算「引用了 MTR 类型」。
body_no_target = body.replace('targets = "org.mtr.core.data.Vehicle"', "")
check("org.mtr." not in body_no_target and "net.minecraft" not in body_no_target,
      "★ 注入体里没有任何 MTR / MC 类型（编译期无 MTR 依赖；取值交给 PsdDepartHold）")
check("private void smoothlift$holdAfterDoorsClosed" in depart_mixin,
      "★ 处理器是实例方法（目标 startUp 是实例方法）")

check("Mtr4TrainDepartHoldMixin" in load(MTR_JSON), "mixin 已登记进 smoothlift.mtr.mixins.json")
check("Mtr4TrainDepartHoldMixin" in load(LIFT_PLUGIN)
      and 'classPresent("org.mtr.core.data.Vehicle")' in load(LIFT_PLUGIN),
      "门禁按它自己的目标类判（org.mtr.core.data.Vehicle），且只用 classPresent（绝不 load 类）")

# ======================================================================
# 5) 反向对照：把「只在满值动手」改坏 ==> 断言必须变红
# ======================================================================
print()
print("===== 5) 反向对照（mutation 后必须变红 + md5 证还原） =====")

with open(DEPART_MIXIN, "rb") as fh:
    before = fh.read()
before_md5 = hashlib.md5(before).hexdigest()
good = b"if (doorCooldown != 4200L) {"
bad = b"if (false) {"
check(good in before, "原文件含「只在满值动手」判据（实验前提）")
with open(DEPART_MIXIN, "wb") as fh:
    fh.write(before.replace(good, bad))
after = load(DEPART_MIXIN)
check(good.decode() not in after, "mutation 后那条断言当场变红")
with open(DEPART_MIXIN, "wb") as fh:
    fh.write(before)
check(md5_of(DEPART_MIXIN) == before_md5,
      "还原成功（md5 一致）：%s" % before_md5,
      "还原前后 md5 不一致！源码可能被污染")

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
