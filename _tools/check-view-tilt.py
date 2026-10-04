# -*- coding: utf-8 -*-
"""离线校验：【1.31.1204】「列车倾斜时把整个画面转一个角度」（`/mtrqx on|off`）。

## 用户原话（逐句对应）

> 「mtrqx on/off 指令开关玩家视角随着列车倾斜而倾斜，原本的游戏里列车上下坡玩家视角
> 始终平行于地平线，但是列车是倾斜的，模组要修复这个特性，做到玩家视角随着列车倾斜而倾斜的效果。
> 版本号改为 1.31.1204 只修改 fabric1.20.4 版本」

## ★【五改】用户最终口径（四轮澄清后定稿）

> 「视角旋转指的是旋转玩家游戏窗口，**旋转角度=列车倾斜角度**……直接旋转玩家整个游戏窗口。
> 任务栏之类的保持不变。之前的方法错了。但是玩家建模底面平行于列车地板可以保留。」
> 照观感补充（三改原话）：「看向列车侧面车窗外，列车上下坡时，玩家视角对于地面来说应该是斜的，
> 也就是**平行于窗框或者列车地板**。」
> ⇒ ① **只转 3D 世界**（准星 / 物品栏 / HUD 保持竖直）；② 角度 = **列车倾斜角本人，与视线方向无关**；
> ③「玩家模型底面平行于地板」保留。
> ⇒ 实现 = **相机乘 `R^{-1}`**（模型那条乘 `R`，恰好互逆）：
> 屏幕上列车/地板/窗框水平、地面与地平线变斜。§5c 用不变量钉住。

## ★★【四改】错在哪（LOGf8 实锤，本脚本留作反证）

四改把横滚写成「当前视线的函数」（`roll = p·sin(az)` 一族）⇒ 看正前 `roll ≡ 0`（骑在车里默认
就是顺着车头看 ⇒ 用户看到「并没有旋转」），一扭头 roll 在 0…±17° 间摆（⇒「莫名其妙抽搐视角」）。
**根因 = 把「转一个固定角度」写成了「按视线投影」。** 五改已把视线投影整段删除：
`§5c` 的 (d)(e) 与 `§8` 的 mutation 专门守「算式里不许再出现相机视线」。

## 这条功能真正容易错的地方（本脚本守的就是这几条）

1. ★★★ **`@Inject` 处理器的 `static` 必须与目标方法一致**。
   目标 `VehicleRidingMovement.movePlayer(DDD)V` / `sendUpdate(Z)V` 都是 **static**（javap 核过），
   所以那两个处理器必须 `static`；而 `PositionAndRotation.transformForwards/Backwards`
   是**实例**方法，处理器就**不许**带 `static`。不一致时 Mixin 直接拒收注入，
   而 `require = 0` 又让它**完全静默**（不崩、也不生效）——“灯根本没闪”就是这么来的。
2. ★★ **注入点名字必须与 jar 里 MTR 的真实形态逐字对上**（`movePlayer` 在
   `VehicleRidingMovement` 里有**两个重载**，只写名字会歧义 ⇒ 必须写全 `movePlayer(DDD)V`）。
3. ★★ **反馈环上的两条判据缺一不可**：光看「值变新了」不够，还要看「是不是**本线程**刚写的」
   —— 渲染线程也在往同一个变量里写俯仰角（`RenderVehicles` / `RenderLifts` 都调 transform）。
   少了线程判据 ⇒ 读到别的车 / 上一帧的残值 ⇒ 视角乱抖。
4. ★★★【四改】**绝不能再写玩家视角**：用户明确否掉了「拧视线方向」那条路（准星会飘）
   ⇒ `TrainTiltView` 里不许出现 `setXRot` / `setYRot` / `rotateViewDir` / `appliedViewRot` /
   `applyRidePitch`（§5d 源码级反证 + §7 产物级反证 + §8 mutation）。「转画面」只能靠**相机 roll**。
   ★ 这是本功能最容易「回退」的一条：旧实现留下的死代码一旦被人当参考抄回来，用户立刻能看出来。
5. ★ **下车只要认出来就行**（已经不再需要「把加进去的还回去」）：`sendUpdate(true)` /
   连续 N tick 没有骑乘帧 ⇒ 清 `rideActive`，渲染侧下一帧就不再倾斜 / 横滚。
6. **没装 MTR / 装了 MTR3 时必须安静跳过**：两个目标类都是 MTR4 客户端独有，门禁按
   「那个 mixin 自己的目标类在不在」判，且只读类路径资源（绝不用 `Class.forName` —— 那会崩别人的 mixin）。
7. ★★★ **回调参数必须与目标方法的「返回值形态」一致**（本轮真踩）：`transformForwards` /
   `transformBackwards` 是**有返回值**的泛型方法（`<T> T …`，擦除后描述符返回 `Object`）
   ⇒ 处理器必须收 **`CallbackInfoReturnable<Object>`**，写成 `CallbackInfo` 会被 Mixin 判
   `InvalidInjectionException: CallbackInfoReturnable is required!`，而 `require = 0` 让整条 mixin
   **静默拒收**（不崩、不生效）——症状就是「装了 MTR4 但视角纹丝不动、日志一句有用的都没有」。
   判据在 Mixin 本体（`CallbackInjector.inject` 里
   `handler.desc.replace("…/CallbackInfo;", "…/CallbackInfoReturnable;")` 后若 `checkDescriptor`
   命中 ⇒ 当场报错）；泛型实参在描述符里被擦除 ⇒ **只有类名对不对有关系，实参无所谓**。
8. ★★★【二改】**门禁插件的默认分支是 `return false`**（`PsdDoorMixinPlugin.shouldApplyMixin` 末尾）
   ⇒ 新 mixin 只登记进 `smoothlift.psd.mixins.json`、忘了在插件里 `endsWith(...)` 放行，
   会被**默默挡掉**（日志里连「跳过」两个字都没有，因为那个分支根本不会走到）。
   §3 专门钉了这一条。
9. ★★★【二改】玩家**模型**倾斜的几何（`RideTiltPlayerRenderMixin`）：钩
   `LivingEntityRenderer#render` 的 HEAD（那一刻 poseStack 只做了世界平移 ⇒ `mulPose` 是**世界系**
   旋转，旋转中心=实体原点=脚底 ⇒ 脚不动、身体歪），绕**车体横向轴** `r = f × u`
   （`f = (sin yaw, 0, cos yaw)`，MTR 的 `yaw = atan2(Δx,Δz)`）转 `pitch`。
   ★ MTR 的 `yaw`/`pitch` 取的是**同一对路径点** ⇒ 就算两点顺序反了，`f→-f` 且 `pitch→-pitch`，
   `r→-r`、`θ→-θ`，**两次翻转抵消**，矩阵逐字不变 ⇒ 不用知道 yaw 指哪一头。§5b 用不变量断言钉住。
   ★★【五改】注意：模型乘 `R`、相机乘 `R^{-1}` —— 两者**严格互逆**，所以屏幕上「车厢平 + 模型站直」
   同时成立。§5c(c) 直接用矩阵乘积钉这条。
10. ★★★【五改】**相机那条**的注入点与算式（`RideTiltCameraRollMixin`）：
   钩 `GameRenderer.renderLevel` 里**第 4 处** `PoseStack.mulPose(Quaternionf)`（`ordinal = 3`
   = 相机 Y 旋转，offset 523）**之后**（`At.Shift.AFTER`）—— 其后取逆视图矩阵 / 视锥 / 世界 /
   第一人称手持物**共用同一个 poseStack**，所以一起带上这个旋转；HUD 在别的矩阵栈上（调用方
   `pushPose()+setIdentity()` 之后才画 GUI）天然不转。
   **算式 = `poseStack.mulPose(rotateAxis(-pitch, r))`，即 `R^{-1}`，`r = (-cos yaw, 0, sin yaw)`。**
   ★★ **不许再读相机视线**（源码里不许有 `getLookVector` / `Camera` / `atan2`）：读视线 = 退回四改，
   看正前恒 0（用户报「没转」）、扭头乱摆（用户报「抽」）。§4c / §5c / §8 三条一起钉。
11. ★★【五改】「角度 = 列车倾斜角」的直接判据：心跳里**「窗口横滚」必须跟着「列车pitch」一起非零**
   ——LOGf8 里 `列车pitch=8.52°` 而 `窗口横滚=0.00°` 就是「注入在跑但算式恒 0」的铁证。

用法：`python _tools/check-view-tilt.py`（退出码 0 = 全部通过）
"""

