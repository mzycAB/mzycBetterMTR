import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import smooth.lift.RepairCrypto;

/**
 * 离线探针：**玩家自制（未加密）的补丁包**到底会被认成哪几段。
 *
 * <p>★ 为什么需要它：`RepairStore.importFromSave` 要 `ServerLevel`，开不了游戏就验不了；
 * 但它真正做的事只有两件 —— ① 用 {@link RepairCrypto#readAesZip} 读包；
 * ② 用 {@link RepairCrypto#pickOggs} 挑出认识的条目。这两件都是**纯 JDK**，
 * 所以「玩家放一个普通 zip 进去会发生什么」可以完全离线钉死。
 *
 * <p>纯 JDK 就能跑（**不需要 Minecraft**，`RepairCrypto` 刻意不依赖 MC 类型）：
 * <pre>
 *   JDK=~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin
 *   "$JDK/javac" -encoding UTF-8 -d _tools/out-rcz src/main/java/smooth/lift/RepairCrypto.java _tools/RepairPlainZipProbe.java
 *   "$JDK/java" -Dfile.encoding=UTF-8 -cp _tools/out-rcz RepairPlainZipProbe &lt;工作区根&gt;
 * </pre>
 *
 * <p>判据（都要成立才算过）：
 * <ol>
 *   <li>官方 `Repair.zip`（AES-256 / 密码在 jar 内）⇒ 6 个键；</li>
 *   <li>无密码 zip 只放 `doorclose.ogg` ⇒ **只有** doorclose（其余 5 个留空 ⇒ 回模组默认）；</li>
 *   <li>无密码 zip 放 `doorclose.ogg` + `liftmusic.ogg` ⇒ 正好这两段；</li>
 *   <li>无密码 zip 里名字全不对（`lala.ogg`）⇒ **空**（= 失败、旧补丁不动）；</li>
 *   <li>★ 条目名带文件夹前缀（`MBM_Repair/doorclose.ogg`）⇒ **不认** —— 这是**已知口径**：
 *       用户点名「里面的 ogg 文件名字也一样」，所以走的是**精确条目名**匹配。
 *       玩家把整个文件夹压进去会「放进去没反应」，这条探针把这件事写成断言，免得以后忘。</li>
 * </ol>
 */
public class RepairPlainZipProbe {

    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ==> 通过  " : "  ==> 失败  ") + what);
        if (!ok) {
            fails++;
        }
    }

    /** 读一个包并把「认出来的键」按名字排好序（`readAesZip` 抛异常 = 空表 + 记录原因）。 */
    static String keys(Path zip, StringBuilder why) {
        try {
            Map<String, byte[]> entries = RepairCrypto.readAesZip(Files.readAllBytes(zip));
            Map<String, byte[]> picked = RepairCrypto.pickOggs(entries);
            TreeSet<String> names = new TreeSet<>(picked.keySet());
            why.append("包内条目=").append(new TreeSet<>(entries.keySet()));
            return names.toString();
        } catch (Throwable t) {
            why.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            return "[]";
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of("").toAbsolutePath();
        Path tmp = Files.createTempDirectory("repair-probe");
        System.out.println("工作区根：" + root);
        System.out.println("临时目录：" + tmp);

        // ---- 造几个「玩家会做出来的」包 ----
        Path onlyClose = tmp.resolve("plain_only_doorclose.zip");
        zip(onlyClose, Map.of("doorclose.ogg", ogg(3000)));
        Path twoOggs = tmp.resolve("plain_two.zip");
        Map<String, byte[]> m2 = new LinkedHashMap<>();
        m2.put("doorclose.ogg", ogg(3000));
        m2.put("liftmusic.ogg", ogg(2000));
        zip(twoOggs, m2);
        Path wrongName = tmp.resolve("plain_wrongname.zip");
        zip(wrongName, Map.of("lala.ogg", ogg(3000)));
        Path folderPrefix = tmp.resolve("plain_folderprefix.zip");
        zip(folderPrefix, Map.of("MBM_Repair/doorclose.ogg", ogg(3000)));
        Path notOgg = tmp.resolve("plain_notogg.zip");
        zip(notOgg, Map.of("doorclose.ogg", "not an ogg".getBytes("UTF-8")));

        System.out.println();
        System.out.println("===== 1) 官方 Repair.zip（AES-256）仍然读得出来 =====");
        Path official = root.resolve("Repair.zip");
        StringBuilder why = new StringBuilder();
        String k = keys(official, why);
        check(k.equals("[doorclose, dooropen, down, liftmusic, mdoorclose, up]"),
                "官方包 6 段全认出来（实际 " + k + "；" + why + "）");

        System.out.println();
        System.out.println("===== 2) 玩家自制无密码 zip（本次新增能力）=====");
        why.setLength(0);
        k = keys(onlyClose, why);
        check(k.equals("[doorclose]"),
                "只放 doorclose.ogg ⇒ **只有** doorclose（少的那 5 段回模组默认）（实际 " + k + "）");
        why.setLength(0);
        k = keys(twoOggs, why);
        check(k.equals("[doorclose, liftmusic]"), "放两段 ⇒ 正好那两段（实际 " + k + "）");
        why.setLength(0);
        k = keys(notOgg, why);
        check(k.equals("[]"), "条目名叫对但内容不是 OggS ⇒ 不认（防住「压错文件」）（实际 " + k + "）");

        System.out.println();
        System.out.println("===== 3) 已知口径：认不出来的那两种 =====");
        why.setLength(0);
        k = keys(wrongName, why);
        check(k.equals("[]"), "名字全不对 ⇒ 空表 = 失败、旧补丁不动（实际 " + k + "）");
        why.setLength(0);
        k = keys(folderPrefix, why);
        check(k.equals("[]"),
                "★ 条目名带文件夹前缀（MBM_Repair/doorclose.ogg）⇒ **不认**"
                        + "（口径 = 精确条目名；实际 " + k + "）");

        System.out.println();
        System.out.println("===== 4) 门禁常量（三态决策的那两个哨兵值）=====");
        check(RepairCrypto.KEYS.size() == 6 && RepairCrypto.KEYS.contains("up")
                        && RepairCrypto.KEYS.contains("doorclose"),
                "RepairCrypto.KEYS = 6 段（up/down/liftmusic/dooropen/doorclose/mdoorclose）");

        System.out.println();
        if (fails > 0) {
            System.out.println("== 失败 " + fails + " 项 ==");
            System.exit(1);
        }
        System.out.println("== 玩家自制补丁包的读取口径：全部通过 ==");
    }

    // ------------------------------------------------------------------ 造 zip 的小工具（纯 JDK）

    static byte[] ogg(int bodyLen) {
        byte[] out = new byte[4 + bodyLen];
        out[0] = 'O';
        out[1] = 'g';
        out[2] = 'g';
        out[3] = 'S';
        for (int i = 0; i < bodyLen; i++) {
            out[4 + i] = (byte) (i * 31 + 7);
        }
        return out;
    }

    /** 用 `java.util.zip` 的**普通（未加密）** deflate 写一个 zip —— 就是玩家自己压出来的那种。 */
    static void zip(Path path, Map<String, byte[]> entries) throws Exception {
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }
}
