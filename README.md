# SmoothLift

## 介绍
这个模组可以让MTR里的扶梯变得更加平滑 使用 /futispeed X 改变扶梯运行速度（单位：格/秒）,使用石斧点击扶梯下端可以更改扶梯速度。支持MTR4和MTR3。用 /futimusic 可以为扶梯设置/更换音频（含模组内置音频），用 /futiloud 可以统一调所有扶梯的音量（1~1000，100 = 原始音量），用 /futihelp 可以开关香港地铁式的「视障人士提升音」（进扶梯一端急促咔咔、出扶梯一端缓慢咔咔），用 /futihelploud 调这路提示音的音量，用 /futiround 调扶梯音效的淡入淡出范围（默认 16 格）、用 /futihelpround 调无障碍提示音的淡入淡出范围（默认 4 格），用 /futihelpspeed in|out 调这路提示音的速率（每秒响几次，1~20 Hz，默认 进入 10 / 离开 1）。

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

**`/futihelp`（无障碍提示音开关，数据模型与 `/futispeed` 完全对称）**

香港地铁式的「视障人士提升音」：**进扶梯那一端**发**急促**的「咔啪」无咚细金属声（默认约 **1/10 秒一次 = 10 Hz**，催你尽快踏上），
**出扶梯那一端**发**缓慢**的「咔啪」无咚细金属声（默认约 **1 秒一次 = 1 Hz**，提示前面就是出口）。
音色是**没有低频「咚」、干干脆脆的清脆细金属「咔啪」**（不是电子「滴滴」）。
**这两个速率从 1.31 起可以用 `/futihelpspeed in|out` 改**（1~20 Hz，见下）。
两路音源**只装在扶梯的首、尾两块扶梯方块上**
（每个端头那一对左右并列的方块算一块，**整条扶梯中间不放音源**），射程 4 格，
所以站在底下只听得见急促那路、走到顶上就只剩缓慢那路。**默认开启。**
向上的扶梯：**最下面第一块**放急促（上客端）、**最上面最后一块**放缓慢（落客端）。

> 首尾间距小于 8 格（短扶梯）时，两端各 4 格的覆盖会重叠，站在中间会同时听到两头 —— 这是**有意保留**的
> （真实世界的短扶梯也是这样）。射程就是固定 4 格，不会为了「中段必须静音」再收窄。

| 指令 | 作用 |
| --- | --- |
| `/futihelp` | **显示当前扶梯的提示音开关**（定位规则同 `/futispeed`），并标出「单独设置 / 使用默认开关」。 |
| `/futihelp on` `/futihelp off` | **默认开关 = 开/关**：没有单独设置过的扶梯都会用它；单独设置过的（石斧界面）不受影响。 |
| `/futihelp on to off` / `off to on` | 只有**默认开关**正好是 X 时才改成 Y；单独设置过的一律不动（与 `/futispeed X to Y` 对称）。 |
| `/futihelp -f on` `/futihelp -f off` | **强制游戏内所有扶梯 = 开/关**：设成默认开关并清掉所有单独设置。 |
| `/futihelp -f on to off` / `-f off to on` | 把所有**生效开关**正好是 X 的扶梯（**含单独设置的**）改成 Y；不是 X 的保持不变。 |

> 取值优先级：**单独设置（石斧界面里的「无障碍：开/关」按钮） &gt; 默认开关（`/futihelp`） &gt; 开**。
>
> 说明：
> - 开关只影响这路提示音，**不影响** `/futimusic` 绑定的那条扶梯运行底噪；
> - 扶梯被停掉（`status=false`）时本来就静音，这是另一条独立规则，与本开关无关；
> - 旧存档（1.15 及更早）没有这一段 → 一律按**开**处理，升级上来行为不变；
> - 提示音**只装在扶梯的首、尾两块方块上**（每个端头那一对左右并列的方块算一块，
>   整条扶梯**中间**不放音源），射程只有 **4 格**，按到**该端头**的距离平方衰减。
>   所以长扶梯走到中段两路都听不见 —— 提示音只在进出口有意义；短扶梯（首尾间距 &lt; 8 格）
>   两端会重叠、中间同时听到两头，这是有意保留的（真实世界的短扶梯也这样）。
>   别和「扶梯运行底噪」搞混：`/futimusic` 绑的那条底噪作用在**整条扶梯**上、射程 **16 格**，
>   坐一整程都听得见才是对的。

