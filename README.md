# SmoothLift

## 介绍
这个模组可以让MTR里的扶梯变得更加平滑 使用 /futispeed X 改变扶梯运行速度（单位：格/秒）,使用石斧点击扶梯下端可以更改扶梯速度。支持MTR4和MTR3。用 /futimusic 可以为扶梯设置/更换音频（含模组内置音频），用 /futiloud 可以统一调所有扶梯的音量（1~1000，100 = 原始音量）。

## 界面（石斧）

拿石斧右键某条扶梯会打开设置界面，里面只有两个输入框和一个按钮：

| 控件 | 作用 |
| --- | --- |
| **扶梯速度** 输入框 | 这条扶梯的运行速度（会同步左右两列） |
| **阶梯速度** 输入框 | 这条扶梯的阶梯动画速度 |
| **阶梯速度对齐扶梯速度** 按钮 | 把阶梯速度框填成扶梯速度框的值 |
| **声音音量** 输入框 | 这条扶梯的声音音量，输入 **1~1000**（**100 = 原始音量**，1000 = 10× 放大），越界自动夹取 |
| **声音设置…** 按钮 | 进入自定义声音子界面（选择/绑定音频） |

- **按 ESC（或回车）退出界面时统一应用**改动，没有「确定」按钮；两项都没改就什么都不发。
- 改 **扶梯速度** 时，**阶梯速度会跟着一起变**（除非你先手动改过阶梯速度框）。
- 改 **阶梯速度** 时，**扶梯速度不会跟着变**。
- 多条扶梯能同时各自动各自的速度（真正的逐条独立，互不干扰）。
- **石斧调速会自动同步左右两边**：MTR 一条扶梯本来是 `side=left` / `side=right` 两列并排，
  现在点任意一边都会把两边一起设置，不会再出现「只能半边半边地调」。

### 阶梯速度的取值优先级

1. 这条扶梯被**单独设置**过阶梯速度 → 用单独值（界面里的「阶梯速度」框）；
2. 否则如果它的**运行速度**被石斧单独改过 → 跟随它自己的运行速度
   （视作「专门调过的扶梯」，全局阶梯速度不再影响它）；
3. 否则如果 `/jietispeed` 设过**全局阶梯速度** → 用全局值；
4. 否则 → **跟随自己的运行速度**（= 全局运行速度）。

> 也就是说：默认什么都没设时，阶梯速度就等于扶梯速度。

### 指令

先约定两个词：

- **全局扶梯** = 没被石斧单独改过的扶梯（也包括「改过、但运行速度和阶梯速度都仍然与全局值一致」的）。
- **全局扶梯速度 / 全局阶梯速度** = 这两条指令设置的全局值；一条扶梯没被单独设置时就跟随它。

**总规则（所有指令与界面都遵守）**

> **设置「扶梯速度」时，「阶梯速度」一起跟随变成同一个值；设置「阶梯速度」时，绝不改「扶梯速度」。**
>
> 例：`/futispeed -f 5` → 全游戏扶梯速度变 5，**阶梯速度也变 5**；
> `/jietispeed -f 5` → **只有**全游戏阶梯速度变 5，扶梯速度一点不动。

**`/futispeed`（改运行速度 ⇒ 阶梯速度一起跟随）**

| 指令 | 作用 |
| --- | --- |
| `/futispeed` | **显示当前扶梯速度**。站在扶梯上（或准星指向 / 附近 16 格内有扶梯）就显示那一条的速度，并标出「单独设置 / 跟随全局」；否则显示全局默认值。 |
| `/futispeed X` | 全局运行速度 = X，**全局阶梯速度同步镜像为 X**。只改全局扶梯；运行速度或阶梯速度与全局不同的扶梯原封不动。 |
| `/futispeed X to Y` | 只有当前全局运行速度正好是 X 时才改成 Y（阶梯速度同步跟随）；没有速度为 X 的就不改。 |
| `/futispeed -f X` | **强制**游戏内所有扶梯运行速度 = X（不管有没有被改过），并清掉单独设置，于是**所有扶梯的阶梯速度也变成 X**。 |
| `/futispeed -f X to Y` | 把所有运行速度为 X 的扶梯（含被改过的）改成 Y，它们的阶梯速度一起跟随变成 Y；速度不是 X 的保持不变。 |

