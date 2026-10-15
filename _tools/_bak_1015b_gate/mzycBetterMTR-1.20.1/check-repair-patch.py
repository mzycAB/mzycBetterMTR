# -*- coding: utf-8 -*-
"""离线校验：【补丁】链路 —— 「导入补丁」弹窗 + 音频**融入存档** + 可删除。

## 用户点名（第二轮：架构已改）

    「导入补丁按钮放在兼容模式按钮左侧，点击导入补丁按钮之后弹窗，窗口有：
      『导入补丁』『删除补丁』『补丁文件夹』『取消』4 个按钮，
      补丁导入之后是直接把那些补丁音频**融入存档**的，而不是修补 jar 文件，
      点击删除补丁按钮之后删除融入存档的补丁音频，玩家自己导入的其他 ogg 不要动
      …补丁不要改模组 jar 文件内容！只修改存档！」

## 判据（改错了都不报错，症状 = 「按钮没了」/「导入了没声音」/「删补丁把玩家音频删了」）

1. **界面**：`MbmHelpScreen` 右上角**只有一只**「导入补丁」（位置 = 兼容模式左边，
   `rightRowX(1)`），点开 `SyncPopupScreen`；弹窗里三个选项的字面量 +
   弹窗自己补的「取消」= 用户点名的那 4 只。
2. **只动存档、绝不写 jar、也不往 jar 外写文件**：源码里不许再出现
   `patch.dat` / `config/smoothlift/repair`；`RepairStore` 里不许有 `Files.write`。
3. **存档落地**：`EscalatorSpeedData.repairAssets` 字段 + NBT 写/读成对。
4. **★★ 删补丁不许碰玩家音频**：`RepairStore.deleteFromSave` 的方法体里
   **只能**出现 `repairAssets`；一旦出现 `audioLibrary` 就判红。
5. **同步**：`buildAudioSyncPayload` 末尾追加 `repairAssets`；客户端接收端同序读回；
   `applyClientRepairData`；同步到达后清「时长 / 分界点」两张表。
6. **播放优先**：`bundledBytes` 先查补丁再读 jar；`patched()`；直梯与屏蔽门的注入分支。
7. **网络**：两个频道 + `serverImportRepair` / `serverDeleteRepair`
   （各平台网络层写法不同，这里只钉「频道常量与入口方法」）。
8. **密码与格式**：AES-256 包、20 位密码、明文密码不在 jar 里。
9. **反向对照**：拿掉「补丁优先」/ 把 `repairAssets` 换成 `audioLibrary` ⇒ 判据当场变红。

用法：`python _tools/check-repair-patch.py`（退出码 0 = 全部通过）
"""

import glob
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKSPACE = os.path.dirname(os.path.dirname(ROOT))     # <工作区根>/<版本>/<工程> ⇒ 上两级
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
# ★ Forge 工程把客户端代码也放在 src/main/java 下（没有分开源集）
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
if not os.path.isdir(CLIENT):
    # ★ Forge 没有分开源集：客户端类在 src/main/java/smooth/lift/**client** 下 ——
    #   回退时别漏掉最后那一级 `client`（漏了会让本脚本读到空文件，然后满屏假红）。
    CLIENT = os.path.join(MAIN, "client")
assert_ = os.path.isdir(CLIENT)
AUDIO_PLAYER = os.path.join(CLIENT, "EscalatorAudioPlayer.java")
REPAIR_ZIP = os.path.join(WORKSPACE, "Repair.zip")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)
    return ok


def read(path):
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def squash(text):
    return " ".join(text.split())


def tight(text):
    return re.sub(r"\s+", "", strip_comments(text))


def body_of(src, signature):
    """抠出某个方法的**方法体**（签名后的第一个 `{` 到配对的 `}`）。"""
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return ""


SCREEN = read(os.path.join(CLIENT, "MbmHelpScreen.java"))
SCREEN_CODE = strip_comments(SCREEN)
PATCH = read(os.path.join(CLIENT, "RepairPatch.java"))
STORE = read(os.path.join(MAIN, "RepairStore.java"))
CRYPTO = read(os.path.join(MAIN, "RepairCrypto.java"))
DATA = read(os.path.join(MAIN, "EscalatorSpeedData.java"))
MGR = read(os.path.join(MAIN, "EscalatorSpeedManager.java"))
MAIN_CLS = read(os.path.join(MAIN, "SmoothLift.java"))
# ★ 客户端「音频同步解析」所在的文件各工程叫法不同（Fabric/Forge1.18.2 = SmoothLiftClient.java，
#   Forge1.20.1 = SmoothLiftClientEvents.java）⇒ 用 glob 找，别写死文件名。
_sync_files = sorted(glob.glob(os.path.join(CLIENT, "SmoothLiftClient*.java")))
CLIENT_MAIN = "\n".join(read(x) for x in _sync_files)
POPUP = read(os.path.join(CLIENT, "SyncPopupScreen.java"))

