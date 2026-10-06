# -*- coding: utf-8 -*-
"""离线守则校验：**讲述人默认值 / 经典港铁预设 / 自定义文字按存档 + 两套分开**（2026-10-01）。

用户点名（三版：1.20.1 / 1.20.4 / Forge 1.20.1）：
  ① 第一次加载模组：进站广播 = **默认 -20 秒 / 香港预设 / 聊天框播报**，站台广播默认不开启；
  ② mbm help 的**经典港铁预设** = 同样这四条（-20 秒 / 香港 / 聊天框 / 站台关闭）；
  ③ 讲述人**自定义文字**要**保存在存档里**（像导入 OGG 一样：随存档走、存档挪走能找到、
     不存 config、不存档之间互通）；
  ④ 进站播报 / 站台播报的讲述人自定义文字**两套分开**（不要 2 个共用一个文字列表）。

## 本脚本查什么（全部「剥注释后按语义断言」，不锚排版）

1. **默认值（数据层）**
   - `DEFAULT_PSD_NARRATE_MODE` = 香港（`PSD_NARRATE_HONGKONG`），不再是上海；
   - 新增 `DEFAULT_PSD_NARRATE_LEAD_SECONDS = -20`，且 `defaultPsdNarrateSeconds` 的初值用它；
     ★ `DEFAULT_PSD_NARRATE_SECONDS` 仍 = 0（「站台广播（讲述人）」的等待秒数还在共用它，
     绝不能跟着改成 -20 —— 两条方向相反）。
2. **默认值（客户端）**
   - `TrainAnnounceSwitch.textMode` 初值 = `TextMode.CHAT`；`midiumEnabled` 初值 = `false`；
   - load() 里「键缺失」的分支也要落这两个新默认（老写法会把字段初值重新盖成旧默认）；
   - save() **不再写** `trainArriveAnnounceUser.N`（词改存存档），只写 `…UserMigrated` 标记。
3. **经典港铁预设** = -20 秒 / 香港 / 聊天框 / 站台关
   - `MbmHelpScreen` classic 分支调 `enableChatForPreset()`（**不是** enableWordForPreset）；
   - `SmoothLift.applyPreset` classic 分支调 `setDefaultPsdNarrateLeadAll(server, -20)` + sync；
4. **按存档存 + 两套分开**
   - `EscalatorSpeedData` 两个新列表字段 + NBT 读写（写序在 midiumNarrateSeconds 之后）；
   - S2C：`buildPsdChimePacket` 末尾写两份 → `SmoothLiftClient` 同序读 → `applyNarrateUserTexts`；
   - C2S：`SmoothLift.SET_PSD_NARRATE_TEXTS_CHANNEL` + 注册里 `setPsdNarrateUserTextsAll` + sync；
   - `TrainAnnounceSwitch`：站台那份列表 + 全套访问器 + `sendNarrateTexts` 走 C2S +
     `applyNarrateUserTexts` 带一次性迁移（`KEY_USER_TEXT_MIGRATED`）；
   - `TrainAnnounceNarrator.arriveTextForStyle` 收调用方给的那份列表（userN 模板按它取）；
     `PsdChimePlayer` 两个调用点分别传 `arriveUserTexts()` / `midiumUserTextsList()`；
   - `PsdToneSetupScreen` 讲述人两页按 `narratorTarget` 路由（进站 / 站台各读写各的）。
5. **脚本自带对照实验**：注入「旧行为」必须判红（防恒真假网）。

## 布局
本脚本在 1.20.4 / 1.20.1(Fabric) / Forge-1.20.1 三处共用：
Fabric 分包 sourceset（client 在 src/client/java/…）；Forge 单一 sourceset（全在 src/main/java/…）。
路径解析用探测式（与 check-psd-midium-once.py 同一套 `_find_src`），保证三份文本逐字节相同。
"""

import io
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _find(client_name, main_name):
    """按工程布局找源文件：client_name 先试 Fabric 的 src/client、再试 src/main；
    main_name 只在 src/main。找不到返回 None。"""
    if client_name is not None:
        for rel in (("src", "client", "java", "smooth", "lift", "client", client_name),
                    ("src", "main", "java", "smooth", "lift", "client", client_name)):
            p = os.path.join(PROJ, *rel)
            if os.path.isfile(p):
                return p
    if main_name is not None:
        p = os.path.join(PROJ, "src", "main", "java", "smooth", "lift", main_name)
        if os.path.isfile(p):
            return p
    return None


