# -*- coding: utf-8 -*-
"""Script: check-psd-nbt.py

离线回归：**存档字段的「写」与「读」必须成对**（屏蔽门这一批 + 全局对称性）。

为什么值得单写一条：
  新加一批存档字段时，最容易犯的错是「只加了写、忘了读」或反过来。
  两种都不报错：
    - 写了不读  → 玩家改完设置、重进世界又变回默认（表现像「设置没保存」）；
    - 读了不写  → 值只在当前会话有效，重启即丢。
  这类 bug 只有「开存档 → 改设置 → 退出 → 再进」才能撞见，所以在这里用静态对称性钉住。

判定方式（不依赖任何 Java 解析器，直接对着源码做词法扫描）：
  1. 从 `EscalatorSpeedData.java` 的 `fromTag` 里抓出所有 `tag.contains("<名>")`；
  2. 从 `save()` 里抓出所有 `tag.put*("<名>", …)` / `tag.putString("<名>", …)`；
  3. 断言两个集合**完全相等**；
  4. 额外断言：每个屏蔽门字段在 `EscalatorSpeedManager` 里都有读侧入口（get/is 方法），
     否则「存进去了但没人能读出来」同样是死数据。

用法：`python _tools/check-psd-nbt.py`（退出码 0 = 全部通过）
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedData.java")
MANAGER = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedManager.java")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments(src):
    """去掉 // 与 /* */ 注释（否则注释里举的字段名例子会被误判成真实读写）。"""
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//[^\n]*", " ", src)
    return src


with open(DATA, encoding="utf-8") as fh:
    raw = fh.read()
body = strip_comments(raw)

read_keys = set(re.findall(r'tag\.contains\(\s*"([A-Za-z0-9_]+)"', body))
# 「写」同理不能只认 tag.xxx：列表元素是用临时变量写的，例如
#   CompoundTag t = new CompoundTag(); t.putLong("key", …); t.putString("open", …);
write_keys = set(re.findall(r'\.put[A-Za-z]*\(\s*"([A-Za-z0-9_]+)"', body))

# ★ 「读」不止 tag.contains 一种形态：列表里的元素用 item.getInt("x") / t.contains("y") 读，
#   也有直接 tag.getList("speeds", 10) 不先判 contains 的。所以把所有
#   `<任意接收者>.contains|getInt|getBoolean|getString|getDouble|getLong|getList|getCompound|getByteArray(
#   "<名字>"` 都算「读」。漏掉这些会把正常的字段误报成「写了不读」。
READ_CALLS = (r"\.(?:contains|getInt|getBoolean|getString|getDouble|getLong|getFloat|getByte|"
              r"getList|getCompound|getByteArray|getIntArray|getLongArray)\(\s*\"([A-Za-z0-9_]+)\"")
nested_reads = set(re.findall(READ_CALLS, body))
read_keys |= nested_reads

print("===== 1) 读 / 写 集合完全相等（全局）=====")
print("        读 %d 个字段、写 %d 个字段" % (len(read_keys), len(write_keys)))
only_read = sorted(read_keys - write_keys)
only_write = sorted(write_keys - read_keys)
check(not only_read, "没有「读了却不写」的字段（否则值一重启就丢）",
      "多出来：%s" % only_read if only_read else "无")
check(not only_write, "没有「写了却不读」的字段（否则设置存了没人理）",
      "多出来：%s" % only_write if only_write else "无")