print("===== 1) 界面：一只「导入补丁」+ 4 项弹窗 =====")
# ★ 按钮文字只看字面量：1.19+ 用 Component.literal("…")，1.18.2 那版走 ButtonBuilder + TextComponent
for label in ("导入补丁", "删除补丁", "补丁文件夹"):
    check('"%s"' % label in SCREEN_CODE, "弹窗选项文字「%s」在 MbmHelpScreen 里" % label)
check('"取消"' in POPUP, "「取消」由 SyncPopupScreen 统一补在最后（那份代码只有一处）")
check("new SyncPopupScreen(this," in SCREEN_CODE and "SyncPopupScreen.Option" in SCREEN_CODE,
      "点「导入补丁」开的是通用选项弹窗 SyncPopupScreen")
check("rightRowX(1)" in SCREEN_CODE,
      "★ 入口按钮挂在 rightRowX(1) —— 即「兼容模式」（rightRowX(0)）**左侧**（用户点名位置）")
check("rightRowX(2)" not in SCREEN_CODE,
      "★ 上一版的第二只按钮（打开补丁文件夹）已撤掉 —— 它现在是弹窗里的一项")
check(SCREEN_CODE.count("RepairPatch::importPatch") == 1
      and SCREEN_CODE.count("RepairPatch::deletePatch") == 1
      and SCREEN_CODE.count("RepairPatch::openFolder") == 1,
      "三个选项分别接到 importPatch / deletePatch / openFolder")
check("panelH()" in POPUP,
      "★ SyncPopupScreen 的面板高度按按钮数算（写死会让第 4 只按钮掉到黑框外面）")

print()
print("===== 2) 只动存档：不写 jar、也不往 jar 外写文件 =====")
for f, name in ((STORE, "RepairStore"), (PATCH, "RepairPatch"), (CRYPTO, "RepairCrypto")):
    code = strip_comments(f)
    check("patch.dat" not in code, "%s 里没有上一版的落盘文件 patch.dat" % name)
    check("config/smoothlift/repair" not in code, "%s 里没有旧的游戏目录落盘路径" % name)
    check("Files.write" not in code and "Files.newOutputStream" not in code,
          "%s 不往磁盘写任何文件（只读 zip / 只写存档数据）" % name)
check("Files.readAllBytes" in STORE and "MBM_Repair" in STORE,
      "RepairStore 只**读**存档目录下的 MBM_Repair/Repair.zip")

print()
print("===== 3) 存档字段：repairAssets 写/读成对 =====")
check("public final Map<String, byte[]> repairAssets" in DATA,
      "EscalatorSpeedData 有 repairAssets 字段（与 audioLibrary **分开**两张表）")
check('tag.put("repairAssets"' in DATA and 'getCompound("repairAssets")' in DATA,
      "NBT 写 / 读成对（写进存档、读回内存；缺字段 = 老存档兼容）")
check("data.repairAssets.put(key, bytes)" in DATA,
      "读侧把字节填进 repairAssets（带 MAX_AUDIO_BYTES 上限）")
check("data.setDirty()" in STORE, "导入/删除之后 setDirty（不标脏就不会真正落盘）")

print()
print("===== 4) ★★ 删补丁不许碰玩家自己导入的 ogg =====")
del_body = strip_comments(body_of(STORE, "public static boolean deleteFromSave(ServerLevel level)"))
check(bool(del_body), "抠得出 deleteFromSave 方法体")
check("repairAssets.clear()" in del_body, "★ 删补丁清的是 repairAssets")
check("audioLibrary" not in del_body,
      "★★ 方法体里**不出现** audioLibrary ⇒ 玩家自己导入的 ogg 一个都不动（用户点名要求）")
import_body = strip_comments(body_of(STORE, "public static int importFromSave(ServerLevel level)"))
check("repairAssets" in import_body and "audioLibrary" not in import_body,
      "★ 导入也只写 repairAssets（不动玩家那一张表）")
check("for (String key : RepairCrypto.KEYS)" in import_body,
      "★ 只写 RepairCrypto.KEYS 里那 6 个键（不认识的条目不进存档）")

print()
print("===== 5) 同步：读写出现在同一个包上 =====")
check("buf.writeVarInt(data.repairAssets.size());" in MGR,
      "服务端 buildAudioSyncPayload 末尾追加 repairAssets 段")
check("buf.writeUtf(entry.getKey(), 64)" in MGR and "buf.writeByteArray(entry.getValue())" in MGR,
      "写入形状 = 键(utf64) + 字节数组")
