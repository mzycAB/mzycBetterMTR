package smooth.lift.mixin;

import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorModelOverride;

/**
 * 【1.24 / Forge 移植】把 {@link EscalatorModelOverride} 挂到「模型 ID → 未烘焙模型」的收口上。
 *
 * <h2>为什么是 {@code ModelBakery.getModel(ResourceLocation)}</h2>
 * <p>Fabric 侧这件事由 {@code ModelLoadingPlugin.modifyModelBeforeBake} 回调完成。
 * Forge 1.20.1 <b>没有</b>等价的模型加载 API（那是 fabric-model-loading-api-v1 独有的），
 * 所以只能在原版模型管线上找一个等价时机。1.20.1 的候选只有两个：
 * <ol>
 *   <li>{@code ModelEvent.ModifyBakingResult}（Forge 自己的事件）—— 但它拿到的是**已经烘好**的
 *       {@code BakedModel}，{@code #step} 已经变成 BakedQuad 上的 sprite，想换贴图就得自己
 *       遍历四方块重造 quad，还得处理 uv / 方向 / cullface —— 纯属把简单事做复杂；</li>
 *   <li>{@code ModelBakery.getModel(ResourceLocation)} —— 反汇编确认（1.20.1 srg
 *       {@code m_119341_}）它是「按 ID 拿未烘焙模型」的**唯一收口**：
 *       构造期加载附加模型、{@code loadTopLevel}（{@code m_119306_}）全部经过它，
 *       而且它返回的实例就是之后被烘烤的那一个（同一引用被塞进 {@code unbakedCache}
 *       与 {@code topLevelModels} 两张表）。在这里改 {@code textureMap}，烘烤结果必然跟着变
 *       —— 与 Fabric 的 BeforeBake 时机等价。</li>
 * </ol>
 *
 * <h2>注入点选 {@code @At("RETURN")} 而不是 HEAD</h2>
 * <p>{@code getModel} 有两条返回路径：早期命中缓存的 {@code areturn}、以及真正去磁盘加载后的
 * {@code areturn}。{@code @At("RETURN")} 覆盖**每一条** areturn，所以两条路径都过一遍注入判断
 * （{@code EscalatorModelOverride} 里是幂等的 {@code put}，重复命中无害）；
 * 若挂在 HEAD，缓存命中路径就不会被处理。
 *
 * <p>本文件放在 {@code smooth_escalator.mixins.json} 的 {@code client} 数组里 ——
 * {@code ModelBakery} 是纯客户端类，**专用服务端绝不能加载这个类**。
 * 因为 mixin 配置里 {@code defaultRequire: 1}，任何一次 1.20.1 的 {@code getModel} 改名都会在
 * 启动时**响亮失败**，而不是悄悄不注入。
 */
@Mixin(ModelBakery.class)
public abstract class EscalatorModelBakeryMixin {

    @Inject(
            method = "getModel(Lnet/minecraft/resources/ResourceLocation;)"
                    + "Lnet/minecraft/client/resources/model/UnbakedModel;",
            at = @At("RETURN"),
            cancellable = true)
    private void smoothlift$injectEscalatorStepMarker(ResourceLocation id,
                                                      CallbackInfoReturnable<UnbakedModel> cir) {
        UnbakedModel original = cir.getReturnValue();
        UnbakedModel patched = EscalatorModelOverride.inject(id, original);
        // 正常情况下 inject 是就地改 textureMap、返回同一个引用；这里仍然显式比对一次，
        // 好让「将来 inject 改成返回新实例」也不会静默失效。
        if (patched != original) {
            cir.setReturnValue(patched);
        }
    }
}
