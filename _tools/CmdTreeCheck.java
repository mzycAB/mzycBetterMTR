package smooth.lift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 临时校验工具（放在 _tools，不参与打包）：脱离游戏环境把真·指令树（{@link SmoothLift#registerCommands}）
 * 建出来，然后 dump 出各层级的 Tab 补全项，确认 `/futihelp`、`/futihelploud`、`/futiround`、
 * `/futihelpround`、`/futihelpspeed`、`/futihelpmusic`（【1.39】，【1.41】起带 in|out），
 * 以及【1.42】直梯的 `/lifthelp` 与【1.43】`/lifthelploud` 的每个分支都真的可达。
 * 【1.60】`/lifthelpspeed` 已删除、`/lifthelpup|down|chime` 已改成 `/lifthelp up|down|open|close`，
 * 这几条在下面都有「必须不再存在」的断言。
 *
 * <p>用法见 _tools/check-command-tree.sh。核心手法是给 {@code dispatcher.parse(input, null)}
 * 传一个 <b>null source</b>：Brigadier 解析与补全只用到指令树本身，不会去碰 source，
 * 所以不需要真的开一个 MC 服务器。
 *
 * <p>【1.41】还多了一项 <b>{@link #expectSameShape}</b>：把 `/futihelpmusic` 与 `/futihelpspeed`
 * 的补全结构**逐层对比**（顶层 / in / out / -f / 各层 to …）—— 需求就是「指令细节与
 * /futihelpspeed 的 in|out **对齐**」，用一条可复跑的断言把它钉住，比人工看 dump 可靠。
 *
 * <p>⚠️ 三条已知的「不算失败」的 Brigadier 行为，都放在对照区（probe）：
 * <ol>
 *   <li><b>数值参数不给补全项</b> —— {@code futiround 20 to } 的期望是 {@code []} 而不是 {@code [0]}；</li>
 *   <li><b>根节点自带执行器的指令，后面多打一个词会停在根节点、不报异常</b> ——
 *       {@code /futihelp bogus}、{@code /futihelpspeed 5}、以及【1.41】之后的
 *       {@code /futihelpmusic default}（忘了写 in|out）都是既有的
 *       「不带参数 = 显示当前值」语义的副作用，别写成 {@code expectNotParsed}；</li>
 *   <li><b>字符串参数不给补全项、也不在解析期报错</b> —— 名字（含 {@code -f}、{@code to}）会被
 *       整个吃掉，真正的报错发生在 {@code resolveHelpAudioName} 里。</li>
 * </ol>
 *
 * <p>★【1.60】/lifthelp up|down|open|close 这一层有个**必须钉住**的东西：
 * 同一层上既有字面量 {@code on} / {@code off} / {@code -f}，又有「音频名字」字符串参数，
 * 而 {@code StringArgumentType} 会把 {@code on} 也读成一个合法的字符串 ⇒ 两个子节点**同时匹配**。
 * Brigadier 取的是**子节点插入顺序里靠前**的那个（所以注册时字面量必须写在参数前面）。
 * {@link #expectNode} 就是为这条写的：{@code lifthelp up on} 必须落在字面量 {@code on} 上，
 * {@code lifthelp up default} 必须落在参数 {@code name} 上。谁把顺序调了，这里立刻红。
 *
 * <p>★ 音频名字的补全项（default / none / 导入过的 ogg）**在这个工具里测不到**：
 * 本工具给 {@code dispatcher.parse} 传的是 null source，而补全提供器要从 source 取维度、
 * 再取存档里的音频库。所以补全提供器对 null source 直接返回空（见
 * {@code SmoothLift.liftToneNameSuggestions}），候选列表本身由
 * {@code _tools/check-lift-tone.py} 做源码级断言。
 */
public final class CmdTreeCheck {

    private CmdTreeCheck() {
    }

    public static void main(String[] args) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        SmoothLift.registerCommands(dispatcher);

        int failures = 0;
        System.out.println("==================== /futihelp 指令树 ====================");
        failures += dump(dispatcher, "/", "根（全部指令）");
        failures += dump(dispatcher, "futihelp ", "futihelp 的直接子节点");
        failures += dump(dispatcher, "futihelp on ", "futihelp on 的下一层");
        failures += dump(dispatcher, "futihelp on to ", "futihelp on to 的下一层");
        failures += dump(dispatcher, "futihelp off ", "futihelp off 的下一层");
        failures += dump(dispatcher, "futihelp off to ", "futihelp off to 的下一层");
        failures += dump(dispatcher, "futihelp -f ", "futihelp -f 的下一层");
        failures += dump(dispatcher, "futihelp -f on ", "futihelp -f on 的下一层");
        failures += dump(dispatcher, "futihelp -f on to ", "futihelp -f on to 的下一层");
        failures += dump(dispatcher, "futihelp -f off ", "futihelp -f off 的下一层");
        failures += dump(dispatcher, "futihelp -f off to ", "futihelp -f off to 的下一层");
        System.out.println("-------------------- /futihelploud 指令树 --------------------");
        failures += dump(dispatcher, "futihelploud ", "futihelploud 的直接子节点");
        failures += dump(dispatcher, "futihelploud 200 ", "futihelploud <音量> 的下一层");
        failures += dump(dispatcher, "futihelploud 200 to ", "futihelploud <X> to 的下一层");
        failures += dump(dispatcher, "futihelploud -f ", "futihelploud -f 的下一层");
        failures += dump(dispatcher, "futihelploud -f 200 ", "futihelploud -f <音量> 的下一层");
        failures += dump(dispatcher, "futihelploud -f 200 to ", "futihelploud -f <X> to 的下一层");
        System.out.println("-------------------- /futiround 指令树 --------------------");
        failures += dump(dispatcher, "futiround ", "futiround 的直接子节点");
        failures += dump(dispatcher, "futiround 20 ", "futiround <范围> 的下一层");
        failures += dump(dispatcher, "futiround 20 to ", "futiround <X> to 的下一层");
        failures += dump(dispatcher, "futiround -f ", "futiround -f 的下一层");
        failures += dump(dispatcher, "futiround -f 20 ", "futiround -f <范围> 的下一层");
        failures += dump(dispatcher, "futiround -f 20 to ", "futiround -f <X> to 的下一层");
        System.out.println("-------------------- /futihelpround 指令树 --------------------");
        failures += dump(dispatcher, "futihelpround ", "futihelpround 的直接子节点");
        failures += dump(dispatcher, "futihelpround 4 ", "futihelpround <范围> 的下一层");
        failures += dump(dispatcher, "futihelpround 4 to ", "futihelpround <X> to 的下一层");
        failures += dump(dispatcher, "futihelpround -f ", "futihelpround -f 的下一层");
        failures += dump(dispatcher, "futihelpround -f 4 ", "futihelpround -f <范围> 的下一层");
        failures += dump(dispatcher, "futihelpround -f 4 to ", "futihelpround -f <X> to 的下一层");
        System.out.println("-------------------- /futihelpspeed 指令树 --------------------");
        failures += dump(dispatcher, "futihelpspeed ", "futihelpspeed 的直接子节点（应有 -f / in / out）");
        failures += dump(dispatcher, "futihelpspeed in ", "futihelpspeed in 的下一层（数值参数，无补全）");
        failures += dump(dispatcher, "futihelpspeed in 5 ", "futihelpspeed in <Hz> 的下一层");
        failures += dump(dispatcher, "futihelpspeed in 5 to ", "futihelpspeed in <X> to 的下一层");
        failures += dump(dispatcher, "futihelpspeed out ", "futihelpspeed out 的下一层（数值参数，无补全）");
        failures += dump(dispatcher, "futihelpspeed out 1 ", "futihelpspeed out <Hz> 的下一层");
        failures += dump(dispatcher, "futihelpspeed -f ", "futihelpspeed -f 的下一层（应有 in / out）");
        failures += dump(dispatcher, "futihelpspeed -f in 5 ", "futihelpspeed -f in <Hz> 的下一层");
        failures += dump(dispatcher, "futihelpspeed -f in 5 to ", "futihelpspeed -f in <X> to 的下一层");
        failures += dump(dispatcher, "futihelpspeed -f out 1 ", "futihelpspeed -f out <Hz> 的下一层");

        System.out.println();
        System.out.println("==================== /futihelpmusic 指令树（【1.41】与 /futihelpspeed 的 in|out 对齐） ====================");
        failures += dump(dispatcher, "futihelpmusic ", "futihelpmusic 的直接子节点（应有 -f / in / out）");
        failures += dump(dispatcher, "futihelpmusic in ", "futihelpmusic in 的下一层（字符串参数不补全）");
        failures += dump(dispatcher, "futihelpmusic in default ", "futihelpmusic in <名字> 的下一层");
        failures += dump(dispatcher, "futihelpmusic in default to ", "futihelpmusic in <X> to 的下一层");
        failures += dump(dispatcher, "futihelpmusic out default ", "futihelpmusic out <名字> 的下一层");
        failures += dump(dispatcher, "futihelpmusic out default to ", "futihelpmusic out <X> to 的下一层");
        failures += dump(dispatcher, "futihelpmusic -f ", "futihelpmusic -f 的下一层（应有 in / out）");
        failures += dump(dispatcher, "futihelpmusic -f in ", "futihelpmusic -f in 的下一层（字符串参数不补全）");
        failures += dump(dispatcher, "futihelpmusic -f in default ", "futihelpmusic -f in <名字> 的下一层");
        failures += dump(dispatcher, "futihelpmusic -f in default to ", "futihelpmusic -f in <X> to 的下一层");
        failures += dump(dispatcher, "futihelpmusic -f out default ", "futihelpmusic -f out <名字> 的下一层");

        System.out.println();
        System.out.println("==================== 【1.42】/lifthelp 指令树 ====================");
        failures += dump(dispatcher, "lifthelp ", "lifthelp 的直接子节点（1.60 起多了 up/down/open/close）");
        failures += dump(dispatcher, "lifthelp on ", "lifthelp on 的下一层");
        failures += dump(dispatcher, "lifthelp on to ", "lifthelp on to 的下一层");
        failures += dump(dispatcher, "lifthelp off to ", "lifthelp off to 的下一层");
        failures += dump(dispatcher, "lifthelp -f ", "lifthelp -f 的下一层");
        failures += dump(dispatcher, "lifthelp -f on to ", "lifthelp -f on to 的下一层");
        System.out.println("-------------------- 【1.60】/lifthelp up|down|open|close 指令树 --------------------");
        for (String liftTone : new String[]{"up", "down", "open", "close"}) {
            System.out.println("===== lifthelp " + liftTone + " =====");
            failures += dump(dispatcher, "lifthelp " + liftTone + " ",
                    "lifthelp " + liftTone + " 的直接子节点（字面量 -f/off/on + 音频名字参数）");
            failures += dump(dispatcher, "lifthelp " + liftTone + " on ", "子开关 on 的下一层");
            failures += dump(dispatcher, "lifthelp " + liftTone + " on to ", "子开关 on to 的下一层");
            failures += dump(dispatcher, "lifthelp " + liftTone + " off to ", "子开关 off to 的下一层");
            failures += dump(dispatcher, "lifthelp " + liftTone + " -f ", "-f 的下一层（on / off / 音频名字）");
            failures += dump(dispatcher, "lifthelp " + liftTone + " -f on to ", "-f on to 的下一层");
            failures += dump(dispatcher, "lifthelp " + liftTone + " -f default to ",
                    "-f <名字> to 的下一层（字符串参数不补全）");
            failures += dump(dispatcher, "lifthelp " + liftTone + " default ",
                    "<名字> 的下一层（应有 to）");
            failures += dump(dispatcher, "lifthelp " + liftTone + " default to ",
                    "<名字> to 的下一层（字符串参数不补全）");
        }
        System.out.println("-------------------- 【1.43】/lifthelploud 指令树 --------------------");
        failures += dump(dispatcher, "lifthelploud ", "lifthelploud 的直接子节点");
        failures += dump(dispatcher, "lifthelploud 200 ", "lifthelploud <音量> 的下一层");
        failures += dump(dispatcher, "lifthelploud 200 to ", "lifthelploud <X> to 的下一层");
        failures += dump(dispatcher, "lifthelploud -f ", "lifthelploud -f 的下一层");
        failures += dump(dispatcher, "lifthelploud -f 200 to ", "lifthelploud -f <X> to 的下一层");
        // 【1.60】lifthelploud up|down|open|close：四项提示音各自的音量
        for (String liftToneLoud : new String[]{"up", "down", "open", "close"}) {
            System.out.println("===== lifthelploud " + liftToneLoud + " =====");
            failures += dump(dispatcher, "lifthelploud " + liftToneLoud + " ",
                    "lifthelploud " + liftToneLoud + " 的直接子节点（数值参数，无补全）");
            failures += dump(dispatcher, "lifthelploud " + liftToneLoud + " 200 ",
                    "lifthelploud " + liftToneLoud + " <音量> 的下一层");
            failures += dump(dispatcher, "lifthelploud " + liftToneLoud + " 200 to ",
                    "lifthelploud " + liftToneLoud + " <X> to 的下一层");
            failures += dump(dispatcher, "lifthelploud -f " + liftToneLoud + " ",
                    "lifthelploud -f " + liftToneLoud + " 的直接子节点（数值参数，无补全）");
            failures += dump(dispatcher, "lifthelploud -f " + liftToneLoud + " 200 to ",
                    "lifthelploud -f " + liftToneLoud + " <X> to 的下一层");
        }

        System.out.println("-------------------- 【1.47】/lifthelpround 指令树 --------------------");
        failures += dump(dispatcher, "lifthelpround ", "lifthelpround 的直接子节点（数值参数，无补全）");
        failures += dump(dispatcher, "lifthelpround 4 ", "lifthelpround <范围> 的下一层");
        failures += dump(dispatcher, "lifthelpround 4 to ", "lifthelpround <X> to 的下一层");
        failures += dump(dispatcher, "lifthelpround -f ", "lifthelpround -f 的下一层");
        failures += dump(dispatcher, "lifthelpround -f 4 to ", "lifthelpround -f <X> to 的下一层");
        failures += expectNotParsed(dispatcher, "lifthelpround 0", "范围下限是 1");
        failures += expectNotParsed(dispatcher, "lifthelpround 200", "范围上限是 128");

        System.out.println("-------------------- 【1.15】/lifthelpup|down|chime 必须已经不存在 --------------------");
        for (String dead : new String[]{"lifthelpup", "lifthelpdown", "lifthelpchime", "lifthelpspeed"}) {
            failures += expectNotParsed(dispatcher, dead, "【1.15】已删除的旧指令");
            failures += expectNotParsed(dispatcher, dead + " on", "【1.15】已删除的旧指令（带参数）");
        }

        System.out.println();
        System.out.println("==================== 【1.50】/pbmmusic 指令树（屏蔽门开关门提示音） ====================");
        failures += dump(dispatcher, "pbmmusic ", "pbmmusic 的直接子节点（应有 -f / close / off / on / open）");
        failures += dump(dispatcher, "pbmmusic on ", "pbmmusic on 的下一层");
        failures += dump(dispatcher, "pbmmusic on to ", "pbmmusic on to 的下一层");
        failures += dump(dispatcher, "pbmmusic off to ", "pbmmusic off to 的下一层");
        failures += dump(dispatcher, "pbmmusic -f ", "pbmmusic -f 的下一层");
        failures += dump(dispatcher, "pbmmusic -f on to ", "pbmmusic -f on to 的下一层");
        failures += dump(dispatcher, "pbmmusic open ", "pbmmusic open 的直接子节点");
        failures += dump(dispatcher, "pbmmusic open on ", "pbmmusic open on 的下一层");
        failures += dump(dispatcher, "pbmmusic open on to ", "pbmmusic open on to 的下一层");
        failures += dump(dispatcher, "pbmmusic open off to ", "pbmmusic open off to 的下一层");
        failures += dump(dispatcher, "pbmmusic open -f ", "pbmmusic open -f 的下一层");
        failures += dump(dispatcher, "pbmmusic open -f on to ", "pbmmusic open -f on to 的下一层");
        failures += dump(dispatcher, "pbmmusic open -f default to ",
                "【1.15】pbmmusic open -f <名字> to 的下一层（应有音频名字参数 target）");
        failures += dump(dispatcher, "pbmmusic open default ",
                "【1.15】pbmmusic open <名字> 的下一层（应有 to）");
        failures += dump(dispatcher, "pbmmusic open default to ",
                "【1.15】pbmmusic open <X> to 的下一层（应有音频名字参数 target）");
        failures += dump(dispatcher, "pbmmusic close ", "pbmmusic close 的直接子节点");
        failures += dump(dispatcher, "pbmmusic close -f off to ", "pbmmusic close -f off to 的下一层");
        failures += dump(dispatcher, "pbmmusic close default to ",
                "【1.15】pbmmusic close <X> to 的下一层（应有音频名字参数 target）");

        System.out.println("-------------------- 【1.15】素材名解析（真调 resolvePsdToneName） --------------------");
        // ★ 为什么必须单独测这一层：字符串参数**不在解析期报错**（见类注释第 ③ 条），
        //   `pbmmusic close <任意字符串>` 都能 parse 成功 —— 名字到底收不收，只有
        //   resolvePsdToneName 说了算。所以这里直接把那个真方法调起来，
        //   把「四段内置名 + none/mute」逐个钉死。
        //   ★ 光在源码里 grep 常量名是不够的：那只能证明「这个词在方法里出现过」，
        //     证明不了「它被正确接住、返回了正确的 id」。本项目最常犯的错就是
        //     「常量加了、某个调用点漏了」，只有真调一次才拦得住。
        //   这些名字走的是**前置短路分支**（在 getServerData(level) 之前就返回），
        //   所以传 null 当 level 不会 NPE —— 非内置名才会去查音频库，那种情形这里不测。
        failures += expectPsdToneName("default", EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN, false);
        failures += expectPsdToneName("default-c", EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE, false);
        failures += expectPsdToneName("default-m", EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M, false);
        failures += expectPsdToneName("default-s", EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S, false);
        // 指令名解析是大小写不敏感的（resolvePsdToneName 里先 toLowerCase）。
        failures += expectPsdToneName("DEFAULT-S", EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S, false);
        failures += expectPsdToneName("none", EscalatorSpeedData.PSD_TONE_OFF, true);
        failures += expectPsdToneName("mute", EscalatorSpeedData.PSD_TONE_OFF, true);

        System.out.println("-------------------- 【1.50】/pbmloud 指令树 --------------------");
        failures += dump(dispatcher, "pbmloud ", "pbmloud 的直接子节点（应有 -f / close / open）");
        failures += dump(dispatcher, "pbmloud 200 ", "pbmloud <音量> 的下一层");
        failures += dump(dispatcher, "pbmloud 200 to ", "pbmloud <X> to 的下一层");
        failures += dump(dispatcher, "pbmloud open ", "pbmloud open 的直接子节点（数值参数，无补全）");
        failures += dump(dispatcher, "pbmloud open 200 to ", "pbmloud open <X> to 的下一层");
        failures += dump(dispatcher, "pbmloud close 200 ", "pbmloud close <音量> 的下一层");
        failures += dump(dispatcher, "pbmloud -f ", "pbmloud -f 的下一层（应有 close / open）");
        failures += dump(dispatcher, "pbmloud -f 200 to ", "pbmloud -f <X> to 的下一层");
        failures += dump(dispatcher, "pbmloud -f open 200 to ", "pbmloud -f open <X> to 的下一层");
        failures += dump(dispatcher, "pbmloud -f close 200 ", "pbmloud -f close <音量> 的下一层");
        System.out.println("-------------------- 【1.50】【09-29】/pbmround 指令树（范围已拆双维） --------------------");
        // ★【09-29】范围拆成**水平（x、z 轴）+ 垂直（y 轴）**两格 ⇒ 形状 = `[<xz> <y>] [to <Xz> <Y>]`
        //   （所有 round 指令都由 SmoothLift.roundCommand 一处产出）。只给一个数值**不成立**。
        failures += dump(dispatcher, "pbmround ", "pbmround 的直接子节点（数值参数，无补全）");
        failures += dump(dispatcher, "pbmround 20 ", "pbmround <水平> 的下一层（还要一格垂直）");
        failures += dump(dispatcher, "pbmround 20 5 ", "pbmround <水平> <垂直> 的下一层");
        failures += dump(dispatcher, "pbmround 20 5 to ", "pbmround <Xz> <Y> to 的下一层");
        failures += dump(dispatcher, "pbmround -f ", "pbmround -f 的下一层");
        failures += dump(dispatcher, "pbmround -f 20 5 to ", "pbmround -f <Xz> <Y> to 的下一层");
        // 【1.23】三条同形指令（一串门都由 SmoothLift.roundCommand 产出 ⇒ 形状想不一致都难）
        System.out.println("-------------------- 【1.23】/pbmmusicround /pbmmidiumround /pbmarriveround 指令树 --------------------");
        for (String nm : new String[]{"pbmmusicround", "pbmmidiumround", "pbmarriveround"}) {
            failures += dump(dispatcher, nm + " ", nm + " 的直接子节点（数值参数，无补全）");
            failures += dump(dispatcher, nm + " 20 ", nm + " <水平> 的下一层（还要一格垂直）");
            failures += dump(dispatcher, nm + " 20 5 ", nm + " <水平> <垂直> 的下一层");
            failures += dump(dispatcher, nm + " 20 5 to ", nm + " <Xz> <Y> to 的下一层");
            failures += dump(dispatcher, nm + " -f ", nm + " -f 的下一层");
            failures += dump(dispatcher, nm + " -f 20 5 to ", nm + " -f <Xz> <Y> to 的下一层");
        }
        System.out.println("-------------------- 【1.16】/pbmclosewait 指令树（关门提示音强制等待时长） --------------------");
        failures += dump(dispatcher, "pbmclosewait ", "pbmclosewait 的直接子节点（数值参数，无补全）");
        failures += dump(dispatcher, "pbmclosewait 5 ", "pbmclosewait <秒> 的下一层（应有 to）");
        failures += dump(dispatcher, "pbmclosewait 5 to ", "pbmclosewait <X> to 的下一层");
        failures += dump(dispatcher, "pbmclosewait -f ", "pbmclosewait -f 的下一层");
        failures += dump(dispatcher, "pbmclosewait -f 5 to ", "pbmclosewait -f <X> to 的下一层");
        System.out.println("-------------------- 【1.17】/pbmmidium 指令树（到站播报：素材名 + 等待秒数） --------------------");
        // 形状：/pbmmidium                      -> 显示当前
        //       /pbmmidium <名字>               -> 只改素材（保留秒数）
        //       /pbmmidium <名字> <秒>          -> 本维度
        //       /pbmmidium -f <名字> <秒>       -> 所有维度
        // ★ 与 /pbmclosewait 的**唯一**形状差别：它第一个参数是**字符串**（素材名），
        //   所以 `pbmmidium ` 这一层的补全项只有字面量 -f；名字的候选（off + 音频库）
        //   在这个工具里测不到（null source ⇒ 补全提供器返回空），候选本身由
        //   _tools/check-psd-tone.py 做源码级断言。
        failures += dump(dispatcher, "pbmmidium ", "pbmmidium 的直接子节点（应有 -f；名字是字符串参数不补全）");
        failures += dump(dispatcher, "pbmmidium default ", "pbmmidium <名字> 的下一层（数值参数，无补全）");
        failures += dump(dispatcher, "pbmmidium -f ", "pbmmidium -f 的下一层（字符串参数不补全）");
        failures += dump(dispatcher, "pbmmidium -f default ", "pbmmidium -f <名字> 的下一层（数值参数，无补全）");
        // 真·执行器：四条路（显示 / 只改名字 / 本维度 / 所有维度）都必须落在一个有执行器的节点上。
        failures += expectExecutable(dispatcher, "pbmmidium");
        failures += expectExecutable(dispatcher, "pbmmidium default");
        failures += expectExecutable(dispatcher, "pbmmidium default 0");
        failures += expectExecutable(dispatcher, "pbmmidium default 5");
        failures += expectExecutable(dispatcher, "pbmmidium -f default 5");
        // 【1.17】★ 用户点名「pbmmidium 指令和 ui 允许输入 0 到正无穷的数字 [0,+∞)」：
        //   下界 0 必须**收**（0 = 开门音一停就播），负值必须**拒**，int 上界原样收。
        //   注意「只测中间值」是测不出这道闸的 —— 0 与 -1 必须逐个钉住。
        failures += expectExecutable(dispatcher, "pbmmidium default 0");
        failures += expectExecutable(dispatcher, "pbmmidium -f default 0");
        failures += expectNotParsed(dispatcher, "pbmmidium default -1", "到站播报等待时长下界是 0 秒");
        failures += expectNotParsed(dispatcher, "pbmmidium -f default -1", "-f 分支同样下界 0 秒");
        // ★ 上界故意不设 ⇒ 极大值也该收；超 int 的写法要在**参数类型**这一层就被拒。
        failures += expectExecutable(dispatcher, "pbmmidium default " + Integer.MAX_VALUE);
        failures += expectNotParsed(dispatcher, "pbmmidium default 2147483648", "超出 int 的秒数应当解析失败");
        // ★ 真调一次**数据层**的夹取（第二道闸）：指令层能挡 -1，但挡不住「石斧 UI 输入框」
        //   与「存档里被外部改坏的值」这两条路 —— 它们只经过 clampPsdMidiumWaitSeconds。
        //   上界**不设**是这次的需求本身，所以这里要专门断言「大值原样通过」（写错了会变成「填大没用」）。
        failures += expectClampMidiumWait(-1, EscalatorSpeedData.PSD_MIDIUM_WAIT_MIN);
        failures += expectClampMidiumWait(0, 0);
        failures += expectClampMidiumWait(5, 5);
        failures += expectClampMidiumWait(3600, 3600);
        failures += expectClampMidiumWait(Integer.MAX_VALUE, Integer.MAX_VALUE);
        failures += expectMidiumWaitBounds();
        failures += expectMidiumWaitDefault();
        // ★ 素材名归一化：**没有内置到站播报素材** ⇒ 空串只能是「不播」（不能像提示音那样
        //   落回 default，否则会去播一段不存在的素材）。off / none / 大小写都要收敛。
        failures += expectMidiumNormalize(null, EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectMidiumNormalize("", EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectMidiumNormalize("off", EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectMidiumNormalize("OFF", EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectMidiumNormalize("none", EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectMidiumNormalize("NONE", EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectMidiumNormalize("shanghai.ogg", "shanghai.ogg");
        // ★ 同样真调一次「名字解析」（理由同 expectPsdToneName：字符串参数不在解析期报错，
        //   名字收不收只有解析方法说了算）。off / none 走**前置短路**分支，传 null level 不会 NPE；
        //   到站播报**没有内置素材**，所以这几个词收敛成「不播」，而**不是**「用内置」。
        failures += expectPsdMidiumName("off", EscalatorSpeedData.PSD_MIDIUM_OFF);
        failures += expectPsdMidiumName("none", EscalatorSpeedData.PSD_MIDIUM_OFF);

        System.out.println();
        System.out.println("-------------------- 【1.21】/pbmarrive 指令树（进站报站：素材名 + 秒数：-X = 最近一班车还剩 X 秒到站时播） --------------------");
        // 形状与 /pbmmidium **逐字同形**，只有一处**故意相反**：秒数范围是 (-∞, 0]
        //   （用户点名「输入框范围：(-无穷,0]」，例子 X=-50 = 「开门嘀嘀嘀开始播放那一刻的 50 秒前」）。
        //   所以 /pbmmidium 那一节断言「-1 必须被拒」，这一节断言「-1 必须收、1 必须被拒」——
        //   两条合起来才说明「两个参数类型确实不一样」，单看任何一边都可能只是抄错。
        failures += dump(dispatcher, "pbmarrive ", "pbmarrive 的直接子节点（应有 -f）");
        failures += dump(dispatcher, "pbmarrive default ", "pbmarrive <名字> 的下一层");
        failures += dump(dispatcher, "pbmarrive -f ", "pbmarrive -f 的下一层");
        failures += expectExecutable(dispatcher, "pbmarrive");
        failures += expectExecutable(dispatcher, "pbmarrive default");
        failures += expectExecutable(dispatcher, "pbmarrive default 0");
        failures += expectExecutable(dispatcher, "pbmarrive default -1");
        failures += expectExecutable(dispatcher, "pbmarrive default -50");
        failures += expectExecutable(dispatcher, "pbmarrive -f default -50");
        // ★ 上界 0：0 收（= 与开门音同一刻），正值**必须拒**（「晚于开门音」没有意义）。
        failures += expectNotParsed(dispatcher, "pbmarrive default 1", "进站报站秒数上界是 0 秒（正值 = 晚于到站，无意义）");
        failures += expectNotParsed(dispatcher, "pbmarrive -f default 1", "-f 分支同样上界 0 秒");
        // ★ 下界故意不设 ⇒ Integer.MIN_VALUE 也该收；超 int 的写法要在参数类型这一层被拒。
        failures += expectExecutable(dispatcher, "pbmarrive default " + Integer.MIN_VALUE);
        failures += expectNotParsed(dispatcher, "pbmarrive default -2147483649", "超出 int 的秒数应当解析失败");
        // ★ 真调一次**数据层**的夹取（第二道闸）：指令层能挡正值，但挡不住
        //   「石斧 UI 输入框」与「存档里被外部改坏的值」—— 它们只经过 clampPsdArriveSeconds。
        //   与到站播报**正好相反**：这里要断言「很小的负值原样通过」（写错了会变成「填 -3600 没用」）。
        failures += expectClampArrive(1, EscalatorSpeedData.PSD_ARRIVE_SECONDS_MAX);
        failures += expectClampArrive(0, 0);
        failures += expectClampArrive(-1, -1);
        failures += expectClampArrive(-50, -50);
        failures += expectClampArrive(-3600, -3600);
        failures += expectClampArrive(Integer.MIN_VALUE, Integer.MIN_VALUE);
        failures += expectArriveBounds();
        failures += expectArriveDefault();
        // ★ 素材名归一化：进站报站同样**没有内置素材** ⇒ 空值只能是「不播」。
        failures += expectArriveNormalize(null, EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectArriveNormalize("", EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectArriveNormalize("off", EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectArriveNormalize("OFF", EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectArriveNormalize("none", EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectArriveNormalize("NONE", EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectArriveNormalize("jinzhan.ogg", "jinzhan.ogg");
        // ★ 真调一次「名字解析」（理由同 expectPsdToneName）：
        //   off / none 必须收敛成「不播」，且走的是**前置短路**分支（传 null level 不 NPE）。
        failures += expectPsdArriveName("off", EscalatorSpeedData.PSD_ARRIVE_OFF);
        failures += expectPsdArriveName("none", EscalatorSpeedData.PSD_ARRIVE_OFF);

        System.out.println();
        System.out.println("==================== 【1.50】/pbmmusic open|close 与 /lifthelp up|down 同形状 ====================");
        // 需求是「指令部分与直梯几乎一致」：open / close 两棵子开关树必须与直梯的
        // up / down 两棵**逐层同形**（顶层 / on / off / to / -f / -f on to …）——
        // 以后谁把 to 或 -f 漏掉一层，这里立刻红。
        // ★【1.15】直梯那两棵从顶级指令搬到了 /lifthelp 下面（lifthelpup → lifthelp up）。
        //   compareShape 自己会补一个空格（base + " " + 后缀），所以后缀不带前导空格。
        failures += compareShape(dispatcher, "pbmmusic open", "lifthelp up", new String[][]{
                {"", ""}, {"on ", "on "}, {"on to ", "on to "}, {"off to ", "off to "},
                {"-f ", "-f "}, {"-f on to ", "-f on to "}, {"-f off to ", "-f off to "},
                // 【1.15】素材分支：两条子树都要有 <名字> / <X> to <Y> / -f <名字> / -f <X> to <Y>
                {"default ", "default "}, {"default to ", "default to "},
                {"-f default ", "-f default "}, {"-f default to ", "-f default to "},
        });
        failures += compareShape(dispatcher, "pbmmusic close", "lifthelp down", new String[][]{
                {"", ""}, {"on ", "on "}, {"on to ", "on to "}, {"off to ", "off to "},
                {"-f ", "-f "}, {"-f on to ", "-f on to "}, {"-f off to ", "-f off to "},
                {"default ", "default "}, {"default to ", "default to "},
                {"-f default ", "-f default "}, {"-f default to ", "-f default to "},
        });

        System.out.println("-------------------- 【1.50】/pbmloud open|close 与 /lifthelploud up|down 同形状 --------------------");
        failures += compareShape(dispatcher, "pbmloud open", "lifthelploud up", new String[][]{
                {"", ""}, {"200 ", "200 "}, {"200 to ", "200 to "},
        });
        failures += compareShape(dispatcher, "pbmloud -f open", "lifthelploud -f up", new String[][]{
                {"", ""}, {"200 ", "200 "}, {"200 to ", "200 to "},
        });
        failures += compareShape(dispatcher, "pbmloud close", "lifthelploud down", new String[][]{
                {"", ""}, {"200 ", "200 "}, {"200 to ", "200 to "},
        });

        System.out.println("-------------------- 【10-03】/pbmround 与 /lifthelpround：**又同形了**（都双维） --------------------");
        // ★ 1.23~1.29 两条同形（都是「一个整数 + to + -f」）；【09-29】屏蔽门那四条范围拆成了
        //   **双维**（水平 + 垂直），当时 /lifthelpround 仍是单值 ⇒ 两条不同形。
        //   ★【10-03】用户点名把所有 round 都拆双维 ⇒ 两条**又同形了**，改回做对撞
        //   （谁被顺手改回单维，这里当场红）。
        failures += compareShape(dispatcher, "lifthelpround", "pbmround", new String[][]{
                {"", ""}, {"20 ", "20 "}, {"20 5 ", "20 5 "}, {"20 5 to ", "20 5 to "},
                {"-f ", "-f "}, {"-f 20 ", "-f 20 "}, {"-f 20 5 ", "-f 20 5 "},
                {"-f 20 5 to ", "-f 20 5 to "},
        });
        failures += expect(dispatcher, "lifthelpround 20 ");          // 双维：只给水平不成立
        failures += expect(dispatcher, "lifthelpround 20 5 ", "to");
        failures += expect(dispatcher, "lifthelpround -f 20 ");
        failures += expect(dispatcher, "lifthelpround -f 20 5 ", "to");
        failures += expect(dispatcher, "pbmround 20 ");              // 屏蔽门：双维，只给水平不成立
        failures += expect(dispatcher, "pbmround 20 5 ", "to");
        failures += expect(dispatcher, "pbmround -f 20 ");
        failures += expect(dispatcher, "pbmround -f 20 5 ", "to");

        System.out.println("-------------------- 【1.23】/pbmmusicround|/pbmmidiumround|/pbmarriveround 与 /pbmround 同形状 --------------------");
        // ★ 三条新指令由 SmoothLift.roundCommand(...) 一处产出，形状**必须**与 /pbmround 逐格一致；
        //   真建一遍树做对撞，比 grep 每一个 literal 强（历史上栽过「常量加了、某个调用点漏了」）。
        for (String nm : new String[]{"pbmmusicround", "pbmmidiumround", "pbmarriveround"}) {
            failures += compareShape(dispatcher, nm, "pbmround", new String[][]{
                    {"", ""}, {"20 ", "20 "}, {"20 5 ", "20 5 "}, {"20 5 to ", "20 5 to "},
                    {"-f ", "-f "}, {"-f 20 ", "-f 20 "}, {"-f 20 5 ", "-f 20 5 "},
                    {"-f 20 5 to ", "-f 20 5 to "},
            });
        }

        System.out.println("-------------------- 【09-29】/pbmclosewait 与 /pbmround：**刻意不同形**（单值秒数 vs 双维范围） --------------------");
        // ★【1.16】这条曾经**照抄** /pbmround 的形状（显示 / <值> / <X> to <Y> / -f <值> / -f <X> to <Y>）。
        //   【09-29】范围拆双维之后 /pbmround 多了一层 `<y>`，而秒数天然只有一个值 ⇒ 两条不再同形，
        //   那条对撞断言作废；改成**两条各自钉自己的形状**（防止谁被顺手改成另一种）。
        failures += expect(dispatcher, "pbmclosewait 5 ", "to");
        failures += expect(dispatcher, "pbmclosewait -f 5 ", "to");
        failures += expect(dispatcher, "pbmclosewait 5 to ");
        failures += expect(dispatcher, "pbmround 20 ");
        failures += expect(dispatcher, "pbmround 20 5 ", "to");

        System.out.println();
        System.out.println("==================== 【1.41】/futihelpmusic 与 /futihelpspeed 形状对齐 ====================");
        failures += expectSameShape(dispatcher, "futihelpmusic", "futihelpspeed");

        System.out.println();
        System.out.println("==================== 【1.48】/lifthelploud 三项分支各自可执行 ====================");
        // 1.43 时 lifthelploud 与 lifthelpspeed 同构；1.60 加了 up|down|open|close 四项子分支后不再同构，
        // 同构断言改由上面的逐分支 dump + 这里的三项可执行守住。
        for (String liftToneLoud : new String[]{"up", "down", "open", "close"}) {
            failures += expectExecutable(dispatcher, "lifthelploud " + liftToneLoud + " 200");
            failures += expectExecutable(dispatcher, "lifthelploud " + liftToneLoud + " 200 to 300");
            failures += expectExecutable(dispatcher, "lifthelploud -f " + liftToneLoud + " 200");
            failures += expectExecutable(dispatcher, "lifthelploud -f " + liftToneLoud + " 200 to 300");
        }

        System.out.println();
        System.out.println("==================== 期望的补全项 ====================");
        failures += expect(dispatcher, "futihelp ", "-f", "off", "on");  // Brigadier 补全按字典序
        failures += expect(dispatcher, "futihelp on ", "to");
        failures += expect(dispatcher, "futihelp on to ", "off");
        failures += expect(dispatcher, "futihelp off ", "to");
        failures += expect(dispatcher, "futihelp off to ", "on");
        failures += expect(dispatcher, "futihelp -f ", "off", "on");
        failures += expect(dispatcher, "futihelp -f on ", "to");
        failures += expect(dispatcher, "futihelp -f on to ", "off");
        failures += expect(dispatcher, "futihelp -f off ", "to");
        failures += expect(dispatcher, "futihelp -f off to ", "on");
        failures += expect(dispatcher, "jietispeed ", "-f");   // 数值参数不补全，-f 可补全
        failures += expect(dispatcher, "futiloud ", "-f");
        failures += expect(dispatcher, "futihelploud ", "-f");   // 数值参数不补全，-f 可补全
        failures += expect(dispatcher, "futiround ", "-f");      // 【1.24】
        failures += expect(dispatcher, "futiround 20 ");          // ★【10-03】双维：只给水平不成立
        failures += expect(dispatcher, "futiround 20 5 ", "to");
        failures += expect(dispatcher, "futiround 20 5 to ");     // 数值参数不给补全项（既有指令同理）
        failures += expect(dispatcher, "futiround -f 20 ");
        failures += expect(dispatcher, "futiround -f 20 5 ", "to");
        failures += expect(dispatcher, "futihelpround ", "-f");  // 【1.24】
        failures += expect(dispatcher, "futihelpround 4 ");       // ★【10-03】双维
        failures += expect(dispatcher, "futihelpround 4 5 ", "to");
        failures += expect(dispatcher, "futihelpround 4 5 to ");
        failures += expect(dispatcher, "futihelpround -f 4 ");
        failures += expect(dispatcher, "futihelpround -f 4 5 ", "to");
        // 【1.31】/futihelpspeed：顶层有 -f / in / out（Brigadier 字面量按字典序，'-' < 'i' < 'o'）
        failures += expect(dispatcher, "futihelpspeed ", "-f", "in", "out");
        failures += expect(dispatcher, "futihelpspeed in ");
        failures += expect(dispatcher, "futihelpspeed in 5 ", "to");
        failures += expect(dispatcher, "futihelpspeed in 5 to ");
        failures += expect(dispatcher, "futihelpspeed out 1 ", "to");
        failures += expect(dispatcher, "futihelpspeed -f ", "in", "out");
        failures += expect(dispatcher, "futihelpspeed -f in 5 ", "to");
        failures += expect(dispatcher, "futihelpspeed -f in 5 to ");
        failures += expect(dispatcher, "futihelpspeed -f out 1 ", "to");

        // 【1.41】/futihelpmusic：结构与 /futihelpspeed 逐层对齐（顶层 = -f / in / out，
        // 每个 in|out 下都是 `<名字> [to <名字>]`；名字是字符串参数 → 不补全）。
        // ★ 这里没有 expectNotParsed：「名字」是 StringArgumentType.string()，会把任何词（含 -f、to）
        //   都当成名字吃掉，所以不存在「打错词就报错」的分支 —— 与 /futimusic 完全一致。
        failures += expect(dispatcher, "futihelpmusic ", "-f", "in", "out");
        failures += expect(dispatcher, "futihelpmusic in ");
        failures += expect(dispatcher, "futihelpmusic in default ", "to");
        failures += expect(dispatcher, "futihelpmusic in default to ");
        failures += expect(dispatcher, "futihelpmusic out default ", "to");
        failures += expect(dispatcher, "futihelpmusic out default to ");
        failures += expect(dispatcher, "futihelpmusic -f ", "in", "out");
        failures += expect(dispatcher, "futihelpmusic -f in ");
        failures += expect(dispatcher, "futihelpmusic -f in default ", "to");
        failures += expect(dispatcher, "futihelpmusic -f in default to ");
        failures += expect(dispatcher, "futihelpmusic -f out default ", "to");
        failures += expect(dispatcher, "futihelpmusic -f out default to ");

        // 【1.42】/lifthelp：与 /futihelp 同形状的 on|off 开关（只是 `-f` 的含义是「所有维度」）。
        failures += expect(dispatcher, "lifthelp ", "-f", "close", "down", "off", "on", "open", "up");  // 字典序
        failures += expect(dispatcher, "lifthelp on ", "to");
        failures += expect(dispatcher, "lifthelp on to ", "off");
        failures += expect(dispatcher, "lifthelp off ", "to");
        failures += expect(dispatcher, "lifthelp off to ", "on");
        failures += expect(dispatcher, "lifthelp -f ", "off", "on");
        failures += expect(dispatcher, "lifthelp -f on to ", "off");
        // 【1.60】/lifthelp up|down|open|close：同一层 = 字面量 -f / off / on + 音频名字字符串参数。
        //   ★ 字符串参数的补全候选在**这个工具里是空的**（null source，见类头说明），
        //     所以这里只断言三个字面量 —— 候选列表由 check-lift-tone.py 做源码级断言。
        for (String liftTone : new String[]{"up", "down", "open", "close"}) {
            failures += expect(dispatcher, "lifthelp " + liftTone + " ", "-f", "off", "on");
            failures += expect(dispatcher, "lifthelp " + liftTone + " on ", "to");
            failures += expect(dispatcher, "lifthelp " + liftTone + " on to ", "off");
            failures += expect(dispatcher, "lifthelp " + liftTone + " off ", "to");
            failures += expect(dispatcher, "lifthelp " + liftTone + " off to ", "on");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f ", "off", "on");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f on ", "to");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f on to ", "off");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f off ", "to");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f off to ", "on");
            // <名字> 这一支：名字是字符串参数 → 不给补全项，但下一层的 to 要能补出来
            failures += expect(dispatcher, "lifthelp " + liftTone + " default ", "to");
            failures += expect(dispatcher, "lifthelp " + liftTone + " default to ");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f default ", "to");
            failures += expect(dispatcher, "lifthelp " + liftTone + " -f default to ");
        }
        // 【1.43】/lifthelploud：数值参数不补全，-f 可补全；【1.60】加了 up|down|open|close 四个子分支
        failures += expect(dispatcher, "lifthelploud ", "-f", "close", "down", "open", "up");  // Brigadier 按字典序
        failures += expect(dispatcher, "lifthelploud 200 ", "to");
        failures += expect(dispatcher, "lifthelploud 200 to ");    // 数值参数不给补全项
        failures += expect(dispatcher, "lifthelploud -f ", "close", "down", "open", "up");  // 共用音量是数值参数不补全
        failures += expect(dispatcher, "lifthelploud -f 200 ", "to");
        failures += expect(dispatcher, "lifthelploud -f 200 to ");
        failures += expect(dispatcher, "lifthelploud -f up 200 ", "to");
        failures += expect(dispatcher, "lifthelploud -f down 200 to ");   // 数值参数不给补全项
        failures += expect(dispatcher, "lifthelploud -f open ");
        failures += expect(dispatcher, "lifthelploud -f close ");

        // 【1.50】/pbmmusic：顶层 = -f / close / off / on / open（Brigadier 补全按字典序）。
        //   ★ 与 /lifthelp 的差别就在这两个额外子节点 open / close。
        failures += expect(dispatcher, "pbmmusic ", "-f", "close", "off", "on", "open");
        failures += expect(dispatcher, "pbmmusic on ", "to");
        failures += expect(dispatcher, "pbmmusic on to ", "off");
        failures += expect(dispatcher, "pbmmusic off to ", "on");
        failures += expect(dispatcher, "pbmmusic -f ", "off", "on");
        failures += expect(dispatcher, "pbmmusic -f on to ", "off");
        failures += expect(dispatcher, "pbmmusic open ", "-f", "off", "on");
        failures += expect(dispatcher, "pbmmusic open on ", "to");
        failures += expect(dispatcher, "pbmmusic open on to ", "off");
        failures += expect(dispatcher, "pbmmusic open -f ", "off", "on");
        failures += expect(dispatcher, "pbmmusic open -f on to ", "off");
        failures += expect(dispatcher, "pbmmusic close ", "-f", "off", "on");
        // 【1.50】/pbmloud：顶层 = -f / close / open（数值参数不补全，-f 可补全）
        failures += expect(dispatcher, "pbmloud ", "-f", "close", "open");
        failures += expect(dispatcher, "pbmloud 200 ", "to");
        failures += expect(dispatcher, "pbmloud 200 to ");     // 数值参数不给补全项
        failures += expect(dispatcher, "pbmloud open ");        // 数值参数不给补全项
        failures += expect(dispatcher, "pbmloud open 200 ", "to");
        failures += expect(dispatcher, "pbmloud -f ", "close", "open");
        failures += expect(dispatcher, "pbmloud -f 200 ", "to");
        failures += expect(dispatcher, "pbmloud -f open 200 ", "to");
        // 【1.50】【09-29】/pbmround：范围拆双维 ⇒ 数值参数不补全，`<xz> <y>` 一格都不能少
        failures += expect(dispatcher, "pbmround ", "-f");
        failures += expect(dispatcher, "pbmround 20 ");
        failures += expect(dispatcher, "pbmround 20 5 ", "to");
        failures += expect(dispatcher, "pbmround 20 5 to ");
        failures += expect(dispatcher, "pbmround -f 20 ");
        failures += expect(dispatcher, "pbmround -f 20 5 ", "to");
        // 【1.23】三条同形指令：-f 与 to 都必须可达
        for (String nm : new String[]{"pbmmusicround", "pbmmidiumround", "pbmarriveround"}) {
            failures += expect(dispatcher, nm + " ", "-f");
            failures += expect(dispatcher, nm + " 20 ");
            failures += expect(dispatcher, nm + " 20 5 ", "to");
            failures += expect(dispatcher, nm + " 20 5 to ");
            failures += expect(dispatcher, nm + " -f 20 ");
            failures += expect(dispatcher, nm + " -f 20 5 ", "to");
        }
        // 【1.16】/pbmclosewait：单值秒数（数值参数不补全、-f 与 to 必须可达）
        failures += expect(dispatcher, "pbmclosewait ", "-f");
        failures += expect(dispatcher, "pbmclosewait 5 ", "to");
        failures += expect(dispatcher, "pbmclosewait 5 to ");
        failures += expect(dispatcher, "pbmclosewait -f 5 ", "to");

        // 【09-28 续 2】/pbmnarrate：三档样式用**字面量**分支（不是字符串参数）
        //   ⇒ Tab 补全里必须正好看到这三个候选（加 -f）。顺序 = Brigadier 的字典序。
        failures += expect(dispatcher, "pbmnarrate ", "-f", "hongkong", "off", "shanghai");
        failures += expect(dispatcher, "pbmnarrate -f ", "hongkong", "off", "shanghai");

        System.out.println();
        System.out.println("==================== 每条完整指令都可执行 ====================");
        failures += expectExecutable(dispatcher, "futihelp");
        failures += expectExecutable(dispatcher, "futihelp on");
        failures += expectExecutable(dispatcher, "futihelp off");
        failures += expectExecutable(dispatcher, "futihelp on to off");
        failures += expectExecutable(dispatcher, "futihelp off to on");
        failures += expectExecutable(dispatcher, "futihelp -f on");
        failures += expectExecutable(dispatcher, "futihelp -f off");
        failures += expectExecutable(dispatcher, "futihelp -f on to off");
        failures += expectExecutable(dispatcher, "futihelp -f off to on");
        failures += expectExecutable(dispatcher, "futispeed");
        failures += expectExecutable(dispatcher, "futispeed -f 2");
        failures += expectExecutable(dispatcher, "futispeed -f 2 to 3");
        failures += expectExecutable(dispatcher, "jietispeed");
        failures += expectExecutable(dispatcher, "futiloud");
        failures += expectExecutable(dispatcher, "futiloud -f 200");
        failures += expectExecutable(dispatcher, "futiloud -f 200 to 300");
        failures += expectExecutable(dispatcher, "futihelploud");
        failures += expectExecutable(dispatcher, "futihelploud 200");
        failures += expectExecutable(dispatcher, "futihelploud 200 to 300");
        failures += expectExecutable(dispatcher, "futihelploud -f 200");
        failures += expectExecutable(dispatcher, "futihelploud -f 200 to 300");
        failures += expectExecutable(dispatcher, "futimusic");
        // 【1.24】两个「淡入淡出范围」指令（★【10-03】已拆**双维**：<水平> <垂直>）
        failures += expectExecutable(dispatcher, "futiround");
        failures += expectExecutable(dispatcher, "futiround 20 5");
        failures += expectExecutable(dispatcher, "futiround 20 5 to 30 5");
        failures += expectExecutable(dispatcher, "futiround -f 20 5");
        failures += expectExecutable(dispatcher, "futiround -f 20 5 to 30 5");
        failures += expectExecutable(dispatcher, "futihelpround");
        failures += expectExecutable(dispatcher, "futihelpround 4 5");
        failures += expectExecutable(dispatcher, "futihelpround 4 5 to 6 5");
        failures += expectExecutable(dispatcher, "futihelpround -f 6 5");
        failures += expectExecutable(dispatcher, "futihelpround -f 6 5 to 8 5");
        // 【1.31】无障碍提示音速率（入口 / 出口各一套，含 -f 与 to）
        failures += expectExecutable(dispatcher, "futihelpspeed");
        failures += expectExecutable(dispatcher, "futihelpspeed in 5");
        failures += expectExecutable(dispatcher, "futihelpspeed in 5 to 8");
        failures += expectExecutable(dispatcher, "futihelpspeed out 3");
        failures += expectExecutable(dispatcher, "futihelpspeed out 3 to 4");
        failures += expectExecutable(dispatcher, "futihelpspeed -f in 5");
        failures += expectExecutable(dispatcher, "futihelpspeed -f in 5 to 8");
        failures += expectExecutable(dispatcher, "futihelpspeed -f out 1");
        failures += expectExecutable(dispatcher, "futihelpspeed -f out 1 to 2");
        // 【1.41】/futihelpmusic：进 / 出两套 × 5 种形状都能执行（default / off / 文件名 / to / -f）
        failures += expectExecutable(dispatcher, "futihelpmusic");
        failures += expectExecutable(dispatcher, "futihelpmusic in default");
        failures += expectExecutable(dispatcher, "futihelpmusic in off");
        failures += expectExecutable(dispatcher, "futihelpmusic in example.ogg");
        failures += expectExecutable(dispatcher, "futihelpmusic out default");
        failures += expectExecutable(dispatcher, "futihelpmusic out off");
        failures += expectExecutable(dispatcher, "futihelpmusic out example.ogg");
        failures += expectExecutable(dispatcher, "futihelpmusic in default to off");
        failures += expectExecutable(dispatcher, "futihelpmusic out example.ogg to default");
        failures += expectExecutable(dispatcher, "futihelpmusic -f in default");
        failures += expectExecutable(dispatcher, "futihelpmusic -f in off");
        failures += expectExecutable(dispatcher, "futihelpmusic -f in default to example.ogg");
        failures += expectExecutable(dispatcher, "futihelpmusic -f out default");
        failures += expectExecutable(dispatcher, "futihelpmusic -f out example.ogg to off");
        // ★ 旧的「裸名字」写法（1.39 的 /futihelpmusic <名字>）在 1.41 已经**不存在**了；
        //   它现在会停在根节点（不带参数 = 显示当前值），所以只能放对照区 probe，不能写 expectNotParsed。

        // 【1.42】直梯提示音：开关 / 倍速，【1.43】再加音量 —— 三种形状全部可执行
        failures += expectExecutable(dispatcher, "lifthelp");
        failures += expectExecutable(dispatcher, "lifthelp on");
        failures += expectExecutable(dispatcher, "lifthelp off");
        failures += expectExecutable(dispatcher, "lifthelp on to off");
        failures += expectExecutable(dispatcher, "lifthelp off to on");
        failures += expectExecutable(dispatcher, "lifthelp -f on");
        failures += expectExecutable(dispatcher, "lifthelp -f off");
        failures += expectExecutable(dispatcher, "lifthelp -f on to off");
        failures += expectExecutable(dispatcher, "lifthelp -f off to on");
        // 【1.60】/lifthelp up|down|open|close：子开关 + 默认素材，四套 × 各 5 种形状都要可执行
        for (String liftTone : new String[]{"up", "down", "open", "close"}) {
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone);
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " on");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " off");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " on to off");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " off to on");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f on");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f off");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f on to off");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f off to on");
            // 音频素材：default / none / 导入的 .ogg（三种名字） × 本维度 / X to Y / -f / -f X to Y
            for (String audio : new String[]{"default", "none", "example.ogg"}) {
                failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " " + audio);
                failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f " + audio);
            }
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " default to none");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " none to example.ogg");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f default to none");
            failures += expectExecutable(dispatcher, "lifthelp " + liftTone + " -f example.ogg to default");
        }
        failures += expectExecutable(dispatcher, "lifthelploud");
        failures += expectExecutable(dispatcher, "lifthelploud 200");
        failures += expectExecutable(dispatcher, "lifthelploud 200 to 300");
        failures += expectExecutable(dispatcher, "lifthelploud -f 200");
        failures += expectExecutable(dispatcher, "lifthelploud -f 200 to 300");

        // 【1.50】屏蔽门三条：总开关 + open|close 子开关 / 音量（共用 + 两项）+ 范围，
        //   每种形状（无参 / 设值 / X to Y / -f / -f X to Y）都要可执行。
        failures += expectExecutable(dispatcher, "pbmmusic");
        failures += expectExecutable(dispatcher, "pbmmusic on");
        failures += expectExecutable(dispatcher, "pbmmusic off");
        failures += expectExecutable(dispatcher, "pbmmusic on to off");
        failures += expectExecutable(dispatcher, "pbmmusic off to on");
        failures += expectExecutable(dispatcher, "pbmmusic -f on");
        failures += expectExecutable(dispatcher, "pbmmusic -f off");
        failures += expectExecutable(dispatcher, "pbmmusic -f on to off");
        failures += expectExecutable(dispatcher, "pbmmusic -f off to on");
        for (String psdItem : new String[]{"open", "close"}) {
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem);
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " on");
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " off");
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " on to off");
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " off to on");
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " -f on");
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " -f off");
            failures += expectExecutable(dispatcher, "pbmmusic " + psdItem + " -f on to off");
        }
        failures += expectExecutable(dispatcher, "pbmloud");
        failures += expectExecutable(dispatcher, "pbmloud 200");
        failures += expectExecutable(dispatcher, "pbmloud 200 to 300");
        failures += expectExecutable(dispatcher, "pbmloud -f 200");
        failures += expectExecutable(dispatcher, "pbmloud -f 200 to 300");
        for (String psdItem : new String[]{"open", "close"}) {
            failures += expectExecutable(dispatcher, "pbmloud " + psdItem + " 200");
            failures += expectExecutable(dispatcher, "pbmloud " + psdItem + " 200 to 300");
            failures += expectExecutable(dispatcher, "pbmloud -f " + psdItem + " 200");
            failures += expectExecutable(dispatcher, "pbmloud -f " + psdItem + " 200 to 300");
        }
        failures += expectExecutable(dispatcher, "pbmround");
        // 【09-29】范围双维 ⇒ 每个分支都要给**两个**数值（水平 + 垂直）
        failures += expectExecutable(dispatcher, "pbmround 16 5");
        failures += expectExecutable(dispatcher, "pbmround 16 5 to 24 8");
        failures += expectExecutable(dispatcher, "pbmround -f 16 5");
        failures += expectExecutable(dispatcher, "pbmround -f 16 5 to 24 8");
        // 【1.23】三条同形指令：五个分支都要真的可执行（不只是「能 parse」）
        for (String nm : new String[]{"pbmmusicround", "pbmmidiumround", "pbmarriveround"}) {
            failures += expectExecutable(dispatcher, nm);
            failures += expectExecutable(dispatcher, nm + " 16 5");
            failures += expectExecutable(dispatcher, nm + " 16 5 to 24 8");
            failures += expectExecutable(dispatcher, nm + " -f 16 5");
            failures += expectExecutable(dispatcher, nm + " -f 16 5 to 24 8");
        }
        // 【1.16】/pbmclosewait：五个分支都要真的可执行（不只是「能 parse」）
        failures += expectExecutable(dispatcher, "pbmclosewait");
        failures += expectExecutable(dispatcher, "pbmclosewait 5");
        failures += expectExecutable(dispatcher, "pbmclosewait 5 to 10");
        failures += expectExecutable(dispatcher, "pbmclosewait -f 5");
        failures += expectExecutable(dispatcher, "pbmclosewait -f 5 to 10");
        failures += expectExecutable(dispatcher, "pbmnarrate");
        failures += expectExecutable(dispatcher, "pbmnarrate off");
        failures += expectExecutable(dispatcher, "pbmnarrate shanghai");
        failures += expectExecutable(dispatcher, "pbmnarrate hongkong");
        failures += expectExecutable(dispatcher, "pbmnarrate -f off");
        failures += expectExecutable(dispatcher, "pbmnarrate -f shanghai");
        failures += expectExecutable(dispatcher, "pbmnarrate -f hongkong");
        // ★ 样式只有那三档：既没有旧名 `on`（旧版是布尔开/关），也不收任意字符串。
        //   ⚠️ 这里必须用 expectTrailingUnconsumed 而不是 expectNotParsed —— 见那个方法的注释
        //     （带 executes 的节点后面跟多余 token，Brigadier 不留 exception）。
        failures += expectTrailingUnconsumed(dispatcher, "pbmnarrate on",
                "样式＝开/关本身，不再另设 on（旧版布尔开关的遗留名，不许悄悄兼容）");
        failures += expectTrailingUnconsumed(dispatcher, "pbmnarrate hk",
                "缩写不收 —— 只有 off / shanghai / hongkong 三个字面量");
        failures += expectTrailingUnconsumed(dispatcher, "pbmnarrate shanghai2",
                "字面量必须整词匹配（不是前缀匹配）");
        failures += expectTrailingUnconsumed(dispatcher, "pbmnarrate -f on",
                "-f 分支同样只收那三个字面量");
        // 【1.50】屏蔽门音量同样是 1~1000、范围同样是 1~128，两端都要当场拒绝/接受。
        failures += expectNotParsed(dispatcher, "pbmloud 0", "音量下限是 1");
        failures += expectNotParsed(dispatcher, "pbmloud 1001", "音量上限是 1000");
        failures += expectNotParsed(dispatcher, "pbmloud -f open 1001", "-f 单项分支同样上限 1000");
        failures += expectExecutable(dispatcher, "pbmloud 1");
        failures += expectExecutable(dispatcher, "pbmloud 1000");
        // 【09-29】范围双维 ⇒ 两个数值**都**要受 [1,128] 约束（这里只踩水平那一格，垂直由上面同一实例覆盖）
        failures += expectNotParsed(dispatcher, "pbmround 0 5", "范围下限是 1");
        failures += expectNotParsed(dispatcher, "pbmround 200 5", "范围上限是 128");
        failures += expectExecutable(dispatcher, "pbmround 128 5");
        // 【1.23】三条同形指令共用同一个数值参数实例（roundArg()）⇒ 边界必须**逐条**测：
        //   本项目栽过「常量加了、某个调用点漏了」（同一个 argument 常量在别处又 new 了一个）。
        for (String nm : new String[]{"pbmmusicround", "pbmmidiumround", "pbmarriveround"}) {
            failures += expectNotParsed(dispatcher, nm + " 0 5", nm + " 范围下限是 1");
            failures += expectNotParsed(dispatcher, nm + " 200 5", nm + " 范围上限是 128");
            failures += expectNotParsed(dispatcher, nm + " -f 0 5", nm + " -f 分支同样下限 1");
            failures += expectNotParsed(dispatcher, nm + " -f 200 5", nm + " -f 分支同样上限 128");
            failures += expectExecutable(dispatcher, nm + " 1 5");
            failures += expectExecutable(dispatcher, nm + " 128 5");
        }
        // 【1.16】/pbmclosewait 的秒数边界：★ 下界是 **0**（允许「不等」），这与 /pbmround（下界 1）
        //   是这两个指令在**数值范围**上的差别（形状自【09-29】起也刻意不同：单值 vs 双维），
        //   所以只测「中间值能过」是测不出边界错的 ——
        //   0 / 60 / 61 / -1 四个点必须逐个钉住，而且 -f 那条分支的边界要**单独**测
        //   （-f 走的是另一个 argument 实例，历史上本项目就栽在「常量加了、某个调用点漏了」）。
        failures += expectExecutable(dispatcher, "pbmclosewait 0");
        failures += expectExecutable(dispatcher, "pbmclosewait 60");
        failures += expectExecutable(dispatcher, "pbmclosewait -f 0");
        failures += expectExecutable(dispatcher, "pbmclosewait -f 60");
        // 【1.23】上限已按点名放宽到 [0,+∞)（999999），边界点改成 1000000。
        failures += expectNotParsed(dispatcher, "pbmclosewait 1000000", "强制等待上限是 999999 秒");
        failures += expectNotParsed(dispatcher, "pbmclosewait -f 1000000", "-f 分支同样上限 999999 秒");
        failures += expectNotParsed(dispatcher, "pbmclosewait -1", "强制等待下限是 0 秒");
        failures += expectNotParsed(dispatcher, "pbmclosewait 5 to 1000000", "to 的右值同样受上限约束");
        // 【1.16】★ 真调一次**数据层**的夹取（指令参数类型之外的第二道闸）。
        //   为什么必须真调：指令层能挡住 `pbmclosewait 61`，但**挡不住**「石斧 UI 输入框」
        //   与「存档里被外部改坏的值」这两条路 —— 它们都只经过 clampPsdCloseWaitSeconds。
        //   光 grep 常量名只能证明「这个词出现过」，证明不了「边界被正确夹住」。
        failures += expectClampCloseWait(-1, EscalatorSpeedData.PSD_CLOSE_WAIT_MIN);
        failures += expectClampCloseWait(0, 0);
        failures += expectClampCloseWait(5, 5);
        failures += expectClampCloseWait(60, 60);
        failures += expectClampCloseWait(61, 61);
        failures += expectClampCloseWait(1000, 1000);
        failures += expectClampCloseWait(999999, 999999);
        failures += expectClampCloseWait(1000000, 999999);
        // 默认值必须是用户点名的 5 秒（第一次加入模组时就是这个值）
        failures += expectCloseWaitDefault();
        // 上下限本身也要正确（UI 输入框的提示文案与它同源）
        failures += expectCloseWaitBounds();
        // 【1.50】/pbmmusic 的 on|off 是字面量，不存在 on to on / off to off
        failures += expectNotParsed(dispatcher, "pbmmusic on to on", "on to on 应无此分支");
        failures += expectNotParsed(dispatcher, "pbmmusic off to off", "off to off 应无此分支");
        failures += expectNotParsed(dispatcher, "pbmmusic open on to on", "open on to on 应无此分支");
        failures += expectNotParsed(dispatcher, "pbmmusic close -f off to off", "close -f off to off 应无此分支");

        System.out.println();
        System.out.println("==================== 不该存在的分支 ====================");
        failures += expectNotParsed(dispatcher, "futihelp on to on", "on to on 应无此分支");
        failures += expectNotParsed(dispatcher, "futihelp off to off", "off to off 应无此分支");
        failures += expectNotParsed(dispatcher, "futihelp -f on to on", "-f on to on 应无此分支");
        // 【1.24】范围参数越界（合法区间 1~128）必须被 Brigadier 直接拒绝
        failures += expectNotParsed(dispatcher, "futiround 0", "范围下限是 1");
        failures += expectNotParsed(dispatcher, "futiround 200", "范围上限是 128");
        failures += expectNotParsed(dispatcher, "futihelpround 0", "范围下限是 1");
        failures += expectNotParsed(dispatcher, "futihelpround 999", "范围上限是 128");
        // 【1.31】速率参数越界（【1.34】起 1~100 Hz，【1.38】起 1~50 Hz）必须被 Brigadier 直接拒绝。
        // ★ 探针要贴着**当前**上限：1.34~1.37 期间上限是 100，这里写的是 101；1.38 把上限压回 50 后
        //   必须同步改成 51，否则「51~100 又被放行」这种回归根本探不到。同时补两条正例守住上界。
        failures += expectNotParsed(dispatcher, "futihelpspeed in 0", "速率下限是 1 Hz");
        failures += expectNotParsed(dispatcher, "futihelpspeed out 51", "速率上限是 50 Hz");
        failures += expectNotParsed(dispatcher, "futihelpspeed -f in 0", "-f 分支同样下限 1 Hz");
        failures += expectNotParsed(dispatcher, "futihelpspeed -f out 51", "-f 分支同样上限 50 Hz");
        failures += expectExecutable(dispatcher, "futihelpspeed out 50");
        failures += expectExecutable(dispatcher, "futihelpspeed -f out 50");
        // 【1.43】/lifthelploud 音量参数越界（合法区间 1~1000）必须被 Brigadier 直接拒绝。
        // ★ 探针贴着**两端**（0 / 1001），并各补一条正例守住边界 —— 这样「区间被悄悄放宽/收窄」
        //   两种回归都能探到（只探上限的话，把下限从 1 改成 0 就漏了）。
        failures += expectNotParsed(dispatcher, "lifthelploud 0", "音量下限是 1");
        failures += expectNotParsed(dispatcher, "lifthelploud 1001", "音量上限是 1000");
        failures += expectNotParsed(dispatcher, "lifthelploud -f 0", "-f 分支同样下限 1");
        failures += expectNotParsed(dispatcher, "lifthelploud -f 1001", "-f 分支同样上限 1000");
        failures += expectExecutable(dispatcher, "lifthelploud 1");
        failures += expectExecutable(dispatcher, "lifthelploud 1000");
        failures += expectExecutable(dispatcher, "lifthelploud -f 1000");
        // 【1.15】/lifthelpspeed 已删除 ⇒ 它的所有形状都必须解析失败（见上面那一组 expectNotParsed）。
        // 【1.42】/lifthelp 的 on|off 是字面量，不存在 on to on（子命令那一层同理）
        failures += expectNotParsed(dispatcher, "lifthelp on to on", "on to on 应无此分支");
        failures += expectNotParsed(dispatcher, "lifthelp up on to on", "up on to on 应无此分支");
        failures += expectNotParsed(dispatcher, "lifthelp open -f off to off", "open -f off to off 应无此分支");
        failures += expectNotParsed(dispatcher, "lifthelp close -f off to off", "close -f off to off 应无此分支");
        // 说明：「futihelp on off」这类「已匹配到可执行节点后再多打一个词」的输入，Brigadier 会停在
        // 已匹配的节点上、不报异常（下面的 probe 对照可以看到 /futispeed 2 3、/futiloud 200 300
        // 这些**既有**指令行为完全一致），所以这里不算失败项，只在对照区打出来看。

        System.out.println();
        System.out.println("==================== 【1.15】字面量优先于字符串参数（注册顺序必须钉住） ====================");
        // /lifthelp up|down|open|close 这一层，字面量 on/off/-f 与「音频名字」参数会**同时匹配**
        // （StringArgumentType 把 on 也读成一个合法字符串），Brigadier 取的是**先注册**的那个。
        // 所以 `up on` 必须解析成「子开关」，`up default` / `up example.ogg` 必须解析成「音频素材」。
        // 谁把 liftToneBranch 里的 .then(...) 顺序调了，这里立刻红（表现会是「关不掉提示音」）。
        for (String liftTone : new String[]{"up", "down", "open", "close"}) {
            failures += expectNode(dispatcher, "lifthelp " + liftTone, liftTone);
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " on", "on");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " off", "off");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " -f on", "on");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " -f off", "off");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " default", "name");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " example.ogg", "name");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " default to none", "target");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " -f default", "name");
            failures += expectNode(dispatcher, "lifthelp " + liftTone + " -f default to none", "target");
        }
        // 顺带守住「/lifthelp 本身」的顶层：on / off 仍然是总开关，没被 up|down|open|close 抢走
        failures += expectNode(dispatcher, "lifthelp on", "on");
        failures += expectNode(dispatcher, "lifthelp off", "off");
        failures += expectNode(dispatcher, "lifthelp open", "open");
        failures += expectNode(dispatcher, "lifthelp close", "close");

        System.out.println();
        System.out.println("==================== 【1.15】/pbmmusic open|close 同样要钉住字面量优先 ====================");
        // 屏蔽门的素材分支是【1.15】加的，与直梯同一套结构、同一个坑：
        // `open on` 必须是子开关（字面量先注册），`open default` / `open default-c` 必须是素材名参数。
        // 谁把 pbmMusicItemCommand 里的 .then(...) 顺序调了，这里立刻红。
        for (String pbmTone : new String[]{"open", "close"}) {
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone, pbmTone);
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " on", "on");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " off", "off");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " -f on", "on");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " -f off", "off");
            // ★ 三段内置名都要落到 name（而不是被别的字面量接走）
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " default", "name");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " default-c", "name");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " default-m", "name");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " none", "name");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " example.ogg", "name");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " default to none", "target");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " -f default", "name");
            failures += expectNode(dispatcher, "pbmmusic " + pbmTone + " -f default to none", "target");
        }

        System.out.println();
        System.out.println("==================== 【1.15】/pbmmusic open|close 素材分支必须可执行 ====================");
        for (String pbmTone : new String[]{"open", "close"}) {
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " default");
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " default-c");
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " default-m");
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " none");
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " default to none");
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " -f default");
            failures += expectExecutable(dispatcher, "pbmmusic " + pbmTone + " -f default to none");
        }

        System.out.println();
        System.out.println("==================== 【1.53】/MBM（music in|delete + help；【1.57】train 已撤销）====================");
        // ★ 根上必须**同时**有 `MBM` 与 `mbm`：MC 的指令字面量是大小写敏感的，
        //   只注册大写时 `/mbm help` 会报「未知指令」。两条都要能执行才算过。
        failures += expectNode(dispatcher, "MBM help", "help");
        failures += expectNode(dispatcher, "mbm help", "help");
        failures += expectNode(dispatcher, "MBM music in", "in");
        failures += expectNode(dispatcher, "mbm music delete", "delete");
        failures += expectExecutable(dispatcher, "MBM");
        failures += expectExecutable(dispatcher, "mbm");
        failures += expectExecutable(dispatcher, "MBM help");
        failures += expectExecutable(dispatcher, "mbm help");
        failures += expectExecutable(dispatcher, "MBM music in");
        failures += expectExecutable(dispatcher, "MBM music delete");
        failures += expectExecutable(dispatcher, "mbm music in");
        failures += expectExecutable(dispatcher, "mbm music delete");
        dump(dispatcher, "MBM ", "MBM 的直接子节点");
        dump(dispatcher, "MBM music ", "MBM music 的直接子节点");
        // `music` 只是个分类节点，本身不该有执行器（免得「/MBM music」静默当成 help）
        failures += expectNotParsed(dispatcher, "MBM music", "music 只是分类节点，无执行器");
        // 【1.53】旧的根指令已改名 ⇒ 它必须**不存在**了
        failures += expectNotParsed(dispatcher, "dtmusic", "【1.53】旧根指令已改名成 /MBM music");
        failures += expectNotParsed(dispatcher, "dtmusic in", "【1.53】旧根指令已改名成 /MBM music in");
        failures += expectNotParsed(dispatcher, "dtmusic delete", "【1.53】旧根指令已改名成 /MBM music delete");

        // 【1.57】`/MBM train music` 已按用户点名**撤销**（列车音效界面的入口改成了
        //   「石斧右键侧线铁轨」，判据见 SmoothLift.isMtrRail）。
        //   ★★ 这里**不能**写 expectNotParsed：`MBM` 根节点自己带执行器（`/MBM` = 帮助），
        //     Brigadier 遇到未知的 `train` 会**停在 `MBM` 并把它执行掉**（不抛异常），
        //     于是「`MBM train` 应当无法执行」永远假红（本文件顶上第 35 行那条注意事项就是这个坑）。
        //   正确的断言是**子节点表**：`MBM` 的直接子节点只许 [help, music, picture]、
        //     `MBM music` 只许 [delete, in] —— 谁把空壳 `train` 加回来，这里立刻红。
        //   ★【09-28】补上 `picture`：图片方块那一族（查询 / new / <名字> / delete）是真实功能，
        //     它加进来之后这条期望一直没跟着改 ⇒ check-all 从那时起就一直红着这一条。
        //     期望表跟着代码走，别让「一直红」掩掉真正的回归。
        failures += expect(dispatcher, "MBM ", "help", "music", "picture");
        failures += expect(dispatcher, "mbm ", "help", "music", "picture");
        failures += expect(dispatcher, "MBM music ", "delete", "in");
        failures += expect(dispatcher, "mbm music ", "delete", "in");
        //   ★【09-28】`MBM picture <名字>` 那个参数带 suggests 回调（要读存档里的图片库），
        //     所以**不能**用 expect/dump（它们会真跑一次补全 ⇒ 这里的 source 是 null ⇒ NPE）。
        //     用 expectNode（只 parse、不跑补全）守子节点就够了。
        failures += expectNode(dispatcher, "MBM picture new", "new");
        failures += expectNode(dispatcher, "MBM picture delete", "delete");
        // 【09-29】`fold` 必须是**字面量**（Brigadier 先试字面量、再试下面的 `name` 参数）：
        //   少了它，`/MBM picture fold` 会被 `name` 接走 —— 不报错，只是变成
        //   「切换到一张叫 fold 的图片」（静默干错事，正是本项目最怕的那种）。
        //   expectNode 判「最后节点名」+「整串消费完」+「无异常」，正好钉住这一条。
        failures += expectNode(dispatcher, "MBM picture fold", "fold");
        failures += expectNode(dispatcher, "mbm picture fold", "fold");
        failures += expectExecutable(dispatcher, "MBM picture fold");
        failures += expectExecutable(dispatcher, "mbm picture fold");

        System.out.println();
        System.out.println("========== 【1.53】三个港铁预设 + 全音量：预设 55 条指令必须条条可执行 ==========");
        // ★★ 这一段是「界面按钮能不能用」的**离线代理**：
        //   预设 / 全音量的做法是「把这几条指令原样派发」（见 SmoothLift.runCommandBatch），
        //   所以「按钮点了有没有效果」== 「这些指令在指令树里存不存在」。
        //   在这里逐条真解析一遍，等于把界面按钮钉在指令树上 —— 界面自己（在游戏里）没法离线测。
        //   少写一个 `-f`、把 close 的素材名写成 default 而不是 default-s / default-m、
        //   把「不播」写成 `off` 而不是 `none` … 都会在这里红。
        // ⚠️ 但「解析得通」≠「落在对的那一层」：`pbmmusic open -f off` 也能解析（落到**子开关**，
        //   不是素材），所以那件事由 check-mbm-help.py 的「层判据」守着，不在这一层。
        // ⚠️⚠️ **这三份清单和 SmoothLift 里的 PRESET_* 数组是两处**（这里不能直接读 private 常量）
        //   ⇒ 改预设时**两边都要改**，否则就会出现「指令树测的是老清单」的假绿。
        //   「同一份规则出现在两处就是等着分叉」—— 这里是已知的重复点，改预设时先看这里。
        //   ★【09-28 续 4】三档的讲述人条目：经典＝hongkong（开），简单 / 空白＝off（关）。
        //     条数 19 + 19 + 17 = 55（★【10-05 用户点名】三个预设各多 6 条范围；两条无障碍提示音 5 5）。
        //   ★★★【09-29 LOG6】静音一律写**子开关层**的 `-f off`，**不许**写素材层的 `-f none` ——
        //     `none` 落到素材层后，UI「开关」行与 `… <项> on` 都够不到它，用户会觉得
        //     「选了简单港铁预设就无论怎么设置都开不了」（LOG6）。
        //     ⇒ 上一版这里写 `none`（v1 判据也照着错的写）正是那个 bug 的形态，已回退。
        //     见 SmoothLift.PRESET_* 的注释与 check-mbm-help.py 的「层判据 v2」
        //     （那一节对**两个域**同时生效）。
        String[][] mbmPresets = {
                {"经典港铁预设",
                        "futimusic -f default", "futihelp -f on", "lifthelp -f on",
                        "lifthelp open -f on", "lifthelp close -f on",
                        "lifthelp up -f on", "lifthelp down -f on",
                        "pbmclosewait -f 1", "pbmmusic open -f default",
                        "pbmmusic close -f default-m",
                        // ★【10-05 用户点名】范围六条（底噪/门/报站 10 5；两条无障碍提示音 5 5）
                        "futiround -f 10 5", "futihelpround -f 5 5", "lifthelpround -f 5 5",
                        "pbmround -f 10 5", "pbmmidiumround -f 10 5", "pbmarriveround -f 10 5",
                        "pbmmusic open -f on", "pbmmusic close -f on",
                        // 【09-28 续 2】进站广播（讲述人）＝开启(香港)
                        "pbmnarrate hongkong -f"},
                {"简单港铁预设",
                        "futimusic -f default", "futihelp -f off", "lifthelp -f on",
                        // 【09-29 LOG6】`off` = 关**子开关**（静音的正解；UI「开关」/ `open on` 都能开回来）
                        "lifthelp open -f off", "lifthelp close -f off",
                        "lifthelp up -f on", "lifthelp down -f on",
                        "pbmclosewait -f 1", "pbmmusic open -f default",
                        "pbmmusic close -f default-s",
                        // ★【10-05 用户点名】范围六条（底噪/门/报站 10 5；两条无障碍提示音 5 5）
                        "futiround -f 10 5", "futihelpround -f 5 5", "lifthelpround -f 5 5",
                        "pbmround -f 10 5", "pbmmidiumround -f 10 5", "pbmarriveround -f 10 5",
                        "pbmmusic open -f on", "pbmmusic close -f on",
                        // 【09-28 续 4】进站广播（讲述人）＝**关闭**（用户点名：简单港铁要关讲述人）
                        "pbmnarrate off -f"},
                {"空白预设",
                        "futimusic -f off", "futihelp -f off", "lifthelp -f off",
                        // 【09-29 LOG6】「不播」一律写**子开关**层的 `-f off`（素材层 `none` 够不到）
                        "lifthelp open -f off", "lifthelp close -f off",
                        "lifthelp up -f off", "lifthelp down -f off",
                        "pbmclosewait -f 1", "pbmmusic open -f off", "pbmmusic close -f off",
                        // ★【10-05 用户点名】范围六条（空白预设也照设；两条无障碍提示音 5 5）
                        "futiround -f 10 5", "futihelpround -f 5 5", "lifthelpround -f 5 5",
                        "pbmround -f 10 5", "pbmmidiumround -f 10 5", "pbmarriveround -f 10 5",
                        // 【09-28 续 4】进站广播（讲述人）＝**关闭**（用户点名：空白预设也要关）
                        "pbmnarrate off -f"},
        };
        for (String[] preset : mbmPresets) {
            System.out.println("  ---- " + preset[0] + "（" + (preset.length - 1) + " 条）----");
            for (int i = 1; i < preset.length; i++) {
                failures += expectExecutable(dispatcher, preset[i]);
            }
        }
        System.out.println("  ---- 全音量（12 条）----");
        String[] mbmVolumeCommands = {
                "futiloud -f 300", "futihelploud -f 300",
                "lifthelploud -f 300", "lifthelploud -f up 300",
                "lifthelploud -f down 300", "lifthelploud -f open 300",
                "lifthelploud -f close 300",
                "pbmloud -f 300", "pbmloud -f open 300", "pbmloud -f close 300",
                "pbmmidiumloud -f 300", "pbmarriveloud -f 300",
        };
        for (String cmd : mbmVolumeCommands) {
            failures += expectExecutable(dispatcher, cmd);
        }
        // 全音量的边界：1 与 1000 合法，0 与 1001 必须被拒（预设那个框自己也夹取，两头都要有）
        failures += expectExecutable(dispatcher, "futiloud -f 1");
        failures += expectExecutable(dispatcher, "futiloud -f 1000");
        failures += expectNotParsed(dispatcher, "futiloud -f 0", "音量下界 1");
        failures += expectNotParsed(dispatcher, "futiloud -f 1001", "音量上界 1000");
        failures += expectNotParsed(dispatcher, "pbmloud -f open 1001", "全音量里单项分支同样上界 1000");

        System.out.println();
        System.out.println("==================== 【09-30】/zhaji（闸机进/出站提示音）====================");
        // 指令形状：只有 in / out 两个方向 + <名字> / <X> to <Y> + -f（**没有** on/off 开关分支 ——
        // 闸机没有子开关层，「不播」就是选 off 那个素材名）。
        failures += expect(dispatcher, "zhaji ", "-f", "in", "out");
        failures += expectNode(dispatcher, "zhaji in", "in");
        failures += expectNode(dispatcher, "zhaji out", "out");
        failures += expectExecutable(dispatcher, "zhaji");
        failures += expectExecutable(dispatcher, "zhaji in");
        failures += expectExecutable(dispatcher, "zhaji out");
        failures += expectExecutable(dispatcher, "zhaji in default");
        failures += expectExecutable(dispatcher, "zhaji out none");
        failures += expectExecutable(dispatcher, "zhaji in off to default");
        failures += expectExecutable(dispatcher, "zhaji -f in default");
        failures += expectExecutable(dispatcher, "zhaji -f out default to none");
        // ★ 一层上没有 on / off 字面量（有的话就是「闸机多了子开关」这个设计错误）。
        //   ⚠️ 不能写成 expectNotParsed：`in` / `out` 这一层带一个**字符串参数** name，
        //   Brigadier 会把 on / off 当名字吃下去（这是既有的「<名字> 什么都收」语义，
        //   真正的报错发生在 resolveZhajiToneName 里）。所以这里改判「落到了 name 节点」——
        //   若哪天有人真加了个 on 字面量，字面量优先，这条会立刻红。
        failures += expectNode(dispatcher, "zhaji in on", "name");
        failures += expectNode(dispatcher, "zhaji out off", "name");
        failures += expectNode(dispatcher, "zhaji in default", "name");
        // /zhajiloud：in/out + <音量> + <X> to <Y> + -f，音量区间复用 1~1000
        failures += expect(dispatcher, "zhajiloud ", "-f", "in", "out");
        failures += expectExecutable(dispatcher, "zhajiloud");
        failures += expectExecutable(dispatcher, "zhajiloud in");
        failures += expectExecutable(dispatcher, "zhajiloud out");
        failures += expectExecutable(dispatcher, "zhajiloud in 200");
        failures += expectExecutable(dispatcher, "zhajiloud out 100 to 300");
        failures += expectExecutable(dispatcher, "zhajiloud -f in 200");
        failures += expectExecutable(dispatcher, "zhajiloud -f out 100 to 1000");
        failures += expectExecutable(dispatcher, "zhajiloud in 1");
        failures += expectExecutable(dispatcher, "zhajiloud in 1000");
        failures += expectNotParsed(dispatcher, "zhajiloud in 0", "闸机音量下界 1（复用 volumeArg）");
        failures += expectNotParsed(dispatcher, "zhajiloud in 1001", "闸机音量上界 1000（复用 volumeArg）");

        System.out.println();
        System.out.println("==================== 尾部多余输入的对照（对照既有指令，判断是不是 Brigadier 固有行为） ====================");
        probe(dispatcher, "futihelp on off");
        probe(dispatcher, "futispeed 2 3");
        probe(dispatcher, "jietispeed 2 3");
        probe(dispatcher, "futiloud 200 300");
        probe(dispatcher, "futihelploud 200 300");
        probe(dispatcher, "futihelp bogus");
        // 【1.31】「根节点自带执行器」的指令，后面多打一个词都会停在根节点、不报异常
        // （和不带参数执行显示当前速率是同一个语义），所以这两条只能放对照区、不能算失败。
        probe(dispatcher, "futihelpspeed 5");
        probe(dispatcher, "futihelpspeed bogus");
        // 【1.39】/futihelpmusic 名字也是字符串参数：随便一个词都会被当成名字，报错发生在
        // resolveHelpAudioName 里（"存档里没有叫…的音频"），不是解析期 —— 所以同样只能放对照区。
        probe(dispatcher, "futihelpmusic bogus");
        probe(dispatcher, "futihelpmusic -f");
        probe(dispatcher, "futihelpmusic in");
        probe(dispatcher, "futihelpmusic in default to");
        // 【1.41】忘了写 in|out 的旧写法（1.39 的 /futihelpmusic <名字>）：会停在根节点
        //（= 执行「不带参数就显示当前值」），**不报异常** —— 与 /futihelpspeed 5 同一个
        // Brigadier 行为（根节点自带执行器），所以只能放对照区。
        probe(dispatcher, "futihelpmusic default");
        probe(dispatcher, "futihelpmusic default to off");
        // 【1.42/1.43】直梯那几条同样是「根节点自带执行器」（不带参数 = 显示当前值），
        // 所以多打一个词只会停在根节点、不报异常 —— 只能放对照区。
        probe(dispatcher, "lifthelp bogus");
        probe(dispatcher, "lifthelploud 200 300");
        // 【1.15】子命令那一层也是「自带执行器」（/lifthelp up = 显示当前子开关 + 默认素材），
        // 所以 /lifthelp up bogus 会停在 up 节点；而「名字」本身是字符串参数，
        // /lifthelp up on 这类会被字面量接走（见上面那一组 expectNode）。
        probe(dispatcher, "lifthelp up bogus");
        probe(dispatcher, "lifthelp up on to");
        probe(dispatcher, "lifthelp up -f");
        probe(dispatcher, "lifthelp up default to");

        System.out.println();
        if (failures == 0) {
            System.out.println("结果：全部通过 ✅");
        } else {
            System.out.println("结果：有 " + failures + " 项不符 ❌");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 打印一次解析的最后节点 / 是否有执行器 / 异常表，用来和既有指令对照行为。 */
    private static void probe(CommandDispatcher<CommandSourceStack> dispatcher, String input) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        var nodes = parsed.getContext().getNodes();
        String last = nodes.isEmpty() ? "(空)" : nodes.get(nodes.size() - 1).getNode().getName();
        boolean hasExecutor = nodes.isEmpty() || nodes.get(nodes.size() - 1).getNode().getCommand() != null;
        System.out.println("  「" + input + "」 最后节点=" + last + " 有执行器=" + hasExecutor
                + " 已消费到=" + parsed.getReader().getCursor() + "/" + input.length()
                + " 异常=" + parsed.getExceptions().keySet());
    }

    private static int dump(CommandDispatcher<CommandSourceStack> dispatcher, String input, String what) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        List<String> names = completionNames(dispatcher, parsed);
        System.out.println(pad(what) + " 「" + input + "」 -> " + names);
        return 0;
    }

    private static int expect(CommandDispatcher<CommandSourceStack> dispatcher, String input, String... expected) {
        List<String> actual = completionNames(dispatcher, dispatcher.parse(input, null));
        List<String> want = new ArrayList<>();
        for (String e : expected) {
            if (!e.isEmpty()) {
                want.add(e);
            }
        }
        boolean ok = actual.equals(want);
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 期望 " + want + " 实际 " + actual);
        return ok ? 0 : 1;
    }

    /**
     * 【1.41】指令形状对齐校验：把 {@code base}（/futihelpmusic）与 {@code otherBase}（/futihelpspeed）
     * 在**同一组层级**下的 Tab 补全结果逐条对比，必须完全一致。
     *
     * <p>需求是「/futihelpmusic 的 in|out 细节与 /futihelpspeed 对齐（含 -f）」，这条断言把
     * 「顶层 = -f / in / out」「in|out 下各带 to」这些形状钉死 —— 以后谁把 in/out 挪出 -f 之外、
     * 或漏掉某一层的 to，这里立刻会红。
     *
     * <p>两个指令的参数类型不同（速率是整数、名字是字符串），但**都不给补全项**，所以补全结果恰好可比：
     * 能补出来的只有字面量（in / out / to / -f）。左边用 {@code a} 当名字占位、右边用 {@code 5}
     * 当速率占位（必须都是各自合法的值，否则那一层会解析失败、补全为空，比出来就是假红）。
     */
    private static int expectSameShape(CommandDispatcher<CommandSourceStack> dispatcher,
                                       String base, String otherBase) {
        return compareShape(dispatcher, base, otherBase, new String[][]{
                {"", ""},
                {"in ", "in "},
                {"in a ", "in 5 "},
                {"in a to ", "in 5 to "},
                {"out ", "out "},
                {"out a ", "out 1 "},
                {"out a to ", "out 1 to "},
                {"-f ", "-f "},
                {"-f in ", "-f in "},
                {"-f in a ", "-f in 5 "},
                {"-f in a to ", "-f in 5 to "},
                {"-f out ", "-f out "},
                {"-f out a ", "-f out 1 "},
                {"-f out a to ", "-f out 1 to "},
        });
    }

    /** 逐层比较两条指令的补全结果（{@code layers} 的每项 = {base 的输入后缀, otherBase 的输入后缀}）。 */
    private static int compareShape(CommandDispatcher<CommandSourceStack> dispatcher,
                                    String base, String otherBase, String[][] layers) {
        int bad = 0;
        for (String[] layer : layers) {
            List<String> a = completionNames(dispatcher, dispatcher.parse(base + " " + layer[0], null));
            List<String> b = completionNames(dispatcher, dispatcher.parse(otherBase + " " + layer[1], null));
            boolean ok = a.equals(b);
            System.out.println((ok ? "  OK   " : "  FAIL ") + "形状「"
                    + (layer[0].isEmpty() ? "(顶层)" : layer[0]) + "」 " + base + "=" + a
                    + " / " + otherBase + "=" + b);
            if (!ok) {
                bad++;
            }
        }
        return bad;
    }

    /**
     * 【1.15】解析 {@code input} 后，**最后一个节点的名字**必须是 {@code expected}。
     *
     * <p>用途：`/lifthelp up|down|open|close` 那一层上，字面量 {@code on} / {@code off} / {@code -f}
     * 与「音频名字」字符串参数会**同时匹配**（{@code StringArgumentType} 会把 on 读成合法字符串），
     * Brigadier 取的是**先注册**的那个子节点 ⇒ 「字面量写在参数前面」这条约定必须被机器钉住。
     * 只看「能不能执行」是抓不到这个回归的（两种情况都能执行，只是执行到了另一支）。
     *
     * <p>同时要求**输入被完整消费**（没有剩字符），否则 {@code lifthelp up on} 被某个更短的
     * 节点接走时也会「看起来对」。
     */
    private static int expectNode(CommandDispatcher<CommandSourceStack> dispatcher, String input, String expected) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        var nodes = parsed.getContext().getNodes();
        String actual = nodes.isEmpty() ? "(空)" : nodes.get(nodes.size() - 1).getNode().getName();
        boolean ok = expected.equals(actual) && !parsed.getReader().canRead() && parsed.getExceptions().isEmpty();
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 最后节点应为 " + expected
                + " 实际 " + actual + "（未消费到 " + parsed.getReader().getCursor() + "/" + input.length()
                + "，异常=" + parsed.getExceptions().keySet() + "）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.15】真调一次 {@link EscalatorSpeedManager#resolvePsdToneName} —— 见调用点那段注释：
     * 字符串参数在解析期不报错，只有这个方法决定「这个名字收不收、收成哪个 id」。
     *
     * <p>传 {@code null} 当 level 是**故意**的：内置名 / none / mute 都在 {@code getServerData}
     * 之前短路返回，所以不会 NPE；这也顺带证明了「这些名字不依赖存档状态」。
     * 非内置名（要查音频库）不在这里测 —— 那种情形确实需要 level。
     *
     * @param expectId  期望的 id；传 {@code null} = 期望「被拒」（带 error 文案）
     * @param expectOff 期望的「这一项不播」标记
     */
    private static int expectPsdToneName(String name, String expectId, boolean expectOff) {
        EscalatorSpeedManager.AudioArg arg;
        try {
            arg = EscalatorSpeedManager.resolvePsdToneName(null, EscalatorSpeedManager.CAT_PSD_OPEN, name);
        } catch (RuntimeException e) {
            System.out.println("  FAIL 「" + name + "」解析时抛异常（内置名本该在查音频库之前就返回）：" + e);
            return 1;
        }
        boolean ok;
        if (expectId == null) {
            ok = arg.id() == null && arg.error() != null;
        } else {
            ok = expectId.equals(arg.id()) && arg.off() == expectOff && arg.error() == null;
        }
        System.out.println((ok ? "  OK   " : "  FAIL ") + "resolvePsdToneName(null, CAT_PSD_OPEN, \"" + name
                + "\") → id=" + arg.id() + " off=" + arg.off() + " error=" + arg.error()
                + "（期望 id=" + expectId + (expectId == null ? " 且带错误文案" : " off=" + expectOff) + "）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.16】真调一次 {@link EscalatorSpeedData#clampPsdCloseWaitSeconds(int)}，把边界钉住。
     *
     * <p>★ 为什么不能只 grep 常量名：指令参数类型能挡 `pbmclosewait 61`，但**挡不住**
     * 「石斧 UI 输入框」与「存档里被外部改坏的值」这两条路 —— 它们只经过这个夹取。
     * 「常量加了、某个调用点漏了」是本项目的头号错误形态，只有真调一次才拦得住。
     */
    private static int expectClampCloseWait(int input, int expected) {
        int actual = EscalatorSpeedData.clampPsdCloseWaitSeconds(input);
        boolean ok = actual == expected;
        System.out.println((ok ? "  OK   " : "  FAIL ") + "clampPsdCloseWaitSeconds(" + input
                + ") = " + actual + "（期望 " + expected + "）");
        return ok ? 0 : 1;
    }

    /** 【1.16】默认值必须是用户点名的 **5 秒**（「第一次加入 mod 时默认为 5 秒」）。 */
    private static int expectCloseWaitDefault() {
        int actual = EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;
        boolean ok = actual == 5;
        System.out.println((ok ? "  OK   " : "  FAIL ")
                + "DEFAULT_PSD_CLOSE_WAIT_SECONDS = " + actual + "（期望 5）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.16】上下限本身：下界必须是 **0**（允许「不等」），上界必须 &gt; 0。
     *
     * <p>★ 下界这一条值得单独钉：若有人「顺手」把它也写成 1（跟 /pbmround 一致），
     * 用户就没法把等待调成 0（= 人声紧跟开门音），而这类「只能选比预期更大的值」的
     * 症状在我们这儿表现为「填了 0 没用」，不会有任何报错。
     */
    private static int expectCloseWaitBounds() {
        int lo = EscalatorSpeedData.PSD_CLOSE_WAIT_MIN;
        int hi = EscalatorSpeedData.PSD_CLOSE_WAIT_MAX;
        boolean ok = lo == 0 && hi > lo;
        System.out.println((ok ? "  OK   " : "  FAIL ") + "PSD_CLOSE_WAIT_MIN/MAX = " + lo + "/" + hi
                + "（期望下界 0、上界大于下界）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.17】真调一次 {@link EscalatorSpeedData#clampPsdMidiumWaitSeconds(int)}。
     *
     * <p>★ 与 {@link #expectClampCloseWait} 的差别正是本次需求：**没有上界**。
     * 所以这里不仅要测「负值折成 0」，还要测「大值原样通过」—— 若谁把上界顺手补成 60
     * （照抄 closeWait 那条），用户就会撞上「填 3600 没用」，而且不会有任何报错。
     */
    private static int expectClampMidiumWait(int input, int expected) {
        int actual = EscalatorSpeedData.clampPsdMidiumWaitSeconds(input);
        boolean ok = actual == expected;
        System.out.println((ok ? "  OK   " : "  FAIL ") + "clampPsdMidiumWaitSeconds(" + input
                + ") = " + actual + "（期望 " + expected + "）");
        return ok ? 0 : 1;
    }

    /** 【1.17】到站播报等待时长的边界：下界必须是 **0**，且**不存在上界常量**（需求 = [0,+∞)）。 */
    private static int expectMidiumWaitBounds() {
        int lo = EscalatorSpeedData.PSD_MIDIUM_WAIT_MIN;
        boolean ok = lo == 0;
        System.out.println((ok ? "  OK   " : "  FAIL ") + "PSD_MIDIUM_WAIT_MIN = " + lo
                + "（期望 0；上界故意不设 = 用户点名的 [0,+∞)）");
        return ok ? 0 : 1;
    }

    /** 【1.17】默认等待秒数 = 0（开门音一播完就播报）。 */
    private static int expectMidiumWaitDefault() {
        int actual = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;
        boolean ok = actual == 0;
        System.out.println((ok ? "  OK   " : "  FAIL ")
                + "DEFAULT_PSD_MIDIUM_WAIT_SECONDS = " + actual + "（期望 0）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.17】真调一次 {@link EscalatorSpeedData#normalizePsdMidiumAudio(String)}。
     *
     * <p>★ 为什么值得真调：到站播报**没有内置素材**，所以「空值」只能是「不播」。
     * 若有人照抄提示音那套（空值 → default / 内置），「没导入过素材」的玩家会去播一条
     * 不存在的音频 —— 症状是「开了没声、也不报错」。这个函数是唯一的分诊点。
     */
    private static int expectMidiumNormalize(String input, String expected) {
        String actual = EscalatorSpeedData.normalizePsdMidiumAudio(input);
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "  OK   " : "  FAIL ") + "normalizePsdMidiumAudio("
                + (input == null ? "null" : "\"" + input + "\"") + ") = \"" + actual
                + "\"（期望 \"" + expected + "\"）");
        return ok ? 0 : 1;
    }

    /** 【1.17】真调一次 {@link EscalatorSpeedManager#resolvePsdMidiumName}（只测前置短路的 off 系）。 */
    private static int expectPsdMidiumName(String name, String expectId) {
        String actual;
        try {
            actual = EscalatorSpeedManager.resolvePsdMidiumName(null, EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        } catch (RuntimeException e) {
            System.out.println("  FAIL 「" + name + "」解析时抛异常（off 系本该在查音频库之前就返回）：" + e);
            return 1;
        }
        boolean ok = expectId.equals(actual);
        System.out.println((ok ? "  OK   " : "  FAIL ") + "resolvePsdMidiumName(null, CAT_PSD_MIDIUM, \"" + name
                + "\") = " + (actual == null ? "null" : "\"" + actual + "\"")
                + "（期望「" + expectId + "」）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.21】真调一次 {@link EscalatorSpeedData#clampPsdArriveSeconds(int)}。
     *
     * <p>★ 它与 {@link #expectClampMidiumWait} 是**镜像**：那一个只有下界（[0,+∞)），
     * 这一个只有上界（(-∞,0]）。所以这里专门测「很小的负值原样通过」——
     * 谁把下界顺手补上（照抄 midium 那条），用户就会撞上「填 -3600 没用」，而且不会有任何报错。
     */
    private static int expectClampArrive(int input, int expected) {
        int actual = EscalatorSpeedData.clampPsdArriveSeconds(input);
        boolean ok = actual == expected;
        System.out.println((ok ? "  OK   " : "  FAIL ") + "clampPsdArriveSeconds(" + input
                + ") = " + actual + "（期望 " + expected + "）");
        return ok ? 0 : 1;
    }

    /** 【1.21】进站报站提前量的边界：上界必须是 **0**（需求 = (-∞, 0]）。 */
    private static int expectArriveBounds() {
        int hi = EscalatorSpeedData.PSD_ARRIVE_SECONDS_MAX;
        boolean ok = hi == 0;
        System.out.println((ok ? "  OK   " : "  FAIL ") + "PSD_ARRIVE_SECONDS_MAX = " + hi
                + "（期望 0；下界故意不设 = 用户点名的 (-∞, 0]）");
        return ok ? 0 : 1;
    }

    /** 【1.21】默认提前秒数 = 0（与开门音同一刻）。 */
    private static int expectArriveDefault() {
        int actual = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS;
        boolean ok = actual == 0;
        System.out.println((ok ? "  OK   " : "  FAIL ")
                + "DEFAULT_PSD_ARRIVE_SECONDS = " + actual + "（期望 0）");
        return ok ? 0 : 1;
    }

    /**
     * 【1.21】真调一次 {@link EscalatorSpeedData#normalizePsdArriveAudio(String)}。
     *
     * <p>★ 与到站播报同一条教训：**没有内置素材** ⇒ 空值只能是「不播」。
     * 落回 default 的症状是「开了没声、也不报错」。
     */
    private static int expectArriveNormalize(String input, String expected) {
        String actual = EscalatorSpeedData.normalizePsdArriveAudio(input);
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "  OK   " : "  FAIL ") + "normalizePsdArriveAudio("
                + (input == null ? "null" : "[" + input + "]") + ") = [" + actual
                + "]（期望 [" + expected + "]）");
        return ok ? 0 : 1;
    }

    /** 【1.21】真调一次 {@link EscalatorSpeedManager#resolvePsdArriveName}（只测前置短路的 off 系）。 */
    private static int expectPsdArriveName(String name, String expectId) {
        String actual;
        try {
            actual = EscalatorSpeedManager.resolvePsdArriveName(null, EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        } catch (RuntimeException e) {
            System.out.println("  FAIL [" + name + "] 解析时抛异常（off 系本该在查音频库之前就返回）：" + e);
            return 1;
        }
        boolean ok = expectId.equals(actual);
        System.out.println((ok ? "  OK   " : "  FAIL ") + "resolvePsdArriveName(null, CAT_PSD_ARRIVE, [" + name
                + "]) = " + (actual == null ? "null" : "[" + actual + "]")
                + "（期望 [" + expectId + "]）");
        return ok ? 0 : 1;
    }

    private static int expectExecutable(CommandDispatcher<CommandSourceStack> dispatcher, String input) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        boolean ok = !parsed.getContext().getNodes().isEmpty()
                && parsed.getContext().getNodes().get(parsed.getContext().getNodes().size() - 1).getNode().getCommand() != null
                && parsed.getExceptions().isEmpty();
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 可执行 = " + ok
                + (parsed.getExceptions().isEmpty() ? "" : " 异常=" + parsed.getExceptions().keySet()));
        return ok ? 0 : 1;
    }

    private static int expectNotParsed(CommandDispatcher<CommandSourceStack> dispatcher, String input, String why) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        // 【1.15】「整条指令不存在」时节点表是空的（Brigadier 对未匹配的根子节点直接不产生候选），
        // 这时显然也算「无法执行」—— 先判空，别去 get(size - 1) 抛 IndexOutOfBounds。
        boolean ok = !parsed.getExceptions().isEmpty()
                || parsed.getContext().getNodes().isEmpty()
                || parsed.getContext().getNodes().get(parsed.getContext().getNodes().size() - 1).getNode().getCommand() == null;
        if (!ok) {
            System.out.println("       调试：exceptions=" + parsed.getExceptions()
                    + " readerCursor=" + parsed.getReader().getCursor()
                    + " totalLen=" + input.length());
        }
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 应当无法执行（" + why + "）");
        return ok ? 0 : 1;
    }

    /**
     * 【09-28 续 2】断言「这条输入**读不完**」（＝多余 token 不被收下 ⇒ 这行指令不成立）。
     *
     * <p>★★ 为什么不能直接用 {@link #expectNotParsed}：那个判据是
     * 「有 exception ∨ 节点表为空 ∨ 末节点没有命令」。但当**节点本身带 executes** 时
     * （{@code /pbmnarrate} 就是），Brigadier 解析到那个节点就停住，
     * **既不抛异常、也不往 {@code getExceptions()} 里放东西**，只是把 reader 停在半路 ——
     * 于是 {@code expectNotParsed} 会误判成「可执行」。
     * 这种「字面量枚举之外的 token」只能看 {@code reader.canRead()}：
     * 读不完就说明这一行在游戏里是**不完整/无法执行**的。
     * （实测：{@code pbmnarrate on} → {@code exceptions={} readerCursor=11/13}。）
     */
    private static int expectTrailingUnconsumed(CommandDispatcher<CommandSourceStack> dispatcher,
                                                String input, String why) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        boolean ok = parsed.getReader().canRead();
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 应当读不完"
                + "（多余 token 不被收下）（" + why + "）"
                + "  readerCursor=" + parsed.getReader().getCursor() + "/" + input.length());
        return ok ? 0 : 1;
    }

    private static List<String> completionNames(CommandDispatcher<CommandSourceStack> dispatcher,
                                                ParseResults<CommandSourceStack> parsed) {
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parsed).join();
        List<String> out = new ArrayList<>();
        for (Suggestion s : suggestions.getList()) {
            out.add(s.getText());
        }
        StringRange range = suggestions.getRange();
        // 递归展开「子节点文面完全相同」时 Brigadier 会用公共前缀折叠补全项，这里只关心名字，不处理。
        return out;
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 26) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
