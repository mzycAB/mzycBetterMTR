package smooth.lift;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【1.37】存档文件夹里的 {@code MBM_settings.txt} —— 目前只有一项 {@code Repair_Check}。
 *
 * <h2>一、用户点名的口径</h2>
 * <blockquote>
 *   「点击『不再显示』就将此改动写入存档文件夹里的 MBM_settings 文件
 *     （需要模组新建这个文件如果存档文件夹里面没有），这个文件内有一串代码：
 *     Repair_Check=1，为1时启动游戏之后弹窗，为0时不弹窗。
 *     点击『不再显示』以后启动游戏不会再检测是否导入补丁，其实就是把 Repair_Check 改为 0」
 * </blockquote>
 * 所以：
 * <ul>
 *   <li>文件就在**存档根目录**：{@code <存档>/MBM_settings.txt}（和 {@code MBM_Audio} /
 *       {@code MBM_Picture} / {@code MBM_Repair} 三个文件夹平级）；</li>
 *   <li>它是**纯文本**（{@code .txt}），一行一项（{@code 键=值}），玩家双击就能用记事本改；</li>
 *   <li>★ 为什么用 {@code .txt} 而不是无扩展名：用户点名「能不能改成 txt 或者其他的」。
 *       内容本来就只有一行 {@code Repair_Check=1}，纯文本已经是**最轻**的格式
 *       （比 JSON 少一次解析、比 NBT/gzip 少一次解压）；
 *       加 {@code .txt} 只是让 Windows 双击能直接用记事本打开，**不增加任何解析开销**。</li>
 *   <li>★ 文件里**不写任何注释行** —— 用户点名：「mbmsettings 里的 # … 删掉不要注释」。
 *       新建出来的文件就只有一行 {@code Repair_Check=1}；</li>
 *   <li>文件不存在 / 里面没有这一行 ⇒ 按**默认 {@code 1}** 处理（= 还弹窗）；
 *       {@code 0} ⇒ 不弹窗，而且**连「有没有导入补丁」都不查**（用户点名的短路语义）；</li>
 *   <li>写的时候**只改这一行**，文件里其它行（玩家自己加的注释 / 以后加的配置项）原样保留。</li>
 * </ul>
 *
 * <h2>二、为什么是服务端写的</h2>
 * 存档文件夹躺在**服务端**那台机器上：单人游戏里它和客户端是同一台机器，但多人服务器上
 * 客户端根本够不着。所以判定与写文件都放在服务端（见 {@link RepairNotice}），
 * 客户端只负责「弹窗」这件事本身（{@code RepairNoticeScreen}）。
 *
 * <p>★ 这个类**不依赖 Minecraft 的任何数据类型**（只用一个 {@link ServerLevel} 去问存档根路径），
 * 与 {@link RepairCrypto} 同一个理由：好移植、好单独测。
 */
