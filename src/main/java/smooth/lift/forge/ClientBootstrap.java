package smooth.lift.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;

import smooth.lift.compat.ClientCommandRegistrationCallback;
import smooth.lift.compat.ClientChunkEvents;
import smooth.lift.compat.ClientTickEvents;
import smooth.lift.compat.ClientPlayConnectionEvents;
import smooth.lift.compat.HudRenderCallback;
import smooth.lift.compat.WorldRenderContext;
import smooth.lift.compat.WorldRenderEvents;
import smooth.lift.compat.FabricItemGroup;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import smooth.lift.PictureBlocks;
import smooth.lift.client.SmoothLiftClient;

/** 仅客户端：把 Forge 客户端事件转发到 Fabric 垫片事件，并跑客户端初始化。 */
public final class ClientBootstrap {

    private ClientBootstrap() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new Handlers());
        new SmoothLiftClient().onInitializeClient();
        FabricItemGroup.register(PictureBlocks.ITEM_GROUP);
    }

    public static final class Handlers {

        @SubscribeEvent
        public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
            ClientCommandRegistrationCallback.EVENT.invoke(callback ->
                    callback.register(event.getDispatcher(), null));
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            ClientTickEvents.END_CLIENT_TICK.invoke(callback -> callback.onEndTick(client));
        }

        @SubscribeEvent
        public void onChunkLoad(ChunkEvent.Load event) {
            if (!(event.getWorld() instanceof ClientLevel level)) {
                return;
            }
            if (!(event.getChunk() instanceof LevelChunk chunk)) {
                return;
            }
            ClientChunkEvents.CHUNK_LOAD.invoke(callback -> callback.onChunkLoad(level, chunk));
        }

        @SubscribeEvent
        public void onChunkUnload(ChunkEvent.Unload event) {
            if (!(event.getWorld() instanceof ClientLevel level)) {
                return;
            }
            if (!(event.getChunk() instanceof LevelChunk chunk)) {
                return;
            }
            ClientChunkEvents.CHUNK_UNLOAD.invoke(callback -> callback.onChunkUnload(level, chunk));
        }

        @SubscribeEvent
        public void onLoggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
            ClientPlayConnectionEvents.JOIN.invoke(callback ->
                    callback.onPlayReady(event.getConnection(), null, Minecraft.getInstance()));
        }

        @SubscribeEvent
        public void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            ClientPlayConnectionEvents.DISCONNECT.invoke(callback ->
                    callback.onPlayDisconnect(event.getConnection(), Minecraft.getInstance()));
        }

        @SubscribeEvent
        public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
            if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
                return;
            }
            if (HudRenderCallback.EVENT.isEmpty()) {
                return;
            }
            PoseStack poseStack = event.getMatrixStack();
            float partialTick = event.getPartialTicks();
            HudRenderCallback.EVENT.invoke(callback -> callback.onHudRender(poseStack, partialTick));
        }

        @SubscribeEvent
        public void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            if (WorldRenderEvents.AFTER_ENTITIES.isEmpty()) {
                return;
            }
            WorldRenderEvents.AFTER_ENTITIES.invoke(callback -> callback.onEnd(new Context(event)));
        }
    }

    private static final class Context implements WorldRenderContext {

        private final RenderLevelStageEvent event;

        private Context(RenderLevelStageEvent event) {
            this.event = event;
        }

        @Override
        public PoseStack matrixStack() {
            return event.getPoseStack();
        }

        @Override
        public Matrix4f projectionMatrix() {
            return event.getProjectionMatrix();
        }

        @Override
        public float tickDelta() {
            return event.getPartialTick();
        }

        @Override
        public ClientLevel world() {
            return Minecraft.getInstance().level;
        }

        @Override
        public Camera camera() {
            return event.getCamera();
        }

        @Override
        public Frustum frustum() {
            return event.getFrustum();
        }
    }
}