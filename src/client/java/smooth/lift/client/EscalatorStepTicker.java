package smooth.lift.client;

import net.minecraft.client.renderer.texture.SpriteTicker;
import smooth.lift.EscalatorSpeedData;

/**
 * 扶梯阶梯贴图专用 SpriteTicker：包裹原版 ticker，把「每游戏帧推进 1 帧」
 * 改为「每游戏帧推进 speed 帧」。
 *
 * <p>速度由 {@link EscalatorAnimationDriver} 解析 —— 阶梯贴图在地图上共享，
 * 同一时刻只能由一条扶梯驱动动画，具体选择规则见那个类。
 */
public final class EscalatorStepTicker implements SpriteTicker {

    private final SpriteTicker delegate;
    private double accumulator;

    public EscalatorStepTicker(SpriteTicker delegate) {
        this.delegate = delegate;
    }

    @Override
    public void tickAndUpload(int x, int y) {
        double speed = EscalatorAnimationDriver.resolveStepAnimationSpeed();
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            speed = EscalatorSpeedData.DEFAULT_SPEED;
        }
        double factor = Math.max(0.0, Math.min(50.0, speed / EscalatorSpeedData.VANILLA_STEP));
        accumulator += factor;
        int rounds = (int) accumulator;
        accumulator -= rounds;
        while (rounds-- > 0) {
            delegate.tickAndUpload(x, y);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