> `/futimusic default` = 所有**没单独绑过音频**的扶梯都播放模组内置音频；
> `/futimusic -f default` = 强制**游戏内所有**扶梯（含单独绑过的）都播放内置音频。

**`/futihelploud`（无障碍提示音音量，数据模型与 `/futiloud`、`/futihelp` 完全对称）**

调上面那路「视障人士提升音」的**音量**（1~1000，100 = 原始音量，可放大到 1000 = 10×）。
**注意它与 `/futiloud` 是两件不同的事**：`/futiloud` 管的是「扶梯运行底噪」（整条扶梯、射程 16 格）的音量，
本指令管的是「无障碍提示音」（装在端头那一块扶梯方块上、射程 4 格）的音量。
**两套数据、两个指令互不影响**，两个音量在石斧界面里也是并排的两个输入框。

| 指令 | 作用 |
| --- | --- |
| `/futihelploud` | **显示当前扶梯的提示音音量**（定位规则同 `/futispeed`），并标出「单独设置 / 使用默认音量」。 |
| `/futihelploud <音量>` | **默认音量 = 音量**：没有单独设置过音量的扶梯都会用它；单独设置过的（石斧界面）不受影响。 |
| `/futihelploud <X> to <Y>` | 只有**默认音量**正好是 X 时才改成 Y；单独设置过的一律不动（与 `/futiloud X to Y` 对称）。 |
| `/futihelploud -f <音量>` | **强制游戏内所有扶梯的提示音都用这个音量**：设成默认音量并清掉所有单独设置。 |
| `/futihelploud -f <X> to <Y>` | 把所有**生效音量**正好是 X 的扶梯（**含单独设置的**）改成 Y；不是 X 的保持不变。 |

> 取值优先级：**单独设置（石斧界面里的「提示音音量」输入框） &gt; 默认音量（`/futihelploud`） &gt; 100**。
> 单独设置的值等于默认音量时会自动不存（省存档），读出来仍是那个值。
>
> 提示音的实际响度 = **距离衰减（默认 4 格内平方衰减到 0）× 这个百分比**。默认 100，所以旧存档升级上来音量不变。
> 距离规则是**到某一个端头默认 4 格**（不是 16 格）：站在长扶梯中段时两端都超出范围 → 两路都不响，
> 这正是「提示音只在进出口有意义」的设计；「离开整条扶梯 16 格」那条规则属于**扶梯运行底噪**（`/futiloud`），
> 别混在一起看。**这两个距离从 1.24 起可以分别用 `/futihelpround` 与 `/futiround` 改**（见下）。
>
> 【1.20】`&gt;100` 现在真的能放大了：以前提示音被漏在音量放行逻辑之外，原版把它的增益夹在 [0,1]，
> 所以「调到 100 以上完全没变化」。现在它和底噪走同一套放行（客户端 Mixin 认 `GainManagedSound`
> 接口 + 开播时把 OpenAL 的 `AL_MAX_GAIN` 抬到 10×），1~1000 全部生效。

**`/futiround`（扶梯音效淡入淡出范围，单位格，默认 16）**

控制**扶梯运行底噪**（整条扶梯一起响那路声音，见 `/futiloud`）「多远还听得见」：
范围就是在距离衰减的半径 —— 半径内线性衰减到 0，所以「听得见的最远距离」就等于它。
**1~128 格，默认 16**。旧存档 / 从没设过这条指令的玩家，一律按默认 16 格处理。

| 指令 | 作用 |
| --- | --- |
| `/futiround` | **显示当前扶梯的音效范围**（定位规则同 `/futispeed`），并标出「单独设置 / 使用默认范围」。 |
| `/futiround <范围>` | **默认范围 = 范围**：没有单独设置过范围的扶梯都会用它；单独设置过的不受影响。 |
| `/futiround <X> to <Y>` | 只有**默认范围**正好是 X 时才改成 Y；单独设置过的一律不动（与 `/futiloud X to Y` 对称）。 |
| `/futiround -f <范围>` | **强制游戏内所有扶梯都用这个范围**：设成默认并清掉所有单独设置。 |
| `/futiround -f <X> to <Y>` | 把所有**生效范围**正好是 X 的扶梯（**含单独设置的**）改成 Y；不是 X 的保持不变。 |

