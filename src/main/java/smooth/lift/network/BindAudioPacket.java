package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.function.Supplier;

/** 客户端 -> 服务端：把一段音频绑定到某条扶梯（音频必须已入库，或是内置音频）。 */
public class BindAudioPacket {
    private final BlockPos pos;
    private final String audioId;

    public BindAudioPacket(BlockPos pos, String audioId) {
        this.pos = pos;
        this.audioId = audioId;
    }

    public static void encode(BindAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.audioId, 128);
    }

    public static BindAudioPacket decode(FriendlyByteBuf buf) {
        return new BindAudioPacket(buf.readBlockPos(), buf.readUtf(128));
    }

    public static void handle(BindAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (EscalatorSpeedManager.bindAudio(level, pkt.pos, pkt.audioId)) {
                player.displayClientMessage(Component.literal("已为这条扶梯绑定自定义声音"), true);
                EscalatorSpeedManager.syncAudioToAll(player.server);
            } else {
                player.displayClientMessage(Component.literal("绑定失败：音频不存在"), true);
            }
        });
        context.setPacketHandled(true);
    }
}
