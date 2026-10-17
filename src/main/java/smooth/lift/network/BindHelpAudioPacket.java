package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【1.41】客户端 -> 服务端：把一段音频设为这条扶梯**某一头**的无障碍提示音音乐。
 *
 * <p>与 {@link BindAudioPacket}（运行底噪）完全对称，但数据独立；
 * 音频字节共用同一个 {@code audioLibrary}（同一个导入文件夹，导入一次两边都能选）。
 *
 * <p>buf 顺序：{@code pos, audioId, in} —— {@code in = true} 表示「进入扶梯（上客端）」那一头。
 */
public class BindHelpAudioPacket {
    private final BlockPos pos;
    private final String audioId;
    private final boolean in;

    public BindHelpAudioPacket(BlockPos pos, String audioId, boolean in) {
        this.pos = pos;
        this.audioId = audioId;
        this.in = in;
    }

    public static void encode(BindHelpAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.audioId, 128);
        buf.writeBoolean(pkt.in);
    }

    public static BindHelpAudioPacket decode(FriendlyByteBuf buf) {
        return new BindHelpAudioPacket(buf.readBlockPos(), buf.readUtf(128), buf.readBoolean());
    }

    public static void handle(BindHelpAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!EscalatorUtil.isEscalator(level.getBlockState(pkt.pos))) {
                return;
            }
            if (EscalatorSpeedManager.bindHelpAudio(level, pkt.pos, pkt.audioId, pkt.in)) {
                player.displayClientMessage(Component.literal(
                        EscalatorSpeedData.HELP_AUDIO_OFF.equals(pkt.audioId)
                                ? "这条扶梯" + SmoothLift.helpEndLabel(pkt.in) + "的无障碍提示音已设为「不播」"
                                : "已把这段声音设为这条扶梯" + SmoothLift.helpEndLabel(pkt.in)
                                        + "的无障碍提示音"), true);
                EscalatorSpeedManager.syncHelpAudioToAll(player.server);
            } else {
                player.displayClientMessage(Component.literal(
                        "设置失败：音频不存在（先在提示音选择界面里导入 .ogg）"), true);
            }
        });
        context.setPacketHandled(true);
    }
}