> 取值优先级：**单独设置 &gt; 默认范围（`/futiround`） &gt; 16**。
> 单独设置的值等于默认范围时会自动不存（省存档），读出来仍是那个值。
> 改完**下一个 tick 就生效**，不用重进世界；这是 1.24 新增的项，**没有石斧界面控件**（只能用指令改）。
>
> ★ 别和 `/futihelpround` 搞混：**本指令管「整条扶梯一起响」的运行底噪**；
> `/futihelpround` 管**端头单块**的无障碍提示音。两者默认值 16 : 4，**故意不同**。

**`/futihelpround`（无障碍提示音淡入淡出范围，单位格，默认 4）**

控制**无障碍提示音**（香港式「视障人士提升音」，见 `/futihelp`）「多远还听得见」：
范围就是端头那一对扶梯方块周围的衰减半径（**平方**衰减到 0）。
**1~128 格，默认 4**。旧存档 / 从没设过这条指令的玩家，一律按默认 4 格处理。

| 指令 | 作用 |
| --- | --- |
| `/futihelpround` | **显示当前扶梯的提示音范围**（定位规则同 `/futispeed`），并标出「单独设置 / 使用默认范围」。 |
| `/futihelpround <范围>` | **默认范围 = 范围**：没有单独设置过范围的扶梯都会用它；单独设置过的不受影响。 |
| `/futihelpround <X> to <Y>` | 只有**默认范围**正好是 X 时才改成 Y；单独设置过的一律不动（与 `/futiloud X to Y` 对称）。 |
| `/futihelpround -f <范围>` | **强制游戏内所有扶梯的提示音都用这个范围**：设成默认并清掉所有单独设置。 |
| `/futihelpround -f <X> to <Y>` | 把所有**生效范围**正好是 X 的扶梯（**含单独设置的**）改成 Y；不是 X 的保持不变。 |

> 取值优先级：**单独设置 &gt; 默认范围（`/futihelpround`） &gt; 4**。
> 单独设置的值等于默认范围时会自动不存（省存档），读出来仍是那个值。
> 改完**下一个 tick 就生效**。同样**没有石斧界面控件**。
>
> 把范围调大以后两端提示音重叠会更多（本来 4 格时就只在短扶梯上重叠）—— 这是有意交给玩家的选择，
> 代码不再为「中段必须静音」额外收窄半径。

**`/futihelpspeed`（无障碍提示音速率，单位 Hz，每秒响几次）**

控制**无障碍提示音**（香港式「视障人士提升音」，见 `/futihelp`）**响得多快**。
分「进入扶梯」（上客端）与「离开扶梯」（落客端）两套，各用一个子命令。
**1~20 Hz，默认 进入 10、离开 1**（与真机听感定版的「入口 1/10 秒一次、出口 1 秒一次」一致）。
旧存档 / 从没设过这条指令的玩家，一律按 10 / 1 处理 —— 也就是听起来**和以前完全一样**。

| 指令 | 作用 |
| --- | --- |
| `/futihelpspeed` | **显示当前扶梯两头的速率**（定位规则同 `/futispeed`），并标出各自「单独设置 / 使用默认速率」。 |
| `/futihelpspeed in <Hz>` | **默认进入扶梯速率 = Hz**（例：`in 5` = 一秒响 5 次）；单独设置过的不受影响。 |
| `/futihelpspeed out <Hz>` | 同上，改的是**离开扶梯**那一路。 |
| `/futihelpspeed in\|out <X> to <Y>` | 只有**默认速率**正好是 X 时才改成 Y；单独设置过的一律不动（与 `/futiloud X to Y` 对称）。 |
| `/futihelpspeed -f in\|out <Hz>` | **强制游戏内所有扶梯这一头都用该速率**：设成默认并清掉所有单独设置。 |
| `/futihelpspeed -f in\|out <X> to <Y>` | 把所有**生效速率**正好是 X 的扶梯（**含单独设置的**）改成 Y。 |

> 取值优先级：**单独设置 &gt; 默认速率（`/futihelpspeed`） &gt; 10 / 1 Hz**。
> 单独设置的值等于默认速率时会自动不存（省存档），读出来仍是那个值。
> 改完**下一个 tick 就生效**。同样**没有石斧界面控件**。
>
> ★ 提示音上现在有**四个互不影响**的可调项，别混：
> `/futihelp`（响不响）、`/futihelploud`（多响）、`/futihelpround`（多远还听得见）、
> `/futihelpspeed`（多快）。另外 `/futiround` 管的是**整条扶梯一起响的运行底噪**，
> 与本指令无关。
>
> 上限为什么是 20 Hz：速率是靠「换素材 + 调 playback pitch」实现的，而原版把 pitch 夹在
> **0.5~2.0 倍**，一个素材只够两个八度。模组内置 **1 / 4 / 10 Hz** 三段素材，它们的可用区间
> 首尾相接，合起来正好覆盖 **1~20 Hz**。默认的 10 / 1 Hz 正好等于素材原速（pitch = 1.0），
> 所以**默认档不会有任何音色变化**。

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

