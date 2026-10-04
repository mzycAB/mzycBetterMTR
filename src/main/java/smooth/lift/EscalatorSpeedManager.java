package smooth.lift;

import smooth.lift.net.SLNet;

import net.minecraft.core.BlockPos;
import smooth.lift.compat.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.mixin.ChunkMapAccessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 速度与阶梯动画数据中枢：
 * - 服务端：每个维度一份 EscalatorSpeedData（SavedData，随世界保存）。
 * - 客户端：保存服务端同步过来的镜像，供客户端预测与贴图动画使用（和服务端一致）。
 *
 * <p>1.6 起阶梯动画速度是「每条扶梯单独设置」：设置了就用单独值；
 * 没设置的回退到「维度默认阶梯动画速度」（/jietispeed，仅当它被开启时）；
 * 连维度默认都没开时，**跟随这条扶梯自己的运行速度**。
 *
 * <p><b>贯穿所有指令与界面的统一规则：设置「扶梯速度」时「阶梯速度」一起跟随；
 * 设置「阶梯速度」时绝不动「扶梯速度」。</b>
 * 因此每一个写运行速度的入口（石斧界面、/futispeed 各分支）都会同时把阶梯速度
 * 置成同一个值（清掉单独阶梯设置，或镜像全局阶梯值）；而 /jietispeed 各分支只写阶梯速度。
 */
