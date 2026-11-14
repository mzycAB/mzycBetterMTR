package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【1.45】客户端 -> 服务端：把 smoothlift_audio 文件夹里的一个 OGG 导入存档，
 * 并设为某条直梯的某一项提示音。
 *
 * <p>buf 顺序：{@code key(long), which(utf), fileName(utf)}。
 */
public class ImportFolderLiftTonePacket {
    private final long key;
    private final String which;
    private final String fileName;

    public ImportFolderLiftTonePacket(long key, String which, String fileName) {
        this.key = key;
        this.which = which;
        this.fileName = fileName;
    }

    public static void encode(ImportFolderLiftTonePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.which, 32);
        buf.writeUtf(pkt.fileName, 128);
    }

    public static ImportFolderLiftTonePacket decode(FriendlyByteBuf buf) {
        return new ImportFolderLiftTonePacket(buf.readLong(), buf.readUtf(32), buf.readUtf(128));
    }

    public static void handle(ImportFolderLiftTonePacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            // 【1.39.11201】电梯 BGM 复用同一条「选中即导入」通道：
            //   which = "bgm" 时 key 不参与（BGM 是按维度一份，不是按竖井列），
            //   导入到 MBM_Audio/lift/liftbgm 后直接设为这一维度的 BGM。
            if ("bgm".equals(pkt.which)) {
                String bgmProblem = EscalatorSpeedManager.importAudioToStore(
                        level, EscalatorSpeedManager.CAT_LIFT_BGM, pkt.fileName);
                if (bgmProblem == null && EscalatorSpeedManager.setLiftBgmAudio(level, pkt.fileName)) {
                    SmoothLift.noteUiResult(player, true);
                    EscalatorSpeedManager.syncAudioToAll(player.server);
                    EscalatorSpeedManager.syncLiftBgmToAll(player.server);
                } else {
                    SmoothLift.noteUiResult(player, false);
                    EscalatorSpeedManager.syncAudioToAll(player.server);
                }
                return;
            }
            String problem = EscalatorSpeedManager.importAudioToStore(
                    level, EscalatorSpeedManager.liftToneCategory(pkt.which), pkt.fileName);
            if (problem == null) {
                if (EscalatorSpeedManager.setServerLiftTone(level, pkt.key, pkt.which, pkt.fileName)) {
                    player.displayClientMessage(Component.literal(
                            "已从文件夹导入并设为这条直梯的"
                                    + SmoothLift.liftToneLabel(pkt.which, pkt.fileName)
                                    + "（原文件删除后仍可播放）"), true);
                    EscalatorSpeedManager.syncAudioToAll(player.server);
                    EscalatorSpeedManager.syncLiftToneToAll(player.server);
                } else {
                    player.displayClientMessage(Component.literal("设置失败：导入成功但绑定失败"), true);
                    EscalatorSpeedManager.syncAudioToAll(player.server);
                }
            } else {
                player.displayClientMessage(Component.literal("导入失败：" + problem), true);
            }
        });
        context.setPacketHandled(true);
    }
}