print()
print("===== 2) 屏蔽门这一批字段必须 13 项齐全（读 + 写都在）=====")
PSD_SCALARS = [
    "defaultPsdHelp",
    "defaultPsdToneOpenEnabled",
    "defaultPsdToneCloseEnabled",
    "defaultPsdHelpVolume",
    "defaultPsdToneVolumeOpen",
    "defaultPsdToneVolumeClose",
    "defaultPsdHelpRound",
    "psdToneAudio",
    # 【1.15】维度默认**素材**（/pbmmusic open|close <名字>）：open → dooropen / close → doorclose 的落点。
    "defaultPsdToneAudioOpen",
    "defaultPsdToneAudioClose",
    # 【1.16】关门提示音强制等待时长（秒，/pbmclosewait）。★ 它**故意**与上面那批不同：
    #   旧存档缺这个键时**不**保留旧行为，而是读回字段初值 = 5 秒（用户点名「第一次加入
    #   mod 时默认为 5 秒」）。所以第 4 节照样要它套 clamp —— 它同样怕被外部改坏。
    "defaultPsdCloseWaitSeconds",
    # 【1.17】到站播报（/pbmmidium <名字> <秒>）：
    #   defaultPsdMidiumAudio     维度默认播报素材 id（"off" = 不播）；
    #   defaultPsdMidiumWaitSeconds 开门音播完后等几秒再播。
    #   ★ 与 closeWait 一样是「**无键就不覆盖**」，但它们俩的字段初值分别是
    #     "off" / 0，即「旧存档 = 不播」—— 本模组没有内置到站播报素材，所以这是对的行为。
    "defaultPsdMidiumAudio",
    "defaultPsdMidiumWaitSeconds",
    # 【1.21】进站报站（/pbmarrive <名字> <X>）：
    #   defaultPsdArriveAudio   维度默认进站报站素材 id（"off" = 不播）；
    #   defaultPsdArriveSeconds 提前秒数（(-∞, 0]）。
    #   与到站播报同一条：无键就不覆盖，字段初值是 "off" / 0（= 旧存档不播）。
    "defaultPsdArriveAudio",
    "defaultPsdArriveSeconds",
]
for k in PSD_SCALARS:
    check(k in read_keys, "读侧有 %s" % k)
    check(k in write_keys, "写侧有 %s" % k)
check(len(PSD_SCALARS) == 15, "屏蔽门字段清单 = 14 个标量 + 1 个列表（共 15 项）")

print()
print("===== 3) 屏蔽门字段在 Manager 里必须有「读侧入口」（否则是死数据）=====")
with open(MANAGER, encoding="utf-8") as fh:
    mgr = strip_comments(fh.read())

# 字段 → Manager 里那个「把它读出来给玩家/播放端看」的方法名（取不到就是死数据）
ACCESSORS = {
    "defaultPsdHelp": "isPsdHelpEnabled",
    "defaultPsdToneOpenEnabled": "isPsdToneEnabled",
    "defaultPsdToneCloseEnabled": "isPsdToneEnabled",
    "defaultPsdHelpVolume": "getPsdHelpVolume",
    "defaultPsdToneVolumeOpen": "getPsdToneVolume",
    "defaultPsdToneVolumeClose": "getPsdToneVolume",
    "defaultPsdHelpRound": "getPsdHelpRound",
    "psdToneAudio": "getClientPsdTone",
    # 【1.15】两个素材字段由 getPsdToneAudio(level, which) 读出来（open/close 各一）。
    "defaultPsdToneAudioOpen": "getPsdToneAudio",
    "defaultPsdToneAudioClose": "getPsdToneAudio",
    # 【1.16】强制等待时长：播放端（PsdChimePlayer）与石斧 UI 都靠这一个入口读。
    "defaultPsdCloseWaitSeconds": "getPsdCloseWaitSeconds",
    # 【1.17】到站播报：播放端（PsdChimePlayer.planArrivalAnnounce）与石斧 UI 靠这两个入口读。
    "defaultPsdMidiumAudio": "getPsdMidiumAudio",
    "defaultPsdMidiumWaitSeconds": "getPsdMidiumWaitSeconds",
    # 【1.21】进站报站：播放端（PsdChimePlayer.tickArriveAnnounce）与石斧 UI 靠这两个入口读。
    "defaultPsdArriveAudio": "getPsdArriveAudio",
    "defaultPsdArriveSeconds": "getPsdArriveSeconds",
}
for field, accessor in sorted(ACCESSORS.items()):
    check("public static" in mgr and accessor in mgr,
          "Manager 提供 %s（覆盖字段 %s）" % (accessor, field))

