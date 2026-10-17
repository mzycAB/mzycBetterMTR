package smooth.lift.client;

import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.24】在 MTR 扶梯阶梯模型**烘烤前**，把 {@code #step} 贴图注入成我们的透明标记底图。
 *
 * <p>以前这 18 个模型是靠资源覆盖（{@code assets/mtr/models/block/escalator_step_*.json}）
 * 改的 —— 每个 JSON 把 {@code step} 换成 {@code smoothlift:block/step_static_up|down}。
 * 任务 2 起改成代码注入：注册一个 {@link ModelLoadingPlugin}，在
 * {@code modifyModelBeforeBake} 里按模型 ID 命中这 18 个 MTR 模型，
 * 把 {@code BlockModel.textureMap} 里 {@code step} 槽位直接替换成标记 Material。
 *
 * <p>好处：不需要再管理 18 个资源覆盖文件（MTR 升级换模型也不用跟着改）；
 * 开关注入与否也只是一行判断，将来 {@code /mtrxr on} 时可以直接跳过本注入。
 *
 * <p>匹配规则（与旧 18 个 JSON 的映射完全一致）：</p>
 * <ul>
 *   <li>命名空间 {@code mtr}、路径前缀 {@code block/escalator_step_}；</li>
 *   <li>模型必须是个 {@link BlockModel}，且自己的贴图表里有 {@code step} 键
 *       （静态踏板 {@code escalator_step_landing} 没有 {@code #step} 面，天然被排除）；</li>
 *   <li>{@code *_down} → 下行标记 {@code step_static_down}，{@code *_up} / {@code *_stop} →
 *       上行标记 {@code step_static_up}。</li>
 * </ul>
 *
 * <p>注入用的 Material 与 {@link EscalatorStepModels} 里的标记是同一个 sprite 名字
 * （{@code smoothlift:block/step_static_*}），所以烘焙出来的 BakedQuad 的 sprite 身份不变，
 * {@code EscalatorStepModels.build} 仍然靠 sprite 名字认出「哪些面是会动的阶梯面」。
 * 标记贴图本身全透明，MTR 原版渲染它时会被 cutout alpha 整片裁掉，静止阶梯面就此隐藏，
 * 只剩我们重绘的那份。
 */
public final class EscalatorModelOverride {

    private EscalatorModelOverride() {
    }

    /** 注册模型加载插件（客户端初始化时调用一次）。 */
    public static void register() {
        ModelLoadingPlugin.register(plugin ->
                plugin.modifyModelBeforeBake().register(EscalatorModelOverride::beforeBake));
    }

    private static UnbakedModel beforeBake(UnbakedModel model,
                                           ModelModifier.BeforeBake.Context context) {
        // 【1.24】/mtrxr on（MTR 原版渲染）时不再注入：让 MTR 阶梯模型按原样烘焙，
        // 静止阶梯面由 MTR 原版渲染路径负责；只有优化引擎（/mtrxr off）才隐藏它们。
        if (!EscalatorRenderMode.isOptimized()) {
            return model;
        }
        if (!(model instanceof BlockModel blockModel)) {
            return model;
        }
        ResourceLocation id = context.id();
        if (id == null || !"mtr".equals(id.getNamespace())) {
            return model;
        }
        String path = id.getPath();
        if (!path.startsWith("block/escalator_step_")) {
            return model;
        }
        // 静态踏板等没有 #step 键的模型不属于可动画阶梯：不注入。
        if (!blockModel.textureMap.containsKey("step")) {
            return model;
        }
        ResourceLocation marker = path.endsWith("_down")
                ? EscalatorStepModels.MARKER_DOWN
                : EscalatorStepModels.MARKER_UP;
        blockModel.textureMap.put("step",
                Either.left(new Material(TextureAtlas.LOCATION_BLOCKS, marker)));
        return model;
    }
}