check("int repairCount = data.readVarInt();" in CLIENT_MAIN and "data.readUtf(64)" in CLIENT_MAIN,
      "★ 客户端接收端按**同样顺序 / 同样类型**读回（错一格就是静默错位）")
check("applyClientRepairData(dimKey, repairAssets)" in CLIENT_MAIN,
      "读到之后落到 applyClientRepairData（客户端镜像）")
check("EscalatorAudioPlayer.clearBundledMetrics();" in CLIENT_MAIN,
      "★ 同步到达后清「时长 / 分界点」两张表（否则屏蔽门还按旧素材算对齐）")
check(re.search(r"public static void applyClientRepairData\(ResourceKey<Level> dimension,\s*"
                r"Map<String, byte\[\]> repairAssets\)", MGR) is not None,
      "Manager 有 applyClientRepairData(dimension, map)")
check("public static byte[] getRepairBytes(Level level, String builtinKey)" in MGR
      and "public static boolean hasRepairData(Level level)" in MGR,
      "Manager 有 getRepairBytes / hasRepairData（客户端读镜像、服务端读存档）")

print()
print("===== 6) 播放端：补丁优先 + 两条路都走注入 =====")
ap_no = strip_comments(read(AUDIO_PLAYER))


def patch_first(src):
    """★ 这条判据**只有这一个实现**（反向对照直接调它）。"""
    i = src.find("RepairPatch.bytes(builtinKey)")
    j = src.find("getResourceAsStream(BUNDLED_AUDIO_PREFIX")
    return i >= 0 and j > i


check(patch_first(ap_no),
      "★★ bundledBytes：**先查补丁、再读 jar 资源**（顺序反了 = 补丁被自合成素材盖住）")
check(re.search(r"static boolean patched\(String builtinKey\)", ap_no) is not None,
      "有 patched(key)（区分「补丁特意覆盖过」与「jar 里本来就有」）")
lift_no = strip_comments(read(os.path.join(CLIENT, "LiftChimePlayer.java")))
check("EscalatorAudioPlayer.patched(builtinKey)" in lift_no
      and "injectPlayback(mc, builtinKey, patch)" in lift_no,
      "★ 直梯：补丁覆盖过的内置素材改走注入")
psd_no = strip_comments(read(os.path.join(CLIENT, "PsdChimePlayer.java")))
check("EscalatorAudioPlayer.patched(tone.builtinKey())" in psd_no,
      "★ 屏蔽门：补丁覆盖过的内置素材整段起播也走注入")

print()
print("===== 7) 网络入口 =====")
# ★ 1.21 起 ResourceLocation 的构造改成 fromNamespaceAndPath(...)，两种写法都算
def chan(name):
    return ('new ResourceLocation("smoothlift", "%s")' % name) in MAIN_CLS \
        or ('fromNamespaceAndPath("smoothlift", "%s")' % name) in MAIN_CLS


check(chan("mbm_repair_import") and chan("mbm_repair_delete"),
      "两个频道常量在 SmoothLift 里（id = smoothlift:mbm_repair_import / _delete）")
check("serverImportRepair(ServerPlayer player)" in MAIN_CLS
      and "serverDeleteRepair(ServerPlayer player)" in MAIN_CLS,
      "★ 服务端入口 serverImportRepair / serverDeleteRepair 只有一份实现（各平台网络层都调它）")
# ★ 1.18.2 的 ServerPlayer 只有 getLevel()（serverLevel() 是 1.19+ 的 API）⇒ 两种都算
check("RepairStore.importFromSave(level)" in MAIN_CLS
      and re.search(r"RepairStore\.deleteFromSave\(player\.(serverLevel|getLevel)\(\)\)", MAIN_CLS),
      "两个入口分别落到 RepairStore 的导入 / 删除")
check("syncAudioToAll(player.server);" in MAIN_CLS,
      "★ 导入 / 删除之后各同步一次（客户端才能立刻拿到 / 丢掉补丁字节）")
check("noteUiResult(player, imported > 0)" in MAIN_CLS and "noteUiResult(player, deleted)" in MAIN_CLS,
      "★ 成败记进「本次界面会话」⇒ 退出界面时那条 UI执行成功/失败 汇总（界面里不写小字）")
# ★ 发包形式按平台分叉：Fabric / Forge1.18.2 直接发**频道常量**；
#   Forge1.20.1 是一个包一个类（SimpleChannel），发的是 packet 对象。
if "MBM_REPAIR_IMPORT_CHANNEL" in PATCH:
    check("MBM_REPAIR_DELETE_CHANNEL" in PATCH,
          "RepairPatch 把两个动作发到服务端（频道常量形式）")
