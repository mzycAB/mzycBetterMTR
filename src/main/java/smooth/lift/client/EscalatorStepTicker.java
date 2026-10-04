package smooth.lift.client;

import net.minecraft.client.renderer.texture.Tickable;
import smooth.lift.EscalatorSpeedData;

/**
 * 扶梯阶梯贴图专用 Tickable：包裹原版动画 ticker，把「每游戏帧推进 1 帧」
 * 改为「每游戏帧推进 speed 帧」。
 *
 * <p>速度由 {@link EscalatorAnimationDriver} 解析 —— 阶梯贴图在地图上共享，
 * 同一时刻只能由一条扶梯驱动动画，具体选择规则见那个类。
 *
 * <p>1.18.2 的动画接口是 {@link Tickable#tick()}（1.20.4 的 {@code SpriteTicker}
 * 是 {@code tickAndUpload(int,int)} + {@code close()}）；本类把累加出来的整数
 * 帧数逐次转交给原版 ticker 的 {@code tick()}。
 */
public final class EscalatorStepTicker implements Tickable {

    private final Tickable delegate;
    private double accumulator;

    public EscalatorStepTicker(Tickable delegate) {
        this.delegate = delegate;
    }

    @Override
    public void tick() {
        double speed = EscalatorAnimationDriver.resolveStepAnimationSpeed();
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            speed = EscalatorSpeedData.DEFAULT_SPEED;
        }
        double factor = Math.max(0.0, Math.min(50.0, speed / EscalatorSpeedData.VANILLA_STEP));
        accumulator += factor;
        int rounds = (int) accumulator;
        accumulator -= rounds;
        while (rounds-- > 0) {
            delegate.tick();
        }
    }
}