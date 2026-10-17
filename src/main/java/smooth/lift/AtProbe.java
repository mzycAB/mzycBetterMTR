package smooth.lift;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/**
 * Temporary probe: touches every member opened by accesstransformer.cfg so the
 * Forge toolchain validates the AT file. Deleted once real sources are ported.
 */
final class AtProbe {

    private AtProbe() {
    }

    static void probe(BlockModel model, SoundManager soundManager, LevelRenderer levelRenderer,
                      TextureAtlasSprite sprite) {
        model.textureMap.size();

        SoundEngine engine = soundManager.soundEngine;
        SoundBufferLibrary library = engine.soundBuffers;
        library.cache.size();
        engine.instanceToChannel.size();

        levelRenderer.renderChunksInFrustum.size();

        RenderStateShard.TextureStateShard texture = new RenderStateShard.TextureStateShard(null, false, false);
        RenderStateShard.CullStateShard cull = new RenderStateShard.CullStateShard(true);
        RenderStateShard.LightmapStateShard lightmap = new RenderStateShard.LightmapStateShard(true);
        RenderStateShard.OverlayStateShard overlay = new RenderStateShard.OverlayStateShard(true);
        RenderStateShard.TransparencyStateShard transparency = new RenderStateShard.TransparencyStateShard("x", () -> {
        }, () -> {
        });
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(texture)
                .setCullState(cull)
                .setLightmapState(lightmap)
                .setOverlayState(overlay)
                .setTransparencyState(transparency)
                .createCompositeState(true);
        if (state == null || sprite == null) {
            throw new IllegalStateException();
        }
    }

    static int channelSource(Channel channel) {
        return channel.source;
    }

    static void shaders() {
        RenderStateShard.RENDERTYPE_CUTOUT_SHADER.toString();
        RenderStateShard.NO_TRANSPARENCY.toString();
        RenderStateShard.CULL.toString();
        RenderStateShard.LIGHTMAP.toString();
        RenderStateShard.OVERLAY.toString();
    }
}