**`/pbmmusic`（屏蔽门开关门提示音，MTR 平台幕门 / 半高安全门；数据模型与 `/futihelp` 完全对称）**

屏蔽门提示音分 **开门（open）/ 关门（close）** 两项，各有一份素材，各有一个子开关。

素材名的写法：

| 名字 | 含义 |
| --- | --- |
| `default` | **跟内置**：关门端 = `mdoorclose.ogg`（`default-m`），开门端 = `dooropen.ogg` |
| `default-s` | **默认（短）**：素材与前一行相同，但**不播开头的语音播报段**，只播后面的嘀嘀（= 旧版行为） |
| `default-c` | `doorclose.ogg`（内置关门备选） |
| `default-m` | `mdoorclose.ogg`（内置关门默认） |
| `none`（也可写 `off`） | **这一项不播** |
| 其它 | 玩家导入的音频文件名（**要带后缀**，如 `example.ogg`；少写 `.ogg` 也会自动补） |

| 指令 | 作用 |
| --- | --- |
| `/pbmmusic` | **显示本维度的总开关**与「开门 / 关门」两项子开关。 |
| `/pbmmusic on` `/pbmmusic off` | **本维度**总开关 = 开/关（其它维度不变）。 |
| `/pbmmusic on to off` / `off to on` | 本维度总开关正好是 X 时才改成 Y。 |
| `/pbmmusic -f on` `/pbmmusic -f off` | **强制所有维度**的总开关 = 开/关。 |
| `/pbmmusic -f on to off` / `-f off to on` | 所有维度里总开关正好是 X 的改成 Y。 |
| `/pbmmusic open` `/pbmmusic close` | **显示本维度该项**的子开关与默认素材。 |
| `/pbmmusic open on` `/pbmmusic close off` | **本维度**该项子开关 = 开/关。 |
| `/pbmmusic open on to off` / `close off to on` | 本维度该项子开关正好是 X 时才改成 Y。 |
| `/pbmmusic open -f on` / `close -f off` | **强制所有维度**该项子开关 = 开/关。 |
| `/pbmmusic open -f on to off` / `close -f off to on` | 所有维度里该项子开关正好是 X 的改成 Y。 |
| `/pbmmusic open <名字>` / `/pbmmusic close <名字>` | **本维度该项默认素材 = 名字**：没有单独设置过这一项的门都会用它；已经单独设置过的门不受影响。 |
| `/pbmmusic open <X> to <Y>` / `close <X> to <Y>` | 本维度该项默认素材正好是 X 时才改成 Y；单独设置过的一律不动。 |
| **`/pbmmusic open -f <名字>`** / **`/pbmmusic close -f <名字>`** | ★ **批量设置所有屏蔽门**：**所有维度**该项默认素材 = 名字，**并清掉「按扇门单独设置」那一项**（那批门从此跟着默认素材走）。<br>这就是「一次把所有屏蔽门设成某个 ogg」的写法；**要全部恢复内置**就写 `/pbmmusic close -f default`（开门端同理 `/pbmmusic open -f default`）。 |
| `/pbmmusic open -f <X> to <Y>` / `close -f <X> to <Y>` | 所有维度里该项**生效素材**正好是 X 的门（**含单独设置的**）改成 Y；不是 X 的保持不变。 |

> 取值优先级：**单独设置（石斧右键门开界面 / 导入文件夹） &gt; 维度默认素材（`/pbmmusic open|close <名字>`） &gt; 内置**
> （关门 `mdoorclose.ogg` / 开门 `dooropen.ogg`）。
>
> ★ **想「一次性把所有门都设成同一个声音」必须带 `-f`** —— 不带 `-f` 只改「默认素材」，
> 之前用石斧单独设置过的那些门会**保持它们自己的声音**。

**关门提示音为什么听起来是「先播报、再嘀嘀」（自动分段）**

内置的 `mdoorclose.ogg` / `doorclose.ogg` 是一条**真实关门录音**，结构是三段：

    [语音播报 ~6.7s] → [静音 ~0.5s] → [嘀嘀 ~3.5s（约 18 声、严格 200ms 等间隔）]    全长 10.8s

