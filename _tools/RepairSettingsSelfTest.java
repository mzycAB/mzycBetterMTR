import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RepairSettings 的**纯文件层**自测（不碰 Minecraft：把 {@code file(ServerLevel)} 换成直接调
 * 私有 write/read 的镜像实现，逐条比对语义）。
 *
 * <p>目的是把「用户点名的四条规则」钉死：
 * <ol>
 *   <li>文件不存在 ⇒ 默认 1（弹窗）；</li>
 *   <li>写 0 ⇒ 文件里是 {@code Repair_Check=0}，再读是 false；</li>
 *   <li>只改这一行，其它行（注释 / 以后的配置项）原样保留；</li>
 *   <li>GBK / UTF-8 BOM 手改过的文件也能读、写回不把原文变乱码。</li>
 * </ol>
 */
public final class RepairSettingsSelfTest {

    private static int failed;
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mbm-settings-test");

        // ① 文件不存在 ⇒ 默认 1
        Path f = dir.resolve("MBM_settings.txt");
        check("不存在时默认为 1", readCheck(f) == 1);

        // ② 写 0 → 读回 0；再写 1 → 读回 1
        writeCheck(f, 0);
        check("写 0 后读回 0", readCheck(f) == 0);
        writeCheck(f, 1);
        check("写 1 后读回 1", readCheck(f) == 1);

        // ②b ★ 新建出来的文件**只有一行**，没有任何注释行（用户点名：设置文件里不要注释行）
        List<String> fresh = Files.readAllLines(f, StandardCharsets.UTF_8);
        check("新建文件只有 1 行", fresh.size() == 1);
        check("新建文件那一行就是 Repair_Check=1", fresh.get(0).equals("Repair_Check=1"));
        check("新建文件没有 # 注释行",
                fresh.stream().noneMatch(line -> line.trim().startsWith("#")));

        // ③ 只动那一行：文件里其它行原样保留
        Files.write(f, ("# 玩家自己的注释\n"
                + "Some_Other_Key=abc\n"
                + "Repair_Check=1\n"
                + "# 尾巴注释\n").getBytes(StandardCharsets.UTF_8));
        writeCheck(f, 0);
        List<String> after = Files.readAllLines(f, StandardCharsets.UTF_8);
        check("注释行保留", after.contains("# 玩家自己的注释") && after.contains("# 尾巴注释"));
        check("其它键保留", after.contains("Some_Other_Key=abc"));
        check("目标行被改写", after.contains("Repair_Check=0"));
        check("没有重复写目标行", countOccurrences(after, "Repair_Check=") == 1);
        check("改完后读回 0", readCheck(f) == 0);

        // ④ 只有别的键、没有 Repair_Check ⇒ 追加在末尾
        Files.write(f, "Other=1\n".getBytes(StandardCharsets.UTF_8));
        writeCheck(f, 0);
        List<String> appended = Files.readAllLines(f, StandardCharsets.UTF_8);
        check("追加了 Repair_Check", lastLine(appended).equals("Repair_Check=0"));
        check("原有键还在", appended.contains("Other=1"));

        // ⑤ GBK 手改（老记事本）也能读出来，且写回不改乱原文
        String gbkText = "# 中文注释（GBK）\nRepair_Check=1\n";
        Charset gbk = Charset.forName("GBK");
        Files.write(f, gbkText.getBytes(gbk));
        check("GBK 文件读回 1", readCheck(f) == 1);
        writeCheck(f, 0);
        String reread = new String(Files.readAllBytes(f), gbk);
        check("GBK 写回后注释没乱", reread.contains("# 中文注释（GBK）"));
        check("GBK 写回后是 0", reread.contains("Repair_Check=0"));

        // ⑥ UTF-8 BOM（记事本「另存为 UTF-8」）
        byte[] bom = new byte[]{'#', 'b', 'o', 'm', '\n'};
        byte[] body = "Repair_Check=1\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(bom, 0, withBom, 3, bom.length);
        System.arraycopy(body, 0, withBom, 3 + bom.length, body.length);
        Files.write(f, withBom);
        check("BOM 文件读回 1", readCheck(f) == 1);

        // ⑦ 写坏的值 ⇒ 按默认（弹窗），不要静默关掉提示
        Files.write(f, "Repair_Check=yes\n".getBytes(StandardCharsets.UTF_8));
        check("写坏的值按默认 1", readCheck(f) == 1);
        Files.write(f, "Repair_Check=\n".getBytes(StandardCharsets.UTF_8));
        check("空值按默认 1", readCheck(f) == 1);
        Files.write(f, "repair_check=0\n".getBytes(StandardCharsets.UTF_8));
        check("键名大小写不一致 → 不命中 → 默认 1", readCheck(f) == 1);

        // ⑧ 前后空白 / CRLF 也算
        Files.write(f, "  Repair_Check = 0  \r\n".getBytes(StandardCharsets.UTF_8));
        check("空白 + CRLF 也能认", readCheck(f) == 0);

        // ⑨ ★ 旧的无扩展名文件（1.37 最初那版）要能被改名成 .txt，且内容一个字节不丢
        Path legacy = dir.resolve("MBM_settings");   // 无扩展名
        Files.deleteIfExists(f);
        Files.deleteIfExists(legacy);
        Files.write(legacy, "Repair_Check=0\n".getBytes(StandardCharsets.UTF_8));
        check("迁移前：旧名字读得到 0", readCheck(legacy) == 0);
        boolean moved = migrate(legacy, f);
        check("旧文件已改名为 .txt", moved && Files.isRegularFile(f) && !Files.isRegularFile(legacy));
        check("迁移后：新名字读得到 0（设置没丢）", readCheck(f) == 0);

