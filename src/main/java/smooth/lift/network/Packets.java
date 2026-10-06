package smooth.lift.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Packets {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("smoothlift", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    private Packets() {
    }

    public static void register() {
        CHANNEL.messageBuilder(SetSpeedPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetSpeedPacket::encode)
                .decoder(SetSpeedPacket::decode)
                .consumerMainThread(SetSpeedPacket::handle)
                .add();
        CHANNEL.messageBuilder(ApplyChainPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ApplyChainPacket::encode)
                .decoder(ApplyChainPacket::decode)
                .consumerMainThread(ApplyChainPacket::handle)
                .add();
        CHANNEL.messageBuilder(RequestSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestSyncPacket::encode)
                .decoder(RequestSyncPacket::decode)
                .consumerMainThread(RequestSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncPacket::encode)
                .decoder(SyncPacket::decode)
                .consumerMainThread(SyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetStepSpeedPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetStepSpeedPacket::encode)
                .decoder(SetStepSpeedPacket::decode)
                .consumerMainThread(SetStepSpeedPacket::handle)
                .add();
        CHANNEL.messageBuilder(AlignStepPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AlignStepPacket::encode)
                .decoder(AlignStepPacket::decode)
                .consumerMainThread(AlignStepPacket::handle)
                .add();
        CHANNEL.messageBuilder(RestoreStepPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RestoreStepPacket::encode)
                .decoder(RestoreStepPacket::decode)
                .consumerMainThread(RestoreStepPacket::handle)
                .add();
        // 【1.7】自定义扶梯声音：客户端 -> 服务端
        CHANNEL.messageBuilder(BindAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BindAudioPacket::encode)
                .decoder(BindAudioPacket::decode)
                .consumerMainThread(BindAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnbindAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UnbindAudioPacket::encode)
                .decoder(UnbindAudioPacket::decode)
                .consumerMainThread(UnbindAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(DeleteAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DeleteAudioPacket::encode)
                .decoder(DeleteAudioPacket::decode)
                .consumerMainThread(DeleteAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportFolderAudioPacket::encode)
                .decoder(ImportFolderAudioPacket::decode)
                .consumerMainThread(ImportFolderAudioPacket::handle)
                .add();
        // 【1.9】声音音量：客户端 -> 服务端
        CHANNEL.messageBuilder(SetVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetVolumePacket::encode)
                .decoder(SetVolumePacket::decode)
                .consumerMainThread(SetVolumePacket::handle)
                .add();
        // 【1.7】服务端 -> 客户端：分块音频同步
        CHANNEL.messageBuilder(AudioSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AudioSyncPacket::encode)
                .decoder(AudioSyncPacket::decode)
                .consumerMainThread(AudioSyncPacket::handle)
                .add();
        // 【1.9】服务端 -> 客户端：音量表同步
        CHANNEL.messageBuilder(VolumeSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VolumeSyncPacket::encode)
                .decoder(VolumeSyncPacket::decode)
                .consumerMainThread(VolumeSyncPacket::handle)
                .add();
        // 【1.16】无障碍提示音开关：客户端 -> 服务端 + 服务端 -> 客户端
        CHANNEL.messageBuilder(SetHelpPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetHelpPacket::encode)
                .decoder(SetHelpPacket::decode)
                .consumerMainThread(SetHelpPacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpSyncPacket::encode)
                .decoder(HelpSyncPacket::decode)
                .consumerMainThread(HelpSyncPacket::handle)
                .add();
        // 【1.18】无障碍提示音音量：客户端 -> 服务端 + 服务端 -> 客户端
        CHANNEL.messageBuilder(SetHelpVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetHelpVolumePacket::encode)
                .decoder(SetHelpVolumePacket::decode)
                .consumerMainThread(SetHelpVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpVolumeSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpVolumeSyncPacket::encode)
                .decoder(HelpVolumeSyncPacket::decode)
                .consumerMainThread(HelpVolumeSyncPacket::handle)
                .add();
        // 【1.24】两个淡入淡出范围（底噪 / 提示音）：只有服务端 -> 客户端
        CHANNEL.messageBuilder(RoundSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RoundSyncPacket::encode)
                .decoder(RoundSyncPacket::decode)
                .consumerMainThread(RoundSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpRoundSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpRoundSyncPacket::encode)
                .decoder(HelpRoundSyncPacket::decode)
                .consumerMainThread(HelpRoundSyncPacket::handle)
                .add();
        // 【1.31】无障碍提示音速率（进 / 出两套合成一只包）：只有服务端 -> 客户端
        CHANNEL.messageBuilder(HelpSpeedSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpSpeedSyncPacket::encode)
                .decoder(HelpSpeedSyncPacket::decode)
                .consumerMainThread(HelpSpeedSyncPacket::handle)
                .add();
        // 【1.41】无障碍提示音音乐（进 / 出两套合成一只包）：服务端 -> 客户端 + 客户端 -> 服务端
        CHANNEL.messageBuilder(HelpAudioSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpAudioSyncPacket::encode)
                .decoder(HelpAudioSyncPacket::decode)
                .consumerMainThread(HelpAudioSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(BindHelpAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BindHelpAudioPacket::encode)
                .decoder(BindHelpAudioPacket::decode)
                .consumerMainThread(BindHelpAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnbindHelpAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UnbindHelpAudioPacket::encode)
                .decoder(UnbindHelpAudioPacket::decode)
                .consumerMainThread(UnbindHelpAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderHelpAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportFolderHelpAudioPacket::encode)
                .decoder(ImportFolderHelpAudioPacket::decode)
                .consumerMainThread(ImportFolderHelpAudioPacket::handle)
                .add();
        // 【1.42/1.43/1.46/1.47/1.48】直梯开关门提示音设置：服务端 -> 客户端
        CHANNEL.messageBuilder(LiftChimeSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LiftChimeSyncPacket::encode)
                .decoder(LiftChimeSyncPacket::decode)
                .consumerMainThread(LiftChimeSyncPacket::handle)
                .add();
        // 【1.45】直梯楼层轨道提示音（石斧界面设置）：服务端 -> 客户端 + 客户端 -> 服务端
        CHANNEL.messageBuilder(LiftToneSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LiftToneSyncPacket::encode)
                .decoder(LiftToneSyncPacket::decode)
                .consumerMainThread(LiftToneSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetLiftTonePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetLiftTonePacket::encode)
                .decoder(SetLiftTonePacket::decode)
                .consumerMainThread(SetLiftTonePacket::handle)
                .add();
        // 【1.46】三提示音独立子开关：客户端 -> 服务端
        CHANNEL.messageBuilder(SetLiftToneSwitchPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetLiftToneSwitchPacket::encode)
                .decoder(SetLiftToneSwitchPacket::decode)
                .consumerMainThread(SetLiftToneSwitchPacket::handle)
                .add();
        // 【1.48】直梯提示音音量（共用默认 + 三项各自）：客户端 -> 服务端
        CHANNEL.messageBuilder(SetLiftChimeVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetLiftChimeVolumePacket::encode)
                .decoder(SetLiftChimeVolumePacket::decode)
                .consumerMainThread(SetLiftChimeVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetLiftToneVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetLiftToneVolumePacket::encode)
                .decoder(SetLiftToneVolumePacket::decode)
                .consumerMainThread(SetLiftToneVolumePacket::handle)
                .add();
        // 【1.45】从文件夹导入 OGG 并设为直梯提示音：客户端 -> 服务端
        CHANNEL.messageBuilder(ImportFolderLiftTonePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportFolderLiftTonePacket::encode)
                .decoder(ImportFolderLiftTonePacket::decode)
                .consumerMainThread(ImportFolderLiftTonePacket::handle)
                .add();
        // 【1.50~1.58】屏蔽门（PSD / APG）提示音：客户端 -> 服务端
        CHANNEL.messageBuilder(SetPsdTonePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdTonePacket::encode)
                .decoder(SetPsdTonePacket::decode)
                .consumerMainThread(SetPsdTonePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdToneSwitchPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdToneSwitchPacket::encode)
                .decoder(SetPsdToneSwitchPacket::decode)
                .consumerMainThread(SetPsdToneSwitchPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdToneVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdToneVolumePacket::encode)
                .decoder(SetPsdToneVolumePacket::decode)
                .consumerMainThread(SetPsdToneVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdChimeVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdChimeVolumePacket::encode)
                .decoder(SetPsdChimeVolumePacket::decode)
                .consumerMainThread(SetPsdChimeVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdOpenWaitPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdOpenWaitPacket::encode)
                .decoder(SetPsdOpenWaitPacket::decode)
                .consumerMainThread(SetPsdOpenWaitPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdCloseWaitPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdCloseWaitPacket::encode)
                .decoder(SetPsdCloseWaitPacket::decode)
                .consumerMainThread(SetPsdCloseWaitPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdMidiumPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdMidiumPacket::encode)
                .decoder(SetPsdMidiumPacket::decode)
                .consumerMainThread(SetPsdMidiumPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdMidiumLoudPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdMidiumLoudPacket::encode)
                .decoder(SetPsdMidiumLoudPacket::decode)
                .consumerMainThread(SetPsdMidiumLoudPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdArrivePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdArrivePacket::encode)
                .decoder(SetPsdArrivePacket::decode)
                .consumerMainThread(SetPsdArrivePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdArriveLoudPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdArriveLoudPacket::encode)
                .decoder(SetPsdArriveLoudPacket::decode)
                .consumerMainThread(SetPsdArriveLoudPacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderPsdTonePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportFolderPsdTonePacket::encode)
                .decoder(ImportFolderPsdTonePacket::decode)
                .consumerMainThread(ImportFolderPsdTonePacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportPsdMidiumAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportPsdMidiumAudioPacket::encode)
                .decoder(ImportPsdMidiumAudioPacket::decode)
                .consumerMainThread(ImportPsdMidiumAudioPacket::handle)
                .add();
        // 【1.53】MBM 预设选择 / 全音量：客户端 -> 服务端
        CHANNEL.messageBuilder(MbmPresetPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MbmPresetPacket::encode)
                .decoder(MbmPresetPacket::decode)
                .consumerMainThread(MbmPresetPacket::handle)
                .add();
        CHANNEL.messageBuilder(MbmAllVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MbmAllVolumePacket::encode)
                .decoder(MbmAllVolumePacket::decode)
                .consumerMainThread(MbmAllVolumePacket::handle)
                .add();
        // 【1.55】「同步所有」弹窗：客户端 -> 服务端
        CHANNEL.messageBuilder(SyncSettingsPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SyncSettingsPacket::encode)
                .decoder(SyncSettingsPacket::decode)
                .consumerMainThread(SyncSettingsPacket::handle)
                .add();
        // 【1.50】屏蔽门提示音 / 每扇门素材：服务端 -> 客户端
        CHANNEL.messageBuilder(PsdChimeSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PsdChimeSyncPacket::encode)
                .decoder(PsdChimeSyncPacket::decode)
                .consumerMainThread(PsdChimeSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(PsdToneSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PsdToneSyncPacket::encode)
                .decoder(PsdToneSyncPacket::decode)
                .consumerMainThread(PsdToneSyncPacket::handle)
                .add();
        // 【1.53】服务端 -> 客户端：打开「预设选择」界面
        CHANNEL.messageBuilder(MbmHelpOpenPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MbmHelpOpenPacket::encode)
                .decoder(MbmHelpOpenPacket::decode)
                .consumerMainThread(MbmHelpOpenPacket::handle)
                .add();
        // 【09-28~10-01 / 1.29 移植】屏蔽门讲述人 + 闸机提示音 + UI 结果汇总 + 图片库
        CHANNEL.messageBuilder(SetPsdNarratePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdNarratePacket::encode)
                .decoder(SetPsdNarratePacket::decode)
                .consumerMainThread(SetPsdNarratePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdNarrateLeadPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdNarrateLeadPacket::encode)
                .decoder(SetPsdNarrateLeadPacket::decode)
                .consumerMainThread(SetPsdNarrateLeadPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdMidiumNarratePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdMidiumNarratePacket::encode)
                .decoder(SetPsdMidiumNarratePacket::decode)
                .consumerMainThread(SetPsdMidiumNarratePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetPsdMidiumNarrateLeadPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdMidiumNarrateLeadPacket::encode)
                .decoder(SetPsdMidiumNarrateLeadPacket::decode)
                .consumerMainThread(SetPsdMidiumNarrateLeadPacket::handle)
                .add();
        // 【10-03 五改 / Forge】「关门后等待 X 秒发车」：门串级（键 = 门串锚点 + 站台 id + 秒数）。
        CHANNEL.messageBuilder(SetPsdDepartDelayPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdDepartDelayPacket::encode)
                .decoder(SetPsdDepartDelayPacket::decode)
                .consumerMainThread(SetPsdDepartDelayPacket::handle)
                .add();
        // 【10-01】石斧讲述人页：整份自定义文字写进存档（进站 / 站台各一份，按存档存）。
        CHANNEL.messageBuilder(SetPsdNarrateTextsPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPsdNarrateTextsPacket::encode)
                .decoder(SetPsdNarrateTextsPacket::decode)
                .consumerMainThread(SetPsdNarrateTextsPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetZhajiTonePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetZhajiTonePacket::encode)
                .decoder(SetZhajiTonePacket::decode)
                .consumerMainThread(SetZhajiTonePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetZhajiVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetZhajiVolumePacket::encode)
                .decoder(SetZhajiVolumePacket::decode)
                .consumerMainThread(SetZhajiVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderZhajiTonePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportFolderZhajiTonePacket::encode)
                .decoder(ImportFolderZhajiTonePacket::decode)
                .consumerMainThread(ImportFolderZhajiTonePacket::handle)
                .add();
        CHANNEL.messageBuilder(UiClosePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UiClosePacket::encode)
                .decoder(UiClosePacket::decode)
                .consumerMainThread(UiClosePacket::handle)
                .add();
        CHANNEL.messageBuilder(ZhajiToneSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ZhajiToneSyncPacket::encode)
                .decoder(ZhajiToneSyncPacket::decode)
                .consumerMainThread(ZhajiToneSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(PictureSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PictureSyncPacket::encode)
                .decoder(PictureSyncPacket::decode)
                .consumerMainThread(PictureSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(MbmOpenFolderPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MbmOpenFolderPacket::encode)
                .decoder(MbmOpenFolderPacket::decode)
                .consumerMainThread(MbmOpenFolderPacket::handle)
                .add();
        // 【10-05】pbm* 指令不带 -f：请求客户端算「最近那一串」（S2C 空包）+ 回包（C2S）
        CHANNEL.messageBuilder(PsdNearestRequestPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PsdNearestRequestPacket::encode)
                .decoder(PsdNearestRequestPacket::decode)
                .consumerMainThread(PsdNearestRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(PsdNearestReplyPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PsdNearestReplyPacket::encode)
                .decoder(PsdNearestReplyPacket::decode)
                .consumerMainThread(PsdNearestReplyPacket::handle)
                .add();
    }
}
