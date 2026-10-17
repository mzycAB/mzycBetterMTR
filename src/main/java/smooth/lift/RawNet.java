package smooth.lift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【1.30.1211 移植】1.20.4 → 1.21.1 的网络层适配。
 *
 * <p>Fabric API 自 1.20.5 起删除了「ResourceLocation + FriendlyByteBuf」的旧式网络 API
 * （{@code registerGlobalReceiver(channel, handler)} / {@code send(player, channel, buf)}），
 * 只保留 {@link CustomPacketPayload} + {@link StreamCodec} 的 payload API。本模组 1.20.4 有
 * 30 多个「字节流」频道，全部是「打开 buf 按固定顺序 readXxx」的自定义协议 —— 为了**不改动任何
 * 一个频道的字节布局**（保证与 1.20.4 的服务端/客户端互操作的读写顺序逐字节一致），这里做一个
 * 最小适配层：
 *
 * <ul>
 *   <li>{@link Payload} = 「频道 id + 原始 FriendlyByteBuf」，本模组所有频道共用这一个类；</li>
 *   <li>编解码只做**字节原样搬运**（encode 把 buf 的内容拷进输出流，decode 把收到的字节再包一层
 *       FriendlyByteBuf），不解释任何字段；</li>
 *   <li>每个频道一个 {@link CustomPacketPayload.Type} 实例（按 id 缓存，Type 是 record、按 id
 *       相等），编解码器闭包捕获频道 id ⇒ 收到的 payload 一定能回答自己的频道是谁。</li>
 * </ul>
 *
 * <p>使用方式与 1.20.4 一一对应：
 * <ul>
 *   <li>注册接收器：{@code ServerPlayNetworking.registerGlobalReceiver(RawNet.type(CH), (payload, ctx) -> { var buf = payload.data(); ... })}</li>
 *   <li>服务端发：{@code ServerPlayNetworking.send(player, RawNet.s2c(CH, buf))}</li>
 *   <li>客户端发：{@code ClientPlayNetworking.send(RawNet.c2s(CH, buf))}</li>
 * </ul>
 */
public final class RawNet {

    /** 「频道 id + 未解码字节」的通用 payload：本模组所有频道共用这一个类。 */
    public record Payload(ResourceLocation channel, FriendlyByteBuf data) implements CustomPacketPayload {
        @Override
        public Type<Payload> type() {
            return RawNet.type(channel);
        }
    }

    private static final Map<ResourceLocation, CustomPacketPayload.Type<Payload>> TYPES = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> REGISTERED = ConcurrentHashMap.newKeySet();

    /**
     * 每个频道一个 Type 实例（全进程共享）。调用它会**顺带注册**该频道的双向编解码 ——
     * 注册动作幂等（{@link #REGISTERED} 去重），且必须发生在任何收发之前；
     * 接收器注册都在模组初始化阶段完成，天然满足这个时序。
     */
    public static CustomPacketPayload.Type<Payload> type(ResourceLocation channel) {
        CustomPacketPayload.Type<Payload> type = TYPES.computeIfAbsent(channel, CustomPacketPayload.Type::new);
        if (REGISTERED.add(channel)) {
            // 两个方向都要注册：发给对方时对方要用同一套 codec 解开（字节布局对称）。
            PayloadTypeRegistry.playC2S().register(type, codec(channel));
            PayloadTypeRegistry.playS2C().register(type, codec(channel));
        }
        return type;
    }

    /**
     * 该频道的编解码器：字节原样搬运，不做任何格式转换。闭包捕获 id，decode 回填 channel 字段。
     *
     * <p>注意 decode 收到的 buf **只含 payload 本体**（频道 id 已由协议层消费），encode 时协议层
     * 会用 {@code payload.type().id()} 自己写 id —— 所以两侧都不需要（也不能）在这里碰 id。
     */
    public static StreamCodec<FriendlyByteBuf, Payload> codec(ResourceLocation channel) {
        return new StreamCodec<>() {
            @Override
            public Payload decode(FriendlyByteBuf buf) {
                // 拷贝一份字节再包 FriendlyByteBuf：payload 可能被接收线程之外的逻辑持有，
                // 不与协议层临时缓冲共享底层存储。
                return new Payload(channel, new FriendlyByteBuf(buf.readBytes(buf.readableBytes())));
            }

            @Override
            public void encode(FriendlyByteBuf buf, Payload payload) {
                FriendlyByteBuf d = payload.data();
                d.readerIndex(0);
                buf.writeBytes(d);
            }
        };
    }

    /** 服务端 → 客户端 发包（对应 1.20.4 的 {@code ServerPlayNetworking.send(player, ch, buf)}）。 */
    public static Payload s2c(ResourceLocation channel, FriendlyByteBuf buf) {
        type(channel); // 确保该频道的编解码已注册（专用服务器上可能没有客户端来注册 S2C 频道）
        return new Payload(channel, buf);
    }

    /** 客户端 → 服务端 发包（对应 1.20.4 的 {@code ClientPlayNetworking.send(ch, buf)}）。 */
    public static Payload c2s(ResourceLocation channel, FriendlyByteBuf buf) {
        type(channel); // 同上，双向兜底
        return new Payload(channel, buf);
    }

    private RawNet() {
    }
}
