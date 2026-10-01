# -*- coding: utf-8 -*-
"""离线校验：【1.28】音频分类隔离（每个设置项只读自己分类的子文件夹）。

## 设计要点（脚本要钉住的不变量）

1. **分类常量齐全**：17 个分类（futi/music 底噪、futi/help 提示音、
   train run/round/switch/in/out 列车五项、pbm open/close/midium/arrive、
   lift up/down/open/close、zhaji in/out 闸机两侧），全部在 ALL_CATEGORIES 里；
   分类 = 子文件夹名（MBM_Audio/<分类>）。
   ★ 目录 2026-09-27 改版（第一版）：扶梯两类提到 MBM_Audio/futi 下；
     futi/ 只是**分组目录**、本身不是分类（判据：没有任何分类字符串恰好 == "futi"）⇒ 里面不放 ogg。
   ★★ 同日二次改版：旧的 `pbm/music`（列车音效）**已删**，拆成 `train/run|round|switch|in|out`
     五项（`train/` 同样只是分组目录）；老键 `pbm/music` 由 migrateCategoryKey 迁到 `train/run`。
   ★【09-30】新增闸机两类 `zhaji/in|out`（分组目录 `zhaji/`）——这是**纯新增**，
     没有任何旧键要迁（闸机提示音是这一版才有的功能）。
   旧存档里的旧键（pbm/futi、pbm/help、pbm/music、以及一轮中间的 futi）由 migrateCategoryKey 迁到新键；
   屏蔽门 / 直梯路径未动，无需迁移。
2. **客户端两列表按分类取**：每个界面 init 里 getClientAudioLibraryKeys / getClientFolderAudioKeys
   都带自己分类参数（主界面无列表的除外）—— 漏传分类 = 静默回到空表，界面看着像「没有音频」。
3. **DELETE_AUDIO 包带分类**：服务端先 readUtf(64) 分类再 readUtf(128) 名字；发送方（四个界面）
   都先写分类 —— 漏写 = 读序错位，删的是别的分类（界面列表还会残着）。
4. **IMPORT_PSD_MIDIUM_AUDIO_CHANNEL 包带分类**：服务端按包里的分类导入；列车五项
   （CAT_TRAIN_*）与屏蔽门到站/进站页（CAT_PSD_MIDIUM / CAT_PSD_ARRIVE）共用这一条纯导入通道 ——
   不带分类就会导进错误分类、界面右列不出现。
5. **同步包按分类结构**：buildAudioSyncPayload 写「分类数 → (分类 → 待导入数 → 名字×N →
   已导入数 → 名字×N) × 分类数」；客户端接收器按同一顺序读 —— 结构错位不报错，列表会乱/空。
6. **列车五页 ↔ 五分类同序**：TrainSoundScreen 的 LABELS / PAGE_SCOPES / PAGE_CATEGORIES
   三者必须同序（运行→run、转弯→round、道岔→switch、进站→in、出站→out）—— 错位不报错，
   只是列表读错文件夹（或同步到另一项）。

用法：`python _tools/check-audio-category.py`（退出码 0 = 全部通过）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
SL = os.path.join(MAIN, "SmoothLift.java")
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
SLC = os.path.join(CLIENT, "SmoothLiftClient.java")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//[^\n]*", " ", src)
    return src


mgr = read(MGR)
sl = read(SL)
data_src = read(DATA)
client = read(SLC)
mgr_no = strip_comments(mgr)
sl_no = strip_comments(sl)
data_no = strip_comments(data_src)
client_no = strip_comments(client)

print("== 1. 分类常量 ==")
CATS = ["CAT_FUTI", "CAT_HELP",
        "CAT_TRAIN_RUN", "CAT_TRAIN_ROUND", "CAT_TRAIN_SWITCH", "CAT_TRAIN_IN", "CAT_TRAIN_OUT",
        "CAT_PSD_OPEN", "CAT_PSD_CLOSE", "CAT_PSD_MIDIUM", "CAT_PSD_ARRIVE",
        "CAT_LIFT_UP", "CAT_LIFT_DOWN", "CAT_LIFT_OPEN", "CAT_LIFT_CLOSE",
        "CAT_ZHAJI_IN", "CAT_ZHAJI_OUT"]
check(all(c in mgr_no for c in CATS), "17 个分类常量都在 Manager 里定义")
check('ALL_CATEGORIES' in mgr_no, "ALL_CATEGORIES 数组存在")
check('"futi/music"' in mgr_no and '"futi/help"' in mgr_no
      and '"train/run"' in mgr_no and '"train/out"' in mgr_no,
      "futi/music=底噪、futi/help=提示音、train/*=列车五项（2026-09-27 二次改版）")
_pbm_music_hits = mgr_no.count('"pbm/music"')
check(_pbm_music_hits == 2,
      '★ 旧值 "pbm/music" 只作为迁移字面量出现 ×2（migrateCategoryKey + LEGACY_FOLDERS），不再当分类用',
      "命中 %d 次" % _pbm_music_hits)
_cats_declared = re.findall(r'public static final String (CAT_[A-Z_]+)\s*=\s*"([^"]*)"', mgr_no)
_vals = set(v for _, v in _cats_declared)
check("futi" not in _vals and "train" not in _vals and "pbm" not in _vals
      and "lift" not in _vals and "zhaji" not in _vals,
      '★ futi/ train/ pbm/ lift/ zhaji/ 只是分组目录：没有任何分类恰好等于分组名（里面不放 ogg）',
      "分类值 = %s" % sorted(_vals))
check(_vals == {"futi/music", "futi/help",
                "train/run", "train/round", "train/switch", "train/in", "train/out",
                "pbm/open", "pbm/close", "pbm/midium", "pbm/arrive",
                "lift/up", "lift/down", "lift/open", "lift/close",
                "zhaji/in", "zhaji/out"},
      "17 个分类值 = 目录树（futi/* ×2、train/* ×5、pbm/* ×4、lift/* ×4、zhaji/* ×2）",
      "实际 = %s" % sorted(_vals))
check('"pbm/open"' in mgr_no and '"pbm/close"' in mgr_no
      and '"pbm/midium"' in mgr_no and '"pbm/arrive"' in mgr_no,
      "屏蔽门四分类仍在 pbm/ 下")
check('"lift/up"' in mgr_no and '"lift/open"' in mgr_no,
      "直梯四分类仍在 lift/ 下")
check('"zhaji/in"' in mgr_no and '"zhaji/out"' in mgr_no,
      "★【09-30】闸机两分类在 zhaji/ 下（进站 / 出站各一个子文件夹）")
# ★ 旧分类键迁移：改名后旧存档那几个分类的「已存入」注册表要能接上（否则列表看着像没导入过）。
_mig = re.search(r"public static String migrateCategoryKey\(String legacyKey\)\s*\{(.*?)\n    \}",
                 mgr_no, re.S)
_mig_body = _mig.group(1) if _mig else ""
check(_mig_body.count("equals(legacyKey)") == 4
      and '"pbm/futi"' in _mig_body and '"futi"' in _mig_body
      and '"pbm/help"' in _mig_body and '"pbm/music"' in _mig_body
      and "CAT_FUTI" in _mig_body and "CAT_HELP" in _mig_body and "CAT_TRAIN_RUN" in _mig_body,
      "migrateCategoryKey：pbm/futi|futi → CAT_FUTI、pbm/help → CAT_HELP、pbm/music → CAT_TRAIN_RUN")
check('"futi/music".equals(legacyKey)' not in _mig_body,
      "★ 迁移表**不含** futi/music → train/run（否则底噪数据每读档一次就被误迁，往返 bug）")
check("migrateCategoryKey(cat)" in data_no,
      "读档 audioCategoryNames 的键走 migrateCategoryKey（旧存档不丢「已存入」）")
# ★ 老文件夹搬移（文件侧）：启动时把老目录里的 .ogg 搬到新分类目录，且只搬 .ogg / 不覆盖 / 搬空才删。
check("LEGACY_FOLDERS" in mgr_no and "migrateLegacyAudioFolder" in mgr_no,
      "★ 老分类**文件夹**迁移存在（文件侧与键迁移配套）")
check('{"pbm/futi", CAT_FUTI},' in mgr_no and '{"pbm/help", CAT_HELP},' in mgr_no
      and '{"pbm/music", CAT_TRAIN_RUN},' in mgr_no,
      "老文件夹表：pbm/futi→CAT_FUTI、pbm/help→CAT_HELP、pbm/music→CAT_TRAIN_RUN")
check('endsWith(".ogg")' in mgr_no and "Files.move(" in mgr_no
      and "Files.exists(target)" in mgr_no and "Files.deleteIfExists(oldDir)" in mgr_no,
      "老文件夹迁移只搬 .ogg、同名不覆盖、搬空才删源目录（绝不递归删）")
m = re.search(r"public static final String\[\] ALL_CATEGORIES\s*=\s*\{(.*?)\};", mgr_no, re.S)
if m:
    listed = re.findall(r"CAT_[A-Z_]+", m.group(1))
    check(listed == CATS, "ALL_CATEGORIES 顺序 = 全部 17 个（与同步包书写顺序一致）",
          "实际 %s" % listed)
check('CAT_MUSIC' not in mgr_no,
      "★ 常量名 CAT_MUSIC 已彻底移除（换成 CAT_TRAIN_RUN 等五个）")
check("audioCategoryNames" in mgr_no and "folderByCategory" in mgr_no,
      "数据层：分类 → 已导入注册表 + 分类 → 待导入镜像（取代旧平铺字段）")

print("\n== 2. 客户端两列表按分类取 ==")
screen_files = sorted(f for f in os.listdir(CLIENT) if f.endswith("Screen.java"))
for f in screen_files:
    src = read(os.path.join(CLIENT, f))
    if "getClientAudioLibraryKeys(" not in src and "getClientFolderAudioKeys(" not in src:
        continue  # 没有音频列表的界面不在此列
    check("getClientAudioLibraryKeys(mc.level," in src
          and "getClientFolderAudioKeys(mc.level," in src,
          "%s 的两列表都带分类参数" % f)
    # 主界面无列表的界面要显式「page > 0 才加载」；其余无条件加载但要带分类
    if "page > 0" in src or "categoryForPage" in src or "categoryFor" in src:
        check(True, "%s 有按页取分类的逻辑" % f)
    else:
        check(True, "%s 无条件加载（但已带分类）" % f)

print("\n== 3. DELETE_AUDIO 包带分类 ==")
# 服务端：先读分类再读名字（从通道注册点一路截到下一个 registerGlobalReceiver）
_del_start = sl_no.find("registerGlobalReceiver(DELETE_AUDIO_CHANNEL")
_del_end = sl_no.find("registerGlobalReceiver(", _del_start + 10) if _del_start >= 0 else -1
recv = sl_no[_del_start:_del_end] if _del_start >= 0 else ""
check('category = buf.readUtf(64)' in recv and 'audioId = buf.readUtf(128)' in recv,
      "服务端 DELETE 接收器先读分类(utf64) 再读名字(utf128)")
check("deleteAudio(level, category, audioId)" in recv,
      "按分类删（deleteAudio(level, category, id)）")
# 客户端发送点都要先写分类
for f in ("AudioSetupScreen.java", "HelpAudioSetupScreen.java",
          "PsdToneSetupScreen.java", "TrainSoundScreen.java",
          "ZhajiToneSetupScreen.java"):
    src = read(os.path.join(CLIENT, f))
    if "DELETE_AUDIO_CHANNEL" not in src:
        continue
    i = src.find("ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL")
    ctx = src[max(0, i - 350):i]
    writes = re.findall(r"writeUtf\(([^,]+),\s*(\d+)\)", ctx)
    check(len(writes) >= 2 and writes[-2][1] == "64",
          "%s：DELETE 发送 = writeUtf(分类,64) + writeUtf(名字,128)" % f,
          "前两个写 %s" % writes[:2])

print("\n== 4. IMPORT_PSD_MIDIUM_AUDIO_CHANNEL 带分类 ==")
_mid_start = sl_no.find("registerGlobalReceiver(IMPORT_PSD_MIDIUM_AUDIO_CHANNEL")
_mid_end = sl_no.find("registerGlobalReceiver(", _mid_start + 10) if _mid_start >= 0 else -1
recv = sl_no[_mid_start:_mid_end] if _mid_start >= 0 else ""
check('category = buf.readUtf(64)' in recv and 'name = buf.readUtf(128)' in recv,
      "服务端纯导入接收器先读分类(utf64) 再读名字(utf128)")
check("importAudioToStore(level, category, name)" in recv,
      "按包里的分类导入（不再写死 CAT_PSD_MIDIUM）")
for f, probe in (("TrainSoundScreen.java", "currentCategory()"),
                 ("PsdToneSetupScreen.java", "categoryForPage")):
    src = read(os.path.join(CLIENT, f))
    i = src.find("IMPORT_PSD_MIDIUM_AUDIO_CHANNEL")
    ctx = src[max(0, i - 350):i] if i >= 0 else ""
    check(i >= 0 and probe in ctx, "%s：纯导入发送点带分类（%s）" % (f, probe))

print("\n== 5. 同步包按分类结构（写侧 == 读侧） ==")
m = re.search(r"private static byte\[\] buildAudioSyncPayload\((.*?)\n    \}", mgr, re.S)
w = m.group(1) if m else ""
check("writeVarInt(ALL_CATEGORIES.length)" in w, "写侧：先写分类数")
check("scanAudioFiles(level, category)" in w, "写侧：每个分类扫自己的子文件夹")
check("data.audioCategoryNames.getOrDefault(category, Set.of())" in w,
      "写侧：每个分类写已导入注册表")
r_m = re.search(r"int categoryCount = data.readVarInt\(\);(.*?)\n            int bindCount", client, re.S)
r = r_m.group(1) if r_m else ""
check(bool(r) and "folderByCategory.put(category, folder)" in r
      and "audioCategoryNames.put(category, imported)" in r,
      "读侧：分类数 → 按分类读待导入 + 已导入两表")
check("applyClientAudioData(dimKey, audioLibrary, folderByCategory," in client,
      "读侧把两张分类表喂给 applyClientAudioData（新签名）")

print("\n== 6. 指令补全按分类 ==")
for fn in ("liftToneNameCandidates", "psdNameCandidates", "psdMidiumSuggestions",
           "psdArriveSuggestions"):
    m = re.search(r"public static List<String> %s\(ServerLevel level, String category\)" % fn,
                  mgr_no)
    check(m is not None, "%s 带分类参数" % fn)
check("getServerAudioLibraryKeys(ServerLevel level, String category)" in mgr_no
      and mgr_no.count("public static Set<String> getServerAudioLibraryKeys") == 1,
      "getServerAudioLibraryKeys 只定义一次（带分类）")

print("\n== 7. ★ UPLOAD 死通道已删除（对齐 Forge；防泄漏路径复活） ==")
# 【1.7】的分块上传通道曾是分类隔离的泄漏路径：storeAudio 只写 audioLibrary、
# 不写 audioCategoryNames ⇒ 音频「悬浮在分类外」、任何界面「已导入」看不到。
# 客户端早已零发送（界面全走 IMPORT_FOLDER 系列）⇒ 2026-09-27 已连根删除
# （通道定义 + 接收器 + handleAudioUploadChunk + storeAudio + PENDING_UPLOADS）。
# Forge-1.20.1 移植时就没要这条通道。★ 钉死「不得复活」。
check("UPLOAD_AUDIO_CHANNEL" not in sl_no and "UPLOAD_AUDIO_CHANNEL" not in client_no,
      "★ UPLOAD_AUDIO_CHANNEL 已从服务端 + 客户端**彻底删除**（复活 = 绕过分类注册表入库）")
check("handleAudioUploadChunk" not in mgr_no and "storeAudio" not in mgr_no
      and "PENDING_UPLOADS" not in mgr_no and "MAX_AUDIO_NAME" not in mgr_no,
      "★ 上传链（handleAudioUploadChunk / storeAudio / PENDING_UPLOADS / MAX_AUDIO_NAME）零残留")
check("AUDIO_CHUNK_SIZE" in mgr_no,
      "★ AUDIO_CHUNK_SIZE 保留（同步分包还在用，与上传链无关）")

print("\n== 8. ★ 列车五页 ↔ 五分类同序（运行/转弯/道岔/进站/出站） ==")
# 【09-27 二次改版】列车音效由「五项共用一个 pbm/music」拆成 train/run|round|switch|in|out。
# 三个数组必须**同序**：LABELS[页-1] / PAGE_SCOPES[页-1] / PAGE_CATEGORIES[页-1]。
# 错位不报错 —— 只是列表读错文件夹、或「同步所有」同步到另一项。
tss = read(os.path.join(CLIENT, "TrainSoundScreen.java"))
_labels = re.search(r"String\[\] LABELS\s*=\s*\{(.*?)\};", tss, re.S)
_label_items = re.findall(r'"([^"]+)"', _labels.group(1)) if _labels else []
check(_label_items == ["列车运行音效", "列车转弯音效", "列车道岔音效", "列车进站音效", "列车出站音效"],
      "一级页 5 个按钮文字 = 运行/转弯/道岔/进站/出站（顺序钉死）",
      "实际 %s" % _label_items)
_cats_arr = re.search(r"String\[\] PAGE_CATEGORIES\s*=\s*\{(.*?)\};", tss, re.S)
_cat_items = re.findall(r"CAT_[A-Z_]+", _cats_arr.group(1)) if _cats_arr else []
check(_cat_items == ["CAT_TRAIN_RUN", "CAT_TRAIN_ROUND", "CAT_TRAIN_SWITCH",
                     "CAT_TRAIN_IN", "CAT_TRAIN_OUT"],
      "PAGE_CATEGORIES 五项同序 = run/round/switch/in/out（与按钮文字一一对应）",
      "实际 %s" % _cat_items)
_scopes = re.search(r"int\[\] PAGE_SCOPES\s*=\s*\{(.*?)\};", tss, re.S)
_scope_items = re.findall(r"SYNC_TRAIN_[A-Z_]+", _scopes.group(1)) if _scopes else []
check(len(_label_items) == 5 and len(_cat_items) == 5 and len(_scope_items) == 5,
      "LABELS / PAGE_CATEGORIES / PAGE_SCOPES 都是 5 项（同序下标）",
      "scopes=%s" % _scope_items)
check("currentCategory()" in tss
      and tss.count("getClientAudioLibraryKeys(mc.level, category)") == 1
      and tss.count("getClientFolderAudioKeys(mc.level, category)") == 1,
      "两列表都按 currentCategory() 取（不再写死单一分类）")
check(tss.count("buf.writeUtf(currentCategory(), 64)") == 2,
      "导入 / 删除两处发送都用 currentCategory()（共 2 处）")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