public final class RepairSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 设置文件名（相对存档根目录）—— 存档文件夹里的 {@code MBM_settings.txt}。 */
    public static final String FILE_NAME = "MBM_settings.txt";

    /**
     * ★ 旧文件名（1.37 最初那一版用的、**没有扩展名**）。
     *
     * <p>用户点名「能不能改成 txt 或者其他的」⇒ 正式名改成 {@link #FILE_NAME}。
     * 但已经跑过旧版的存档里躺着的是无扩展名的那个文件，**必须**继续认它，
     * 否则玩家「上次点了不再显示」的设置会凭空失效、又弹一次窗（还会被当成回归 bug）。
     *
     * <p>处理方式（见 {@link #migrateLegacyName}）：新文件不存在而旧文件在 ⇒
     * **把旧文件改名成新名字**（一次 {@code Files.move}，原子、不丢内容）；
     * 两个都在 ⇒ 以**新文件**为准、旧的不动（不擅自删玩家文件）。
     */
    public static final String LEGACY_FILE_NAME = "MBM_settings";

    /** 那个开关的键名 —— 用户点名：{@code Repair_Check=1}。 */
    public static final String KEY_REPAIR_CHECK = "Repair_Check";

    /** {@code Repair_Check} 的默认值：{@code 1} = 启动游戏后弹窗（用户点名）。 */
    public static final int DEFAULT_REPAIR_CHECK = 1;

    /**
     * 认的那一行：{@code Repair_Check=<数字>}（前后允许空白、键名大小写**必须**一致）。
     *
     * <p>只认整数：写成 {@code Repair_Check=yes} / {@code Repair_Check=} 这种一律当「没写」，
     * 按默认 {@code 1} 处理 —— 写坏了宁可多弹一次窗，也不要静默把提示关掉。
     */
    private static final Pattern REPAIR_CHECK_LINE =
            Pattern.compile("^\\s*" + KEY_REPAIR_CHECK + "\\s*=\\s*(\\d+)\\s*$");

    /**
     * 认的编码，**按顺序**试：UTF-8 → GBK → ISO-8859-1（最后这个任何字节序列都解得开，兜底）。
     *
     * <p>★ 为什么不是直接写死 UTF-8：玩家很可能用记事本 / Notepad++ 手动改这个文件，
     * 老一点的记事本在中文 Windows 上存的是 **GBK**。硬按 UTF-8 读会抛
     * {@code MalformedInputException} ⇒ 我们就会「读到失败 → 按默认处理」，
     * 玩家明明写了 {@code Repair_Check=0} 却还弹窗，而且点「不再显示」也写不回去。
     *
     * <p>★ 写回时用**当初解开的那个编码**，这样文件里原有的注释不会被来回转码成乱码
     * （新建文件永远是 UTF-8）。
     *
     * <p>★ GBK 是可选的（裁剪过的 JRE 可能没有）：解析不到就跳过，不影响 UTF-8 / 兜底两条。
     */
    private static final java.nio.charset.Charset[] CANDIDATE_CHARSETS = buildCandidateCharsets();

    private static java.nio.charset.Charset[] buildCandidateCharsets() {
        java.nio.charset.Charset gbk = null;
        try {
            gbk = java.nio.charset.Charset.forName("GBK");
        } catch (RuntimeException ignored) {
            // 这个 JRE 没有 GBK：只留 UTF-8 + 兜底（功能不受影响，只是手改的 GBK 文件读不出来）。
        }
        if (gbk == null) {
            return new java.nio.charset.Charset[]{StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1};
        }
        return new java.nio.charset.Charset[]{StandardCharsets.UTF_8, gbk, StandardCharsets.ISO_8859_1};
    }

    private RepairSettings() {
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 设置文件的完整路径（服务端视角）；拿不到世界路径时返回 {@code null}。 */
    public static Path file(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return file(level.getServer());
    }

    /** 同 {@link #file(ServerLevel)}，但直接用服务端实例（拿不到时返回 {@code null}）。 */
    public static Path file(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
        } catch (Throwable t) {
            // 世界还没起来 / 路径不可用时不要炸：调用方按「没有这个文件」处理。
            return null;
        }
    }

    /** 旧文件（无扩展名）的完整路径；拿不到世界路径时返回 {@code null}。 */
    public static Path legacyFile(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            return server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 旧文件（无扩展名）的完整路径（{@link ServerLevel} 版）。 */
    public static Path legacyFile(ServerLevel level) {
        return level == null ? null : legacyFile(level.getServer());
    }

    /**
     * 把旧的无扩展名文件改名成 {@code MBM_settings.txt}（幂等，每次进世界都可以调）。
     *
     * <p>三种情况：
     * <ul>
     *   <li>旧文件在、新文件不在 ⇒ **改名**（{@code Files.move}，内容一个字节都不动）；</li>
     *   <li>两个都在 ⇒ 什么都不做（**新文件优先**，旧的不擅自删）；</li>
     *   <li>旧文件不在 ⇒ 什么都不做。</li>
     * </ul>
     *
     * <p>★ 为什么改名而不是「读旧的、写新的、删旧的」：改名是一次元数据操作、原子，
     * 中途崩了也不会出现「新的写了一半、旧的已经没了」这种把玩家设置弄丢的状态。
     *
     * @return 这次有没有真的改名
     */
    public static boolean migrateLegacyName(MinecraftServer server) {
        Path legacy = legacyFile(server);
        Path current = file(server);
        if (legacy == null || current == null) {
            return false;
        }
        if (!Files.isRegularFile(legacy) || Files.isRegularFile(current)) {
            // 旧的不在（没什么可搬）或新的已经在（新文件优先）⇒ 都不动。
            return false;
        }
        try {
            Files.move(legacy, current);
            LOGGER.info("[SmoothLift/Repair] 已把旧设置文件 {} 改名为 {}", LEGACY_FILE_NAME, FILE_NAME);
            return true;
        } catch (IOException | RuntimeException e) {
            // 改名失败（跨卷 / 没权限）：不吵玩家 —— 读的时候还会**再认一次旧名字**（见 readPath），
            // 所以就算搬不动，上次「不再显示」的设置也不会失效。
            LOGGER.warn("[SmoothLift/Repair] 旧设置文件改名失败（读的时候仍会认旧名字）：{}", e.toString());
            return false;
        }
    }

    /**
     * 读的时候到底读哪个文件：**新名字优先，没有就退回旧名字**。
     *
     * <p>★ 这是改名失败 / 玩家自己把文件改回无扩展名时的兜底 ——
     * 「读」这条路必须同时认两个名字，否则一次改名失败就等于把玩家的设置重置了。
     *
     * @return 实际存在的那个文件；都不存在返回 {@code null}
     */
    private static Path existingFile(MinecraftServer server) {
        Path current = file(server);
        if (current != null && Files.isRegularFile(current)) {
            return current;
        }
        Path legacy = legacyFile(server);
        if (legacy != null && Files.isRegularFile(legacy)) {
            return legacy;
        }
        return null;
    }

    /** 这个存档里有没有这个文件（没有就说明玩家一次都没点过「不再显示」）。 */
    public static boolean exists(ServerLevel level) {
        return level != null && existingFile(level.getServer()) != null;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 现在还要不要检测 / 弹补丁提示。
     *
     * <p>文件不存在、读不出来、没有这一行、值不是整数 ⇒ 一律返回 {@code true}（默认 {@code 1}）。
     * 只有明确读到 {@code 0} 才返回 {@code false}。
     *
     * @return {@code true} = 弹窗（Repair_Check=1）；{@code false} = 不弹（Repair_Check=0）
     */
    public static boolean isRepairCheckEnabled(ServerLevel level) {
        if (level == null) {
            return DEFAULT_REPAIR_CHECK != 0;
        }
        // ★ 新名字优先、旧名字兜底（见 existingFile）—— 改名失败时也不能重置玩家的设置。
        Path file = existingFile(level.getServer());
        if (file == null) {
            return DEFAULT_REPAIR_CHECK != 0;
        }
        try {
            Decoded decoded = readDecoded(file);
            for (String line : decoded.lines()) {
                Matcher matcher = REPAIR_CHECK_LINE.matcher(line);
                if (matcher.matches()) {
                    int value;
                    try {
                        value = Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException e) {
                        // 位数离谱到 int 都装不下：同样按「写坏了」处理 → 默认弹窗。
                        return DEFAULT_REPAIR_CHECK != 0;
                    }
                    return value != 0;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SmoothLift/Repair] 读 {} 失败，按默认（弹窗）处理：{}", file, e.toString());
        }
        return DEFAULT_REPAIR_CHECK != 0;
    }

    /** 一次读盘的产物：按行切开的内容 + 当初用哪个编码解开的（写回时要沿用）。 */
    private record Decoded(List<String> lines, java.nio.charset.Charset charset) {
    }

    /**
     * 读文件并按 {@link #CANDIDATE_CHARSETS} 逐个试编码。
     *
     * <p>★ 必须用**严格解码**（{@code CodingErrorAction.REPORT}）：默认的 {@code new String(bytes, cs)}
     * 遇到坏字节会静默替换成 {@code U+FFFD}，GBK 的文件就会被「成功」解成乱码、
     * 于是永远读不到 {@code Repair_Check} 那一行。严格解码才会抛异常、才会轮到下一个编码。
     */
    private static Decoded readDecoded(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // UTF-8 BOM（记事本另存为 UTF-8 时会写）先剥掉，否则第一行的键名前面会多一个 U+FEFF。
        int offset = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        for (java.nio.charset.Charset charset : CANDIDATE_CHARSETS) {
            try {
                java.nio.charset.CharsetDecoder decoder = charset.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                String text = decoder.decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                        .toString();
                return new Decoded(splitLines(text), charset);
            } catch (java.nio.charset.CharacterCodingException ignored) {
                // 这个编码解不开 → 试下一个（最后那个 ISO-8859-1 永远解得开）。
            }
        }
        // 理论上到不了这里（ISO-8859-1 兜底）；真到了就按 UTF-8 宽松解码。
        return new Decoded(splitLines(new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    /** 按 {@code \r\n} / {@code \n} / {@code \r} 切行（{@link Files#readAllLines} 只认前两种）。 */
    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            out.add(line);
        }
        // 末尾换行会切出一个空串：写回时不要凭空多一行。
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 把 {@code Repair_Check} 改成 {@code 1} / {@code 0}（文件不存在就**新建**）。
     *
     * <p>★ 只动这一行：文件里其它内容原样保留（逐行读、命中那行替换、没命中就追加在末尾）。
     *
     * @return 是否真的写进去了（false = 没权限 / 磁盘错，调用方只记日志，不打扰玩家）
     */
    public static boolean setRepairCheck(ServerLevel level, boolean enabled) {
        return write(level, enabled ? "1" : "0");
    }

    /**
     * 确保设置文件**存在且里面有 {@code Repair_Check} 这一行**（缺什么补什么）。
     *
     * <p>用户点名「需要模组新建这个文件如果存档文件夹里面没有」，随后又点名
     * 「如果我不小心删除了 MBM_settings 里的所有内容，下一次进入存档会恢复吗，
     * 就是那一行 Repair_Check=1」⇒ 所以这里管**三件事**，不只是「文件在不在」：
     * <ol>
     *   <li>文件不存在 ⇒ 新建，内容就是 {@code Repair_Check=1}；</li>
     *   <li>文件存在但**被清空 / 删掉了那一行** ⇒ 把 {@code Repair_Check=1} 补回去
     *       （文件里其它内容原样保留，只追加这一行）；</li>
     *   <li>文件存在且那一行还在（无论 0 还是 1）⇒ **不动它**
     *       —— 玩家点了「不再显示」写进去的 {@code 0} 绝不能被这里覆盖成 1。</li>
     * </ol>
     *
     * @return 文件现在是否存在且带那一行（true = 已经可用）
     */
    public static boolean ensureExists(ServerLevel level) {
        if (level == null) {
            return false;
        }
        // 旧的无扩展名文件先改名（幂等）；改不动也不要紧，下面 existingFile 还会认它。
        migrateLegacyName(level.getServer());
        Path file = file(level);
        if (file == null) {
            return false;
        }
        Path existing = existingFile(level.getServer());
        if (existing == null) {
            // ① 文件根本不在：新建，写默认值 1。
            return write(level, String.valueOf(DEFAULT_REPAIR_CHECK));
        }
        // ② 文件在：看看里面还有没有那一行。
        if (hasRepairCheckLine(existing)) {
            return true;
        }
        // ③ 被清空 / 那一行被删掉了 ⇒ 补回默认值 1（其它内容照旧保留）。
        LOGGER.info("[SmoothLift/Repair] {} 里没有 {} 这一行（被清空了？）⇒ 补回默认值 {}",
                existing.getFileName(), KEY_REPAIR_CHECK, DEFAULT_REPAIR_CHECK);
        return write(level, String.valueOf(DEFAULT_REPAIR_CHECK));
    }

    /**
     * 这个文件里到底有没有 {@code Repair_Check=<数字>} 这一行（不管值是 0 还是 1）。
     *
     * <p>★ 与 {@link #isRepairCheckEnabled} 的区别：那个回答「要不要弹窗」，
     * 这个只回答「那一行在不在」。前者读不到就按默认 1，所以**不能**用它来判断
     * 「文件是不是空的」—— 会把「空的」和「写着 1」混为一谈，就永远不会补写。
     */
    private static boolean hasRepairCheckLine(Path file) {
        try {
            for (String line : readDecoded(file).lines()) {
                if (REPAIR_CHECK_LINE.matcher(line).matches()) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SmoothLift/Repair] 读 {} 失败（按「没有那一行」处理）：{}", file, e.toString());
        }
        return false;
    }

    /**
     * 写文件的实际实现：逐行读、命中 {@code Repair_Check} 就替换、没有就追加，然后整份写回。
     *
     * @param rawValue 写进文件的字面值（{@code "1"} / {@code "0"}）
     */
    private static boolean write(ServerLevel level, String rawValue) {
        Path file = file(level);
        if (file == null) {
            LOGGER.warn("[SmoothLift/Repair] 拿不到存档路径，{} 写不了（Repair_Check={}）", FILE_NAME, rawValue);
            return false;
        }
        List<String> lines = new ArrayList<>();
        boolean replaced = false;
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        try {
            // ★ 写之前先把旧的无扩展名文件改名过来（幂等）：否则会出现
            //   「旧文件还躺着 Repair_Check=0、这里又新建一个新文件」⇒ 下次读新文件又是默认 1。
            migrateLegacyName(level.getServer());
            // 读**实际存在**的那个（新名字优先、旧名字兜底）：改名失败时就把旧文件写回旧名字，
            // 内容仍然有效，不会出现「两份设置互相打架」。
            Path source = existingFile(level.getServer());
            if (source != null) {
                // ★ 沿用**当初解开它的那个编码**写回：文件里原有的中文注释才不会被转码成乱码
                //   （玩家用 GBK 记事本改过这个文件的话，这里就是 GBK）。
                Decoded decoded = readDecoded(source);
                charset = decoded.charset();
                for (String line : decoded.lines()) {
                    if (!replaced && REPAIR_CHECK_LINE.matcher(line).matches()) {
                        lines.add(KEY_REPAIR_CHECK + "=" + rawValue);
                        replaced = true;
                    } else {
                        lines.add(line);
                    }
                }
                // 改名失败的兜底：真正要写的还是**原来那个文件**（不凭空造第二份）。
                file = source;
            }
            if (!replaced) {
                // ★ 新建文件时**只写这一行**，不写任何注释（用户点名：设置文件里不要注释行）。
                lines.add(KEY_REPAIR_CHECK + "=" + rawValue);
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 整份覆盖写：文件很小（几行），不搞临时文件 + 改名那套。
            // ★ 每行后面补回 {@code \n}：玩家手改过的文件本来也是带换行结尾的。
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(line).append('\n');
            }
            Files.write(file, text.toString().getBytes(charset));
            LOGGER.info("[SmoothLift/Repair] 已写入 {}：{}={}（编码 {}）",
                    file, KEY_REPAIR_CHECK, rawValue, charset.name());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SmoothLift/Repair] 写 {} 失败：{}", file, e.toString());
            return false;
        }
        return true;
    }
}
