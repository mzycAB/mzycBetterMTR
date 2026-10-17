package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;
import java.util.function.Supplier;

/**
 * 【1.53】客户端 -> 服务端：MBM help 预设选择界面点按钮 → 依次执行预设指令。buf: presetId(utf16)
 */
public class MbmPresetPacket {
    private final String presetId;

    public MbmPresetPacket(String presetId) {
        this.presetId = presetId;
    }

    public static void encode(MbmPresetPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.presetId, 16);
    }

    public static MbmPresetPacket decode(FriendlyByteBuf buf) {
        return new MbmPresetPacket(buf.readUtf(16));
    }

    public static void handle(MbmPresetPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            int count = SmoothLift.applyPreset(player, pkt.presetId);
            if (count == 0) {
                player.displayClientMessage(Component.literal(
                        "未知预设：" + pkt.presetId), false);
                return;
            }
            player.displayClientMessage(Component.literal(
                    "已应用" + SmoothLift.presetLabel(pkt.presetId) + "（依次执行 " + count + " 条指令）"), false);

        });
        context.setPacketHandled(true);
    }
}