public final class EscalatorSpeedManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private EscalatorSpeedManager() {
    }

    public static final class ClientDimensionData {
        public double defaultSpeed = EscalatorSpeedData.DEFAULT_SPEED;
        public final Map<BlockPos, Double> speeds = new HashMap<>();
        public final Map<BlockPos, Double> stepSpeeds = new HashMap<>();
        public boolean stepEnabled = false;
        public double stepValue = EscalatorSpeedData.DEFAULT_SPEED;
        /** 【1.7】音频ID → OGG 字节（服务端同步过来的镜像，供客户端播放器注入声音引擎）。 */
        public final Map<String, byte[]> audioLibrary = new HashMap<>();
        /** 【1.7】扶梯方块 → 音频ID（服务端同步过来的镜像）。 */
        public final Map<BlockPos, String> blockAudio = new HashMap<>();
        /** 【1.9】扶梯方块 → 声音音量（1~1000，服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockVolume = new HashMap<>();
        /** 【1.12】默认扶梯音量（/futiloud 设置，未单独设置音量的扶梯使用）。 */
        public int defaultVolume = EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
        /** 【1.11】默认扶梯音频 ID（未单独绑定音频的扶梯使用）；null = 无默认（静音）。 */
        public String defaultAudio;
        /** 【1.16】默认无障碍提示音开关（/futihelp 设置，未单独设置的扶梯使用）。 */
        public boolean defaultHelp = true;
        /** 【1.16】扶梯方块 → 提示音开关（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Boolean> blockHelp = new HashMap<>();
        /**
         * 【1.18】默认无障碍提示音音量（/futihelploud 设置，未单独设置的扶梯使用）。
         * 注意与 {@link #defaultVolume}（扶梯**运行底噪**音量）是两套互不影响的数据。
         */
        public int defaultHelpVolume = EscalatorSpeedData.DEFAULT_HELP_VOLUME;
        /** 【1.18】扶梯方块 → 提示音音量（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpVolume = new HashMap<>();
        /** 【1.24】默认扶梯运行底噪的**水平**可闻范围（/futiround 设置，单位格，初始 10）。 */
        public int defaultRound = EscalatorSpeedData.DEFAULT_ROUND;
        /** ★【10-03】默认扶梯运行底噪的**垂直（y 轴）**范围（初始 5）。 */
        public int defaultRoundY = EscalatorSpeedData.DEFAULT_ROUND_Y;
        /** 【1.24】扶梯方块 → 运行底噪**水平**范围（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockRound = new HashMap<>();
        /** ★【10-03】扶梯方块 → 运行底噪**垂直**范围（镜像；无记录 ⇒ 用 {@link #defaultRoundY}）。 */
        public final Map<BlockPos, Integer> blockRoundY = new HashMap<>();
        /** 【1.24】默认无障碍提示音的**水平**可闻范围（/futihelpround 设置，单位格，初始 4）。 */
        public int defaultHelpRound = EscalatorSpeedData.DEFAULT_HELP_ROUND;
        /** ★【10-03】默认无障碍提示音的**垂直（y 轴）**范围（初始 5）。 */
        public int defaultHelpRoundY = EscalatorSpeedData.DEFAULT_HELP_ROUND_Y;
        /** 【1.24】扶梯方块 → 提示音**水平**范围（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpRound = new HashMap<>();
        /** ★【10-03】扶梯方块 → 提示音**垂直**范围（镜像；无记录 ⇒ 用 {@link #defaultHelpRoundY}）。 */
        public final Map<BlockPos, Integer> blockHelpRoundY = new HashMap<>();
        /** 【1.31】默认**上客端（进入扶梯）**提示音速率（Hz，初始 10）。 */
        public int defaultHelpSpeedIn = EscalatorSpeedData.DEFAULT_HELP_SPEED_IN;
        /** 【1.31】扶梯方块 → 上客端提示音速率（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpSpeedIn = new HashMap<>();
        /** 【1.31】默认**落客端（离开扶梯）**提示音速率（Hz，初始 1）。 */
        public int defaultHelpSpeedOut = EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT;
        /** 【1.31】扶梯方块 → 落客端提示音速率（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpSpeedOut = new HashMap<>();
        /**
         * 【1.41】默认无障碍提示音**音乐** ID 的**进入扶梯（上客端）**那一套
         * （{@code /futihelpmusic in} 设置，未单独设置的扶梯使用）。
         * 初始 {@link EscalatorSpeedData#HELP_AUDIO_DEFAULT} = 模组原来的提示音。
         */
        public String defaultHelpAudioIn = EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        /** 【1.41】扶梯方块 → **上客端**无障碍提示音音乐 ID（服务端同步过来的镜像）。 */
        public final Map<BlockPos, String> blockHelpAudioIn = new HashMap<>();
        /** 【1.41】默认无障碍提示音**音乐** ID 的**离开扶梯（落客端）**那一套（{@code /futihelpmusic out}）。 */
        public String defaultHelpAudioOut = EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        /** 【1.41】扶梯方块 → **落客端**无障碍提示音音乐 ID（服务端同步过来的镜像）。 */
        public final Map<BlockPos, String> blockHelpAudioOut = new HashMap<>();
        /**
         * 【1.42】直梯开关门提示音开关（{@code /lifthelp}，服务端同步过来的镜像）。
         * 与扶梯那套提示音（{@link #defaultHelp}）**完全无关**，是独立的另一件事。
         */
        public boolean liftHelp = true;
        /** 【1.42】直梯开关门提示音倍速（服务端同步过来的镜像；【1.15】起没有指令能改它）。 */
        public float liftHelpSpeed = EscalatorSpeedData.DEFAULT_LIFT_HELP_SPEED;
        /** 【1.43】直梯开关门提示音音量（{@code /lifthelploud}，服务端同步过来的镜像）。 */
        public int liftHelpVolume = EscalatorSpeedData.DEFAULT_LIFT_HELP_VOLUME;
        /** 【1.48】四项各自音量镜像（-1 = 跟随共用默认）。【1.28】chime 拆成 open / close。 */
        public int liftToneVolumeUp = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        public int liftToneVolumeDown = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        public int liftToneVolumeOpen = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        public int liftToneVolumeClose = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        /** 【1.47】直梯提示音（四项共用）**水平**淡入淡出范围（{@code /lifthelpround}，镜像）。 */
        public int liftHelpRound = EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND;
        /** ★【10-03】直梯提示音**垂直（y 轴）**范围镜像（初始 5）。 */
        public int liftHelpRoundY = EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND_Y;
        /** 【1.46】四提示音独立子开关的镜像（{@code /lifthelp up|down|open|close on|off} / 石斧 UI 开关）。 */
        public boolean liftToneUpEnabled = true;
        public boolean liftToneDownEnabled = true;
        public boolean liftToneOpenEnabled = true;
        public boolean liftToneCloseEnabled = true;
        /**
         * 【1.15】四提示音的**维度默认素材**镜像（{@code /lifthelp up|down|open|close <名字>}）。
         * 单独设置过的那条直梯按 {@link #liftToneAudio} 走；没设置、或者那一项是
         * {@code default}（跟维度默认）时回落到这里。
         */
        public String liftToneAudioUp = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        public String liftToneAudioDown = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        public String liftToneAudioOpen = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        public String liftToneAudioClose = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        /**
         * 【1.28】分类 → 该分类子文件夹里的 OGG 文件名（服务端扫描同步过来的镜像，待导入来源）。
         * 取代旧版平铺的 {@code folderAudio}。
         */
        public final Map<String, Set<String>> folderByCategory = new HashMap<>();
        /** 【1.28】分类 → 该分类已导入存档的音频名集合（服务端同步过来的镜像）。 */
        public final Map<String, Set<String>> audioCategoryNames = new HashMap<>();
        /**
         * 【1.45】直梯楼层轨道提示音（竖井列打包坐标 → 三音频 id，服务端同步过来的镜像）。
         * 播放端按「最近直梯的楼层列」查它。
         */
        public final Map<Long, EscalatorSpeedData.LiftToneAudio> liftToneAudio = new HashMap<>();

        // ------------------------------------------------------------------
        // 【09-30】闸机（MTR Ticket Barrier）提示音的镜像
        //   两层：下面这四个字段 = **维度默认层**；zhajiTone 表 = **逐组层**（石斧右键那一组）。
        // ------------------------------------------------------------------

        /** 【09-30】进站闸机（{@code mtr:ticket_barrier_entrance_1}）的提示音素材镜像。 */
        public String zhajiToneAudioIn = EscalatorSpeedData.ZHAJI_TONE_DEFAULT;
        /** 【09-30】出站闸机（{@code mtr:ticket_barrier_exit_1}）的提示音素材镜像。 */
        public String zhajiToneAudioOut = EscalatorSpeedData.ZHAJI_TONE_DEFAULT;
        /** 【09-30】进站闸机提示音音量镜像（1~1000）。 */
        public int zhajiToneVolumeIn = EscalatorSpeedData.DEFAULT_ZHAJI_VOLUME;
        /** 【09-30】出站闸机提示音音量镜像（1~1000）。 */
        public int zhajiToneVolumeOut = EscalatorSpeedData.DEFAULT_ZHAJI_VOLUME;

        /**
         * 【09-30 续】石斧右键**那一组闸机**的设置镜像（组锚点 asLong → {@link EscalatorSpeedData.ZhajiTone}）。
         *
         * <p>播放端按音源坐标算出组锚点后先查这张表，查不到再回落上面那四个「维度默认」字段 ——
         * 与屏蔽门的 {@code psdToneAudio} 镜像同一套用法。
         */
        public final Map<Long, EscalatorSpeedData.ZhajiTone> zhajiTone = new HashMap<>();

        // ------------------------------------------------------------------
        // 【1.50】屏蔽门（MTR PSD / APG）开关门提示音的镜像（形状与直梯那一组对称）
        // ------------------------------------------------------------------

        /** 【1.50】屏蔽门提示音总开关镜像（{@code /pbmmusic}）。 */
        public boolean psdHelp = true;
        /** 【1.50】屏蔽门两项各自子开关的镜像（{@code /pbmmusic open|close}）。 */
        public boolean psdToneOpenEnabled = true;
        public boolean psdToneCloseEnabled = true;
        /** 【1.50】屏蔽门提示音**共用默认**音量的镜像（{@code /pbmloud}）。 */
        public int psdHelpVolume = EscalatorSpeedData.DEFAULT_PSD_HELP_VOLUME;
        /** 【1.50】两项各自音量的镜像（-1 = 跟随共用默认）。 */
        public int psdToneVolumeOpen = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        public int psdToneVolumeClose = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        /** 【1.22】到站播报 / 进站报站各自那一项音量的镜像（-1 = 跟随共用默认）。 */
        public int psdMidiumVolume = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        public int psdArriveVolume = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        /** 【09-29】屏蔽门提示音可闻范围镜像（{@code /pbmround}，格）——水平/垂直两维。 */
        public int psdHelpRoundXz = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_XZ;
        public int psdHelpRoundY = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_Y;
        /** 【09-29】到站播报 / 进站报站各自的可闻范围镜像（水平/垂直两维）。 */
        public int psdMidiumRoundXz = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_XZ;
        public int psdMidiumRoundY = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_Y;
        public int psdArriveRoundXz = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_XZ;
        public int psdArriveRoundY = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_Y;
        /** 【1.16】关门提示音强制等待时长镜像（{@code /pbmclosewait}，秒）。 */
        public int psdCloseWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;
        /** 【1.15】两项**维度默认素材**的镜像（{@code /pbmmusic open|close <名字>}）。 */
        public String psdToneAudioOpen = EscalatorSpeedData.PSD_TONE_DEFAULT;
        public String psdToneAudioClose = EscalatorSpeedData.PSD_TONE_DEFAULT;
        /** 【1.17】到站播报的镜像：素材 id（{@code off} = 不播）+ 等待秒数。 */
        public String psdMidiumAudio = EscalatorSpeedData.PSD_MIDIUM_OFF;
        public int psdMidiumWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;
        /** 【1.21】进站报站的镜像：素材 id（{@code off} = 不播）+ 秒数（(-∞, 0]：最近一班车还剩 |X| 秒到站时起播）。 */
        public String psdArriveAudio = EscalatorSpeedData.PSD_ARRIVE_OFF;
        public int psdArriveSeconds = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS;
        /**
         * 【09-28】「进站广播（讲述人）」的镜像：**样式**（0 关 / 1 上海 / 2 香港）+ 秒数（(-∞, 0]）。
         * ★ 与 {@link #psdArriveSeconds} 那一对**互相独立**（用户点名「取消借用进站广播」）：
         *   两条广播各有各的时间窗口，只是恰好都能用「最近一班车还剩 |X| 秒」这套口径。
         */
        public int psdNarrateMode = EscalatorSpeedData.DEFAULT_PSD_NARRATE_MODE;
        public int psdNarrateSeconds = EscalatorSpeedData.DEFAULT_PSD_NARRATE_SECONDS;
        /**
         * 【09-30 续 3】「站台广播（讲述人）」的镜像：样式（站台讲述人只有「关闭」与 userN ——
         * SH / HK 预设已删，默认**关闭**）+ 等待秒数（[0,+∞)）。与进站讲述人那一对**互相独立**。
         */
        public int psdMidiumNarrateMode = EscalatorSpeedData.PSD_NARRATE_OFF;
        public int psdMidiumNarrateSeconds = EscalatorSpeedData.DEFAULT_PSD_NARRATE_SECONDS;
        /** 【1.50】每扇门单独设置的素材镜像（门锚点打包坐标 → {open, close}）。 */
        public final Map<Long, EscalatorSpeedData.PsdToneAudio> psdToneAudio = new HashMap<>();
        /**
         * 【10-03 五改】**门串级**设置的镜像（门串锚点 → 关门后等待发车）。
         * ★ 与上面的 {@code psdToneAudio} 是**两张表、两个键空间**（车站级 vs 门串级），别合并。
         */
        public final Map<Long, EscalatorSpeedData.PsdRunSetting> psdRunSettings = new HashMap<>();
    }

    private static final Map<ResourceKey<Level>, ClientDimensionData> CLIENT_DATA = new HashMap<>();

    /** 运行速度：客户端读镜像，服务端读 SavedData。 */
    public static double getSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_SPEED;
            }
            Double speed = data.speeds.get(pos);
            return speed != null ? speed : data.defaultSpeed;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Double speed = data.speeds.get(pos);
        return speed != null ? speed : data.defaultSpeed;
    }

    /**
     * 阶梯动画设定速度（1.6：以「每条扶梯单独设置」为主）：
     * - 该扶梯被单独设置过阶梯动画速度 -> 用它的单独值；
     * - 否则 -> 用「维度默认阶梯动画速度」（/jietispeed 设定，未开启则为 MTR 原版 0.625）。
     */
    public static double getStepAnimationSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.VANILLA_STEP;
            }
            Double s = data.stepSpeeds.get(pos);
            if (s != null) {
                return s;
            }
            return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Double s = data.stepSpeeds.get(pos);
        if (s != null) {
            return s;
        }
        return data.defaultStepSpeed();
    }

    /** 维度默认阶梯动画速度：未单独设置阶梯动画的扶梯使用它。 */
    public static double getDefaultStepSpeed(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.VANILLA_STEP;
            }
            return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
        }
        return getServerData((ServerLevel) level).defaultStepSpeed();
    }

    /** 查询某条扶梯单独设置的阶梯动画速度；未单独设置返回 null（此时跟随全局）。 */
    public static Double getIndividualStepSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.stepSpeeds.get(pos);
        }
        return getServerData((ServerLevel) level).stepSpeeds.get(pos);
    }

    /** 查询某条扶梯单独设置的运行速度；未单独设置返回 null（此时跟随全局运行速度）。 */
    public static Double getIndividualRunSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.speeds.get(pos);
        }
        return getServerData((ServerLevel) level).speeds.get(pos);
    }

    /** 维度默认阶梯动画速度当前是否启用（/jietispeed on / X 会开启，off 关闭）。 */
    public static boolean isStepEnabled(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data != null && data.stepEnabled;
        }
        return getServerData((ServerLevel) level).stepEnabled;
    }

    /**
     * 这条扶梯**实际使用**的阶梯动画速度。取值优先级：
     *
     * <ol>
     *   <li>这条扶梯被单独设置过阶梯速度 -&gt; 用单独值（石斧界面「阶梯速度」框）；</li>
     *   <li>否则如果它的**运行速度**被石斧单独改过 -&gt; 跟随它自己的运行速度
     *       （这条扶梯属于「专门调过的」，全局阶梯速度不再影响它
     *       —— 这正是「不会修改与全局不同的扶梯」）；</li>
     *   <li>否则若 /jietispeed 开着全局阶梯速度 -&gt; 用全局值；</li>
     *   <li>否则 -&gt; 跟随自己的运行速度（= 全局运行速度）。</li>
     * </ol>
     *
     * <p>所以默认情况下「改扶梯速度，阶梯速度会跟着变」；而改阶梯速度只写第 1 档，
     * 不会反过来影响运行速度。
     */
    public static double getAnimationSpeed(Level level, BlockPos pos) {
        Double individualStep = getIndividualStepSpeed(level, pos);
        if (individualStep != null) {
            return individualStep;
        }
        Double individualRun = getIndividualRunSpeed(level, pos);
        if (individualRun != null) {
            return individualRun;
        }
        if (isStepEnabled(level)) {
            return getDefaultStepSpeed(level);
        }
        return getSpeed(level, pos);
    }

    /** 输入界面预填：查询单个方块运行速度，未设置返回 null（界面再显示默认值）。 */
    public static Double getClientSpeed(Level level, BlockPos pos) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return null;
        }
        return data.speeds.get(pos);
    }

    public static double getClientDefault(Level level) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        return data != null ? data.defaultSpeed : EscalatorSpeedData.DEFAULT_SPEED;
    }

    /** 界面预填：维度默认阶梯动画速度（未被单独设置的扶梯使用）。 */
    public static double getClientDefaultStepSpeed(Level level) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return EscalatorSpeedData.VANILLA_STEP;
        }
        return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
    }

    /**
     * 应用服务端同步过来的速度/阶梯动画数据。
     *
     * <p><b>注意</b>：这里必须复用已有的 {@link ClientDimensionData}（只覆盖速度相关字段），
     * 不能 `CLIENT_DATA.put(dimension, new ClientDimensionData())` ——
     * 那会把同一维度里已经收到的**音频库 / 扶梯-音频绑定 / 音量**一起清空。
     * 速度同步（SYNC）和音频同步（AUDIO_SYNC）是两个独立的包，
     * 到达顺序不保证：先收音频再收速度时，旧的写法会让声音数据凭空消失。
     */
    public static void applyClientData(ResourceKey<Level> dimension, double defaultSpeed,
                                       Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds,
                                       boolean stepEnabled, double stepValue) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.defaultSpeed = defaultSpeed;
        data.speeds.clear();
        data.speeds.putAll(speeds);
        data.stepSpeeds.clear();
        data.stepSpeeds.putAll(stepSpeeds);
        data.stepEnabled = stepEnabled;
        data.stepValue = stepValue;
    }

    /** 【1.7】应用服务端同步过来的音频库、就绪拾取文件夹名单与扶梯-音频绑定（覆盖式更新）。 */
    public static void applyClientAudioData(ResourceKey<Level> dimension,
                                            Map<String, byte[]> audioLibrary,
                                            Map<String, Set<String>> folderByCategory,
                                            Map<String, Set<String>> audioCategoryNames,
                                            Map<BlockPos, String> blockAudio,
                                            String defaultAudio) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.audioLibrary.clear();
        data.audioLibrary.putAll(audioLibrary);
        data.folderByCategory.clear();
        for (Map.Entry<String, Set<String>> e : folderByCategory.entrySet()) {
            data.folderByCategory.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        data.audioCategoryNames.clear();
        for (Map.Entry<String, Set<String>> e : audioCategoryNames.entrySet()) {
            data.audioCategoryNames.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        data.blockAudio.clear();
        data.blockAudio.putAll(blockAudio);
        data.defaultAudio = defaultAudio;
    }

    // ------------------------------------------------------------------
    // 【六改】运行底噪「默认」层的兜底 = 模组内置的那条底噪
    //
    //   用户点名「扶梯ui里「声音设置」的「默认」和「内置地铁自动扶梯」是一个东西」，
    //   于是六改把界面里那条内置音频行删掉了。但 defaultAudio 字段**初始是 null**：
    //   若不做兜底，「默认」= null = 静音 ⇒ 内置行删掉之后，玩家再没有任何办法让扶梯出声
    //   （用户实报：「选择扶梯默认提示音没有声音了」）。
    //   兜底之后「默认」就真的等于那条内置底噪，与用户「两个是一个东西」的判断一致。
    // ------------------------------------------------------------------

    /** 【六改】模组内置的第一条运行底噪的绑定 ID（目前唯一一条 = {@code builtin:subway_escalator}）。 */
    public static String builtinDefaultAudioId() {
        if (BUILTIN_AUDIO.isEmpty()) {
            return null;
        }
        return BUILTIN_PREFIX + BUILTIN_AUDIO.keySet().iterator().next();
    }

    /** 【六改】把「未设置」的默认音频规整成内置底噪 ID（null / 空串 → 内置；其余原样）。 */
    public static String normaliseDefaultAudio(String audioId) {
        return audioId == null || audioId.isEmpty() ? builtinDefaultAudioId() : audioId;
    }

    /** 【1.11】客户端：当前维度的默认扶梯音频 ID；没设置返回**内置底噪**（六改起不再返回 null）。 */
    public static String getClientDefaultAudio(ResourceKey<Level> dimension) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        return normaliseDefaultAudio(data == null ? null : data.defaultAudio);
    }

    /** 【1.9】该扶梯**实际使用**的声音音量（1~1000，100 = 原始音量）。
     *  优先级：单独设置（石斧界面） &gt; 默认音量（/futiloud） &gt; 100。
     *  客户端读镜像，服务端读 SavedData。 */
    public static int getVolume(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME
                    : data.blockVolume.getOrDefault(pos, data.defaultVolume);
        }
        return getServerData((ServerLevel) level).getVolume(pos);
    }

    /** 【1.12】该扶梯方块**单独设置**的音量；没单独设置返回 null（表示跟随默认音量）。 */
    public static Integer getIndividualVolume(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.blockVolume.get(pos);
        }
        return getServerData((ServerLevel) level).getIndividualVolume(pos);
    }

    /** 【1.12】该扶梯方块是否**单独设置**过音量（石斧界面）。 */
    public static boolean hasIndividualVolume(Level level, BlockPos pos) {
        return getIndividualVolume(level, pos) != null;
    }

    /**
     * 【1.9】界面用：取「这条扶梯」的音量。
     *
     * <p>音频绑定与音量都是按**玩家当时点到的那一个方块**存的，而同一条扶梯上不同方块是不同的 key。
     * 所以这里先看自己这块有没有单独记录；没有就顺着扶梯链（{@link EscalatorUtil#collectChain}）
     * 找同一条扶梯上单独设过音量的方块，避免出现「在 A 块设成 50，走到 B 块打开界面却显示默认值」。
     * 整条链都没单独设过 → 用维度默认音量（【1.12】/futiloud 设置的那个）。
     * （只有打开界面时才会调用，链遍历开销可忽略。）
     */
    public static int getVolumeForScreen(Level level, BlockPos pos) {
        Integer own = getIndividualVolume(level, pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            if (p.equals(pos)) {
                continue;
            }
            Integer v = getIndividualVolume(level, p);
            if (v != null) {
                return v;
            }
        }
        return getDefaultVolume(level);
    }

    /** 【1.12】该维度的默认扶梯音量（/futiloud 设置；未设置过就是 100 = 原始音量）。 */
    public static int getDefaultVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME : data.defaultVolume;
        }
        return getServerData((ServerLevel) level).defaultVolume;
    }

    /**
     * 【1.9】声音播放器专用：按「音频绑定所在的那个方块」直接取音量。
     * 播放器本来就拿着绑定坐标，所以不做链回退，每 tick 调用也只是一次 HashMap 查询。
     * 【1.12】没单独设过就用维度默认音量。
     */
    public static int getClientBindingVolume(ResourceKey<Level> dimension, BlockPos boundPos) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        if (data == null) {
            return EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
        }
        return data.blockVolume.getOrDefault(boundPos, data.defaultVolume);
    }

    /**
     * 【1.9】设置某条扶梯的声音音量（服务端）。返回夹取到 1~1000 之后的实际值（100 = 原始音量）。
     *
     * <p>会把同一个扶梯链上**已绑定音频**的方块一起设成同一个值 —— 否则
     * 「在 B 块调音量、而音频绑在 A 块」时播放器读的是 A 块的音量，表现就是「调了没反应」。
     */
    public static int setVolume(ServerLevel level, BlockPos pos, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampVolume(volume);
        data.setVolume(pos, v);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            if (data.blockAudio.containsKey(p)) {
                data.setVolume(p, v);
            }
        }
        data.setDirty();
        return v;
    }

    // ------------------------------------------------------------------
    // 【1.12】/futiloud：默认扶梯音量（数据模型与 /futispeed、/futimusic 完全对称）
    // ------------------------------------------------------------------

    /** /futiloud &lt;音量&gt;：只改**默认**音量，单独设置过音量的扶梯不变。 */
    public static void setDefaultVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultVolume = EscalatorSpeedData.clampVolume(volume);
        data.setDirty();
    }

    /**
     * /futiloud -f &lt;音量&gt;：强制**所有**扶梯音量 = 该值。
     * 做法是设默认值 + 清掉所有单独设置（清掉比把每一格都写成同一个值更省存档）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockVolume.size();
        data.defaultVolume = EscalatorSpeedData.clampVolume(volume);
        data.blockVolume.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futiloud &lt;X&gt; to &lt;Y&gt;：默认音量正好是 X 时才改成 Y；单独设置过的一概不动。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultVolume != from) {
            return false;
        }
        data.defaultVolume = EscalatorSpeedData.clampVolume(to);
        data.setDirty();
        return true;
    }

    /**
     * /futiloud -f &lt;X&gt; to &lt;Y&gt;：把**所有**音量正好是 X 的扶梯（含单独设置的）改成 Y。
     * 「音量正好是 X」按实际生效值判断（单独设置优先，其次默认音量）。
     *
     * @return 被改动的处数（默认层算 1 处）
     */
    public static int forceReplaceVolumeFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampVolume(to);
        int changed = 0;
        if (data.defaultVolume == from) {
            data.defaultVolume = target;
            changed++;
        }
        // 先把要改的坐标收集起来再改，避免边遍历边改 Map。
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : data.blockVolume.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setVolume(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.9】应用服务端同步过来的扶梯音量表（覆盖式更新）。 */
    public static void applyClientVolumes(ResourceKey<Level> dimension, Map<BlockPos, Integer> volumes,
                                          int defaultVolume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockVolume.clear();
        data.blockVolume.putAll(volumes);
        data.defaultVolume = defaultVolume;
    }

    // ------------------------------------------------------------------
    // 【1.16】/futihelp：无障碍提示音开关（数据模型与 /futiloud 完全对称）
    //
    //   两层：维度默认（/futihelp on|off） + 每条扶梯单独设置（石斧界面里的开关）。
    //   单独设置**只存一个方块**（玩家点的那块），但读的时候会顺着扶梯链找 ——
    //   同一条扶梯上任意一块设过，整条都算设过（和 getVolumeForScreen 同一套规则），
    //   这样「在 A 块关掉、走到 B 块打开界面」看到的仍然是关。
    // ------------------------------------------------------------------

    /** 【1.16】这条扶梯**实际生效**是否播放无障碍提示音（单独设置 &gt; 维度默认）。 */
    public static boolean isHelpEnabled(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                // 镜像还没到（刚进世界）：按默认「开」处理，宁可响也不要哑掉。
                return true;
            }
            Boolean own = findChainHelp(data.blockHelp, level, pos);
            return own != null ? own : data.defaultHelp;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Boolean own = findChainHelp(data.blockHelp, level, pos);
        return own != null ? own : data.defaultHelp;
    }

    /** 【1.16】这条扶梯是否被**单独设置**过提示音开关（顺扶梯链找）。 */
    public static boolean hasOwnHelp(Level level, BlockPos pos) {
        Map<BlockPos, Boolean> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockHelp;
        } else {
            overrides = getServerData((ServerLevel) level).blockHelp;
        }
        return findChainHelp(overrides, level, pos) != null;
    }

    /** 顺扶梯链找单独设置：自己这块优先，其次链上其它方块；整条链都没有返回 null（用维度默认）。 */
    private static Boolean findChainHelp(Map<BlockPos, Boolean> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Boolean own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Boolean value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.16】该维度的默认提示音开关（/futihelp 设置；未设置过就是 true = 开）。 */
    public static boolean getDefaultHelp(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null || data.defaultHelp;
        }
        return getServerData((ServerLevel) level).defaultHelp;
    }

    /**
     * 【1.16】石斧界面：设置某条扶梯的提示音开关（服务端）。
     *
     * <p>先把整条链上的旧记录清掉，再只在玩家点的那一块记一条（仅在它与默认值不同时），
     * 保证「一条扶梯最多一条记录」，省存档也免得链上多块各说各话。
     */
    public static void setHelp(ServerLevel level, BlockPos pos, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelp.remove(p);
        }
        data.blockHelp.remove(pos);
        data.setHelp(pos, enabled);
        data.setDirty();
    }

    /** /futihelp &lt;on|off&gt;：只改**默认**开关，单独设置过的扶梯不变。 */
    public static void setDefaultHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelp = enabled;
        data.setDirty();
    }

    /**
     * /futihelp -f &lt;on|off&gt;：强制**所有**扶梯 = 该开关。
     * 做法是设默认值 + 清掉所有单独设置（和 forceDefaultVolume 同一套）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelp.size();
        data.defaultHelp = enabled;
        data.blockHelp.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futihelp &lt;X&gt; to &lt;Y&gt;：默认开关正好是 X 时才改成 Y；单独设置过的一概不动。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultHelp(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelp != from) {
            return false;
        }
        data.defaultHelp = to;
        data.setDirty();
        return true;
    }

    /**
     * /futihelp -f &lt;X&gt; to &lt;Y&gt;：把所有**生效开关正好是 X** 的扶梯（含单独设置的）改成 Y。
     *
     * @return 被改动的处数（默认层算 1 处）
     */
    public static int forceReplaceHelpFromTo(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        if (data.defaultHelp == from) {
            data.defaultHelp = to;
            changed++;
        }
        // 先收集再改，避免边遍历边改 Map。
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : data.blockHelp.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setHelp(pos, to);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.16】应用服务端同步过来的提示音开关表（覆盖式更新）。 */
    public static void applyClientHelp(ResourceKey<Level> dimension, boolean defaultHelp,
                                       Map<BlockPos, Boolean> blockHelp) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelp.clear();
        data.blockHelp.putAll(blockHelp);
        data.defaultHelp = defaultHelp;
        clientHelpGeneration++;
    }

    /**
     * 【1.16】无障碍开关的「代次」：只在客户端镜像被整体替换时（收到 HELP_SYNC、断开连接清镜像）递增。
     *
     * <p>给每 tick 都要判断「现在该不该响」的调用方（{@code EscalatorChimePlayer}）当缓存键用 ——
     * {@link #isHelpEnabled} 在链上有单独设置时要展开整条链，每 tick 都做没必要；
     * 而只要代次变了就说明开关数据变了，必须立刻重查。**千万别用「缓存到下一次方块变化为止」那套**：
     * 玩家坐在扶梯上不动时那种缓存永远不会失效，开关就「关不掉」。
     */
    public static long clientHelpGeneration() {
        return clientHelpGeneration;
    }

    /** 见 {@link #clientHelpGeneration()}。只在客户端线程写。 */
    private static long clientHelpGeneration;

    // ------------------------------------------------------------------
    // 【1.18】/futihelploud：无障碍提示音音量（数据模型与 /futiloud、/futihelp 完全对称）
    //
    //   两层：维度默认（/futihelploud <音量>） + 每条扶梯单独设置（石斧界面里的输入框）。
    //   单独设置**只存一个方块**（玩家点的那块），读的时候顺着扶梯链找 —— 与提示音开关同一套规则。
    //
    //   注意：这是「提示音（香港式视障人士提升音）」的音量，作用在端头**单块**方块上、射程 4 格；
    //   与 /futiloud 管的「扶梯运行底噪」（整条扶梯、射程 16 格）是**两件不同的事**，数据与指令互不影响。
    // ------------------------------------------------------------------

    /**
     * 【1.18】这条扶梯**实际生效**的提示音音量（单独设置 &gt; 维度默认；1~1000，100 = 原始音量）。
     *
     * <p>这里和 {@link #isHelpEnabled} 一样**会顺扶梯链找**：提示音播放器拿的是「离玩家最近的阶梯块」，
     * 而玩家可能在护栏/侧板上打开界面设的音量，所以链上任意一块设过都算。
     */
    public static int getHelpVolume(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                // 镜像还没到（刚进世界）：按默认音量处理。
                return EscalatorSpeedData.DEFAULT_HELP_VOLUME;
            }
            Integer own = findChainHelpVolume(data.blockHelpVolume, level, pos);
            return own != null ? own : data.defaultHelpVolume;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainHelpVolume(data.blockHelpVolume, level, pos);
        return own != null ? own : data.defaultHelpVolume;
    }

    /** 【1.18】这条扶梯是否被**单独设置**过提示音音量（顺扶梯链找）。 */
    public static boolean hasOwnHelpVolume(Level level, BlockPos pos) {
        Map<BlockPos, Integer> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockHelpVolume;
        } else {
            overrides = getServerData((ServerLevel) level).blockHelpVolume;
        }
        return findChainHelpVolume(overrides, level, pos) != null;
    }

    /** 顺扶梯链找单独设置的音量：自己这块优先，其次链上其它方块；整条链都没有返回 null（用维度默认）。 */
    private static Integer findChainHelpVolume(Map<BlockPos, Integer> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Integer own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Integer value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.18】该维度的默认提示音音量（/futihelploud 设置；未设置过就是 100 = 原始音量）。 */
    public static int getDefaultHelpVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_VOLUME : data.defaultHelpVolume;
        }
        return getServerData((ServerLevel) level).defaultHelpVolume;
    }

    /**
     * 【1.18】石斧界面：设置某条扶梯的提示音音量（服务端）。返回夹取后的实际值。
     *
     * <p>先把整条链上的旧记录清掉，再只在玩家点的那一块记一条（仅在它与默认值不同时），
     * 保证「一条扶梯最多一条记录」。
     */
    public static int setHelpVolume(ServerLevel level, BlockPos pos, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpVolume(volume);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpVolume.remove(p);
        }
        data.blockHelpVolume.remove(pos);
        data.setHelpVolume(pos, v);
        data.setDirty();
        return v;
    }

    /** /futihelploud &lt;音量&gt;：只改**默认**音量，单独设置过的不变。 */
    public static void setDefaultHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpVolume = EscalatorSpeedData.clampHelpVolume(volume);
        data.setDirty();
    }

    /**
     * /futihelploud -f &lt;音量&gt;：强制**所有**扶梯提示音音量 = 该值。
     * 做法是设默认值 + 清掉所有单独设置（和 forceDefaultVolume 同一套）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpVolume.size();
        data.defaultHelpVolume = EscalatorSpeedData.clampHelpVolume(volume);
        data.blockHelpVolume.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futihelploud &lt;X&gt; to &lt;Y&gt;：默认音量正好是 X 时才改成 Y；单独设置过的一概不动。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultHelpVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpVolume != from) {
            return false;
        }
        data.defaultHelpVolume = EscalatorSpeedData.clampHelpVolume(to);
        data.setDirty();
        return true;
    }

    /**
     * /futihelploud -f &lt;X&gt; to &lt;Y&gt;：把所有**生效音量正好是 X** 的扶梯（含单独设置的）改成 Y。
     *
     * @return 被改动的处数（默认层算 1 处）
     */
    public static int forceReplaceHelpVolumeFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpVolume(to);
        int changed = 0;
        if (data.defaultHelpVolume == from) {
            data.defaultHelpVolume = target;
            changed++;
        }
        // 先收集再改，避免边遍历边改 Map。
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpVolume.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setHelpVolume(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.18】应用服务端同步过来的提示音音量表（覆盖式更新）。 */
    public static void applyClientHelpVolume(ResourceKey<Level> dimension, int defaultHelpVolume,
                                             Map<BlockPos, Integer> blockHelpVolume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelpVolume.clear();
        data.blockHelpVolume.putAll(blockHelpVolume);
        data.defaultHelpVolume = defaultHelpVolume;
        clientHelpVolumeGeneration++;
    }

    /**
     * 【1.18】提示音音量的「代次」：只在客户端镜像被整体替换时（收到 HELP_VOLUME_SYNC、断开连接清镜像）递增。
     *
     * <p>用途与 {@link #clientHelpGeneration()} 完全相同：给每 tick 都要读音量的
     * {@code EscalatorChimePlayer} 当缓存键，保证「音量一改，下一个 tick 立刻生效」。
     * 同样**绝不能**用「缓存到方块变化为止」那套。
     */
    public static long clientHelpVolumeGeneration() {
        return clientHelpVolumeGeneration;
    }

    /** 见 {@link #clientHelpVolumeGeneration()}。只在客户端线程写。 */
    private static long clientHelpVolumeGeneration;

    // ------------------------------------------------------------------
    // 【1.24】/futiround 与 /futihelpround：两个「淡入淡出范围」（单位格）
    //
    //   底噪（/futiround）默认 16 格、提示音（/futihelpround）默认 4 格 —— 与 1.17 定的
    //   「底噪按整条扶梯 16 格、提示音按端头单块 4 格」完全一致，只是现在可调了。
    //
    //   两层：维度默认（X） + 每条扶梯单独设置。**1.24 没有石斧界面控件**，
    //   所以单独设置这一层目前只能由 `-f <X> to <Y>` 间接产生；数据模型与其它可调项一致。
    //
    //   ★ 坑 17 在此同样适用：范围是「由网络同步驱动、随时会变」的条件，
    //     播放侧必须**逐 tick 现算**（或按代次缓存），绝不能塞进任何「按 anchor 缓存的几何计算」里，
    //     否则玩家站在扶梯上不动时改了范围也永远不生效。
    // ------------------------------------------------------------------

    /** 【1.24】这条扶梯**运行底噪**的生效可闻范围（单独设置 &gt; 维度默认；1~128 格）。 */
    public static int getRound(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_ROUND;
            }
            Integer own = findChainRound(data.blockRound, level, pos);
            return own != null ? own : data.defaultRound;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainRound(data.blockRound, level, pos);
        return own != null ? own : data.defaultRound;
    }

    /** 【1.24】这条扶梯是否被**单独设置**过底噪范围（顺扶梯链找）。 */
    public static boolean hasOwnRound(Level level, BlockPos pos) {
        Map<BlockPos, Integer> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockRound;
        } else {
            overrides = getServerData((ServerLevel) level).blockRound;
        }
        return findChainRound(overrides, level, pos) != null;
    }

    /** 顺扶梯链找单独设置的底噪范围：自己这块优先，其次链上其它方块；整条链都没有返回 null。 */
    private static Integer findChainRound(Map<BlockPos, Integer> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Integer own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Integer value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.24】该维度的默认底噪范围（/futiround 设置；未设置过就是 16 格）。 */
    public static int getDefaultRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_ROUND : data.defaultRound;
        }
        return getServerData((ServerLevel) level).defaultRound;
    }

    /** ★【10-03】该维度的默认底噪范围——**垂直（y 轴）**维（未设置过就是 5 格）。 */
    public static int getDefaultRoundY(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_ROUND_Y : data.defaultRoundY;
        }
        return getServerData((ServerLevel) level).defaultRoundY;
    }

    /** ★【10-03】同 {@link #getRound}，但取**垂直（y 轴）**维；无记录 ⇒ 该维维度默认（初始 5 格）。 */
    public static int getRoundY(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_ROUND_Y;
            }
            Integer own = findChainRound(data.blockRoundY, level, pos);
            return own != null ? own : data.defaultRoundY;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainRound(data.blockRoundY, level, pos);
        return own != null ? own : data.defaultRoundY;
    }

    /** 【1.24】设置某条扶梯的底噪范围（服务端，★【10-03】双维：水平 xz + 垂直 y）。返回夹取后的水平值。 */
    public static int setRound(ServerLevel level, BlockPos pos, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampRound(xz);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockRound.remove(p);
            data.blockRoundY.remove(p);
        }
        data.blockRound.remove(pos);
        data.blockRoundY.remove(pos);
        data.setRound(pos, v, y);
        data.setDirty();
        return v;
    }

    /** /futiround &lt;水平&gt; &lt;垂直&gt;：只改**默认**范围，单独设置过的不变。 */
    public static void setDefaultRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultRound = EscalatorSpeedData.clampRound(xz);
        data.defaultRoundY = EscalatorSpeedData.clampRound(y);
        data.setDirty();
    }

    /**
     * /futiround -f &lt;水平&gt; &lt;垂直&gt;：强制**所有**扶梯底噪范围 = 该值（设默认 + 清掉所有单独设置）。
     *
     * @return 被清掉的单独设置数（水平 / 垂直两表的并集）
     */
    public static int forceDefaultRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = unionKeys(data.blockRound, data.blockRoundY).size();
        data.defaultRound = EscalatorSpeedData.clampRound(xz);
        data.defaultRoundY = EscalatorSpeedData.clampRound(y);
        data.blockRound.clear();
        data.blockRoundY.clear();
        data.setDirty();
        return cleared;
    }

    /** /futiround &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt;：默认范围**两维都**正好时才改成新值。 */
    public static boolean replaceDefaultRound(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultRound != fromXz || data.defaultRoundY != fromY) {
            return false;
        }
        data.defaultRound = EscalatorSpeedData.clampRound(toXz);
        data.defaultRoundY = EscalatorSpeedData.clampRound(toY);
        data.setDirty();
        return true;
    }

    /** /futiround -f &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt;：把**生效范围两维都正好是** Xz/Y 的扶梯改成新值。 */
    public static int forceReplaceRoundFromTo(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        int targetXz = EscalatorSpeedData.clampRound(toXz);
        int targetY = EscalatorSpeedData.clampRound(toY);
        int changed = 0;
        if (data.defaultRound == fromXz && data.defaultRoundY == fromY) {
            data.defaultRound = targetXz;
            data.defaultRoundY = targetY;
            changed++;
        }
        for (BlockPos pos : unionKeys(data.blockRound, data.blockRoundY)) {
            if (data.getRound(pos) == fromXz && data.getRoundY(pos) == fromY) {
                data.setRound(pos, targetXz, targetY);
                changed++;
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * ★【10-03】两张「按方块」表的**键并集** —— 拆双维后一侧可能只调了水平、另一侧只调了垂直，
     * 所以任何「按方块逐个判定」的逻辑都必须看并集，漏一侧就是「敲得响、值没落盘」。
     */
    private static java.util.Set<BlockPos> unionKeys(Map<BlockPos, Integer> a, Map<BlockPos, Integer> b) {
        java.util.Set<BlockPos> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    /**
     * 【1.24】该维度里**可能的最大**底噪范围 = max(默认范围, 所有单独设置的最大值)。
     *
     * <p>只用来定「去多远找扶梯」的扫描半径 —— 真正判定「听不听得见」时用的是
     * {@link #getRound} 给出的**那条扶梯自己的**范围。单独设置表平时是空的，所以这就是默认值本身。
     *
     * <p>★【10-03】拆双维后这里取 **xz / y 两维的公共上界**（扫描是逐轴立方体预筛，用一个大半径
     * 同时盖住两维即可；精确判定仍在播放侧按各自那一维再比一次）。
     */
    public static int getMaxRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return Math.max(EscalatorSpeedData.DEFAULT_ROUND, EscalatorSpeedData.DEFAULT_ROUND_Y);
            }
            return Math.max(maxOf(data.defaultRound, data.blockRound),
                    maxOf(data.defaultRoundY, data.blockRoundY));
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return Math.max(maxOf(data.defaultRound, data.blockRound),
                maxOf(data.defaultRoundY, data.blockRoundY));
    }

    /** 【1.24】应用服务端同步过来的底噪范围表（★【10-03】双维：水平表 + 垂直表，覆盖式更新）。 */
    public static void applyClientRounds(ResourceKey<Level> dimension, int defaultRound,
                                        Map<BlockPos, Integer> blockRound,
                                        int defaultRoundY, Map<BlockPos, Integer> blockRoundY) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockRound.clear();
        data.blockRound.putAll(blockRound);
        data.defaultRound = defaultRound;
        data.blockRoundY.clear();
        data.blockRoundY.putAll(blockRoundY);
        data.defaultRoundY = defaultRoundY;
        clientRoundGeneration++;
    }

    /**
     * 【1.24】底噪范围的「代次」：只在客户端镜像被整体替换时（收到 ROUND_SYNC、断开清镜像）递增。
     *
     * <p>给每 tick 都要读范围的播放器当缓存键，保证「范围一改，下一个 tick 立刻生效」。
     */
    public static long clientRoundGeneration() {
        return clientRoundGeneration;
    }

    /** 见 {@link #clientRoundGeneration()}。只在客户端线程写。 */
    private static long clientRoundGeneration;

    /** 【1.24】这条扶梯**无障碍提示音**的生效可闻范围（单独设置 &gt; 维度默认；1~128 格）。 */
    public static int getHelpRound(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_ROUND;
            }
            Integer own = findChainRound(data.blockHelpRound, level, pos);
            return own != null ? own : data.defaultHelpRound;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainRound(data.blockHelpRound, level, pos);
        return own != null ? own : data.defaultHelpRound;
    }

    /** ★【10-03】同 {@link #getHelpRound}，但取**垂直（y 轴）**维；无记录 ⇒ 该维维度默认（初始 5 格）。 */
    public static int getHelpRoundY(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_ROUND_Y;
            }
            Integer own = findChainRound(data.blockHelpRoundY, level, pos);
            return own != null ? own : data.defaultHelpRoundY;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainRound(data.blockHelpRoundY, level, pos);
        return own != null ? own : data.defaultHelpRoundY;
    }

    /** 【1.24】这条扶梯是否被**单独设置**过提示音范围（顺扶梯链找）。 */
    public static boolean hasOwnHelpRound(Level level, BlockPos pos) {
        Map<BlockPos, Integer> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockHelpRound;
        } else {
            overrides = getServerData((ServerLevel) level).blockHelpRound;
        }
        return findChainRound(overrides, level, pos) != null;
    }

    /** 【1.24】该维度的默认提示音范围（/futihelpround 设置；未设置过就是 4 格）。 */
    public static int getDefaultHelpRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_ROUND : data.defaultHelpRound;
        }
        return getServerData((ServerLevel) level).defaultHelpRound;
    }

    /** ★【10-03】该维度的默认提示音范围——**垂直（y 轴）**维（未设置过就是 5 格）。 */
    public static int getDefaultHelpRoundY(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_ROUND_Y : data.defaultHelpRoundY;
        }
        return getServerData((ServerLevel) level).defaultHelpRoundY;
    }

    /** 【1.24】设置某条扶梯的提示音范围（服务端，★【10-03】双维）。返回夹取后的水平值。 */
    public static int setHelpRound(ServerLevel level, BlockPos pos, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpRound(xz);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpRound.remove(p);
            data.blockHelpRoundY.remove(p);
        }
        data.blockHelpRound.remove(pos);
        data.blockHelpRoundY.remove(pos);
        data.setHelpRound(pos, v, y);
        data.setDirty();
        return v;
    }

    /** /futihelpround &lt;水平&gt; &lt;垂直&gt;：只改**默认**范围，单独设置过的不变。 */
    public static void setDefaultHelpRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpRound = EscalatorSpeedData.clampHelpRound(xz);
        data.defaultHelpRoundY = EscalatorSpeedData.clampHelpRound(y);
        data.setDirty();
    }

    /** /futihelpround -f &lt;水平&gt; &lt;垂直&gt;：强制所有扶梯提示音范围 = 该值（设默认 + 清单独设置）。 */
    public static int forceDefaultHelpRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = unionKeys(data.blockHelpRound, data.blockHelpRoundY).size();
        data.defaultHelpRound = EscalatorSpeedData.clampHelpRound(xz);
        data.defaultHelpRoundY = EscalatorSpeedData.clampHelpRound(y);
        data.blockHelpRound.clear();
        data.blockHelpRoundY.clear();
        data.setDirty();
        return cleared;
    }

    /** /futihelpround &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt;：默认范围**两维都**正好时才改成新值。 */
    public static boolean replaceDefaultHelpRound(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpRound != fromXz || data.defaultHelpRoundY != fromY) {
            return false;
        }
        data.defaultHelpRound = EscalatorSpeedData.clampHelpRound(toXz);
        data.defaultHelpRoundY = EscalatorSpeedData.clampHelpRound(toY);
        data.setDirty();
        return true;
    }

    /** /futihelpround -f &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt;：生效范围两维都正好是 Xz/Y 的扶梯改成新值。 */
    public static int forceReplaceHelpRoundFromTo(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        int targetXz = EscalatorSpeedData.clampHelpRound(toXz);
        int targetY = EscalatorSpeedData.clampHelpRound(toY);
        int changed = 0;
        if (data.defaultHelpRound == fromXz && data.defaultHelpRoundY == fromY) {
            data.defaultHelpRound = targetXz;
            data.defaultHelpRoundY = targetY;
            changed++;
        }
        for (BlockPos pos : unionKeys(data.blockHelpRound, data.blockHelpRoundY)) {
            if (data.getHelpRound(pos) == fromXz && data.getHelpRoundY(pos) == fromY) {
                data.setHelpRound(pos, targetXz, targetY);
                changed++;
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.24】该维度里**可能的最大**提示音范围（见 {@link #getMaxRound}；★【10-03】含两维）。 */
    public static int getMaxHelpRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return Math.max(EscalatorSpeedData.DEFAULT_HELP_ROUND, EscalatorSpeedData.DEFAULT_HELP_ROUND_Y);
            }
            return Math.max(maxOf(data.defaultHelpRound, data.blockHelpRound),
                    maxOf(data.defaultHelpRoundY, data.blockHelpRoundY));
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return Math.max(maxOf(data.defaultHelpRound, data.blockHelpRound),
                maxOf(data.defaultHelpRoundY, data.blockHelpRoundY));
    }

    /** 【1.24】应用服务端同步过来的提示音范围表（★【10-03】双维：水平表 + 垂直表，覆盖式更新）。 */
    public static void applyClientHelpRounds(ResourceKey<Level> dimension, int defaultHelpRound,
                                             Map<BlockPos, Integer> blockHelpRound,
                                             int defaultHelpRoundY, Map<BlockPos, Integer> blockHelpRoundY) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelpRound.clear();
        data.blockHelpRound.putAll(blockHelpRound);
        data.defaultHelpRound = defaultHelpRound;
        data.blockHelpRoundY.clear();
        data.blockHelpRoundY.putAll(blockHelpRoundY);
        data.defaultHelpRoundY = defaultHelpRoundY;
        clientHelpRoundGeneration++;
    }

    /** 【1.24】提示音范围的「代次」，用途同 {@link #clientRoundGeneration()}。 */
    public static long clientHelpRoundGeneration() {
        return clientHelpRoundGeneration;
    }

    /** 见 {@link #clientHelpRoundGeneration()}。只在客户端线程写。 */
    private static long clientHelpRoundGeneration;

    // ------------------------------------------------------------------
    // 【1.31】无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   ★ 这里管的是**端头那一路提示音**「响得多快」，和上面四个维度凑成完整的一套：
    //     /futihelp（开关）、/futihelploud（音量）、/futihelpround（范围）、
    //     /futihelpspeed in|out（速率）。四套数据与四条指令互不影响。
    //   ★ 入口（上客端）与出口（落客端）是**两套**数据：同一个指令的两个子命令。
    //   ★ 与 1.24 的两个范围一样**没有石斧界面控件**，只有 S→C 同步、没有 SET 通道。
    //
    //   速率是「换素材 + 调 pitch」实现的（原版 SoundEngine 把 pitch 夹在 [0.5,2.0]），
    //   细节见 EscalatorChimePlayer#chimeEventFor / #chimePitchFor。
    //   两套速率总是同时设置、同时同步，所以**共用一只同步包和一个代次**（见 applyClientHelpSpeeds）。
    // ------------------------------------------------------------------

    /** 【1.31】这条扶梯**上客端（进入扶梯）**提示音的生效速率（Hz，单独设置 &gt; 维度默认；1~100）。 */
    public static int getHelpSpeedIn(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_SPEED_IN;
            }
            Integer own = findChainHelpSpeed(data.blockHelpSpeedIn, level, pos);
            return own != null ? own : data.defaultHelpSpeedIn;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainHelpSpeed(data.blockHelpSpeedIn, level, pos);
        return own != null ? own : data.defaultHelpSpeedIn;
    }

    /** 【1.31】这条扶梯**落客端（离开扶梯）**提示音的生效速率（Hz）。 */
    public static int getHelpSpeedOut(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT;
            }
            Integer own = findChainHelpSpeed(data.blockHelpSpeedOut, level, pos);
            return own != null ? own : data.defaultHelpSpeedOut;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainHelpSpeed(data.blockHelpSpeedOut, level, pos);
        return own != null ? own : data.defaultHelpSpeedOut;
    }

    /** 【1.31】这条扶梯是否被**单独设置**过上客端速率（顺扶梯链找）。 */
    public static boolean hasOwnHelpSpeedIn(Level level, BlockPos pos) {
        return findChainHelpSpeed(helpSpeedMap(level, true), level, pos) != null;
    }

    /** 【1.31】这条扶梯是否被**单独设置**过落客端速率（顺扶梯链找）。 */
    public static boolean hasOwnHelpSpeedOut(Level level, BlockPos pos) {
        return findChainHelpSpeed(helpSpeedMap(level, false), level, pos) != null;
    }

    /** 取（客户端镜像 / 服务端存档的）速率「单独设置」表；{@code in} 为 true 取上客端那套。 */
    private static Map<BlockPos, Integer> helpSpeedMap(Level level, boolean in) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return java.util.Collections.emptyMap();
            }
            return in ? data.blockHelpSpeedIn : data.blockHelpSpeedOut;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return in ? data.blockHelpSpeedIn : data.blockHelpSpeedOut;
    }

    /**
     * 顺扶梯链找单独设置的提示音速率：自己这块优先，其次链上其它方块；整条链都没有返回 null。
     *
     * <p>与 {@link #findChainRound} 同一套逻辑 —— 单独设置是按「整条扶梯」写的，
     * 所以从链上任意一格读都要能读到（否则「在 A 块设过、走到 B 块读出来是默认值」）。
     */
    private static Integer findChainHelpSpeed(Map<BlockPos, Integer> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Integer own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Integer value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.31】该维度的默认上客端提示音速率（/futihelpspeed in 设置；未设置过就是 10 Hz）。 */
    public static int getDefaultHelpSpeedIn(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_SPEED_IN : data.defaultHelpSpeedIn;
        }
        return getServerData((ServerLevel) level).defaultHelpSpeedIn;
    }

    /** 【1.31】该维度的默认落客端提示音速率（/futihelpspeed out 设置；未设置过就是 1 Hz）。 */
    public static int getDefaultHelpSpeedOut(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT : data.defaultHelpSpeedOut;
        }
        return getServerData((ServerLevel) level).defaultHelpSpeedOut;
    }

    /** 【1.31】设置某条扶梯的上客端提示音速率（服务端）。返回夹取后的实际值。 */
    public static int setHelpSpeedIn(ServerLevel level, BlockPos pos, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpSpeed(speed);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpSpeedIn.remove(p);
        }
        data.blockHelpSpeedIn.remove(pos);
        data.setHelpSpeedIn(pos, v);
        data.setDirty();
        return v;
    }

    /** 【1.31】设置某条扶梯的落客端提示音速率（服务端）。返回夹取后的实际值。 */
    public static int setHelpSpeedOut(ServerLevel level, BlockPos pos, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpSpeed(speed);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpSpeedOut.remove(p);
        }
        data.blockHelpSpeedOut.remove(pos);
        data.setHelpSpeedOut(pos, v);
        data.setDirty();
        return v;
    }

    /** /futihelpspeed in &lt;Hz&gt;：只改**默认**上客端速率，单独设置过的不变。 */
    public static void setDefaultHelpSpeedIn(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpSpeedIn = EscalatorSpeedData.clampHelpSpeed(speed);
        data.setDirty();
    }

    /** /futihelpspeed out &lt;Hz&gt;：只改**默认**落客端速率，单独设置过的不变。 */
    public static void setDefaultHelpSpeedOut(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpSpeedOut = EscalatorSpeedData.clampHelpSpeed(speed);
        data.setDirty();
    }

    /**
     * /futihelpspeed -f in &lt;Hz&gt;：强制**所有**扶梯上客端速率 = 该值（设默认 + 清掉所有单独设置）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelpSpeedIn(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpSpeedIn.size();
        data.defaultHelpSpeedIn = EscalatorSpeedData.clampHelpSpeed(speed);
        data.blockHelpSpeedIn.clear();
        data.setDirty();
        return cleared;
    }

    /** /futihelpspeed -f out &lt;Hz&gt;：强制所有扶梯落客端速率 = 该值（设默认 + 清单独设置）。 */
    public static int forceDefaultHelpSpeedOut(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpSpeedOut.size();
        data.defaultHelpSpeedOut = EscalatorSpeedData.clampHelpSpeed(speed);
        data.blockHelpSpeedOut.clear();
        data.setDirty();
        return cleared;
    }

    /** /futihelpspeed in &lt;X&gt; to &lt;Y&gt;：默认上客端速率正好是 X 时才改成 Y；单独设置的不动。 */
    public static boolean replaceDefaultHelpSpeedIn(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpSpeedIn != from) {
            return false;
        }
        data.defaultHelpSpeedIn = EscalatorSpeedData.clampHelpSpeed(to);
        data.setDirty();
        return true;
    }

    /** /futihelpspeed out &lt;X&gt; to &lt;Y&gt;：默认落客端速率正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultHelpSpeedOut(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpSpeedOut != from) {
            return false;
        }
        data.defaultHelpSpeedOut = EscalatorSpeedData.clampHelpSpeed(to);
        data.setDirty();
        return true;
    }

    /** /futihelpspeed -f in &lt;X&gt; to &lt;Y&gt;：把所有**生效上客端速率正好是 X** 的（含单独设置的）改成 Y。 */
    public static int forceReplaceHelpSpeedInFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpSpeed(to);
        int changed = 0;
        if (data.defaultHelpSpeedIn == from) {
            data.defaultHelpSpeedIn = target;
            changed++;
        }
        for (BlockPos pos : matchingKeys(data.blockHelpSpeedIn, from)) {
            data.setHelpSpeedIn(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** /futihelpspeed -f out &lt;X&gt; to &lt;Y&gt;：把所有生效落客端速率正好是 X 的（含单独设置的）改成 Y。 */
    public static int forceReplaceHelpSpeedOutFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpSpeed(to);
        int changed = 0;
        if (data.defaultHelpSpeedOut == from) {
            data.defaultHelpSpeedOut = target;
            changed++;
        }
        for (BlockPos pos : matchingKeys(data.blockHelpSpeedOut, from)) {
            data.setHelpSpeedOut(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 先收集「值正好是 from」的键再改，避免边遍历边改 map（和 round 那两处同一手法）。 */
    private static List<BlockPos> matchingKeys(Map<BlockPos, Integer> overrides, int from) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : overrides.entrySet()) {
            if (entry.getValue() == from) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * 【1.31】应用服务端同步过来的两套提示音速率（覆盖式更新）。
     *
     * <p>入口与出口总是同一条指令一起设置、一起同步，所以共用一只包 → 一次覆盖两张表、代次只 +1。
     */
    public static void applyClientHelpSpeeds(ResourceKey<Level> dimension,
                                             int defaultIn, Map<BlockPos, Integer> blockIn,
                                             int defaultOut, Map<BlockPos, Integer> blockOut) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelpSpeedIn.clear();
        data.blockHelpSpeedIn.putAll(blockIn);
        data.defaultHelpSpeedIn = defaultIn;
        data.blockHelpSpeedOut.clear();
        data.blockHelpSpeedOut.putAll(blockOut);
        data.defaultHelpSpeedOut = defaultOut;
        clientHelpSpeedGeneration++;
    }

    /**
     * 【1.31】提示音速率的「代次」，用途同 {@link #clientRoundGeneration()} ——
     * 给每 tick 现算速率的播放器当缓存键，保证「指令一改，下一个 tick 立刻换速度」。
     */
    public static long clientHelpSpeedGeneration() {
        return clientHelpSpeedGeneration;
    }

    /** 见 {@link #clientHelpSpeedGeneration()}。只在客户端线程写。 */
    private static long clientHelpSpeedGeneration;

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音**音乐**（/futihelpmusic in|out + 提示音选择界面）
    //
    // 数据模型与 /futimusic 完全对称：
    //   defaultHelpAudioIn/Out = 「默认」层（没单独设置的扶梯都用它，初始 = 模组原来的提示音）；
    //   blockHelpAudioIn/Out   = 「被单独设置过」的扶梯（界面上点的那一条）。
    // 但**音频字节共用同一份 audioLibrary**（与运行底噪同一个导入文件夹 / 同一个库）。
    // ★【1.41】「进入扶梯（上客端）」与「离开扶梯（落客端）」是**两套独立数据**
    //   （形状同 /futihelpspeed 的 in|out），两头一起同步、共用一只包与一个代次
    //   （见 applyClientHelpAudio / clientHelpAudioGeneration）。
    // ★ 这是「可变条件」：不能塞进按 anchor 缓存的几何结果里（见 EscalatorChimePlayer 的
    //   helpAudioIds，与 helpEnabled 同一套代次缓存）。
    // ------------------------------------------------------------------

    /** 【1.41】应用服务端同步过来的提示音音乐（进 / 出两套：默认层 + 单独设置层，覆盖式更新）。 */
    public static void applyClientHelpAudio(ResourceKey<Level> dimension,
                                            String defaultIn, Map<BlockPos, String> blockIn,
                                            String defaultOut, Map<BlockPos, String> blockOut) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.defaultHelpAudioIn = normaliseHelpAudio(defaultIn);
        data.blockHelpAudioIn.clear();
        data.blockHelpAudioIn.putAll(blockIn);
        data.defaultHelpAudioOut = normaliseHelpAudio(defaultOut);
        data.blockHelpAudioOut.clear();
        data.blockHelpAudioOut.putAll(blockOut);
        clientHelpAudioGeneration++;
    }

    /**
     * 【1.41】提示音音乐的「代次」，用途同 {@link #clientHelpSpeedGeneration()} ——
     * 给每 tick 现算「这条扶梯该播哪段提示音」的播放器当缓存键。
     */
    public static long clientHelpAudioGeneration() {
        return clientHelpAudioGeneration;
    }

    /** 见 {@link #clientHelpAudioGeneration()}。只在客户端线程写。 */
    private static long clientHelpAudioGeneration;

    /** 空 / null 一律归到「默认」（= 模组原来的提示音），别让 null 漏进播放器。 */
    private static String normaliseHelpAudio(String audioId) {
        return audioId == null || audioId.isEmpty() ? EscalatorSpeedData.HELP_AUDIO_DEFAULT : audioId;
    }

    /**
     * 【1.41】客户端：当前维度默认的提示音音乐 ID；镜像还没到时返回 default。
     * {@code in} 为 true = 进入扶梯（上客端）那一头。
     */
    public static String getClientDefaultHelpAudio(ResourceKey<Level> dimension, boolean in) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        if (data == null) {
            return EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        }
        return normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
    }

    /**
     * 【1.41】该扶梯方块**单独设置**的提示音音乐 ID；没单独设置返回 null。
     * 客户端读镜像，服务端读 SavedData。{@code in} 为 true = 上客端。
     */
    public static String getBlockHelpAudioId(Level level, BlockPos pos, boolean in) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return null;
            }
            return (in ? data.blockHelpAudioIn : data.blockHelpAudioOut).get(pos);
        }
        return getServerData((ServerLevel) level).getHelpAudioId(pos, in);
    }

    /** 「默认值 + 单独设置表」里可能的最大值（表为空时就是默认值）。 */
    private static int maxOf(int defaultRound, Map<BlockPos, Integer> overrides) {
        int max = defaultRound;
        for (int v : overrides.values()) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    /** 【1.7】该扶梯方块绑定的音频ID；无绑定返回 null。客户端读镜像，服务端读 SavedData。 */
    public static String getBlockAudioId(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.blockAudio.get(pos);
        }
        return getServerData((ServerLevel) level).getAudioId(pos);
    }

    /** 【1.7】取音频字节；不存在返回 null。客户端读镜像，服务端读 SavedData。 */
    public static byte[] getAudioBytes(Level level, String audioId) {
        if (audioId == null) {
            return null;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.audioLibrary.get(audioId);
        }
        return getServerData((ServerLevel) level).audioLibrary.get(audioId);
    }

    /** 【1.7】客户端：当前维度全部扶梯-音频绑定（只读视图），供声音播放器每 tick 遍历候选；无数据返回空 Map。 */
    public static Map<BlockPos, String> getClientAudioBindings(ResourceKey<Level> dimension) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        return data == null ? Map.of() : java.util.Collections.unmodifiableMap(data.blockAudio);
    }

    /** 断开连接时清空客户端镜像，避免换世界后残留旧数据。 */
    public static void clearClientData() {
        CLIENT_DATA.clear();
        clientHelpGeneration++;
        clientHelpVolumeGeneration++;
        // 【1.24】两个范围的代次也要 ++：镜像被清空同样是一次「整体替换」，
        // 否则播放器会继续用上一个世界缓存下来的范围。
        clientRoundGeneration++;
        clientHelpRoundGeneration++;
        // 【1.39】提示音音乐的代次同样要 ++：镜像被清空是一次「整体替换」，
        // 否则播放器会继续用上一个世界缓存下来的提示音。
        clientHelpAudioGeneration++;
        // 【1.42】直梯提示音设置也同理：不断代的话，断线重连后会沿用上一个世界的开关/倍速。
        clientLiftChimeGeneration++;
    }

    // ------------------------------------------------------------------
    // 【1.8】内置音频：随模组 jar 一起分发（assets/smoothlift/sounds/audio/*.ogg，
    // 并在 assets/smoothlift/sounds.json 里注册成 smoothlift:audio/* 声音事件）。
    // 好处：玩家装好模组就能直接绑定、开箱即用 —— 不需要自己准备 .ogg 文件，
    // 也不需要任何转码工具（ffmpeg 只在"作者制作音频"时用得到，与玩家无关）。
    // 绑定到扶梯时存的是带前缀的 ID（builtin:<key>），不会和存档音频库里的文件名撞车。
    //
    // 目前只内置 1 段（按需求精简）：
    //   subway_escalator.ogg（约 23.6 秒）—— 素材 = Freesound "4_Escalator.wav"
    //   https://freesound.org/people/14G_Panska_Hoskovcova_Eliska/sounds/419482/
    //   作者 14G_Panska_Hoskovcova_Eliska，许可 CC0 1.0（公共领域奉献，无需署名，
    //   可商用/可修改/可再分发）。出处与许可见 jar 内 AUDIO-CREDITS.txt。
    // ------------------------------------------------------------------

    /** 内置音频绑定 ID 的前缀。 */
    public static final String BUILTIN_PREFIX = "builtin:";

    /**
     * 内置音频：key（= 资源文件名，同时也是 sounds.json 里 audio/&lt;key&gt; 的名字）-&gt; 界面显示名。
     *
     * <p><b>不要随意改 key</b>：玩家的存档里存的是 {@code builtin:<key>},
     * 改名会让已经绑定过的扶梯静音。
     */
    private static final Map<String, String> BUILTIN_AUDIO = new LinkedHashMap<>();

    static {
        BUILTIN_AUDIO.put("subway_escalator", "内置 · 地铁自动扶梯");
    }

    /** 全部内置音频的绑定 ID（已带 {@link #BUILTIN_PREFIX} 前缀），顺序固定，供界面列出。 */
    public static List<String> builtinAudioIds() {
        List<String> out = new ArrayList<>(BUILTIN_AUDIO.size());
        for (String key : BUILTIN_AUDIO.keySet()) {
            out.add(BUILTIN_PREFIX + key);
        }
        return out;
    }

    /** 这个绑定 ID 是不是内置音频。 */
    public static boolean isBuiltinAudio(String audioId) {
        return audioId != null && audioId.startsWith(BUILTIN_PREFIX)
                && BUILTIN_AUDIO.containsKey(audioId.substring(BUILTIN_PREFIX.length()));
    }

    /** 内置音频的 key（去掉前缀，= 资源文件名）；不是内置时返回 null。 */
    public static String builtinKey(String audioId) {
        if (!isBuiltinAudio(audioId)) {
            return null;
        }
        return audioId.substring(BUILTIN_PREFIX.length());
    }

    /** 内置音频在界面上的中文显示名；不是内置时原样返回 ID。 */
    public static String displayName(String audioId) {
        String key = builtinKey(audioId);
        if (key == null) {
            return audioId;
        }
        String name = BUILTIN_AUDIO.get(key);
        return name != null ? name : key;
    }

    // ------------------------------------------------------------------
    // 【1.7】存档音频来源文件夹：<存档>/MBM_Audio/。该文件夹只是"上传来源"：
    // 选中一个文件后会把内容拷进 SavedData（融入存档），之后删掉原文件仍可播放。
    //
    // 【1.53】用户点名改名为 MBM_Audio（原名 smoothlift_audio）。
    // ★ 改的只是**来源文件夹**的名字：音频字节导入后存在 SavedData 里，
    //   已导入的音频不受影响；改名之后旧文件夹里还没导入过的 ogg 不会再出现在
    //   「待导入」列表里 —— 把文件挪进 MBM_Audio 即可（模组**不会**自动搬文件）。
    // ------------------------------------------------------------------

    /** 存档目录下存放待导入 OGG 的文件夹名。【1.53】smoothlift_audio → MBM_Audio。 */
    public static final String AUDIO_FOLDER = "MBM_Audio";

    // ------------------------------------------------------------------
    // 【1.28】音频分类（音频隔离）
    //
    // 每个设置项只读自己分类的**子文件夹**（MBM_Audio/<分类>）里的 .ogg：
    //   待导入列表 = 该子文件夹里还没入库的文件；
    //   导入存档后名字记进**这个分类**的注册表（audioCategoryNames）；
    //   已存入列表 = 只有这个分类导入过的音频。
    // 目录总览（MBM_Audio 下的分组目录：futi / train / pbm / lift）：
    //   futi/music      扶梯运行底噪（/futimusic、石斧 UI 扶梯音乐）
    //   futi/help       扶梯无障碍提示音（/futihelpmusic、石斧 UI 提示音音乐）
    //   train/run       列车运行音效（石斧 UI 侧线「列车音效」第 1 页）
    //   train/round     列车转弯音效（同上第 2 页）
    //   train/switch    列车道岔音效（同上第 3 页）
    //   train/in        列车进站音效（同上第 4 页）
    //   train/out       列车出站音效（同上第 5 页）
    //   pbm/open        屏蔽门开门提示音（/pbmmusic open、石斧 UI 屏蔽门第 1 页）
    //   pbm/close       屏蔽门关门提示音（/pbmmusic close、石斧 UI 第 2 页）
    //   pbm/midium      屏蔽门到站播报（/pbmmidium、石斧 UI 第 3 页）
    //   pbm/arrive      屏蔽门进站报站（/pbmarrive、石斧 UI 第 4 页）
    //   lift/up         直梯上楼提示音（/lifthelp up）
    //   lift/down       直梯下楼提示音（/lifthelp down）
    //   lift/open       直梯开门提示音（/lifthelp open）
    //   lift/close      直梯关门提示音（/lifthelp close）
    // ★ futi/ 、train/ 与 pbm/、lift/ 一样只是**分组目录**：它们本身不是分类 ⇒ 里面不放 ogg，
    //   futi/ 只放 music/ 与 help/，train/ 只放 run/ round/ switch/ in/ out/。
    //   （判据：没有任何分类字符串恰好 == "futi" / "train" / "pbm" / "lift"。）
    // ★★【09-27 三次改版】旧的 `pbm/music`（列车音效）**已删**，作用拆成上面 train/ 五项；
    //   老存档里的 pbm/music 由 migrateCategoryKey 迁到 train/run（文件夹也由
    //   migrateLegacyAudioFolder 搬过去）。
    // ------------------------------------------------------------------

    public static final String CAT_FUTI = "futi/music";
    public static final String CAT_HELP = "futi/help";
    public static final String CAT_TRAIN_RUN = "train/run";
    public static final String CAT_TRAIN_ROUND = "train/round";
    public static final String CAT_TRAIN_SWITCH = "train/switch";
    public static final String CAT_TRAIN_IN = "train/in";
    public static final String CAT_TRAIN_OUT = "train/out";
    public static final String CAT_PSD_OPEN = "pbm/open";
    public static final String CAT_PSD_CLOSE = "pbm/close";
    public static final String CAT_PSD_MIDIUM = "pbm/midium";
    public static final String CAT_PSD_ARRIVE = "pbm/arrive";
    public static final String CAT_LIFT_UP = "lift/up";
    public static final String CAT_LIFT_DOWN = "lift/down";
    public static final String CAT_LIFT_OPEN = "lift/open";
    public static final String CAT_LIFT_CLOSE = "lift/close";
    // 【09-30】闸机（MTR Ticket Barrier）两类：进站 / 出站各一个子文件夹。
    //   用户点名的自定义素材位置就是 MBM_Audio\zhaji\in 与 MBM_Audio\zhaji\out。
    public static final String CAT_ZHAJI_IN = "zhaji/in";
    public static final String CAT_ZHAJI_OUT = "zhaji/out";

    /**
     * 【09-29】{@code MBM_Audio} 下的四个**分组目录**名（上面那段总览里的 futi / train / pbm / lift）。
     *
     * <p>它们本身**不是**分类（判据：没有任何 {@link #ALL_CATEGORIES} 里的字符串恰好等于分组名）
     * ⇒ 里面不放 ogg，只放下一层分类文件夹。
     *
     * <p>★ 用途：界面右上角那个「打开文件夹」按钮（{@link smooth.lift.client.FolderOpenButton}）。
     * 一级菜单（扶梯主界面 / 直梯一级菜单 / 列车音效一级页 / 屏蔽门主界面）点开的就是这一层 ——
     * 玩家先看到「这一组下面有哪些分类文件夹」，再进二级页点开对应的**子**文件夹，
     * 与「已导入 / 未导入」列表读的是同一个目录树，不会出现「按钮开的目录和列表看的目录不是一个」。
     */
    public static final String GROUP_FUTI = "futi";
    public static final String GROUP_TRAIN = "train";
    public static final String GROUP_PSD = "pbm";
    public static final String GROUP_LIFT = "lift";
    /** 【09-30】闸机分组目录 {@code MBM_Audio/zhaji}（里面 in/ out/ 两个分类）。 */
    public static final String GROUP_ZHAJI = "zhaji";

    /** 全部音频分类（顺序 = 存档/同步包的书写顺序，别改；旧存档迁移也按它铺名字）。 */
    public static final String[] ALL_CATEGORIES = {
            CAT_FUTI, CAT_HELP,
            CAT_TRAIN_RUN, CAT_TRAIN_ROUND, CAT_TRAIN_SWITCH, CAT_TRAIN_IN, CAT_TRAIN_OUT,
            CAT_PSD_OPEN, CAT_PSD_CLOSE, CAT_PSD_MIDIUM, CAT_PSD_ARRIVE,
            CAT_LIFT_UP, CAT_LIFT_DOWN, CAT_LIFT_OPEN, CAT_LIFT_CLOSE,
            CAT_ZHAJI_IN, CAT_ZHAJI_OUT,
    };

    /**
     * 【09-27 三次改版】旧**文件夹**相对路径 → 现在该放的分类（服务端启动时把老 .ogg 搬过去）。
     *
     * <p>与 {@link #migrateCategoryKey(String)}（NBT 键）配套：键迁了、文件不搬的话，
     * 界面「已存入」有名字却找不到文件（或反过来）。只搬 {@code .ogg}、**不覆盖**同名文件；
     * 源目录搬空后才删，非空（还有别的东西）就留着 —— 绝不递归删。
     * <ul>
     *   <li>{@code pbm/futi} → {@link #CAT_FUTI}</li>
     *   <li>{@code futi}（一轮中间改版：.ogg 直接躺在 futi/ 下）→ {@link #CAT_FUTI}</li>
     *   <li>{@code pbm/help} → {@link #CAT_HELP}</li>
     *   <li>{@code pbm/music} → {@link #CAT_TRAIN_RUN}</li>
     * </ul>
     * ★ 不搬 {@code futi/music}：它现在**就是** {@link #CAT_FUTI} 的新路径，不是旧目录。
     */
    private static final String[][] LEGACY_FOLDERS = {
            {"pbm/futi", CAT_FUTI},
            {"futi", CAT_FUTI},
            {"pbm/help", CAT_HELP},
            {"pbm/music", CAT_TRAIN_RUN},
    };

    /**
     * 【1.28+】旧分类键 → 新分类键（改过目录的是**扶梯两类**与**列车音效**）。
     *
     * <p>读档时用它迁移 {@code audioCategoryNames} 的键：否则改名之后，旧存档里那些分类
     * 的「已存入」注册表会找不到 → 界面右列看着像「没导入过」（字节其实还在 audioLibrary 里）。
     * <ul>
     *   <li>{@code pbm/futi}（扶梯底噪原始路径）→ {@link #CAT_FUTI}</li>
     *   <li>{@code futi}（一轮中间改版的底噪路径）→ {@link #CAT_FUTI}</li>
     *   <li>{@code pbm/help}（扶梯提示音原始路径）→ {@link #CAT_HELP}</li>
     *   <li>{@code pbm/music}（列车音效原始路径）→ {@link #CAT_TRAIN_RUN}</li>
     * </ul>
     * 屏蔽门 / 直梯都没改过路径，无需迁移。
     * ★ 千万不要加 {@code futi/music} → train/run：{@code futi/music} 现在是**扶梯底噪**的新键，
     *   映射它会导致底噪数据每次读档都被误迁到列车音效（往返 bug）。
     */
    public static String migrateCategoryKey(String legacyKey) {
        if ("pbm/futi".equals(legacyKey)) {
            return CAT_FUTI;
        }
        if ("futi".equals(legacyKey)) {
            return CAT_FUTI;
        }
        if ("pbm/help".equals(legacyKey)) {
            return CAT_HELP;
        }
        if ("pbm/music".equals(legacyKey)) {
            return CAT_TRAIN_RUN;
        }
        return legacyKey;
    }

    /** 该世界存档的音频来源文件夹路径（根）。 */
    public static Path audioFolder(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(AUDIO_FOLDER);
    }

    /** 该世界存档某个分类的子文件夹路径（{@code MBM_Audio/<分类>}）。 */
    public static Path audioFolder(ServerLevel level, String category) {
        return audioFolder(level).resolve(category);
    }

    /** 确保存档音频来源文件夹（含全部分类子文件夹）存在；不存在则自动创建（服务端启动时调用）。 */
    public static void ensureAudioFolder(ServerLevel level) {
        try {
            Files.createDirectories(audioFolder(level));
            for (String category : ALL_CATEGORIES) {
                Files.createDirectories(audioFolder(level, category));
            }
        } catch (IOException ignored) {
            // 无法创建时忽略：后续扫描遇到不可读文件夹会当作无音频处理。
        }
        // 【09-27 三次改版】把老分类目录里玩家已经放进去的 .ogg 搬到新目录（幂等；搬空才删源目录）。
        for (String[] pair : LEGACY_FOLDERS) {
            migrateLegacyAudioFolder(level, pair[0], pair[1]);
        }
    }

    // ------------------------------------------------------------------
    // 【1.18.1204】地图图片（MBM_Picture）：文件夹 / 扫描 / 导入 / 删除
    //
    // 与音频同一个模式：MBM_Picture 是**来源文件夹**，玩家把图片放进去后由
    // /MBM picture new 导入；图片原始字节存入 SavedData（pictureLibrary），
    // 所以删掉文件夹里的原图也不影响已导入的图片。客户端拿到字节后自行
    // 做「裁四等分 → 缩放 1024 → 加 64px 灰边」并更新图片方块图集贴图。
    // ------------------------------------------------------------------

    /** 存档目录下存放待导入地图图片的文件夹名（服务端启动时自动创建）。 */
    public static final String PICTURE_FOLDER = "MBM_Picture";

    /** 【09-27】单张地图图片任一方向的像素上限。NativeImage 按 宽×高×4 在原生内存分配，
     *  12MB 的压缩大小上限约束不住解码后的内存（曾导致 /MBM picture new 后 OOM），
     *  因此在解码前用文件头探测尺寸并拒绝超限图片。 */
    public static final int MAX_PICTURE_DIMENSION = 8192;

    /** 【09-27】最近一次 {@link #scanPictureFiles} 因体积/尺寸超限被忽略的文件数（供指令提示玩家）。 */
    public static int lastScanRejected = 0;

    /**
     * 【09-27】只解析图片文件头（PNG IHDR / JPEG SOF / BMP 信息头）读取像素宽高，**不整图解码**，
     * 用于在解码前拒绝超大图片。格式无法识别或头部损坏返回 {@code null}（调用方按“未知”处理）。
     */
    public static int[] probeImageSize(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        try {
            // PNG：8 字节签名 + IHDR 块，宽度/高度在偏移 16/20（大端）。
            if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50
                    && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47) {
                if (bytes.length < 24) {
                    return null;
                }
                int w = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16)
                        | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
                int h = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16)
                        | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
                return new int[]{w, h};
            }
            // JPEG：SOI(FFD8) 后按段遍历，找 SOF0~15（段内偏移 +5/+7 为高/宽，大端）。
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                int i = 2;
                while (i + 9 < bytes.length) {
                    if ((bytes[i] & 0xFF) != 0xFF) {
                        i++;
                        continue;
                    }
                    int marker = bytes[i + 1] & 0xFF;
                    if (marker == 0xD9 || marker == 0xDA) {
                        break; // EOI 或 SOS：后面是图像数据，不再有 SOF
                    }
                    boolean sof = marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3
                            || marker == 0xC5 || marker == 0xC6 || marker == 0xC7
                            || marker == 0xC9 || marker == 0xCA || marker == 0xCB
                            || marker == 0xCD || marker == 0xCE || marker == 0xCF;
                    if (sof) {
                        int h = ((bytes[i + 5] & 0xFF) << 8) | (bytes[i + 6] & 0xFF);
                        int w = ((bytes[i + 7] & 0xFF) << 8) | (bytes[i + 8] & 0xFF);
                        return new int[]{w, h};
                    }
                    if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                        i += 2; // 无数据段
                        continue;
                    }
                    if (i + 4 >= bytes.length) {
                        return null;
                    }
                    int segLen = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
                    if (segLen < 2) {
                        return null;
                    }
                    i += 2 + segLen;
                }
                return null;
            }
            // BMP：14 字节文件头 + 信息头；宽/高在偏移 18/22（小端）。负高度 = 自顶向下，取绝对值。
            if ((bytes[0] & 0xFF) == 0x42 && (bytes[1] & 0xFF) == 0x4D) {
                if (bytes.length < 26) {
                    return null;
                }
                int headerSize = (bytes[14] & 0xFF) | ((bytes[15] & 0xFF) << 8)
                        | ((bytes[16] & 0xFF) << 16) | ((bytes[17] & 0xFF) << 24);
                int w;
                int h;
                if (headerSize >= 40) {
                    // BITMAPINFOHEADER（32 位宽高）
                    w = (bytes[18] & 0xFF) | ((bytes[19] & 0xFF) << 8)
                            | ((bytes[20] & 0xFF) << 16) | ((bytes[21] & 0xFF) << 24);
                    h = (bytes[22] & 0xFF) | ((bytes[23] & 0xFF) << 8)
                            | ((bytes[24] & 0xFF) << 16) | ((bytes[25] & 0xFF) << 24);
                } else {
                    // BITMAPCOREHEADER（16 位宽高）
                    w = (bytes[18] & 0xFF) | ((bytes[19] & 0xFF) << 8);
                    h = (bytes[20] & 0xFF) | ((bytes[21] & 0xFF) << 8);
                }
                if (h < 0) {
                    h = -h;
                }
                return new int[]{w, h};
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /** 该世界存档的地图图片来源文件夹路径。 */
    public static Path pictureFolder(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(PICTURE_FOLDER);
    }

    /** 确保存档地图图片来源文件夹存在；不存在则自动创建（服务端启动时调用）。 */
    public static void ensurePictureFolder(ServerLevel level) {
        try {
            Files.createDirectories(pictureFolder(level));
        } catch (IOException ignored) {
            // 无法创建时忽略：后续扫描遇到不可读文件夹会当作无图片处理。
        }
    }

    /** 扫描 MBM_Picture 文件夹里的图片文件，返回 文件名 → 字节。文件夹不存在/不可读/超限的文件忽略。 */
    public static Map<String, byte[]> scanPictureFiles(ServerLevel level) {
        Map<String, byte[]> out = new HashMap<>();
        Path dir = pictureFolder(level);
        lastScanRejected = 0;
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".png") || name.endsWith(".jpg")
                                || name.endsWith(".jpeg") || name.endsWith(".bmp");
                    })
                    .forEach(path -> {
                        try {
                            byte[] bytes = Files.readAllBytes(path);
                            if (bytes.length == 0) {
                                return;
                            }
                            if (bytes.length > EscalatorSpeedData.MAX_PICTURE_BYTES) {
                                LOGGER.warn("[SmoothLift/Picture] 跳过 {}：{} 字节超过单张大小上限 {}",
                                        path.getFileName(), bytes.length, EscalatorSpeedData.MAX_PICTURE_BYTES);
                                lastScanRejected++;
                                return;
                            }
                            // 解码前用文件头探测尺寸，拒绝超限图片，防止客户端 NativeImage 解码时原生内存爆掉。
                            int[] dim = probeImageSize(bytes);
                            if (dim != null && (dim[0] > MAX_PICTURE_DIMENSION || dim[1] > MAX_PICTURE_DIMENSION)) {
                                LOGGER.warn("[SmoothLift/Picture] 跳过 {}：尺寸 {}x{} 超过单边上限 {}px",
                                        path.getFileName(), dim[0], dim[1], MAX_PICTURE_DIMENSION);
                                lastScanRejected++;
                                return;
                            }
                            out.put(path.getFileName().toString(), bytes);
                        } catch (IOException ignored) {
                            // 单个文件读失败就跳过，别中断其它文件。
                        }
                    });
        } catch (IOException ignored) {
            // 文件夹不存在或不可读时返回空映射。
        }
        return out;
    }

    /**
     * 从 MBM_Picture 文件夹把指定图片拷入存档图片库（上传来源 -> 融入存档）。
     * 内容可解码性由客户端纹理管线校验；服务端只做大小上限校验。
     *
     * @return {@code null} 表示导入成功；否则返回失败原因（可直接显示给玩家）
     */
    public static String importPictureToStore(ServerLevel level, String fileName) {
        byte[] bytes = scanPictureFiles(level).get(fileName);
        if (bytes == null) {
            return "存档文件夹 " + PICTURE_FOLDER + " 里没有这个文件";
        }
        EscalatorSpeedData data = getServerData(level);
        data.pictureLibrary.put(fileName, bytes);
        data.setDirty();
        LOGGER.info("[SmoothLift/Picture] 已导入 {}（{} 字节）到存档图片库", fileName, bytes.length);
        return null;
    }

    /** 从存档图片库删除一份图片（只删存档里的，**不动** MBM_Picture 文件夹里的文件）。 */
    public static void deletePictureFromStore(ServerLevel level, String fileName) {
        EscalatorSpeedData data = getServerData(level);
        data.removePicture(fileName);
        data.setDirty();
        LOGGER.info("[SmoothLift/Picture] 已从存档图片库删除 {}", fileName);
    }

    /** 服务端：已导入存档的图片名（/MBM picture delete 指令补全用）。 */
    public static Set<String> getServerPictureLibraryKeys(ServerLevel level) {
        return java.util.Collections.unmodifiableSet(
                new HashSet<>(getServerData(level).pictureLibrary.keySet()));
    }

    /** 设置当前显示图片（null = 库空，客户端显示白色+灰边占位）。 */
    public static void setPictureCurrent(ServerLevel level, String name) {
        EscalatorSpeedData data = getServerData(level);
        data.pictureCurrent = (name != null && data.pictureLibrary.containsKey(name)) ? name : null;
        data.setDirty();
    }

    /** 当前显示图片ID（null = 库空）。 */
    public static String getPictureCurrent(ServerLevel level) {
        return getServerData(level).pictureCurrent;
    }

    /** 图片库里字典序最大的一张图片ID；库空返回 null（删除当前图片后的回退选择）。 */
    public static String largestLibraryKey(ServerLevel level) {
        Set<String> keys = getServerData(level).pictureLibrary.keySet();
        if (keys.isEmpty()) {
            return null;
        }
        return java.util.Collections.max(keys);
    }

    /**
     * 【09-27 三次改版】把 {@code MBM_Audio/<legacyCategory>} 里已有的 {@code .ogg} 搬到
     * {@code MBM_Audio/<newCategory>}（幂等）。
     *
     * <p>安全约定：只动 {@code .ogg}；目标已存在同名文件时**跳过**（不覆盖玩家的东西）；
     * 源目录只有在**搬空之后**才尝试删除，里面还有别的文件就原样留着。任何异常都吞掉
     * （音频目录不该让服务端起不来）。
     */
    private static void migrateLegacyAudioFolder(ServerLevel level, String legacyCategory, String newCategory) {
        Path oldDir = audioFolder(level, legacyCategory);
        if (legacyCategory.equals(newCategory) || !Files.isDirectory(oldDir)) {
            return;
        }
        Path newDir = audioFolder(level, newCategory);
        try {
            Files.createDirectories(newDir);
            try (Stream<Path> paths = Files.list(oldDir)) {
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                        continue;
                    }
                    Path target = newDir.resolve(name);
                    if (Files.exists(target)) {
                        continue;   // 不覆盖
                    }
                    try {
                        Files.move(path, target);
                    } catch (IOException ignored) {
                        // 单个文件搬失败就留着，别中断其它文件。
                    }
                }
            }
            try (Stream<Path> rest = Files.list(oldDir)) {
                if (rest.findAny().isEmpty()) {
                    Files.deleteIfExists(oldDir);
                }
            }
        } catch (IOException ignored) {
            // 迁移失败按「老目录还在原地」处理，不影响启动。
        }
    }

    /** 扫描某个分类的子文件夹里的 .ogg 文件，返回 文件名 → 字节。文件夹不存在/不可读/超限的文件忽略。 */
    public static Map<String, byte[]> scanAudioFiles(ServerLevel level, String category) {
        Map<String, byte[]> out = new HashMap<>();
        Path dir = audioFolder(level, category);
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .forEach(path -> {
                        try {
                            byte[] bytes = Files.readAllBytes(path);
                            if (bytes.length > 0 && bytes.length <= EscalatorSpeedData.MAX_AUDIO_BYTES) {
                                out.put(path.getFileName().toString(), bytes);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
            // 子文件夹尚未创建或不可读：视作无可用来源。
        }
        return out;
    }

    /**
     * 【1.7 诊断】判断这段字节是不是 Minecraft 能解码的「Ogg Vorbis」，不是就返回中文原因。
     *
     * <p>客户端是用 stb_vorbis（{@code com.mojang.blaze3d.audio.OggAudioStream}）解码的，
     * 只有 **Ogg 容器 + Vorbis 编码** 能解。常见踩坑：
     * <ul>
     *   <li>MP3 直接改扩展名为 .ogg —— 容器都不是 Ogg，解码时抛
     *       {@code IOException("Failed to find Ogg header")}；</li>
     *   <li>在线转换器/新版工具导出成 Ogg <b>Opus</b> —— stb_vorbis 不认 Opus；</li>
     *   <li>Ogg FLAC / Ogg Speex —— 同样不认。</li>
     * </ul>
     * 这些文件在客户端一律只能「静音」（解码异常被捕获），玩家完全看不出原因，
     * 所以入库前先在这里拦下来并给出明确提示。
     *
     * @return {@code null} 表示是合法的 Ogg Vorbis；否则返回可直接显示给玩家的原因
     */
    public static String describeOggProblem(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "文件是空的或太小";
        }
        if (bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S') {
            int n = Math.min(bytes.length, 16);
            String head = new String(bytes, 0, n, StandardCharsets.ISO_8859_1);
            if (head.startsWith("ID3")) {
                return "内容其实是 MP3；请真正转成 Ogg Vorbis";
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
                return "内容是 MPEG 音频帧，不是 Ogg；请真正转成 Ogg Vorbis";
            }
            if (head.startsWith("fLaC")) {
                return "内容是 FLAC，不是 Ogg；请转成 Ogg Vorbis";
            }
            if (head.startsWith("RIFF")) {
                return "内容是 WAV，不是 Ogg；请转成 Ogg Vorbis";
            }
            return "不是 Ogg 容器";
        }
        // 容器对了，再确认编码是 Vorbis（stb_vorbis 不认 Opus / FLAC / Speex）
        String body = new String(bytes, 0, Math.min(bytes.length, 64 * 1024), StandardCharsets.ISO_8859_1);
        if (body.contains("OpusHead") || body.contains("OpusTags")) {
            return "是 Ogg Opus，MC 只支持 Ogg Vorbis；请用 Vorbis 编码重新导出";
        }
        if (!body.contains("vorbis")) {
            return "没有 Vorbis 编码头";
        }
        return null;
    }

    /**
     * 从存档音频来源文件夹的**某个分类子文件夹**把指定文件拷入存档音频库
     * （上传来源 -&gt; 融入存档）。入库前校验内容确实是 MC 能播的 Ogg Vorbis。
     *
     * <p>【1.28】名字同时记进该分类的注册表（{@code audioCategoryNames}）——
     * 「分开存放」：每个设置项的「已存入」列表只列自己分类导入过的音频。
     *
     * @return {@code null} 表示导入成功；否则返回失败原因（可直接显示给玩家）
     */
    public static String importAudioToStore(ServerLevel level, String category, String fileName) {
        byte[] bytes = scanAudioFiles(level, category).get(fileName);
        if (bytes == null) {
            return "存档文件夹 " + AUDIO_FOLDER + "/" + category + " 里没有这个文件";
        }
        String problem = describeOggProblem(bytes);
        if (problem != null) {
            LOGGER.warn("[SmoothLift/Audio] 拒绝导入 {}（{} 字节）：{}", fileName, bytes.length, problem);
            return problem;
        }
        EscalatorSpeedData data = getServerData(level);
        data.audioLibrary.put(fileName, bytes);
        data.audioCategoryNames.computeIfAbsent(category, k -> new HashSet<>()).add(fileName);
        data.setDirty();
        LOGGER.info("[SmoothLift/Audio] 已导入 {}（{} 字节）到存档音频库[分类 {}]", fileName, bytes.length, category);
        return null;
    }

    /** 客户端：某个分类的子文件夹里尚未入库、可"选中即导入并绑定"的 OGG 文件名；空返回空集合。 */
    public static Set<String> getClientFolderAudioKeys(Level level, String category) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return Set.of();
        }
        Set<String> folder = data.folderByCategory.getOrDefault(category, Set.of());
        Set<String> imported = data.audioCategoryNames.getOrDefault(category, Set.of());
        Set<String> out = new HashSet<>(folder);
        out.removeAll(imported);
        return java.util.Collections.unmodifiableSet(out);
    }

    /** 客户端：某个分类已导入存档的音频名（「已存入」列表）；空返回空集合。 */
    public static Set<String> getClientAudioLibraryKeys(Level level, String category) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return Set.of();
        }
        return java.util.Collections.unmodifiableSet(
                new HashSet<>(data.audioCategoryNames.getOrDefault(category, Set.of())));
    }

    /** 服务端：某个分类已导入存档的音频名（指令补全用）。 */
    public static Set<String> getServerAudioLibraryKeys(ServerLevel level, String category) {
        Set<String> names = getServerData(level).audioCategoryNames.get(category);
        return names == null ? Set.of() : java.util.Collections.unmodifiableSet(new HashSet<>(names));
    }

    /** 服务端：某个分类的「已导入」集合（为空时自动补一张空表，方便调用方直接 add）。 */
    public static Set<String> categoryAudioNames(EscalatorSpeedData data, String category) {
        return data.audioCategoryNames.computeIfAbsent(category, k -> new HashSet<>());
    }

    public static EscalatorSpeedData getServerData(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        // ★ 存档键带本模组前缀，避免与 mtr4backport（MTR-LMTE）的同名 SavedData 撞车：
        //   撞车时对方 EscalatorSpeedManager 会取到本模组实例并强转失败（ClassCastException）。
        EscalatorSpeedData data = storage.get(EscalatorSpeedData::fromTag, EscalatorSpeedData.DATA_NAME);
        if (data != null) {
            return data;
        }
        // 新键还没有 → 尝试从旧键（smoothlift_speeds）把老存档数据迁移过来。
        EscalatorSpeedData migrated = EscalatorSpeedData.readLegacy(storage);
        if (migrated != null) {
            LOGGER.info("[SmoothLift] 已把旧存档键 {} 的扶梯数据迁移到 {}",
                    EscalatorSpeedData.LEGACY_DATA_NAME, EscalatorSpeedData.DATA_NAME);
            data = migrated;
        } else {
            data = new EscalatorSpeedData();
        }
        storage.set(EscalatorSpeedData.DATA_NAME, data);
        return data;
    }

    /** 对 seed 所在的整条扶梯链设置运行速度，返回实际设置到几个方块。 */
    public static int setSpeed(ServerLevel level, BlockPos seed, double speed) {
        speed = EscalatorSpeedData.clamp(speed);
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            data.speeds.put(pos, speed);
            // 规则：设置运行速度时阶梯速度一起跟随 —— 清掉单独阶梯设置，
            // 没单独设置的阶梯速度本来就跟随运行速度。
            data.stepSpeeds.remove(pos);
            data.axeModified.remove(pos);
        }
        data.setDirty();
        return chain.size();
    }

    /**
     * 石斧设置界面按下 ESC 时一次性应用改动。两个改动合成一个包发过来，
     * 顺序固定、不会出现「先设阶梯又被清掉」的竞态。
     *
     * <ul>
     *   <li>{@code setRun=true}：把这条扶梯（整条链）的运行速度设为 run，
     *       并**清掉它的单独阶梯速度**，于是阶梯速度自动跟随新的运行速度
     *       —— 即「改扶梯速度，阶梯速度也会跟着一起调整」。</li>
     *   <li>{@code setStep=true}：把这条扶梯的阶梯速度单独设为 step，
     *       **完全不动运行速度** —— 即「改阶梯速度，扶梯速度不会跟着调整」。</li>
     * </ul>
     *
     * <p>两个都开时先写运行速度再写阶梯速度，所以最终阶梯速度以 setStep 的值为准。
     *
     * @return 受影响的扶梯方块数；0 表示这个位置没有扶梯
     */
    public static int applyChain(ServerLevel level, BlockPos seed, boolean setRun, double run,
                                 boolean setStep, double step) {
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        if (chain.isEmpty()) {
            return 0;
        }
        if (!setRun && !setStep) {
            return chain.size();
        }
        EscalatorSpeedData data = getServerData(level);
        if (setRun) {
            double value = EscalatorSpeedData.clamp(run);
            for (BlockPos pos : chain) {
                data.speeds.put(pos, value);
                // 阶梯速度跟随运行速度：清掉单独设置（没单独设置时本来就跟随运行速度）。
                data.stepSpeeds.remove(pos);
                data.axeModified.remove(pos);
            }
        }
        if (setStep) {
            double value = EscalatorSpeedData.clamp(step);
            for (BlockPos pos : chain) {
                data.stepSpeeds.put(pos, value);
                data.axeModified.add(pos);
            }
        }
        data.setDirty();
        return chain.size();
    }

    /** 石斧：给【这一条】扶梯单独设置阶梯动画速度（只影响这条扶梯）。 */
    public static int setStepSpeed(ServerLevel level, BlockPos seed, double step) {
        step = EscalatorSpeedData.clamp(step);
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            data.stepSpeeds.put(pos, step);
        }
        data.axeModified.addAll(chain);
        data.setDirty();
        return chain.size();
    }

    /** 石斧：对齐——【这一条】扶梯的阶梯动画速度 = 它自己的运行速度。 */
    public static int alignStepToRunning(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            Double run = data.speeds.get(pos);
            data.stepSpeeds.put(pos, run != null ? run : data.defaultSpeed);
        }
        data.axeModified.addAll(chain);
        data.setDirty();
        return chain.size();
    }

    /**
     * 石斧：清除【这一条】扶梯的单独阶梯动画设置，让它重新跟随维度默认阶梯动画速度。
     * 只改动画，不动运行速度。
     */
    public static int clearStepSpeed(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        int cleared = 0;
        for (BlockPos pos : chain) {
            boolean removed = data.stepSpeeds.remove(pos) != null;
            data.axeModified.remove(pos);
            if (removed) {
                cleared++;
            }
        }
        if (cleared > 0) {
            data.setDirty();
        }
        return chain.size();
    }

    // ------------------------------------------------------------------
    // 全局 / 批量指令
    //
    // 统一规则：**改运行速度 ⇒ 阶梯速度一起跟随；改阶梯速度 ⇒ 运行速度不动。**
    //
    // 数据模型：defaultSpeed = 全局扶梯速度；stepEnabled/stepValue = 全局阶梯速度
    //          （未开启时全局扶梯的阶梯速度 = 自己的运行速度）；
    //          speeds / stepSpeeds 里有记录的才是「被石斧单独改过」的扶梯。
    //
    // 因为 getAnimationSpeed 的优先级里「单独运行速度」高于「全局阶梯值」，
    // 改全局运行速度时要把 stepValue 镜像成新的 defaultSpeed（否则设了 /jietispeed
    // 之后阶梯速度不会跟着运行速度走）；反过来 -f 强制阶梯速度时，要给这些
    // 「只单独改过运行速度」的扶梯显式补一条阶梯设置，否则覆盖不到它们。
    //
    // 不带 -f 的指令只改「全局扶梯」：直接改全局值，并把那些
    //   **运行速度与阶梯速度都仍和全局一致**的、曾被改过的扶梯恢复成跟随全局
    //   （值不一致的扶梯原封不动）。
    // 带 -f 的指令强制改所有扶梯：改全局值并清掉所有单独设置。
    // ------------------------------------------------------------------

    /** 数值比较用的容差。 */
    public static final double EPSILON = 1.0E-6;

    public static boolean same(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    /** 全局扶梯的运行速度（= 维度默认运行速度）。 */
    public static double getGlobalRunSpeed(ServerLevel level) {
        return getServerData(level).defaultSpeed;
    }

    /** 全局扶梯的阶梯速度：/jietispeed 开着用维度默认值，关着就是跟随运行速度。 */
    public static double getGlobalStepSpeed(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        return data.stepEnabled ? data.stepValue : data.defaultSpeed;
    }

    /** /jietispeed X 设定的维度默认阶梯值（与是否开启无关）。 */
    public static double getStepValue(ServerLevel level) {
        return getServerData(level).stepValue;
    }

    /** 一条扶梯当前的运行速度（没有单独设置就是全局值）。 */
    private static double runSpeedOf(EscalatorSpeedData data, BlockPos pos) {
        Double v = data.speeds.get(pos);
        return v != null ? v : data.defaultSpeed;
    }

    /**
     * 一条扶梯当前的阶梯速度，优先级与 {@link #getAnimationSpeed} 一致：
     * 单独阶梯设置 &gt; 单独运行速度 &gt; 全局阶梯速度 &gt; 全局运行速度。
     */
    private static double stepSpeedOf(EscalatorSpeedData data, BlockPos pos) {
        Double step = data.stepSpeeds.get(pos);
        if (step != null) {
            return step;
        }
        Double run = data.speeds.get(pos);
        if (run != null) {
            return run;
        }
        return data.stepEnabled ? data.stepValue : data.defaultSpeed;
    }

    /**
     * 把「运行速度与阶梯速度都仍等于给定全局值」的、曾被单独改过的扶梯恢复为跟随全局
     * （删掉它们的单独记录）。有一条不一致就跳过 —— 这正是
     * 「不会修改扶梯速度或阶梯速度与全局不同的扶梯」。
     *
     * @return 恢复（记录被删除）的扶梯方块数
     */
    private static int releaseMatchingOverrides(EscalatorSpeedData data, double run, double step) {
        Set<BlockPos> candidates = new HashSet<>();
        candidates.addAll(data.speeds.keySet());
        candidates.addAll(data.stepSpeeds.keySet());
        candidates.addAll(data.axeModified);
        int released = 0;
        for (BlockPos pos : candidates) {
            // 注意：必须在改动全局值**之前**调用，这里读到的才是旧全局值。
            if (!same(runSpeedOf(data, pos), run) || !same(stepSpeedOf(data, pos), step)) {
                continue;
            }
            boolean changed = data.speeds.remove(pos) != null
                    | data.stepSpeeds.remove(pos) != null
                    | data.axeModified.remove(pos);
            if (changed) {
                released++;
            }
        }
        return released;
    }

    /**
     * /futispeed X：只改**全局扶梯**的运行速度。
     * 值仍与全局一致的（含曾被改过但值一致的）会自动回到全局并跟随新值；
     * 运行速度或阶梯速度与全局不同的扶梯原封不动。
     *
     * <p>规则：**设置扶梯速度时阶梯速度一起跟随** —— 全局阶梯值会被镜像成新的全局运行速度，
     * 所以全局扶梯的阶梯速度也会变成 X。
     *
     * @return 被恢复为跟随全局的扶梯方块数
     */
    public static int setGlobalRunSpeed(ServerLevel level, double speed) {
        EscalatorSpeedData data = getServerData(level);
        double oldRun = data.defaultSpeed;
        double oldStep = data.stepEnabled ? data.stepValue : data.defaultSpeed;
        // 必须先按旧全局值判定/释放，再改全局值。
        int released = releaseMatchingOverrides(data, oldRun, oldStep);
        data.defaultSpeed = EscalatorSpeedData.clamp(speed);
        // 阶梯速度跟随运行速度：全局阶梯值镜像到新的全局运行速度。
        data.stepValue = data.defaultSpeed;
        data.setDirty();
        return released;
    }

    /**
     * /futispeed -f X：强制所有扶梯运行速度 = X（不管有没有被改过）。
     * 规则：「设置扶梯速度时阶梯速度一起跟随」—— 清掉所有单独阶梯设置，
     * 并把全局阶梯值镜像成 X，于是**所有**扶梯的阶梯速度也都变成 X。
     *
     * @return 被清掉的单独记录数（运行速度 + 阶梯速度条目）
     */
    public static int forceGlobalRunSpeed(ServerLevel level, double speed) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.speeds.size() + data.stepSpeeds.size();
        data.defaultSpeed = EscalatorSpeedData.clamp(speed);
        // 阶梯速度一起跟随：全局阶梯值镜像到新的全局运行速度。
        data.stepValue = data.defaultSpeed;
        data.speeds.clear();
        data.stepSpeeds.clear();
        data.axeModified.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futispeed -f X to Y：把运行速度为 X 的**所有**扶梯（含被改过的）改成 Y；
     * 运行速度不为 X 的扶梯保持不变。
     * 被改到运行速度 Y 的扶梯，其阶梯速度也一起跟随变成 Y（规则：设运行速度 ⇒ 阶梯跟随）。
     *
     * @return 被改动的扶梯方块数
     */
    public static int forceRunFromTo(ServerLevel level, double from, double to) {
        EscalatorSpeedData data = getServerData(level);
        double target = EscalatorSpeedData.clamp(to);
        boolean dirty = false;
        if (same(data.defaultSpeed, from)) {
            data.defaultSpeed = target;
            // 阶梯速度一起跟随。
            data.stepValue = target;
            dirty = true;
        }
        int changed = 0;
        for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
            if (!same(entry.getValue(), from)) {
                continue;
            }
            entry.setValue(target);
            // 运行速度变了，清掉单独阶梯设置让阶梯速度跟随新的运行速度。
            data.stepSpeeds.remove(entry.getKey());
            data.axeModified.remove(entry.getKey());
            changed++;
        }
        if (dirty || changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * /jietispeed X：只改**全局扶梯**的阶梯速度（不动任何运行速度）。
     *
     * @return 被恢复为跟随全局的扶梯方块数
     */
    public static int setGlobalStepSpeed(ServerLevel level, double value) {
        EscalatorSpeedData data = getServerData(level);
        double oldRun = data.defaultSpeed;
        double oldStep = data.stepEnabled ? data.stepValue : data.defaultSpeed;
        int released = releaseMatchingOverrides(data, oldRun, oldStep);
        data.stepValue = EscalatorSpeedData.clamp(value);
        data.stepEnabled = true;
        data.setDirty();
        return released;
    }

    /**
     * /jietispeed -f X：强制所有扶梯的阶梯速度 = X（不管有没有被改过）。
     * **完全不动运行速度**。
     *
     * <p>注意：阶梯速度取值里「单独运行速度」优先于「全局阶梯值」，所以只设全局值
     * 盖不住那些被石斧单独改过运行速度的扶梯。这里额外给它们显式补一条阶梯设置，
     * 让 -f 真正覆盖**所有**扶梯。
     *
     * @return 被清掉的单独阶梯设置数
     */
    public static int forceGlobalStepSpeed(ServerLevel level, double value) {
        EscalatorSpeedData data = getServerData(level);
        double target = EscalatorSpeedData.clamp(value);
        int cleared = data.stepSpeeds.size() + data.axeModified.size();
        data.stepValue = target;
        data.stepEnabled = true;
        data.stepSpeeds.clear();
        data.axeModified.clear();
        // 运行速度被单独改过的扶梯：显式补阶梯设置 = X（只写阶梯，运行速度原封不动）。
        for (BlockPos pos : data.speeds.keySet()) {
            data.stepSpeeds.put(pos, target);
            data.axeModified.add(pos);
        }
        data.setDirty();
        return cleared + data.speeds.size();
    }

    /**
     * /jietispeed -f X to Y：把阶梯速度为 X 的**所有**扶梯（含被改过的）改成 Y，
     * 阶梯速度不为 X 的保持不变；**不动任何运行速度**。
     *
     * <p>「阶梯速度为 X」要按 {@link #getAnimationSpeed} 的优先级链逐一识别：
     * <ol>
     *   <li>单独阶梯设置 == X；</li>
     *   <li>没有单独阶梯设置、但单独运行速度 == X（阶梯跟随运行速度）；</li>
     *   <li>都没有时，全局阶梯值（开启时）或全局运行速度 == X。</li>
     * </ol>
     *
     * @return 被改动的扶梯方块数
     */
    public static int forceStepFromTo(ServerLevel level, double from, double to) {
        EscalatorSpeedData data = getServerData(level);
        double target = EscalatorSpeedData.clamp(to);
        boolean dirty = false;
        // 1) 全局阶梯值正好是 X：连维度默认一起改成 Y（覆盖未加载区块与以后放置的扶梯）。
        if (data.stepEnabled && same(data.stepValue, from)) {
            data.stepValue = target;
            dirty = true;
        }
        // 2) 单独设置过阶梯速度、且正好是 X 的：改成 Y。
        int changed = 0;
        for (Map.Entry<BlockPos, Double> entry : data.stepSpeeds.entrySet()) {
            if (same(entry.getValue(), from)) {
                entry.setValue(target);
                changed++;
            }
        }
        // 3) 只单独改过运行速度、没有单独阶梯设置的扶梯：阶梯速度跟随自己的运行速度
        //    （优先于全局值），所以运行速度 == X 的也要一并改成 Y。
        for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
            if (data.stepSpeeds.containsKey(entry.getKey()) || !same(entry.getValue(), from)) {
                continue;
            }
            data.stepSpeeds.put(entry.getKey(), target);
            data.axeModified.add(entry.getKey());
            changed++;
        }
        // 4) 全局阶梯值没开启时，未单独改过的扶梯阶梯速度 = 全局运行速度；
        //    全局运行速度正好是 X 的话，把全局阶梯值整体抬到 Y 并开启。
        if (!data.stepEnabled && same(data.defaultSpeed, from)) {
            data.stepValue = target;
            data.stepEnabled = true;
            dirty = true;
        }
        if (dirty || changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 枚举一个已加载区块里的所有扶梯方块。 */
    private static List<BlockPos> escalatorBlocksIn(LevelChunk chunk, Level level) {
        List<BlockPos> out = new ArrayList<>();
        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = minY + chunk.getHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                    if (EscalatorUtil.isEscalator(chunk.getBlockState(pos))) {
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    /** 通过访问器拿到服务端已加载区块。 */
    private static List<LevelChunk> lastChunks(ServerLevel level) {
        List<LevelChunk> out = new ArrayList<>();
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        if (cache.chunkMap == null) {
            return out;
        }
        for (ChunkHolder holder : ((ChunkMapAccessor) (Object) cache.chunkMap).smoothlift_getChunks()) {
            LevelChunk chunk = holder.getFullChunk();
            if (chunk != null) {
                out.add(chunk);
            }
        }
        return out;
    }

    /**
     * 清除某个扶梯方块上的全部记录（速度 / 阶梯速度 / 斧头标记 / 音量 / 音频绑定）。
     * 返回是否真的有记录被清除，供调用方决定要不要广播同步包。
     * <p>注意：音频绑定（blockAudio）也必须一并清除，否则拆掉已绑声音的扶梯后，
     * 那个坐标仍会在客户端继续播放，直到下一次音频同步才消失。
     */
    public static boolean removeSpeed(ServerLevel level, BlockPos pos) {
        EscalatorSpeedData data = getServerData(level);
        boolean removed = data.speeds.remove(pos) != null
                | data.stepSpeeds.remove(pos) != null
                | data.axeModified.remove(pos)
                | data.blockVolume.remove(pos) != null
                | data.blockHelp.remove(pos) != null
                | data.blockHelpVolume.remove(pos) != null
                | data.blockRound.remove(pos) != null
                | data.blockHelpRound.remove(pos) != null
                | data.blockHelpSpeedIn.remove(pos) != null
                | data.blockHelpSpeedOut.remove(pos) != null
                // 【1.41】提示音音乐的两套单独设置（顺便补上 1.39 漏掉的这一处：
                //   拆掉已单独设过提示音的扶梯时，旧坐标会一直留在存档里，并可能被后来的
                //   同步包带着走 —— 表现是「拆掉的那条扶梯还在响」）
                | data.blockHelpAudioIn.remove(pos) != null
                | data.blockHelpAudioOut.remove(pos) != null
                | data.blockAudio.remove(pos) != null;
        if (removed) {
            data.setDirty();
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 【1.30】「拆掉一部分再放回去」之后把整条链的单独设置补齐
    //
    // 症状：扶梯阶梯分成左右两半、两半的动画速度不一样，/futispeed 重写一遍才正常。
    // 两个成因都在这里补齐：
    //   ① EscalatorUtil.collectChain 可能只收半边（已单独修，见那边的【1.30】）；
    //   ② 被拆掉的那一段记录在破坏方块的回调里（removeSpeed）被清掉了，玩家再放回去时**没有任何东西**
    //      会把这段补回来 —— 于是新放的那段跟随全局、留着的那段还是单独设置，两段动画不同步。
    //      MTR 是自己在 ItemEscalator#useOnBlock 里 setBlockState 的，不触发 Forge / Fabric 的
    //      放置事件，所以这里改成「玩家用扶梯物品右键 → 下一个服务端刻去重扫一遍」。
    // ------------------------------------------------------------------

    /** 待重扫的坐标：维度 → 右键点过的位置。下一个服务端刻统一处理。 */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PENDING_CHAIN_RECONCILE = new HashMap<>();

    /** 找扶梯方块时扫描的半径（右键点在扶梯下方，实际方块落在附近几格内）。 */
    private static final int RECONCILE_RADIUS = 3;

    /**
     * 记下「玩家刚用扶梯物品右键了 around 附近」，下一个服务端刻再去看实际落了哪些方块。
     *
     * <p>调用方是各平台入口的右键钩子（它们手上只有「玩家点了哪一格」，而 MTR 的方块是这次
     * 右键**之后**才被放下去的，所以不能当场处理）。
     */
    public static void scheduleChainReconcile(Level level, BlockPos around) {
        if (level == null || around == null || level.isClientSide()) {
            return;
        }
        PENDING_CHAIN_RECONCILE
                .computeIfAbsent(level.dimension(), key -> new HashSet<>())
                .add(around.immutable());
    }

    /**
     * 每服务端刻调用：把上一刻记下的右键点重扫一遍，把整条扶梯的单独设置补齐。
     *
     * <p>只在真的补齐了东西时才广播同步包；已经一致时 {@link #reconcileChain} 返回 false，
     * 所以「连续右键延长扶梯」的常见操作不会反复发包。
     */
    public static void tickPendingReconcile(MinecraftServer server) {
        if (server == null || PENDING_CHAIN_RECONCILE.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ResourceKey<Level> key : new ArrayList<>(PENDING_CHAIN_RECONCILE.keySet())) {
            Set<BlockPos> pending = PENDING_CHAIN_RECONCILE.remove(key);
            ServerLevel level = server.getLevel(key);
            if (pending == null || level == null) {
                continue;
            }
            for (BlockPos around : pending) {
                BlockPos seed = findStepNear(level, around);
                if (seed != null && reconcileChain(level, seed)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            syncToAll(server);
        }
    }

    /** 在 around 附近（半径 {@link #RECONCILE_RADIUS} 的立方体，按由近到远）找第一个扶梯**阶梯**方块。 */
    private static BlockPos findStepNear(ServerLevel level, BlockPos around) {
        for (int ring = 0; ring <= RECONCILE_RADIUS; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy)) != ring) {
                            continue;
                        }
                        BlockPos pos = around.offset(dx, dy, dz);
                        if (EscalatorUtil.isEscalatorStep(level.getBlockState(pos))) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 把 seed 所在那条扶梯的「单独设置」补齐到整条链上：链上任意一格有单独设置，
     * 就让整条链都用它 —— 于是左右两半、以及刚放回去的那一段，动画速度完全一致。
     *
     * <p>**只搬运已经在链上的值，绝不发明新设置**：整条链都没有单独设置（纯跟随全局）时直接返回
     * false，不动任何数据 —— 所以没被石斧调过的扶梯不受影响。
     *
     * @return 是否有数据被改动（调用方据此决定要不要广播）
     */
    private static boolean reconcileChain(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        if (chain.isEmpty()) {
            return false;
        }
        Double repRun = null;
        Double repStep = null;
        for (BlockPos pos : chain) {
            if (repRun == null) {
                repRun = data.speeds.get(pos);
            }
            if (repStep == null) {
                repStep = data.stepSpeeds.get(pos);
            }
            if (repRun != null && repStep != null) {
                break;
            }
        }
        if (repRun == null && repStep == null) {
            return false;
        }
        boolean changed = false;
        for (BlockPos pos : chain) {
            changed |= putOrRemove(data.speeds, pos, repRun);
            changed |= putOrRemove(data.stepSpeeds, pos, repStep);
            // axeModified 决定 stepSpeeds 会不会被同步给客户端（见 filteredStepSpeeds），
            // 所以它必须与「这一格有没有单独阶梯设置」严格对应。
            if (repStep == null) {
                changed |= data.axeModified.remove(pos);
            } else {
                changed |= data.axeModified.add(pos);
            }
        }
        if (changed) {
            data.setDirty();
        }
        return changed;
    }

    /** 把 map 里 pos 的值设成 value；value 为 null 表示删掉。返回是否真的改动了。 */
    private static boolean putOrRemove(Map<BlockPos, Double> map, BlockPos pos, Double value) {
        if (value == null) {
            return map.remove(pos) != null;
        }
        Double old = map.put(pos, value);
        return old == null || !same(old, value);
    }

    public static void syncToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SLNet.sendToPlayer(player, SmoothLift.SYNC_CHANNEL, buildSyncPacket(server));
        }
    }

    // ------------------------------------------------------------------
    // 【1.7】自定义扶梯声音：网络处理
    // ------------------------------------------------------------------

    /** 音频传输分块大小。MC 网络包上限 32767 字节，这里留足协议头余量。 */
    public static final int AUDIO_CHUNK_SIZE = 16 * 1024;

    /** 把音频绑定到扶梯方块（音频必须已在**扶梯底噪分类**入库，或是内置音频）。返回是否绑定成功。 */
    public static boolean bindAudio(ServerLevel level, BlockPos pos, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        // 【09-27】「不播」哨兵专门放行：它不是一个文件（音频库里必然找不到），
        //   但它是合法取值 —— 含义 = 这一条扶梯静音、压过维度默认层。
        if (audioId == null
                || (!EscalatorSpeedData.FUTI_AUDIO_OFF.equals(audioId)
                        && !isBuiltinAudio(audioId)
                        && !categoryAudioNames(data, CAT_FUTI).contains(audioId))) {
            return false;
        }
        data.bindAudio(pos, audioId);
        if (!data.hasAudio(pos)) {
            return false;
        }
        data.setDirty();
        return true;
    }

    /** 解绑扶梯方块的音频（之后该扶梯静音）。返回是否真的有绑定被解除。 */
    public static boolean unbindAudio(ServerLevel level, BlockPos pos) {
        EscalatorSpeedData data = getServerData(level);
        if (!data.hasAudio(pos)) {
            return false;
        }
        data.unbindAudio(pos);
        data.setDirty();
        return true;
    }

    /** 从存档删除一段音频，同时解绑所有引用它的扶梯。返回是否真的删除了。 */
    /**
     * 【1.28】从**某个分类**删除一段已导入的音频。
     *
     * <p>只把这个名字从该分类的注册表摘掉（界面「已存入」列表立刻不显示）；
     * 只有当名字不再属于**任何**分类时，才真正从音频库删字节 + 清理所有引用
     * （{@link EscalatorSpeedData#removeAudio} 会把引用它的扶梯/提示音/直梯/屏蔽门
     * 一并退化成默认或「不播」）。同名音频在别的分类还有一份时，字节与引用保留。
     *
     * @return 分类注册表里确实有这个名字才 true
     */
    public static boolean deleteAudio(ServerLevel level, String category, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        Set<String> names = data.audioCategoryNames.get(category);
        if (names == null || !names.remove(audioId)) {
            return false;
        }
        boolean stillReferenced = false;
        for (Set<String> other : data.audioCategoryNames.values()) {
            if (other.contains(audioId)) {
                stillReferenced = true;
                break;
            }
        }
        if (!stillReferenced) {
            data.removeAudio(audioId);
        }
        data.setDirty();
        return true;
    }

    // ------------------------------------------------------------------
    // 【1.11】默认扶梯音频（/futimusic）
    //
    // 数据模型和速度完全对称：
    //   defaultAudio = 「全局/默认」层 —— 没单独绑定音频的扶梯都用它；
    //   blockAudio   = 「被单独设置过」的扶梯（石斧界面绑定的）。
    //
    // 不带 -f 的指令只改**默认层**（已单独绑定音频的扶梯原封不动）；
    // 带 -f 的指令改**所有**扶梯（含单独绑定的）。
    // ------------------------------------------------------------------

    /** 【1.11】默认扶梯音频 ID；【六改】没设置返回**内置底噪**（不再返回 null，见 {@link #normaliseDefaultAudio}）。 */
    public static String getDefaultAudio(ServerLevel level) {
        return normaliseDefaultAudio(getServerData(level).defaultAudio);
    }

    /**
     * 【1.11】/futimusic &lt;名字&gt;：只改**默认**扶梯音频。
     * 已经单独绑定过音频的扶梯不受影响（这正是「已经添加了其他音乐的扶梯除外」）。
     */
    public static void setDefaultAudio(ServerLevel level, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultAudio = audioId;
        data.setDirty();
    }

    /**
     * 【1.11】/futimusic -f &lt;名字&gt;：强制**所有**扶梯都用这个音频。
     * 做法是设默认值 + 清掉所有单独绑定（清掉比把每一格都写成同一个值更省存档）。
     *
     * @return 被清掉的单独绑定数
     */
    public static int forceDefaultAudio(ServerLevel level, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockAudio.size();
        data.defaultAudio = audioId;
        data.blockAudio.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * 【1.11】/futimusic &lt;X&gt; to &lt;Y&gt;：默认音频正好是 X 时才改成 Y；
     * 已经单独绑定音频的扶梯一概不动（音频不是「默认的 X」的就不变）。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultAudio(ServerLevel level, String from, String to) {
        EscalatorSpeedData data = getServerData(level);
        if (from == null || !from.equals(data.defaultAudio)) {
            return false;
        }
        data.defaultAudio = to;
        data.setDirty();
        return true;
    }

    /**
     * 【1.11】/futimusic -f &lt;X&gt; to &lt;Y&gt;：把**所有**音频为 X 的扶梯（含单独绑定的）改成 Y。
     *
     * @return 被改动的扶梯数（默认层算 1 条）
     */
    public static int forceReplaceAudioFromTo(ServerLevel level, String from, String to) {
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        if (from != null && from.equals(data.defaultAudio)) {
            data.defaultAudio = to;
            changed++;
        }
        if (to == null) {
            changed += data.blockAudio.size();
            data.blockAudio.clear();
        } else {
            for (Map.Entry<BlockPos, String> entry : data.blockAudio.entrySet()) {
                if (from != null && from.equals(entry.getValue())) {
                    entry.setValue(to);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音「音乐」（/futihelpmusic in|out）
    //
    // 与 /futimusic 的运行底噪**完全对称**的第二套音频绑定，但有三处不同：
    //   ① 共用同一份 audioLibrary（同一个导入文件夹，导入一次两边都能选）；
    //   ② `default` 的含义不同 —— 这里是「模组原来的提示音」（五档素材 + 速率分档），
    //      不是内置运行底噪 subway_escalator；
    //   ③ 多一个 HELP_AUDIO_OFF：可以把**某一条扶梯的这一头**（或整个默认层）单独设成不播提示音。
    // ★【1.41】「进入扶梯（上客端）」与「离开扶梯（落客端）」是**两套独立数据**：
    //   下面每个方法都带一个 `in` 参数（true = 上客端），形状与 /futihelpspeed 的 in|out 完全一致，
    //   指令也照它写成 `/futihelpmusic in|out <名字>`（详见 SmoothLift 里的注册段）。
    // ------------------------------------------------------------------

    /** 【1.41】默认提示音音乐 ID（永远非 null，初始 = {@code default} = 模组原来的提示音）。 */
    public static String getDefaultHelpAudio(ServerLevel level, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        return normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
    }

    /** 【1.41】/futihelpmusic in|out &lt;名字&gt;：只改**默认**层（已单独设置过的扶梯不变）。 */
    public static void setDefaultHelpAudio(ServerLevel level, String audioId, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        String id = normaliseHelpAudio(audioId);
        if (in) {
            data.defaultHelpAudioIn = id;
        } else {
            data.defaultHelpAudioOut = id;
        }
        data.setDirty();
    }

    /**
     * 【1.41】/futihelpmusic -f in|out &lt;名字&gt;：设默认值 + 清掉**这一头**的所有单独设置。
     *
     * <p>注意只清 `in`（或只清 `out`）那一张表：另一头的单独设置原地不动
     * —— 与 /futihelpspeed -f in|out 的语义完全一致。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelpAudio(ServerLevel level, String audioId, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        Map<BlockPos, String> overrides = data.helpAudioOverrides(in);
        int cleared = overrides.size();
        String id = normaliseHelpAudio(audioId);
        if (in) {
            data.defaultHelpAudioIn = id;
        } else {
            data.defaultHelpAudioOut = id;
        }
        overrides.clear();
        data.setDirty();
        return cleared;
    }

    /** 【1.41】/futihelpmusic in|out &lt;X&gt; to &lt;Y&gt;：默认层正好是 X 时才改成 Y。@return 是否真的改了 */
    public static boolean replaceDefaultHelpAudio(ServerLevel level, String from, String to, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        String current = normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
        if (from == null || !from.equals(current)) {
            return false;
        }
        String id = normaliseHelpAudio(to);
        if (in) {
            data.defaultHelpAudioIn = id;
        } else {
            data.defaultHelpAudioOut = id;
        }
        data.setDirty();
        return true;
    }

    /**
     * 【1.41】/futihelpmusic -f in|out &lt;X&gt; to &lt;Y&gt;：把这一头音乐为 X 的扶梯（含单独设置的）改成 Y。
     *
     * @return 被改动的扶梯数（默认层算 1 条）
     */
    public static int forceReplaceHelpAudioFromTo(ServerLevel level, String from, String to, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        String id = normaliseHelpAudio(to);
        String current = normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
        if (from != null && from.equals(current)) {
            if (in) {
                data.defaultHelpAudioIn = id;
            } else {
                data.defaultHelpAudioOut = id;
            }
            changed++;
        }
        for (Map.Entry<BlockPos, String> entry : data.helpAudioOverrides(in).entrySet()) {
            if (from != null && from.equals(entry.getValue())) {
                entry.setValue(id);
                changed++;
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * 【1.39】命令行「提示音音乐」名字 → ID。
     *
     * <ul>
     *   <li>{@code default} -&gt; 模组原来的提示音（{@link EscalatorSpeedData#HELP_AUDIO_DEFAULT}）；</li>
     *   <li>{@code off} / {@code none} -&gt; 不播提示音（{@link EscalatorSpeedData#HELP_AUDIO_OFF}，off=true）；</li>
     *   <li>其他 -&gt; 存档音频库里同名的文件（找不到时再试「名字 + .ogg」）。</li>
     * </ul>
     *
     * <p>★ **不接受内置运行底噪**（{@code builtin:...}）：那是整条扶梯 23 秒的环境音，
     * 而提示音要的是端头短促循环的定位音；「模组自带的那一个」已经被 {@code default} 占用，
     * 再允许 builtin 只会让两个 default 的语义打架。
     */
    public static AudioArg resolveHelpAudioName(ServerLevel level, String category, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "提示音名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("default".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.HELP_AUDIO_DEFAULT, false, null);
        }
        if ("off".equals(lower) || "none".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.HELP_AUDIO_OFF, true, null);
        }
        if (isBuiltinAudio(name)) {
            return new AudioArg(null, false, "无障碍提示音不能用内置运行底噪；"
                    + "这里请用 default或自己导入的文件名");
        }
        EscalatorSpeedData data = getServerData(level);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return new AudioArg(name, false, null);
        }
        if (!lower.endsWith(".ogg") && catNames.contains(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "「" + category + "」分类里没有叫「" + name + "」的音频"
                + (catNames.isEmpty()
                        ? "（这个分类还没有导入过音频）"
                        : "（已有：" + previewNames(data, category) + "）"));
    }

    /**
     * 【1.41】这条扶梯**实际生效**的提示音音乐 ID：单独设置 &gt; 维度默认。**永远非 null**
     * （没设过就是 {@code default} = 模组原来的提示音）。
     *
     * <p>{@code in} 为 true = **进入扶梯（上客端）**那一头，false = 离开扶梯（落客端）。
     */
    public static String effectiveHelpAudioId(Level level, BlockPos pos, boolean in) {
        if (pos == null) {
            return EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        }
        String own = findChainHelpAudio(level, pos, in);
        if (own != null) {
            return own;
        }
        if (level.isClientSide()) {
            return getClientDefaultHelpAudio(level.dimension(), in);
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
    }

    /** 【1.41】这条扶梯**这一头**是否被**单独设置**过提示音音乐（顺扶梯链找）。 */
    public static boolean hasIndividualHelpAudio(Level level, BlockPos pos, boolean in) {
        return pos != null && findChainHelpAudio(level, pos, in) != null;
    }

    /** 【1.41】界面用：这条扶梯单独设置的提示音音乐 ID（顺链找）；没设过返回 null（= 跟随默认）。 */
    public static String getHelpAudioForScreen(Level level, BlockPos pos, boolean in) {
        return findChainHelpAudio(level, pos, in);
    }

    /** 顺扶梯链找单独设置：自己这块优先，其次链上其它方块；整条链都没有返回 null（用维度默认）。 */
    private static String findChainHelpAudio(Level level, BlockPos pos, boolean in) {
        Map<BlockPos, String> overrides = helpAudioOverrides(level, in);
        if (overrides.isEmpty()) {
            return null;
        }
        String own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            String value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 【1.41】当前维度的「单独设置」表（客户端读镜像、服务端读 SavedData）。
     * {@code in} 为 true = 进入扶梯（上客端）那一头。
     */
    private static Map<BlockPos, String> helpAudioOverrides(Level level, boolean in) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return Map.of();
            }
            return in ? data.blockHelpAudioIn : data.blockHelpAudioOut;
        }
        return getServerData((ServerLevel) level).helpAudioOverrides(in);
    }

    /**
     * 【1.41】界面：把提示音音乐绑定到这条扶梯的**某一头**。
     *
     * <p>与 {@link #bindAudio} 不同，这里**先清掉整条链上的旧记录、再只记玩家点的那一块**
     * （同 {@link #setHelp} 的做法），保证「一条扶梯每一头最多一条记录」，
     * 免得链上多块各说各话、界面上来回跳。
     *
     * <p>★ 只动 {@code in}（或只动 {@code out}）那一张表：另一头的单独设置原地不动
     * —— 这正是「进 / 出各设各的」的关键。
     */
    public static boolean bindHelpAudio(ServerLevel level, BlockPos pos, String audioId, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        String id = normaliseHelpAudio(audioId);
        if (!EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(id)
                && !EscalatorSpeedData.HELP_AUDIO_OFF.equals(id)
                && !categoryAudioNames(data, CAT_HELP).contains(id)) {
            return false;
        }
        Map<BlockPos, String> overrides = data.helpAudioOverrides(in);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            overrides.remove(p);
        }
        overrides.remove(pos);
        // 与默认层相同就不必记：省存档，界面上也会老老实实显示成「使用默认」。
        String defaultId = normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
        if (!id.equals(defaultId)) {
            data.bindHelpAudio(pos, id, in);
        }
        // 【六改】用户点名「选择音乐就默认开启，选择不播就默认关闭」（那只开关按钮已按其点名删掉）
        //   ⇒ 绑一段**会出声的**提示音（含「默认提示音」）时，顺手把这条扶梯的无障碍提示音打开。
        //   否则：这一条（或本维度）曾被关过的话，玩家选完仍是静音，而界面上已经没有开关
        //   可以把状态拨回来（用户实报「选择扶梯默认提示音没有声音了」）。
        //   ★ 只做「开」、不顺手做「关」：让某一头静音由 HELP_AUDIO_OFF（不播）表达，
        //     而它只作用于那一头，不会把另一头一起哑掉 —— 1.41 的「进 / 出各设各的」不能破。
        if (!EscalatorSpeedData.HELP_AUDIO_OFF.equals(id)) {
            setHelp(level, pos, true);
        }
        data.setDirty();
        return true;
    }

    /** 【1.41】清掉这条扶梯**这一头**的提示音音乐单独设置（回到维度默认）。@return 是否真的有记录被清掉 */
    public static boolean unbindHelpAudio(ServerLevel level, BlockPos pos, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        Map<BlockPos, String> overrides = data.helpAudioOverrides(in);
        boolean removed = false;
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            removed |= overrides.remove(p) != null;
        }
        removed |= overrides.remove(pos) != null;
        if (removed) {
            data.setDirty();
        }
        return removed;
    }

    /**
     * 【1.11】命令行音频名字 → 绑定 ID。
     *
     * <ul>
     *   <li>{@code default} -&gt; 模组内置音频（目前只有 1 段）；</li>
     *   <li>{@code off} / {@code none} -&gt; {@code off=true}（清除默认音频）；</li>
     *   <li>其他 -&gt; 存档音频库里同名的文件（找不到时再试「名字 + .ogg」）。</li>
     * </ul>
     *
     * @param id    解析出的绑定 ID（{@code off} 时为 null）
     * @param off   是否是「关闭默认音频」
     * @param error 解析失败的原因（可直接显示给玩家）；成功时为 null
     */
    public record AudioArg(String id, boolean off, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 【1.28】按分类解析扶梯运行底噪（/futimusic）的素材名：default / off / none / builtin:… / 本分类导入的 .ogg。 */
    public static AudioArg resolveAudioName(ServerLevel level, String category, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "音频名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("default".equals(lower)) {
            return new AudioArg(builtinAudioIds().get(0), false, null);
        }
        if ("off".equals(lower) || "none".equals(lower)) {
            return new AudioArg(null, true, null);
        }
        if (isBuiltinAudio(name)) {
            return new AudioArg(name, false, null);
        }
        EscalatorSpeedData data = getServerData(level);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return new AudioArg(name, false, null);
        }
        // 玩家少打后缀名时兜底
        if (!lower.endsWith(".ogg") && catNames.contains(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "「" + category + "」分类里没有叫「" + name + "」的音频"
                + (catNames.isEmpty()
                        ? "（这个分类还没有导入过音频）"
                        : "（已有：" + previewNames(data, category) + "）"));
    }

    /** 列某个分类里几个已有音频名，拼进「找不到音频」的提示里。 */
    private static String previewNames(EscalatorSpeedData data, String category) {
        List<String> names = new ArrayList<>(categoryAudioNames(data, category));
        names.sort(String::compareTo);
        boolean more = names.size() > 6;
        if (more) {
            names = names.subList(0, 6);
        }
        return String.join("、", names) + (more ? " 等" : "");
    }

    /** 【1.11】某条扶梯**实际使用**的音频 ID：链上任意方块单独绑定过就用它，否则用默认音频。 */
    public static String effectiveAudioId(Level level, BlockPos pos) {
        if (pos == null) {
            return null;
        }
        String own = findIndividualAudio(level, pos);
        if (own != null) {
            return own;
        }
        if (level.isClientSide()) {
            return getClientDefaultAudio(level.dimension());
        }
        return getDefaultAudio((ServerLevel) level);
    }

    /** 【1.11】这条扶梯是否有单独绑定的音频（链上任意方块有绑定就算）。 */
    public static boolean hasIndividualAudio(Level level, BlockPos pos) {
        return pos != null && findIndividualAudio(level, pos) != null;
    }

    /** 在「这条扶梯」的整条链上找单独绑定的音频 ID；没有返回 null。 */
    private static String findIndividualAudio(Level level, BlockPos pos) {
        String own = getBlockAudioId(level, pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            String id = getBlockAudioId(level, p);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /**
     * 【1.11】服务端：玩家「当前所在的扶梯」，供 /futispeed、/jietispeed、/futimusic 显示用。
     * 优先级与客户端动画驱动一致：脚下/身上 → 准星指向（64 格）→ 附近最近（16 格）。
     * 找不到返回 null（此时指令显示全局默认值）。
     */
    public static BlockPos currentEscalator(ServerPlayer player) {
        ServerLevel level = player.getLevel();
        BlockPos feet = player.blockPosition();
        if (EscalatorUtil.isEscalator(level.getBlockState(feet))) {
            return feet;
        }
        if (EscalatorUtil.isEscalator(level.getBlockState(feet.below()))) {
            return feet.below();
        }
        if (EscalatorUtil.isEscalator(level.getBlockState(feet.above()))) {
            return feet.above();
        }
        HitResult hit = player.pick(64.0, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = blockHit.getBlockPos();
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos candidate = hitPos.offset(0, dy, 0);
                if (EscalatorUtil.isEscalator(level.getBlockState(candidate))) {
                    return candidate;
                }
            }
        }
        // 附近最近（指令调用频率极低，直接三重循环足够，不必做球壳优化）
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -NEARBY_SEARCH; dx <= NEARBY_SEARCH; dx++) {
            for (int dy = -NEARBY_SEARCH; dy <= NEARBY_SEARCH; dy++) {
                for (int dz = -NEARBY_SEARCH; dz <= NEARBY_SEARCH; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!EscalatorUtil.isEscalator(level.getBlockState(candidate))) {
                        continue;
                    }
                    double dist = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** 「当前扶梯」兜底搜索半径（格）。 */
    private static final int NEARBY_SEARCH = 16;

    /** 构建某维度的完整音频同步负载（音频库 + 来源文件夹名单 + 扶梯-音频绑定）。 */
    private static byte[] buildAudioSyncPayload(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        // ① 平铺音频库（名字 → 字节，播放端按名字取）
        buf.writeVarInt(data.audioLibrary.size());
        for (Map.Entry<String, byte[]> entry : data.audioLibrary.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeByteArray(entry.getValue());
        }
        // ②【1.28】分类区：每分类一段（子文件夹待导入名单 + 已导入注册表名单）
        //    格式：分类数 → (分类名 → 待导入数 → 名字 ×N → 已导入数 → 名字 ×N) × 分类数
        buf.writeVarInt(ALL_CATEGORIES.length);
        for (String category : ALL_CATEGORIES) {
            buf.writeUtf(category, 64);
            Map<String, byte[]> folder = scanAudioFiles(level, category);
            buf.writeVarInt(folder.size());
            for (String name : folder.keySet()) {
                buf.writeUtf(name, 128);
            }
            Set<String> imported = data.audioCategoryNames.getOrDefault(category, Set.of());
            buf.writeVarInt(imported.size());
            for (String name : imported) {
                buf.writeUtf(name, 128);
            }
        }
        buf.writeVarInt(data.blockAudio.size());
        for (Map.Entry<BlockPos, String> entry : data.blockAudio.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
        // 【1.11】默认扶梯音频（空串表示没有默认音频）
        buf.writeUtf(data.defaultAudio == null ? "" : data.defaultAudio, 128);
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return payload;
    }

    /** 把一个维度的音频数据分块发给单个玩家。 */
    public static void sendAudioSyncTo(ServerPlayer player, ServerLevel level) {
        byte[] payload = buildAudioSyncPayload(level);
        String dimId = level.dimension().location().toString();
        int totalChunks = Math.max(1, (payload.length + AUDIO_CHUNK_SIZE - 1) / AUDIO_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * AUDIO_CHUNK_SIZE;
            int len = Math.min(AUDIO_CHUNK_SIZE, payload.length - from);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, from, chunk, 0, len);
            FriendlyByteBuf buf = SLNet.buf();
            buf.writeUtf(dimId, 256);
            buf.writeVarInt(totalChunks);
            buf.writeVarInt(i);
            buf.writeByteArray(chunk);
            SLNet.sendToPlayer(player, SmoothLift.AUDIO_SYNC_CHANNEL, buf);
        }
    }

    /** 把全部维度的音频数据分块同步给所有在线玩家。 */
    public static void syncAudioToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendAudioSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.18.1204】地图图片：分块同步（与音频同步同一个模式）
    // ------------------------------------------------------------------

    /** 地图图片同步分块大小（与音频一致，留足协议头余量）。 */
    public static final int PICTURE_CHUNK_SIZE = AUDIO_CHUNK_SIZE;

    /**
     * 构建**合并全部维度**的图片同步负载（文件名 → 原始字节；客户端自行裁切/缩放/加灰边后写图集）。
     *
     * <p>图片库是按维度（主世界/末地/下界）各存一份的，若逐维度发送原始负载，
     * 空维度（末地/下界）会把主世界刚应用的图片覆盖成空白 —— 这是「重进存档图片丢失、
     * 需要重新导入」的根因。合并后任何一轮同步都携带完整图片库，空维度不再清空客户端图集。
     */
    private static byte[] buildMergedPictureSyncPayload(MinecraftServer server) {
        Map<String, byte[]> merged = new LinkedHashMap<>();
        String current = null;
        for (ServerLevel lv : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(lv);
            for (Map.Entry<String, byte[]> entry : data.pictureLibrary.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
            // 当前显示图片：取第一个「有图且确实存在于合并库中」的维度（空维度跳过）。
            if (current == null && data.pictureCurrent != null
                    && data.pictureLibrary.containsKey(data.pictureCurrent)) {
                current = data.pictureCurrent;
            }
        }
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeVarInt(merged.size());
        for (Map.Entry<String, byte[]> entry : merged.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            buf.writeByteArray(entry.getValue());
        }
        // 当前显示图片（空串 = 库空，客户端显示白色+灰边占位）。
        buf.writeUtf(current == null ? "" : current, 256);
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return payload;
    }

    /** 把合并后的图片数据（覆盖全部维度）分块发给单个玩家。 */
    public static void sendPictureSyncTo(ServerPlayer player, ServerLevel level) {
        byte[] payload = buildMergedPictureSyncPayload(level.getServer());
        String dimId = level.dimension().location().toString();
        int totalChunks = Math.max(1, (payload.length + PICTURE_CHUNK_SIZE - 1) / PICTURE_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * PICTURE_CHUNK_SIZE;
            int len = Math.min(PICTURE_CHUNK_SIZE, payload.length - from);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, from, chunk, 0, len);
            FriendlyByteBuf buf = SLNet.buf();
            buf.writeUtf(dimId, 256);
            buf.writeVarInt(totalChunks);
            buf.writeVarInt(i);
            buf.writeByteArray(chunk);
            SLNet.sendToPlayer(player, SmoothLift.PICTURE_SYNC_CHANNEL, buf);
        }
    }

    /** 把合并后的图片数据（覆盖全部维度）分块同步给所有在线玩家。每人一轮即可，无需逐维度。 */
    public static void syncPictureToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendPictureSyncTo(player, player.getLevel());
        }
    }

    /**
     * 查询「合并视图」的当前显示图片名（空维度跳过，与 {@link #buildMergedPictureSyncPayload} 同规则）。
     *
     * <p>返回 {@code null} 表示当前没有任何一张图片在显示（客户端图集是白色+灰边占位）。
     */
    public static String getMergedPictureCurrent(MinecraftServer server) {
        for (ServerLevel lv : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(lv);
            if (data.pictureCurrent != null && data.pictureLibrary.containsKey(data.pictureCurrent)) {
                return data.pictureCurrent;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 【1.9】扶梯声音音量：同步（单独一个小包，避免为了改音量重发整个音频库）
    // ------------------------------------------------------------------

    /** 构建某个维度的「方块 → 音量」同步包（【1.12】含默认音量）。 */
    private static FriendlyByteBuf buildVolumePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.defaultVolume);
        buf.writeVarInt(data.blockVolume.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockVolume.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        return buf;
    }

    /** 把一个维度的扶梯音量表发给单个玩家。 */
    public static void sendVolumeSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.VOLUME_SYNC_CHANNEL, buildVolumePacket(level));
    }

    /** 把全部维度的扶梯音量表同步给所有在线玩家。 */
    public static void syncVolumeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendVolumeSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.16】无障碍提示音开关：同步（和音量一样单独一个小包，开关数据极小）
    // ------------------------------------------------------------------

    /** 构建某个维度的「提示音开关」同步包（含维度默认开关）。 */
    private static FriendlyByteBuf buildHelpPacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeBoolean(data.defaultHelp);
        buf.writeVarInt(data.blockHelp.size());
        for (Map.Entry<BlockPos, Boolean> entry : data.blockHelp.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBoolean(entry.getValue());
        }
        return buf;
    }

    /** 把一个维度的提示音开关表发给单个玩家。 */
    public static void sendHelpSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.HELP_SYNC_CHANNEL, buildHelpPacket(level));
    }

    /** 把全部维度的提示音开关表同步给所有在线玩家。 */
    public static void syncHelpToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.18】无障碍提示音音量：同步（同样单独一个小包）
    // ------------------------------------------------------------------

    /** 构建某个维度的「提示音音量」同步包（含维度默认音量）。 */
    private static FriendlyByteBuf buildHelpVolumePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.defaultHelpVolume);
        buf.writeVarInt(data.blockHelpVolume.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpVolume.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        return buf;
    }

    /** 把一个维度的提示音音量表发给单个玩家。 */
    public static void sendHelpVolumeSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.HELP_VOLUME_SYNC_CHANNEL, buildHelpVolumePacket(level));
    }

    /** 把全部维度的提示音音量表同步给所有在线玩家。 */
    public static void syncHelpVolumeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpVolumeSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.24】两个「淡入淡出范围」：同步（同样各一个小包）
    //   ★ 1.24 没有界面控件，所以只有 S→C 的 SYNC，没有 C→S 的 SET。
    // ------------------------------------------------------------------

    /** 构建某个维度的「底噪范围」同步包（含维度默认范围）。 */
    /**
     * 构建某个维度的「底噪范围」同步包（含维度默认范围）。
     *
     * <p>★【10-03】写序 = 维度 id → 水平默认 → 水平条数 → 水平 (pos,值)×N →
     * 垂直默认 → 垂直条数 → 垂直 (pos,值)×N。客户端 {@code ROUND_SYNC_CHANNEL} 接收器
     * 与 {@link #applyClientRounds} 的入参顺序都必须**逐字对齐**这一串。
     */
    private static FriendlyByteBuf buildRoundPacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.defaultRound);
        buf.writeVarInt(data.blockRound.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockRound.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(data.defaultRoundY);
        buf.writeVarInt(data.blockRoundY.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockRoundY.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        return buf;
    }

    /** 把一个维度的底噪范围表发给单个玩家。 */
    public static void sendRoundSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.ROUND_SYNC_CHANNEL, buildRoundPacket(level));
    }

    /** 把全部维度的底噪范围表同步给所有在线玩家。 */
    public static void syncRoundToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendRoundSyncTo(player, level);
            }
        }
    }

    /** 构建某个维度的「提示音范围」同步包（含维度默认范围）。 */
    /**
     * 构建某个维度的「提示音范围」同步包（含维度默认范围）。
     * ★【10-03】写序与 {@link #buildRoundPacket} 同构：水平默认/表、再垂直默认/表；读侧必须对齐。
     */
    private static FriendlyByteBuf buildHelpRoundPacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.defaultHelpRound);
        buf.writeVarInt(data.blockHelpRound.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpRound.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(data.defaultHelpRoundY);
        buf.writeVarInt(data.blockHelpRoundY.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpRoundY.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        return buf;
    }

    /** 把一个维度的提示音范围表发给单个玩家。 */
    public static void sendHelpRoundSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.HELP_ROUND_SYNC_CHANNEL, buildHelpRoundPacket(level));
    }

    /** 把全部维度的提示音范围表同步给所有在线玩家。 */
    public static void syncHelpRoundToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpRoundSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.31】无障碍提示音**速率**：同步（同样一个小包，但**入口+出口一起发**）
    //   ★ 1.31 没有界面控件，所以只有 S→C 的 SYNC，没有 C→S 的 SET。
    //   两套速率总是同时改、同时同步，所以合成一只包（少一次建包/发送，客户端也少一次覆盖）。
    // ------------------------------------------------------------------

    /** 构建某个维度的「提示音速率」同步包（入口 / 出口两套：维度默认值 + 单独设置表）。 */
    private static FriendlyByteBuf buildHelpSpeedPacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.defaultHelpSpeedIn);
        buf.writeVarInt(data.blockHelpSpeedIn.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpSpeedIn.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(data.defaultHelpSpeedOut);
        buf.writeVarInt(data.blockHelpSpeedOut.size());
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpSpeedOut.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        return buf;
    }

    /** 把一个维度的提示音速率表发给单个玩家。 */
    public static void sendHelpSpeedSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.HELP_SPEED_SYNC_CHANNEL, buildHelpSpeedPacket(level));
    }

    /** 把全部维度的提示音速率表同步给所有在线玩家。 */
    public static void syncHelpSpeedToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpSpeedSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音**音乐**：同步（小包 —— 只发「选了哪一段」，
    //   音频字节本身仍然只走 AUDIO_SYNC 那一份，绝不重复发）
    //   ★ 进 / 出两套总是同时改、同时同步，所以合成一只包（少一次建包/发送）——
    //     与 HELP_SPEED_SYNC 的处理方式完全一致。
    // ------------------------------------------------------------------

    /** 构建某个维度的「提示音音乐」同步包（进 / 出两套：默认层 + 单独设置层）。 */
    private static FriendlyByteBuf buildHelpAudioPacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeUtf(normaliseHelpAudio(data.defaultHelpAudioIn), 128);
        buf.writeVarInt(data.blockHelpAudioIn.size());
        for (Map.Entry<BlockPos, String> entry : data.blockHelpAudioIn.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
        buf.writeUtf(normaliseHelpAudio(data.defaultHelpAudioOut), 128);
        buf.writeVarInt(data.blockHelpAudioOut.size());
        for (Map.Entry<BlockPos, String> entry : data.blockHelpAudioOut.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
        return buf;
    }

    /** 把一个维度的提示音音乐表发给单个玩家。 */
    public static void sendHelpAudioSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.HELP_AUDIO_SYNC_CHANNEL, buildHelpAudioPacket(level));
    }

    /** 把全部维度的提示音音乐表同步给所有在线玩家。 */
    public static void syncHelpAudioToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpAudioSyncTo(player, level);
            }
        }
    }

    private static FriendlyByteBuf buildSyncPacket(MinecraftServer server) {
        FriendlyByteBuf buf = SLNet.buf();
        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }
        buf.writeVarInt(levels.size());
        for (ServerLevel level : levels) {
            EscalatorSpeedData data = getServerData(level);
            buf.writeUtf(level.dimension().location().toString(), 256);
            buf.writeDouble(data.defaultSpeed);
            buf.writeBoolean(data.stepEnabled);
            buf.writeDouble(data.stepValue);
            buf.writeVarInt(data.speeds.size());
            for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeDouble(entry.getValue());
            }
            buf.writeVarInt(filteredStepSpeeds(data).size());
            for (Map.Entry<BlockPos, Double> entry : filteredStepSpeeds(data).entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeDouble(entry.getValue());
            }
        }
        return buf;
    }

    /** 只同步石斧自定义过的阶梯动画（旧指令写的、无 axeModified 的条目不发）。 */
    private static Map<BlockPos, Double> filteredStepSpeeds(EscalatorSpeedData data) {
        Map<BlockPos, Double> out = new HashMap<>();
        for (Map.Entry<BlockPos, Double> entry : data.stepSpeeds.entrySet()) {
            if (data.axeModified.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    public static ResourceKey<Level> parseDimensionKey(String id) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(id));
    }

    // ==================================================================
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    //   数据只有「维度默认」一层（见 EscalatorSpeedData 里那一段的说明），所以这里的
    //   访问器比其它设置短得多：没有 blockXxx 表、没有「单独设置」概念。
    //
    //   ★ 这里说的「维度默认」是**按维度存的**（每个 ServerLevel 一份 SavedData），
    //     所以指令里的 `-f` 取「**对所有维度**强制」的含义 —— 那才是这套数据里
    //     唯一能被「强制」的东西。别照抄扶梯那边的「清掉单独设置」文案。
    // ==================================================================

    /** 【1.42】这个维度**生效**的直梯提示音开关（客户端读镜像，服务端读 SavedData）。 */
    public static boolean isLiftHelpEnabled(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null || data.liftHelp;
        }
        return getServerData((ServerLevel) level).defaultLiftHelp;
    }

    // ------------------------------------------------------------------
    // 【1.46】四提示音（up / down / open / close）的**独立子开关**：总开关（/lifthelp）开着时，
    //   这四个还能各自再关一层。which 一律是 "up" / "down" / "open" / "close"。
    //   客户端读镜像、服务端读 SavedData；指令与石斧 UI 都走这一组。
    //   ★【1.28】原来的 chime（开关门一体）拆成 open（开门）/ close（关门）两项。
    // ------------------------------------------------------------------

    private static boolean serverLiftToneEnabled(EscalatorSpeedData data, String which) {
        return switch (which) {
            case "up" -> data.defaultLiftToneUpEnabled;
            case "down" -> data.defaultLiftToneDownEnabled;
            case "open" -> data.defaultLiftToneOpenEnabled;
            case "close" -> data.defaultLiftToneCloseEnabled;
            default -> true;
        };
    }

    private static void setServerLiftToneEnabled(EscalatorSpeedData data, String which, boolean enabled) {
        switch (which) {
            case "up" -> data.defaultLiftToneUpEnabled = enabled;
            case "down" -> data.defaultLiftToneDownEnabled = enabled;
            case "open" -> data.defaultLiftToneOpenEnabled = enabled;
            case "close" -> data.defaultLiftToneCloseEnabled = enabled;
            default -> {
            }
        }
    }

    /** 【1.46】这个维度**生效**的某项子开关（客户端读镜像，服务端读 SavedData）。 */
    public static boolean isLiftToneEnabled(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return switch (which) {
                case "up" -> data == null || data.liftToneUpEnabled;
                case "down" -> data == null || data.liftToneDownEnabled;
                case "open" -> data == null || data.liftToneOpenEnabled;
                case "close" -> data == null || data.liftToneCloseEnabled;
                default -> true;
            };
        }
        return serverLiftToneEnabled(getServerData((ServerLevel) level), which);
    }

    /** {@code /lifthelp up|down|door <on|off>}：只改**本维度**的对应子开关。 */
    public static void setDefaultLiftToneEnabled(ServerLevel level, String which, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        setServerLiftToneEnabled(data, which, enabled);
        data.setDirty();
    }

    /** {@code /lifthelp up|down|door <X> to <Y>}：本维度对应子开关正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftToneEnabled(ServerLevel level, String which, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (serverLiftToneEnabled(data, which) != from) {
            return false;
        }
        setServerLiftToneEnabled(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelp up|down|door -f <on|off>}：把**所有维度**的对应子开关都设成该值。 */
    public static int setDefaultLiftToneEnabledAll(MinecraftServer server, String which, boolean enabled) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneEnabled(data, which) != enabled) {
                setServerLiftToneEnabled(data, which, enabled);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelp up|down|door -f <X> to <Y>}：所有维度里对应子开关正好是 X 的改成 Y。 */
    public static int replaceDefaultLiftToneEnabledAll(MinecraftServer server, String which, boolean from, boolean to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneEnabled(data, which) == from) {
                setServerLiftToneEnabled(data, which, to);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** 【1.46】某项子开关在界面 / 指令反馈里的中文名。 */
    public static String liftToneEnabledLabel(String which) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            case "open" -> "开门提示音";
            case "close" -> "关门提示音";
            default -> "提示音";
        };
    }

    // ------------------------------------------------------------------
    // 【1.15】三提示音的**维度默认素材**（{@code /lifthelp up|down|door <名字> [-f]}）
    //
    // 与上面的子开关那一组**形状对称**：本维度 / <X> to <Y> / -f 全部维度 / -f <X> to <Y>。
    // 值域与 liftToneAudio 相同：default（跟上一层 = 内置素材）/ off（这一项不播）/
    // 音频库文件名。
    //
    // ★ `-f` 的含义是「**所有维度**都设成它，并清掉按竖井列的单独设置」——
    //   与 /futimusic -f、/futihelpmusic -f 的语义完全一致（清掉更细的一层，
    //   剩下的那条粗粒度设置就管住全部）。
    // ★ 为什么「单独设置里那一项是 default」要当成「跟维度默认」：两层的 default 都是
    //   「跟上一层」的意思，初始值也都是 default ⇒ 不设任何东西时行为与 1.14 一模一样。
    // ------------------------------------------------------------------

    private static String serverLiftToneAudio(EscalatorSpeedData data, String which) {
        return switch (which) {
            case "up" -> data.defaultLiftToneAudioUp;
            case "down" -> data.defaultLiftToneAudioDown;
            case "open" -> data.defaultLiftToneAudioOpen;
            case "close" -> data.defaultLiftToneAudioClose;
            default -> EscalatorSpeedData.LIFT_TONE_DEFAULT;
        };
    }

    private static void setServerLiftToneAudio(EscalatorSpeedData data, String which, String audioId) {
        String id = EscalatorSpeedData.normalizeLiftToneAudio(audioId);
        switch (which) {
            case "up" -> data.defaultLiftToneAudioUp = id;
            case "down" -> data.defaultLiftToneAudioDown = id;
            case "open" -> data.defaultLiftToneAudioOpen = id;
            case "close" -> data.defaultLiftToneAudioClose = id;
            default -> {
            }
        }
    }

    /** 【1.15】这个维度**生效**的某项默认素材（客户端读镜像，服务端读 SavedData）。 */
    public static String getLiftToneAudio(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return EscalatorSpeedData.normalizeLiftToneAudio(switch (which) {
                case "up" -> data == null ? null : data.liftToneAudioUp;
                case "down" -> data == null ? null : data.liftToneAudioDown;
                case "open" -> data == null ? null : data.liftToneAudioOpen;
                case "close" -> data == null ? null : data.liftToneAudioClose;
                default -> null;
            });
        }
        return serverLiftToneAudio(getServerData((ServerLevel) level), which);
    }

    /** {@code /lifthelp up|down|door <名字>}：只改**本维度**这一项的默认素材。 */
    public static void setDefaultLiftToneAudio(ServerLevel level, String which, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        setServerLiftToneAudio(data, which, audioId);
        data.setDirty();
    }

    /** {@code /lifthelp up|down|door <X> to <Y>}：本维度默认素材正好是 X 时才改成 Y（单独设置的不动）。 */
    public static boolean replaceDefaultLiftToneAudio(ServerLevel level, String which, String from, String to) {
        EscalatorSpeedData data = getServerData(level);
        if (!java.util.Objects.equals(serverLiftToneAudio(data, which), from)) {
            return false;
        }
        setServerLiftToneAudio(data, which, to);
        data.setDirty();
        return true;
    }

    /**
     * {@code /lifthelp up|down|door -f <名字>}：把**所有维度**这一项的默认素材都设成它，
     * 并清掉按竖井列的单独设置（那些直梯从此跟维度默认）。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftToneAudioAll(MinecraftServer server, String which, String audioId) {
        String id = EscalatorSpeedData.normalizeLiftToneAudio(audioId);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (!id.equals(serverLiftToneAudio(data, which))) {
                setServerLiftToneAudio(data, which, id);
                touched = true;
            }
            if (clearLiftToneOverrides(data, which)) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelp up|down|door -f <X> to <Y>}：所有维度里默认素材正好是 X 的改成 Y；
     * 同时把**单独设置**里那一项正好是 X 的也改成 Y（与 {@code /futimusic -f X to Y} 同一语义）。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftToneAudioAll(MinecraftServer server, String which, String from, String to) {
        String id = EscalatorSpeedData.normalizeLiftToneAudio(to);
        String src = EscalatorSpeedData.normalizeLiftToneAudio(from);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (src.equals(serverLiftToneAudio(data, which))) {
                setServerLiftToneAudio(data, which, id);
                touched = true;
            }
            // 单独设置里那一项正好是 X 的也一起换掉（-f = 「含单独设置的」）
            if (!data.liftToneAudio.isEmpty()) {
                Map<Long, EscalatorSpeedData.LiftToneAudio> next = new HashMap<>();
                for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> e : data.liftToneAudio.entrySet()) {
                    EscalatorSpeedData.LiftToneAudio t = e.getValue();
                    String up = "up".equals(which) && src.equals(t.up()) ? id : t.up();
                    String down = "down".equals(which) && src.equals(t.down()) ? id : t.down();
                    String open = "open".equals(which) && src.equals(t.open()) ? id : t.open();
                    String close = "close".equals(which) && src.equals(t.close()) ? id : t.close();
                    if (!up.equals(t.up()) || !down.equals(t.down())
                            || !open.equals(t.open()) || !close.equals(t.close())) {
                        touched = true;
                    }
                    if (!EscalatorSpeedData.isLiftToneAllDefault(up, down, open, close)) {
                        next.put(e.getKey(), new EscalatorSpeedData.LiftToneAudio(up, down, open, close));
                    }
                }
                if (touched) {
                    data.liftToneAudio.clear();
                    data.liftToneAudio.putAll(next);
                }
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.15】把「按竖井列单独设置」里 {@code which} 这一项**改回「跟维度默认」**；
     * 四项都变成默认的那条记录直接删掉（表越干净越好查）。
     *
     * @return 真的改动过才 true
     */
    private static boolean clearLiftToneOverrides(EscalatorSpeedData data, String which) {
        if (data.liftToneAudio.isEmpty()) {
            return false;
        }
        Map<Long, EscalatorSpeedData.LiftToneAudio> next = new HashMap<>();
        boolean touched = false;
        for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> e : data.liftToneAudio.entrySet()) {
            EscalatorSpeedData.LiftToneAudio t = e.getValue();
            String up = "up".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.up();
            String down = "down".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.down();
            String open = "open".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.open();
            String close = "close".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.close();
            if (!up.equals(t.up()) || !down.equals(t.down())
                    || !open.equals(t.open()) || !close.equals(t.close())) {
                touched = true;
            }
            if (!EscalatorSpeedData.isLiftToneAllDefault(up, down, open, close)) {
                next.put(e.getKey(), new EscalatorSpeedData.LiftToneAudio(up, down, open, close));
            }
        }
        if (!touched) {
            return false;
        }
        data.liftToneAudio.clear();
        data.liftToneAudio.putAll(next);
        return true;
    }

    /**
     * 【1.15】命令行音频名字 → 直梯提示音素材 id。
     *
     * <ul>
     *   <li>{@code default}（大小写不敏感）→ {@link EscalatorSpeedData#LIFT_TONE_DEFAULT}
     *       （模组内置素材：上楼 up.ogg / 下楼 down.ogg / 开关门 liftmusic.ogg）；</li>
     *   <li>{@code none} / {@code mute} / {@code off} → {@link EscalatorSpeedData#LIFT_TONE_OFF}
     *       （这一项不播）；</li>
     *   <li>其它 → 音频库里同名的文件（找不到时再试「名字 + .ogg」，与扶梯那套一致）。</li>
     * </ul>
     *
     * <p>★ 为什么「不播」推荐写 {@code none} 而不是 {@code off}：指令里 {@code off}
     * 已经是**子开关**的字面量（{@code /lifthelp up off} = 关掉上行提示音），
     * Brigadier 的字面量优先于字符串参数 ⇒ 玩家打不出「把默认素材设成 off」这一句。
     * 这里仍然认 {@code off} 只是让「别处传进来 / 玩家从别处抄来的写法」不至于报错。
     */
    /** 【1.28】按分类解析直梯提示音素材名（/lifthelp up|down|open|close <名字>）。 */
    public static AudioArg resolveLiftToneName(ServerLevel level, String category, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "音频名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(lower)) {
            return new AudioArg(EscalatorSpeedData.LIFT_TONE_DEFAULT, false, null);
        }
        if (EscalatorSpeedData.LIFT_TONE_OFF.equals(lower) || "none".equals(lower) || "mute".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.LIFT_TONE_OFF, true, null);
        }
        EscalatorSpeedData data = getServerData(level);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return new AudioArg(name, false, null);
        }
        // 玩家少打后缀名时兜底（与 /futimusic 同一手法）
        if (!lower.endsWith(".ogg") && catNames.contains(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "「" + category + "」分类里没有叫「" + name + "」的音频。直梯提示音可以用 "
                + EscalatorSpeedData.LIFT_TONE_DEFAULT + " 内置素材、none 不播，或用导入过的 .ogg；"
                + (catNames.isEmpty()
                        ? "现在还没有导入过任何音频，玩家需要在石斧界面里上传或导入 .ogg"
                        : "已有的：" + previewNames(data, category)));
    }

    /**
     * 【1.15】直梯提示音名字参数的 Tab 补全候选（**字典序，default / none 排在最前**）：
     * 模组内置素材 {@code default}、不播 {@code none}，然后是**该分类**导入的每一个 .ogg 文件名。
     * 【1.28】按分类取。
     */
    public static List<String> liftToneNameCandidates(ServerLevel level, String category) {
        List<String> names = new ArrayList<>(getServerAudioLibraryKeys(level, category));
        names.sort(String::compareTo);
        List<String> out = new ArrayList<>(names.size() + 2);
        out.add(EscalatorSpeedData.LIFT_TONE_DEFAULT);
        out.add("none");
        out.addAll(names);
        return out;
    }

    /**
     * 【1.42】这个维度**生效**的直梯提示音倍速（客户端读镜像，服务端读 SavedData）。
     * 始终落在 [{@link EscalatorSpeedData#LIFT_HELP_SPEED_MIN}, {@link EscalatorSpeedData#LIFT_HELP_SPEED_MAX}]。
     */
    public static float getLiftHelpSpeed(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_SPEED : data.liftHelpSpeed;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpSpeed;
    }

    /**
     * 【1.43】这个维度**生效**的直梯提示音音量（客户端读镜像，服务端读 SavedData）。
     * 始终落在 [{@link EscalatorSpeedData#HELP_VOLUME_MIN}, {@link EscalatorSpeedData#HELP_VOLUME_MAX}]
     * （1~1000，100 = 原始音量、1000 = 10× 放大）。
     */
    public static int getLiftHelpVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_VOLUME : data.liftHelpVolume;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpVolume;
    }

    // ------------------------------------------------------------------
    // 【1.48】四提示音（up / down / open / close）**各自的音量**：-1 = 该项没单独调过 → 跟随共用默认。
    //   which 一律是 "up" / "down" / "open" / "close"。
    //   客户端读镜像、服务端读 SavedData；石斧 UI 每个列表里的音量输入框走这一组。
    // ------------------------------------------------------------------

    private static int serverLiftToneVolume(EscalatorSpeedData data, String which) {
        return switch (which) {
            case "up" -> data.defaultLiftToneVolumeUp;
            case "down" -> data.defaultLiftToneVolumeDown;
            case "open" -> data.defaultLiftToneVolumeOpen;
            case "close" -> data.defaultLiftToneVolumeClose;
            default -> EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        };
    }

    private static void setServerLiftToneVolume(EscalatorSpeedData data, String which, int volume) {
        switch (which) {
            case "up" -> data.defaultLiftToneVolumeUp = EscalatorSpeedData.clampLiftToneVolume(volume);
            case "down" -> data.defaultLiftToneVolumeDown = EscalatorSpeedData.clampLiftToneVolume(volume);
            case "open" -> data.defaultLiftToneVolumeOpen = EscalatorSpeedData.clampLiftToneVolume(volume);
            case "close" -> data.defaultLiftToneVolumeClose = EscalatorSpeedData.clampLiftToneVolume(volume);
            default -> {
            }
        }
    }

    /** 【1.48】这项提示音**生效**的音量（单项 -1 → 跟随共用默认）。客户端读镜像，服务端读 SavedData。 */
    public static int getLiftToneVolume(Level level, String which) {
        int own;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            own = switch (which) {
                case "up" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeUp;
                case "down" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeDown;
                case "open" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeOpen;
                case "close" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeClose;
                default -> EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
            };
        } else {
            own = serverLiftToneVolume(getServerData((ServerLevel) level), which);
        }
        if (own == EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET) {
            return getLiftHelpVolume(level); // 跟随共用默认
        }
        return own;
    }

    /** 【1.48】这项提示音有没有**单独调过**音量（true = 有自己的值；false = 跟随共用默认）。 */
    public static boolean hasOwnLiftToneVolume(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return switch (which) {
                case "up" -> data != null && data.liftToneVolumeUp != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                case "down" -> data != null && data.liftToneVolumeDown != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                case "open" -> data != null && data.liftToneVolumeOpen != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                case "close" -> data != null && data.liftToneVolumeClose != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                default -> false;
            };
        }
        return serverLiftToneVolume(getServerData((ServerLevel) level), which)
                != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
    }

    /** {@code /lifthelploud up|down|open|close <音量>}：只改**本维度**这项的音量。 */
    public static void setDefaultLiftToneVolume(ServerLevel level, String which, int volume) {
        EscalatorSpeedData data = getServerData(level);
        setServerLiftToneVolume(data, which, volume);
        data.setDirty();
    }

    /** {@code /lifthelploud up|down|open|close <X> to <Y>}：本维度这项音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftToneVolume(ServerLevel level, String which, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (serverLiftToneVolume(data, which) != from) {
            return false;
        }
        setServerLiftToneVolume(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelploud -f up|down|open|close <音量>}：把**所有维度**这项的音量都设成该值。 */
    public static int setDefaultLiftToneVolumeAll(MinecraftServer server, String which, int volume) {
        int changed = 0;
        int clamped = EscalatorSpeedData.clampLiftToneVolume(volume);
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneVolume(data, which) != clamped) {
                setServerLiftToneVolume(data, which, clamped);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelploud -f up|down|open|close <X> to <Y>}：所有维度里这项音量正好是 X 的改成 Y。 */
    public static int replaceDefaultLiftToneVolumeAll(MinecraftServer server, String which, int from, int to) {
        int changed = 0;
        int clamped = EscalatorSpeedData.clampLiftToneVolume(to);
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneVolume(data, which) == from) {
                setServerLiftToneVolume(data, which, clamped);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.47】这个维度**生效**的直梯提示音淡入淡出范围（客户端读镜像，服务端读 SavedData）。
     * 三项提示音（上楼 / 下楼 / 开关门）共用这一份；始终落在
     * [{@link EscalatorSpeedData#LIFT_HELP_ROUND_MIN}, {@link EscalatorSpeedData#LIFT_HELP_ROUND_MAX}]。
     */
    public static int getLiftHelpRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND : data.liftHelpRound;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpRound;
    }

    /** ★【10-03】这个维度生效的直梯提示音范围的**垂直（y 轴）**维（客户端读镜像；初始 5 格）。 */
    public static int getLiftHelpRoundY(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND_Y : data.liftHelpRoundY;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpRoundY;
    }

    /** {@code /lifthelpround <水平> <垂直>}：只改**本维度**的默认范围（★【10-03】双维）。 */
    public static void setDefaultLiftHelpRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelpRound = EscalatorSpeedData.clampLiftHelpRound(xz);
        data.defaultLiftHelpRoundY = EscalatorSpeedData.clampLiftHelpRound(y);
        data.setDirty();
    }

    /** {@code /lifthelpround <Xz> <Y> to <新Xz> <新Y>}：本维度默认范围**两维都**正好时才改成新值。 */
    public static boolean replaceDefaultLiftHelpRound(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelpRound != fromXz || data.defaultLiftHelpRoundY != fromY) {
            return false;
        }
        data.defaultLiftHelpRound = EscalatorSpeedData.clampLiftHelpRound(toXz);
        data.defaultLiftHelpRoundY = EscalatorSpeedData.clampLiftHelpRound(toY);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelpround -f <水平> <垂直>}：把**所有维度**的默认范围都设成该值。 */
    public static int setDefaultLiftHelpRoundAll(MinecraftServer server, int xz, int y) {
        int clampedXz = EscalatorSpeedData.clampLiftHelpRound(xz);
        int clampedY = EscalatorSpeedData.clampLiftHelpRound(y);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpRound != clampedXz || data.defaultLiftHelpRoundY != clampedY) {
                data.defaultLiftHelpRound = clampedXz;
                data.defaultLiftHelpRoundY = clampedY;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelpround -f <Xz> <Y> to <新Xz> <新Y>}：所有维度里默认范围两维都正好是 Xz/Y 的改成新值。 */
    public static int replaceDefaultLiftHelpRoundAll(MinecraftServer server,
                                                     int fromXz, int fromY, int toXz, int toY) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpRound == fromXz && data.defaultLiftHelpRoundY == fromY) {
                data.defaultLiftHelpRound = EscalatorSpeedData.clampLiftHelpRound(toXz);
                data.defaultLiftHelpRoundY = EscalatorSpeedData.clampLiftHelpRound(toY);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelp <on|off>}：只改**本维度**的默认开关。 */
    public static void setDefaultLiftHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelp = enabled;
        data.setDirty();
    }

    /** {@code /lifthelp <X> to <Y>}：本维度默认开关正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftHelp(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelp != from) {
            return false;
        }
        data.defaultLiftHelp = to;
        data.setDirty();
        return true;
    }

    /** 【1.15】`/lifthelpspeed` 指令已删除；这个方法与下面三个**只留给数据层/存档兼容**，没有调用点。 */
    public static void setDefaultLiftHelpSpeed(ServerLevel level, float speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        data.setDirty();
    }

    /** 【1.15】见上面那条说明（本维度默认倍速正好是 X 时才改成 Y）。 */
    public static boolean replaceDefaultLiftHelpSpeed(ServerLevel level, float from, float to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelpSpeed != from) {
            return false;
        }
        data.defaultLiftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelploud <音量>}：只改**本维度**的默认音量。 */
    public static void setDefaultLiftHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        data.setDirty();
    }

    /** {@code /lifthelploud <X> to <Y>}：本维度默认音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftHelpVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelpVolume != from) {
            return false;
        }
        data.defaultLiftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(to);
        data.setDirty();
        return true;
    }

    /**
     * {@code /lifthelp -f <on|off>}：把**所有维度**的默认开关都设成该值。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftHelpAll(MinecraftServer server, boolean enabled) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelp != enabled) {
                data.defaultLiftHelp = enabled;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.15】已无指令调用：把**所有维度**的默认倍速都设成该值（保留给存档/后续复用）。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftHelpSpeedAll(MinecraftServer server, float speed) {
        float target = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpSpeed != target) {
                data.defaultLiftHelpSpeed = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelp -f <X> to <Y>}：所有维度里，默认开关正好是 X 的那些改成 Y。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftHelpAll(MinecraftServer server, boolean from, boolean to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelp == from) {
                data.defaultLiftHelp = to;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.15】已无指令调用：所有维度里，默认倍速正好是 X 的那些改成 Y。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftHelpSpeedAll(MinecraftServer server, float from, float to) {
        float target = EscalatorSpeedData.clampLiftHelpSpeed(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpSpeed == from) {
                data.defaultLiftHelpSpeed = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelploud -f <音量>}：把**所有维度**的默认音量都设成该值。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftHelpVolumeAll(MinecraftServer server, int volume) {
        int target = EscalatorSpeedData.clampLiftHelpVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpVolume != target) {
                data.defaultLiftHelpVolume = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelploud -f <X> to <Y>}：所有维度里，默认音量正好是 X 的那些改成 Y。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftHelpVolumeAll(MinecraftServer server, int from, int to) {
        int target = EscalatorSpeedData.clampLiftHelpVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpVolume == from) {
                data.defaultLiftHelpVolume = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.42】应用服务端同步过来的直梯提示音设置（覆盖式更新本维度的镜像）。
     *
     * <p>【1.43】多了**音量**一项 —— 和开关 / 倍速同属一套「按维度」的设置，所以共用同一只
     * 同步包、同一个代次，不做第三条频道。
     *
     * <p>代次 +1 是给客户端播放器做「缓存作废」用的（与 {@code clientHelpGeneration} 同一手法）：
     * 播放器每 tick 只在「代次变了」时才真的去查，不然每次读都要过一次 Map。
     */
    public static void applyClientLiftChime(ResourceKey<Level> dimension, boolean enabled, float speed,
                                            int volume, boolean upEnabled, boolean downEnabled,
                                            boolean openEnabled, boolean closeEnabled, int round,
                                            int roundY,
                                            int toneVolumeUp, int toneVolumeDown,
                                            int toneVolumeOpen, int toneVolumeClose,
                                            String toneAudioUp, String toneAudioDown,
                                            String toneAudioOpen, String toneAudioClose) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.liftHelp = enabled;
        data.liftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        data.liftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        data.liftHelpRound = EscalatorSpeedData.clampLiftHelpRound(round);
        data.liftHelpRoundY = EscalatorSpeedData.clampLiftHelpRound(roundY);
        data.liftToneUpEnabled = upEnabled;
        data.liftToneDownEnabled = downEnabled;
        data.liftToneOpenEnabled = openEnabled;
        data.liftToneCloseEnabled = closeEnabled;
        data.liftToneVolumeUp = EscalatorSpeedData.clampLiftToneVolume(toneVolumeUp);
        data.liftToneVolumeDown = EscalatorSpeedData.clampLiftToneVolume(toneVolumeDown);
        data.liftToneVolumeOpen = EscalatorSpeedData.clampLiftToneVolume(toneVolumeOpen);
        data.liftToneVolumeClose = EscalatorSpeedData.clampLiftToneVolume(toneVolumeClose);
        // 【1.15】四项的维度默认素材
        data.liftToneAudioUp = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioUp);
        data.liftToneAudioDown = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioDown);
        data.liftToneAudioOpen = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioOpen);
        data.liftToneAudioClose = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioClose);
        clientLiftChimeGeneration++;
    }

    /** 见 {@link #applyClientLiftChime}。只在客户端线程读、在客户端线程写。 */
    public static long clientLiftChimeGeneration() {
        return clientLiftChimeGeneration;
    }

    /** 见 {@link #clientLiftChimeGeneration()}。 */
    private static long clientLiftChimeGeneration;

    /**
     * 【1.42】构建某个维度的「直梯提示音」同步包：维度默认开关 + 维度默认倍速
     * 【1.43】+ 维度默认音量。【1.46】+ 四提示音独立子开关（up/down/open/close）。
     * 【1.15】+ 四项的**维度默认素材**。
     *
     * <p>字段顺序**就是** {@link #applyClientLiftChime} 的入参顺序，两处必须一起改
     * （客户端 {@code SmoothLiftClient} 那边是按同一顺序读的）：
     * {@code dimId → enabled → speed → volume → upEnabled → downEnabled → openEnabled → closeEnabled}。
     */
    private static FriendlyByteBuf buildLiftChimePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeBoolean(data.defaultLiftHelp);
        buf.writeFloat(data.defaultLiftHelpSpeed);
        buf.writeVarInt(data.defaultLiftHelpVolume);
        buf.writeBoolean(data.defaultLiftToneUpEnabled);
        buf.writeBoolean(data.defaultLiftToneDownEnabled);
        buf.writeBoolean(data.defaultLiftToneOpenEnabled);
        buf.writeBoolean(data.defaultLiftToneCloseEnabled);
        // 【1.47】淡入淡出范围（四项共用）—— ★【10-03】双维：水平紧接垂直，读侧顺序必须一致
        buf.writeVarInt(data.defaultLiftHelpRound);
        buf.writeVarInt(data.defaultLiftHelpRoundY);
        // 【1.48】四项各自音量（-1 = 跟随共用默认）
        buf.writeVarInt(data.defaultLiftToneVolumeUp);
        buf.writeVarInt(data.defaultLiftToneVolumeDown);
        buf.writeVarInt(data.defaultLiftToneVolumeOpen);
        buf.writeVarInt(data.defaultLiftToneVolumeClose);
        // 【1.15】四项的维度默认素材（包尾追加，读侧顺序必须一致）
        buf.writeUtf(EscalatorSpeedData.normalizeLiftToneAudio(data.defaultLiftToneAudioUp), 128);
        buf.writeUtf(EscalatorSpeedData.normalizeLiftToneAudio(data.defaultLiftToneAudioDown), 128);
        buf.writeUtf(EscalatorSpeedData.normalizeLiftToneAudio(data.defaultLiftToneAudioOpen), 128);
        buf.writeUtf(EscalatorSpeedData.normalizeLiftToneAudio(data.defaultLiftToneAudioClose), 128);
        return buf;
    }

    /** 【1.42】把一个维度的直梯提示音设置发给单个玩家。 */
    public static void sendLiftChimeSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.LIFT_CHIME_SYNC_CHANNEL, buildLiftChimePacket(level));
    }

    /** 【1.42】把所有维度的直梯提示音设置同步给所有在线玩家。 */
    public static void syncLiftChimeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendLiftChimeSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.45】直梯楼层轨道提示音（石斧右键楼层轨道设置：up / down / chime 三列表）
    // ------------------------------------------------------------------

    /** 竖井列打包坐标：同一条直梯的所有楼层轨道共享 (X, Z)，只有 Y 不同 ⇒ key = asLong(x, 0, z)。 */
    public static long liftToneKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    /** 从任意一个楼层/轿厢坐标（double）算竖井列 key；越界/异常一律退化成「无设置」。 */
    public static long liftToneKeyNear(double x, double z) {
        return liftToneKey((int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * 服务端：读某条直梯（竖井列）的三项提示音设置；没设置过 → {@link EscalatorSpeedData.LiftToneAudio#NONE}。
     * 值域校验不做在这里（写入时已经夹过）。
     */
    public static EscalatorSpeedData.LiftToneAudio getServerLiftTone(ServerLevel level, long key) {
        EscalatorSpeedData.LiftToneAudio tone = getServerData(level).liftToneAudio.get(key);
        return tone != null ? tone : EscalatorSpeedData.LiftToneAudio.NONE;
    }

    /**
     * 客户端：读某条直梯（竖井列）的三项提示音设置（镜像）；没同步过 → {@code NONE}。
     */
    public static EscalatorSpeedData.LiftToneAudio getClientLiftTone(Level level, long key) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return EscalatorSpeedData.LiftToneAudio.NONE;
        }
        EscalatorSpeedData.LiftToneAudio tone = data.liftToneAudio.get(key);
        return tone != null ? tone : EscalatorSpeedData.LiftToneAudio.NONE;
    }

    /**
     * 【1.45】按「哪一项（up/down/open/close）」定位值；{@code which} 不属于这四项 → null。
     *
     * <p>【1.15】改成 public：播放端（{@code LiftChimePlayer}）要拿它做「单独设置」这一层的
     * 取值，再按「default = 跟维度默认」往下回落。
     */
    public static String toneField(EscalatorSpeedData.LiftToneAudio tone, String which) {
        return switch (which) {
            case "up" -> tone.up();
            case "down" -> tone.down();
            case "open" -> tone.open();
            case "close" -> tone.close();
            default -> null;
        };
    }

    /**
     * 校验并写入一个直梯提示音设置。
     *
     * @return 成功写入了才 true；{@code audioId} 不是 default / off / 该分类音频库里存在的 id → false。
     * 【1.28】素材校验按这一项的**分类**走（up→lift/up、down→lift/down、open→lift/open、close→lift/close）。
     */
    public static boolean setServerLiftTone(ServerLevel level, long key, String which, String audioId) {
        if (!"up".equals(which) && !"down".equals(which) && !"open".equals(which) && !"close".equals(which)) {
            return false;
        }
        if (!EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(audioId)
                && !EscalatorSpeedData.LIFT_TONE_OFF.equals(audioId)
                && !categoryAudioNames(getServerData(level), liftToneCategory(which)).contains(audioId)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        EscalatorSpeedData.LiftToneAudio old = data.liftToneAudio.get(key);
        if (old == null) {
            old = EscalatorSpeedData.LiftToneAudio.NONE;
        }
        String up = "up".equals(which) ? audioId : old.up();
        String down = "down".equals(which) ? audioId : old.down();
        String open = "open".equals(which) ? audioId : old.open();
        String close = "close".equals(which) ? audioId : old.close();
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(up)
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(down)
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(open)
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(close)) {
            // 四项全默认 = 等于没设置，直接删掉这条记录（表越干净越好查）。
            data.liftToneAudio.remove(key);
        } else {
            data.liftToneAudio.put(key, new EscalatorSpeedData.LiftToneAudio(up, down, open, close));
        }
        data.setDirty();
        return true;
    }

    /** 【1.28】直梯提示音「项」→ 音频分类（与界面/指令的文件夹对应）。 */
    public static String liftToneCategory(String which) {
        return switch (which) {
            case "up" -> CAT_LIFT_UP;
            case "down" -> CAT_LIFT_DOWN;
            case "open" -> CAT_LIFT_OPEN;
            case "close" -> CAT_LIFT_CLOSE;
            default -> CAT_LIFT_OPEN;
        };
    }

    /**
     * 【1.45】构建某个维度的「直梯楼层轨道提示音」同步包：
     * {@code dimId → 条数 → (key, up, down, open, close) × N}。
     */
    private static FriendlyByteBuf buildLiftTonePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.liftToneAudio.size());
        for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> e : data.liftToneAudio.entrySet()) {
            buf.writeLong(e.getKey());
            EscalatorSpeedData.LiftToneAudio tone = e.getValue();
            buf.writeUtf(tone.up(), 128);
            buf.writeUtf(tone.down(), 128);
            buf.writeUtf(tone.open(), 128);
            buf.writeUtf(tone.close(), 128);
        }
        return buf;
    }

    /** 【1.45】把一个维度的直梯楼层轨道提示音设置发给单个玩家。 */
    public static void sendLiftToneSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.LIFT_TONE_SYNC_CHANNEL, buildLiftTonePacket(level));
    }

    /** 【1.45】把所有维度的直梯楼层轨道提示音设置同步给所有在线玩家。 */
    public static void syncLiftToneToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendLiftToneSyncTo(player, level);
            }
        }
    }

    /** 【1.45】客户端接收器：把同步包的直梯楼层轨道提示音写进镜像（包已在接收器里解析好）。 */
    public static void applyClientLiftTone(ResourceKey<Level> dimension,
                                           Map<Long, EscalatorSpeedData.LiftToneAudio> tones) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.liftToneAudio.clear();
        data.liftToneAudio.putAll(tones);
        clientLiftToneGeneration++;
    }

    /**
     * 【1.45】客户端：点完石斧界面某一行后**本地立即**改镜像一个竖井列的设置
     * （服务端的权威值随后会通过 {@link #applyClientLiftTone} 整表覆盖回来，所以只是临时加速回显）。
     */
    public static void applyClientLiftToneLocal(ResourceKey<Level> dimension, long key,
                                                EscalatorSpeedData.LiftToneAudio tone) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.up())
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.down())
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.open())
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.close())) {
            data.liftToneAudio.remove(key);
        } else {
            data.liftToneAudio.put(key, tone);
        }
        clientLiftToneGeneration++;
    }

    /** 【1.46】客户端：石斧 UI 点完开关后**本地立即**翻镜像（服务端权威值随后整表覆盖回来）。 */
    public static void applyClientLiftToneSwitchLocal(ResourceKey<Level> dimension, String which, boolean enabled) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        switch (which) {
            case "up" -> data.liftToneUpEnabled = enabled;
            case "down" -> data.liftToneDownEnabled = enabled;
            case "open" -> data.liftToneOpenEnabled = enabled;
            case "close" -> data.liftToneCloseEnabled = enabled;
            default -> {
            }
        }
        clientLiftChimeGeneration++;
    }

    /** 【1.48】客户端：石斧 UI 主界面「设置默认音量」后**本地立即**改镜像（服务端随后权威同步覆盖）。 */
    public static void applyClientLiftVolumeLocal(ResourceKey<Level> dimension, int volume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.liftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        clientLiftChimeGeneration++;
    }

    /** 【1.48】客户端：石斧 UI 单项列表「音量」后**本地立即**改镜像（服务端随后权威同步覆盖）。 */
    public static void applyClientLiftToneVolumeLocal(ResourceKey<Level> dimension, String which, int volume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        int v = EscalatorSpeedData.clampLiftToneVolume(volume);
        switch (which) {
            case "up" -> data.liftToneVolumeUp = v;
            case "down" -> data.liftToneVolumeDown = v;
            case "open" -> data.liftToneVolumeOpen = v;
            case "close" -> data.liftToneVolumeClose = v;
            default -> {
            }
        }
        clientLiftChimeGeneration++;
    }

    /** 直梯楼层轨道提示音镜像的代数（客户端播放端用来刷新缓存）。 */
    public static long clientLiftToneGeneration() {
        return clientLiftToneGeneration;
    }

    private static long clientLiftToneGeneration;

    // ==================================================================
    // 【09-30】闸机（MTR Ticket Barrier）提示音：进站 / 出站各一份
    //
    // 粒度 = **两层**（【09-30 续】从「只有维度默认」升级成与屏蔽门同一套形状）：
    //   ① 维度默认层（{@code defaultZhajiToneAudioIn/Out} + `…Volume…`）：整个维度这一侧，
    //      **只有指令**改它；界面的「同步所有」是「把这一组的值写进这一层」。
    //   ② 逐组层（{@link EscalatorSpeedData#zhajiTone}，键 = 组锚点 asLong）：
    //      石斧右键**那一组**闸机（连着的、同一功能的那些）；界面里改的每一项都落这一层，
    //      没设过的项**回落**到 ①。
    //   用户原话：「石斧右键…打开 ui…」+（续）「连着的相同功能（进站/出站）闸机为一组，
    //   可以单独调整一组闸机的音效和音量」「一起调整功能要和屏蔽门差不多」。
    //   ⇒ 与屏蔽门【1.20】那次改版同一个理由、同一套形状。
    //
    //   which：{@code "in"} = 进站闸机 / {@code "out"} = 出站闸机。
    //   「哪一台闸机算进站」由方块注册名决定：{@code mtr:ticket_barrier_entrance_1} /
    //   {@code mtr:ticket_barrier_exit_1}（不依赖 MTR 编译期，见 SmoothLift 那两个谓词）。
    //   组锚点由**客户端**（{@code ZhajiChain.anchorOf}）从方块坐标洪水填充算出，
    //   随包带上来；服务端只把它当键存，不需要自己遍历世界。
    //
    //   ★ 同步包是**独立一条**（ZHAJI_TONE_SYNC_CHANNEL），不往直梯/屏蔽门那两个包里塞 ——
    //     两个域各自演化，塞在一起就是等着「读写序错位而静默串字段」（回归脚本专门钉过这条）。
    // ==================================================================

    /**
     * 【09-30 续】「不在任何一组里」的哨兵 —— 写通道里带它 = 改**维度默认层**。
     *
     * <p>为什么不用 {@code 0L}：{@link BlockPos#asLong} 的世界原点 (0,0,0) 恰好就是 0，
     * 拿它当哨兵会把「原点那一组」和「维度默认」混成一句（本仓那条「一个哨兵同时表达两件事」）。
     * 真锚点永远不会是 {@link Long#MIN_VALUE}（那是 (x,y,z) 全 {@code -8388608} 那一格，
     * 在世界外的坐标区里，闸机放不到那儿）。
     */
    public static final long ZHAJI_GROUP_NONE = Long.MIN_VALUE;

    /** 闸机方向归一化：只认 {@code out}，其它一律当 {@code in}（拼错时落到最常用的那一侧）。 */
    public static String zhajiWhich(String which) {
        return "out".equals(which) ? "out" : "in";
    }

    /** 闸机方向的显示名（命令回执 / 界面 / 日志**共用这一份**，免得两处各写一遍而分叉）。 */
    public static String zhajiLabel(String which) {
        return "out".equals(zhajiWhich(which)) ? "出站闸机" : "进站闸机";
    }

    /** 闸机方向 → 音频分类（= MBM_Audio 下的子文件夹名）。 */
    public static String zhajiToneCategory(String which) {
        return "out".equals(zhajiWhich(which)) ? CAT_ZHAJI_OUT : CAT_ZHAJI_IN;
    }

    /**
     * 按分类解析闸机提示音素材名（{@code /zhaji in|out <名字>}）。
     *
     * <p>与 {@link #resolveLiftToneName} 同一套写法：{@code default} 跟内置、
     * {@code off}/{@code none}/{@code mute} 都是「这一侧不播」，其余按名字（少打 {@code .ogg} 兜底）。
     */
    public static AudioArg resolveZhajiToneName(ServerLevel level, String which, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "音频名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(lower)) {
            return new AudioArg(EscalatorSpeedData.ZHAJI_TONE_DEFAULT, false, null);
        }
        if (EscalatorSpeedData.ZHAJI_TONE_OFF.equals(lower) || "none".equals(lower) || "mute".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.ZHAJI_TONE_OFF, true, null);
        }
        EscalatorSpeedData data = getServerData(level);
        String category = zhajiToneCategory(which);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return new AudioArg(name, false, null);
        }
        // 玩家少打后缀名时兜底（与 /futimusic、/lifthelp 同一手法）
        if (!lower.endsWith(".ogg") && catNames.contains(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "「" + category + "」分类里没有叫「" + name + "」的音频。闸机提示音可以用 "
                + EscalatorSpeedData.ZHAJI_TONE_DEFAULT + " 内置素材、none 不播，或用导入过的 .ogg；"
                + (catNames.isEmpty()
                        ? "现在还没有导入过任何音频，玩家需要在石斧界面里导入 .ogg"
                        : "已有的：" + previewNames(data, category)));
    }

    private static String serverZhajiToneAudio(EscalatorSpeedData data, String which) {
        return "out".equals(zhajiWhich(which)) ? data.defaultZhajiToneAudioOut : data.defaultZhajiToneAudioIn;
    }

    private static void setServerZhajiToneAudio(EscalatorSpeedData data, String which, String audioId) {
        String id = EscalatorSpeedData.normalizeZhajiToneAudio(audioId);
        if ("out".equals(zhajiWhich(which))) {
            data.defaultZhajiToneAudioOut = id;
        } else {
            data.defaultZhajiToneAudioIn = id;
        }
    }

    private static int serverZhajiToneVolume(EscalatorSpeedData data, String which) {
        return "out".equals(zhajiWhich(which)) ? data.defaultZhajiToneVolumeOut : data.defaultZhajiToneVolumeIn;
    }

    private static void setServerZhajiToneVolume(EscalatorSpeedData data, String which, int volume) {
        int v = EscalatorSpeedData.clampZhajiVolume(volume);
        if ("out".equals(zhajiWhich(which))) {
            data.defaultZhajiToneVolumeOut = v;
        } else {
            data.defaultZhajiToneVolumeIn = v;
        }
    }

    // --- 读（客户端读镜像 / 服务端读 SavedData） ------------------------

    /**
     * 这一侧闸机**生效**的提示音素材（客户端读镜像，服务端读 SavedData）。
     * 镜像还没同步过 → {@link EscalatorSpeedData#ZHAJI_TONE_DEFAULT}（= 用 MTR 内置音）。
     */
    public static String getZhajiToneAudio(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.ZHAJI_TONE_DEFAULT;
            }
            return EscalatorSpeedData.normalizeZhajiToneAudio(
                    "out".equals(zhajiWhich(which)) ? data.zhajiToneAudioOut : data.zhajiToneAudioIn);
        }
        return serverZhajiToneAudio(getServerData((ServerLevel) level), which);
    }

    /** 这一侧闸机**生效**的提示音音量（客户端读镜像，服务端读 SavedData），1~1000。 */
    public static int getZhajiToneVolume(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_ZHAJI_VOLUME;
            }
            return "out".equals(zhajiWhich(which)) ? data.zhajiToneVolumeOut : data.zhajiToneVolumeIn;
        }
        return serverZhajiToneVolume(getServerData((ServerLevel) level), which);
    }

    // --- 【09-30 续】逐组层：读（本组 → 维度默认，两层回落） -------------

    /** 一组闸机的**原始**记录（服务端读 SavedData）；没设置过 → {@code NONE}。 */
    public static EscalatorSpeedData.ZhajiTone zhajiGroupRecord(ServerLevel level, long groupKey) {
        EscalatorSpeedData.ZhajiTone t = getServerData(level).zhajiTone.get(groupKey);
        return t != null ? t : EscalatorSpeedData.ZhajiTone.NONE;
    }

    /** 一组闸机的**原始**记录（客户端读镜像）；没同步到 → {@code NONE}。 */
    public static EscalatorSpeedData.ZhajiTone clientZhajiGroupRecord(Level level, long groupKey) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return EscalatorSpeedData.ZhajiTone.NONE;
        }
        EscalatorSpeedData.ZhajiTone t = data.zhajiTone.get(groupKey);
        return t != null ? t : EscalatorSpeedData.ZhajiTone.NONE;
    }

    /**
     * 这一组、这一侧**生效**的素材（本组设过 → 本组；本组是 {@code default} / 没记过 → 维度默认层）。
     *
     * <p>{@code groupKey == }{@link #ZHAJI_GROUP_NONE}（或客户端还没同步到这一组）时直接走默认层 ——
     * 所以「算不出组」永远退回旧行为，不会静默哑掉。
     */
    public static String getZhajiToneAudio(Level level, String which, long groupKey) {
        if (groupKey != ZHAJI_GROUP_NONE) {
            EscalatorSpeedData.ZhajiTone t = level.isClientSide()
                    ? clientZhajiGroupRecord(level, groupKey)
                    : zhajiGroupRecord((ServerLevel) level, groupKey);
            String own = t.audioFor(zhajiWhich(which));
            if (own != null && !EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(own)) {
                return EscalatorSpeedData.normalizeZhajiToneAudio(own);
            }
        }
        return getZhajiToneAudio(level, which);
    }

    /**
     * 这一组、这一侧**生效**的音量（本组记过 → 本组；{@code null} → 维度默认层）。
     *
     * <p>注意「本组音量跟默认」与「本组音量 = 100」是两件事：前者记 {@code null}、后者记 100，
     * 所以这里必须判 {@code null} 而不是判「等于默认值」。
     */
    public static int getZhajiToneVolume(Level level, String which, long groupKey) {
        if (groupKey != ZHAJI_GROUP_NONE) {
            EscalatorSpeedData.ZhajiTone t = level.isClientSide()
                    ? clientZhajiGroupRecord(level, groupKey)
                    : zhajiGroupRecord((ServerLevel) level, groupKey);
            Integer own = t.volumeFor(zhajiWhich(which));
            if (own != null) {
                return EscalatorSpeedData.clampZhajiVolume(own);
            }
        }
        return getZhajiToneVolume(level, which);
    }

    // --- 写（本维度） --------------------------------------------------

    /**
     * 【09-30 续】石斧界面：给**一组闸机**的某一侧写素材（{@code default} = 本组跟维度默认）。
     *
     * <p>校验与指令同源（{@link #zhajiAudioValid}：{@code default} / {@code off} / 该分类里的 ogg），
     * 免得界面能把一个库外名字写进存档。四项都回到「跟默认」时整条记录删掉（表越干净越好查）。
     *
     * @return 写入成功才 true（组哨兵 / 素材非法 → false）
     */
    public static boolean setZhajiGroupTone(ServerLevel level, long groupKey, String which, String audioId) {
        if (groupKey == ZHAJI_GROUP_NONE || !zhajiAudioValid(level, which, audioId)) {
            return false;
        }
        String id = EscalatorSpeedData.normalizeZhajiToneAudio(audioId);
        updateZhajiGroup(level, groupKey, t -> t.withAudio(zhajiWhich(which), id));
        return true;
    }

    /** 【09-30 续】石斧界面：给**一组闸机**的某一侧写音量（{@code null} 走 {@link #clearZhajiGroupVolume}）。 */
    public static void setZhajiGroupVolume(ServerLevel level, long groupKey, String which, int volume) {
        if (groupKey == ZHAJI_GROUP_NONE) {
            return;
        }
        int v = EscalatorSpeedData.clampZhajiVolume(volume);
        updateZhajiGroup(level, groupKey, t -> t.withVolume(zhajiWhich(which), v));
    }

    /** 【09-30 续】把某组某一侧的音量恢复成「跟维度默认」（界面把输入框清空时用）。 */
    public static void clearZhajiGroupVolume(ServerLevel level, long groupKey, String which) {
        if (groupKey == ZHAJI_GROUP_NONE) {
            return;
        }
        updateZhajiGroup(level, groupKey, t -> t.withVolume(zhajiWhich(which), null));
    }

    /**
     * 改一条「一组闸机」的记录：改完为「等于没设置过」就整条删掉，否则写回。
     * 与屏蔽门 {@code updateDoor} 是同一套写法（先取旧的、用它派生新的，绝不重建整条）。
     */
    private static void updateZhajiGroup(ServerLevel level, long groupKey,
                                         java.util.function.UnaryOperator<EscalatorSpeedData.ZhajiTone> fn) {
        EscalatorSpeedData data = getServerData(level);
        EscalatorSpeedData.ZhajiTone old = data.zhajiTone.get(groupKey);
        if (old == null) {
            old = EscalatorSpeedData.ZhajiTone.NONE;
        }
        EscalatorSpeedData.ZhajiTone now = fn.apply(old);
        if (now.isEmpty()) {
            data.zhajiTone.remove(groupKey);
        } else {
            data.zhajiTone.put(groupKey, now);
        }
        data.setDirty();
    }

    // --- 写（本维度） --------------------------------------------------

    /**
     * 校验 {@code audioId} 是不是这一侧闸机分类里**合法**的取值。
     *
     * <p>合法 = {@code default}（跟 MTR 内置）/ {@code off}（这一侧不播）/ 该分类音频库里已导入的 id。
     * 与 {@link #setServerLiftTone} 同一套判据，只是闸机没有 {@code -c/-m/-s} 那几个内置别名。
     */
    public static boolean zhajiAudioValid(ServerLevel level, String which, String audioId) {
        String id = EscalatorSpeedData.normalizeZhajiToneAudio(audioId);
        return EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(id)
                || EscalatorSpeedData.ZHAJI_TONE_OFF.equals(id)
                || categoryAudioNames(getServerData(level), zhajiToneCategory(which)).contains(id);
    }

    /**
     * {@code /zhaji in|out <名字>}：只改**本维度**这一侧的素材。
     *
     * @return 素材不存在 → false（不动存档）
     */
    public static boolean setZhajiToneAudio(ServerLevel level, String which, String audioId) {
        if (!zhajiAudioValid(level, which, audioId)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        setServerZhajiToneAudio(data, which, audioId);
        data.setDirty();
        return true;
    }

    /** {@code /zhaji in|out <X> to <Y>}：本维度这一侧素材正好是 X 时才改成 Y。 */
    public static boolean replaceZhajiToneAudio(ServerLevel level, String which, String from, String to) {
        if (!zhajiAudioValid(level, which, to)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        if (!java.util.Objects.equals(serverZhajiToneAudio(data, which), from)) {
            return false;
        }
        setServerZhajiToneAudio(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /zhajiloud in|out <音量>}：只改**本维度**这一侧的音量。 */
    public static void setZhajiToneVolume(ServerLevel level, String which, int volume) {
        EscalatorSpeedData data = getServerData(level);
        setServerZhajiToneVolume(data, which, volume);
        data.setDirty();
    }

    /** {@code /zhajiloud in|out <X> to <Y>}：本维度这一侧音量正好是 X 时才改成 Y。 */
    public static boolean replaceZhajiToneVolume(ServerLevel level, String which, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (serverZhajiToneVolume(data, which) != from) {
            return false;
        }
        setServerZhajiToneVolume(data, which, to);
        data.setDirty();
        return true;
    }

    // --- 写（所有维度，`-f`） -----------------------------------------

    /**
     * {@code /zhaji -f in|out <名字>}：把**所有维度**这一侧的素材都设成它。
     *
     * @return 实际被改动的维度数（已经是那个值的维度不算）
     */
    public static int setZhajiToneAudioAll(MinecraftServer server, String which, String audioId) {
        String id = EscalatorSpeedData.normalizeZhajiToneAudio(audioId);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (!id.equals(serverZhajiToneAudio(data, which))) {
                setServerZhajiToneAudio(data, which, id);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /zhaji -f in|out <X> to <Y>}：所有维度里这一侧素材正好是 X 的那些改成 Y。 */
    public static int replaceZhajiToneAudioAll(MinecraftServer server, String which, String from, String to) {
        String target = EscalatorSpeedData.normalizeZhajiToneAudio(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (java.util.Objects.equals(serverZhajiToneAudio(data, which), from)) {
                setServerZhajiToneAudio(data, which, target);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /zhajiloud -f in|out <音量>}：把**所有维度**这一侧的音量都设成它。
     *
     * @return 实际被改动的维度数
     */
    public static int setZhajiToneVolumeAll(MinecraftServer server, String which, int volume) {
        int v = EscalatorSpeedData.clampZhajiVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverZhajiToneVolume(data, which) != v) {
                setServerZhajiToneVolume(data, which, v);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /zhajiloud -f in|out <X> to <Y>}：所有维度里这一侧音量正好是 X 的那些改成 Y。 */
    public static int replaceZhajiToneVolumeAll(MinecraftServer server, String which, int from, int to) {
        int v = EscalatorSpeedData.clampZhajiVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverZhajiToneVolume(data, which) == from) {
                setServerZhajiToneVolume(data, which, v);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // --- 客户端镜像：本地立即翻（UI / 指令回执之后立刻回显，服务端权威值随后覆盖） ---

    /**
     * 【09-30 续】石斧界面改完**这一组**的素材后本地立即改镜像（服务端随后权威同步覆盖）。
     *
     * <p>落值逻辑与服务端 {@link #updateZhajiGroup} 同一套：四项都回到「跟默认」→ 整条删掉。
     *
     * <p>★ 为什么没有「改维度默认层」的本地回显：那一层**只有指令**改（服务端权威），
     * 界面改的全是逐组层 —— 留着两个没人调用的 default 回显方法只会误导后来的人。
     */
    public static void applyClientZhajiGroupToneLocal(ResourceKey<Level> dimension, long groupKey,
                                                      String which, String audioId) {
        applyClientZhajiGroupLocal(dimension, groupKey,
                t -> t.withAudio(zhajiWhich(which), EscalatorSpeedData.normalizeZhajiToneAudio(audioId)));
    }

    /** 【09-30 续】石斧界面改完**这一组**的音量后本地立即改镜像；{@code null} = 恢复「跟维度默认」。 */
    public static void applyClientZhajiGroupVolumeLocal(ResourceKey<Level> dimension, long groupKey,
                                                        String which, Integer volume) {
        Integer v = volume == null ? null : EscalatorSpeedData.clampZhajiVolume(volume);
        applyClientZhajiGroupLocal(dimension, groupKey, t -> t.withVolume(zhajiWhich(which), v));
    }

    /** 逐组镜像的唯一写入口（本地翻与服务端同步覆盖都走它，免得两处各写一遍而分叉）。 */
    private static void applyClientZhajiGroupLocal(ResourceKey<Level> dimension, long groupKey,
                                                   java.util.function.UnaryOperator<EscalatorSpeedData.ZhajiTone> fn) {
        if (groupKey == ZHAJI_GROUP_NONE) {
            return;
        }
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        EscalatorSpeedData.ZhajiTone old = data.zhajiTone.get(groupKey);
        if (old == null) {
            old = EscalatorSpeedData.ZhajiTone.NONE;
        }
        EscalatorSpeedData.ZhajiTone now = fn.apply(old);
        if (now.isEmpty()) {
            data.zhajiTone.remove(groupKey);
        } else {
            data.zhajiTone.put(groupKey, now);
        }
    }

    // --- 同步包（独立一条通道） ---------------------------------------

    /**
     * 打包「一个维度的闸机提示音设置」=
     * {@code dimId → in → out → inVol → outVol → 条数 N → (组键, in, out, inVol?, outVol?) × N}。
     *
     * <p>★ 读侧（{@code SmoothLiftClient} 的 ZHAJI_TONE_SYNC_CHANNEL 接收器）**必须逐格同序** ——
     * 逐组那一段的两格音量用 {@link #writeDoorOptInt}（可空）编解码，缺格 = 「跟维度默认」。
     */
    public static FriendlyByteBuf buildZhajiPacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeUtf(EscalatorSpeedData.normalizeZhajiToneAudio(data.defaultZhajiToneAudioIn), 128);
        buf.writeUtf(EscalatorSpeedData.normalizeZhajiToneAudio(data.defaultZhajiToneAudioOut), 128);
        buf.writeVarInt(data.defaultZhajiToneVolumeIn);
        buf.writeVarInt(data.defaultZhajiToneVolumeOut);
        // 【09-30 续】逐组那一段。★ 只发**非空**记录（等于没设置过的不占带宽）。
        java.util.List<Map.Entry<Long, EscalatorSpeedData.ZhajiTone>> entries = new java.util.ArrayList<>();
        for (Map.Entry<Long, EscalatorSpeedData.ZhajiTone> e : data.zhajiTone.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                entries.add(e);
            }
        }
        buf.writeVarInt(entries.size());
        for (Map.Entry<Long, EscalatorSpeedData.ZhajiTone> e : entries) {
            EscalatorSpeedData.ZhajiTone t = e.getValue();
            buf.writeLong(e.getKey());
            buf.writeUtf(EscalatorSpeedData.normalizeZhajiToneAudio(t.audioIn()), 128);
            buf.writeUtf(EscalatorSpeedData.normalizeZhajiToneAudio(t.audioOut()), 128);
            writeDoorOptInt(buf, t.volumeIn() == null ? null : EscalatorSpeedData.clampZhajiVolume(t.volumeIn()));
            writeDoorOptInt(buf, t.volumeOut() == null ? null : EscalatorSpeedData.clampZhajiVolume(t.volumeOut()));
        }
        return buf;
    }

    /** 把一个维度的闸机提示音设置发给单个玩家。 */
    public static void sendZhajiSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.ZHAJI_TONE_SYNC_CHANNEL, buildZhajiPacket(level));
    }

    /** 把所有维度的闸机提示音设置同步给所有在线玩家。 */
    public static void syncZhajiToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendZhajiSyncTo(player, level);
            }
        }
    }

    /** 客户端：收下服务端权威值，整表覆盖镜像（读侧调它，落值逻辑与本地翻共用一份口径）。 */
    public static void applyClientZhaji(ResourceKey<Level> dimension,
                                        String audioIn, String audioOut, int volumeIn, int volumeOut,
                                        Map<Long, EscalatorSpeedData.ZhajiTone> tones) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.zhajiToneAudioIn = EscalatorSpeedData.normalizeZhajiToneAudio(audioIn);
        data.zhajiToneAudioOut = EscalatorSpeedData.normalizeZhajiToneAudio(audioOut);
        data.zhajiToneVolumeIn = EscalatorSpeedData.clampZhajiVolume(volumeIn);
        data.zhajiToneVolumeOut = EscalatorSpeedData.clampZhajiVolume(volumeOut);
        // 逐组那一段是**整表覆盖**（服务端是权威）：先清再灌，否则删掉的组会留在镜像里。
        data.zhajiTone.clear();
        data.zhajiTone.putAll(tones);
    }

    // ==================================================================
    // 【1.50】列车屏蔽门（MTR PSD / APG）开关门提示音
    //
    // 与上面【1.42】~【1.48】直梯那一整套**一一对应**（同样的「维度默认 + 可选方块粒度」两层，
    // 同样的 to / -f 四种取值语义），区别只有三点：
    //   1) 项从三项（up/down/chime）变成两项（open/close）；
    //   2) 直梯**只能**按维度默认（没有方块粒度），屏蔽门是「维度默认 + 每扇门可覆盖」，
    //      所以多一张 {@link EscalatorSpeedData#psdToneAudio} 表；
    //   3) 没有「倍速」——用户需求里屏蔽门只有开/关/音量三样，不加多余参数。
    // 「哪些门需要发声」由客户端自己按门值跳变判（见 PsdChimePlayer），服务端只管设置。
    // ==================================================================

    /**
     * 【1.50】一扇屏蔽门的 key：**门的锚点坐标**。客户端算好之后随「设素材」的包发过来，
     * 服务端只把 long 当不透明键存（与直梯的 {@link #liftToneKey} 同一手法）。
     */
    public static long psdToneKey(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    // ------------------------------------------------------------------
    // 总开关（/pbmmusic）：维度默认一层，-f = 所有维度
    // ------------------------------------------------------------------

    /** 【1.50】本维度屏蔽门提示音总开关。客户端读镜像、服务端读 SavedData。 */
    public static boolean isPsdHelpEnabled(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null || data.psdHelp;
        }
        return getServerData((ServerLevel) level).defaultPsdHelp;
    }

    /** {@code /pbmmusic <on|off>}：只改**本维度**的总开关。 */
    public static void setDefaultPsdHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdHelp = enabled;
        data.setDirty();
    }

    /** {@code /pbmmusic <X> to <Y>}：本维度总开关正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdHelp(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultPsdHelp != from) {
            return false;
        }
        data.defaultPsdHelp = to;
        data.setDirty();
        return true;
    }

    /** {@code /pbmmusic -f <on|off>}：**所有维度**的总开关都设成该值；返回被改动的维度数。 */
    public static int setDefaultPsdHelpAll(MinecraftServer server, boolean enabled) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdHelp != enabled) {
                data.defaultPsdHelp = enabled;
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   设值：抹回跟维度默认（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.help() == null ? null : t.withHelp(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmmusic -f <X> to <Y>}：所有维度里总开关正好是 X 的那些改成 Y。 */
    public static int replaceDefaultPsdHelpAll(MinecraftServer server, boolean from, boolean to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdHelp == from) {
                data.defaultPsdHelp = to;
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   条件：正好是 X 的改成 Y（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.help() == null || t.help() != from ? null : t.withHelp(to))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 两项独立子开关（/pbmmusic open|close）
    // ------------------------------------------------------------------

    private static boolean serverPsdToneEnabled(EscalatorSpeedData data, String which) {
        return "open".equals(which) ? data.defaultPsdToneOpenEnabled : data.defaultPsdToneCloseEnabled;
    }

    private static void setServerPsdToneEnabled(EscalatorSpeedData data, String which, boolean enabled) {
        if ("open".equals(which)) {
            data.defaultPsdToneOpenEnabled = enabled;
        } else {
            data.defaultPsdToneCloseEnabled = enabled;
        }
    }

    /**
     * 【1.50】这一项（open / close）在本维度**生效**的子开关。
     * 客户端读镜像、服务端读 SavedData。{@code which} 不认识 → false（宁可不响，也别乱响）。
     */
    public static boolean isPsdToneEnabled(Level level, String which) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return false;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return switch (which) {
                case "open" -> data == null || data.psdToneOpenEnabled;
                default -> data == null || data.psdToneCloseEnabled;
            };
        }
        return serverPsdToneEnabled(getServerData((ServerLevel) level), which);
    }

    /** {@code /pbmmusic open|close <on|off>}：只改**本维度**这一项的子开关。 */
    public static void setDefaultPsdToneEnabled(ServerLevel level, String which, boolean enabled) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return;
        }
        EscalatorSpeedData data = getServerData(level);
        setServerPsdToneEnabled(data, which, enabled);
        data.setDirty();
    }

    /** {@code /pbmmusic open|close <X> to <Y>}：本维度这一项子开关正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdToneEnabled(ServerLevel level, String which, boolean from, boolean to) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        if (serverPsdToneEnabled(data, which) != from) {
            return false;
        }
        setServerPsdToneEnabled(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /pbmmusic -f open|close <on|off>}：**所有维度**这一项子开关都设成该值。 */
    public static int setDefaultPsdToneEnabledAll(MinecraftServer server, String which, boolean enabled) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return 0;
        }
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (serverPsdToneEnabled(data, which) != enabled) {
                setServerPsdToneEnabled(data, which, enabled);
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   设值：这一项子开关抹回跟维度默认（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openEnabled() : t.closeEnabled()) == null
                          ? null
                          : t.withToneEnabled(which, null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmmusic -f open|close <X> to <Y>}：所有维度里这一项子开关正好是 X 的改成 Y。 */
    public static int replaceDefaultPsdToneEnabledAll(MinecraftServer server, String which,
                                                      boolean from, boolean to) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return 0;
        }
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (serverPsdToneEnabled(data, which) == from) {
                setServerPsdToneEnabled(data, which, to);
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   条件：这一项子开关正好是 X 的改成 Y（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openEnabled() : t.closeEnabled()) == null
                          || ("open".equals(which) ? t.openEnabled() : t.closeEnabled()) != from
                          ? null
                          : t.withToneEnabled(which, to))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 共用默认音量（/pbmloud）
    // ------------------------------------------------------------------

    /** 【1.50】本维度屏蔽门提示音的**共用默认**音量（1~1000）。 */
    public static int getPsdHelpVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_HELP_VOLUME : data.psdHelpVolume;
        }
        return getServerData((ServerLevel) level).defaultPsdHelpVolume;
    }

    /** {@code /pbmloud <音量>}：只改**本维度**的共用默认音量。 */
    public static void setDefaultPsdHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        data.setDirty();
    }

    /** {@code /pbmloud <X> to <Y>}：本维度共用默认音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdHelpVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultPsdHelpVolume != from) {
            return false;
        }
        data.defaultPsdHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(to);
        data.setDirty();
        return true;
    }

    /** {@code /pbmloud -f <音量>}：**所有维度**的共用默认音量都设成该值。 */
    public static int setDefaultPsdHelpVolumeAll(MinecraftServer server, int volume) {
        int target = EscalatorSpeedData.clampLiftHelpVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdHelpVolume != target) {
                data.defaultPsdHelpVolume = target;
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   设值：音量抹回跟维度默认（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.volume() == null ? null : t.withVolume(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmloud -f <X> to <Y>}：所有维度里共用默认音量正好是 X 的那些改成 Y。 */
    public static int replaceDefaultPsdHelpVolumeAll(MinecraftServer server, int from, int to) {
        int target = EscalatorSpeedData.clampLiftHelpVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdHelpVolume == from) {
                data.defaultPsdHelpVolume = target;
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   条件：音量正好是 X 的改成 Y（已 clamp 的 target）（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.volume() == null || t.volume() != from ? null : t.withVolume(target))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【1.22】到站播报 / 进站报站各自那一项的音量
    //   （/pbmmidiumloud、/pbmarriveloud）—— -1 = 跟随共用默认
    // ------------------------------------------------------------------

    /** 维度默认层：这一项自己设的音量（-1 = 跟随共用默认）。 */
    private static int serverPsdItemVolume(EscalatorSpeedData data, String which) {
        return "midium".equals(which) ? data.defaultPsdMidiumVolume : data.defaultPsdArriveVolume;
    }

    private static void setServerPsdItemVolume(EscalatorSpeedData data, String which, int volume) {
        if ("midium".equals(which)) {
            data.defaultPsdMidiumVolume = EscalatorSpeedData.clampPsdToneVolume(volume);
        } else {
            data.defaultPsdArriveVolume = EscalatorSpeedData.clampPsdToneVolume(volume);
        }
    }

    /** 【1.22】这一项维度默认层**生效**的音量（-1 → 跟随共用默认）。 */
    public static int getPsdItemVolume(Level level, String which) {
        int own;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                own = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
            } else {
                own = "midium".equals(which) ? data.psdMidiumVolume : data.psdArriveVolume;
            }
        } else {
            own = serverPsdItemVolume(getServerData((ServerLevel) level), which);
        }
        return own == EscalatorSpeedData.PSD_TONE_VOLUME_UNSET ? getPsdHelpVolume(level) : own;
    }

    /** 【1.22】到站播报这一项**生效**的音量。 */
    public static int getPsdMidiumVolume(Level level) {
        return getPsdItemVolume(level, "midium");
    }

    /** 【1.22】进站报站这一项**生效**的音量。 */
    public static int getPsdArriveVolume(Level level) {
        return getPsdItemVolume(level, "arrive");
    }

    /** 【1.22】到站播报这一项有没有**单独调过**音量。 */
    public static boolean hasOwnPsdItemVolume(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            return ("midium".equals(which) ? data.psdMidiumVolume : data.psdArriveVolume)
                    != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        }
        return serverPsdItemVolume(getServerData((ServerLevel) level), which)
                != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
    }

    /** {@code /pbmmidiumloud <音量>}：只改本维度到站播报的音量。 */
    public static void setDefaultPsdMidiumVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        setServerPsdItemVolume(data, "midium", volume);
        data.setDirty();
    }

    /** {@code /pbmarriveloud <音量>}：只改本维度进站报站的音量。 */
    public static void setDefaultPsdArriveVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        setServerPsdItemVolume(data, "arrive", volume);
        data.setDirty();
    }

    private static boolean replaceDefaultPsdItemVolume(ServerLevel level, String which, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (serverPsdItemVolume(data, which) != from) {
            return false;
        }
        setServerPsdItemVolume(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /pbmmidiumloud <X> to <Y>}：本维度到站播报音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdMidiumVolume(ServerLevel level, int from, int to) {
        return replaceDefaultPsdItemVolume(level, "midium", from, to);
    }

    /** {@code /pbmarriveloud <X> to <Y>}：本维度进站报站音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdArriveVolume(ServerLevel level, int from, int to) {
        return replaceDefaultPsdItemVolume(level, "arrive", from, to);
    }

    /** {@code /pbmmidiumloud -f <音量>}：**所有维度**到站播报音量都设成该值。 */
    public static int setDefaultPsdMidiumVolumeAll(MinecraftServer server, int volume) {
        int target = EscalatorSpeedData.clampPsdToneVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdMidiumVolume != target) {
                data.defaultPsdMidiumVolume = target;
                touched = true;
            }
            // -f = 「修改全部」：这一项的「按扇门单独设置」也一起抹掉（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.midiumVolume() == null ? null : t.withMidiumVolume(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmarriveloud -f <音量>}：**所有维度**进站报站音量都设成该值。 */
    public static int setDefaultPsdArriveVolumeAll(MinecraftServer server, int volume) {
        int target = EscalatorSpeedData.clampPsdToneVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdArriveVolume != target) {
                data.defaultPsdArriveVolume = target;
                touched = true;
            }
            if (remapPsdDoorOverrides(data, t -> t.arriveVolume() == null ? null : t.withArriveVolume(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmmidiumloud -f <X> to <Y>}：所有维度里到站播报音量正好是 X 的改成 Y。 */
    public static int replaceDefaultPsdMidiumVolumeAll(MinecraftServer server, int from, int to) {
        int target = EscalatorSpeedData.clampPsdToneVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdMidiumVolume == from) {
                data.defaultPsdMidiumVolume = target;
                touched = true;
            }
            if (remapPsdDoorOverrides(data, t -> t.midiumVolume() == null || t.midiumVolume() != from
                    ? null : t.withMidiumVolume(target))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmarriveloud -f <X> to <Y>}：所有维度里进站报站音量正好是 X 的改成 Y。 */
    public static int replaceDefaultPsdArriveVolumeAll(MinecraftServer server, int from, int to) {
        int target = EscalatorSpeedData.clampPsdToneVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdArriveVolume == from) {
                data.defaultPsdArriveVolume = target;
                touched = true;
            }
            if (remapPsdDoorOverrides(data, t -> t.arriveVolume() == null || t.arriveVolume() != from
                    ? null : t.withArriveVolume(target))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 两项各自的音量（/pbmloud open|close）—— -1 = 跟随共用默认
    // ------------------------------------------------------------------

    private static int serverPsdToneVolume(EscalatorSpeedData data, String which) {
        return "open".equals(which) ? data.defaultPsdToneVolumeOpen : data.defaultPsdToneVolumeClose;
    }

    private static void setServerPsdToneVolume(EscalatorSpeedData data, String which, int volume) {
        if ("open".equals(which)) {
            data.defaultPsdToneVolumeOpen = EscalatorSpeedData.clampPsdToneVolume(volume);
        } else {
            data.defaultPsdToneVolumeClose = EscalatorSpeedData.clampPsdToneVolume(volume);
        }
    }

    /** 【1.50】这一项**生效**的音量（单项 -1 → 跟随共用默认）。 */
    public static int getPsdToneVolume(Level level, String which) {
        int own;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                own = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
            } else {
                own = "open".equals(which) ? data.psdToneVolumeOpen : data.psdToneVolumeClose;
            }
        } else {
            own = serverPsdToneVolume(getServerData((ServerLevel) level), which);
        }
        if (own == EscalatorSpeedData.PSD_TONE_VOLUME_UNSET) {
            return getPsdHelpVolume(level); // 跟随共用默认
        }
        return own;
    }

    /** 【1.50】这一项有没有**单独调过**音量。 */
    public static boolean hasOwnPsdToneVolume(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            return "open".equals(which)
                    ? data.psdToneVolumeOpen != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET
                    : data.psdToneVolumeClose != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        }
        return serverPsdToneVolume(getServerData((ServerLevel) level), which)
                != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
    }

    /**
     * 【1.20】这一扇门这一项的音量是不是**跟随**来的（它自己与维度都没单独调过）。
     *
     * <p>给石斧 UI 那句「（跟随 N）」用：把 N 换成这一扇门**生效**的共用音量，
     * 用户看到的就是播放端真正会用的那个数。
     */
    public static boolean hasOwnDoorPsdToneVolume(Level level, long key, String which) {
        EscalatorSpeedData.PsdToneAudio t = psdDoorRecord(level, key);
        Integer own = "open".equals(which) ? t.openVolume() : t.closeVolume();
        return own != null || hasOwnPsdToneVolume(level, which);
    }

    /** {@code /pbmloud open|close <音量>}：只改**本维度**这一项的音量。 */
    public static void setDefaultPsdToneVolume(ServerLevel level, String which, int volume) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return;
        }
        EscalatorSpeedData data = getServerData(level);
        setServerPsdToneVolume(data, which, volume);
        data.setDirty();
    }

    /** {@code /pbmloud open|close <X> to <Y>}：本维度这一项音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdToneVolume(ServerLevel level, String which, int from, int to) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        if (serverPsdToneVolume(data, which) != from) {
            return false;
        }
        setServerPsdToneVolume(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /pbmloud -f open|close <音量>}：**所有维度**这一项音量都设成该值。 */
    public static int setDefaultPsdToneVolumeAll(MinecraftServer server, String which, int volume) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return 0;
        }
        int clamped = EscalatorSpeedData.clampPsdToneVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (serverPsdToneVolume(data, which) != clamped) {
                setServerPsdToneVolume(data, which, clamped);
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   设值：这一项音量抹回跟维度默认（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openVolume() : t.closeVolume()) == null
                          ? null
                          : t.withToneVolume(which, null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmloud -f open|close <X> to <Y>}：所有维度里这一项音量正好是 X 的改成 Y。 */
    public static int replaceDefaultPsdToneVolumeAll(MinecraftServer server, String which, int from, int to) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return 0;
        }
        int clamped = EscalatorSpeedData.clampPsdToneVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (serverPsdToneVolume(data, which) == from) {
                setServerPsdToneVolume(data, which, clamped);
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   条件：这一项音量正好是 X 的改成 Y（已 clamp 的 clamped）（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openVolume() : t.closeVolume()) == null
                          || ("open".equals(which) ? t.openVolume() : t.closeVolume()) != from
                          ? null
                          : t.withToneVolume(which, clamped))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 可闻范围（/pbmround）
    // ------------------------------------------------------------------

    /** 【09-29】本维度屏蔽门提示音的可闻范围（格）——水平分量。客户端读镜像。 */
    public static int getPsdHelpRoundXz(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_XZ;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_XZ : data.psdHelpRoundXz;
        }
        return getServerData((ServerLevel) level).defaultPsdHelpRoundXz;
    }

    /** 【09-29】本维度屏蔽门提示音的可闻范围（格）——垂直分量。客户端读镜像。 */
    public static int getPsdHelpRoundY(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_Y;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_Y : data.psdHelpRoundY;
        }
        return getServerData((ServerLevel) level).defaultPsdHelpRoundY;
    }

    /** {@code /pbmround <水平> <垂直>}：只改**本维度**的范围。 */
    public static void setDefaultPsdHelpRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdHelpRoundXz = EscalatorSpeedData.clampPsdHelpRound(xz);
        data.defaultPsdHelpRoundY = EscalatorSpeedData.clampPsdHelpRound(y);
        data.setDirty();
    }

    /** {@code /pbmround <Xz> <Y> to <新Xz> <新Y>}：本维度范围两维都正好时才改成新值。 */
    public static boolean replaceDefaultPsdHelpRound(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultPsdHelpRoundXz != fromXz || data.defaultPsdHelpRoundY != fromY) {
            return false;
        }
        data.defaultPsdHelpRoundXz = EscalatorSpeedData.clampPsdHelpRound(toXz);
        data.defaultPsdHelpRoundY = EscalatorSpeedData.clampPsdHelpRound(toY);
        data.setDirty();
        return true;
    }

    /** {@code /pbmround -f <水平> <垂直>}：**所有维度**都设成该范围。 */
    public static int setDefaultPsdHelpRoundAll(MinecraftServer server, int xz, int y) {
        int clampedXz = EscalatorSpeedData.clampPsdHelpRound(xz);
        int clampedY = EscalatorSpeedData.clampPsdHelpRound(y);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdHelpRoundXz != clampedXz || data.defaultPsdHelpRoundY != clampedY) {
                data.defaultPsdHelpRoundXz = clampedXz;
                data.defaultPsdHelpRoundY = clampedY;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmround -f <Xz> <Y> to <新Xz> <新Y>}：所有维度里两维都正好时改成新值。 */
    public static int replaceDefaultPsdHelpRoundAll(MinecraftServer server, int fromXz, int fromY, int toXz, int toY) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdHelpRoundXz == fromXz && data.defaultPsdHelpRoundY == fromY) {
                data.defaultPsdHelpRoundXz = EscalatorSpeedData.clampPsdHelpRound(toXz);
                data.defaultPsdHelpRoundY = EscalatorSpeedData.clampPsdHelpRound(toY);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【1.23】到站播报 / 进站报站各自的可闻范围（/pbmmidiumround、/pbmarriveround）
    //
    //   与 /pbmround 完全同构（本维度一份、可 -f 推所有维度、可 X to Y 条件替换），
    //   只是读写的是**另外两组**字段。★ 三条指令共用同一套 handler（按种类参数化），
    //   所以这里也必须三个种类各有一组方法 —— 少一组就是「指令敲得响、值没落盘」。
    //
    //   ★ 为什么不在数据层用一个「按种类取字段」的小工具（反射 / switch）省掉这十份：
    //   那种写法会让「谁改哪个字段」在编译期不可见，而这里正是最需要一眼看懂的地方。
    //   十份样板换来「加第四类声音时编译器会逼你把十处补齐」。
    // ------------------------------------------------------------------

    /** 【09-29】本维度「到站播报」的可闻范围（格）——水平分量。客户端读镜像。 */
    public static int getPsdMidiumRoundXz(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_XZ;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_XZ : data.psdMidiumRoundXz;
        }
        return getServerData((ServerLevel) level).defaultPsdMidiumRoundXz;
    }

    /** 【09-29】本维度「到站播报」的可闻范围（格）——垂直分量。客户端读镜像。 */
    public static int getPsdMidiumRoundY(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_Y;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_Y : data.psdMidiumRoundY;
        }
        return getServerData((ServerLevel) level).defaultPsdMidiumRoundY;
    }

    /** 【09-29】本维度「进站报站」的可闻范围（格）——水平分量。客户端读镜像。 */
    public static int getPsdArriveRoundXz(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_XZ;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_XZ : data.psdArriveRoundXz;
        }
        return getServerData((ServerLevel) level).defaultPsdArriveRoundXz;
    }

    /** 【09-29】本维度「进站报站」的可闻范围（格）——垂直分量。客户端读镜像。 */
    public static int getPsdArriveRoundY(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_Y;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_Y : data.psdArriveRoundY;
        }
        return getServerData((ServerLevel) level).defaultPsdArriveRoundY;
    }

    /** {@code /pbmmidiumround <水平> <垂直>}：只改**本维度**的范围。 */
    public static void setDefaultPsdMidiumRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdMidiumRoundXz = EscalatorSpeedData.clampPsdMidiumRound(xz);
        data.defaultPsdMidiumRoundY = EscalatorSpeedData.clampPsdMidiumRound(y);
        data.setDirty();
    }

    /** {@code /pbmarriveround <水平> <垂直>}：只改**本维度**的范围。 */
    public static void setDefaultPsdArriveRound(ServerLevel level, int xz, int y) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdArriveRoundXz = EscalatorSpeedData.clampPsdArriveRound(xz);
        data.defaultPsdArriveRoundY = EscalatorSpeedData.clampPsdArriveRound(y);
        data.setDirty();
    }

    /** {@code /pbmmidiumround <Xz> <Y> to <新Xz> <新Y>}：本维度范围两维都正好时才改成新值。 */
    public static boolean replaceDefaultPsdMidiumRound(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultPsdMidiumRoundXz != fromXz || data.defaultPsdMidiumRoundY != fromY) {
            return false;
        }
        data.defaultPsdMidiumRoundXz = EscalatorSpeedData.clampPsdMidiumRound(toXz);
        data.defaultPsdMidiumRoundY = EscalatorSpeedData.clampPsdMidiumRound(toY);
        data.setDirty();
        return true;
    }

    /** {@code /pbmarriveround <Xz> <Y> to <新Xz> <新Y>}：本维度范围两维都正好时才改成新值。 */
    public static boolean replaceDefaultPsdArriveRound(ServerLevel level, int fromXz, int fromY, int toXz, int toY) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultPsdArriveRoundXz != fromXz || data.defaultPsdArriveRoundY != fromY) {
            return false;
        }
        data.defaultPsdArriveRoundXz = EscalatorSpeedData.clampPsdArriveRound(toXz);
        data.defaultPsdArriveRoundY = EscalatorSpeedData.clampPsdArriveRound(toY);
        data.setDirty();
        return true;
    }

    /** {@code /pbmmidiumround -f <水平> <垂直>}：**所有维度**都设成该范围（返回改动个数）。 */
    public static int setDefaultPsdMidiumRoundAll(MinecraftServer server, int xz, int y) {
        int clampedXz = EscalatorSpeedData.clampPsdMidiumRound(xz);
        int clampedY = EscalatorSpeedData.clampPsdMidiumRound(y);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdMidiumRoundXz != clampedXz || data.defaultPsdMidiumRoundY != clampedY) {
                data.defaultPsdMidiumRoundXz = clampedXz;
                data.defaultPsdMidiumRoundY = clampedY;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmarriveround -f <水平> <垂直>}：**所有维度**都设成该范围（返回改动个数）。 */
    public static int setDefaultPsdArriveRoundAll(MinecraftServer server, int xz, int y) {
        int clampedXz = EscalatorSpeedData.clampPsdArriveRound(xz);
        int clampedY = EscalatorSpeedData.clampPsdArriveRound(y);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdArriveRoundXz != clampedXz || data.defaultPsdArriveRoundY != clampedY) {
                data.defaultPsdArriveRoundXz = clampedXz;
                data.defaultPsdArriveRoundY = clampedY;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmmidiumround -f <Xz> <Y> to <新Xz> <新Y>}：所有维度里两维都正好时改成新值。 */
    public static int replaceDefaultPsdMidiumRoundAll(MinecraftServer server, int fromXz, int fromY, int toXz, int toY) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdMidiumRoundXz == fromXz && data.defaultPsdMidiumRoundY == fromY) {
                data.defaultPsdMidiumRoundXz = EscalatorSpeedData.clampPsdMidiumRound(toXz);
                data.defaultPsdMidiumRoundY = EscalatorSpeedData.clampPsdMidiumRound(toY);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmarriveround -f <Xz> <Y> to <新Xz> <新Y>}：所有维度里两维都正好时改成新值。 */
    public static int replaceDefaultPsdArriveRoundAll(MinecraftServer server, int fromXz, int fromY, int toXz, int toY) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdArriveRoundXz == fromXz && data.defaultPsdArriveRoundY == fromY) {
                data.defaultPsdArriveRoundXz = EscalatorSpeedData.clampPsdArriveRound(toXz);
                data.defaultPsdArriveRoundY = EscalatorSpeedData.clampPsdArriveRound(toY);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【1.16】关门提示音强制等待时长（/pbmclosewait，秒）
    //
    //   与 /pbmround 同构：本维度一份、可 -f 推所有维度、可 X to Y 条件替换。
    //   语义见 EscalatorSpeedData#defaultPsdCloseWaitSeconds。
    // ------------------------------------------------------------------

    /** 【1.16】本维度关门提示音的强制等待时长（秒）。客户端读镜像。 */
    public static int getPsdCloseWaitSeconds(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS
                    : data.psdCloseWaitSeconds;
        }
        return getServerData((ServerLevel) level).defaultPsdCloseWaitSeconds;
    }

    /** {@code /pbmclosewait <秒>}：只改**本维度**。 */
    public static void setDefaultPsdCloseWaitSeconds(ServerLevel level, int seconds) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(seconds);
        data.setDirty();
    }

    /** {@code /pbmclosewait <X> to <Y>}：本维度正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdCloseWaitSeconds(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultPsdCloseWaitSeconds != from) {
            return false;
        }
        data.defaultPsdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(to);
        data.setDirty();
        return true;
    }

    /** {@code /pbmclosewait -f <秒>}：**所有维度**都设成该值。 */
    public static int setDefaultPsdCloseWaitSecondsAll(MinecraftServer server, int seconds) {
        int clamped = EscalatorSpeedData.clampPsdCloseWaitSeconds(seconds);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdCloseWaitSeconds != clamped) {
                data.defaultPsdCloseWaitSeconds = clamped;
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   设值：强制等待秒数抹回跟维度默认（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.closeWaitSeconds() == null ? null : t.withCloseWaitSeconds(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmclosewait -f <X> to <Y>}：所有维度里正好是 X 的那些改成 Y。 */
    public static int replaceDefaultPsdCloseWaitSecondsAll(MinecraftServer server, int from, int to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdCloseWaitSeconds == from) {
                data.defaultPsdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(to);
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   条件：强制等待秒数正好是 X 的改成 Y（clamp）（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> t.closeWaitSeconds() == null || t.closeWaitSeconds() != from
                          ? null
                          : t.withCloseWaitSeconds(EscalatorSpeedData.clampPsdCloseWaitSeconds(to)))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【1.17】到站播报（/pbmmidium <名字> <秒>）
    //
    //   与 /pbmclosewait 同构（本维度一份 + 可 -f 推所有维度 + 可 X to Y 条件替换），
    //   多一个「素材」维度。语义见 EscalatorSpeedData#defaultPsdMidiumAudio。
    // ------------------------------------------------------------------

    /** 【1.17】本维度到站播报的素材 id（{@code off} = 不播）。客户端读镜像。 */
    public static String getPsdMidiumAudio(Level level) {
        if (level == null) {
            return EscalatorSpeedData.PSD_MIDIUM_OFF;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.PSD_MIDIUM_OFF : data.psdMidiumAudio;
        }
        return getServerData((ServerLevel) level).defaultPsdMidiumAudio;
    }

    /** 【1.17】本维度到站播报的等待秒数（0 ~ +∞）。客户端读镜像。 */
    public static int getPsdMidiumWaitSeconds(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS
                    : data.psdMidiumWaitSeconds;
        }
        return getServerData((ServerLevel) level).defaultPsdMidiumWaitSeconds;
    }

    /** 【1.21】本维度进站报站的素材 id（{@code off} = 不播）。客户端读镜像。 */
    public static String getPsdArriveAudio(Level level) {
        if (level == null) {
            return EscalatorSpeedData.PSD_ARRIVE_OFF;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.PSD_ARRIVE_OFF : data.psdArriveAudio;
        }
        return getServerData((ServerLevel) level).defaultPsdArriveAudio;
    }

    /** 【1.21】本维度进站报站的秒数（(-∞, 0]：最近一班车还剩 |X| 秒到站时起播）。客户端读镜像。 */
    public static int getPsdArriveSeconds(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS
                    : data.psdArriveSeconds;
        }
        return getServerData((ServerLevel) level).defaultPsdArriveSeconds;
    }

    /** 【1.21】{@code /pbmarrive <名字> <X>}：只改**本维度**。 */
    public static void setDefaultPsdArrive(ServerLevel level, String audioId, int seconds) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdArriveAudio = EscalatorSpeedData.normalizePsdArriveAudio(audioId);
        data.defaultPsdArriveSeconds = EscalatorSpeedData.clampPsdArriveSeconds(seconds);
        data.setDirty();
    }

    /** 【09-28】本维度「进站广播（讲述人）」的**维度默认样式**（0/1/2）。客户端读镜像。 */
    public static int getPsdNarrateMode(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_NARRATE_MODE;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_PSD_NARRATE_MODE : data.psdNarrateMode;
        }
        return getServerData((ServerLevel) level).defaultPsdNarrateMode;
    }

    /**
     * 【09-28】本维度的讲述人**是不是开着**（样式 ≠ 关闭）。
     *
     * <p>★ 只在「只需要知道开/关」的地方用（例如日志、UI 的状态行）；要**选播报词**就必须用
     * {@link #getPsdNarrateMode} / {@link #getDoorPsdNarrateMode} —— 开/关 这一位丢掉了
     * 「上海还是香港」，拿它去选句式会把香港档也念成上海词。
     */
    public static boolean isPsdNarrateOn(Level level) {
        return getPsdNarrateMode(level) != EscalatorSpeedData.PSD_NARRATE_OFF;
    }

    /** 【09-28】本维度「进站广播（讲述人）」的**维度默认**秒数（(-∞, 0]）。客户端读镜像。 */
    public static int getPsdNarrateSeconds(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_NARRATE_SECONDS;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_PSD_NARRATE_SECONDS
                    : data.psdNarrateSeconds;
        }
        return getServerData((ServerLevel) level).defaultPsdNarrateSeconds;
    }

    /** 【09-28】「同步所有」弹窗：只改**本维度**的讲述人维度默认（样式 + 秒数）。 */
    public static void setDefaultPsdNarrate(ServerLevel level, int mode, int seconds) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdNarrateMode = EscalatorSpeedData.clampPsdNarrateMode(mode);
        data.defaultPsdNarrateSeconds = EscalatorSpeedData.clampPsdNarrateSeconds(seconds);
        data.setDirty();
    }

    /** 【09-28】「强制同步」：讲述人维度默认写给**所有维度**，并抹掉按串覆盖（{@code -f} 口径）。 */
    public static int setDefaultPsdNarrateAll(MinecraftServer server, int mode, int seconds) {
        int clamped = EscalatorSpeedData.clampPsdNarrateMode(mode);
        int sec = EscalatorSpeedData.clampPsdNarrateSeconds(seconds);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdNarrateMode != clamped || data.defaultPsdNarrateSeconds != sec) {
                data.defaultPsdNarrateMode = clamped;
                data.defaultPsdNarrateSeconds = sec;
                touched = true;
            }
            // 【09-28】-f = 「修改全部」：这一项的「按串单独设置」也一起抹回跟维度默认。
            if (remapPsdDoorOverrides(data, t -> (t.narrate() == null && t.narrateSeconds() == null)
                          ? null
                          : t.withNarrate(null).withNarrateSeconds(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【09-28 续 2】{@code /pbmnarrate <样式> -f} 用：把**所有维度**的讲述人**样式**写成
     * {@code mode}，**每一维度的秒数一律不动**；同时抹掉按串覆盖（{@code -f} 口径）。
     *
     * <p>★ 为什么不直接调 {@link #setDefaultPsdNarrateAll}：那一个的签名要求**同时给秒数**，
     * 会把其它维度各自调好的秒数一起冲掉 —— 而 {@code /pbmnarrate} 只谈样式、不谈时间。
     * 「副产物最小」这条比「少写一个方法」重要（改门速度/音量时踩过同样的坑）。
     *
     * @return 真正被改动的维度数
     */
    public static int setDefaultPsdNarrateModeAll(MinecraftServer server, int mode) {
        int clamped = EscalatorSpeedData.clampPsdNarrateMode(mode);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdNarrateMode != clamped) {
                data.defaultPsdNarrateMode = clamped;
                touched = true;
            }
            // ★ 只抹「样式」那一格（narrate）；narrateSeconds 是另一回事，不碰。
            if (remapPsdDoorOverrides(data, t -> t.narrate() == null ? null : t.withNarrate(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【10-01】两条讲述人广播的「玩家自定义文字」—— **按存档存**（用户点名）
    //   约定同导入的 OGG：文字随存档 NBT 走，存档挪走也能找到；不进 config、不存档间互通。
    //   两条广播（进站 / 站台）各一份，互不共用。
    // ------------------------------------------------------------------

    /** 讲述人自定义文字的目标：进站广播（讲述人）。 */
    public static final int NARRATE_TEXTS_ARRIVE = 0;
    /** 讲述人自定义文字的目标：站台广播（讲述人）。 */
    public static final int NARRATE_TEXTS_MIDIUM = 1;

    /** 【10-01】本维度「进站广播（讲述人）」的自定义文字（user1…userN 模板原文，按存档）。 */
    public static List<String> getPsdNarrateUserTexts(ServerLevel level) {
        return getServerData(level).defaultPsdArriveNarrateUserTexts;
    }

    /** 【10-01】本维度「站台广播（讲述人）」的自定义文字（按存档，与进站广播分开一份）。 */
    public static List<String> getPsdMidiumNarrateUserTexts(ServerLevel level) {
        return getServerData(level).defaultPsdMidiumNarrateUserTexts;
    }

    /**
     * 【10-01】把某条讲述人广播的**整份**自定义文字写成**所有维度**（石斧讲述人页的增删改
     * 走这里：编辑页把整份列表发来 → 落库 → 全量同步回客户端）。
     *
     * <p>★ 为什么「整份替换」而不是按 index 单点改：编辑页增删后 userN 序号会整体挪动，
     * 逐条打补丁容易错位；整份覆盖天然一致（与 UI 的「保存编辑器」语义对应）。
     *
     * @param target {@link #NARRATE_TEXTS_ARRIVE} 或 {@link #NARRATE_TEXTS_MIDIUM}
     * @return 真正被改动的维度数
     */
    public static int setPsdNarrateUserTextsAll(MinecraftServer server, int target, List<String> texts) {
        List<String> cleaned = new java.util.ArrayList<>(
                texts == null ? java.util.Collections.emptyList() : texts);
        int max = EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES;
        while (cleaned.size() > max) {
            cleaned.remove(cleaned.size() - 1);
        }
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            List<String> dst = target == NARRATE_TEXTS_MIDIUM
                    ? data.defaultPsdMidiumNarrateUserTexts : data.defaultPsdArriveNarrateUserTexts;
            java.util.List<String> copy = new java.util.ArrayList<>(cleaned);
            if (!copy.equals(dst)) {
                dst.clear();
                dst.addAll(copy);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【10-01】「经典港铁预设」用：把**所有维度**的进站讲述人**秒数**写成 {@code seconds}，
     * 同时抹掉按串覆盖（{@code -f} 口径；与 {@link #setDefaultPsdNarrateModeAll} 对称 ——
     * 用户点名「经典港铁预设：进站广播默认 -20 秒、秒数也要一起设过去」）。
     *
     * @return 真正被改动的维度数
     */
    public static int setDefaultPsdNarrateLeadAll(MinecraftServer server, int seconds) {
        int sec = EscalatorSpeedData.clampPsdNarrateSeconds(seconds);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdNarrateSeconds != sec) {
                data.defaultPsdNarrateSeconds = sec;
                touched = true;
            }
            // ★ 只抹「秒数」那一格（narrateSeconds）；样式不碰 —— 预设的样式由 pbmnarrate 那条管。
            if (remapPsdDoorOverrides(data,
                    t -> t.narrateSeconds() == null ? null : t.withNarrateSeconds(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 【09-30 续 3】「站台广播（讲述人）」—— 与进站讲述人**完全同构**的一套维度默认，
    //   只是它管 pbmmidium（站台播报，开门后起念）那条链路的讲述人。档位共用 PSD_NARRATE_*。
    // ------------------------------------------------------------------

    /** 【09-30 续 3】本维度「站台广播（讲述人）」的**维度默认样式**（默认关闭）。客户端读镜像。 */
    public static int getPsdMidiumNarrateMode(Level level) {
        if (level == null) {
            return EscalatorSpeedData.PSD_NARRATE_OFF;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.PSD_NARRATE_OFF : data.psdMidiumNarrateMode;
        }
        return getServerData((ServerLevel) level).defaultPsdMidiumNarrateMode;
    }

    /** 【09-30 续 3】本维度「站台广播（讲述人）」的**维度默认**等待秒数（[0,+∞)）。客户端读镜像。 */
    public static int getPsdMidiumNarrateSeconds(Level level) {
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_PSD_NARRATE_SECONDS;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_PSD_NARRATE_SECONDS
                    : data.psdMidiumNarrateSeconds;
        }
        return getServerData((ServerLevel) level).defaultPsdMidiumNarrateSeconds;
    }

    /** 【09-30 续 3】「同步所有」弹窗（站台讲述人页）：只改**本维度**的维度默认（样式 + 秒数）。 */
    public static void setDefaultPsdMidiumNarrate(ServerLevel level, int mode, int seconds) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdMidiumNarrateMode = EscalatorSpeedData.clampPsdNarrateMode(mode);
        data.defaultPsdMidiumNarrateSeconds = EscalatorSpeedData.clampPsdMidiumNarrateSeconds(seconds);
        data.setDirty();
    }

    /** 【09-30 续 3】「强制同步」（站台讲述人页）：维度默认写给**所有维度**，并抹掉按串覆盖。 */
    public static int setDefaultPsdMidiumNarrateAll(MinecraftServer server, int mode, int seconds) {
        int clamped = EscalatorSpeedData.clampPsdNarrateMode(mode);
        int sec = EscalatorSpeedData.clampPsdMidiumNarrateSeconds(seconds);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (data.defaultPsdMidiumNarrateMode != clamped || data.defaultPsdMidiumNarrateSeconds != sec) {
                data.defaultPsdMidiumNarrateMode = clamped;
                data.defaultPsdMidiumNarrateSeconds = sec;
                touched = true;
            }
            // -f = 「修改全部」：这一项的「按串单独设置」也一起抹回跟维度默认。
            if (remapPsdDoorOverrides(data, t -> (t.midiumNarrate() == null && t.midiumNarrateSeconds() == null)
                    ? null
                    : t.withMidiumNarrate(null).withMidiumNarrateSeconds(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmmidium <名字> <秒>}：只改**本维度**。 */
    public static void setDefaultPsdMidium(ServerLevel level, String audioId, int seconds) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultPsdMidiumAudio = EscalatorSpeedData.normalizePsdMidiumAudio(audioId);
        data.defaultPsdMidiumWaitSeconds = EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds);
        data.setDirty();
    }

    /**
     * 【1.17】解析 {@code /pbmmidium} 与石斧界面的**素材名**：{@code off}/{@code none}/{@code null}
     * → {@link EscalatorSpeedData#PSD_MIDIUM_OFF}；否则必须是音频库里的名字。
     *
     * <p>与 {@link #resolveAudioName} 的差别有两条，都是刻意的：
     * <ol>
     *   <li>**不收内置名**（{@code default}/{@code default-c}/… ）—— 到站播报是玩家自己的站台广播，
     *       模组不可能随 jar 分发它，所以没有「内置」这一层；</li>
     *   <li>**文件夹里还没入库时自动导入一次** —— 玩家打一条指令不该被逼着先去界面上点两下。
     *       导入失败（文件不存在 / 不是合法 OGG）就返回 {@code null}，由调用方报错。</li>
     * </ol>
     *
     * @return 落库后的 id，或 {@code null}（没找到这个名字 = 失败）
     */
    /** 【1.28】解析 {@code /pbmmidium} 与石斧界面的**素材名**（分类 = pbm/midium）。 */
    public static String resolvePsdMidiumName(ServerLevel level, String category, String name) {
        if (EscalatorSpeedData.isPsdMidiumOff(name)) {
            return EscalatorSpeedData.PSD_MIDIUM_OFF;
        }
        EscalatorSpeedData data = getServerData(level);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return name;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            String withExt = name + ".ogg";
            if (catNames.contains(withExt)) {
                return withExt;
            }
            if (importAudioToStore(level, category, withExt) == null && catNames.contains(withExt)) {
                return withExt;
            }
        }
        if (importAudioToStore(level, category, name) == null && catNames.contains(name)) {
            return name;
        }
        return null;
    }

    /**
     * 【1.21】解析 {@code /pbmarrive} 与石斧界面的**素材名**：{@code off}/{@code none}/{@code null}
     * → {@link EscalatorSpeedData#PSD_ARRIVE_OFF}；否则必须是音频库里的名字。
     *
     * <p>与 {@link #resolvePsdMidiumName} **同一套语义**（**不收内置名**、文件夹里还没入库时
     * 自动导入一次）：进站报站和到站播报一样没有内置素材 —— 站台广播不可能随模组分发。
     * ★ 两个方法**故意分开**而不是互相调用：它们的「不播」哨兵眼下是同一个串，
     * 但将来任何一方改了语义都必须是本地改动（本仓那条「一个哨兵同时表达两件事」的教训）。
     */
    /** 【1.28】解析 {@code /pbmarrive} 与石斧界面的**素材名**（分类 = pbm/arrive）。 */
    public static String resolvePsdArriveName(ServerLevel level, String category, String name) {
        if (EscalatorSpeedData.isPsdArriveOff(name)) {
            return EscalatorSpeedData.PSD_ARRIVE_OFF;
        }
        EscalatorSpeedData data = getServerData(level);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return name;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            String withExt = name + ".ogg";
            if (catNames.contains(withExt)) {
                return withExt;
            }
            if (importAudioToStore(level, category, withExt) == null && catNames.contains(withExt)) {
                return withExt;
            }
        }
        if (importAudioToStore(level, category, name) == null && catNames.contains(name)) {
            return name;
        }
        return null;
    }

    /** 【1.21】{@code /pbmarrive <名字>} 的补全项：该分类全部名字 + {@code off}。 */
    public static List<String> psdArriveSuggestions(ServerLevel level, String category) {
        List<String> out = new ArrayList<>();
        out.add(EscalatorSpeedData.PSD_ARRIVE_OFF);
        if (level != null) {
            List<String> names = new ArrayList<>(categoryAudioNames(getServerData(level), category));
            names.sort(String::compareTo);
            out.addAll(names);
        }
        return out;
    }

    /**
     * 【1.21】从存档删除一段音频后，把**所有维度**里指向它的「进站报站」清成 {@code off}。
     *
     * <p>与 {@link #clearPsdMidiumIfRemoved} 同一条教训：删掉素材之后那个设置会变成一条
     * **指向空文件**的引用，用户按下「删除」的语义是「这段音频我不要了」。
     *
     * @return 被清掉的维度数
     */
    public static int clearPsdArriveIfRemoved(MinecraftServer server, String audioId) {
        if (audioId == null || audioId.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (audioId.equals(data.defaultPsdArriveAudio)) {
                data.defaultPsdArriveAudio = EscalatorSpeedData.PSD_ARRIVE_OFF;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** 【1.21】{@code /pbmarrive -f <名字> <X>}：**所有维度**都设成该值。 */
    public static int setDefaultPsdArriveAll(MinecraftServer server, String audioId, int seconds) {
        String id = EscalatorSpeedData.normalizePsdArriveAudio(audioId);
        int sec = EscalatorSpeedData.clampPsdArriveSeconds(seconds);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (!id.equals(data.defaultPsdArriveAudio) || data.defaultPsdArriveSeconds != sec) {
                data.defaultPsdArriveAudio = id;
                data.defaultPsdArriveSeconds = sec;
                touched = true;
            }
            // 【1.21】-f = 「修改全部」：这一项的「按串单独设置」也一起抹回跟维度默认。
            if (remapPsdDoorOverrides(data, t -> (t.arrive() == null && t.arriveSeconds() == null)
                          ? null
                          : t.withArrive(null).withArriveSeconds(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** 【1.17】{@code /pbmmidium <名字>} 的补全项：该分类（pbm/midium）全部名字 + {@code off}。 */
    public static List<String> psdMidiumSuggestions(ServerLevel level, String category) {
        List<String> out = new ArrayList<>();
        out.add(EscalatorSpeedData.PSD_MIDIUM_OFF);
        if (level != null) {
            List<String> names = new ArrayList<>(categoryAudioNames(getServerData(level), category));
            names.sort(String::compareTo);
            out.addAll(names);
        }
        return out;
    }

    /**
     * 【1.17】从存档删除一段音频后，把**所有维度**里指向它的「到站播报」清成 {@code off}。
     *
     * <p>为什么必须有这一步：删掉素材之后那个设置会变成一条**指向空文件**的引用 ——
     * 播放端只会刷一条「素材不在音频库里」的警告，界面上则显示一个已经不存在文件名。
     * 用户从界面按下「删除」的语义是「这段音频我不要了」，不是「留一个坏引用」。
     *
     * @return 被清掉的维度数
     */
    public static int clearPsdMidiumIfRemoved(MinecraftServer server, String audioId) {
        if (audioId == null || audioId.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (audioId.equals(data.defaultPsdMidiumAudio)) {
                data.defaultPsdMidiumAudio = EscalatorSpeedData.PSD_MIDIUM_OFF;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /pbmmidium -f <名字> <秒>}：**所有维度**都设成该值。 */
    public static int setDefaultPsdMidiumAll(MinecraftServer server, String audioId, int seconds) {
        String id = EscalatorSpeedData.normalizePsdMidiumAudio(audioId);
        int sec = EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (!id.equals(data.defaultPsdMidiumAudio) || data.defaultPsdMidiumWaitSeconds != sec) {
                data.defaultPsdMidiumAudio = id;
                data.defaultPsdMidiumWaitSeconds = sec;
                touched = true;
            }
            // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
            //   设值：到站播报素材 + 等待秒数一起抹回跟维度默认（见 remapPsdDoorOverrides）。
            if (remapPsdDoorOverrides(data, t -> (t.midium() == null && t.midiumWaitSeconds() == null)
                          ? null
                          : t.withMidium(null).withMidiumWaitSeconds(null))) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 每扇门单独设置的素材（石斧右键门）
    // ------------------------------------------------------------------

    /** 服务端：读一扇门的提示音设置；没设置过 → {@code NONE}（两项都走内置素材）。 */
    public static EscalatorSpeedData.PsdToneAudio getServerPsdTone(ServerLevel level, long key) {
        EscalatorSpeedData.PsdToneAudio tone = getServerData(level).psdToneAudio.get(key);
        return tone != null ? tone : EscalatorSpeedData.PsdToneAudio.NONE;
    }

    /** 客户端：读一扇门的提示音设置（镜像）；没同步过 → {@code NONE}。 */
    public static EscalatorSpeedData.PsdToneAudio getClientPsdTone(Level level, long key) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return EscalatorSpeedData.PsdToneAudio.NONE;
        }
        EscalatorSpeedData.PsdToneAudio tone = data.psdToneAudio.get(key);
        return tone != null ? tone : EscalatorSpeedData.PsdToneAudio.NONE;
    }

    /**
     * 【1.50】校验并写入一扇门的提示音设置。
     *
     * <p>【1.15】起还接受三个**显式内置**别名 {@code default-c}（doorclose.ogg）/
     * {@code default-m}（mdoorclose.ogg）/ {@code default-s}（「默认（短）」：同素材但不播播报段）
     * —— 与 {@link #resolvePsdToneName} 同一套名字集合，
     * 否则石斧界面里点这几行会被这里判成「音频不存在」。
     *
     * @return 成功写入了才 true；{@code audioId} 不是 default / default-c / default-m / default-s
     *         / off / 音频库里存在的 id → false。
     */
    public static boolean setServerPsdTone(ServerLevel level, long key, String which, String audioId) {
        if (!"open".equals(which) && !"close".equals(which)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        String id = EscalatorSpeedData.normalizePsdToneAudio(audioId);
        if (!EscalatorSpeedData.isPsdBuiltinName(id)
                && !EscalatorSpeedData.PSD_TONE_OFF.equals(id)
                && !categoryAudioNames(data, "open".equals(which) ? CAT_PSD_OPEN : CAT_PSD_CLOSE).contains(id)) {
            return false;
        }
        EscalatorSpeedData.PsdToneAudio old = data.psdToneAudio.get(key);
        if (old == null) {
            old = EscalatorSpeedData.PsdToneAudio.NONE;
        }
        // ★【1.20】用 withTone 只换这一端的素材：这条记录现在还带着这扇门的开关 / 音量 /
        //   强制等待 / 到站播报（石斧 UI 改的就是它们），重建整条会把那些一起抹掉。
        EscalatorSpeedData.PsdToneAudio now = old.withTone(which, id);
        if (now.isEmpty()) {
            // 全部覆盖项都回到默认 = 等于没设置，删掉这条记录（表越干净越好查）。
            data.psdToneAudio.remove(key);
        } else {
            data.psdToneAudio.put(key, now);
        }
        data.setDirty();
        return true;
    }

    // ------------------------------------------------------------------
    // 【1.20】「这一扇门」的**全套**设置（石斧右键 UI 改的就是这一层）
    //
    // 用户原话：「石斧右键屏蔽门的 ui 里面修改的（要）全部都是玩家右键的连在一起的屏蔽门，
    //         而不是修改全部屏蔽门，只有指令才是修改全部」。
    // ⇒ 石斧 UI 改的每一项落到**这扇门的锚点**上（{@code psdToneAudio} 这一条记录的覆盖项），
    //   「维度默认」那一层退化成**回落层**，只由指令（不带 -f）写。
    //
    // 三条读数约定，全项目统一（改这里之前先读一遍）：
    //   1) 覆盖项为 null ⇒ 回落 {@link #getPsdHelpVolume(Level)} 那一族维度默认；
    //   2) **客户端**读的是镜像（播放端逐 tick 现算），**服务端**读 SavedData —— 两边同形；
    //   3) 不设「改一扇门就顺手改维度默认」的副作用：维度默认只有指令能动。
    // ------------------------------------------------------------------

    /** 服务端 / 客户端统一的「这一扇门那条记录」读取口（没设置过 → {@code NONE}）。 */
    public static EscalatorSpeedData.PsdToneAudio psdDoorRecord(Level level, long key) {
        if (level == null) {
            return EscalatorSpeedData.PsdToneAudio.NONE;
        }
        return level.isClientSide()
                ? getClientPsdTone(level, key)
                : getServerPsdTone((ServerLevel) level, key);
    }

    /**
     * 改这一扇门那条记录的一项（{@code fn} 负责把旧记录变成新记录）。
     *
     * <p>★ 统一走这一个口，是为了把「改完变空就删掉这条记录」这条规矩收在一处
     * —— 9 个字段各写一遍 {@code remove/put} 迟早会漏一个，留下一条全空的记录。
     */
    private static void updateDoor(ServerLevel level, long key,
                                   java.util.function.UnaryOperator<EscalatorSpeedData.PsdToneAudio> fn) {
        EscalatorSpeedData data = getServerData(level);
        EscalatorSpeedData.PsdToneAudio old = data.psdToneAudio.get(key);
        if (old == null) {
            old = EscalatorSpeedData.PsdToneAudio.NONE;
        }
        EscalatorSpeedData.PsdToneAudio now = fn.apply(old);
        if (now.isEmpty()) {
            data.psdToneAudio.remove(key);
        } else {
            data.psdToneAudio.put(key, now);
        }
        data.setDirty();
    }

    /** 石斧 UI「总开关」：只改**这一扇门**。 */
    public static void setDoorPsdHelp(ServerLevel level, long key, boolean enabled) {
        updateDoor(level, key, t -> t.withHelp(enabled));
    }

    /** 石斧 UI「开门 / 关门」页的开关行：只改**这一扇门这一项**。 */
    public static void setDoorPsdToneEnabled(ServerLevel level, long key, String which, boolean enabled) {
        updateDoor(level, key, t -> t.withToneEnabled(which, enabled));
    }

    /** 石斧 UI「默认音量」：只改**这一扇门**。 */
    public static void setDoorPsdHelpVolume(ServerLevel level, long key, int volume) {
        updateDoor(level, key, t -> t.withVolume(EscalatorSpeedData.clampLiftHelpVolume(volume)));
    }

    /** 石斧 UI 单项列表「音量」：只改**这一扇门这一项**（{@code -1} = 跟随共用默认，是个真实值）。 */
    public static void setDoorPsdToneVolume(ServerLevel level, long key, String which, int volume) {
        updateDoor(level, key, t -> t.withToneVolume(which, EscalatorSpeedData.clampPsdToneVolume(volume)));
    }

    /** 石斧 UI「强制等待」：只改**这一扇门**。 */
    public static void setDoorPsdCloseWaitSeconds(ServerLevel level, long key, int seconds) {
        updateDoor(level, key, t -> t.withCloseWaitSeconds(EscalatorSpeedData.clampPsdCloseWaitSeconds(seconds)));
    }

    /**
     * 【10-03 五改】**门串级**设置（关门后等待发车）的统一改法。
     *
     * <p>★ 与 {@link #updateDoor} 的区别是**键**：那一个的键是车站级 runKey（同站两侧共用一条），
     * 这一个的键是**门串锚点**（连在一起的 门 + 幕墙 + 幕墙尾部 = 一串）。用户点名这一项
     * 「每个屏蔽门串独有，改一个不许全局同步」⇒ 只能另起一张表、另走这一个入口。
     */
    private static void updatePsdRun(ServerLevel level, long runAnchor, long platformId,
                                     java.util.function.UnaryOperator<EscalatorSpeedData.PsdRunSetting> fn) {
        EscalatorSpeedData data = getServerData(level);
        EscalatorSpeedData.PsdRunSetting old = data.psdRunSettings.get(runAnchor);
        if (old == null) {
            old = EscalatorSpeedData.PsdRunSetting.NONE;
        }
        EscalatorSpeedData.PsdRunSetting now = fn.apply(old);
        if (EscalatorSpeedData.psdPlatformKnown(platformId)) {
            now = now.withPlatformId(platformId);
        }
        if (now.isEmpty()) {
            data.psdRunSettings.remove(runAnchor);
        } else {
            data.psdRunSettings.put(runAnchor, now);
        }
        data.setDirty();
    }

    /**
     * 【10-03 五改】石斧 UI「关门后等待 X 秒发车」：只改**这一串门**（范围 (-∞,+∞)，恒等夹取）。
     *
     * @param platformId 这一串落在哪个 MTR 站台（列车那侧要用；认不到时传
     *                   {@link EscalatorSpeedData#PSD_PLATFORM_ID_NONE}）
     */
    public static void setPsdRunDepartDelaySeconds(ServerLevel level, long runAnchor, long platformId,
                                                   int seconds) {
        updatePsdRun(level, runAnchor, platformId,
                t -> t.withDepartDelaySeconds(EscalatorSpeedData.clampPsdDepartDelaySeconds(seconds)));
    }

    /** 【10-03 五改】这一串门的「关门后等待 X 秒发车」；没设过 ⇒ 0（MTR 原样）。 */
    public static int getPsdRunDepartDelaySeconds(Level level, long runAnchor) {
        Integer own = psdRunRecord(level, runAnchor).departDelaySeconds();
        return own != null ? own : EscalatorSpeedData.DEFAULT_PSD_DEPART_DELAY_SECONDS;
    }

    /**
     * 【10-03 五改 修订】**旧档兼容版**读法：先查**门串锚点**，查不到再回落**车站级旧键**。
     *
     * <p>为什么需要：第一版把这项写在车站级 {@code psdToneAudio} 里，第二版搬到门串级
     * {@code psdRunSettings} 并做了存档迁移（旧值挂旧键 = 车站级 runKey）——这里把「找旧键」
     * 显式做出来，旧档的值在 UI 里继续可见；玩家在新版重新设过之后，门串锚点那一格
     * 先命中、天然盖过旧值。
     *
     * @param runAnchor 门串锚点（新版键）
     * @param stationKey 车站级 runKey（旧版键；UI 那边有现成的 {@code door.runKey()}）
     */
    public static int getPsdRunDepartDelaySecondsResolved(Level level, long runAnchor, long stationKey) {
        Integer own = psdRunRecord(level, runAnchor).departDelaySeconds();
        if (own == null && stationKey != runAnchor) {
            own = psdRunRecord(level, stationKey).departDelaySeconds();
        }
        return own != null ? own : EscalatorSpeedData.DEFAULT_PSD_DEPART_DELAY_SECONDS;
    }

    /** 客户端镜像 / 服务端存档统一走这一个取记录口（没有 ⇒ {@code NONE}）。 */
    private static EscalatorSpeedData.PsdRunSetting psdRunRecord(Level level, long runAnchor) {
        if (level == null) {
            return EscalatorSpeedData.PsdRunSetting.NONE;
        }
        if (level instanceof ServerLevel serverLevel) {
            return getServerData(serverLevel).psdRunSettings
                    .getOrDefault(runAnchor, EscalatorSpeedData.PsdRunSetting.NONE);
        }
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        return data == null ? EscalatorSpeedData.PsdRunSetting.NONE
                : data.psdRunSettings.getOrDefault(runAnchor, EscalatorSpeedData.PsdRunSetting.NONE);
    }

    /**
     * 【10-03 五改】**列车那侧**的取值：这一座 MTR 站台配的发车等待秒数。
     *
     * <p>为什么按站台 id 而不是门串锚点：门串锚点是个方块坐标（客户端几何算出来的），
     * 服务端的列车只知道「我停在哪个站台」⇒ 写设置时把站台 id 一起存进门串记录里，
     * 这里按它反查（一张几十条的小表，每站只查一次）。
     *
     * @return 秒数（未配置 / 读不到 ⇒ 0 = MTR 原样）
     */
    public static int getPsdDepartDelayForPlatform(ServerLevel level, long platformId) {
        if (!EscalatorSpeedData.psdPlatformKnown(platformId)) {
            return 0;
        }
        for (EscalatorSpeedData.PsdRunSetting v : getServerData(level).psdRunSettings.values()) {
            if (v.platformId() != null && v.platformId() == platformId && v.departDelaySeconds() != null) {
                return EscalatorSpeedData.clampPsdDepartDelaySeconds(v.departDelaySeconds());
            }
        }
        return 0;
    }

    /** 【10-03 五改】客户端本地回显：直接把这一串门的新设置写进本地镜像。 */
    public static void applyClientPsdRunLocal(ResourceKey<Level> dimension, long runAnchor,
                                              java.util.function.UnaryOperator<EscalatorSpeedData.PsdRunSetting> fn) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        EscalatorSpeedData.PsdRunSetting now =
                fn.apply(data.psdRunSettings.getOrDefault(runAnchor, EscalatorSpeedData.PsdRunSetting.NONE));
        if (now.isEmpty()) {
            data.psdRunSettings.remove(runAnchor);
        } else {
            data.psdRunSettings.put(runAnchor, now);
        }
    }

    /** 【10-03 五改】整份替换某个维度的门串级镜像（同步包读端用）。 */
    public static void applyClientPsdRun(ResourceKey<Level> dimension,
                                         Map<Long, EscalatorSpeedData.PsdRunSetting> settings) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.psdRunSettings.clear();
        data.psdRunSettings.putAll(settings);
    }

    /** 【1.23】石斧 UI「开门提示」行：开门提示音强制等待秒数（对称项）。 */
    public static int getDoorPsdOpenWaitSeconds(Level level, long key) {
        Integer own = psdDoorRecord(level, key).openWaitSeconds();
        return own != null ? own : EscalatorSpeedData.DEFAULT_PSD_OPEN_WAIT_SECONDS;
    }

    /** 【1.23】石斧 UI「开门提示」行：设开门提示音等待秒数（与关门强制等待同范围 [0,+∞)）。 */
    public static void setDoorPsdOpenWaitSeconds(ServerLevel level, long key, int seconds) {
        updateDoor(level, key, t -> t.withOpenWaitSeconds(EscalatorSpeedData.clampPsdOpenWaitSeconds(seconds)));
    }

    /** 石斧 UI「到站播放音频 / 等待几秒后播放」：只改**这一扇门**。 */
    public static void setDoorPsdMidium(ServerLevel level, long key, String audioId, int seconds) {
        updateDoor(level, key, t -> t.withMidium(EscalatorSpeedData.normalizePsdMidiumAudio(audioId))
                .withMidiumWaitSeconds(EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds)));
    }

    /** 【1.21】石斧 UI「进站播放音频 / 到站前几秒」：只改**这一串门**。 */
    public static void setDoorPsdArrive(ServerLevel level, long key, String audioId, int seconds) {
        updateDoor(level, key, t -> t.withArrive(EscalatorSpeedData.normalizePsdArriveAudio(audioId))
                .withArriveSeconds(EscalatorSpeedData.clampPsdArriveSeconds(seconds)));
    }

    /**
     * 【09-28】石斧 UI「进站广播（讲述人）」二级页（关闭 / 开启(上海) / 开启(香港)）：
     * 只改**这一串门**的**样式**。
     */
    public static void setDoorPsdNarrate(ServerLevel level, long key, int mode) {
        updateDoor(level, key, t -> t.withNarrate(EscalatorSpeedData.clampPsdNarrateMode(mode)));
    }

    /** 【09-28】石斧 UI「进站广播（讲述人）」主界面的秒数格：只改**这一串门**（(-∞, 0]）。 */
    public static void setDoorPsdNarrateSeconds(ServerLevel level, long key, int seconds) {
        updateDoor(level, key, t -> t.withNarrateSeconds(EscalatorSpeedData.clampPsdNarrateSeconds(seconds)));
    }

    /** 【09-30 续 3】石斧 UI「站台广播（讲述人）」二级页：只改**这一串门**的**样式**（档位共用）。 */
    public static void setDoorPsdMidiumNarrate(ServerLevel level, long key, int mode) {
        updateDoor(level, key, t -> t.withMidiumNarrate(EscalatorSpeedData.clampPsdNarrateMode(mode)));
    }

    /** 【09-30 续 3】石斧 UI「站台广播（讲述人）」主界面的秒数格：只改**这一串门**（[0,+∞)）。 */
    public static void setDoorPsdMidiumNarrateSeconds(ServerLevel level, long key, int seconds) {
        updateDoor(level, key,
                t -> t.withMidiumNarrateSeconds(EscalatorSpeedData.clampPsdMidiumNarrateSeconds(seconds)));
    }

    // ---- 读数：每一项都是「门的覆盖值 > 维度默认」 ----

    /** 这一扇门**生效**的总开关。 */
    public static boolean isDoorPsdHelpEnabled(Level level, long key) {
        Boolean own = psdDoorRecord(level, key).help();
        return own != null ? own : isPsdHelpEnabled(level);
    }

    /** 这一扇门这一项**生效**的子开关。 */
    public static boolean isDoorPsdToneEnabled(Level level, long key, String which) {
        EscalatorSpeedData.PsdToneAudio t = psdDoorRecord(level, key);
        Boolean own = "open".equals(which) ? t.openEnabled() : t.closeEnabled();
        return own != null ? own : isPsdToneEnabled(level, which);
    }

    /** 这一扇门**生效**的共用音量。 */
    public static int getDoorPsdHelpVolume(Level level, long key) {
        Integer own = psdDoorRecord(level, key).volume();
        return own != null ? own : getPsdHelpVolume(level);
    }

    /** 这一扇门这一项**生效**的音量（没单独调过 → 回落这一扇门的共用音量 → 维度默认）。 */
    public static int getDoorPsdToneVolume(Level level, long key, String which) {
        Integer own = "open".equals(which)
                ? psdDoorRecord(level, key).openVolume()
                : psdDoorRecord(level, key).closeVolume();
        return own != null ? own : getDoorPsdHelpVolume(level, key);
    }

    /** 【1.22】这一串门**生效**的到站播报音量。 */
    public static int getDoorPsdMidiumVolume(Level level, long key) {
        Integer own = psdDoorRecord(level, key).midiumVolume();
        return own != null ? own : getPsdMidiumVolume(level);
    }

    /** 【1.22】这一串门**生效**的进站报站音量。 */
    public static int getDoorPsdArriveVolume(Level level, long key) {
        Integer own = psdDoorRecord(level, key).arriveVolume();
        return own != null ? own : getPsdArriveVolume(level);
    }

    /** 石斧 UI「到站播放音频」那一行的音量：只改这一串门。 */
    public static void setDoorPsdMidiumVolume(ServerLevel level, long key, int volume) {
        updateDoor(level, key, t -> t.withMidiumVolume(EscalatorSpeedData.clampPsdToneVolume(volume)));
    }

    /** 石斧 UI「进站播放音频」那一行的音量：只改这一串门。 */
    public static void setDoorPsdArriveVolume(ServerLevel level, long key, int volume) {
        updateDoor(level, key, t -> t.withArriveVolume(EscalatorSpeedData.clampPsdToneVolume(volume)));
    }

    /** 这一扇门**生效**的强制等待秒数。 */
    public static int getDoorPsdCloseWaitSeconds(Level level, long key) {
        Integer own = psdDoorRecord(level, key).closeWaitSeconds();
        return own != null ? own : getPsdCloseWaitSeconds(level);
    }

    /** 这一扇门**生效**的到站播报素材（{@code off} = 不播）。 */
    public static String getDoorPsdMidiumAudio(Level level, long key) {
        String own = psdDoorRecord(level, key).midium();
        return own != null ? own : getPsdMidiumAudio(level);
    }

    /** 这一扇门**生效**的到站播报等待秒数。 */
    public static int getDoorPsdMidiumWaitSeconds(Level level, long key) {
        Integer own = psdDoorRecord(level, key).midiumWaitSeconds();
        return own != null ? own : getPsdMidiumWaitSeconds(level);
    }

    /** 【1.21】这一串门**生效**的进站报站素材（{@code off} = 不播）。 */
    public static String getDoorPsdArriveAudio(Level level, long key) {
        String own = psdDoorRecord(level, key).arrive();
        return own != null ? own : getPsdArriveAudio(level);
    }

    /** 【1.21】这一串门**生效**的进站报站秒数（(-∞, 0]：最近一班车还剩 |X| 秒到站时起播）。 */
    public static int getDoorPsdArriveSeconds(Level level, long key) {
        Integer own = psdDoorRecord(level, key).arriveSeconds();
        return own != null ? own : getPsdArriveSeconds(level);
    }

    /**
     * 【09-28】这一串门**生效**的「进站广播（讲述人）」**样式**（门覆盖值 &gt; 维度默认；0/1/2）。
     *
     * <p>★ 播放端**必须**用这一个而不是 {@link #isDoorPsdNarrateOn}：选播报词要区分
     * 「开启(上海)」与「开启(香港)」，只看开/关会把香港档也念成上海词。
     */
    public static int getDoorPsdNarrateMode(Level level, long key) {
        Integer own = psdDoorRecord(level, key).narrate();
        return own != null ? own : getPsdNarrateMode(level);
    }

    /** 【09-28】这一串门**生效**的「进站广播（讲述人）」开关（= 样式 ≠ 关闭；门覆盖值 > 维度默认）。 */
    public static boolean isDoorPsdNarrateOn(Level level, long key) {
        return getDoorPsdNarrateMode(level, key) != EscalatorSpeedData.PSD_NARRATE_OFF;
    }

    /** 【09-28】这一串门**生效**的讲述人秒数（门覆盖值 > 维度默认；(-∞, 0]）。 */
    public static int getDoorPsdNarrateSeconds(Level level, long key) {
        Integer own = psdDoorRecord(level, key).narrateSeconds();
        return own != null ? own : getPsdNarrateSeconds(level);
    }

    /**
     * 【09-30 续 3】这一串门**生效**的「站台广播（讲述人）」**样式**（门覆盖值 &gt; 维度默认；
     * 档位与进站讲述人共用：0 关 / 1 上海 / 2 香港 / 3+ userN）。
     */
    public static int getDoorPsdMidiumNarrateMode(Level level, long key) {
        Integer own = psdDoorRecord(level, key).midiumNarrate();
        return own != null ? own : getPsdMidiumNarrateMode(level);
    }

    /** 【09-30 续 3】这一串门**生效**的「站台广播（讲述人）」等待秒数（门覆盖值 &gt; 维度默认；[0,+∞)）。 */
    public static int getDoorPsdMidiumNarrateSeconds(Level level, long key) {
        Integer own = psdDoorRecord(level, key).midiumNarrateSeconds();
        return own != null ? own : getPsdMidiumNarrateSeconds(level);
    }

    /**
     * 有没有**任何一扇门**自己把总开关打开了。
     *
     * <p>给播放端那条最早的全局早退用：维度默认关着时，只要有一扇门自己开着，
     * 就不能整块跳过（否则那扇门成了永远不响的死配置）。
     * 只在「维度默认是关的」那一支被调用，所以这点扫描不心疼。
     */
    public static boolean hasAnyDoorPsdHelpOn(Level level) {
        if (level == null) {
            return false;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            for (EscalatorSpeedData.PsdToneAudio t : data.psdToneAudio.values()) {
                if (Boolean.TRUE.equals(t.help())) {
                    return true;
                }
            }
            return false;
        }
        for (EscalatorSpeedData.PsdToneAudio t : getServerData((ServerLevel) level).psdToneAudio.values()) {
            if (Boolean.TRUE.equals(t.help())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 【1.15】屏蔽门「维度默认素材」—— /pbmmusic open|close <名字>
    //
    // 与直梯那套（defaultLiftToneAudioUp/Down/Chime）**完全对称**，只有两点差别：
    //   1) 两项（open / close）而不是三项；
    //   2) 「跟内置」的落点按**端别**分：开门 → dooropen.ogg、关门 → mdoorclose.ogg
    //      （★【1.15】关门端由 doorclose.ogg 改成 mdoorclose.ogg —— 用户点名「屏蔽门默认音效改为
    //        mdoorclose.ogg（default-m）」），
    //      另外还有两个**显式**名字 default-c（doorclose）/ default-m（mdoorclose）。
    //
    // 两层回落（播放端 PsdChimePlayer 走同一条链）：
    //   某扇门的值 --(空/default)→ 维度默认素材 --(空/default)→ 端别内置
    // ------------------------------------------------------------------

    /** 服务端：读这个维度某一项的默认素材（空/null → default）。 */
    private static String serverPsdToneAudio(EscalatorSpeedData data, String which) {
        return switch (which) {
            case "open" -> EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioOpen);
            case "close" -> EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioClose);
            default -> EscalatorSpeedData.PSD_TONE_DEFAULT;
        };
    }

    private static void setServerPsdToneAudio(EscalatorSpeedData data, String which, String audioId) {
        String id = EscalatorSpeedData.normalizePsdToneAudio(audioId);
        switch (which) {
            case "open" -> data.defaultPsdToneAudioOpen = id;
            case "close" -> data.defaultPsdToneAudioClose = id;
            default -> {
            }
        }
    }

    /** 这个维度**生效**的某项默认素材（客户端读镜像，服务端读 SavedData）。 */
    public static String getPsdToneAudio(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return EscalatorSpeedData.normalizePsdToneAudio(switch (which) {
                case "open" -> data == null ? null : data.psdToneAudioOpen;
                case "close" -> data == null ? null : data.psdToneAudioClose;
                default -> null;
            });
        }
        return serverPsdToneAudio(getServerData((ServerLevel) level), which);
    }

    /** 一扇门的某一项取值（与 {@link #toneField} 对称，PSD 版本）。 */
    public static String psdToneField(EscalatorSpeedData.PsdToneAudio tone, String which) {
        if (tone == null) {
            return EscalatorSpeedData.PSD_TONE_DEFAULT;
        }
        return "open".equals(which) ? tone.open() : tone.close();
    }

    /** {@code /pbmmusic open|close <名字>}：只改**本维度**这一项的默认素材。 */
    public static void setDefaultPsdToneAudio(ServerLevel level, String which, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        setServerPsdToneAudio(data, which, audioId);
        data.setDirty();
    }

    /** {@code /pbmmusic open|close <X> to <Y>}：本维度默认素材正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultPsdToneAudio(ServerLevel level, String which, String from, String to) {
        EscalatorSpeedData data = getServerData(level);
        if (!java.util.Objects.equals(serverPsdToneAudio(data, which), from)) {
            return false;
        }
        setServerPsdToneAudio(data, which, to);
        data.setDirty();
        return true;
    }

    /**
     * {@code /pbmmusic open|close -f <名字>}：把**所有维度**这一项的默认素材都设成它，
     * 并清掉按扇门的单独设置（那些门从此跟维度默认）。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultPsdToneAudioAll(MinecraftServer server, String which, String audioId) {
        String id = EscalatorSpeedData.normalizePsdToneAudio(audioId);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (!id.equals(serverPsdToneAudio(data, which))) {
                setServerPsdToneAudio(data, which, id);
                touched = true;
            }
            if (clearPsdToneOverrides(data, which)) {
                touched = true;
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /pbmmusic open|close -f <X> to <Y>}：所有维度里默认素材正好是 X 的改成 Y；
     * 同时把**单独设置**里那一项正好是 X 的也改成 Y（与 {@code /lifthelp up -f X to Y} 同一语义）。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultPsdToneAudioAll(MinecraftServer server, String which, String from, String to) {
        String id = EscalatorSpeedData.normalizePsdToneAudio(to);
        String src = EscalatorSpeedData.normalizePsdToneAudio(from);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            boolean touched = false;
            if (src.equals(serverPsdToneAudio(data, which))) {
                setServerPsdToneAudio(data, which, id);
                touched = true;
            }
            // 单独设置里那一项正好是 X 的也一起换掉（-f = 「含单独设置的」）
            if (!data.psdToneAudio.isEmpty()) {
                Map<Long, EscalatorSpeedData.PsdToneAudio> next = new HashMap<>();
                for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
                    EscalatorSpeedData.PsdToneAudio t = e.getValue();
                    // ★【1.20】只换**素材那一项**，其余覆盖项（开关 / 音量 / 强制等待 / 到站播报）
                    //   原样保留 —— 这里原来用 new PsdToneAudio(open, close) 重建，会顺手把
                    //   这扇门的音量设置抹掉（「改个素材，音量回默认」）。
                    if (src.equals("open".equals(which) ? t.open() : t.close())) {
                        t = t.withTone(which, id);
                        touched = true;
                    }
                    if (!t.isEmpty()) {
                        next.put(e.getKey(), t);
                    }
                }
                if (touched) {
                    data.psdToneAudio.clear();
                    data.psdToneAudio.putAll(next);
                }
            }
            if (touched) {
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 把「按扇门单独设置」里 {@code which} 这一项**改回「跟维度默认」**；
     * 两项都变成默认的那条记录直接删掉（表越干净越好查）。
     *
     * @return 真的改动过才 true
     */
    private static boolean clearPsdToneOverrides(EscalatorSpeedData data, String which) {
        if (data.psdToneAudio.isEmpty()) {
            return false;
        }
        Map<Long, EscalatorSpeedData.PsdToneAudio> next = new HashMap<>();
        boolean touched = false;
        for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
            EscalatorSpeedData.PsdToneAudio t = e.getValue();
            // ★【1.20】只把**这一项的素材**清回「跟维度默认」，其余覆盖项原样保留
            //   （老写法用 new PsdToneAudio(open, close) 重建 ⇒ 顺手抹掉这扇门的音量等设置）。
            if (EscalatorSpeedData.PSD_TONE_DEFAULT.equals("open".equals(which) ? t.open() : t.close())) {
                next.put(e.getKey(), t);
                continue;
            }
            t = t.withTone(which, EscalatorSpeedData.PSD_TONE_DEFAULT);
            touched = true;
            if (!t.isEmpty()) {
                next.put(e.getKey(), t);
            }
        }
        if (!touched) {
            return false;
        }
        data.psdToneAudio.clear();
        data.psdToneAudio.putAll(next);
        return true;
    }

    /**
     * 【1.20】把本维度**所有扇门**的 per-door 记录按 {@code fn} 变换一遍：
     * fn 返回 null 的记录原样保留，返回新记录则替换；全部覆盖项都回到默认的那条直接删掉。
     *
     * <p>两条用途（都是指令的 {@code -f} 半 ——「只有指令才是修改全部」）：
     * <ul>
     *   <li>设值（{@code -f <新值>}）：把这一项抹回「跟维度默认」——
     *       {@code t -> t.volume() == null ? null : t.withVolume(null)}；</li>
     *   <li>条件替换（{@code -f <X> to <Y>}）：这一项**正好是 X** 的改成 Y——
     *       {@code t -> t.volume() == null || t.volume() != X ? null : t.withVolume(Y)}
     *       （与素材那条 replace 的 per-door 半同一语义）。</li>
     * </ul>
     *
     * <p>为什么设值型要清 per-door：维度默认是**回落层**，UI 单独设过的那扇门不落在上面；
     * 不清掉它，{@code -f} 对那扇门就不生效，视觉上就是「全局指令改了没反应」。
     *
     * @return 真的改动过才 true
     */
    private static boolean remapPsdDoorOverrides(EscalatorSpeedData data,
                                                 java.util.function.Function<EscalatorSpeedData.PsdToneAudio,
                                                         EscalatorSpeedData.PsdToneAudio> fn) {
        if (data.psdToneAudio.isEmpty()) {
            return false;
        }
        Map<Long, EscalatorSpeedData.PsdToneAudio> next = new HashMap<>();
        boolean touched = false;
        for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
            EscalatorSpeedData.PsdToneAudio changed = fn.apply(e.getValue());
            if (changed == null) {
                next.put(e.getKey(), e.getValue());
                continue;
            }
            touched = true;
            if (!changed.isEmpty()) {
                next.put(e.getKey(), changed);
            }
        }
        if (!touched) {
            return false;
        }
        data.psdToneAudio.clear();
        data.psdToneAudio.putAll(next);
        return true;
    }

    /**
     * 【1.15】命令行音频名字 → 屏蔽门提示音素材 id。
     *
     * <ul>
     *   <li>{@code default} → {@link EscalatorSpeedData#PSD_TONE_BUILTIN_OPEN}（= default）：
     *       「跟上一层」；落到维度默认那一层时按**端别**取内置素材
     *       （开门 {@code dooropen.ogg} / 关门 {@code mdoorclose.ogg}）；</li>
     *   <li>{@code default-c} → {@code doorclose.ogg}（显式，与端别无关）；</li>
     *   <li>{@code default-m} → {@code mdoorclose.ogg}（显式，与端别无关）；</li>
     *   <li>{@code default-s} → **「默认（短）」**：按端别的默认素材，但**不播语音播报段**
     *       （关门端听起来就是纯粹一串嘀嘀 = 【1.15】之前那种行为）；</li>
     *   <li>{@code none} / {@code mute} / {@code off} → {@link EscalatorSpeedData#PSD_TONE_OFF}
     *       （这一项不播）；</li>
     *   <li>其它 → 音频库里同名的文件（找不到时再试「名字 + .ogg」，与扶梯那套一致）。</li>
     * </ul>
     *
     * <p>★ 与直梯那边的取舍一样：「不播」推荐写 {@code none} 而不是 {@code off}，
     * 因为 {@code off} 已经是**子开关**的字面量（{@code /pbmmusic open off} = 关掉开门提示音），
     * Brigadier 的字面量优先于字符串参数 ⇒ 玩家打不出「把素材设成 off」这一句。
     * 这里仍然认 {@code off} 只是让从别处抄来的写法不至于报错。
     */
    /** 【1.28】按分类解析屏蔽门提示音素材名（/pbmmusic open|close <名字>）。 */
    public static AudioArg resolvePsdToneName(ServerLevel level, String category, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "音频名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN.equals(lower)) {
            return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN, false, null);
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE.equals(lower)) {
            return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE, false, null);
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M.equals(lower)) {
            return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M, false, null);
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S.equals(lower)) {
            return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S, false, null);
        }
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(lower) || "none".equals(lower) || "mute".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.PSD_TONE_OFF, true, null);
        }
        EscalatorSpeedData data = getServerData(level);
        Set<String> catNames = categoryAudioNames(data, category);
        if (catNames.contains(name)) {
            return new AudioArg(name, false, null);
        }
        if (!lower.endsWith(".ogg") && catNames.contains(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "「" + category + "」分类里没有叫「" + name + "」的音频。屏蔽门提示音可以用 "
                + EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN + " / "
                + EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE + " / "
                + EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M + " / "
                + EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S + " 四段内置素材、none 不播，"
                + "或用导入过的 .ogg；"
                + (catNames.isEmpty()
                        ? "现在还没有导入过任何音频，玩家需要在石斧界面里上传或导入 .ogg"
                        : "已有的：" + previewNames(data, category)));
    }

    /**
     * 【1.15】屏蔽门名字参数的 Tab 补全候选（**四段内置名排最前，然后是 none 与库文件名**）：
     * {@code default} / {@code default-c} / {@code default-m} / {@code default-s} / {@code none}
     * + 该分类导入过的每一个 .ogg。【1.28】按分类取。
     */
    public static List<String> psdNameCandidates(ServerLevel level, String category) {
        List<String> names = new ArrayList<>(getServerAudioLibraryKeys(level, category));
        names.sort(String::compareTo);
        List<String> out = new ArrayList<>(names.size() + 5);
        out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN);
        out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE);
        out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M);
        out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S);
        out.add("none");
        out.addAll(names);
        return out;
    }

    /**
     * 【1.50】构建某个维度的「屏蔽门开关门提示音」同步包（按维度的那一套，最小的包）：
     * {@code dimId → 总开关 → 共用默认音量 → open子开关 → close子开关 → 范围
     *   → open单独音量 → close单独音量 → 【1.15】open默认素材 → close默认素材}。
     *
     * <p>★ 字段顺序**就是** {@link #applyClientPsdChime} 的入参顺序，两处必须一起改
     * （客户端 {@code SmoothLiftClient} 那边按同一顺序读）。
     */
    private static FriendlyByteBuf buildPsdChimePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeBoolean(data.defaultPsdHelp);
        buf.writeVarInt(data.defaultPsdHelpVolume);
        buf.writeBoolean(data.defaultPsdToneOpenEnabled);
        buf.writeBoolean(data.defaultPsdToneCloseEnabled);
        // 【09-29】范围拆双维：第 1 格水平（x、z 轴）原位，第 2 格垂直（y 轴）紧跟
        buf.writeVarInt(data.defaultPsdHelpRoundXz);
        buf.writeVarInt(data.defaultPsdHelpRoundY);
        buf.writeVarInt(data.defaultPsdToneVolumeOpen);
        buf.writeVarInt(data.defaultPsdToneVolumeClose);
        // 【1.15】两项的维度默认素材（末尾追加，读侧同序）
        buf.writeUtf(EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioOpen), 128);
        buf.writeUtf(EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioClose), 128);
        // 【1.16】关门提示音的强制等待时长（秒，末尾再追加一格，读侧同序）
        buf.writeVarInt(data.defaultPsdCloseWaitSeconds);
        // 【1.17】到站播报：素材 id + 等待秒数（末尾再追加两格，读侧同序）
        buf.writeUtf(EscalatorSpeedData.normalizePsdMidiumAudio(data.defaultPsdMidiumAudio), 128);
        buf.writeVarInt(data.defaultPsdMidiumWaitSeconds);
        // 【1.21】进站报站：素材 id + 秒数（末尾再追加两格，读侧同序）
        buf.writeUtf(EscalatorSpeedData.normalizePsdArriveAudio(data.defaultPsdArriveAudio), 128);
        buf.writeVarInt(data.defaultPsdArriveSeconds);
        // 【1.22】到站 / 进站播报各自那一项的音量（末尾再追加两格，读侧同序）
        buf.writeVarInt(data.defaultPsdMidiumVolume);
        buf.writeVarInt(data.defaultPsdArriveVolume);
        // 【1.23】【09-29】到站 / 进站播报各自的**可闻范围**（末尾再追加，读侧同序）：
        //   范围拆双维 —— 每项第 1 格水平（x、z 轴）原位，第 2 格垂直（y 轴）紧跟
        buf.writeVarInt(data.defaultPsdMidiumRoundXz);
        buf.writeVarInt(data.defaultPsdMidiumRoundY);
        buf.writeVarInt(data.defaultPsdArriveRoundXz);
        buf.writeVarInt(data.defaultPsdArriveRoundY);
        // 【09-28】「进站广播（讲述人）」维度默认：样式（0/1/2）+ 秒数（末尾再追加两格，读侧同序）
        //   ★ 续：第 1 格由 Boolean（开关）改成 VarInt（三档样式）—— 格子数不变，类型变了，
        //   读侧同步改（SmoothLiftClient 那一行 narrateMode = buf.readVarInt()）。
        buf.writeVarInt(data.defaultPsdNarrateMode);
        buf.writeVarInt(data.defaultPsdNarrateSeconds);
        // 【09-30 续 3】「站台广播（讲述人）」维度默认（末尾再追加两格，读侧同序）
        buf.writeVarInt(data.defaultPsdMidiumNarrateMode);
        buf.writeVarInt(data.defaultPsdMidiumNarrateSeconds);
        // 【10-01】两条讲述人广播的玩家自定义文字（末尾再追加：条数 + 按序 utf；读侧同序）。
        //   ★ 文字随维度同步包发给客户端 —— 客户端讲述人/编辑页念的就是这份按存档存的词。
        writeNarrateUserTexts(buf, data.defaultPsdArriveNarrateUserTexts);
        writeNarrateUserTexts(buf, data.defaultPsdMidiumNarrateUserTexts);
        return buf;
    }

    /** 【1.50】把一个维度的屏蔽门提示音设置发给单个玩家。 */
    public static void sendPsdChimeSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.PSD_CHIME_SYNC_CHANNEL, buildPsdChimePacket(level));
    }

    /** 【1.50】把所有维度的屏蔽门提示音设置同步给所有在线玩家。 */
    public static void syncPsdChimeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendPsdChimeSyncTo(player, level);
            }
        }
    }

    /**
     * 【1.50】构建某个维度的「每扇门单独素材」同步包：
     * {@code dimId → 条数 → (key, open, close) × N}。
     */
    private static FriendlyByteBuf buildPsdTonePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(level.dimension().location().toString(), 256);
        buf.writeVarInt(data.psdToneAudio.size());
        for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
            buf.writeLong(e.getKey());
            EscalatorSpeedData.PsdToneAudio tone = e.getValue();
            buf.writeUtf(tone.open(), 128);
            buf.writeUtf(tone.close(), 128);
            // 【1.20】这一扇门的其余覆盖项（石斧 UI 改的就是它们）。
            //   ★ 写序必须与客户端读序**严格一致**（错位不报错，只会把值串到别的字段上）：
            //     help → openEnabled → closeEnabled → volume → openVolume → closeVolume
            //     → closeWaitSeconds → midium → midiumWaitSeconds
            //   null（= 跟维度默认）用一个 boolean 前缀表达 —— 包上没有 NBT 那种 contains。
            writeDoorOptBool(buf, tone.help());
            writeDoorOptBool(buf, tone.openEnabled());
            writeDoorOptBool(buf, tone.closeEnabled());
            writeDoorOptInt(buf, tone.volume());
            writeDoorOptInt(buf, tone.openVolume());
            writeDoorOptInt(buf, tone.closeVolume());
            writeDoorOptInt(buf, tone.openWaitSeconds());
            writeDoorOptInt(buf, tone.closeWaitSeconds());
            writeDoorOptString(buf, tone.midium());
            writeDoorOptInt(buf, tone.midiumWaitSeconds());
            // 【1.21】进站报站（写序必须与客户端读序严格一致 —— 错位不报错，只会把值串到别的字段上）
            writeDoorOptString(buf, tone.arrive());
            writeDoorOptInt(buf, tone.arriveSeconds());
            // 【1.22】到站 / 进站各自那一项的音量（末尾再追加两格，读侧同序）
            writeDoorOptInt(buf, tone.midiumVolume());
            writeDoorOptInt(buf, tone.arriveVolume());
            // 【09-28】「进站广播（讲述人）」这一串门的覆盖项：样式（0/1/2）+ 秒数（末尾再追加两格，读侧同序）
            //   ★ 续：第 1 格由 Boolean（开关）改成可选 int（三档样式）—— 格子数不变，读侧同步改。
            writeDoorOptInt(buf, tone.narrate());
            writeDoorOptInt(buf, tone.narrateSeconds());
            // 【09-30 续 3】「站台广播（讲述人）」这一串门的覆盖项（末尾再追加两格，读侧同序）
            writeDoorOptInt(buf, tone.midiumNarrate());
            writeDoorOptInt(buf, tone.midiumNarrateSeconds());
        }
        // 【10-03 五改】**门串级**设置（与上面那张车站级表不同键空间）：末尾再追加一段。
        //   ★ 写序：条数 → (门串锚点 long, 平台 id long, 发车等待?) × N，读侧同序。
        buf.writeVarInt(data.psdRunSettings.size());
        for (Map.Entry<Long, EscalatorSpeedData.PsdRunSetting> e : data.psdRunSettings.entrySet()) {
            EscalatorSpeedData.PsdRunSetting v = e.getValue();
            buf.writeLong(e.getKey());
            buf.writeLong(v.platformId() != null
                    ? v.platformId() : EscalatorSpeedData.PSD_PLATFORM_ID_NONE);
            writeDoorOptInt(buf, v.departDelaySeconds());
        }
        return buf;
    }

    // ---- 【1.20】「可选值」的包读写：与 buildPsdTonePacket / 客户端读端成对使用 ----

    private static void writeDoorOptBool(FriendlyByteBuf buf, Boolean v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeBoolean(v);
        }
    }

    private static void writeDoorOptInt(FriendlyByteBuf buf, Integer v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeVarInt(v);
        }
    }

    private static void writeDoorOptString(FriendlyByteBuf buf, String v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeUtf(v, 128);
        }
    }

    // ---- 【10-01】讲述人自定义文字列表的包读写（与 buildPsdChimePacket 末尾成对）----

    private static void writeNarrateUserTexts(FriendlyByteBuf buf, List<String> texts) {
        int n = Math.min(texts == null ? 0 : texts.size(), EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(texts.get(i) == null ? "" : texts.get(i), 256);
        }
    }

    /** 客户端读一段「讲述人自定义文字」列表（与 {@link #writeNarrateUserTexts} 成对）。 */
    public static List<String> readNarrateUserTexts(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES);
        List<String> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf(256));
        }
        return out;
    }

    /** 客户端读一个「可选 boolean」；与 {@link #writeDoorOptBool} 成对。 */
    public static Boolean readDoorOptBool(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBoolean() : null;
    }

    /** 客户端读一个「可选 int」；与 {@link #writeDoorOptInt} 成对。 */
    public static Integer readDoorOptInt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readVarInt() : null;
    }

    /** 客户端读一个「可选字符串」；与 {@link #writeDoorOptString} 成对。 */
    public static String readDoorOptString(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(128) : null;
    }

    /** 【1.50】把一个维度的「每扇门单独素材」发给单个玩家。 */
    public static void sendPsdToneSyncTo(ServerPlayer player, ServerLevel level) {
        SLNet.sendToPlayer(player, SmoothLift.PSD_TONE_SYNC_CHANNEL, buildPsdTonePacket(level));
    }

    /** 【1.50】把所有维度的「每扇门单独素材」同步给所有在线玩家。 */
    public static void syncPsdToneToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendPsdToneSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 镜像写入（客户端接收器 / 石斧界面的本地即时回显）
    // ------------------------------------------------------------------

    /**
     * 【1.50】应用服务端同步过来的屏蔽门提示音设置（覆盖式更新本维度的镜像）。
     * 入参顺序 = {@link #buildPsdChimePacket} 的写序。
     */
    public static void applyClientPsdChime(ResourceKey<Level> dimension, boolean enabled, int volume,
                                           boolean openEnabled, boolean closeEnabled,
                                           int roundXz, int roundY,
                                           int toneVolumeOpen, int toneVolumeClose,
                                           String toneAudioOpen, String toneAudioClose,
                                           int closeWaitSeconds,
                                           String midiumAudio, int midiumWaitSeconds,
                                           String arriveAudio, int arriveSeconds,
                                           int midiumVolume, int arriveVolume,
                                           int midiumRoundXz, int midiumRoundY,
                                           int arriveRoundXz, int arriveRoundY,
                                           int narrateMode, int narrateSeconds,
                                           int midiumNarrateMode, int midiumNarrateSeconds) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.psdHelp = enabled;
        data.psdHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        data.psdToneOpenEnabled = openEnabled;
        data.psdToneCloseEnabled = closeEnabled;
        data.psdHelpRoundXz = EscalatorSpeedData.clampPsdHelpRound(roundXz);
        data.psdHelpRoundY = EscalatorSpeedData.clampPsdHelpRound(roundY);
        data.psdToneVolumeOpen = EscalatorSpeedData.clampPsdToneVolume(toneVolumeOpen);
        data.psdToneVolumeClose = EscalatorSpeedData.clampPsdToneVolume(toneVolumeClose);
        data.psdToneAudioOpen = EscalatorSpeedData.normalizePsdToneAudio(toneAudioOpen);
        data.psdToneAudioClose = EscalatorSpeedData.normalizePsdToneAudio(toneAudioClose);
        data.psdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(closeWaitSeconds);
        data.psdMidiumAudio = EscalatorSpeedData.normalizePsdMidiumAudio(midiumAudio);
        data.psdMidiumWaitSeconds = EscalatorSpeedData.clampPsdMidiumWaitSeconds(midiumWaitSeconds);
        data.psdArriveAudio = EscalatorSpeedData.normalizePsdArriveAudio(arriveAudio);
        data.psdArriveSeconds = EscalatorSpeedData.clampPsdArriveSeconds(arriveSeconds);
        data.psdMidiumVolume = EscalatorSpeedData.clampPsdToneVolume(midiumVolume);
        data.psdArriveVolume = EscalatorSpeedData.clampPsdToneVolume(arriveVolume);
        data.psdMidiumRoundXz = EscalatorSpeedData.clampPsdMidiumRound(midiumRoundXz);
        data.psdMidiumRoundY = EscalatorSpeedData.clampPsdMidiumRound(midiumRoundY);
        data.psdArriveRoundXz = EscalatorSpeedData.clampPsdArriveRound(arriveRoundXz);
        data.psdArriveRoundY = EscalatorSpeedData.clampPsdArriveRound(arriveRoundY);
        data.psdNarrateMode = EscalatorSpeedData.clampPsdNarrateMode(narrateMode);
        data.psdNarrateSeconds = EscalatorSpeedData.clampPsdNarrateSeconds(narrateSeconds);
        data.psdMidiumNarrateMode = EscalatorSpeedData.clampPsdNarrateMode(midiumNarrateMode);
        data.psdMidiumNarrateSeconds = EscalatorSpeedData.clampPsdMidiumNarrateSeconds(midiumNarrateSeconds);
        clientPsdChimeGeneration++;
    }

    /** 见 {@link #applyClientPsdChime}。只在客户端线程读、在客户端线程写。 */
    public static long clientPsdChimeGeneration() {
        return clientPsdChimeGeneration;
    }

    /** 见 {@link #clientPsdChimeGeneration()}。 */
    private static long clientPsdChimeGeneration;

    /** 【1.50】客户端接收器：把同步包的「每扇门单独素材」整表覆盖进镜像。 */
    public static void applyClientPsdTone(ResourceKey<Level> dimension,
                                          Map<Long, EscalatorSpeedData.PsdToneAudio> tones) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.psdToneAudio.clear();
        data.psdToneAudio.putAll(tones);
        clientPsdToneGeneration++;
    }

    /** 【1.50】客户端：点完石斧界面某一行后**本地立即**改镜像一扇门的设置（权威值随后整表覆盖）。 */
    public static void applyClientPsdToneLocal(ResourceKey<Level> dimension, long key,
                                               EscalatorSpeedData.PsdToneAudio tone) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        if (tone.isEmpty()) {
            data.psdToneAudio.remove(key);
        } else {
            data.psdToneAudio.put(key, tone);
        }
        clientPsdToneGeneration++;
    }

    /**
     * 【1.20】客户端：石斧 UI 改完**这一扇门**的一项后**本地立即**改镜像。
     *
     * <p>与 {@link #applyClientPsdToneLocal} 同一件事，只是入口收成一个 {@code fn}：
     * UI 那边 5 个控件（总开关 / 子开关 / 音量 / 强制等待 / 到站播报）各自把「改哪一项」
     * 写成一个 lambda，本地回显与服务端写入就不会有第二套语义。
     *
     * <p>★ 为什么必须有本地回显：播放端（{@code PsdChimePlayer}）是**客户端**逐 tick 现算的，
     * 权威值要等一个来回才到；不回显就会出现「填完这一轮没反应、下一站才生效」。
     */
    public static void applyClientPsdDoorLocal(ResourceKey<Level> dimension, long key,
                                               java.util.function.UnaryOperator<EscalatorSpeedData.PsdToneAudio> fn) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        EscalatorSpeedData.PsdToneAudio old = data.psdToneAudio.get(key);
        if (old == null) {
            old = EscalatorSpeedData.PsdToneAudio.NONE;
        }
        applyClientPsdToneLocal(dimension, key, fn.apply(old));
    }

    /** 【1.50】「每扇门单独素材」镜像的代数（客户端播放端用来刷新缓存）。 */
    public static long clientPsdToneGeneration() {
        return clientPsdToneGeneration;
    }

    private static long clientPsdToneGeneration;
}