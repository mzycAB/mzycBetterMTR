package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;
import java.util.function.Supplier;

/**
 * 【1.55】客户端 -> 服务端：「同步所有」弹窗。buf: domain(utf16)→scope(varInt)→force(boolean)→key(long)
 *
 * <p>【10-04 修 2】末尾追加一格 {@code extraKey}(long)：PSD 主界面多了一个**门串级**设置
 * （「关门后等待发车」，键 = 门串锚点），弹窗只带一个 key（车站级 runKey）服务端读不到
 * 「这一串此刻生效的发车等待」——主页的同步按钮把 runAnchor 塞进这一格；其它域传 0。
 */
public class SyncSettingsPacket {
    private final String domain;
    private final int scope;
    private final boolean force;
    private final long key;
    private final long extraKey;

    public SyncSettingsPacket(String domain, int scope, boolean force, long key) {
        this(domain, scope, force, key, 0L);
    }

    public SyncSettingsPacket(String domain, int scope, boolean force, long key, long extraKey) {
        this.domain = domain;
        this.scope = scope;
        this.force = force;
        this.key = key;
        this.extraKey = extraKey;
    }

    public static void encode(SyncSettingsPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.domain, 16);
        buf.writeVarInt(pkt.scope);
        buf.writeBoolean(pkt.force);
        buf.writeLong(pkt.key);
        buf.writeLong(pkt.extraKey);
    }

    public static SyncSettingsPacket decode(FriendlyByteBuf buf) {
        return new SyncSettingsPacket(buf.readUtf(16), buf.readVarInt(), buf.readBoolean(),
                buf.readLong(), buf.readLong());
    }

    public static void handle(SyncSettingsPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            String answer = SmoothLift.syncSettings(player.server, level, pkt.domain, pkt.scope,
                    pkt.force, pkt.key, pkt.extraKey);
            // 【10-04 修 3】UI 弹窗不再把服务端那句长总结打进聊天栏（用户点名「应该改为操作成功失败」，
            //   与【10-01】其它 UI 操作的口径一致）：成功 / 失败并进本次界面会话的结果，
            //   退出界面时由 UiClosePacket 统一回一条「UI执行成功 / UI执行失败」。
            //   ★ 判据：同步域/页写错时 syncSettings 回「同步失败：…」；列车域还没接数据层时
            //     回「还没接数据层」—— 那一条什么都不曾改，必须算失败，不许谎报成功。
            //     详细原因不打扰玩家，命令行 /pbm... 的详细回话不受影响（那是另一条路径）。
            boolean ok = !answer.startsWith("同步失败") && !answer.contains("还没接数据层");
            SmoothLift.noteUiResult(player, ok);
        });
        context.setPacketHandled(true);
    }
}