import glob
import json
import math
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
MIXIN_DIR = os.path.join(CLIENT, "mixin")
VIEW = os.path.join(CLIENT, "TrainTiltView.java")
CMD = os.path.join(CLIENT, "TrainTiltViewCommand.java")
CLIENT_INIT = os.path.join(CLIENT, "SmoothLiftClient.java")
POS_MIXIN = os.path.join(MIXIN_DIR, "Mtr4RideTiltPositionMixin.java")
MOV_MIXIN = os.path.join(MIXIN_DIR, "Mtr4RideTiltMovementMixin.java")
RENDER_MIXIN = os.path.join(MIXIN_DIR, "RideTiltPlayerRenderMixin.java")
ROLL_MIXIN = os.path.join(MIXIN_DIR, "RideTiltCameraRollMixin.java")
PSD_JSON = os.path.join(ROOT, "src", "client", "resources", "smoothlift.psd.mixins.json")
PLUGIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                      "PsdDoorMixinPlugin.java")
GRADLE_PROPS = os.path.join(ROOT, "gradle.properties")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src):
    """剥掉 // 与 /* */ 注释（保留字符串字面量 —— 目标类名、注入方法名都在字符串里）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


# ======================================================================
# 1) 指令侧：/mtrqx 三支 + 装配三处
# ======================================================================
print("===== 1) 指令侧：/mtrqx（无参 / on / off）与装配点 =====")

client_init = strip_comments(load(CLIENT_INIT))
cmd = strip_comments(load(CMD))
view = strip_comments(load(VIEW))

check('ClientCommandManager.literal("mtrqx")' in client_init, '注册了 /mtrqx 根指令')
check("TrainTiltViewCommand::show" in client_init, "无参 -> 显示当前状态（show）")
check("TrainTiltViewCommand.set(ctx, true)" in client_init, "/mtrqx on -> 开启")
check("TrainTiltViewCommand.set(ctx, false)" in client_init, "/mtrqx off -> 关闭")
check("TrainTiltView.load()" in client_init, "客户端初始化时读回上次的开关（load）")
check("ClientTickEvents.END_CLIENT_TICK.register(TrainTiltView::onClientTick)" in client_init,
      "★ 落地线程：视角写入挂在 END_CLIENT_TICK（主线程），不在渲染线程里改玩家视角")
check("TrainTiltView.onDisconnect()" in client_init,
      "退出世界时复位（帧号 / 基线 / 已加过多少俯仰）")

check("public static int show(" in cmd and "public static int set(" in cmd
      and "public static String apply(" in cmd,
      "命令类三段结构（show / set / 不经过 brigadier 的 apply）—— 与 /mtrxr 同一套")
check("TrainTiltView.name()" in cmd
      and "开启（列车倾斜时整个画面跟着旋转" not in cmd
      and "关闭（与 MTR 原版一致：画面不随列车倾斜）" not in cmd,
      "★ 状态文案只有 TrainTiltView.name() 一份（命令类不另写一份状态字面量，免得两处走样）")
check('"开启（列车倾斜时整个画面跟着旋转，窗外窗框与地板保持水平）"' in view
      and '"关闭（与 MTR 原版一致：画面不随列车倾斜）"' in view,
      "状态文案确实在 TrainTiltView.name() 里（口径已是「转画面」而非「改视线」）")

# ======================================================================
# 2) 配置持久化
# ======================================================================
print()
print("===== 2) config/smoothlift-view.properties 的读法 =====")

view = strip_comments(load(VIEW))
check('"smoothlift-view.properties"' in view, "配置文件名")
check('"trainTiltView"' in view, "properties 键名 trainTiltView")
check('VALUE_OFF.equals(props.getProperty(KEY_ENABLED))' in view,
      "★ 只有明确写成 off 才关（键不存在 / 写坏了都按默认「开」—— 与 EscalatorRenderMode 同一条规矩）")
check("enabled = true" in view, "默认开")
check("Files.newInputStream(file)" in view and "props.load(in)" in view, "读：整份 Properties")
check("props.store(out," in view, "写：整份覆盖写")

# ======================================================================
# 3) mixin 登记 + 门禁
# ======================================================================
print()
print("===== 3) mixin 登记与门禁（没装 MTR4 必须安静跳过） =====")

psd_json = json.loads(load(PSD_JSON))
listed = psd_json.get("client", [])
check("Mtr4RideTiltPositionMixin" in listed, "俯仰角读取那条已登记进 smoothlift.psd.mixins.json")
check("Mtr4RideTiltMovementMixin" in listed, "骑乘帧那条已登记进 smoothlift.psd.mixins.json")
check("RideTiltPlayerRenderMixin" in listed,
      "【二改】玩家模型倾斜那条已登记进 smoothlift.psd.mixins.json（没登记 = 运行期根本不加载）")
check("RideTiltCameraRollMixin" in listed,
      "【三改】相机随列车横滚那条已登记进 smoothlift.psd.mixins.json（没登记 = 运行期根本不加载）")
check(psd_json.get("required") is False,
      "配置 required=false（目标类不在时不许把游戏带崩）")

plugin = strip_comments(load(PLUGIN))
# ★★★ 「放行」的判据必须锚在 **shouldApplyMixin 里的整条 if 分支**（endsWith("…") { return tiltTargetPresent(…); }），
#   不能只 grep 一个裸子串 —— 同一个子串在 postApply 信标的 if 链里也出现一次，
#   裸子串判据在「真分支被删、只剩信标」时仍然为真（假绿）。这是本节最容易漏的一类。
GATE = r'endsWith\("%s"\)\s*\)\s*\{\s*return\s+tiltTargetPresent\('
check(re.search(GATE % "Mtr4RideTiltPositionMixin", plugin) is not None
      and re.search(GATE % "Mtr4RideTiltMovementMixin", plugin) is not None,
      "门禁插件里为这两条各有**真分支**（if…{ return tiltTargetPresent(…) }）")
# ★★★ 这条是「静默失效」的隐形陷阱：PsdDoorMixinPlugin.shouldApplyMixin 的**默认分支是 false**
#   ⇒ 新 mixin 只登记进 json 而忘了在插件里放行，会被门禁默默挡掉（日志里连「跳过」都没有）。
check(re.search(GATE % "RideTiltPlayerRenderMixin", plugin) is not None,
      "★★ 门禁插件里也为「玩家模型倾斜」放行（整条 if 分支；插件默认 return false ⇒ 漏了这条就静默不生效）")
check(re.search(GATE % "RideTiltCameraRollMixin", plugin) is not None,
      "★★ 门禁插件里也为「相机随列车横滚」放行（整条 if 分支；插件默认 return false ⇒ 漏了这条就静默不生效）")
check('tiltTargetPresent("org.mtr.mod.render.PositionAndRotation"' in plugin,
      "俯仰角那条按它自己的目标类判（org.mtr.mod.render.PositionAndRotation）")
check('tiltTargetPresent("org.mtr.mod.client.VehicleRidingMovement"' in plugin,
      "骑乘帧那条按它自己的目标类判（org.mtr.mod.client.VehicleRidingMovement）")
check("Class.forName" not in plugin and "loadClass" not in plugin,
      "★ 门禁只用类路径资源探测（Class.forName/loadClass 会 load 类 ⇒ 崩别的模组的 mixin 准备）")

# ---- 【1.31.1204 诊断】「装了没反应」必须能从日志/指令一眼定位 ------------------
# ★ 这组断言的由来：用户报「装了 MTR4 但视角没倾斜」，而这条链路上有四处**完全静默**的拒收
#   （插件放行后注入仍可能被 require=0 丢弃；noteRideMove 的三个拒绝分支；渲染侧 pitch==0 早退）。
#   守卫在这里钉住「每个静默点都配了一条正面证据」，防止以后重构时把诊断删掉又变回「没反应」。
check("postApply" in plugin and "mixin 已注入" in plugin,
      "★ 插件 postApply 打「mixin 已注入」信标（只有「已放行」没「已注入」⇒ 注入被丢弃）")

tv = strip_comments(load(VIEW))
check("HEARTBEAT_TICKS" in tv and "骑乘中" in tv and "窗口横滚" in tv,
      "★ 骑乘心跳：每秒一行（列车pitch / 窗口横滚 / 开关）—— 一眼看出「在骑但没转」")
check("检测到骑乘开始" in tv,
      "★ 每次上车打一行「检测到骑乘开始」（证明 movePlayer(DDD)V 那条链路通了）")
check("骑乘链路在跑，但列车俯仰角从未采到" in tv,
      "★ noteRideMove 拒绝分支①：俯仰角一次都没采到（⇒ PositionAndRotation 注入点没生效）")
check("骑乘链路在跑，但本帧没有新的列车俯仰角" in tv,
      "★ noteRideMove 拒绝分支②：本帧没有新值（⇒ transform 漏采）")
check("骑乘链路在跑，但采到的俯仰角来自别的线程" in tv,
      "★ noteRideMove 拒绝分支③：线程判据把这一帧拒了")
check("骑乘中但本模组不改视角" not in tv,
      "★【四改】「不改视角 / 只转画面」那条解释串已删除（① 整条路都没了，不再有这种早退）")
check("注入点命中" in tv and "当前窗口横滚=" in tv,
      "TrainTiltView.diagnostics() 给出「分段定位」状态串（含当前窗口横滚角）")

# ---- 【二改】模型倾斜那一路的诊断（与视角那一路分开，缺哪条一眼可辨） ----------------
check("玩家模型倾斜注入点已命中" in tv and "noteModelTilt" in tv,
      "★ 模型倾斜真正生效（角度非零）时留一行正面证据（注入点命中 ≠ 角度非零，要能分开看）")
check("模型倾斜=" in tv,
      "diagnostics() 里有「模型倾斜」那一段（已生效 / 待生效 / 未在车上）")
check(re.search(r'\|\|\s*mixinClassName\.endsWith\("RideTiltPlayerRenderMixin"\)\s*(\|\||\)\s*\{)',
                plugin) is not None
      and "mixin 已注入" in plugin,
      "★ 新的模型倾斜 mixin 也吃 postApply「mixin 已注入」信标（挂在 postApply 的 if 链里，别漏)")
# ★ 更稳的锚：直接看 **postApply 方法体**里是不是四条 endsWith 都在（链中间那条后面跟的是 || ，
#   不是 ){ —— 只锚 RideTiltPlayer 的 `){` 会在「后面又追加一条」时失效，见本轮真踩）。
_post_body = plugin[plugin.index("void postApply"):]
check(all('endsWith("%s")' % _m in _post_body
          for _m in ("Mtr4RideTiltPositionMixin", "Mtr4RideTiltMovementMixin",
                     "RideTiltPlayerRenderMixin", "RideTiltCameraRollMixin")),
      "★ 四条倾斜 mixin 都吃 postApply「mixin 已注入」信标（挂在 postApply 的 if 链里，别漏)")

# ---- 【三改】相机横滚那一路的诊断（与模型 / 视角两路分开，缺哪条一眼可辨） ------------
check("相机随列车横滚注入点已命中" in tv and "noteCameraRoll" in tv,
      "★ 相机横滚真正生效（roll 非零）时留一行正面证据（与模型倾斜/视角两路各自独立）")
check("相机横滚=" in tv and "cameraRollLogged" in tv,
      "diagnostics() 里有「相机横滚」那一段（已生效 / 待生效 / 未在车上）")
check(re.search(r'\|\|\s*mixinClassName\.endsWith\("RideTiltCameraRollMixin"\)\s*\)\s*\{', plugin) is not None
      and "mixin 已注入" in plugin,
      "★ 相机横滚 mixin 也吃 postApply「mixin 已注入」信标（挂在 postApply 的 if 链里，别漏)")

cmd = strip_comments(load(CMD))
check("TrainTiltView.diagnostics()" in cmd and "诊断" in cmd,
      "★ /mtrqx（无参数）在游戏里直接打印诊断串（不必翻日志）")

# ======================================================================
# 4) 注入点 + static 一致性 + 注入体只用原语
# ======================================================================
print()
print("===== 4) 注入点 / static 一致性 / 注入体铁律 =====")

pos = strip_comments(load(POS_MIXIN))
mov = strip_comments(load(MOV_MIXIN))

check('targets = "org.mtr.mod.render.PositionAndRotation"' in pos, "俯仰角 mixin 的目标类名")
check('targets = "org.mtr.mod.client.VehicleRidingMovement"' in mov, "骑乘帧 mixin 的目标类名")

# ★★★ static 一致性：目标 movePlayer(DDD)V / sendUpdate(Z)V 是 static ⇒ 处理器必须 static；
#     目标 transformForwards/Backwards 是实例方法 ⇒ 处理器不许带 static。
check(re.search(r"private\s+static\s+void\s+smoothlift\$noteRideMove\s*\(", mov) is not None,
      "★ movePlayer(DDD)V 的处理器是 static（目标 static；不一致 ⇒ 注入被拒且 require=0 完全静默）")
check(re.search(r"private\s+static\s+void\s+smoothlift\$noteRideEnd\s*\(", mov) is not None,
      "★ sendUpdate(Z)V 的处理器是 static（同上）")
check(re.search(r"private\s+void\s+smoothlift\$note(Forwards|Backwards)\s*\(", pos) is not None
      and re.search(r"private\s+static\s+void\s+smoothlift\$note(Forwards|Backwards)\s*\(", pos) is None,
      "★ transformForwards/Backwards 的处理器是实例方法（目标非 static）")

# ★★★ 回调参数必须与目标返回值形态一致：transformForwards/Backwards 有返回值（泛型 <T> T，
#     擦除后返回 Object）⇒ 必须收 CallbackInfoReturnable。收 CallbackInfo 会被 Mixin 拒收
#     （InvalidInjectionException: CallbackInfoReturnable is required!），require=0 让它静默失效。
check("import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;" in pos,
      "★★★ 俯仰角 mixin 引入 CallbackInfoReturnable")
check(re.search(r"smoothlift\$note(Forwards|Backwards)\s*\(\s*CallbackInfoReturnable\s*<", pos) is not None,
      "★★★ 两个处理器都收 CallbackInfoReturnable<…>（目标有返回值；收 CallbackInfo ⇒ 注入被静默拒收）")
# 反面：剥掉注释后不该再有「CallbackInfo 作参数」的残留（void 目标才允许）
check(re.search(r"\(\s*CallbackInfo\s+\w+\s*\)", pos) is None,
      "★ 俯仰角 mixin 里没有残留的 CallbackInfo 形参（那两个目标不是 void）")

# 注入点名字 / 描述符（movePlayer 有两个重载 ⇒ 必须写全描述符）
check('method = "movePlayer(DDD)V"' in mov,
      '★ 写全描述符 movePlayer(DDD)V（只写名字会在两个重载之间歧义）')
check('method = "sendUpdate(Z)V"' in mov, "注入点 sendUpdate(Z)V（只读那个 boolean）")
check('method = "transformForwards"' in pos and 'method = "transformBackwards"' in pos,
      "★ 两个 transform 都挂（正常骑乘走 forwards、站台间隙那条走 backwards）")
check(mov.count("require = 0") == 2 and pos.count("require = 0") == 2,
      "四处注入 require=0（MTR 换版本时安静跳过；配套的「首帧已命中」日志负责暴露没生效）")
check(mov.count("remap = false") == 2 and pos.count("remap = false") == 2,
      "remap=false（MTR 不参与原版混淆，名字两端一致）")

# @Shadow 只能影子目标类自己的两个 double
check("@Shadow" in pos and "@Final" in pos and re.search(r"private\s+double\s+pitch\s*;", pos)
      and re.search(r"private\s+double\s+yaw\s*;", pos),
      "@Shadow 两个 double（pitch / yaw）—— 都是目标类自己的字段")

# 注入体铁律：不出现 MTR / MC 类型（编译期没有 MTR 依赖）
pos_body = pos.replace('targets = "org.mtr.mod.render.PositionAndRotation"', "")
mov_body = mov.replace('targets = "org.mtr.mod.client.VehicleRidingMovement"', "")
check("org.mtr" not in pos_body and "net.minecraft" not in pos_body,
      "俯仰角 mixin 的注入体里没有任何 MTR / MC 类型")
check("org.mtr" not in mov_body and "net.minecraft" not in mov_body,
      "骑乘帧 mixin 的注入体里没有任何 MTR / MC 类型")
check("TrainTiltView.noteTransform(" in pos and "TrainTiltView.noteRideMove(" in mov
      and "TrainTiltView.onRideEnd(" in mov,
      "取值/判断全部交给辅助类 TrainTiltView（辅助类不在 mixin 包里）")

# ======================================================================
# 4b) 【二改】玩家模型倾斜：钩在哪儿、回调形态、只认本地玩家
# ======================================================================
print()
print("===== 4b) 玩家模型倾斜（RideTiltPlayerRenderMixin） =====")

rnd = strip_comments(load(RENDER_MIXIN))
check("@Mixin(LivingEntityRenderer.class)" in rnd,
      "目标类 = 原版 LivingEntityRenderer（第一人称下它不会被调，天然只影响第三人称）")
check("render(Lnet/minecraft/world/entity/LivingEntity;FF" in rnd
      and "Lnet/minecraft/client/renderer/MultiBufferSource;I)V" in rnd,
      "★ 写全描述符（泛型 T 擦除成 LivingEntity；只写方法名在重载/泛型下都靠不住）")
check("@At(\"HEAD\")" in rnd,
      "★ 钩 HEAD —— 此刻 poseStack 只做了世界平移 ⇒ mulPose 是**世界系**旋转（脚底不动、身体歪）")
# ★★★ 回调参数必须与目标返回值形态一致：render(...) 是 void ⇒ CallbackInfo。
#     写成 CallbackInfoReturnable 会被 Mixin 拒收；反之（有返回值却写 CallbackInfo）更静默。
check("CallbackInfo ci" in rnd and "CallbackInfoReturnable" not in rnd,
      "★★ 回调参数是 CallbackInfo（目标 render(...) 是 void；有返回值才用 CallbackInfoReturnable）")
check("Minecraft.getInstance().player" in rnd and "entity != Minecraft.getInstance().player" in rnd.replace("\n", ""),
      "只处理本地玩家（别人的车俯仰角拿不到）")
check("poseStack.mulPose(new Quaternionf().rotateAxis(" in rnd.replace("\n", ""),
      "唯一动作 = 往矩阵上乘一个绕车体横向轴的旋转（不改坐标、不改原版其它渲染）")
check("TrainTiltView.renderTiltPitch()" in rnd and "TrainTiltView.renderTiltYaw()" in rnd
      and "TrainTiltView.noteModelTilt(" in rnd,
      "取值/判断全部交给 TrainTiltView（mixin 里不出现任何 MTR 类型）")
check("org.mtr" not in rnd,
      "★ 注入体里没有任何 MTR 类型（编译期没有 MTR 依赖）")
check('"render(Lnet/minecraft/world/entity/LivingEntity;FF' in rnd and "require = 0" in rnd,
      "require = 0（原版改了签名就安静跳过，不崩）+ 由插件 postApply 给「已注入」正面证据")

# ======================================================================
# 4c) 【三改】相机随列车横滚：钩在哪儿、回调形态、描述符
# ======================================================================
print()
print("===== 4c) 相机随列车横滚（RideTiltCameraRollMixin） =====")

roll = strip_comments(load(ROLL_MIXIN))
check("@Mixin(GameRenderer.class)" in roll,
      "目标类 = 原版 GameRenderer（视角矩阵就是它拼的；MC 相机本身没有 roll 入口）")
# ★★★【本轮真踩】描述符必须带 J(long)：真签名是 renderLevel(FJ…)V（javap 核过：
#    `public void renderLevel(float, long, com.mojang.blaze3d.vertex.PoseStack)`）。
#    漏掉 J ⇒ 编译期就报 `Cannot find target method`，而 require=0 让运行期**静默跳过**（不崩、不生效）。
check('method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"' in roll,
      "★★★ 写全描述符 renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V（漏 J(long) ⇒ 注入找不到目标）")
check('target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V"' in roll
      and "ordinal = 3" in roll,
      "★ 钩在 renderLevel 里第 4 处 mulPose(Quaternionf)（= 相机 Y 旋转，offset 523）之后")
check("At.Shift.AFTER" in roll,
      "★ Shift.AFTER —— 相机 Y 旋转已经乘上去，此刻补 roll；它之后取逆视图矩阵 / 视锥 / 世界 / 手共用同一 poseStack")
# ★★★ static 一致性：目标 renderLevel 是**实例**方法 ⇒ 处理器不许带 static（与 §4 同一条铁律）。
check(re.search(r"private\s+void\s+smoothlift\$rollCameraWithTrain\s*\(", roll) is not None
      and re.search(r"private\s+static\s+void\s+smoothlift\$rollCameraWithTrain\s*\(", roll) is None,
      "★★ renderLevel 是实例方法 ⇒ 处理器**不许** static（不一致 ⇒ 注入被拒且 require=0 完全静默）")
check("CallbackInfo ci" in roll and "CallbackInfoReturnable" not in roll,
      "★★ 回调参数是 CallbackInfo（目标 renderLevel(...) 是 void）")
check("require = 0" in roll,
      "require = 0（原版改了签名就安静跳过，不崩）+ 由插件 postApply 给「已注入」正面证据")
check("TrainTiltView.renderTiltPitch()" in roll and "TrainTiltView.renderTiltYaw()" in roll
      and "TrainTiltView.noteCameraRoll(" in roll,
      "取值/判断全部交给 TrainTiltView（mixin 里不出现任何 MTR 类型）")
check("org.mtr" not in roll,
      "★ 注入体里没有任何 MTR 类型（编译期没有 MTR 依赖）")
check("poseStack.mulPose(new Quaternionf().rotateAxis(" in roll.replace("\n", ""),
      "唯一动作 = 往矩阵上乘一个旋转（不改坐标、不改原版其它渲染）")

# ★★★【五改】视角无关性：算式里**只许**有 pitch / yaw，绝不许再出现相机视线。
#   读视线 = 退回四改（看正前恒 0 ⇒ 「没转」；扭头 0↔±17° ⇒ 「抽」）。
#   ★ 判据陷阱（本轮真踩）：**别用裸子串 "Camera"** —— 本类自己就叫 RideTiltCameraRollMixin，
#     那个 "Camera" 会假红。要锚「读相机」的具体形态：导入 / 取相机 / 取视线。
roll_flat = re.sub(r"\s+", " ", roll)
check("getLookVector" not in roll and "getMainCamera" not in roll
      and "import net.minecraft.client.Camera;" not in roll
      and "atan2" not in roll and "upNow" not in roll and "upTarget" not in roll,
      "★★★【五改】算式里没有相机视线（无 getLookVector / getMainCamera / Camera 导入 / atan2 / upNow / upTarget）"
      "⇒ 旋转角与「往哪看」无关：坡上一定转、扭头不抽")
check("import net.minecraft.client.Minecraft;" not in roll
      and "Vector3f" not in roll and "Quaternionf" in roll,
      "★★★【五改】连 Minecraft / Vector3f 都不引（只留 PoseStack + JOML Quaternionf + TrainTiltView）")
check("rotateAxis( (float) -pitch" in roll_flat,
      "★★★【五改】往 poseStack 上乘的是 **-pitch**（即 R^{-1}；写成 +pitch 会让列车**双倍**倾斜）")
check("-Math.cos(yaw)" in roll and "Math.sin(yaw)" in roll,
      "★★★【五改】轴仍是车体横向轴 r = (-cos yaw, 0, sin yaw)（与模型倾斜那条**同一个 r**）")
check("pitch == 0.0" in roll,
      "★ 平路（pitch 恰为 0）直接 return，不动矩阵（免得平路直行也歪一下）")

# ======================================================================
# 5) ★【四改】不再改玩家视线 —— 只做几何重放（模型倾斜 + 相机横滚）
# ======================================================================
print()
print("===== 5) 几何（模型倾斜 / 相机横滚，纯逻辑重放） =====")

# ----------------------------------------------------------------------
# 5a) 模型倾斜的几何：轴取对没取对、方向对不对、以及一条不变量
# ----------------------------------------------------------------------


def quat_axis(angle, ax, ay, az):
    """按 JOML {@code Quaternionf.rotateAxis}(angle, axis) 的定义（右手定则）算 3×3 旋转矩阵。"""
    n = math.sqrt(ax * ax + ay * ay + az * az)
    ax, ay, az = ax / n, ay / n, az / n
    c, s = math.cos(angle), math.sin(angle)
    t = 1.0 - c
    return (
        (t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay),
        (t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax),
        (t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c),
    )


def mv(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def train_axis(yaw):
    """源码里的式子：车体横向轴 r = f × u = (-cos yaw, 0, sin yaw)，f = (sin yaw, 0, cos yaw)。"""
    return (-math.cos(yaw), 0.0, math.sin(yaw))


def train_rot(pitch, yaw):
    return quat_axis(pitch, *train_axis(yaw))


def train_forward(yaw):
    return (math.sin(yaw), 0.0, math.cos(yaw))


UP = (0.0, 1.0, 0.0)
CLIMB = math.radians(10.0)          # 上坡 10°（MTR 约定：上坡为正）
YAW = math.radians(37.0)            # 随便一个不是整角的朝向来验证一般性

r = train_axis(YAW)
check(abs(r[1]) < 1e-12, "车体横向轴是**水平**的（r.y ≡ 0）⇒ 绕它转是纯粹的「俯仰」，不含侧倾")
check(abs(math.hypot(r[0], r[2]) - 1.0) < 1e-12 and abs(r[0] * math.sin(YAW) + r[2] * math.cos(YAW)) < 1e-12,
      "r = f × u 与前进方向正交（r·f = r.x·sin yaw + r.z·cos yaw ≡ 0）且是单位向量（(-cos yaw, 0, sin yaw) 的闭式解正确）")

R = train_rot(CLIMB, YAW)
n = mv(R, UP)                        # 身体上方向 = 地板法线
check(n[1] > 0 and (n[0] * math.sin(YAW) + n[2] * math.cos(YAW)) < 0,
      "★ 上坡时身体上方向朝**下坡侧**倾（垂直于地板）⇒ 脚底贴合地板、玩家向后仰（n·f = %+0.4f）"
      % (n[0] * math.sin(YAW) + n[2] * math.cos(YAW)))
check(abs(math.degrees(math.acos(max(-1.0, min(1.0, n[1])))) - 10.0) < 1e-9,
      "★ 地板法线与世界上方夹角 = 列车俯仰角（实测 %0.4f° = 10°）"
      % math.degrees(math.acos(max(-1.0, min(1.0, n[1])))))

# ★ 不变量：MTR 那两个角是从**同一对路径点**算的 ⇒ a→b 反过来的话 f→-f 且 pitch→-pitch，
#   r→-r、θ→-θ，两次翻转互相抵消 ⇒ 旋转矩阵逐字不变。所以实现里**不必**知道 yaw 指哪一头。
R_flipped = train_rot(-CLIMB, YAW + math.pi)
dev = max(abs(R[i][j] - R_flipped[i][j]) for i in range(3) for j in range(3))
check(dev < 1e-12,
      "★★ 「路径点反向」不变量：R(pitch, yaw) ≡ R(-pitch, yaw+π)（实测最大偏差 %0.1e）⇒ 不用猜方向" % dev)

# ★ 几何结论：R 是**绕 r** 的旋转 ⇒ r 是它的不动轴（模型倾斜绕的正是车体横向轴）。
#   ★【五改】R 负责「把模型转进列车坐标系」；相机那条乘的正是它的逆 R^{-1}
#     —— 于是屏幕上「车厢平 + 模型站直」同时成立，见 §5c(c)。用户否掉的是「拧视线方向」
#     （写 xRot/yRot），不是「转画面」。
#   ★ 历史（四改）：曾另算一条「绕视线的纯 roll」把屏幕竖直掰到 n —— 那算法看正前恒 0、
#     扭头乱摆，已整段删除，改成 R^{-1}（视角无关）。
rr = mv(R, r)
check(max(abs(rr[i] - r[i]) for i in range(3)) < 1e-12,
      "★ R 的不动轴就是 r（R·r ≡ r，实测残差 %0.1e）：模型倾斜确实是「绕车体横向轴」转"
      % max(abs(rr[i] - r[i]) for i in range(3)))

check("-Math.cos(yaw)" in rnd and "Math.sin(yaw)" in rnd,
      "★ 源码里的轴就是 (-cos yaw, 0, sin yaw)（与上面重放的闭式解同构）")


# ----------------------------------------------------------------------
# 5c) 【五改】相机那条的几何：按 RideTiltCameraRollMixin 的算式重放（R^{-1}，与视线无关）
# ----------------------------------------------------------------------


def vsub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def vscale(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def vdot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def vcross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def vlen(a):
    return math.sqrt(vdot(a, a))


def vnorm(a):
    n_ = vlen(a)
    return (a[0] / n_, a[1] / n_, a[2] / n_)


# 【五改】逐字重放 RideTiltCameraRollMixin 的算式：R^{-1} = rotateAxis(-pitch, r)。
#   ★ 关键：函数签名里**没有 fwd** —— 算式与视线无关，这正是五改与四改的分界。
def camera_unrot(pitch, yaw):
    return quat_axis(-pitch, *train_axis(yaw))


MINV = camera_unrot(CLIMB, YAW)

# (a) ★★★ 五改核心不变量：把世界乘 R^{-1} 之后，**列车的地板法线逐分量 = 世界上方**
#     ⇒ 列车 / 地板 / 窗框在屏幕上**水平**（用户要的「视角平行于窗框 / 列车地板」）。
check(vlen(vsub(mv(MINV, n), UP)) < 1e-12,
      "★★★ 世界乘 R^{-1} 后，列车地板法线逐分量 = 世界上方 ⇒ 列车/地板/窗框在屏幕上**水平**（残差 %0.1e）"
      % vlen(vsub(mv(MINV, n), UP)))

# (b) 地面 / 地平线变斜：世界竖直被转成 R^{-1}·u，与世界上方夹角 = 列车倾斜角；
#     而且它偏的方向与地板法线**相反**（车与坡在屏幕上是「相对斜」的）。
gnd_up = mv(MINV, UP)
ang_gnd = math.degrees(math.acos(max(-1.0, min(1.0, vdot(gnd_up, UP)))))
check(abs(ang_gnd - 10.0) < 1e-9,
      "★★★ 地面（世界竖直）变斜了：与世界上方夹角 = 列车倾斜角（实测 %0.4f° = 10°）" % ang_gnd)
check(vdot(gnd_up, train_forward(YAW)) > 0
      and (n[0] * math.sin(YAW) + n[2] * math.cos(YAW)) < 0,
      "★★ 地面往下坡侧偏、地板法线往上坡侧偏（两者方向相反）⇒ 观感「车厢平、外面坡斜」")

# (c) ★★★ 与模型那条**严格互逆**：R · R^{-1} = I
#     ⇒ 屏幕上「车厢是平的」+「模型站在地板上」两件事同时成立（不是各转各的）。
RR = tuple(tuple(sum(R[i][k] * MINV[k][j] for k in range(3)) for j in range(3)) for i in range(3))
I3 = ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0))
dev_i = max(abs(RR[i][j] - I3[i][j]) for i in range(3) for j in range(3))
check(dev_i < 1e-12,
      "★★★ 相机那条与模型那条**严格互逆**（R·R^{-1} = I，实测最大偏差 %0.1e）⇒「车厢平 + 模型站直」" % dev_i)

# (d) ★★★【五改】**角度 = 列车倾斜角本人，且与视线方向无关**：
#     (d1) 从矩阵里反解出的旋转角恒 = |pitch|；
#     (d2) 换任意车头朝向（yaw）结果不变 ⇒ 算式里没有别的输入。
def _angle_of(m):
    tr = m[0][0] + m[1][1] + m[2][2]
    return math.degrees(math.acos(max(-1.0, min(1.0, (tr - 1.0) / 2.0))))


ang_applied = _angle_of(MINV)
check(abs(ang_applied - 10.0) < 1e-9,
      "★★★【五改】施加的旋转角 = 列车倾斜角本人（实测 %0.4f° = 10°）—— 不再是 0…±17° 那族视线函数"
      % ang_applied)
spread = max(abs(_angle_of(camera_unrot(CLIMB, math.radians(y_))) - 10.0) for y_ in (0.0, 37.0, 123.0, 271.0))
check(spread < 1e-9,
      "★★★【五改】换任意车头朝向（yaw = 0/37/123/271°）旋转角都还是 10°（最大偏差 %0.1e）⇒ 与视线/朝向无关"
      % spread)
check(vlen(vsub(mv(MINV, r), r)) < 1e-12,
      "★ R^{-1} 的不动轴仍是 r（列车横向轴本身不被转）⇒ 没有「左右侧窗各一个方向」的二义")

# (e) 源码侧锚点：算式 = rotateAxis(-pitch, r)，且四改的三件套已整段删除。
check("(float) -pitch" in roll_flat and "-Math.cos(yaw)" in roll
      and "Math.sin(yaw)" in roll,
      "★ 源码里往 poseStack 上乘的是 rotateAxis(-pitch, (-cos yaw, 0, sin yaw))（= R^{-1}）")
check("Math.atan2" not in roll and "upNow" not in roll and "upTarget" not in roll,
      "★★★【五改】源码里四改的 atan2/upNow/upTarget 三件套**整段删除**（不是留着不调用）")


# ----------------------------------------------------------------------
# 5d) ★★★【四改】反向铁律：TrainTiltView 绝不能再写玩家视角
#     （用户明确否掉了「拧视线方向」那条路：只转画面，不动准星/瞄准方向）
# ----------------------------------------------------------------------
check("rotateViewDir" not in view and "appliedViewRot" not in view
      and "applyRidePitch" not in view and "clampView" not in view,
      "★★★ TrainTiltView 里没有「改玩家视线」的残留（rotateViewDir/appliedViewRot/applyRidePitch/clampView 全无）")
check("setXRot" not in view and "setYRot" not in view,
      "★★★ TrainTiltView 里没有 setXRot / setYRot（一个字节都不写玩家视角）")
check("player.setXRot" not in view and "player.setYRot" not in view,
      "★★★ 也不通过 player.setXRot/setYRot 绕过去改视线")
check("setRotation(" not in view,
      "★★★ 也不用 Entity.setRotation 批量写视角（那是 setYRot+xRot 的组合）")

# ======================================================================
# 6) 真·防抖：线程判据 + 帧号判据
# ======================================================================
print()
print("===== 6) 快照的两条判据（渲染线程噪声 / 同线程紧邻） =====")

check("long stamp = pendingStamp" in view and "stamp == consumedStamp" in view,
      "① 有新值才用（帧号）")
check("pendingThread != Thread.currentThread()" in view,
      "★ ② 必须是**本线程**刚写的值 —— 渲染线程（RenderVehicles / RenderLifts）也在写同一个变量")
check("pendingThread = Thread.currentThread()" in view, "写侧记下是哪条线程写的")
check("consumedStamp = stamp" in view, "消费后推进帧号（否则同一帧会被重复消费）")
check("rideEndRequested" in view and "onRideEnd()" in view,
      "★ 下车信号：sendUpdate(true) 只置位，主线程 tick 里清 rideActive（渲染侧下一帧就不再转）")
check("rideEndRequested = true;" in view and "rideEndRequested = false;" in view,
      "★ 置位/清位成对（只置不清 ⇒ 之后再也转不起来）")
check("RIDE_GAP_TICKS" in view and "rideGapTicks >= RIDE_GAP_TICKS" in view,
      "兜底：连续 RIDE_GAP_TICKS 个 tick 没有骑乘帧 ⇒ 也认定已下车（被传送走 / 车被撤掉）")
# ★【四改】反向：① 那套「换算增量 + 写视角」的收口方法必须已经不存在。
check("applyRidePitch" not in view and "appliedTiltDegrees" not in view,
      "★【四改】旧的「算增量 → 写视角」收口（applyRidePitch / appliedTiltDegrees）已彻底删除")

# ---- 【二改】模型倾斜的「在不在车上」标记：两端各写一个 boolean，读侧只看它 -------------
check("private static volatile boolean rideActive" in view,
      "★ rideActive 是 volatile（渲染线程读 / 采集与 tick 两个线程写）")
check("rideActive = true;" in view,
      "★ 采集端（noteRideMove，渲染线程、与搬运同帧）置位")
check(view.count("rideActive = false;") >= 2,
      "★ 消费端（onClientTick 认下车）与 onDisconnect 都要清位（清少了 ⇒ 下车后模型还歪着）")
check("if (!enabled || !rideActive)" in view,
      "★ 渲染侧闸门 = 开关 ∧ 正在车上（两者缺一都不倾斜）")
check("public static double renderTiltPitch()" in view and "return ridePitch;" in view,
      "★ 模型倾斜读的是**绝对** pitch（ridePitch），不是别的什么累加量（【四改】已无累加量）")

# ======================================================================
# 7) 产物侧：jar 里真的有这些类、注入串没被 remap 掉
# ======================================================================
print()
print("===== 7) 产物侧（jar 字节码） =====")

props = load(GRADLE_PROPS)
mod_version = re.search(r"^mod_version\s*=\s*(\S+)\s*$", props, flags=re.M)
mod_version = mod_version.group(1) if mod_version else "?"
jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "mzycBetterMTR-*.jar")))
check(bool(jars), "找得到构建产物 build/libs/mzycBetterMTR-*.jar（找不到一律判失败）")
if not jars:
    print()
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
jar = jars[-1]
check(mod_version in os.path.basename(jar),
      "jar 名带的是 gradle.properties 里的 mod_version",
      "%s / %s" % (os.path.basename(jar), mod_version))
check(mod_version == "1.31.1204", "★ 版本号就是用户点名的 1.31.1204", "得到 %s" % mod_version)

z = zipfile.ZipFile(jar)
names = z.namelist()
for rel in ("smooth/lift/client/TrainTiltView.class",
            "smooth/lift/client/TrainTiltViewCommand.class",
            "smooth/lift/client/mixin/Mtr4RideTiltPositionMixin.class",
            "smooth/lift/client/mixin/Mtr4RideTiltMovementMixin.class",
            "smooth/lift/client/mixin/RideTiltPlayerRenderMixin.class",
            "smooth/lift/client/mixin/RideTiltCameraRollMixin.class"):
    check(rel in names, "jar 里有 " + rel)

in_jar = json.loads(z.read("smoothlift.psd.mixins.json").decode("utf-8"))
check("Mtr4RideTiltPositionMixin" in in_jar.get("client", [])
      and "Mtr4RideTiltMovementMixin" in in_jar.get("client", [])
      and "RideTiltPlayerRenderMixin" in in_jar.get("client", [])
      and "RideTiltCameraRollMixin" in in_jar.get("client", []),
      "★ 打进包的那份 mixins.json 也列着这四条（否则运行时根本不加载）")

pos_bytes = z.read("smooth/lift/client/mixin/Mtr4RideTiltPositionMixin.class")
mov_bytes = z.read("smooth/lift/client/mixin/Mtr4RideTiltMovementMixin.class")
view_bytes = z.read("smooth/lift/client/TrainTiltView.class")

for token in (b"org.mtr.mod.render.PositionAndRotation", b"transformForwards",
              b"transformBackwards", b"pitch", b"yaw",
              b"/Pseudo;", b"/Shadow;", b"/Final;", b"/Inject;",
              b"callback/CallbackInfoReturnable;",
              b"smooth/lift/client/TrainTiltView"):
    check(token in pos_bytes, "俯仰角 mixin class 里含 " + token.decode("latin1"))
# 反面：class 常量池里不该再出现「裸 CallbackInfo」（那两个目标非 void）
check(b"injection/callback/CallbackInfo;" not in pos_bytes,
      "★ 俯仰角 mixin class 里没有裸 CallbackInfo（那两个目标有返回值）")
for token in (b"org.mtr.mod.client.VehicleRidingMovement", b"movePlayer(DDD)V",
              b"sendUpdate(Z)V", b"/Pseudo;", b"/Inject;",
              b"smooth/lift/client/TrainTiltView"):
    check(token in mov_bytes, "骑乘帧 mixin class 里含 " + token.decode("latin1"))
for token in (b"smoothlift-view.properties", b"trainTiltView", b"noteTransform",
              b"noteRideMove", b"onRideEnd", b"onClientTick", b"onDisconnect"):
    check(token in view_bytes, "TrainTiltView class 里含 " + token.decode("latin1"))
for token in (b"noteModelTilt", b"renderTiltPitch", b"renderTiltYaw", b"rideActive",
              b"noteCameraRoll", b"lastRollDegrees", b"rideEndRequested"):
    check(token in view_bytes, "TrainTiltView class 里含 " + token.decode("latin1") + "（模型倾斜 / 相机横滚那两路）")
# ★【四改】反向：产物里也不该再有任何「写玩家视角」的痕迹。
check(b"rotateViewDir" not in view_bytes and b"appliedViewRot" not in view_bytes
      and b"setXRot" not in view_bytes and b"setYRot" not in view_bytes,
      "★★★ TrainTiltView class 里没有 rotateViewDir/appliedViewRot/setXRot/setYRot（产物级反证：不写视线）")

# ---- 【二改】模型倾斜 mixin 的字节码 + refmap 必须把目标方法映射到真正的原版成员 ----
# ★★ 这里断言的是**产物里的中间名**，不是源码里的 Yarn 名：这条 @Inject 没写 remap=false ⇒
#    AP/remapper 会把 MC 侧名字换成 intermediary（@Mixin 目标 → class_922、mulPose → method_22907）；
#    JOML（org.joml.*）不参与 remap ⇒ 名字原样。锚错名字（拿 mulPose / LivingEntityRenderer 去 grep）
#    会得到假红。中间名 ↔ 源码名的对应由**同一份 refmap 的键值对**反证（见下面的 refmap 断言）。
rnd_bytes = z.read("smooth/lift/client/mixin/RideTiltPlayerRenderMixin.class")
for tok, label in (
        (b"Lnet/minecraft/class_922;", "@Mixin 目标已 remap 成 class_922（= 原版 LivingEntityRenderer）"),
        (b"Lnet/minecraft/class_1309;", "描述符里的 class_1309（= LivingEntity，泛型 T 擦除后）"),
        (b"Lnet/minecraft/class_4587;", "描述符里的 class_4587（= PoseStack）"),
        (b"Lnet/minecraft/class_4597;", "描述符里的 class_4597（= MultiBufferSource）"),
        (b"org/joml/Quaternionf", "org/joml/Quaternionf（JOML 不 remap，名字原样）"),
        (b"method_22907", "调用的是 method_22907（= PoseStack.mulPose(Quaternionf)，源码里写 mulPose）"),
        (b"(Lorg/joml/Quaternionf;)V", "mulPose 的描述符 (Lorg/joml/Quaternionf;)V（确认 method_22907 就是它）"),
        (b"smooth/lift/client/TrainTiltView", "取值/判断全交给 TrainTiltView（注入体里没有 MTR 类型）"),
        (b"callback/CallbackInfo;", "回调参数 CallbackInfo（目标 render(...) 是 void）")):
    check(tok in rnd_bytes, "模型倾斜 mixin class 里含 %s" % label)
check(b"render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
      b"Lnet/minecraft/client/renderer/MultiBufferSource;I)V" in rnd_bytes,
      "★ @Inject 的 method 值保留**源码级描述符**（与 refmap 的键逐字一致）")
check(b"callback/CallbackInfoReturnable;" not in rnd_bytes,
      "★ 模型倾斜 mixin 里没有 CallbackInfoReturnable（目标 render(...) 是 void ⇒ 只许 CallbackInfo）")
check(b"org/mtr" not in rnd_bytes,
      "★ 模型倾斜 mixin 里没有 org/mtr（编译期没有 MTR 依赖）")

# ★ refmap 归属：这条 mixin 属 **client 源集** ⇒ 映射只应落在 client-*-refmap.json。
#   所以判据是「**含它的那份**必须映射到 class_922;method_4054」，而不是「两份都必须有」
#   （主 refmap 为空是**对的**，硬要求两份都有 = 假红）。
found_refmap = False
for refmap_name in ("client-mzycBetterMTR-refmap.json", "mzycBetterMTR-refmap.json"):
    if refmap_name not in names:
        continue
    mappings = json.loads(z.read(refmap_name).decode("utf-8")).get("mappings", {})
    entry = mappings.get("smooth/lift/client/mixin/RideTiltPlayerRenderMixin", {})
    if not entry:
        continue
    found_refmap = True
    ok = any("method_4054" in v and "class_922" in v for v in entry.values())
    check(ok, "★ refmap(%s) 把模型倾斜的目标方法映射到了 class_922;method_4054（原版 render）"
          % refmap_name, "条目 %s" % entry)
check(found_refmap,
      "★ 至少一份 refmap 含模型倾斜的目标方法映射（client 源集 ⇒ client-*-refmap.json）")

# ---- 【三改】相机横滚 mixin 的字节码 + refmap ------------------------------------------
roll_bytes = z.read("smooth/lift/client/mixin/RideTiltCameraRollMixin.class")
for tok, label in (
        (b"Lnet/minecraft/class_757;", "@Mixin 目标已 remap 成 class_757（= 原版 GameRenderer）"),
        (b"method_22907", "调用的 method_22907（= PoseStack.mulPose(Quaternionf)）"),
        (b"(Lorg/joml/Quaternionf;)V", "mulPose 描述符 (Lorg/joml/Quaternionf;)V（确认 method_22907 就是它）"),
        (b"org/joml/Quaternionf", "org/joml/Quaternionf（JOML 不 remap，名字原样）"),
        (b"smooth/lift/client/TrainTiltView", "取值/判断全交给 TrainTiltView（注入体里没有 MTR 类型）"),
        (b"callback/CallbackInfo;", "回调参数 CallbackInfo（目标 renderLevel(...) 是 void）")):
    check(tok in roll_bytes, "相机横滚 mixin class 里含 %s" % label)
check(b"renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V" in roll_bytes,
      "★★★ @Inject 的 method 值保留**源码级描述符**且带 J(long)（漏 J ⇒ 编译期就找不到目标）")
check(b"callback/CallbackInfoReturnable;" not in roll_bytes,
      "★ 没有 CallbackInfoReturnable（目标 renderLevel(...) 是 void ⇒ 只许 CallbackInfo）")
check(b"org/mtr" not in roll_bytes,
      "★ 没有 org/mtr（编译期没有 MTR 依赖）")

# ★ refmap 归属：client 源集 ⇒ 映射只应落在 client-*-refmap.json（主 refmap 为空是对的）。
found_roll_refmap = False
for refmap_name in ("client-mzycBetterMTR-refmap.json", "mzycBetterMTR-refmap.json"):
    if refmap_name not in names:
        continue
    mappings = json.loads(z.read(refmap_name).decode("utf-8")).get("mappings", {})
    entry = mappings.get("smooth/lift/client/mixin/RideTiltCameraRollMixin", {})
    if not entry:
        continue
    found_roll_refmap = True
    check(any("method_3188" in v and "class_757" in v for v in entry.values()),
          "★ refmap(%s) 把 renderLevel 映射到 class_757;method_3188（原版 GameRenderer.renderLevel）"
          % refmap_name, "条目 %s" % entry)
    check(any("method_22907" in v and "class_4587" in v for v in entry.values()),
          "★ refmap(%s) 把 mulPose 映射到 class_4587;method_22907（原版 PoseStack.mulPose）"
          % refmap_name, "条目 %s" % entry)
check(found_roll_refmap,
      "★ 至少一份 refmap 含相机横滚的目标方法映射（client 源集 ⇒ client-*-refmap.json）")

# ★ remap 隐患：refmap 里**不该**有这两个目标类的条目。有的话说明注解被 remap 过，
#   @Shadow 的字段名会在运行期被换掉 ⇒ 静默失效。
for refmap_name in ("client-mzycBetterMTR-refmap.json", "mzycBetterMTR-refmap.json"):
    if refmap_name not in names:
        continue
    mappings = json.loads(z.read(refmap_name).decode("utf-8")).get("mappings", {})
    hit = [k for k in mappings if "PositionAndRotation" in k or "VehicleRidingMovement" in k]
    check(not hit, "★ refmap(%s) 里没有这两个目标类的映射条目（有 = 字段名会被 remap 掉）"
          % refmap_name, "命中 %s" % hit)

# ======================================================================
# 8) 反向对照（在内存里改坏 -> 对应判据必须当场变红）
# ======================================================================
print()
print("===== 8) 反向对照（mutation 必须变红） =====")

# ① 【四改】往 TrainTiltView 里塞一句「写玩家视角」⇒「绝不写视线」那条判据必须当场变红。
#    ★ 上一版这里改的是已删除的①代码（绕 r 转 Δpitch）——锚点会随功能被删而失效，
#      所以每轮改动后都要把 mutation 的锚点跟着挪到**当前**代码上，否则就是假绿。
bad_view = view.replace("rideActive = true;",
                        "rideActive = true; minecraft.player.setXRot(0.0F);")
check("setXRot" in bad_view and "setXRot" not in view,
      "往 TrainTiltView 里塞一句 player.setXRot ⇒「一个字节都不写玩家视角」那条判据当场变红")

# ② static 改坏：处理器去掉 static ⇒ 判据必须变红
bad_mov = mov.replace("private static void smoothlift$noteRideMove",
                      "private void smoothlift$noteRideMove")
check(re.search(r"private\s+static\s+void\s+smoothlift\$noteRideMove\s*\(", bad_mov) is None,
      "把处理器的 static 去掉 ⇒「static 必须与目标一致」那条判据当场变红")

# ②' 回调参数改坏：CallbackInfoReturnable → CallbackInfo ⇒ 新增那条判据必须变红
bad_pos = pos.replace("CallbackInfoReturnable<Object>", "CallbackInfo")
check(re.search(r"smoothlift\$note(Forwards|Backwards)\s*\(\s*CallbackInfoReturnable\s*<", bad_pos) is None
      and re.search(r"\(\s*CallbackInfo\s+\w+\s*\)", bad_pos) is not None,
      "把 CallbackInfoReturnable 退回 CallbackInfo ⇒「回调参数必须与返回值形态一致」那条当场变红")

# ③ 门禁删掉：插件里那段分支没了 ⇒ 判据必须变红
bad_plugin = plugin.replace('if (mixinClassName.endsWith("Mtr4RideTiltMovementMixin")) {', "")
check("Mtr4RideTiltMovementMixin" not in bad_plugin.split("tiltTargetPresent")[0]
      or 'mixinClassName.endsWith("Mtr4RideTiltMovementMixin")' not in bad_plugin,
      "把门禁分支删掉 ⇒「为这两条各有分支」那条判据当场变红")

# ③' 【二改】把模型倾斜那条的门禁**真分支**删掉（postApply 信标里的同名子串仍在）⇒ 新判据必须变红。
#     ★ 这里刻意复用 §3 的**同一条 GATE 正则**：裸子串判据在这种破坏下仍为真（假绿），已废弃。
bad_plugin2 = plugin.replace('if (mixinClassName.endsWith("RideTiltPlayerRenderMixin")) {',
                             'if (false) {')
check(re.search(GATE % "RideTiltPlayerRenderMixin", bad_plugin2) is None
      and 'mixinClassName.endsWith("RideTiltPlayerRenderMixin")' in bad_plugin2,
      "把模型倾斜的门禁真分支删掉（信标子串仍在）⇒「插件默认 return false」那条判据当场变红")

# ③'' 【二改】把横向轴取反 ⇒ 「轴 = (-cos,0,sin)」与「上坡向后仰」两条判据一起变红
bad_rnd = rnd.replace("-Math.cos(yaw)", "Math.cos(yaw)")
check("-Math.cos(yaw)" not in bad_rnd,
      "把横向轴取反 ⇒「轴 = (-cos yaw, 0, sin yaw)」那条当场变红")
r_bad = (math.cos(YAW), 0.0, -math.sin(YAW))
n_bad = mv(quat_axis(CLIMB, *r_bad), UP)
check((n_bad[0] * math.sin(YAW) + n_bad[2] * math.cos(YAW)) > 0,
      "反向重放：轴取反后上坡时身体变成**朝上坡侧**倾（n·f = %+0.4f > 0）⇒ 判据必然红"
      % (n_bad[0] * math.sin(YAW) + n_bad[2] * math.cos(YAW)))

# ③''' 【二改】回调参数改坏：CallbackInfo → CallbackInfoReturnable（void 目标）⇒ 判据变红
bad_rnd2 = rnd.replace("CallbackInfo ci", "CallbackInfoReturnable<Object> cir")
check("CallbackInfo ci" not in bad_rnd2 and "CallbackInfoReturnable" in bad_rnd2,
      "把 void 目标的回调参数换成 CallbackInfoReturnable ⇒ 回调形态那条当场变红")

# ④ 基线：真源码没被上面的对照污染
#   ★ 注意用 strip_comments：TrainTiltView 的**注释**里会出现 setXRot 这些词（讲「不许写视线」），
#     直接 grep 原文会假红（本轮真踩）。
_view_src = strip_comments(load(VIEW))
check("rideEndRequested" in _view_src
      and "setXRot" not in _view_src and "setYRot" not in _view_src
      and "rotateViewDir" not in _view_src and "appliedViewRot" not in _view_src
      and re.search(r"private\s+static\s+void\s+smoothlift\$noteRideMove\s*\(",
                    strip_comments(load(MOV_MIXIN))) is not None
      and "CallbackInfoReturnable<Object>" in load(POS_MIXIN)
      and "-Math.cos(yaw)" in load(RENDER_MIXIN)
      and 'method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"' in load(ROLL_MIXIN),
      "对照全部在内存里做 ⇒ 源码未被污染（还原基线一致）")

# ---- 【五改】相机那条的几条专属反向对照 ------------------------------------------------
# ①' 把「R^{-1}」（-pitch）改成「R」（+pitch）⇒ 源码锚点 + 几何 (a)(c) 必须变红
#     （写成 +pitch 会让列车**双倍**倾斜：R·R = R²，地板法线对不齐世界竖直）。
bad_roll = roll.replace("(float) -pitch", "(float) pitch")
check("(float) -pitch" not in bad_roll and "(float) pitch" in bad_roll,
      "把 -pitch 改成 +pitch ⇒「矩阵右乘 R^{-1}」那条判据当场变红")
bad_minv = camera_unrot(-CLIMB, YAW)                      # = R（写成 +pitch 的效果）
check(vlen(vsub(mv(bad_minv, n), UP)) > 0.1,
      "反向重放：写成 +pitch 后列车地板法线对不齐世界上方（残差 %0.3f ≫ 0）⇒ (a) 必然红"
      % vlen(vsub(mv(bad_minv, n), UP)))
# ①'' 【五改】把相机读回算式（退回四改）⇒「算式里不许有相机视线」那条必须变红
#    ★ 同样别用裸子串 "Camera"（会命中本类自己的名字 RideTiltCameraRollMixin）⇒ 锚 getLookVector。
bad_roll_v = roll.replace("TrainTiltView.noteCameraRoll(",
                          "Minecraft.getInstance().gameRenderer.getMainCamera(); "
                          "TrainTiltView.noteCameraRoll(")
check("getMainCamera" in bad_roll_v and "getMainCamera" not in roll,
      "把相机读回算式 ⇒「视角无关（无 getLookVector / getMainCamera / Camera 导入）」那条判据当场变红")
# ②' 把门禁真分支删掉（postApply 信标里的同名子串仍在）⇒ 新判据必须变红。
bad_plugin3 = plugin.replace('if (mixinClassName.endsWith("RideTiltCameraRollMixin")) {',
                             'if (false) {')
check(re.search(GATE % "RideTiltCameraRollMixin", bad_plugin3) is None
      and 'mixinClassName.endsWith("RideTiltCameraRollMixin")' in bad_plugin3,
      "把相机横滚的门禁真分支删掉（信标子串仍在）⇒ 门禁那条判据当场变红")
# ③' 描述符漏掉 J(long) ⇒ 「写全描述符」那条必须变红（正是本轮真踩的坑）。
bad_roll2 = roll.replace("renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
                         "renderLevel(FLcom/mojang/blaze3d/vertex/PoseStack;)V")
check("renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V" not in bad_roll2,
      "把描述符里的 J(long) 删掉 ⇒「写全描述符 renderLevel(FJL…)V」那条当场变红")
# ④' 处理器加 static（目标是实例方法）⇒ static 一致性判据必须变红。
bad_roll3 = roll.replace("private void smoothlift$rollCameraWithTrain",
                         "private static void smoothlift$rollCameraWithTrain")
check(re.search(r"private\s+static\s+void\s+smoothlift\$rollCameraWithTrain\s*\(", bad_roll3) is not None,
      "给处理器加上 static（目标是实例方法）⇒ static 一致性那条当场变红")

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
