package smooth.lift.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.EscalatorSpeedScreen;

import java.util.HashSet;
import java.util.Set;

/**
 * 【1.30.11182】扶梯设置界面的「守卫」：石斧右键扶梯打开 {@link EscalatorSpeedScreen} 之后，
 * 拦掉**其他模组**对界面的顶替。
 *
 * <h2>这个 mixin 修的是什么问题</h2>
 * 用户报（1.18.2）：「拿着石斧右键扶梯时出现的这个模组的 ui 在 forge1.18.2 中只剩下调速功能了，
 * 但是其他版本（1.20.1）有很多功能，比如阶梯速度、扶梯声音、提示音等等」。
 *
 * <p>排查结论：1.18.2 的 {@code EscalatorSpeedScreen} 与 1.20.1 **逐行同义**
 * （五行：扶梯速度 / 阶梯速度 / 声音设置 / 提示音(进入) / 提示音(离开)，规范化 diff 逐行比对过），
 * 拦截链（{@code ForgeEventBridge.onRightClickBlock} → {@code UseBlockCallback} →
 * {@code setScreen(EscalatorSpeedScreen)}）也与 1.20.1 一致。差别在**环境**：
 * 1.18.2 整合包里装着 {@code mtr4backport}（MTR4 特性移植，LOGx1 L164/L167 可见它注册了
 * {@code mtr4backport:escalator_set_speed} / {@code escalator_request_sync} 调速网络通道），
 * 它也实现了「石斧右键扶梯开调速界面」，而且它在 SmoothLift 的界面打开**之后**把界面换成了
 * 它自己的「只剩调速」的界面 —— Forge 的事件取消**不会**阻止后续监听器继续跑，
 * 所以 SmoothLift 侧拦不掉它，只能在这里拦「换界面」这一步。
 *
 * <h2>为什么注入 {@code Minecraft.setScreen}</h2>
 * 1.18.2 里给玩家展示任何界面**只有这一个入口**（无论对方是客户端事件里直接
 * {@code setScreen}，还是服务端 → 客户端发包走 {@code MenuScreens}，最终都到它）。
 * 于是在 HEAD 处判一次：
 * <ul>
 *   <li>当前开着的是我们的 {@link EscalatorSpeedScreen}；</li>
 *   <li>要换上去的界面**不是 null**（放行 ESC / 关界面）且不在 {@code smooth.lift.}
 *       包里（放行我们自己的子界面跳转：声音设置 / 提示音 / 同步弹窗）；</li>
 *   <li>并且世界还在（{@code level != null}，放行断线时的 DisconnectScreen 等收尾界面）；</li>
 * </ul>
 * 三条同时成立 ⇒ 取消这次换界面。这样「谁的界面后到谁赢」变成「SmoothLift 的界面开着就谁也抢不走」，
 * 与时序无关，没有闪烁。
 *
 * <h2>为什么按包名 {@code smooth.lift.} 前缀放行</h2>
 * 本模组自己的界面全在 {@code smooth.lift.client}（EscalatorSpeedScreen / AudioSetupScreen /
 * HelpAudioSetupScreen / SyncPopupScreen / FolderOpenButton 要打开的页面等），
 * 它们之间的跳转必须照常；而“抢界面”的外来模组类不可能落在我们的包里。
 *
 * <p>拦截是**静默但有据**的：每个外来类只记一条 INFO（含类名）——
 * 用户日志里出现「拦截了一次对扶梯设置界面的顶替：mtr4backport.…」即为这条修复在工作的正面证据，
 * 也顺便回答了「到底是谁在抢界面」。
 *
 * <p>本文件在 {@code smoothlift.client.mixins.json} 的 {@code client} 数组里
 * ⇒ 专用服务端不加载它；目标是原版 {@code Minecraft}，无需环境门禁。
 */
@Mixin(Minecraft.class)
public abstract class EscalatorUiGuardMixin {

    @Unique
    private static final Logger smoothlift$LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 每个「想顶替我们界面」的外来类只记一条日志，避免刷屏。 */
    @Unique
    private static final Set<String> smoothlift$BLOCK_LOGGED = new HashSet<>();

    @Shadow
    public Screen screen;

    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
            at = @At("HEAD"), cancellable = true)
    private void smoothlift$protectEscalatorUi(Screen newScreen, CallbackInfo ci) {
        if (newScreen == null) {
            // 关界面（ESC / 退出世界 / 断线）永远放行 —— 用户随时能离开我们的界面。
            return;
        }
        Minecraft self = (Minecraft) (Object) this;
        if (self.level == null) {
            // 世界已经不在了（断线收尾等），放行一切收尾界面。
            return;
        }
        if (this.screen instanceof EscalatorSpeedScreen
                && !newScreen.getClass().getName().startsWith("smooth.lift.")) {
            String name = newScreen.getClass().getName();
            if (smoothlift$BLOCK_LOGGED.add(name)) {
                smoothlift$LOGGER.info(
                        "[SmoothLift] 拦截了一次对扶梯设置界面的顶替：{}（我们的五行设置界面保持打开）",
                        name);
            }
            ci.cancel();
        }
    }
}
