package smooth.lift;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * 补丁包（`Repair.zip`）的**纯密码学 + 序列化**层 —— **刻意不依赖 Minecraft 的任何类型**。
 *
 * <p>★ 为什么要单独拆一个类而且**放在公共源集**（{@code src/main/java}）：这一层是
 * 「错了不会崩、只会静默导入失败」的那种代码（zip 偏移算错、CTR 计数器大小端搞反、
 * PBKDF2 轮数写错…全都会静默解出一堆噪声），拆出来之后可以用**纯 JDK** 离线跑一遍
 * 「读包 → 逐字节比对」（见 {@code _tools/RepairCryptoSelfTest.java}），不启动游戏也能钉死正确性。
 * 而且**解密在服务端做**（服务端读自己世界目录下的 {@code MBM_Repair/Repair.zip}，
 * 解出来的字节直接写进存档的 SavedData）⇒ 它不能住在只给客户端编译的源集里。
 * 客户端那边（{@code smooth.lift.client.RepairPatch}）只负责发请求 / 开文件夹 / 写日志。
 *
 * <h2>加密格式（WinZip AES-256，AE-2）</h2>
 * <ul>
 *   <li>条目 `compressionMethod = 99`，真正的压缩方式写在 `0x9901` 扩展字段里；</li>
 *   <li>数据区 = `salt(8/12/16)` + `密码校验值(2)` + `密文` + `认证码(10)`；
 *       ★ 中央目录里的 `compressedSize` **含**这三段开销；</li>
 *   <li>密钥派生 = PBKDF2-HMAC-SHA1(**1000 轮**) → `[加密键 keyLen][MAC 键 keyLen][2 字节校验值]`；</li>
 *   <li>加密 = AES-CTR，**计数器 16 字节小端、从 1 开始**（与 JCE 的 `AES/CTR` 约定相反）；</li>
 *   <li>认证 = HMAC-SHA1(密文) 取前 10 字节。</li>
 * </ul>
 */
public final class RepairCrypto {

    /** 补丁能覆盖的**内置素材键**（= `sounds.json` 里 `audio/<key>` 的 `<key>`）。 */
    public static final List<String> KEYS =
            List.of("up", "down", "liftmusic", "dooropen", "doorclose", "mdoorclose");

    /** zip 里的条目名 = `<key>` + 这个后缀。 */
    public static final String ZIP_SUFFIX = ".ogg";

