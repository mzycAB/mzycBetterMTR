package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.client.FolderOpenButton;

import java.util.function.Supplier;

/**
 * 【09-29 / Forge 移植】服务端 -> 客户端：打开存档里的某个「导入来源」文件夹
 * （{@code /MBM picture fold} 走这里）。载荷是相对存档根目录的路径（如 MBM_Picture）；
 * 客户端落到 {@link FolderOpenButton#open}（白名单 + 建目录 + 开窗口）。
 */
public class MbmOpenFolderPacket {
    private final String relativePath;

    public MbmOpenFolderPacket(String relativePath) {
        this.relativePath = relativePath;
    }

    public static void encode(MbmOpenFolderPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.relativePath, 64);
    }

    public static MbmOpenFolderPacket decode(FriendlyByteBuf buf) {
        return new MbmOpenFolderPacket(buf.readUtf(64));
    }

    public static void handle(MbmOpenFolderPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> FolderOpenButton.open(pkt.relativePath));
        context.setPacketHandled(true);
    }
}
