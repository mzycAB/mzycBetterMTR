package smooth.lift.compat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Forge 侧垫片：Fabric 的极简事件容器。
 *
 * <p>只保留 {@code register}，真正的触发由 {@code smooth.lift.forge.ForgeEventBridge}
 * 在对应的 Forge 事件里调 {@link #invoke} 完成。
 */
public final class Event<T> {

    private final List<T> listeners = new CopyOnWriteArrayList<>();

    public void register(T listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void invoke(Consumer<T> invoker) {
        for (T listener : listeners) {
            invoker.accept(listener);
        }
    }

    public boolean isEmpty() {
        return listeners.isEmpty();
    }
}