**`/jietispeed`（只改阶梯速度，永远不动运行速度）**

| 指令 | 作用 |
| --- | --- |
| `/jietispeed` | **显示当前阶梯速度**（定位规则同 `/futispeed`），并附上全局阶梯速度状态。 |
| `/jietispeed X` | 全局阶梯速度 = X。**只改全局扶梯**；速度与全局不同的扶梯原封不动。 |
| `/jietispeed X to Y` | 只有当前全局阶梯速度正好是 X 时才改成 Y；没有速度为 X 的就不改。 |
| `/jietispeed -f X` | **强制**所有扶梯阶梯速度 = X——包括被石斧单独改过运行速度的那些（给它们显式补一条阶梯设置覆盖）。**扶梯速度一点不动。** |
| `/jietispeed -f X to Y` | 把所有阶梯速度为 X 的扶梯改成 Y（会按取值优先级识别「阶梯速度确实等于 X」的扶梯，含只改过运行速度的）；不是 X 的保持不变。 |

**`/futimusic`（扶梯音频，数据模型与 `/futispeed` 完全对称）**

「音频」可以是 `default`（模组内置的 `subway_escalator`）、玩家上传/导入的音频文件名（**要带后缀**，如 `example.ogg`；少写 `.ogg` 也会自动补），或 `off` / `none`（清除默认音频）。

| 指令 | 作用 |
| --- | --- |
| `/futimusic` | **显示当前扶梯播放的音频名**（定位规则同 `/futispeed`），并标出「单独绑定 / 使用默认音频」。 |
| `/futimusic <名字>` | **默认音频 = 名字**：没有单独绑定音频的扶梯都会播放它；已经单独绑定过的扶梯（石斧界面绑的）不受影响。 |
| `/futimusic <X> to <Y>` | 只有**默认音频**正好是 X 时才改成 Y；已经单独绑定音频的扶梯一律不动（与 `/futispeed X to Y` 对称）。 |
| `/futimusic -f <名字>` | **强制游戏内所有扶梯都用这个音频**：设成默认音频并清掉所有单独绑定。 |
| `/futimusic -f <X> to <Y>` | 把所有音频为 X 的扶梯（**含单独绑定的**）改成 Y；音频不是 X 的保持不变。 |

**`/futiloud`（扶梯音量，数据模型与 `/futispeed` 完全对称）**

音量范围 **1~1000**：**100 = 原始音量（1.0×）**，1000 = **10× 放大**。

| 指令 | 作用 |
| --- | --- |
| `/futiloud` | **显示当前扶梯音量**（定位规则同 `/futispeed`），并标出「单独设置 / 使用默认音量」。 |
| `/futiloud <音量>` | **默认音量 = 音量**：没有单独设置过音量的扶梯都会用它；已经单独设置过的（石斧界面）不受影响。 |
| `/futiloud <X> to <Y>` | 只有**默认音量**正好是 X 时才改成 Y；单独设置过音量的一律不动（与 `/futispeed X to Y` 对称）。 |
| `/futiloud -f <音量>` | **强制游戏内所有扶梯都用这个音量**：设成默认音量并清掉所有单独设置。 |
| `/futiloud -f <X> to <Y>` | 把所有音量正好是 X 的扶梯（**含单独设置的**）改成 Y；音量不是 X 的保持不变。 |

> 取值优先级：**单独设置（石斧界面） &gt; 默认音量（`/futiloud`） &gt; 100**。
> 单独设置的值等于默认音量时会自动不存（省存档），读出来仍是那个值。

