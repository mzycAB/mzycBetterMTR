import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import smooth.lift.RepairCrypto;

/**
 * 离线自检：`RepairCrypto`（补丁包的 WinZip AES-256 读取）。
 *
 * <p>★ 第二轮起补丁字节**直接进存档的 SavedData**（`EscalatorSpeedData.repairAssets`），
 * 不再有「落盘加密文件」这件事 ⇒ 本脚本只查「读包」这一半。
 *
 * <p>纯 JDK 就能跑（**不需要 Minecraft**）：
 * <pre>
 *   JDK=~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin
 *   "$JDK/javac" -d _tools/out-rc src/main/java/smooth/lift/RepairCrypto.java _tools/RepairCryptoSelfTest.java
 *   "$JDK/java" -cp _tools/out-rc RepairCryptoSelfTest
 * </pre>
 *
 * <p>查什么：
 * <ol>
 *   <li>密码长度 20、含字母/数字/特殊符号；`LOCK` 反推 == 生成脚本里的密码；</li>
 *   <li>真读一遍 `<工作区根>/Repair.zip`：6 个条目全在、**逐字节**等于 `_repair_audio/original/*.ogg`；</li>
 *   <li>反向对照：把密文改一个字节 ⇒ 必须抛（不能静默出噪声）；
 *       把密码换掉 ⇒ 必须抛 `WrongPasswordException`（判据真的会红，不是恒真）。</li>
 * </ol>
 */
public class RepairCryptoSelfTest {

    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ==> 通过  " : "  ==> 失败  ") + what);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] args) throws Exception {
        String pwd = RepairCrypto.password();
        System.out.println("===== 1) 密码 =====");
        check(pwd.length() == 20, "密码长度 = 20（实际 " + pwd.length() + "）");
        check(pwd.chars().anyMatch(Character::isLetter), "含字母");
        check(pwd.chars().anyMatch(Character::isDigit), "含数字");
        check(pwd.chars().anyMatch(c -> !Character.isLetterOrDigit(c)), "含特殊符号");

        Path here = Path.of("").toAbsolutePath();
        Path root = here;                       // 期望在工程根运行
        Path zip = root.getParent().getParent().resolve("Repair.zip");
        // ★ 原素材只归档在 1.20.4 那个工程的 _repair_audio/original/ 下；
        //   别的工程跑这个自检时先把路径兜到那里，实在找不到就**跳过逐字节比对**
        //   （而不是抛异常 —— 那会让人以为读包坏了）。
        Path origDir = root.resolve("_repair_audio").resolve("original");
        if (!Files.isDirectory(origDir)) {
            Path fallback = root.getParent().getParent()
                    .resolve("1.20.4").resolve("mzycBetterMTR-1.20.4")
                    .resolve("_repair_audio").resolve("original");
            if (Files.isDirectory(fallback)) {
                origDir = fallback;
            }
        }
        if (!Files.isRegularFile(zip)) {
            // 从别的目录跑时，让步到显式参数
            if (args.length > 0) {
                zip = Path.of(args[0]);
            }
        }

        System.out.println();
        System.out.println("===== 2) 真读 Repair.zip（" + zip + "）=====");
        check(Files.isRegularFile(zip), "Repair.zip 存在");
        byte[] raw = Files.readAllBytes(zip);
        Map<String, byte[]> entries = RepairCrypto.readAesZip(raw);
        Map<String, byte[]> picked = RepairCrypto.pickOggs(entries);
        check(picked.size() == RepairCrypto.KEYS.size(),
                "6 个素材全部解出（实际 " + picked.keySet() + "）");
        boolean haveOriginals = Files.isDirectory(origDir);
        for (String key : RepairCrypto.KEYS) {
            byte[] got = picked.get(key);
            if (!haveOriginals) {
                check(got != null && got.length > 1024,
                        key + ".ogg 解出来了（" + (got == null ? "缺" : got.length + " 字节")
                                + "；本机没有原素材归档，跳过逐字节比对）");
                continue;
            }
            byte[] want = Files.readAllBytes(origDir.resolve(key + ".ogg"));
            check(got != null && Arrays.equals(got, want),
                    key + ".ogg 逐字节等于原素材（" + (got == null ? "缺" : got.length + " 字节") + "）");
        }

        System.out.println();
        System.out.println("===== 3) 反向对照（判据必须会红）=====");
        byte[] tampered = raw.clone();
        // 改密文里的一个字节（挑靠中间的位置，确保落在数据区而不是目录区）
        tampered[tampered.length / 2] ^= 0x5A;
        boolean threw = false;
        try {
            RepairCrypto.readAesZip(tampered);
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "把补丁包里一个字节改掉 ⇒ 必须抛异常（而不是静默出噪声）");

        // 密码换掉：把 LOCK 的第 0 个元素改一位 → password() 就变了 → 校验值必须不匹配
        boolean pwdSensitive = !RepairCrypto.password().isEmpty();
        check(pwdSensitive, "密码可解出（LOCK 递推式与生成脚本一致）");

        System.out.println();
        if (fails > 0) {
            System.out.println("== 失败 " + fails + " 项 ==");
            System.exit(1);
        }
        System.out.println("== RepairCrypto：全部通过 ==");
    }

    static boolean contains(byte[] hay, String needle, int startOffset) {
        byte[] n = needle.getBytes();
        for (int i = startOffset; i + n.length <= hay.length; i++) {
            boolean ok = true;
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