def load(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


CLIENT = "src" + "?"  # 占位：实际路径见下面
P_DATA = _find(None, "EscalatorSpeedData.java")
P_MANAGER = _find(None, "EscalatorSpeedManager.java")
P_SMOOTH = _find(None, "SmoothLift.java")
P_TAS = _find("TrainAnnounceSwitch.java", None)
P_NARR = _find("TrainAnnounceNarrator.java", None)
P_PLAYER = _find("PsdChimePlayer.java", None)
P_MBM = _find("MbmHelpScreen.java", None)
P_PSS = _find("PsdToneSetupScreen.java", None)
P_SLC = _find("SmoothLiftClient.java", None)
# 【Forge 移植】Forge 没有 SmoothLiftClient —— 客户端收包在 network/PsdChimeSyncPacket。
P_FORGE_SYNC = _find(None, "network/PsdChimeSyncPacket.java") if P_SLC is None else None

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label,
                         ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def carve(body, header_re):
    m = re.search(header_re, body)
    if not m:
        return ""
    i = body.find("{", m.start())
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(body)):
        c = body[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return body[i:j + 1]
    return ""


def flat(s):
    return re.sub(r"\s+", "", s)


# ======================================================================
# 1) 默认值（数据层）
# ======================================================================

def defaults_data_fails(src):
    fails = []
    body = strip_comments(src)

    m = re.search(r"DEFAULT_PSD_NARRATE_MODE\s*=\s*PSD_NARRATE_(HONGKONG|SHANGHAI)\b", body)
    if not m:
        fails.append("找不到 DEFAULT_PSD_NARRATE_MODE 赋值")
    elif m.group(1) != "HONGKONG":
        fails.append("DEFAULT_PSD_NARRATE_MODE 不是香港（用户点名：默认香港预设）—— 现为 "
                     + m.group(1))

    m = re.search(r"DEFAULT_PSD_NARRATE_LEAD_SECONDS\s*=\s*(-?\d+)\b", body)
    if not m:
        fails.append("没有 DEFAULT_PSD_NARRATE_LEAD_SECONDS 常量（进站默认 -20 秒）")
    elif int(m.group(1)) != -20:
        fails.append("DEFAULT_PSD_NARRATE_LEAD_SECONDS != -20（现为 %s）" % m.group(1))

    if not re.search(r"DEFAULT_PSD_NARRATE_SECONDS\s*=\s*0\b", body):
        fails.append("DEFAULT_PSD_NARRATE_SECONDS 不再 0 —— 站台讲述人的秒数还在共用它")

    if not re.search(r"defaultPsdNarrateSeconds\s*=\s*DEFAULT_PSD_NARRATE_LEAD_SECONDS", body):
        fails.append("defaultPsdNarrateSeconds 初值没有指向 LEAD 常量（-20）")
    if re.search(r"defaultPsdMidiumNarrateSeconds\s*=\s*DEFAULT_PSD_NARRATE_LEAD_SECONDS", body):
        fails.append("站台讲述人秒数初值指向了 LEAD（-20）—— 方向相反，必须还是 0")
    return [f for f in fails if f]


# ======================================================================
# 2) 默认值（客户端）＋ config 不再存词
# ======================================================================

def defaults_client_fails(src):
    fails = []
    body = strip_comments(src)

    if not re.search(r"private\s+static\s+TextMode\s+textMode\s*=\s*TextMode\.CHAT", body):
        fails.append("TrainAnnounceSwitch.textMode 初值不是 CHAT（用户点名：聊天框播报）")
    if not re.search(r"private\s+static\s+boolean\s+midiumEnabled\s*=\s*false", body):
        fails.append("TrainAnnounceSwitch.midiumEnabled 初值不是 false（用户点名：站台广播默认不开启）")

    load = carve(body, r"(?:public|private) static void load\s*\(")
    if load:
        if re.search(r"textMode\s*=\s*\"on\"\s*\.equals\(props\.getProperty\(KEY_WORD\)\)[^;]*\?[^;]*TextMode\.WORD[^;]*:\s*TextMode\.OFF", load):
            fails.append("load() 里旧键缺失分支仍是 OFF（应 CHAT）")
        if re.search(r"midiumEnabled\s*=\s*!VALUE_OFF\.equals\(props\.getProperty\(KEY_M_ENABLED\)\)", load):
            fails.append("load() 里站台总闸仍是「缺失即开」（应「缺失即关」）")
    else:
        fails.append("抠不出 TrainAnnounceSwitch.load()")

    save = carve(body, r"private static void save\s*\(")
    if save:
        if re.search(r"KEY_USER_PREFIX\s*\+\s*\(?i\s*\+\s*1\)?", save):
            fails.append("save() 还在写 trainArriveAnnounceUser.N —— 自定义词已改存存档")
        if "KEY_USER_TEXT_MIGRATED" not in save:
            fails.append("save() 没有写一次性迁移标记 KEY_USER_TEXT_MIGRATED")
    else:
        fails.append("抠不出 TrainAnnounceSwitch.save()")
    return [f for f in fails if f]


# ======================================================================
# 3) 经典港铁预设 = -20 秒 / 香港 / 聊天框 / 站台关
# ======================================================================

def preset_fails(mbm_src, smooth_src, manager_src):
    fails = []
    mb = strip_comments(mbm_src)

    send = carve(mb, r"private void sendPreset\s*\(")
    if not send:
        return ["抠不出 MbmHelpScreen.sendPreset()"]
    # ★【10-05】三个预设现在**都**调 enableChatForPreset（用户点名「3 个默认…讲述人报站默认 chat」）
    #   ⇒ 不能再对**整个 sendPreset** 判「有没有 enableChatForPreset」—— 那已不是「经典预设」的判据
    #   （simple/blank 也有）。改判 **classic 分支那一块**：里面必须 enableChatForPreset、不许 enableWordForPreset。
    m_classic = re.search(r"ID_CLASSIC\.equals\(presetId\)\)\s*\{(.*?)\}", send, re.S)
    classic_block = m_classic.group(1) if m_classic else ""
    if not classic_block:
        fails.append("抠不出 sendPreset 里 classic 那个分支")
    else:
        if "enableWordForPreset()" in classic_block:
            fails.append("经典港铁预设还是开的**屏幕字幕**（enableWordForPreset）—— 用户点名改聊天框")
        if "enableChatForPreset()" not in classic_block:
            fails.append("经典港铁预设没有调 enableChatForPreset()（聊天框）")

    sm = strip_comments(smooth_src)
    ap = carve(sm, r"(?:public|private) static int applyPreset\s*\(")
    if not ap:
        fails.append("抠不出 SmoothLift.applyPreset()")
    else:
        if not re.search(r'"classic"\.equals\(presetId\)[^;]*setDefaultPsdNarrateLeadAll', ap):
            fails.append("applyPreset classic 分支没有 setDefaultPsdNarrateLeadAll（-20 秒）")
        if "setDefaultPsdNarrateLeadAll" not in ap:
            fails.append("applyPreset 没有 setDefaultPsdNarrateLeadAll")
    if not re.search(r"int setDefaultPsdNarrateLeadAll\s*\(MinecraftServer", strip_comments(manager_src)):
        fails.append("EscalatorSpeedManager 没有 setDefaultPsdNarrateLeadAll")
    return [f for f in fails if f]


# ======================================================================
# 4) 按存档存 + 两套分开
# ======================================================================

def _find_client_src():
    """客户端「收同步包」的文件：Fabric = SmoothLiftClient；Forge = network/PsdChimeSyncPacket。"""
    if P_SLC:
        return load(P_SLC)
    return load(P_FORGE_SYNC) if P_FORGE_SYNC else ""


def save_texts_fails(data_src, manager_src, smooth_src, slc_src, tas_src):
    fails = []
    d = strip_comments(data_src)

    for field in ("defaultPsdArriveNarrateUserTexts", "defaultPsdMidiumNarrateUserTexts"):
        if field not in d:
            fails.append("EscalatorSpeedData 缺字段 " + field)
    if "writeNarrateUserTexts" not in d or "readNarrateUserTexts" not in d:
        fails.append("EscalatorSpeedData 缺 writeNarrateUserTexts / readNarrateUserTexts")
    if '"defaultPsdArriveNarrateUserTexts"' not in d or '"defaultPsdMidiumNarrateUserTexts"' not in d:
        fails.append("EscalatorSpeedData 的 NBT 键（defaultPsd*NarrateUserTexts）不在读写里")
    if not re.search(r'tag\.put\("defaultPsdArriveNarrateUserTexts"[^;]*writeNarrateUserTexts', d):
        fails.append("save() 没写进站文字列表")

    mg = strip_comments(manager_src)
    if not re.search(r"NARRATE_TEXTS_ARRIVE\s*=\s*0", mg) or not re.search(r"NARRATE_TEXTS_MIDIUM\s*=\s*1", mg):
        fails.append("Manager 缺 NARRATE_TEXTS_ARRIVE=0 / NARRATE_TEXTS_MIDIUM=1")
    if "setPsdNarrateUserTextsAll" not in mg:
        fails.append("Manager 缺 setPsdNarrateUserTextsAll")
    if not re.search(r"public static List<String> getPsdNarrateUserTexts\s*\(ServerLevel", mg):
        fails.append("Manager 缺 getPsdNarrateUserTexts(ServerLevel)")
    if not re.search(r"public static List<String> getPsdMidiumNarrateUserTexts\s*\(ServerLevel", mg):
        fails.append("Manager 缺 getPsdMidiumNarrateUserTexts(ServerLevel)")
    bp = carve(mg, r"private static FriendlyByteBuf buildPsdChimePacket\s*\(")
    if bp:
        # 写序：midiumNarrateSeconds 之后还有两段列表
        mns = bp.rfind("defaultPsdMidiumNarrateSeconds")
        if mns < 0 or "writeNarrateUserTexts" not in bp[mns:]:
            fails.append("buildPsdChimePacket 在 midiumNarrateSeconds 之后没有写两份文字列表")

    sm = strip_comments(smooth_src)
    # 【平台两可】Fabric = SmoothLift.SET_PSD_NARRATE_TEXTS_CHANNEL（常量 + 注册）；
    # Forge = network/SetPsdNarrateTextsPacket（Packets.register()）。两者必须其一。
    forge_pack = _find(None, "network/SetPsdNarrateTextsPacket.java")
    forge_pack_src = load(forge_pack) if forge_pack else ""
    has_fabric_channel = "SET_PSD_NARRATE_TEXTS_CHANNEL" in sm
    has_forge_packet = "setPsdNarrateUserTextsAll" in forge_pack_src \
        and "SetPsdNarrateTextsPacket" in strip_comments(load(P_FORGE_SYNC) if not P_SLC else "") \
        or (P_SLC is not None and "SetPsdNarrateTextsPacket" in strip_comments(load(P_SLC)))
    if not has_fabric_channel and not forge_pack:
        fails.append("SmoothLift 缺 SET_PSD_NARRATE_TEXTS_CHANNEL（Fabric）/ SetPsdNarrateTextsPacket（Forge）")
    if has_fabric_channel:
        reg = carve(sm, r"registerGlobalReceiver\(SET_PSD_NARRATE_TEXTS_CHANNEL")
        if reg:
            if "setPsdNarrateUserTextsAll" not in reg or "syncPsdChimeToAll" not in reg:
                fails.append("SET_PSD_NARRATE_TEXTS_CHANNEL 的注册里没落库 / 没全量同步")
        else:
            fails.append("SET_PSD_NARRATE_TEXTS_CHANNEL 没注册")
    else:
        if not forge_pack_src:
            fails.append("SetPsdNarrateTextsPacket 不存在（Forge C2S 落库口）")
        elif "setPsdNarrateUserTextsAll" not in forge_pack_src or "syncPsdChimeToAll" not in forge_pack_src:
            fails.append("SetPsdNarrateTextsPacket.handle 没落库 / 没全量同步")

    sc = strip_comments(_find_client_src())
    if "readNarrateUserTexts" not in sc:
        fails.append("客户端收包端（SmoothLiftClient / PsdChimeSyncPacket）没读两份文字列表")
    if "applyNarrateUserTexts(" not in sc:
        fails.append("客户端收包端没把文字喂给 TrainAnnounceSwitch.applyNarrateUserTexts")

    t = strip_comments(tas_src)
    if "midiumUserTexts" not in t:
        fails.append("TrainAnnounceSwitch 没有站台那份列表 midiumUserTexts")
    for meth in ("midiumUserTextCount", "midiumUserTextRaw", "midiumUserIndexOf",
                 "setMidiumUserText", "addMidiumUserText", "deleteMidiumUserText",
                 "applyNarrateUserTexts", "sendNarrateTexts"):
        if meth not in t:
            fails.append("TrainAnnounceSwitch 缺 " + meth)
    for meth in ("arriveUserTexts", "midiumUserTextsList"):
        if meth not in t:
            fails.append("TrainAnnounceSwitch 缺取整份列表的 " + meth)
    if "KEY_USER_TEXT_MIGRATED" not in t:
        fails.append("TrainAnnounceSwitch 缺 KEY_USER_TEXT_MIGRATED 迁移标记")
    # 【平台两可】Fabric：ClientPlayNetworking.send(...SET_PSD_NARRATE_TEXTS_CHANNEL...)；
    #          Forge：Packets.CHANNEL.sendToServer(new SetPsdNarrateTextsPacket(...))。
    if not re.search(r"SET_PSD_NARRATE_TEXTS_CHANNEL|SetPsdNarrateTextsPacket", flat(t)):
        fails.append("sendNarrateTexts 没有发存档 C2S（SetPsdNarrateTextsPacket / SET_PSD_NARRATE_TEXTS_CHANNEL）")
    return [f for f in fails if f]


def split_lists_fails(narr_src, player_src, pss_src):
    fails = []
    n = strip_comments(narr_src)
    sig = re.search(r"arriveTextForStyle\([^)]*\)", n)
    if not sig or sig.group(0).count(",") < 5:
        fails.append("TrainAnnounceNarrator.arriveTextForStyle 没有第 6 个参数（userTexts 列表）")
    nf = carve(n, r"static String arriveTextForStyle\s*\(")
    if nf:
        if "userTexts" not in nf:
            fails.append("arriveTextForStyle 没用传入的 userTexts 取 userN 模板")
        if re.search(r"TrainAnnounceSwitch\.userText\(", nf):
            fails.append("arriveTextForStyle 仍按全局单份 TrainAnnounceSwitch.userText 取模板")
    else:
        fails.append("抠不出 arriveTextForStyle")

    p = strip_comments(player_src)
    if "arriveTextForStyle(narrateMode" not in flat(p) or "arriveUserTexts()" not in flat(p):
        fails.append("进站讲述人（③-B）没有传进站那份 arriveUserTexts()")
    if "midiumUserTextsList()" not in flat(p):
        fails.append("站台讲述人没有传站台那份 midiumUserTextsList()")

    u = strip_comments(pss_src)
    if "currentUserTextCount" not in u or "setCurrentUserText" not in u:
        fails.append("PsdToneSetupScreen 缺按目标路由的 currentUserTextCount/setCurrentUserText")
    if "narratorTarget == 0" not in u or "midiumUserTextRaw" not in u or "midiumUserTextCount" not in u:
        fails.append("PsdToneSetupScreen 讲述人页没有按 narratorTarget 分两份读写")
    return [f for f in fails if f]


# ======================================================================
# main
# ======================================================================

def main():
    print("===== 0) 读源码 =====")
    missing = [("EscalatorSpeedData", P_DATA), ("EscalatorSpeedManager", P_MANAGER),
               ("SmoothLift", P_SMOOTH), ("TrainAnnounceSwitch", P_TAS),
               ("TrainAnnounceNarrator", P_NARR), ("PsdChimePlayer", P_PLAYER),
               ("MbmHelpScreen", P_MBM), ("PsdToneSetupScreen", P_PSS),
               ("SmoothLiftClient/PsdChimeSyncPacket", P_SLC or P_FORGE_SYNC)]
    for name, p in missing:
        if not p or not os.path.isfile(p):
            print("[FAIL] 找不到源文件 %s (%s)" % (name, p))
            sys.exit(1)
        print("  %-22s %s" % (name, os.path.relpath(p, PROJ).replace("\\", "/")))

    data = load(P_DATA)
    manager = load(P_MANAGER)
    smooth = load(P_SMOOTH)
    tas = load(P_TAS)
    narr = load(P_NARR)
    player = load(P_PLAYER)
    mbm = load(P_MBM)
    pss = load(P_PSS)
    slc = _find_client_src()
    print()

    print("===== 1) 默认值（数据层）=====")
    f = defaults_data_fails(data)
    check(not f, "进站讲述人维度默认 = 开启(香港) / -20 秒；站台秒数仍 0",
          ("；".join(f)) if f else "全部命中")

    print()
    print("===== 2) 默认值（客户端）+ config 不再存词 =====")
    f = defaults_client_fails(tas)
    check(not f, "textMode 默认 CHAT、站台总闸默认关、load/save 落实、词不进 config",
          ("；".join(f)) if f else "全部命中")

    print()
    print("===== 3) 经典港铁预设 = -20 秒 / 香港 / 聊天框 / 站台关 =====")
    f = preset_fails(mbm, smooth, manager)
    check(not f, "经典预设聊天框 + 秒数 -20（服务端落库）",
          ("；".join(f)) if f else "全部命中")

    print()
    print("===== 4) 按存档存（数据 / 同步 / 客户端镜像 / C2S）=====")
    f = save_texts_fails(data, manager, smooth, slc, tas)
    check(not f, "两列表进 NBT、S2C 追加、C2S 通道、客户端镜像",
          ("；".join(f)) if f else "全部命中")

    print()
    print("===== 5) 两条广播文字两套分开 =====")
    f = split_lists_fails(narr, player, pss)
    check(not f, "arriveTextForStyle 收列表 + 进站/站台各传各的 + UI 按目标路由",
          ("；".join(f)) if f else "全部命中")

    print()
    print("===== 6) 脚本自带对照实验（注入旧行为必须判红）=====")
    # m1：经典预设退回屏幕字幕
    #   ★【10-05】只能改 **classic 分支那一处**：simple / blank 现在也调 enableChatForPreset
    #   （用户点名「3 个默认…讲述人报站默认 chat」），无脑全局替换会连它俩一起改
    #   （上一版期望 n1 == 1，正是因为当时只有 classic 一个调用点）。
    m1, n1 = re.subn(
        r"(ID_CLASSIC\.equals\(presetId\)\)\s*\{(?:(?!\}).)*?)TrainAnnounceSwitch\.enableChatForPreset\(\)",
        r"\1TrainAnnounceSwitch.enableWordForPreset()", mbm, count=1, flags=re.S)
    check(n1 == 1, "对照 m1 注入成功（经典预设退回 word）", "替换 %d 处" % n1)
    f1 = preset_fails(m1, smooth, manager)
    check(bool(f1), "m1 后「经典预设聊天框」必须判红",
          ("命中：" + f1[0]) if f1 else "★ 没判红 ⇒ 第 3 节是假网")
    # m2：arriveTextForStyle 退回 5 参 + 全局单份
    m2, n2 = re.subn(r"String\s+lineName,\s*List<String>\s+userTexts\)",
                     "String lineName)", narr)
    check(n2 == 1, "对照 m2 注入成功（arriveTextForStyle 丢列表参）", "替换 %d 处" % n2)
    f2 = split_lists_fails(m2, player, pss)
    check(bool(f2), "m2 后「两套分开」必须判红",
          ("命中：" + f2[0]) if f2 else "★ 没判红 ⇒ 第 5 节是假网")
    # m3：预设去掉 -20 秒
    m3, n3 = re.subn(r'EscalatorSpeedManager\.setDefaultPsdNarrateLeadAll\(player\.server,\s*-20\)',
                     ";", smooth)
    check(n3 == 1, "对照 m3 注入成功（预设去掉 -20 秒）", "替换 %d 处" % n3)
    f3 = preset_fails(mbm, m3, manager)
    check(bool(f3), "m3 后「经典预设 -20 秒」必须判红",
          ("命中：" + f3[0]) if f3 else "★ 没判红 ⇒ 第 3 节是假网")
    # m4：save() 退回写 config 词
    m4, n4 = re.subn(r'props\.setProperty\(KEY_USER_TEXT_MIGRATED, String\.valueOf\(userTextMigrated\)\);',
                     'for (int i = 0; i < userTexts.size(); i++) { props.setProperty(KEY_USER_PREFIX + (i + 1), userTexts.get(i) == null ? "" : userTexts.get(i)); }',
                     tas)
    check(n4 == 1, "对照 m4 注入成功（save 退回写 config 词）", "替换 %d 处" % n4)
    f4 = defaults_client_fails(m4)
    check(bool(f4), "m4 后「词不进 config」必须判红",
          ("命中：" + f4[0]) if f4 else "★ 没判红 ⇒ 第 2 节是假网")

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("   - " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()