> `/futimusic default` = 所有**没单独绑过音频**的扶梯都播放模组内置音频；
> `/futimusic -f default` = 强制**游戏内所有**扶梯（含单独绑过的）都播放内置音频。
> 距离规则与其它音频一致：**离开整条扶梯 16 格内**才听得见。

> 联动关系：**`/futispeed` 改了运行速度，阶梯速度一定跟着变**（清掉单独阶梯设置 / 镜像全局阶梯值）；
> **`/jietispeed` 只写阶梯速度，绝不会反过来改运行速度**——这条规则对 `X`、`X to Y`、`-f X`、
> `-f X to Y` 每一种写法都成立。
>
> 只保留 `-f` 一种强制写法（旧写法 `f` 已删除）。

> 实现说明（为什么能做到逐条独立）：
> MTR 的 `escalator_up/down.png` 是 **320×5120 的 16 帧纵向长条**。由于 Minecraft 的
> `AnimationMetadataSection.calculateFrameSize` 在未显式指定帧尺寸时取 `Math.min(w, h)`，
> 在贴图图集里这张贴图只占 **320×320（单帧）** 一个格子，原版是靠每 tick 上传长条的不同
> 320 高条带来“播放动画”的——也就是说图集里的这份资源 **全地图只有一套、只有一个相位**，
> 靠它无法让不同扶梯各动各的。
>
> SmoothLift 于是绕开共享图集：把 MTR 那张 **320×5120 长条** 作为模组自己的
> `DynamicTexture` 载入；把 MTR 的 12 个阶梯模型覆写为指向 SmoothLift 的底图；再在
> `WorldRenderEvents.AFTER_ENTITIES` 里，按 **每个台阶方块所属扶梯自己的速度** 计算帧号，
> 从长条贴图里取对应的一段重绘台阶面。
> 因为每个方块引用的是长条贴图里**不同的条带**，所以 **真正实现了逐条扶梯独立动画**。
>
> 那张底图是**全透明**的，用来把 MTR 原版那份「静止的台阶面」彻底隐藏掉：
> MTR 的 `ESCALATOR_STEP` 注册在 **cutout** 渲染层（`InitClient` 里 `RenderLayer.getCutout()`），
> cutout 会做 alpha 裁剪，全透明像素被整片丢弃 —— 既不显示也不写深度。
> 于是**只剩下 SmoothLift 重绘的这份会动的台阶**，不会再出现「两个重叠的台阶，一个不动一个动」。
> （底图不能删：SmoothLift 是靠「sprite 名字 = `smoothlift:block/step_static_up|down`」
> 从 MTR 的烘焙模型里认出哪些面是台阶面的，它相当于一个占位标记。）
>
> 因为静止的那份已被隐藏，**没被重绘的地方就是空的（露空）**，所以重绘范围不能是固定值：
> 直接取客户端的**有效渲染距离**（`Options.getEffectiveRenderDistance()`，即客户端设置与服务端
> 视距的较小值）再加一个区块和 32 格余量 —— 「能看见的区块」和「能看见的台阶」永远是同一批，
> 玩家走远也不会出现台阶在远处消失/露空。
> 放大范围带来的额外开销用**真正的视锥剔除**抵消：每个阶梯方块的 `AABB` 随索引一起缓存
> （见 `EscalatorStepIndex#boxes()`），每帧对视野内的方块做一次 `Frustum#isVisible`，
> 没有逐帧分配；拿不到视锥时才退回「点积 + 距离」的粗略剔除。
>
> 帧号公式：`frame = floor((tick + partialTick) * (speed / 0.625)) mod 16`（0.625 = MTR 原版
> 阶梯动画对应的标定速度）。无状态、确定性：同速度的扶梯同步，不同速度的扶梯相位自然分叉。

## 扶梯声音

石斧界面里的「选择扶梯音乐」子界面分三段，上下用鼠标滚轮滚动：

