package smooth.lift.client;

import com.mojang.datafixers.util.Either;
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
 * 任务 2 起改成代码注入：按模型 ID 命中这 18 个 MTR 模型，把
 * {@code BlockModel.textureMap} 里 {@code step} 槽位直接替换成标记 Material。
 *
 * <p>好处：不需要再管理 18 个资源覆盖文件（MTR 升级换模型也不用跟着改）；
 * <b>而且这是 {@code /mtrxr on|off} 能成立的前提</b> —— 资源覆盖是静态的，没法按模式开关；
 * 代码注入只要一行判断就能「优化引擎时注入、MTR 原版时跳过」。
 *
 * <p>匹配规则（与旧 18 个 JSON 的映射完全一致）：
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
 *
 * <h2>★ 这里与 Fabric 版的差别只有「谁来找我」</h2>
 * <p>Fabric 用 {@code ModelLoadingPlugin} + {@code ModelModifier.BeforeBake} 拿回调；
 * Forge 没有模型加载 API，改成 mixin 挂在 {@code ModelBakery.getModel(ResourceLocation)}
 * 的返回值上（见 {@link smooth.lift.mixin.EscalatorModelBakeryMixin}）——
 * 那是 1.20.1 里「把模型 ID 解析成 UnbakedModel」的**唯一收口**
 * （构造期加载附加模型、{@code loadTopLevel} 全部经过它），
 * 且返回的就是随后被烘烤的那个实例，所以在这里改 {@code textureMap} 与 Fabric 的
 * BeforeBake 时机等价。
 *
 * <p>本类**刻意做成纯 MC 类型、不带任何加载器/事件 API**：这样它在 Forge 与 Fabric
 * 上是同一份业务逻辑，回归脚本也能直接对着字节码校验注入规则，不必管平台。
 */
public final class EscalatorModelOverride {

    private EscalatorModelOverride() {
    }

    /**
     * 按模型 ID 决定要不要注入透明标记贴图。
     *
     * @param id   正在解析的模型 ID（可能为 null —— 某些内部调用会传 null）
     * @param model 解析出来的未烘焙模型（可能为 null）
     * @return 处理后的模型；不命中 / 模式为 MTR 原版时**原样返回**
     */
    public static UnbakedModel inject(ResourceLocation id, UnbakedModel model) {
        // 【1.24】/mtrxr on（MTR 原版渲染）时不再注入：让 MTR 阶梯模型按原样烘焙，
        // 静止阶梯面由 MTR 原版渲染路径负责；只有优化引擎（/mtrxr off）才隐藏它们。
        if (!EscalatorRenderMode.isOptimized()) {
            return model;
        }
        if (model == null) {
            return model;
        }
        if (id == null || !"mtr".equals(id.getNamespace())) {
            return model;
        }
        String path = id.getPath();
        if (!path.startsWith("block/escalator_step_")) {
            return model;
        }
        if (!(model instanceof BlockModel blockModel)) {
            return model;
        }
        // 静态踏板等没有 #step 键的模型不属于可动画阶梯：不注入。
        if (!blockModel.textureMap.containsKey("step")) {
            return model;
        }
        ResourceLocation marker = path.endsWith("_down")
                ? EscalatorStepModels.MARKER_DOWN
                : EscalatorStepModels.MARKER_UP;
        // put 是幂等的：同一批模型可能被解析多次（缓存命中路径也会回到这里），
        // 反复写入同一个标记没有副作用。
        blockModel.textureMap.put("step",
                Either.left(new Material(TextureAtlas.LOCATION_BLOCKS, marker)));
        return model;
    }
}