而屏蔽门自己只走 **4 秒**（MTR4）。所以「整段从头播」会让嘀嘀落在门关上**之后** 3 秒多，
而「剪掉开头去对齐结尾」又会把**整段语音播报剪掉**（【1.15】之前就是这样，表现为「只有嘀嘀了」）。
两者不可兼得，除非把两段**摆到各自的时刻** —— 现在就是这么做的：

| 时刻 | 播什么 |
| --- | --- |
| **门开始开**（列车停站） | 从素材开头播 → **语音播报**；到分界点**自动停掉**，不会盖住后面的嘀嘀 |
| **门开始关** | 从「素材时长 − 剩余门程」处播 → **嘀嘀**；结尾正好落在门关上那一刻 |

- **分界点是量出来的，不是写死的**：程序自己找素材里「最后一段 ≥0.4 秒的安静」，
  取它的起点当分界（实测 6.707s / 6.424s）。所以**你自己导入的任何素材**只要也是
  「喊话 + 嘀嘀」这种结构，都会自动这么分；没有这种结构（例如开门用的 `dooropen.ogg`）
  就完全按老样子**整段播**。
- 为什么播报挂在「开门」而不是「关门前 N 秒」：MTR 的门没有关门预警阶段（红石一断立刻开走），
  **不存在**「还有 7 秒要关」这种信号，所以能用的更早锚点只有「门开」——
  这也正是现实顺序：停站时广播，关门时才嘀嘀。
- 停站时间短于播报长度时，播报会被下一站的动作自然打断，不会串到关门那串嘀嘀上。
- **不想要这段播报？** 把关门素材设成 **`default-s`**（石斧界面里点「默认（短）只播嘀嘀（default-s）」，
  或 `/pbmmusic close -f default-s`）：素材与 `default` **完全相同**，只是**开门时不再提前播播报**
  ⇒ 听起来就是纯粹一串嘀嘀（= 旧版行为），关门那一串仍照旧对齐门关上那一刻。

**`/pbmloud`（屏蔽门提示音音量，1~1000，100 = 原始音量，1000 = 10×）**

数据模型与 `/pbmmusic` 完全对称（共用音量 + open/close 两项各自的音量），写法也一一对应：

| 指令 | 作用 |
| --- | --- |
| `/pbmloud` | 显示本维度共用音量，并标出两项是否跟随共用。 |
| `/pbmloud <音量>` | 本维度共用音量 = 音量。 |
| `/pbmloud <X> to <Y>` | 本维度共用音量正好是 X 时才改成 Y。 |
| `/pbmloud -f <音量>` | **强制所有维度**共用音量 = 音量。 |
| `/pbmloud -f <X> to <Y>` | 所有维度里共用音量正好是 X 的改成 Y。 |
| `/pbmloud open|close <音量>` | 本维度该项自己的音量（没单独调过的项跟随共用默认）。 |
| `/pbmloud open|close <X> to <Y>` | 本维度该项音量正好是 X 时才改成 Y。 |
| `/pbmloud -f open|close <音量>` | 所有维度该项音量。 |
| `/pbmloud -f open|close <X> to <Y>` | 所有维度里该项音量正好是 X 的改成 Y。 |

**`/pbmround`（屏蔽门提示音淡入淡出范围，单位格，默认 16）**

控制「隔多远还听得见」——两项**共用一份**。默认 16 格比直梯的 4 格大得多：
**一列车到站时整排门都要能听见**（只对离玩家最近的那一扇发声，避免整排叠成一团）。

| 指令 | 作用 |
| --- | --- |
| `/pbmround` | 显示当前维度的范围。 |
| `/pbmround <范围>` | 本维度范围 = 范围（1~128）。 |
| `/pbmround <X> to <Y>` | 本维度范围正好是 X 时才改成 Y。 |
| `/pbmround -f <范围>` | **强制所有维度** = 范围。 |
| `/pbmround -f <X> to <Y>` | 所有维度里范围正好是 X 的改成 Y。 |

