package smooth.lift.compat;

import java.util.Random;

/**
 * 垫片：{@code RandomSource} 是 1.19 才引入的随机源抽象，1.18.2 里各处直接收
 * {@link Random}（如 {@code BlockModel.getQuads(..., Random)}）。
 *
 * <p>本类继承 {@code Random}，于是「当 Random 用」的地方原样可编译。
 */
public class RandomSource extends Random {

    public RandomSource() {
        super();
    }

    public RandomSource(long seed) {
        super(seed);
    }

    public static RandomSource create() {
        return new RandomSource();
    }

    public static RandomSource create(long seed) {
        return new RandomSource(seed);
    }
}