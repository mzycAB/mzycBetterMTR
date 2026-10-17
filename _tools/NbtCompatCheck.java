package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

/**
 * 【1.41】离线回归（放在 _tools，不参与打包）：无障碍提示音「音乐」从 1.39 的**单一字段**
 * 拆成**进 / 出两套**之后，NBT 读写是否真的做到「旧存档行为不变 + 新存档两头独立」。
 *
 * <p>用法见 _tools/check-help-audio-nbt.sh（需要先 {@code gradlew compileJava}，以及 _tools/runtime.cp）。
 *
 * <p>为什么值得单独写一个脚本：{@link EscalatorSpeedData#fromTag} 里有一段**顺序敏感**的
 * 兼容逻辑 —— 先读 1.39 的旧字段（**同时**喂给两头），再用 1.41 的新字段覆盖各自那一头。
 * 顺序一旦被改反（新字段先读、旧字段后读），旧存档看起来仍然正常，但**新存档的 out 会被
 * 旧镜像字段覆盖成 in 的值** —— 表现是「给离开扶梯设的提示音，重进世界又变回进扶梯那段」。
 * 这种 bug 只能在游戏里开存档才撞见，所以在这里钉死。
 *
 * <p>校验项：
 * <ol>
 *   <li>1.39 旧存档（只有 {@code defaultHelpAudio} / {@code blockHelpAudio}）→ <b>两头都</b>等于旧值；</li>
 *   <li>1.41 新存档（两头不同）→ 读回来两头各自保持自己那份（旧镜像字段<b>不得</b>覆盖 out）；</li>
 *   <li>写出的新 NBT 里同时有 in / out 两套字段，且旧字段作为**兼容镜像** = 进扶梯那一头；</li>
 *   <li>读写**幂等**：{@code fromTag(save(x))} 与 {@code x} 的结果一致；</li>
 *   <li>删除音频时，底噪 + 提示音进 / 出<b>三张表</b>的引用一起清掉；</li>
 *   <li>{@code bindHelpAudio / unbindHelpAudio(pos, in)} 只动指定的那一头。</li>
 * </ol>
 */
public final class NbtCompatCheck {

    private static int failures;

    private NbtCompatCheck() {
    }

