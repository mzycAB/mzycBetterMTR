package smooth.lift.net;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import smooth.lift.client.SlClientNet;

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

    /**
     * 与 Fabric 的 {@code ClientPlayNetworking.PlayChannelHandler} 同形。
     *
     * <p>★★【1.35 说明】这个**接口签名**里出现 {@code Minecraft} 是安全的：接口没有方法体
     * （没有指令），JVM 只在**调用那一刻**才解析参数类型。真正会要命的是「被加载的类的方法体里
     * 出现 {@code Minecraft.getInstance()}」——那一处已经整块挪到
     * {@code smooth.lift.client.SlClientNet}（见 {@link ToClient#handle} 的注释）。
     * 守卫脚本按「指令级」判据盯着 {@code SLNet$ToClient.class}。
     */
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

    /**
     * ★★【1.35】按频道取客户端回调 —— 只给 {@link SlClientNet} 用。
     *
     * <p>为什么要有这个口：{@link ToClient#handle} 里**不许**再出现
     * {@code Minecraft.getInstance()}（那会让专用服务器在 mod 构造期加载类时被
     * {@code RuntimeDistCleaner} 拒绝）。客户端动作整块挪到 {@link SlClientNet}，
     * 这里只留「取一个回调」——本方法的描述符只提到 {@code ClientReceiver}，**不带**
     * {@code net/minecraft/client} 类型，可以安全地被公共类持有。
     *
     * @param channel 频道 id
     * @return 该频道的客户端回调；没注册过 ⇒ {@code null}
     */
    public static ClientReceiver clientReceiver(ResourceLocation channel) {
        return CLIENT_RECEIVERS.get(channel);
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
            // ★★【1.35 服务器兼容修复】这里**绝对不许**出现 net/minecraft/client 类型。
            //   本类在 mod 构造期（SLNet.init() 的 messageBuilder）就会被加载并被 HotSpot
            //   验证全部方法（含 lambda 合成方法）⇒ 以前那句 Minecraft.getInstance()
            //   会让专用服务器直接崩：
            //     Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER
            //   ⇒ 客户端动作整块挪到 smooth.lift.client.SlClientNet，这里只留一个方法引用；
            //   DistExecutor 在专用服务器上**不求值** supplier ⇒ 那个类永远不会被加载。
            //   判据：SLNet$ToClient.class 字节里不许有 net/minecraft/client
            //   （守卫脚本 _tools/check-server-dist.py）。
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> SlClientNet.deliver(channel, data)));
            ctx.setPacketHandled(true);
        }
    }
}