| 段落 | 说明 |
| --- | --- |
| ① **模组内置音频** | **随模组 jar 分发**，装了模组就自带，点一下即绑定。玩家**不需要准备任何文件，也不需要 ffmpeg 之类的转码工具**。 |
| ② 已存入存档的音频 | 点名字=绑定此扶梯；右侧「删除」=从存档移除。 |
| ③ 存档文件夹 `smoothlift_audio` 待导入 | 点=把文件导入存档并绑定（之后删掉原文件仍可播放）。 |

内置音频（当前只有 1 段，按需求精简）放在 jar 内的
`assets/smoothlift/sounds/audio/subway_escalator.ogg`，由模组自己的
`assets/smoothlift/sounds.json` 注册成 `smoothlift:audio/subway_escalator` 声音事件，
所以走的是原版资源包加载路径 —— 不需要网络同步音频字节、不需要往声音引擎里注入、
也不存在「解码失败」这回事。

| key | 界面显示名 | 时长 | 许可 |
| --- | --- | --- | --- |
| `subway_escalator` | 内置 · 地铁自动扶梯 | 19.8s（原始 23.6s，已剪辑） | **CC0 1.0**（公共领域，无需署名，可商用） |

> 这段音频已针对**循环播放**做过剪辑：原始录音是「11.8s 声音 + 2.2s 完全静音 + 9.4s 声音」，
> 已剪掉中间 2.2s 空白与首尾静音（共约 2.8s）并把两段拼接；拼接处 0.40s、首尾循环点 0.70s
> 各做一次等功率交叉淡化，因此循环时无停顿、无爆音。未做任何音量处理，峰值仍为 -23.8 dBFS。

> 音频素材为 Freesound 的 `4_Escalator.wav`
> （作者 `14G_Panska_Hoskovcova_Eliska`，<https://freesound.org/people/14G_Panska_Hoskovcova_Eliska/sounds/419482/>），
> 页面许可字段为 **Creative Commons 0**（链接指向 `creativecommons.org/publicdomain/zero/1.0/`），
> 原文：*"You can copy, modify, distribute and perform the sound, even for commercial purposes,
> all without the need of asking permission to the author."*
> 出处与许可同时写在 jar 内 `AUDIO-CREDITS.txt`（CC0 无署名义务，保留仅为交代来源）。

> **为什么自备音频会「绑定了却没声音」**：Minecraft 用 stb_vorbis 解码，只认
> **Ogg 容器 + Vorbis 编码**。把 MP3 直接改名为 `.ogg`（报 `Failed to find Ogg header`）、
> 转成 Ogg Opus、转成 Ogg FLAC 都会在解码这一步失败，表现为静音。内置音频在打包前
> 已经统一转成 Ogg Vorbis，因此开箱即用。转码只在**作者制作音频**时用到，和玩家无关。

其余行为：声音音量随玩家与扶梯的距离线性衰减，**离开整条扶梯 16 格内**才听得见
（距离按**整条扶梯**算 —— 取玩家到扶梯链上最近方块的距离，不是到某个方块的距离，
所以 40 格长的扶梯不会因为音频绑在一端就在另一端静音）；
最终音量 = 距离衰减 × 这条扶梯自己的音量设定（1~1000，100 = 原始音量）。同一时刻只播放离玩家最近的那条已绑定
扶梯的声音；没绑定声音的扶梯保持静音。

> **为什么能调到 1000（要同时拆掉两层夹取）**：
> ① **Minecraft 侧**：`SoundEngine.calculateVolume` 把增益硬夹在 `[0, 1]`，由客户端 Mixin
> **只对本模组的扶梯声音**把上限抬到 10×；
> ② **OpenAL 侧**：有效增益还会被夹到音源自己的 `AL_MAX_GAIN`，而它**默认就是 1.0**。
> 只做 ① 是不够的 —— `alSourcef(AL_GAIN, 2.0)` 照发，OpenAL 仍会把有效增益压回 1.0
> （这正是「UI 显示 200 但声音不变大」的原因）。开播时把该源的 `AL_MAX_GAIN` 抬到 10 才真正放大。
> 其它音效一律交回原版、行为零变化。100 = 1.0×（原始音量），1000 = 10× 放大
> （放大会削波失真，按需使用；长时间 10× 也可能刺耳）。

