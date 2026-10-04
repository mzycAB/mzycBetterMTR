package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import smooth.lift.client.PlatformNameMask;

import java.util.Map;

/**
 * 【10-01】把 MTR3 的「站台名」在**屏蔽门 PIDS 显示屏**上做掩码。
 *
 * <h2>为什么不在 ArrivalResponse 上（MTR3 没有 getter）</h2>
 * MTR3 的 {@code mtr.data.Platform} 没有 {@code getName()}，站台名是 public 字段
 * {@code name}。逐条 javap 确认：{@code Platform.name} 的 getfield 只在
 * {@code RenderPIDS.lambda$getSchedules$0(...)} 里出现一次（偏移 77），
 * 紧接着（偏移 80）被 {@code Map.put} 存进「terminatingPlatforms」表，
 * 再由 {@code render(...)} 画到 PIDS 上。所以**唯一**画站台名的地方就是这次 put 的值。
 *
 * <h2>选哪条注入</h2>
 * 选 {@code @Redirect} 掉那次 {@code Map.put}：handler 只碰 {@code java.util.Map} /
 * {@code Object} / {@code String}，不出现任何 MTR 类型；且只改「写进 PIDS 表的那个值」，
 * 不动 {@code Platform.name} 字段本身（不动 canonical 数据，避免污染其它读口）。
 * 故意 {@code require = 0}：lambda 名是编译器生成的，万一 MTR3 小版本改名导致注入点缺失，
 * 也**只是** PIDS 不掩码、绝不崩游戏。
 */
@Pseudo
@Mixin(targets = "mtr.render.RenderPIDS")
public abstract class Mtr3PidsNameMixin {

    @Redirect(
            method = "lambda$getSchedules$0(Ljava/util/Set;Ljava/util/Map;Lmtr/data/Platform;Lmtr/data/ScheduleEntry;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 0,
            remap = false)
    private Object smoothlift$maskTerminatingPlatformName(Map<Object, Object> map, Object key, Object value) {
        return map.put(key, PlatformNameMask.mask((String) value));
    }
}