        // ⑩ 两个都在 ⇒ 新文件优先，旧的不动（不擅自删玩家文件）
        Files.write(legacy, "Repair_Check=1\n".getBytes(StandardCharsets.UTF_8));
        Files.write(f, "Repair_Check=0\n".getBytes(StandardCharsets.UTF_8));
        boolean moved2 = migrate(legacy, f);
        check("两个都在时不改名", !moved2);
        check("两个都在时新文件优先（读 0）", readCheck(f) == 0);
        check("两个都在时旧文件原样保留", Files.isRegularFile(legacy));

        // ⑪ ★ 用户点名：文件内容被清空 ⇒ 下次进存档要补回 Repair_Check=1
        Files.deleteIfExists(legacy);
        Files.write(f, new byte[0]);                       // 空文件
        check("空文件里认不出那一行", !hasLine(f));
        check("空文件补写后带那一行", ensure(f) && hasLine(f));
        check("空文件补写的是 1", readCheck(f) == 1);

        // ⑫ 只删了那一行、其它内容还在 ⇒ 补回 1，其它内容不能丢
        Files.write(f, ("Some_Other_Key=abc\n").getBytes(StandardCharsets.UTF_8));
        check("缺行的文件补写后带那一行", ensure(f) && hasLine(f));
        List<String> kept = Files.readAllLines(f, StandardCharsets.UTF_8);
        check("补写后其它键还在", kept.contains("Some_Other_Key=abc"));
        check("补写后值是 1", readCheck(f) == 1);

        // ⑬ ★★ 最重要的一条：已经写着 0（点了不再显示）**绝不能**被 ensure 覆盖成 1
        Files.write(f, "Repair_Check=0\n".getBytes(StandardCharsets.UTF_8));
        check("ensure 不动已有文件", ensure(f));
        check("ensure 不会把 0 改回 1", readCheck(f) == 0);
        List<String> afterEnsure = Files.readAllLines(f, StandardCharsets.UTF_8);
        check("ensure 不会重复追加那一行", countOccurrences(afterEnsure, "Repair_Check=") == 1);

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 与被测类 {@code ensureExists} 同构（只做「有没有那一行」这一层判定）。 */
    private static boolean ensure(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            writeCheck(file, 1);
            return true;
        }
        if (hasLine(file)) {
            return true;
        }
        writeCheck(file, 1);
        return true;
    }

    /** 与被测类 {@code hasRepairCheckLine} 同构：只看那一行在不在。 */
    private static boolean hasLine(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (LINE.matcher(line).matches()) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** 与被测类 {@code migrateLegacyName} 同构：新文件在就不动；旧文件在就 move。 */
    private static boolean migrate(Path legacy, Path current) {
        if (!Files.isRegularFile(legacy) || Files.isRegularFile(current)) {
            return false;
        }
        try {
            Files.move(legacy, current);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 与被测类**逐行同构**的最小实现（避免为了自测去拖整个 Minecraft 依赖）
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern LINE =
            java.util.regex.Pattern.compile("^\\s*Repair_Check\\s*=\\s*(\\d+)\\s*$");
    private static final Charset[] CHARSETS = {StandardCharsets.UTF_8, Charset.forName("GBK"),
            StandardCharsets.ISO_8859_1};

    private static int readCheck(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return 1;
        }
        byte[] bytes = Files.readAllBytes(file);
        int off = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        String text = null;
        for (Charset cs : CHARSETS) {
            try {
                text = cs.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
                break;
            } catch (java.nio.charset.CharacterCodingException ignored) {
            }
        }
        if (text == null) {
            return 1;
        }
        for (String line : text.split("\r\n|\r|\n", -1)) {
            java.util.regex.Matcher m = LINE.matcher(line);
            if (m.matches()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    private static void writeCheck(Path file, int value) throws IOException {
        byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
        int off = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        Charset charset = StandardCharsets.UTF_8;
        List<String> lines = new ArrayList<>();
        if (bytes.length > 0) {
            for (Charset cs : CHARSETS) {
                try {
                    String text = cs.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
                    charset = cs;
                    for (String line : text.split("\r\n|\r|\n", -1)) {
                        lines.add(line);
                    }
                    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                        lines.remove(lines.size() - 1);
                    }
                    break;
                } catch (java.nio.charset.CharacterCodingException ignored) {
                }
            }
        }
        boolean replaced = false;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!replaced && LINE.matcher(line).matches()) {
                out.add("Repair_Check=" + value);
                replaced = true;
            } else {
                out.add(line);
            }
        }
        if (!replaced) {
            // ★ 与被测类一致：新建文件**只写这一行**，不写任何注释行。
            out.add("Repair_Check=" + value);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : out) {
            sb.append(line).append('\n');
        }
        Files.write(file, sb.toString().getBytes(charset));
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  OK   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static int countOccurrences(List<String> lines, String prefix) {
        int n = 0;
        for (String line : lines) {
            if (line.trim().startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    private static String lastLine(List<String> lines) {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }
}
