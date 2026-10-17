package smooth.lift.client.mixin;

import smooth.lift.compat.ModelModifier;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【1.24 移植】给 Forge 1.18.2 补上「模型烘烤前」的挂载点。
 *
 * <p>1.20.4 那边用的是 Fabric 的 {@code ModelLoadingPlugin.modifyModelBeforeBake()}；
 * Forge 1.18.2 的 {@code ModelBakeEvent} 已经是烘烤之后，改不动 unbaked 的贴图表，
 * 所以这里注入 {@code ModelBakery.getModel}（烘烤时每个模型都会经过它），
 * 在返回前把已登记的修改器作用上去 —— 效果与 Fabric 的 {@code modifyModelBeforeBake} 等价。
 */
@Mixin(ModelBakery.class)
public abstract class ModelBakeryBeforeBakeMixin {

    @Inject(method = "getModel", at = @At("RETURN"), cancellable = true)
    private void smoothlift$modifyBeforeBake(ResourceLocation id,
                                             CallbackInfoReturnable<UnbakedModel> cir) {
        UnbakedModel original = cir.getReturnValue();
        UnbakedModel modified = ModelModifier.applyBeforeBake(id, original);
        if (modified != original) {
            cir.setReturnValue(modified);
        }
    }
}