else:
    check("MbmRepairImportPacket" in PATCH and "MbmRepairDeletePacket" in PATCH,
          "RepairPatch 把两个动作发到服务端（Forge1.20.1 的 packet 类形式）")
    net = os.path.join(MAIN, "network")
    for cls in ("MbmRepairImportPacket", "MbmRepairDeletePacket"):
        check(os.path.isfile(os.path.join(net, cls + ".java")),
              "★ %s.java 存在（Forge 一个包一个类）" % cls)
    packets = read(os.path.join(net, "Packets.java"))
    check(packets.count("MbmRepairImportPacket") >= 1 and packets.count("MbmRepairDeletePacket") >= 1,
          "★ 两个 packet 都在 Packets.register() 里注册了（漏注册 = 发出去静默丢弃）")
    for cls in ("MbmRepairImportPacket", "MbmRepairDeletePacket"):
        body = read(os.path.join(net, cls + ".java"))
        check("SmoothLift.server" in body,
              "★ %s 转发到 SmoothLift 那唯一的服务端入口（各家共用一份实现）" % cls)

print()
print("===== 8) 密码与补丁包格式 =====")
m_lock = re.search(r"int\[\] LOCK\s*=\s*\{(.*?)\};", CRYPTO, re.S)
lock = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", m_lock.group(1))] if m_lock else []
check(len(lock) == 20, "LOCK 是 20 个字节（= 密码 20 字符）", "实际 %d" % len(lock))
crypto_t = tight(CRYPTO)
check('List.of("up","down","liftmusic","dooropen","doorclose","mdoorclose")' in crypto_t,
      "KEYS = 6 段被移除的原素材")
check('ZIP_SUFFIX=".ogg"' in crypto_t, "zip 条目名 = <key>.ogg")
check("PBKDF2WithHmacSHA1" in crypto_t and "HmacSHA1" in crypto_t
      and "readU32LE" in crypto_t and "readU16LE" in crypto_t and "AES/ECB/NoPadding" in crypto_t,
      "★ WinZip AES 三件套 + zip 小端读 + 自己做的 AES-CTR 都还在")


def un_lock(items, seed=0x5A17):
    st = seed
    out = []
    for masked in items:
        st = (st * 1103515245 + 12345) & 0xFFFFFFFF
        out.append(chr(masked ^ ((st >> 16) & 0xFF)))
    return "".join(out)


pwd = un_lock(lock)
check(len(pwd) == 20 and any(c.isalpha() for c in pwd) and any(c.isdigit() for c in pwd)
      and any(not c.isalnum() for c in pwd),
      "★ 由 LOCK 反推出的密码是 20 位、三类字符齐全", "解出 %r" % pwd)

if not os.path.isfile(REPAIR_ZIP):
    check(False, "补丁包存在：%s（先跑 _tools/make_repair_zip.py）" % REPAIR_ZIP)
else:
    with zipfile.ZipFile(REPAIR_ZIP) as z:
        infos = z.infolist()
    check(sorted(i.filename for i in infos)
          == sorted(k + ".ogg" for k in
                    ("up", "down", "liftmusic", "dooropen", "doorclose", "mdoorclose")),
          "补丁包 = 6 个条目", "实际 %s" % sorted(i.filename for i in infos))
    check(all(i.flag_bits & 0x1 for i in infos) and all(i.compress_type == 99 for i in infos),
          "★ 每个条目都是 AES（flag.bit0=1 且 compress_type=99）")
    whole = open(REPAIR_ZIP, "rb").read()
    check(b"OggS" not in whole, "★ 整包里搜不到明文 `OggS`")
    check(pwd.encode() not in whole, "★ 明文密码不在包里（密码只在 jar 内部）")

print()
print("===== 9) 反向对照（判据必须会红）=====")
probe = ap_no.replace("RepairPatch.bytes(builtinKey)", "PROBE_PATCH_READ")
check(not patch_first(probe),
      "★ 拿掉「先查补丁」⇒ 第 6 条的顺序判据当场变红（判的是代码，不是注释）")
probe2 = ('getResourceAsStream(BUNDLED_AUDIO_PREFIX + builtinKey + ".ogg"); ... '
          'RepairPatch.bytes(builtinKey);')
check(not patch_first(probe2), "★ 两段对调（资源读在前）⇒ 同一条判据也变红（它真的在判顺序）")
mutated = del_body.replace("repairAssets", "audioLibrary")
check("audioLibrary" in mutated and mutated != del_body,
      "★ 探针：把删补丁改成清 audioLibrary ⇒ 第 4 条的禁区判据必然变红（对照）")
probe_lock = lock[:-1]
check(un_lock(probe_lock) != pwd, "★ 少一个字节 ⇒ 反推出的密码不同（那 20 字节的判据有鉴别力）")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 【补丁】导入弹窗 + 融入存档：全部通过 ==")