> **关门提示音是「分两段摆」的**（1.15 起）：真实录音常常是「语音播报 + 嘀嘀声」两段
> （内置 `mdoorclose.ogg` 实测：0~6.6s 播报、6.6~7.35s 静音、7.35~10.8s 嘀嘀，全长 10.8 秒），
> 而门自己只走 4 秒 —— 整段从头播会让嘀嘀落在门关上之后，剪掉开头又会把播报整段剪掉。
> 所以模组把两段**摆到各自的时刻**：**语音播报在「开门」那一瞬起播、播到分界处即停**；
> **嘀嘀仍在「关门」时播出，并让结尾落在门完全关上那一刻**。
> 分界点是**从素材里量出来的**（找最后一段 ≥0.5 秒的安静），不是写死的时间，所以自己导入的素材同样适用；
> 素材没有这种两段结构时整个跳过，行为与之前一致。

## 扶梯声音

石斧界面里的「选择扶梯音乐」子界面分三段，上下用鼠标滚轮滚动：

| 段落 | 说明 |
| --- | --- |
| ① **模组内置音频** | **随模组 jar 分发**，装了模组就自带，点一下即绑定。玩家**不需要准备任何文件，也不需要 ffmpeg 之类的转码工具**。 |
| ② 已存入存档的音频 | 点名字=绑定此扶梯；右侧「删除」=从存档移除。 |
| ③ 存档文件夹 `MBM_Audio` 待导入 | 点=把文件导入存档并绑定（之后删掉原文件仍可播放）。 |

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
This mod makes the escalators in MTR run more smoothly. Use /futispeed X to change the escalator speed (in blocks per second). Use a stone axe to right‑click the left and right ends at the very bottom of an escalator to adjust its speed individually. Use /futimusic to set/swap escalator audio (including the mod's bundled sound), /futiloud to set the volume for every escalator at once (1~1000, 100 = original volume), and /futihelp to toggle the MTR-style accessibility chime for visually impaired passengers (a fast crisp low-thump thin-metal clicking at the boarding end, a slow one at the alighting end), /futihelploud to adjust that chime's volume, /futiround to change the escalator ambience fade range (default 16 blocks), /futihelpround to change the accessibility chime's fade range (default 4 blocks) and /futihelpspeed in|out to change how fast that chime clicks (clicks per second, 1~20 Hz, defaults boarding 10 / alighting 1).

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

**`/futihelp` (accessibility chime toggle; data model fully symmetric with `/futispeed`)**

An MTR-style (Hong Kong) accessibility chime for visually impaired passengers: the **boarding end** gets a
**fast** crisp low-thump-free thin-metal "kah-pah" clicking (default ~**once per 1/10 s = 10 Hz**, hurry onto the
step) and the **alighting end** gets a **slow** clicking (default ~**once per 1 s = 1 Hz**, the exit is right ahead).
**Since 1.31 both rates are adjustable with `/futihelpspeed in|out`** (1~20 Hz, see below). The two sources are attached **only to the first and last escalator block**
(the left+right pair at each end counts as one block — **nothing in the middle of the escalator**) with a
**4-block** range, so standing at the bottom you only hear the fast one, and at the top only the slow one.
**On by default.** For an upward escalator: the **bottom-most first block** plays the fast (boarding) chime
and the **top-most last block** plays the slow (alighting) one.

> On short escalators (end-to-end span under 8 blocks) the two 4-block spheres overlap, so standing in the
> middle you hear both — this is **intentional** (a real short escalator behaves the same way). The range is
> fixed at 4 blocks and is not narrowed further to force a silent middle.

| Command | Effect |
| --- | --- |
| `/futihelp` | **shows the current escalator's chime toggle** (same locating rule as `/futispeed`), and whether it is *individually set* or *using the default toggle*. |
| `/futihelp on` / `off` | **default toggle = on/off**: every escalator without an individual setting uses it; individually set ones (stone-axe screen) are not affected. |
| `/futihelp on to off` / `off to on` | only if the **default toggle** is exactly X → Y; individually set ones are never touched (symmetric with `/futispeed X to Y`). |
| `/futihelp -f on` / `-f off` | **force every escalator in the world = on/off**: sets it as the default and clears all individual settings. |
| `/futihelp -f on to off` / `-f off to on` | every escalator whose **effective toggle** is exactly X (**individually set ones included**) → Y; the rest is unchanged. |

> Precedence: **individual setting (the “无障碍：开/关” button in the stone-axe screen) &gt; default toggle
> (`/futihelp`) &gt; on**.
>
> Notes:
> - the toggle only controls this chime; it does **not** affect the escalator ambience bound via `/futimusic`;
> - a stopped escalator (`status=false`) is silent as well — that is a separate, pre-existing rule;
> - old saves (1.15 and earlier) have no such field → treated as **on**, so upgrading changes nothing;
> - the chime is attached **only to the first and last escalator block** (the left+right pair at each end
>   counts as one block; **no source in the middle of the escalator**) and has a range of only **4 blocks**,
>   with squared falloff by distance to **that end** — so on a long escalator both sources are silent in the
>   middle, which is the point: the chime only matters at the entry/exit. On a short escalator (span under
>   8 blocks) the two spheres overlap and the middle hears both — that is intentional (a real short
>   escalator behaves the same way). Do not confuse it with the escalator ambience: the OGG bound via
>   `/futimusic` applies to the **whole escalator** with a **16-block** range — hearing it for the entire
>   ride is correct.

**`/futihelploud` (accessibility chime volume; data model fully symmetric with `/futiloud` and `/futihelp`)**

Sets the **volume of the accessibility chime** above (1~1000, 100 = original volume, up to 1000 = 10×).
**Do not confuse it with `/futiloud`**: `/futiloud` controls the **escalator ambience** (whole escalator,
16-block range), while this command controls the **accessibility chime** (attached to the single end block,
4-block range). **They are two independent data sets and two independent commands**, and the stone-axe
screen shows them as two side-by-side input boxes.

| Command | Effect |
| --- | --- |
| `/futihelploud` | **shows the current escalator's chime volume** (same locating rule as `/futispeed`), and whether it is *individually set* or *using the default volume*. |
| `/futihelploud <volume>` | **default volume = volume**: every escalator without an individual setting uses it; individually set ones (stone-axe screen) are not affected. |
| `/futihelploud <X> to <Y>` | only if the **default volume** is exactly X → Y; individually set ones are never touched (symmetric with `/futiloud X to Y`). |
| `/futihelploud -f <volume>` | **force every escalator's chime to use this volume**: sets it as the default and clears all individual settings. |
| `/futihelploud -f <X> to <Y>` | every escalator whose **effective volume** is exactly X (**individually set ones included**) → Y; the rest is unchanged. |

> Precedence: **individual setting (the “提示音音量” input in the stone-axe screen) &gt; default volume
> (`/futihelploud`) &gt; 100**. An individual value equal to the default is not stored (saves save-file
> space) but still reads back as that value.
>
> The actual loudness = **distance falloff (squared, reaching 0 at 4 blocks by default) × this percentage**. The
> default is 100, so upgrading old saves leaves the volume unchanged. **Since 1.24 that 4-block distance can be
> changed with `/futihelpround`, and the ambience's 16 blocks with `/futiround`** (see below).

**`/futiround` (escalator ambience fade range, in blocks, default 16)**

Controls how far away the **escalator ambience** (the whole-escalator sound behind `/futiloud`) is still
audible: the range is the falloff radius — linearly down to 0 at the radius, so “the farthest audible
distance” equals it. **1~128 blocks, default 16**. Old saves / players who never ran this command always
get the default 16.

| Command | Effect |
| --- | --- |
| `/futiround` | **shows the current escalator's ambience range** (same locating rule as `/futispeed`), and whether it is *individually set* or *using the default*. |
| `/futiround <range>` | **default range = range**: every escalator without an individual setting uses it; individually set ones are unaffected. |
| `/futiround <X> to <Y>` | only if the **default range** is exactly X → Y; individually set ones are never touched (symmetric with `/futiloud X to Y`). |
| `/futiround -f <range>` | **force every escalator to this range**: sets it as the default and clears all individual settings. |
| `/futiround -f <X> to <Y>` | every escalator whose **effective range** is exactly X (**individually set ones included**) → Y; the rest is unchanged. |

> Precedence: **individual setting &gt; default range (`/futiround`) &gt; 16**. An individual value equal to the
> default is not stored. It takes effect **on the very next tick** — no need to reload the world. This is a
> 1.24 addition and has **no stone-axe screen control** (command only).
>
> ★ Do not confuse it with `/futihelpround`: **this one controls the whole-escalator ambience**, while
> `/futihelpround` controls the **single end-block accessibility chime**. Their defaults are 16 : 4 on purpose.

**`/futihelpround` (accessibility chime fade range, in blocks, default 4)**

Controls how far away the **accessibility chime** (the MTR-style audible warning behind `/futihelp`) is
still audible — the falloff radius around that end's pair of escalator blocks (**squared** falloff to 0).
**1~128 blocks, default 4**. Old saves / players who never ran it always get the default 4.

| Command | Effect |
| --- | --- |
| `/futihelpround` | **shows the current escalator's chime range** (same locating rule as `/futispeed`), and whether it is *individually set* or *using the default*. |
| `/futihelpround <range>` | **default range = range**: every escalator without an individual setting uses it; individually set ones are unaffected. |
| `/futihelpround <X> to <Y>` | only if the **default range** is exactly X → Y; individually set ones are never touched. |
| `/futihelpround -f <range>` | **force every escalator's chime to this range**: sets it as the default and clears all individual settings. |
| `/futihelpround -f <X> to <Y>` | every escalator whose **effective range** is exactly X (**individually set ones included**) → Y; the rest is unchanged. |

> Precedence: **individual setting &gt; default range (`/futihelpround`) &gt; 4**. An individual value equal to the
> default is not stored. Takes effect **on the very next tick**. Also **no stone-axe screen control**.
>
> Enlarging it makes the two ends overlap more (at 4 blocks they only overlap on short escalators) — that is
> deliberately left to the player; the code no longer narrows the radius to force a silent middle.
> The distance rule is **4 blocks from either end** (not 16): standing in the middle of a long escalator
> puts both ends out of range, so neither chime plays — exactly the intended "only meaningful at the
> entrances" behaviour. The "within 16 blocks of the whole escalator" rule belongs to the **running
> ambience** (`/futiloud`) — do not mix the two up.
>
> [1.20] Values above 100 now really amplify: the chime used to be missed by the gain-lifting logic and
> was clamped to [0,1] by vanilla, so "setting it above 100 did nothing". It now goes through the same
> route as the ambience (the client mixin recognises the `GainManagedSound` interface, plus raising
> OpenAL's `AL_MAX_GAIN` to 10× when the source starts), so 1~1000 all take effect.

**`/futihelpspeed` (accessibility chime rate, in Hz, clicks per second)**

Controls **how fast** the **accessibility chime** (the MTR-style audible warning behind `/futihelp`) clicks.
There are two independent sets — **boarding** (entering the escalator) and **alighting** (leaving) — each with
its own sub-command. **1~20 Hz, defaults: boarding 10, alighting 1** (matching the real-machine spec
"boarding once per 1/10 s, alighting once per second"). Old saves / players who never ran it always get
10 / 1 — i.e. it sounds **exactly as before**.

| Command | Effect |
| --- | --- |
| `/futihelpspeed` | **shows the current escalator's two rates** (same locating rule as `/futispeed`), each marked *individually set* or *using the default*. |
| `/futihelpspeed in <Hz>` | **default boarding rate = Hz** (e.g. `in 5` = five clicks per second); individually set ones are unaffected. |
| `/futihelpspeed out <Hz>` | same, for the **alighting** chime. |
| `/futihelpspeed in\|out <X> to <Y>` | only if the **default rate** is exactly X → Y; individually set ones are never touched. |
| `/futihelpspeed -f in\|out <Hz>` | **force every escalator's chime on that end to this rate**: sets it as the default and clears all individual settings. |
| `/futihelpspeed -f in\|out <X> to <Y>` | every escalator whose **effective rate** is exactly X (**individually set ones included**) → Y. |

> Precedence: **individual setting &gt; default rate (`/futihelpspeed`) &gt; 10 / 1 Hz**. An individual value equal
> to the default is not stored. Takes effect **on the very next tick**. Also **no stone-axe screen control**.
>
> ★ The chime now has **four independent** tunables — do not mix them up: `/futihelp` (on/off),
> `/futihelploud` (how loud), `/futihelpround` (how far it is audible), `/futihelpspeed` (how fast).
> `/futiround` is a different thing entirely: the **whole-escalator running ambience**.
>
> Why 20 Hz is the ceiling: the rate is produced by **swapping the sample and scaling the playback pitch**,
> and vanilla clamps pitch to **0.5~2.0×**, so one sample only covers two octaves. The mod bundles three
> samples (**1 / 4 / 10 Hz**) whose usable ranges join end-to-end into **1~20 Hz**. The defaults (10 / 1 Hz)
> land exactly on pitch 1.0, so **the default settings change nothing about the sound**.

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
3. **Pending in `<save>/MBM_Audio`** — click to import into the save and bind (the original
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

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

The code of this mod is MIT (see `LICENSE`). The **bundled audio**
`assets/smoothlift/sounds/audio/subway_escalator.ogg` is third-party material released under
**CC0 1.0** (public domain dedication) — no attribution is legally required and commercial use is
allowed. See `AUDIO-CREDITS.txt` for the source URL and the verbatim license statement.