拆掉扶梯方块时，该方块的速度 / 音量 / 音频绑定会一并清除并**立即广播同步**给所有玩家，
不会留下「拆了还在响」的幽灵声音。

> **默认音频层（`/futimusic`）**：`/futimusic <名字>` 设定的是一层「全局默认音频」，
> 只作用于**没有单独绑定音频**的扶梯——单独绑过的（石斧界面绑的）优先，永远盖过默认层，
> 这与速度的「全局值 vs 单独设置」完全对称。只有 `/futimusic -f <名字>` 才会连单独绑定一起覆盖。
> 若从没设过默认音频（旧存档），客户端**完全不会**做「找附近扶梯」的额外扫描，性能与行为都不变。

音频素材的出处与许可（CC0 1.0，无署名义务）见 jar 内的 `AUDIO-CREDITS.txt`。

## Introduction
This mod makes the escalators in MTR run more smoothly. Use /futispeed X to change the escalator speed (in blocks per second). Use a stone axe to right‑click the left and right ends at the very bottom of an escalator to adjust its speed individually. Use /futimusic to set/swap escalator audio (including the mod's bundled sound), and /futiloud to set the volume for every escalator at once (1~1000, 100 = original volume).

## Screen (stone axe)

Right‑clicking an escalator with a stone axe opens a screen with exactly two text fields and one button:

| Widget | Purpose |
| --- | --- |
| **Elevator speed** field | running speed of this escalator (both parallel columns) |
| **Step speed** field | step animation speed of this escalator |
| **Align step speed to elevator speed** button | fills the step field with the elevator field's value |
| **Sound volume** field | sound volume of this escalator, **1~1000** (**100 = original volume**, 1000 = 10× boost); out-of-range values are clamped |
| **Sound settings…** button | opens the custom-sound sub-screen (pick/bind audio) |

- Changes are applied **when you leave the screen with ESC** (Enter also works); there is no confirm
  button, and nothing is sent if neither value changed.
- Changing the **elevator speed** also changes the **step speed** (unless you manually edited the step field).
- Changing the **step speed** never changes the elevator speed.
- Multiple escalators animate at their own individual speeds at the same time.
- Adjusting one side with the stone axe **syncs both sides** (an MTR escalator is two parallel columns,
  `side=left` and `side=right`), so you no longer only change half of it.

### Step speed precedence

1. an **individual** step speed set for this escalator (the step field) → use it;
2. otherwise, if its **running speed** was individually changed with the stone axe → follow its own
   running speed (it counts as a "specially tuned" escalator, so the global step speed no longer applies);
3. otherwise, if `/jietispeed` has a **global step speed** enabled → use that;
4. otherwise → **follow its own running speed** (= the global running speed).

### Commands

Two terms first:

- **global escalator** = an escalator never individually changed with the stone axe (this also covers ones
  that were changed but whose running speed *and* step speed still equal the global values).
- **global running / step speed** = the values these commands set; escalators without an individual
  setting follow them.

**Global rule (applies to every command and to the screen)**

> **Setting the *running* speed also sets the *step* speed to the same value; setting the *step* speed
> never touches the running speed.**
>
> e.g. `/futispeed -f 5` → every escalator's running speed becomes 5 **and its step speed becomes 5**;
> `/jietispeed -f 5` → **only** every escalator's step speed becomes 5, running speeds stay as they were.

**`/futispeed` (sets the running speed ⇒ the step speed follows)**

| Command | Effect |
| --- | --- |
| `/futispeed` | **shows the current escalator's speed.** Stand on an escalator (or point at one / have one within 16 blocks) and it reports that escalator's speed and whether it is *individually set* or *following the global value*; otherwise it shows the global default. |
| `/futispeed X` | global running speed = X, and the global step speed is **mirrored to X**. **Only global escalators**; escalators whose running or step speed differs from the global values are left untouched. |
| `/futispeed X to Y` | only if the current global running speed is exactly X → Y (step speed follows); otherwise nothing changes. |
| `/futispeed -f X` | **force** every escalator in the world to X (customised or not), clearing individual settings, so **every escalator's step speed becomes X too**. |
| `/futispeed -f X to Y` | every escalator whose running speed is X (customised ones included) → Y, and their step speed follows to Y; others unchanged. |

**`/jietispeed` (sets the step speed only, never touches the running speed)**

| Command | Effect |
| --- | --- |
| `/jietispeed` | **shows the current step speed** (same locating rule as `/futispeed`), together with the global step-speed state. |
| `/jietispeed X` | global step speed = X. **Only global escalators**; escalators differing from the global values are left untouched. |
| `/jietispeed X to Y` | only if the current global step speed is exactly X → Y; otherwise nothing changes. |
| `/jietispeed -f X` | **force** every escalator's step speed to X — including ones whose *running* speed was individually changed (an explicit step entry is written for them so the force really covers everything). **Running speeds are never touched.** |
| `/jietispeed -f X to Y` | every escalator whose step speed is X → Y (resolved through the precedence chain, so escalators that only had their running speed changed are covered too); others unchanged. |

**`/futimusic` (escalator audio; data model fully symmetric with `/futispeed`)**

An "audio" can be `default` (the mod's bundled `subway_escalator`), a player-uploaded/imported audio
file name (**with its extension**, e.g. `example.ogg`; a missing `.ogg` is appended automatically), or
`off` / `none` (clear the default audio).

| Command | Effect |
| --- | --- |
| `/futimusic` | **shows the audio name the current escalator is playing** (same locating rule as `/futispeed`), and whether it is *individually bound* or *using the default audio*. |
| `/futimusic <name>` | **default audio = name**: every escalator without an individually bound audio plays it; escalators already bound individually (via the stone-axe screen) are not affected. |
| `/futimusic <X> to <Y>` | only if the **default audio** is exactly X → Y; escalators with an individually bound audio are never touched (symmetric with `/futispeed X to Y`). |
| `/futimusic -f <name>` | **force every escalator in the world to use this audio**: sets it as the default and clears all individual bindings. |
| `/futimusic -f <X> to <Y>` | every escalator whose audio is X (**individually bound ones included**) → Y; escalators whose audio is not X are unchanged. |

**`/futiloud` (escalator volume; data model fully symmetric with `/futispeed`)**

Volume range **1~1000**: **100 = original volume (1.0×)**, 1000 = **10× boost**.

| Command | Effect |
| --- | --- |
| `/futiloud` | **shows the current escalator's volume** (same locating rule as `/futispeed`), and whether it is *individually set* or *using the default volume*. |
| `/futiloud <volume>` | **default volume = volume**: every escalator without an individually set volume uses it; escalators already set individually (via the stone-axe screen) are not affected. |
| `/futiloud <X> to <Y>` | only if the **default volume** is exactly X → Y; escalators with an individually set volume are never touched (symmetric with `/futispeed X to Y`). |
| `/futiloud -f <volume>` | **force every escalator in the world to use this volume**: sets it as the default and clears all individual settings. |
| `/futiloud -f <X> to <Y>` | every escalator whose volume is exactly X (**individually set ones included**) → Y; escalators whose volume is not X are unchanged. |

> Precedence: **individual setting (stone-axe screen) &gt; default volume (`/futiloud`) &gt; 100**.
> An individual value equal to the default is not stored (saves save-file space) but still reads back as that value.

> `/futimusic default` = every escalator **without an individually bound audio** plays the mod's bundled
> audio; `/futimusic -f default` = force **every** escalator in the world (individually bound ones
> included) to play the bundled audio. The distance rule is the same as all other audio: you hear it only
> **within 16 blocks of the whole escalator**.

> Only the `-f` form is kept (the older `f` alias has been removed).
>
> Coupling: **`/futispeed` changes the running speed and the step speed always follows** (individual step
> settings are cleared / the global step value is mirrored); **`/jietispeed` only writes the step speed and
> never changes the running speed** — true for every form (`X`, `X to Y`, `-f X`, `-f X to Y`).

> Why it can do true per‑escalator animation: MTR's `escalator_up/down.png` is a **320×5120, 16‑frame
> vertical strip**. Because Minecraft's `AnimationMetadataSection.calculateFrameSize` uses `Math.min(w, h)`
> when the frame size is unspecified, in the atlas this texture only occupies a **320×320 (single‑frame)**
> slot, and vanilla "plays" it by uploading a different 320‑tall band each tick — i.e. the atlas holds a
> **single, shared, single‑phase** copy. SmoothLift sidesteps the shared atlas: it loads MTR's full
> **320×5120 strip** as its **own** `DynamicTexture`, overrides MTR's 12 step models to point at SmoothLift's
> base texture, then in `WorldRenderEvents.AFTER_ENTITIES` re‑draws each step block's faces selecting a
> frame from **that escalator's own speed**. Since every block references a **different band** of the strip,
> true per‑escalator independence is achieved.
>
> That base texture is **fully transparent**, which hides MTR's own static step faces completely:
> `ESCALATOR_STEP` is registered on the **cutout** layer (`RenderLayer.getCutout()` in `InitClient`), and
> cutout alpha‑test discards fully transparent texels — no colour, no depth. So **only SmoothLift's animated
> copy remains** and the "two overlapping steps, one static one moving" artefact is gone. (The texture can't
> be deleted: SmoothLift identifies the step faces of MTR's baked model by the sprite **name**
> `smoothlift:block/step_static_up|down`, so it acts as a placeholder marker.)
>
> Because the static copy is now hidden, **whatever is not re‑drawn simply isn't there** (holes), so the
> re‑draw range is **not a constant**: it follows the client's **effective render distance**
> (`Options.getEffectiveRenderDistance()`, i.e. client setting clamped by server view distance) plus one
> chunk and a 32‑block margin — the chunks you can see and the steps you can see are always the same set,
> so steps no longer disappear when you walk away. The extra cost of the larger range is paid back with
> **real frustum culling**: every step block's `AABB` is cached alongside the chunk index
> (`EscalatorStepIndex#boxes()`) and tested once per frame with `Frustum#isVisible`, with no per‑frame
> allocation. The old cheap "dot product + distance" test is only a fallback when no frustum is available.
>
> Frame formula: `frame = floor((tick + partialTick) * (speed / 0.625)) mod 16` (0.625 = MTR's vanilla step
> animation calibration speed).

## Escalator sounds

The "pick escalator music" sub-screen is a scrollable three-section list:

1. **Bundled sounds** — shipped inside the mod jar; one click to bind. Players need **no audio
   file and no conversion tool (no ffmpeg)**.
2. **Stored in the save** — click a name to bind, click *Delete* to remove it.
3. **Pending in `<save>/smoothlift_audio`** — click to import into the save and bind (the original
   file may then be deleted).

The bundled sound (currently just one) lives in `assets/smoothlift/sounds/audio/subway_escalator.ogg`
and is registered by the mod's own `assets/smoothlift/sounds.json` as
`smoothlift:audio/subway_escalator`, so it uses the vanilla resource-pack path: no byte sync,
no injection into the sound engine, and no such thing as a "decode failure".

| key | display name | length | license |
| --- | --- | --- | --- |
| `subway_escalator` | 内置 · 地铁自动扶梯 | 19.8s (source 23.6s, edited) | **CC0 1.0** (public domain, no attribution, commercial use OK) |

> The clip was edited for **looping**: the original recording is "11.8 s of sound + 2.2 s of total
> silence + 9.4 s of sound". The 2.2 s gap and the head/tail silence (≈2.8 s total) were removed and
> the two takes joined; a 0.40 s equal-power crossfade sits at the join and a 0.70 s one across the
> loop point, so it loops with no pause and no click. No gain processing — peak stays at -23.8 dBFS.

> Source: Freesound `4_Escalator.wav` by `14G_Panska_Hoskovcova_Eliska`
> (<https://freesound.org/people/14G_Panska_Hoskovcova_Eliska/sounds/419482/>). The page's license
> field is **Creative Commons 0** (linking to `creativecommons.org/publicdomain/zero/1.0/`):
> *"You can copy, modify, distribute and perform the sound, even for commercial purposes, all
> without the need of asking permission to the author."* Credits are also listed in
> `AUDIO-CREDITS.txt` inside the jar (CC0 requires no attribution; it is kept for provenance).

> **Why a self-supplied file can stay silent**: Minecraft decodes with stb_vorbis, which only
> accepts the **Ogg container with the Vorbis codec**. Renaming an MP3 to `.ogg`
> (`Failed to find Ogg header`), Ogg Opus, and Ogg FLAC all fail at the decode step. The bundled
> sounds are converted to Ogg Vorbis before packaging, so they work out of the box. Conversion is
> only needed by the **mod author** when producing audio — never by players.

Volume falls off linearly with distance and is audible within **16 blocks of the whole
escalator** — the distance is measured from the escalator chain (nearest block), not from one
block, so a 40-block escalator does not go silent just because the audio is bound at one end.
The final volume is `distance falloff × this escalator's volume setting (1~1000, 100 = original)`. Only the nearest
bound escalator plays at a time; escalators without a bound sound stay silent.

> **Why it can go up to 1000 (two clamps must both be lifted)**:
> ① **Minecraft side** — `SoundEngine.calculateVolume` hard-clamps the gain to `[0, 1]`; a client mixin
> raises the cap to 10× **only for this mod's escalator sounds**.
> ② **OpenAL side** — the effective gain is *also* clamped to the source's `AL_MAX_GAIN`, which
> **defaults to 1.0**. Doing only ① is not enough: `alSourcef(AL_GAIN, 2.0)` is sent, but OpenAL
> still clamps the effective gain back to 1.0 — that is exactly why "the UI shows 200 but the sound
> doesn't get louder". Lifting that source's `AL_MAX_GAIN` to 10 makes the boost real.
> Every other sound is handed back to vanilla unchanged. 100 = 1.0× (original), 1000 = 10× boost
> (boosting can clip/distort — use as needed).

> **Default-audio layer (`/futimusic`)**: `/futimusic <name>` sets a *global default audio* that only
> applies to escalators **without an individually bound audio** — an individual binding (from the
> stone-axe screen) always wins over the default layer, exactly symmetric with the speed model's
> "global value vs individual setting". Only `/futimusic -f <name>` also overwrites individual
> bindings. If no default audio has ever been set (old saves), the client does **no** "find nearby
> escalator" scanning at all — performance and behaviour are unchanged.

Audio source and license (CC0 1.0, no attribution required) are listed in
`AUDIO-CREDITS.txt` inside the jar.

## Setup

For setup instructions, please see the [Forge Documentation page](https://docs.minecraftforge.net/en/latest/gettingstarted/) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

The code of this mod is MIT (see `LICENSE`). The **bundled audio**
`assets/smoothlift/sounds/audio/subway_escalator.ogg` is third-party material released under
**CC0 1.0** (public domain dedication) — no attribution is legally required and commercial use is
allowed. See `AUDIO-CREDITS.txt` for the source URL and the verbatim license statement.