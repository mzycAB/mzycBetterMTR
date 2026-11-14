package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;

/**
 * 【1.39.1204】电梯 BGM：直梯（MTR Lift）**移动时循环播放**玩家自己导入的 ogg。
 *
 * <h2>与开关门提示音的区别（用户点名：样式一样，只差这一条）</h2>
 * <ul>
 *   <li>{@link LiftChimePlayer} 是**事件性**的：门值一跳变，按排期播几下就完；</li>
 *   <li>本播放器是**持续性**的：只要附近有一条直梯 {@code move() != NONE}（有目标楼层、
 *       正在/准备移动），就把选中的 ogg 以 {@code looping = true} 挂在它身上；
 *       方向回到 {@code NONE}（到站停稳）或玩家走远/换世界，立刻把实例判停。</li>
 * </ul>
 *
 * <p>★ <b>没有默认音频</b>：{@code /liftbgm} 的取值只有「不播」与
 * {@code MBM_Audio/lift/liftbgm} 里导入过的文件名两种。没有内置 ogg、也不回落 liftmusic，
 * 所以不设就是彻底静音（用户点名「要用户自己导入」）。
 *
 * <h2>怎么知道「电梯在移动」</h2>
 * 复用 {@link MtrLiftAccess#snapshot(Level)} —— 与开关门 / 准备移动提示音同一份快照，
 * 不再自己反射 MTR。{@code Move} 是每 tick 从待办指令重算出来的（MTR3 的
 * {@code liftDirection} / MTR4 的 {@code getDirection()}），「有目标楼层」= 移动全程，
 * 「空闲」= NONE，正是用户说的「电梯移动时」。
 *
 * <h2>音量 / 声源</h2>
 * 与开关门提示音同一套旋钮（{@code /lifthelploud} 的音量、{@code /lifthelpround} 的水平/垂直
 * 淡入淡出范围）；声源坐标每 tick 跟着那条直梯走，所以乘梯途中 BGM 一直跟着轿厢。
 * 音量 &gt; 100 仍然要成对放开（实现 {@link GainManagedSound} + 抬 {@code AL_MAX_GAIN}），
 * 与 {@link LiftChimePlayer} 同一套做法。
 */