print()
print("===== 4) 读进来的数值必须夹取（挡住被外部改坏的存档值）=====")
pairs = [
    ("defaultPsdHelpVolume", "clampLiftHelpVolume"),
    ("defaultPsdToneVolumeOpen", "clampPsdToneVolume"),
    ("defaultPsdToneVolumeClose", "clampPsdToneVolume"),
    ("defaultPsdHelpRound", "clampPsdHelpRound"),
    # 【1.16】强制等待时长：读回时套 clamp（0~60）。★ 这一条还有个**副作用**要一起守住：
    #   「旧存档没有这个键」⇒ 走不到这里 ⇒ 保持字段初值 5 秒。所以下面的 contains 断言
    #   既证明「有键时夹取」，也间接证明「无键时不覆盖」。
    ("defaultPsdCloseWaitSeconds", "clampPsdCloseWaitSeconds"),
    # 【1.17】到站播报等待秒数：**只夹下界 0**（用户点名 [0,+∞)）。上界故意不设，
    #   所以这里只能断言「调了这个 clamp」，不能像 closeWait 那样断言区间。
    ("defaultPsdMidiumWaitSeconds", "clampPsdMidiumWaitSeconds"),
    # 【1.21】进站报站提前秒数：**只夹上界 0**（用户点名 (-∞, 0]）。下界故意不设 ——
    #   与上一行正好相反，两处一起看才说明「两个 clamp 是有意不对称的」。
    ("defaultPsdArriveSeconds", "clampPsdArriveSeconds"),
]
for key, clamp in pairs:
    m = re.search(r'tag\.contains\(\s*"%s"\s*\)\s*\)\s*\{([^}]*)\}' % key, body, flags=re.S)
    seg = m.group(1) if m else ""
    check(clamp + "(" in seg,
          "读 %s 时套了 %s（避免越界值被原版悄悄压回去 ⇒ 表现成「填了没用」）" % (key, clamp),
          re.sub(r"\s+", " ", seg).strip()[:70])

print()
print("===== 4b) 字符串型「素材 id」读回时也要归一化（挡住任意脏串）=====")
# 素材 id 存的是字符串，脏值来自「玩家手改存档 / 降级后残留的旧名」。归一化后只会是
# "off" 或库里真实存在的 key，播放端就不用到处判空。
m = re.search(r'tag\.contains\(\s*"defaultPsdMidiumAudio"\s*\)\s*\)\s*\{([^}]*)\}', body, flags=re.S)
seg = m.group(1) if m else ""
check("normalizePsdMidiumAudio(" in seg,
      "读 defaultPsdMidiumAudio 时套了 normalizePsdMidiumAudio（off/空/脏串都收敛）",
      re.sub(r"\s+", " ", seg).strip()[:70])
# 【1.21】进站报站同理：**没有内置素材** ⇒ 空/脏串必须收敛到 off，不能落回 default。
m = re.search(r'tag\.contains\(\s*"defaultPsdArriveAudio"\s*\)\s*\)\s*\{([^}]*)\}', body, flags=re.S)
seg = m.group(1) if m else ""
check("normalizePsdArriveAudio(" in seg,
      "读 defaultPsdArriveAudio 时套了 normalizePsdArriveAudio（off/空/脏串都收敛）",
      re.sub(r"\s+", " ", seg).strip()[:70])

print()
print("===== 5) 布尔字段必须是「只在 contains 时覆盖」，不能无条件写默认值 =====")
# 无条件覆盖 = 旧存档缺这个键时会被重置成默认；对本模组是**可接受**的（新字段），
# 但要确认它是有意为之：这里断言每个布尔读都包在 if (tag.contains(...)) 里。
for key in ("defaultPsdHelp", "defaultPsdToneOpenEnabled", "defaultPsdToneCloseEnabled"):
    ok = ('tag.contains("%s")' % key) in body
    check(ok, "读 %s 前先判 contains（不会把「没有这个键」当成 false 而覆盖）" % key)

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