    public static void main(String[] args) {
        final BlockPos pos = new BlockPos(1, 2, 3);

        System.out.println("==================== ① 1.39 旧存档（单一字段） ====================");
        CompoundTag legacyTag = new CompoundTag();
        legacyTag.putString("defaultHelpAudio", "old.ogg");
        legacyTag.put("blockHelpAudio", single("1,2,3", "old-block.ogg"));

        EscalatorSpeedData legacy = EscalatorSpeedData.fromTag(legacyTag);
        ok("默认值同时进两头 —— 进扶梯 = old.ogg", "old.ogg".equals(legacy.defaultHelpAudioIn));
        ok("默认值同时进两头 —— 离开扶梯 = old.ogg", "old.ogg".equals(legacy.defaultHelpAudioOut));
        ok("单独设置同时进两头 —— 进扶梯 = old-block.ogg", "old-block.ogg".equals(legacy.getHelpAudioId(pos, true)));
        ok("单独设置同时进两头 —— 离开扶梯 = old-block.ogg", "old-block.ogg".equals(legacy.getHelpAudioId(pos, false)));

        System.out.println();
        System.out.println("==================== ② 1.41 新存档（两头不同） ====================");
        CompoundTag modernTag = new CompoundTag();
        modernTag.putString("defaultHelpAudioIn", "in.ogg");
        modernTag.putString("defaultHelpAudioOut", "out.ogg");
        // 1.41 自己写出的存档里，旧字段是「进扶梯那一头」的兼容镜像 —— 必须带着它一起读，
        // 才能验证「新字段覆盖旧镜像」的顺序是对的。
        modernTag.putString("defaultHelpAudio", "in.ogg");
        modernTag.put("blockHelpAudioIn", single("1,2,3", "in-block.ogg"));
        modernTag.put("blockHelpAudioOut", single("1,2,3", "out-block.ogg"));
        modernTag.put("blockHelpAudio", single("1,2,3", "in-block.ogg"));

        EscalatorSpeedData modern = EscalatorSpeedData.fromTag(modernTag);
        ok("进扶梯那头 = in.ogg", "in.ogg".equals(modern.defaultHelpAudioIn));
        ok("离开扶梯那头 = out.ogg（旧镜像不得覆盖它）", "out.ogg".equals(modern.defaultHelpAudioOut));
        ok("单独设置两头独立 —— 进扶梯 = in-block.ogg", "in-block.ogg".equals(modern.getHelpAudioId(pos, true)));
        ok("单独设置两头独立 —— 离开扶梯 = out-block.ogg", "out-block.ogg".equals(modern.getHelpAudioId(pos, false)));

        System.out.println();
        System.out.println("==================== ③ 写出的 NBT 字段 ====================");
        CompoundTag written = modern.save(new CompoundTag());
        ok("写了 defaultHelpAudioIn", written.contains("defaultHelpAudioIn"));
        ok("写了 defaultHelpAudioOut", written.contains("defaultHelpAudioOut"));
        ok("写了 blockHelpAudioIn", written.contains("blockHelpAudioIn"));
        ok("写了 blockHelpAudioOut", written.contains("blockHelpAudioOut"));
        ok("旧字段 defaultHelpAudio = 进扶梯那头（兼容镜像）",
                "in.ogg".equals(written.getString("defaultHelpAudio")));
        ok("旧表 blockHelpAudio = 进扶梯那头的镜像",
                "in-block.ogg".equals(written.getCompound("blockHelpAudio").getString("1,2,3")));
        ok("新表 blockHelpAudioOut 带的是离开扶梯那份",
                "out-block.ogg".equals(written.getCompound("blockHelpAudioOut").getString("1,2,3")));

        System.out.println();
        System.out.println("==================== ④ 读写幂等（写 → 读 一轮后不变） ====================");
        EscalatorSpeedData round = EscalatorSpeedData.fromTag(written);
        ok("默认值 进 不变", Objects.equals(round.defaultHelpAudioIn, modern.defaultHelpAudioIn));
        ok("默认值 离开 不变", Objects.equals(round.defaultHelpAudioOut, modern.defaultHelpAudioOut));
        ok("单独设置 进 不变", Objects.equals(round.getHelpAudioId(pos, true), modern.getHelpAudioId(pos, true)));
        ok("单独设置 离开 不变", Objects.equals(round.getHelpAudioId(pos, false), modern.getHelpAudioId(pos, false)));

        System.out.println();
        System.out.println("==================== ⑤ 删除音频时三张引用一起清 ====================");
        EscalatorSpeedData rm = EscalatorSpeedData.fromTag(new CompoundTag());
        rm.audioLibrary.put("x.ogg", new byte[]{1, 2, 3});
        rm.blockAudio.put(pos, "x.ogg");
        rm.bindHelpAudio(pos, "x.ogg", true);
        rm.bindHelpAudio(pos, "x.ogg", false);
        rm.removeAudio("x.ogg");
        ok("底噪引用已清", !rm.blockAudio.containsKey(pos));
        ok("提示音 进扶梯 引用已清", !rm.hasHelpAudio(pos, true));
        ok("提示音 离开扶梯 引用已清", !rm.hasHelpAudio(pos, false));
        ok("音频字节已清", !rm.audioLibrary.containsKey("x.ogg"));

        System.out.println();
        System.out.println("==================== ⑥ 绑定 / 解绑只动指定那一头 ====================");
        EscalatorSpeedData one = EscalatorSpeedData.fromTag(new CompoundTag());
        one.bindHelpAudio(pos, "a.ogg", true);
        ok("只绑定进扶梯时，离开扶梯仍未设置",
                one.hasHelpAudio(pos, true) && !one.hasHelpAudio(pos, false));
        one.bindHelpAudio(pos, "b.ogg", false);
        ok("再绑定离开扶梯后两头各是各的",
                "a.ogg".equals(one.getHelpAudioId(pos, true)) && "b.ogg".equals(one.getHelpAudioId(pos, false)));
        one.unbindHelpAudio(pos, true);
        ok("解绑进扶梯后，离开扶梯那份不受影响",
                !one.hasHelpAudio(pos, true) && "b.ogg".equals(one.getHelpAudioId(pos, false)));

        System.out.println();
        if (failures == 0) {
            System.out.println("结果：全部通过 ✅");
        } else {
            System.out.println("结果：有 " + failures + " 项不符 ❌");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 造一个「x,y,z」→ 值 的 NBT compound（键的写法与 {@link EscalatorSpeedData} 一致）。 */
    private static CompoundTag single(String key, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        return tag;
    }

    private static void ok(String what, boolean pass) {
        if (!pass) {
            failures++;
        }
        System.out.println((pass ? "  OK   " : "  FAIL ") + what);
    }
}
