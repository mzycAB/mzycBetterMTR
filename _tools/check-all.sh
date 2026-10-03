#!/usr/bin/env bash
# 一键跑完所有离线回归（都不需要开游戏）。
#
# 前置：
#   1) 先跑过一次构建（要有 build/classes/java/main）：`gradlew build --offline -x test`
#   2) _tools/runtime.cp 有效。**工程被搬过目录后这个文件会静默失效**（里面的绝对路径
#      还指向旧位置，报错却是「程序包 net.minecraft.* 不存在」）。重建方式：
#        gradlew -I _tools/dumpcp.gradle dumpRuntimeClasspath   # 取 CLASSPATH_START~END 之间那行
#
# 覆盖：
#   check-mixin-plugin-safety.py  Mixin 准备阶段不得加载类（会崩别的模组的 mixin）
#   check-mixin-package-refs.py   mixin 包里只许放 @Mixin 类与插件类
#                                 ★ 2026-10-01 真事故：普通工具类住进 mixin 包 ⇒
#                                   目标类（mtr.data.LiftServer）的注入处理器一引用它就
#                                   IllegalClassLoadError ⇒ MTR3 世界一加载就崩
#   check-psd-anchor.py           屏蔽门锚点几何 + 开关门跳变判定
#   check-psd-nbt.py              存档字段「写/读」成对 + 数值夹取
#   check-psd-tone.py             屏蔽门提示音素材（三段内置 + 两层回落 + 指令/UI/同步）
#   check-psd-align.py            屏蔽门关门提示音「结尾对齐门关上那一刻」**路 A**（门速实测 + 剪头算式）
#                                 ★ 兜底主干：default-s / 素材无两段结构 / 路 B 没排上的兜底
#   check-psd-split.py            屏蔽门关门提示音「语音播报 + 嘀嘀**整段提前播**、结尾正好落在门上」
#                                 （分界点实测 + 提前量算术 + 顺序证明 + 两条路的互斥与兜底）
#   check-psd-predict.py          ★ 提前量**状态机**逐 tick 跑一遍（算术对了不等于时序对）：
#                                 同一扇门 / **跨站换门** / 停站太短 / 停站变长 / 借来的周期偏长
#                                 —— 用户报「停站很久也只有嘀嘀」就出在这里
#                                 第 7 节：**MTR 时刻表**那一层（停站时长 → 周期，不等实测；
#                                 A=10s / B=20s / C=30s 的逐站结果与「结尾落在门上」）
#                                 第 8 节：★「改成 30 秒也没用」那三处 ——
#                                 第一道闸门（announce/splitMs）不许再静默 return、
#                                 跨门借用要喊出来、时刻表×实测交叉核对、
#                                 认站台从「到中点」改成「到中轴**线段**」（含数值对撞）
#                                 第 9 节：★★「改了停留时间还是 10000ms」取证 ——
#                                 站台清单（车站名+坐标+停留时长，「从头没改过」标出来）、
#                                 被认亲上限挡掉的最近站台也要报、
#                                 以及**直接算出**「这一站至少要设多少秒」（防死数）
#   check-psd-volume.py           【1.22】音量 1~1000（三个指令 + UI 两个框 + 底部合并同行）
#                                 + 距离淡入淡出（每 tick 现算）+ 列车内 -80% + 列表 ≥5 行，
#                                 ★ 核心：两条同步包的**读写序逐格一致**（错位不报错，只串字段）
#                                 + 打包名（archives_base_name 真的应用了 ⇒ jar 叫 mzycBetterMTR-*）
#   check-psd-broadcast.py        【1.26】到站播报 / 进站报站 = **一串门的站台广播**：
#                                 串跨度 55 格 vs 射程 16 格（症状数值化）、一串只排一条（键 = runKey）、
#                                 距离与声源取「本串里离玩家最近的那一扇」、哨兵 Long.MIN_VALUE
#                                 按 asLong 位布局复算它不可达、以及最近门选择的纯逻辑复算
#   check-psd-platform-group.py   【1.27】串身份必须优先落到 **MTR 站台**上（不能停在连通串）：
#                                 LOG5 取证（同站台 3~4 个串 / z=72 单串对照 / 缺口 9 格）、
#                                 站在中段时前后两段增益 0.0625（听觉=静音）vs 中段 0.875、
#                                 站台身份 Long.MIN_VALUE+id 与 asLong 不撞键的数值证明、
#                                 认到才缓存 + 2 秒节流重试 + 换维度作废、1.26 的机器保持原样
#   check-psd-128.py              【1.28】LOG6 两个静默炸点：身份要**每次刷新**迁移（不是建表写死）、
#                                 进站报站与到站播报共用一份身份、列车内倍率改**阶跃**（删 20 tick 斜坡）
#   check-psd-scope.py            【1.29】两类声音的归属范围：播报按整族（门+幕墙+尾部）、
#                                 铃声只认门（幕墙/尾部永不发声）—— 四条链路一起守
#   check-psd-131.py              【1.31】端头两扇门「借」最近已认站台的身份（≤12 格、次近 ≥8 才算唯一近邻）、
#                                 列车倍率移到**平滑之后**乘（只平滑距离、进出列车立即 -80%/恢复 100%）
#   check-train-announce.py       【09-28】进站那两条广播必须**各判各的开关**：
#                                 讲述人（文字转语音念站名，走 MTR 报站用的 text2speech 入口）
#                                 ≠ 自定义进站广播（音频素材）；「不播」只关后者、
#                                 /jsr off 只关前者，两条可同时存在、只共用「提前 N 秒」这个窗口。
#                                 用户报「设了不播之后讲述人又没声音了」就出在这里
#   check-psd-midium-once.py      【10-01】LOG013「站台讲述人同一份话念两遍」：
#                                 ① 排计划那道闸的键必须与写表/放回表**同一个**（都是 runKey）；
#                                 ② 起播后必须把记录**放回表里**（旧实现「起播即摘表」把守卫窗口
#                                    压成 0 ⇒ 同一串后开门的门能再念一遍）；
#                                 ③ 铃声距离基准收窄到**同一 MTR 站台**（nearestOnPlatform），
#                                    播报仍按车站级（传 PLATFORM_ID_NONE）；
#                                 ④ 纯逻辑复现器：LOG013 那两对（隔 2 tick / 8 秒）必须只念 1 次；
#                                    删掉「放回表里」⇒ 必须复现成 2 次。脚本自带对照实验。
#   check-escalator-step-engine.py 【1.26】扶梯阶梯渲染引擎（/mtrxr）：
#                                 ★ 根因「/mtrxr off 后阶梯整片消失、退重进才回来」——
#                                 apply() 只许 invalidate()/**不得** reset()（LOADED 登记表被清空
#                                 而 CHUNK_LOAD 不会重触发 ⇒ 索引永久空）+ tick() 自愈重登记；
#                                 分段稀疏索引 / 分段级距离+视锥剔除 / 单趟解析 / 零分配桶 /
#                                 预烘焙 stopped / CPU 背面剔除（ε=0.02）/ 性能计数 statsLine；
#                                 反面对照 + 字节码精确常量池复核（剥注释再判）
#                                 【1.27】段包围盒必须是**紧致盒**（旧的整块 16³ 盒子
#                                 ⇒ 视锥剔除形同虚设）；顶点模板 rotatedFor/vScaledFor +
#                                 逐方块平移（替代逐顶点矩阵乘法，含**数值等价实测**）；
#                                 性能计数拆「剔除趟 / 写顶点趟」+ 顶点数；
#                                 周期性全量重扫 → 每 tick 切片（rescanSlice，反面对照 rescanAll）；
#                                 statsLine 的格式占位符数 == 实参数（防 /mtrxr 抛异常）
#                                 【1.28】遮挡剔除：借原版 LevelRenderer.visibleSections
#                                 （原版 renderSectionLayer 就是遍历它画地形的）判「这帧画不画」，
#                                 补上「在视锥里、在视距内、但被墙挡住」这一类；
#                                 ★ 空集合必须回退视锥剔除（不得当成「全被挡住」）；
#                                 Section 存段键 + /mtrxr occ on|off 逃生开关（默认开）
#   check-escalator-light-repair.py 【1.27】修**原版 MTR 自带**的「扶梯贴着完整方块 ⇒ 贴图变黑」：
#                                 ★ 根因双验（MTR 字节码 BlockEscalatorSide.getCullingShape2 ==
#                                 VoxelShapes.empty ⇒ 面永不剔除；原版 shouldRenderFace 的
#                                 isEmpty → return true 分支）+ 取光在邻格（邻块实心 ⇒ 光 0 ⇒ 黑；
#                                 AO 的 isSolidRender 保护会被 flags.get(0) 旁路 ⇒ 两条路都踩）；
#                                 【1.27b】判据改成只看「采样值 == 0」（不再自己推断采样格被埋，
#                                 前一版推断差一格就静默失效）+ 扶梯自己格也黑时交回原版；
#                                 ★ 重入闸 IN_REPAIR：2 参 getLightColor 的字节码就是转调 3 参，
#                                 而 3 参正被 mixin 挂着 ⇒ 不设闸无限递归（开闸必须先于取光、finally 复位）；
#                                 ★ 三处 mixin（AO 两端 + 平面端）描述符与 MC 字节码逐字对撞、
#                                 refmap 真 remap 到 class_778$class_4303 / $class_780 / class_761；
#                                 ★ 反面对照：不打遮挡形状（那会换来 X 光）、不覆盖 escalator 模型
#   check-escalator-ui-twocol.py  【09-27】扶梯三页 UI 也改成「这样」：
#                                 主界面四行 = 直梯一级菜单同款 [按钮] 标签 [框]、
#                                 两个子界面（选择扶梯音乐 / 选择无障碍提示音）= 左右两列列表；
#                                 几何唯一源 SoundListLayout + 底部预留 44 ⇒ 240 px 下正好 6 行、
#                                 速度框/音量框语义不许混、不许再有灰字小字。
#                                 ★【五改】删「阶梯速度对齐扶梯速度」与主界面「无障碍：开/关」；
#                                 声音/提示音设置的音量框搬到按钮**右侧**；主界面按钮一律 200 宽；
#                                 声音设置二级菜单加「不播」（哨兵 FUTI_AUDIO_OFF：放行 → 播放端短路）；
#                                 提示音开关搬进提示音二级菜单右列第 3 行（仍发 SET_HELP_CHANNEL）。
#   check-mbm-command.py          【1.53】/MBM music in|delete（原 /dtmusic）+ MBM_Audio 文件夹改名
#   check-mbm-help.py             【1.53】/MBM help 打开的「预设选择」界面：三个港铁预设（与用户清单逐字比对）+
#                                 【1.58】★ 层判据：「不播」必须写 none（素材层）不许写 off（会落到子开关，
#                                 导致之后配任何素材都不出声）+「要出声」的预设必须显式开 PSD 子开关；
#                                 以及全音量；【1.54】界面不许有小字
#   check-1.55-sync.py            【1.55】三个界面右上角「同步所有」按钮 + 弹窗：
#                                 五处入口的域/射程/身份/落地回调逐个比对、弹窗 3 按钮与回调、
#                                 写读序逐格配对、ESC/取消回原界面、
#                                 ★★「同步所有」那一支绝不许调 force*/*All setter（= 改过的项保持不动）
#   check-1.57-train-siding.py    【1.57】「列车音效」二级界面 + **侧线身份**：
#                                 撤销 `/MBM train music` ⇒ 改**石斧右键侧线轨道**打开、
#                                 方块判据 mtr:rail（看命名空间，别撞原版 minecraft:rail）、
#                                 MtrSidingAccess（getFacingRailAndBlockPos + isSiding + 两端点落袋）、
#                                 二级页两列列表（版式唯一源 SoundListLayout）、
#                                 右列第 0 行「MTR自带音效」**只有选用没有删除**、
#                                 底部淡入淡出秒数（默认 1 / 上限 60 / 越界当非法不夹取）、
#                                 ★ 界面只许「标题 + 音量」两种文字（绘制调用点 == 2）、
#                                 骨架底线（翻页不发包 / 选用不假装成功 / syncTrain 不回「已同步」）
#   check-audio-category.py        【1.28】音频分类隔离：17 个分类常量（含列车五项 train/*、闸机两项 zhaji/*）、
#                                 界面按分类取数、
#                                 DELETE / IMPORT_PSD_MIDIUM 两个通道带 category、同步包按分类结构、
#                                 列车五页 ↔ 五分类同序、老分类键/文件夹双迁移、
#                                 补全带分类（只影响 1.20.4；1.20.1 / Forge-1.20.1 无此功能）
#   check-zhaji-tone.py           【09-30】**闸机（MTR Ticket Barrier）提示音**：
#                                 石斧右键闸机开「仿直梯」界面（一级两行 + 1~1000 音量框、二级两列列表）、
#                                 指令 /zhaji in|out XXX 与 /zhajiloud（1~1000）、
#                                 MBM_Audio/zhaji/in|out 两个分类；
#                                 ★★ 四条静默炸点：① 4 个 NBT 字段读写成对（漏一个 = 设置悄悄丢）；
#                                 ② 粒度只有「维度默认」一层（不许有 key 表 / 子开关层）；
#                                 ③ 方块判据是**精确**注册名（startsWith 会连带命中 ticket_processor_*）；
#                                 ④ 同步包 ZHAJI_TONE_SYNC_CHANNEL **独立**一条、读写逐格同序
#                                 （错位不报错，只是 in/out 串字段）；
#                                 以及播放端拦 mtr:ticket_barrier(_concessionary) 时必须
#                                 「default 放行原声 / off 吃掉 / 自定义播自己的」，
#                                 音量上限**成对**放开（GainManagedSound + AL_MAX_GAIN）
#   check-open-folder.py          【09-29】所有界面右上角「同步所有」**左边**那个「打开文件夹」按钮：
#                                 一级菜单开「组」目录（MBM_Audio/pbm…）、二级页开「分类」子目录
#                                 （MBM_Audio/pbm/arrive…），
#                                 ★★ 按钮开的目录必须 == 那一页列表读的目录（分类映射只有一份）、
#                                 整排右上角按钮的几何只有 SyncPopupScreen.ENTRY_* 一处定义、
#                                 路径白名单 MBM_(Audio|Picture)(/[a-z]+)* 整串匹配（挡 .. / 绝对路径）、
#                                 通道 MBM_OPEN_FOLDER_CHANNEL 写读逐格配对、
#                                 /MBM picture fold 是字面量且落在 picture 那一支
#   check-lift-chime.py           直梯提示音：开关/倍速/音量链路 + 同步包读写顺序配对
#   check-lift-tone.py            直梯四提示音（up/down/open/close）素材：数据/指令/UI/播放端
#   check-lift-move-sound.py      直梯「准备移动」up.ogg / down.ogg 的触发判据与素材选择
#   check-fade-curves.py          ★ 全项目「淡入淡出范围」的距离曲线一律**按比例线性** (1 - d/R)
#                                  （扶梯底噪 / 扶梯提示音 / 直梯提示音 / 屏蔽门三类，四份源码逐份钉）
#                                  2026-10-03 用户点名统一；讲述人 TTS 不参与（无音量接口）
#   check-chime-tiers.py          无障碍提示音分档素材（5 档 pitch）
#   check-lift-track-look.py      直梯楼层轨道外观
#   check-command-tree.sh         指令树可达性 / 补全 / 边界拒绝 / 别名同形
set -u
cd "$(dirname "$0")/.."

