package smooth.lift.net;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Forge 侧的「频道式」网络层，刻意做成 Fabric 那套 {@code Client/ServerPlayNetworking}
 * 的等价物：注册回调与发送方法的**签名逐字对齐**，这样 1.20.4 源码里那几十个
 * {@code (server, player, handler, buf, responseSender) -> ...} 的 lambda 可以原样保留。
 *
 * <p>实现上只用一条 {@link SimpleChannel}，载荷统一是「频道 id + 原始字节」，
 * 再由这里的映射表派发到对应的回调 —— 频道数量多（60+）但每个都很小，这样最省事，
 * 也和 Fabric 侧「一条自定义载荷带一个频道名」的语义一致。
 */
public final class SLNet {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("smoothlift", "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    /** 与 Fabric 的 {@code ServerPlayNetworking.PlayChannelHandler} 同形（handler / responseSender 保留但不使用）。 */
    @FunctionalInterface
    public interface ServerReceiver {
        void receive(MinecraftServer server, ServerPlayer player, Object handler, FriendlyByteBuf buf, Object responseSender);
    }

    /** 与 Fabric 的 {@code ClientPlayNetworking.PlayChannelHandler} 同形。 */
    @FunctionalInterface
    public interface ClientReceiver {
        void receive(Minecraft client, Object handler, FriendlyByteBuf buf, Object responseSender);
    }

    private static final Map<ResourceLocation, ServerReceiver> SERVER_RECEIVERS = new HashMap<>();
    private static final Map<ResourceLocation, ClientReceiver> CLIENT_RECEIVERS = new HashMap<>();

    private static boolean messagesRegistered;

    private SLNet() {
    }

    public static void init() {
        if (messagesRegistered) {
            return;
        }
        messagesRegistered = true;
        CHANNEL.messageBuilder(ToServer.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ToServer::encode)
                .decoder(ToServer::new)
                .consumer(ToServer::handle)
                .add();
        CHANNEL.messageBuilder(ToClient.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ToClient::encode)
                .decoder(ToClient::new)
                .consumer(ToClient::handle)
                .add();
    }

    public static void registerServer(ResourceLocation channel, ServerReceiver receiver) {
        SERVER_RECEIVERS.put(channel, receiver);
    }

    public static void registerClient(ResourceLocation channel, ClientReceiver receiver) {
        CLIENT_RECEIVERS.put(channel, receiver);
    }

    public static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public static FriendlyByteBuf emptyBuf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToClient(channel, drain(payload)));
    }

    public static void sendToServer(ResourceLocation channel, FriendlyByteBuf payload) {
        CHANNEL.sendToServer(new ToServer(channel, drain(payload)));
    }

    private static byte[] drain(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;

    public static final class ToServer {
        private final ResourceLocation channel;
        private final byte[] data;

        ToServer(ResourceLocation channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }

        ToServer(FriendlyByteBuf buf) {
            this.channel = buf.readResourceLocation();
            this.data = buf.readByteArray(MAX_PAYLOAD);
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(channel);
            buf.writeByteArray(data);
        }

        void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ServerPlayer player = ctx.getSender();
            ctx.enqueueWork(() -> {
                ServerReceiver receiver = SERVER_RECEIVERS.get(channel);
                if (receiver == null || player == null) {
                    return;
                }
                MinecraftServer server = player.getServer();
                receiver.receive(server, player, null, new FriendlyByteBuf(Unpooled.wrappedBuffer(data)), null);
            });
            ctx.setPacketHandled(true);
        }
    }

    public static final class ToClient {
        private final ResourceLocation channel;
        private final byte[] data;

        ToClient(ResourceLocation channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }

        ToClient(FriendlyByteBuf buf) {
            this.channel = buf.readResourceLocation();
            this.data = buf.readByteArray(MAX_PAYLOAD);
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(channel);
            buf.writeByteArray(data);
        }

        void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ClientReceiver receiver = CLIENT_RECEIVERS.get(channel);
                if (receiver == null) {
                    return;
                }
                receiver.receive(Minecraft.getInstance(), null,
                        new FriendlyByteBuf(Unpooled.wrappedBuffer(data)), null);
            });
            ctx.setPacketHandled(true);
        }
    }
}