public final class LiftBgmPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 占位事件：自定义素材走注入，{@code resolve()} 会把事件换成注入进去的那一段。 */
    private static final ResourceLocation PLACEHOLDER_EVENT =
            new ResourceLocation("smoothlift", "audio/liftmusic");

    /** 当前正在循环的那一条实例；{@code null} = 没在播。 */
    private static LiftBgmInstance current;
    /** 当前实例用的素材名（改了就要重开一条）。 */
    private static String currentAudioId;

    private LiftBgmPlayer() {
    }

    /** 每客户端刻：找最近的「正在移动」的直梯，维持/切换/停掉循环实例。 */
    public static void onClientTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            stop();
            return;
        }
        String audioId = EscalatorSpeedManager.getLiftBgmAudio(mc.level);
        if (EscalatorSpeedData.LIFT_BGM_OFF.equals(audioId)) {
            stop();
            return;
        }
        MtrLiftAccess.LiftView moving = nearestMovingLift(mc);
        if (moving == null) {
            stop();
            return;
        }
        Vec3 pos = new Vec3(moving.x(), moving.y(), moving.z());
        // 【1.39.1204】BGM 有**自己**的音量（UI 里「电梯BGM设置…」按钮右边的输入框；-1 = 跟随共用默认）。
        float base = EscalatorSpeedData.clampLiftHelpVolume(
                EscalatorSpeedManager.getLiftBgmVolume(mc.level)) / 100.0f;

        // 素材换了 / 实例已被引擎回收 ⇒ 重新注入并起播；否则只更新位置与音量，不打断循环。
        if (current == null || current.isStopped() || !audioId.equals(currentAudioId)) {
            stop();
            if (!EscalatorAudioPlayer.injectAudio(mc, audioId)) {
                return; // 字节还没同步到 / 解码失败：下一 tick 再试，不刷屏
            }
            LiftBgmInstance inst = new LiftBgmInstance(audioId);
            inst.place(pos, base, mc);
            mc.getSoundManager().play(inst);
            raiseMaxGain(mc, inst);
            current = inst;
            currentAudioId = audioId;
            LOGGER.info("[SmoothLift/LiftBgm] 电梯 #{} 开始移动 → 循环播放 BGM「{}」",
                    moving.id(), audioId);
            return;
        }
        current.place(pos, base, mc);
    }

    /** 离玩家最近、且 {@code move() != NONE} 的那条直梯；没有就返回 null。 */
    private static MtrLiftAccess.LiftView nearestMovingLift(Minecraft mc) {
        Vec3 player = mc.player.position();
        MtrLiftAccess.LiftView best = null;
        double bestDist = Double.MAX_VALUE;
        for (MtrLiftAccess.LiftView lift : MtrLiftAccess.snapshot(mc.level)) {
            if (lift.move() == MtrLiftAccess.Move.NONE) {
                continue;
            }
            double dist = player.distanceTo(new Vec3(lift.x(), lift.y(), lift.z()));
            if (dist < bestDist) {
                bestDist = dist;
                best = lift;
            }
        }
        return best;
    }

    /** 停掉当前循环（把实例判停，交给原版 SoundEngine 回收通道）。 */
    private static void stop() {
        LiftBgmInstance inst = current;
        current = null;
        currentAudioId = null;
        if (inst != null) {
            inst.markStopped();
        }
    }

    /** 断线 / 换世界：实例与素材缓存一起作废。 */
    public static void onDisconnect() {
        stop();
    }

    /** 见 {@link LiftChimePlayer} 里同名方法：音量上限要「实例 + OpenAL 源」成对放开。 */
    private static void raiseMaxGain(Minecraft mc, LiftBgmInstance inst) {
        SoundEngine engine = mc.getSoundManager().soundEngine;
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
        if (handle != null) {
            handle.execute(ch -> AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN));
        }
    }

    /**
     * 距离增益：水平 / 垂直两维各按 {@code /lifthelpround} 线性淡出、取较小值。
     * 玩家不在世界里时按「贴脸」处理（与项目里其它播放器同一口径）。
     */
    private static float spatialGain(Minecraft mc, Vec3 pos) {
        if (mc.player == null || mc.level == null) {
            return 1.0f;
        }
        Vec3 player = mc.player.position();
        double roundXz = Math.max(1, EscalatorSpeedManager.getLiftHelpRound(mc.level));
        double roundY = Math.max(1, EscalatorSpeedManager.getLiftHelpRoundY(mc.level));
        double dxz = Math.hypot(player.x - pos.x, player.z - pos.z);
        double dy = Math.abs(player.y - pos.y);
        double f = Math.min(1.0 - dxz / roundXz, 1.0 - dy / roundY);
        return (float) Math.max(0.0f, f);
    }

    /**
     * 循环播放实例：{@code looping = true}，{@code isStopped()} 由本模组自己控制
     * （一次性实例能靠引擎「播完自动回收」，循环实例不会自己结束，必须我们判停）。
     */
    private static final class LiftBgmInstance extends AbstractSoundInstance
            implements TickableSoundInstance, GainManagedSound {

        private final String customId;
        private float baseVolume;
        private boolean stopped;

        LiftBgmInstance(String customId) {
            super(PLACEHOLDER_EVENT, SoundSource.BLOCKS, RandomSource.create());
            this.customId = customId;
            this.looping = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = false;
            this.volume = 0.0f;
            this.pitch = 1.0f;
        }

        /** 每 tick 更新声源位置（跟着电梯走）+ 设置音量；音量按玩家当前位置现算。 */
        void place(Vec3 pos, float baseVolume, Minecraft mc) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.baseVolume = baseVolume;
            this.volume = baseVolume * spatialGain(mc, pos);
        }

        @Override
        public void tick() {
            this.volume = stopped ? 0.0f
                    : baseVolume * spatialGain(Minecraft.getInstance(), new Vec3(this.x, this.y, this.z));
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        void markStopped() {
            this.stopped = true;
            this.volume = 0.0f;
        }

        /** 自定义素材：把事件解析成「引擎缓存里那段注入的音频」。 */
        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            ResourceLocation name = EscalatorAudioPlayer.soundLocation(customId);
            Sound s = new Sound(name.toString(), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F),
                    1, Sound.Type.FILE, false, false, 0);
            this.sound = s;
            WeighedSoundEvents events = new WeighedSoundEvents(name, null);
            events.addSound(s);
            return events;
        }
    }
}