PY="C:/Users/user/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
if [ ! -x "$PY" ]; then PY="python"; fi

TMP="$(mktemp -d)"
rc=0

run() {
    local name="$1"; shift
    echo
    echo "############################################################"
    echo "# $name"
    echo "############################################################"
    if "$@" > "$TMP/$(echo "$name" | tr -cd 'a-zA-Z0-9').log" 2>&1; then
        echo "  ==> 通过"
    else
        echo "  ==> 失败（完整输出见下方）"
        cat "$TMP/$(echo "$name" | tr -cd 'a-zA-Z0-9').log"
        rc=1
    fi
}

run "mixin 插件安全"        "$PY" _tools/check-mixin-plugin-safety.py
run "mixin 包守卫(类)"      "$PY" _tools/check-mixin-package-refs.py
run "屏蔽门锚点与跳变"      "$PY" _tools/check-psd-anchor.py
run "屏蔽门存档字段"        "$PY" _tools/check-psd-nbt.py
run "屏蔽门提示音素材"      "$PY" _tools/check-psd-tone.py
run "屏蔽门关门提示音对齐"  "$PY" _tools/check-psd-align.py
run "屏蔽门提示音顺序"      "$PY" _tools/check-psd-split.py
run "屏蔽门提前量状态机"    "$PY" _tools/check-psd-predict.py
run "屏蔽门音量与淡入淡出"  "$PY" _tools/check-psd-volume.py
run "屏蔽门站台广播"        "$PY" _tools/check-psd-broadcast.py
run "屏蔽门串身份=站台"     "$PY" _tools/check-psd-platform-group.py
run "屏蔽门1.28身份迁移与列车倍率" "$PY" _tools/check-psd-128.py
run "屏蔽门铃声/播报归属"   "$PY" _tools/check-psd-scope.py
run "屏蔽门1.31借用与列车挡" "$PY" _tools/check-psd-131.py
run "屏蔽门讲述人/自定义拆开" "$PY" _tools/check-train-announce.py
run "屏蔽门讲述人只念一次"  "$PY" _tools/check-psd-midium-once.py
run "讲述人默认值/预设/按存档文字" "$PY" _tools/check-narrate-preset-save.py
run "讲述人香港档与UI清全局"  "$PY" _tools/check-narrate-ui-hk.py
run "扶梯阶梯渲染引擎"      "$PY" _tools/check-escalator-step-engine.py
run "扶梯黑面修复(原版MTR)" "$PY" _tools/check-escalator-light-repair.py
run "扶梯黑面修复(Sodium兼容)" "$PY" _tools/check-sodium-light-repair.py
run "扶梯三页UI两列版式+五改"    "$PY" _tools/check-escalator-ui-twocol.py
run "MBM批量音频指令"       "$PY" _tools/check-mbm-command.py
run "音频分类隔离"          "$PY" _tools/check-audio-category.py
run "闸机提示音"            "$PY" _tools/check-zhaji-tone.py
run "打开文件夹按钮"        "$PY" _tools/check-open-folder.py
run "预设选择界面与预设" "$PY" _tools/check-mbm-help.py
run "同步所有按钮与弹窗" "$PY" _tools/check-1.55-sync.py
run "列车音效界面与侧线" "$PY" _tools/check-1.57-train-siding.py
run "直梯提示音链路"        "$PY" _tools/check-lift-chime.py
run "直梯四提示音素材"      "$PY" _tools/check-lift-tone.py
run "直梯准备移动音"        "$PY" _tools/check-lift-move-sound.py
run "淡入淡出全按比例"      "$PY" _tools/check-fade-curves.py
run "无障碍提示音分档"      "$PY" _tools/check-chime-tiers.py
run "直梯楼层轨道外观"      "$PY" _tools/check-lift-track-look.py
run "指令树"                bash _tools/check-command-tree.sh
run "PIDS 站台名掩码"      "$PY" _tools/check-pids-name-mask.py
run "关门等待发车"          "$PY" _tools/check-psd-depart.py

echo
echo "############################################################"
if [ "$rc" -eq 0 ]; then
    echo "# 全部离线回归通过"
else
    echo "# 有回归失败，见上面明细"
fi
echo "############################################################"
rm -rf "$TMP"
exit "$rc"
