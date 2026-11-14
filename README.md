# SmoothLift（mzycBetterMTR）1.39.11182

**Minecraft 1.18.2 + Forge**

专为 MTR（Minecraft Transit Railway）打造的扶梯平滑与车站音效增强模组，支持 MTR4 与 MTR3。

## 功能一览

- **扶梯加速**：`/futispeed <格/秒>`，或拿**石斧**右键扶梯打开设置界面（速度 / 阶梯速度 / 音量 / 声音设置…）。
- **扶梯声音**：`/futimusic` 为扶梯绑定音频（含模组内置素材），`/futiloud` 统一调音量（1~1000，100 = 原始音量），`/futiround` 调淡入淡出范围（默认 16 格）。
- **无障碍提示音**：`/futihelp` 开关香港式视障提示音，`/futihelploud` 调音量，`/futihelpround` 调范围（默认 4 格），`/futihelpspeed in|out` 调两端速率（1~20 Hz），`/futihelpmusic in|out` 换两端素材。
- **MBM 批量管理**：`/MBM` 打开帮助；`/MBM music ...` 批量导入 / 删除音频库文件。
- **直梯（Lift）提示音**：`/lifthelp` 开关系列，`/lifthelploud` 调音量（up/down/open/close 四项各自独立 + 电梯 BGM 音量），`/lifthelpround` 调可闻范围，`/liftbgm` 设置**电梯 BGM**（电梯移动时循环播放）。
- **电梯 BGM（1.39 新增）**：没有内置音频，需要把自己准备的 ogg 放进 `存档/MBM_Audio/lift/liftbgm` 并通过石斧直梯界面或 `/liftbgm` 导入选播；`off` / `none` = 不播。
- **屏蔽门（PSD/APG）**：`/pbmmusic` 系开关门提示音（内置三段 + 自定义素材），`/pbmloud`、`/pbmmidiumloud`、`/pbmarriveloud` 调音量，`/pbmmidium`、`/pbmarrive` 到站 / 进站播报素材，`/pbmnarrate` 进站讲述人，`/pbmclosewait` 关门等待时长。
- **闸机（Zhaji）**：`/zhaji`、`/zhajiloud` 进 / 出站提示音。
- **地图图片库**：`/picture` 管理站台地图（`MBM_Picture`）。
- **其它**：直梯自动关门时长（`/lifthelp ... door` 相关）、到站 / 进站播报素材、PIDS 站名掩码（`%` 分隔本名与别名）。

## 指令速查

```
/futispeed <X>            扶梯运行速度（格/秒）
/futimusic                扶梯自定义音频（内置 / MBM_Audio/futi/music）
/futiloud <1~1000>        扶梯底噪音量（100 = 原始）
/futiround <1~128>        扶梯音效可闻范围（默认 16）
/futihelp on|off          无障碍提示音开关
/futihelploud <1~1000>    无障碍提示音音量
/futihelpround <1~128>    无障碍提示音范围（默认 4）
/futihelpspeed in|out <Hz>   两端提示音速率（1~20 Hz）
/futihelpmusic in|out <素材> 两端提示音素材
/lifthelp on|off / up|down|open|close ...   直梯开关门提示音
/lifthelploud [up|down|open|close|bgm] <1~1000>   四项独立音量 + 电梯 BGM 音量
/lifthelpround <X> [<Y>]  直梯提示音可闻范围（水平 / 垂直）
/liftbgm [<素材>|off]     电梯 BGM（-f 前缀 = 所有维度；无参数 = 查询）
/MBM help|music ...       批量音频管理
/pbmmusic / pbmloud / pbmmidiumloud / pbmarriveloud / pbmarrive / pbmnarrate / pbmclosewait ...   屏蔽门提示音
/zhaji / zhajiloud        闸机提示音
/picture                  地图图片库
```

> 音量统一 1~1000（100 = 原始音量、1000 = 10× 放大）；音频素材放 `存档/MBM_Audio/<分类>`，其中电梯 BGM 在 `MBM_Audio/lift/liftbgm`。



## 安装

1. 安装对应加载器：Forge 1.18.2（ForgeGradle 构建）。
2. 安装 MTR 模组（MTR3 或 MTR4 均可）。
3. 把本模组的 jar 放入 `mods` 文件夹，启动游戏。

## 反馈

所有指令 / 界面操作只回执「指令执行成功 / 指令执行失败」（UI 退出时统一回执），界面上不刷多余小字。

## 许可证

MIT License（见根目录 LICENSE）。作者：mzycAB。