    private static final int AES_ZIP_ITERATIONS = 1000;
    private static final int PBKDF2_STORE_ITERATIONS = 100_000;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] STORE_MAGIC = {'M', 'B', 'M', 'R', 'P', '1'};

    /**
     * 密码的**混淆形式**：第 i 个字符 XOR {@link #padByte}(i)。
     * 真值只在 `_tools/make_repair_zip.py` 里；改密码必须同步这两处。
     */
    private static final int[] LOCK = {
            0x0A, 0xB0, 0x94, 0xF0, 0xBF, 0xFC, 0xDE, 0x12, 0x65, 0xD3,
            0xCE, 0x76, 0x09, 0xEA, 0x7B, 0x6F, 0x65, 0x15, 0xF3, 0xE6,
    };

    private static final int LOCK_SEED = 0x5A17;

    private RepairCrypto() {
    }

    /** 密码校验值不对 / HMAC 对不上 —— 与 IO 错误分开，日志里好分辨。 */
    public static final class WrongPasswordException extends IOException {
        public WrongPasswordException(String message) {
            super(message);
        }
    }

    /** 把 {@link #LOCK} 解回密码（递推式必须与生成脚本逐位一致）。 */
    public static String password() {
        StringBuilder sb = new StringBuilder(LOCK.length);
        int state = LOCK_SEED;
        for (int masked : LOCK) {
            state = state * 1103515245 + 12345;   // ★ 32 位溢出即回绕（Java int 语义）
            sb.append((char) (masked ^ ((state >>> 16) & 0xFF)));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 补丁包：读

    /**
     * 从 zip 的条目表里挑出本模组认识的那 6 个素材。
     *
     * @param entries `条目名 → 字节`（{@link #readAesZip} 的产物）
     * @return 素材键 → ogg 字节（只含真正存在、且是 Ogg 容器的条目）
     */
    public static Map<String, byte[]> pickOggs(Map<String, byte[]> entries) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            byte[] data = entries.get(key + ZIP_SUFFIX);
            if (data == null) {
                data = entries.get(key);
            }
            if (data == null || data.length < 4) {
                continue;
            }
            if (data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S') {
                continue;   // MC 只认 Ogg Vorbis，别的容器直接跳过（不整包失败）
            }
            out.put(key, data);
        }
        return out;
    }

    /**
     * 读一个 **WinZip AES 加密**的 zip（AE-2，128/192/256 bit 都支持），返回 `条目名 → 解压后字节`。
     *
     * <p>★ 从**中央目录**读而不是顺序读本地头：AES 条目一律带 data descriptor
     * （本地头的 sizes 是 0），顺序读根本不知道密文有多长；中央目录里有正确的
     * `compressedSize`（含 AES 开销）与 `0x9901` 扩展字段，是唯一可靠的路。
     */
    public static Map<String, byte[]> readAesZip(byte[] raw) throws IOException {
        int eocd = -1;
        int floor = Math.max(0, raw.length - 22 - 65536);
        for (int i = raw.length - 22; i >= floor; i--) {
            if (readU32LE(raw, i) == 0x06054b50) {   // ★ zip 的签名/字段全是**小端**
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("不是 zip（找不到中央目录结尾记录）");
        }
        int count = readU16LE(raw, eocd + 10);
        int cdOffset = readU32LE(raw, eocd + 16);

        Map<String, byte[]> out = new LinkedHashMap<>();
        int p = cdOffset;
        for (int i = 0; i < count; i++) {
            if (p + 46 > raw.length || readU32LE(raw, p) != 0x02014b50) {
                throw new IOException("中央目录第 " + i + " 条签名不对");
            }
            int method = readU16LE(raw, p + 10);
            int compSize = readU32LE(raw, p + 20);
            int nameLen = readU16LE(raw, p + 28);
            int extraLen = readU16LE(raw, p + 30);
            int commentLen = readU16LE(raw, p + 32);
            int localOffset = readU32LE(raw, p + 42);
            String name = new String(raw, p + 46, nameLen, StandardCharsets.UTF_8);
            int extraStart = p + 46 + nameLen;

            int strength = 0;
            int actualMethod = method;
            if (method == 99) {
                int[] aes = findAesExtra(raw, extraStart, extraLen);
                if (aes == null) {
                    throw new IOException(name + "：声明了 AES 加密但没有 0x9901 扩展字段");
                }
                strength = aes[0];
                actualMethod = aes[1];
            }

            if (localOffset + 30 > raw.length || readU32LE(raw, localOffset) != 0x04034b50) {
                throw new IOException(name + "：本地文件头签名不对");
            }
            int localNameLen = readU16LE(raw, localOffset + 26);
            int localExtraLen = readU16LE(raw, localOffset + 28);
            int dataStart = localOffset + 30 + localNameLen + localExtraLen;

            byte[] data;
            if (strength == 0) {
                if (dataStart + compSize > raw.length) {
                    throw new IOException(name + "：未加密条目越界");
                }
                data = inflate(Arrays.copyOfRange(raw, dataStart, dataStart + compSize), actualMethod);
            } else {
                int saltLen = aesSaltLen(strength);
                int keyLen = aesKeyLen(strength);
                if (dataStart + saltLen + 2 + 10 > raw.length) {
                    throw new IOException(name + "：AES 条目越界");
                }
                byte[] salt = Arrays.copyOfRange(raw, dataStart, dataStart + saltLen);
                byte[] verify = Arrays.copyOfRange(raw, dataStart + saltLen, dataStart + saltLen + 2);
                int cipherLen = compSize - saltLen - 2 - 10;
                if (cipherLen < 0 || dataStart + saltLen + 2 + cipherLen + 10 > raw.length) {
                    throw new IOException(name + "：AES 密文长度不对");
                }
                byte[] cipherText = Arrays.copyOfRange(raw,
                        dataStart + saltLen + 2, dataStart + saltLen + 2 + cipherLen);
                byte[] auth = Arrays.copyOfRange(raw,
                        dataStart + saltLen + 2 + cipherLen, dataStart + saltLen + 2 + cipherLen + 10);

                byte[][] keys = aesZipKeys(salt, keyLen);
                if (verify[0] != keys[2][0] || verify[1] != keys[2][1]) {
                    throw new WrongPasswordException("password verification failed");
                }
                byte[] hmac;
                try {
                    Mac mac = Mac.getInstance("HmacSHA1");
                    mac.init(new SecretKeySpec(keys[1], "HmacSHA1"));
                    hmac = mac.doFinal(cipherText);
                } catch (Exception e) {
                    throw new IOException("HMAC 失败：" + e);
                }
                for (int k = 0; k < 10; k++) {
                    if (hmac[k] != auth[k]) {
                        throw new WrongPasswordException("HMAC mismatch");
                    }
                }
                data = inflate(aesCtr(keys[0], cipherText), actualMethod);
            }
            out.put(name, data);
            p = extraStart + extraLen + commentLen;
        }
        return out;
    }

    /** WinZip AES：128bit → salt 8 / key 16；192 → 12 / 24；256 → 16 / 32。 */
    private static int aesSaltLen(int strength) {
        return strength == 1 ? 8 : strength == 2 ? 12 : 16;
    }

    private static int aesKeyLen(int strength) {
        return strength == 1 ? 16 : strength == 2 ? 24 : 32;
    }

    /** 在扩展字段里找 0x9901（AES），返回 {strength, actualMethod}；没有返回 null。 */
    private static int[] findAesExtra(byte[] raw, int start, int len) {
        int p = start;
        int end = Math.min(start + len, raw.length - 4);
        while (p + 4 <= end) {
            int id = readU16LE(raw, p);
            int size = readU16LE(raw, p + 2);
            if (id == 0x9901 && p + 4 + size <= start + len && size >= 7) {
                // 结构：版本(2) 厂商标识"AE"(2) 强度(1) 实际压缩方式(2, 小端)
                int strength = raw[p + 8] & 0xFF;
                int method = readU16LE(raw, p + 9);
                return new int[]{strength, method};
            }
            p += 4 + size;
        }
        return null;
    }

    /** WinZip AES 密钥派生：PBKDF2-HMAC-SHA1(1000) → [加密键][MAC 键][2 字节校验值]。 */
    private static byte[][] aesZipKeys(byte[] salt, int keyLen) throws IOException {
        try {
            PBEKeySpec spec = new PBEKeySpec(password().toCharArray(), salt,
                    AES_ZIP_ITERATIONS, (keyLen * 2 + 2) * 8);
            byte[] all = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
            if (all.length < keyLen * 2 + 2) {
                throw new IOException("派生长度不足");
            }
            return new byte[][]{
                    Arrays.copyOfRange(all, 0, keyLen),
                    Arrays.copyOfRange(all, keyLen, keyLen * 2),
                    Arrays.copyOfRange(all, keyLen * 2, keyLen * 2 + 2),
            };
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("密钥派生失败：" + e);
        }
    }

    /**
     * WinZip AES 用的 **AES-CTR**：计数器是 **16 字节小端、从 1 开始**。
     *
     * <p>★ 不能直接用 `Cipher("AES/CTR/NoPadding")` —— JCE 那个是**大端**计数器、从 0 开始，
     * 与 WinZip 的约定不同，拿来解只会得到一堆噪声（而且 `Cipher.update` 会静默出结果）。
     * 所以自己用 AES-ECB 生成密钥流再异或。
     */
    static byte[] aesCtr(byte[] key, byte[] cipherText) throws IOException {
        try {
            Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] out = new byte[cipherText.length];
            byte[] counter = new byte[16];
            byte[] block = new byte[16];
            long index = 1L;
            for (int off = 0; off < cipherText.length; off += 16) {
                Arrays.fill(counter, (byte) 0);
                long v = index;
                for (int i = 0; i < 8 && v != 0; i++) {
                    counter[i] = (byte) (v & 0xFF);
                    v >>>= 8;
                }
                ecb.doFinal(counter, 0, 16, block, 0);
                int n = Math.min(16, cipherText.length - off);
                for (int i = 0; i < n; i++) {
                    out[off + i] = (byte) (cipherText[off + i] ^ block[i]);
                }
                index++;
            }
            return out;
        } catch (Exception e) {
            throw new IOException("AES-CTR 失败：" + e);
        }
    }

    /** `method == 8` 走 raw deflate；`0` 原样返回。 */
    static byte[] inflate(byte[] data, int method) throws IOException {
        if (method == 0) {
            return data;
        }
        if (method != 8) {
            throw new IOException("不支持的压缩方式 " + method);
        }
        Inflater inflater = new Inflater(true);
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 3));
        byte[] buf = new byte[16384];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("解压失败：" + e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 小工具

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readU16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int readU32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /**
     * ★★ 读 zip 结构**必须**用这两个小端版本。
     *
     * <p>踩过的坑：zip 的多字节字段（签名、长度、偏移）**全是小端**。用大端读
     * 连 EOCD 签名 `50 4B 05 06` 都找不到（会被读成 `0x504B0506`，而常量写的是 `0x06054B50`），
     * 报出来的是「不是 zip（找不到中央目录结尾记录）」—— 看起来像文件坏了，其实是读法错了。
     * 上面那两个大端版是给**本模组自己的落盘格式**（`MBMRP1`，契约里就是大端）用的。
     */
    private static int readU16LE(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int readU32LE(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** 自检用：拿一个 `HashMap` 也能编（顺序无关）。 */
    public static Map<String, byte[]> newMap() {
        return new HashMap<>();
    }
}
