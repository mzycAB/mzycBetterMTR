package smooth.lift.compat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Forge 侧垫片：Fabric 的 {@code ModelModifier}。
 *
 * <p>1.18.2 的 Forge 没有「烘烤前」的模型事件（{@code ModelBakeEvent} 已经是烘烤之后），
 * 所以真正的挂载点由 {@code smooth.lift.client.mixin.ModelBakeryBeforeBakeMixin} 提供：
 * 它在 {@code ModelBakery.getModel} 返回前调用 {@link #applyBeforeBake}。
 */
public interface ModelModifier {

    List<BeforeBake.Modifier> BEFORE_BAKE = new CopyOnWriteArrayList<>();

    /** 由 mixin 调用：把已登记的「烘烤前」修改器依次作用到模型上。 */
    static UnbakedModel applyBeforeBake(ResourceLocation id, UnbakedModel model) {
        if (model == null || BEFORE_BAKE.isEmpty()) {
            return model;
        }
        BeforeBake.Context context = () -> id;
        UnbakedModel current = model;
        for (BeforeBake.Modifier modifier : BEFORE_BAKE) {
            current = modifier.modify(current, context);
        }
        return current;
    }

    interface BeforeBake {

        interface Context {
            ResourceLocation id();
        }

        @FunctionalInterface
        interface Modifier {
            UnbakedModel modify(UnbakedModel model, Context context);
        }

        void register(Modifier modifier);
    }
}