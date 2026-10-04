package smooth.lift.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.EscalatorStepIndex;

/**
 * 【1.30b】把「客户端世界有方块被改过」这件事告诉扶梯阶梯索引。
 *
 * <h2>这个 mixin 修的是什么 bug</h2>
 * 症状（用户原话）：「玩家放置扶梯时，阶梯贴图会等 2~3 秒出现」。
 *
 * <p>根因不在渲染，在**索引的响应延迟**：{@link EscalatorStepIndex} 原来只靠「定期重扫」
 * 跟上增删，而 1.27 把「一遍全量重扫」切成片之后（每 10 刻取一片、一片只覆盖 1/10 的已加载
 * 区块），一遍要走 100 刻 = **5 秒**。于是玩家放下扶梯后，那座区块得等「下次轮到它」
 * 才被扫到 —— 均匀分布，均值 2.5 秒、最坏 5 秒，正好对上「2~3 秒」。
 * 而这期间 MTR 原版那份静止阶梯面已经被全透明标记贴图隐藏（见
 * {@link smooth.lift.client.EscalatorModelOverride}），所以表现是「扶梯放着是空的、
 * 过两三秒台阶贴图才冒出来」。
 *
 * <h2>为什么注入点选 {@code onBlockStateChange}</h2>
 * 它是 {@code Level.setBlock(...)} 的**末尾**（1.20.4 字节码实测：`... invokevirtual
 * onBlockStateChange; iconst_1; ireturn`），所以一次覆盖两条路：
 * <ul>
 *   <li>本地玩家操作的**客户端预测** —— MTR 的 {@code ItemEscalator.useOnBlock} 走
 *       {@code World.setBlockState → Level.setBlockAndUpdate → Level.setBlock}
 *       （反汇编 MTR 4.0.5：{@code org/mtr/mapping/holder/World.method_8501}）；</li>
 *   <li>服务端下发的**方块更新** —— {@code ClientLevel.setServerVerifiedBlockState
 *       → Level.setBlock}。</li>
 * </ul>
 * 它同时也被集成服务端的世界调用（单人游戏里 {@code ServerLevel} 也是这个类），
 * 所以判断「是不是客户端世界」这件事放在 {@link EscalatorStepIndex#onBlockChanged} 里，
 * **只写一处**（两处各判一次就是等着分叉）。
 *
 * <p>注入体本身只往一个集合里塞一个 long（按区块去重），**不做任何扫描** ——
 * 扫描放在下一个客户端刻（{@code EscalatorStepIndex.tick} → {@code applyDirtyChunks}）。
 * 这样钩子跑在网络包处理路径上时也不会做重活，而且「一次划一片区域」触发的成百上千次
 * 会被去重成一次重扫。
 *
 * <p>本文件在 {@code smoothlift.client.mixins.json} 的 {@code client} 数组里
 * ⇒ **专用服务端根本不加载它**。
 */
@Mixin(Level.class)
public abstract class LevelBlockChangeMixin {

    @Inject(method = "onBlockStateChange(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"))
    private void smoothlift$notifyEscalatorStepIndex(BlockPos pos, BlockState oldState,
                                                     BlockState newState, CallbackInfo ci) {
        // 不用 pos / oldState / newState 过滤：客户端预测与服务端下发各来一遍，区块级别去重后
        // 代价已经很低；而「只看新状态是不是扶梯」会漏掉「扶梯被拆掉」那一半
        // （拆掉时新状态是空气），那正好也需要重扫。
        EscalatorStepIndex.onBlockChanged((Level) (Object) this, pos);
    }
}
