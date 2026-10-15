package smooth.lift;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【补丁】**服务端**那一半：把 `Repair.zip` 里的音频**导入存档 / 从存档里删掉**。
 *
 * <h2>一、补丁到底存在哪（用户点名的口径）</h2>
 * <blockquote>
 *   「补丁导入之后是直接把那些补丁音频**融入存档**的，而不是修补 jar 文件…
 *     点击删除补丁按钮之后删除融入存档的补丁音频，玩家自己导入的其他 ogg 不要动
 *     …补丁不要改模组 jar 文件内容！只修改存档！」
 * </blockquote>
 * 所以：
 * <ul>
 *   <li>解出来的 6 段 ogg 写进 **{@link EscalatorSpeedData#repairAssets}**
 *       —— 与音频库平级的**另一个存档字段**（存进世界的 `level.dat` 数据里，
 *       跟着存档走、换世界就换一份）；</li>
 *   <li>**绝不动 jar**：本类不写任何模组目录 / 游戏目录下的文件，
 *       只读 {@code <存档>/MBM_Repair/Repair.zip}、只写存档的 SavedData；</li>
 *   <li>**不碰玩家自己导入的 ogg**：那批在 {@link EscalatorSpeedData#audioLibrary} 里，
 *       与 {@code repairAssets} 是两张表 ⇒ 删除补丁只清后者。</li>
 * </ul>
 *
 * <h2>二、为什么解密放在服务端</h2>
 * 文件在**服务端的世界目录**下（`getWorldPath(LevelResource.ROOT)/MBM_Repair/Repair.zip`），
 * 而且写进的是服务端的存档数据 —— 客户端只需要最终那几段字节（走已有的分块音频同步）。
 * 解密用的是纯 JDK 的 {@link RepairCrypto}（它因此住在公共源集里）。
 *
 * <h2>三、与「内置素材」的关系</h2>
 * 导入之后，播放端 {@code EscalatorAudioPlayer.bundledBytes} 会**优先**取这里的字节
 * （见 {@code RepairPatch.bytes}）；删除之后自动退回 jar 里随包分发的自合成素材。
 */
public final class RepairStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 补丁文件夹（相对存档根目录）—— 用户点名：`存档文件夹\MBM_Repair`。 */
    public static final String FOLDER = "MBM_Repair";
    /** 补丁压缩包文件名（用户点名）。 */
    public static final String ZIP_NAME = "Repair.zip";

    private RepairStore() {
    }

    /** 补丁包的完整路径（服务端视角）；拿不到世界路径时返回 {@code null}。 */
    public static Path zipPath(ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            return level.getServer().getWorldPath(LevelResource.ROOT).resolve(FOLDER).resolve(ZIP_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这个存档里现在有没有补丁素材。 */
    public static boolean has(ServerLevel level) {
        return level != null && !EscalatorSpeedManager.getServerData(level).repairAssets.isEmpty();
    }

    /** 存档里有哪些补丁素材键（日志/诊断用）。 */
    public static java.util.Set<String> keys(ServerLevel level) {
        return has(level) ? java.util.Set.copyOf(EscalatorSpeedManager.getServerData(level).repairAssets.keySet())
                : java.util.Set.of();
    }

    /**
     * 从存档目录里的 `Repair.zip` 导入补丁。
     *
     * @return 真正导进去的段数；0 = 失败（包不在 / 解不开 / 里面没有可用素材）
     */
    public static int importFromSave(ServerLevel level) {
        Path zip = zipPath(level);
        if (zip == null || !Files.isRegularFile(zip)) {
            LOGGER.warn("[SmoothLift/Repair] 没找到补丁包：{}（请把 {} 放进存档的 {} 文件夹）",
                    zip, ZIP_NAME, FOLDER);
            return 0;
        }
        Map<String, byte[]> picked;
        try {
            picked = RepairCrypto.pickOggs(RepairCrypto.readAesZip(Files.readAllBytes(zip)));
        } catch (RepairCrypto.WrongPasswordException e) {
            LOGGER.warn("[SmoothLift/Repair] 补丁包解密失败（不是本模组的补丁包，或文件被改过）：{}", zip);
            return 0;
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift/Repair] 读取补丁包失败：{}", e.toString());
            return 0;
        }
        if (picked.isEmpty()) {
            LOGGER.warn("[SmoothLift/Repair] 补丁包里没有任何可用素材（期望 {}）", RepairCrypto.KEYS);
            return 0;
        }
        EscalatorSpeedData data = EscalatorSpeedManager.getServerData(level);
        // ★ 只写这 6 个**已知键**、只写 repairAssets 这一张表 ——
        //   玩家自己导入的 audioLibrary 一个字节都不碰（用户点名要求）。
        Map<String, byte[]> next = new LinkedHashMap<>();
        for (String key : RepairCrypto.KEYS) {
            byte[] bytes = picked.get(key);
            if (bytes != null && bytes.length > 0 && bytes.length <= EscalatorSpeedData.MAX_AUDIO_BYTES) {
                next.put(key, bytes);
            }
        }
        data.repairAssets.clear();
        data.repairAssets.putAll(next);
        data.setDirty();
        long total = 0L;
        for (byte[] b : next.values()) {
            total += b.length;
        }
        LOGGER.info("[SmoothLift/Repair] 补丁已融入存档：{} 段（{}）、共 {}KB；补丁包 {}",
                next.size(), next.keySet(), total / 1024,
                "可留可删（字节已进存档，删掉 zip 不影响）");
        return next.size();
    }

    /**
     * 删除已经融入存档的补丁素材。
     *
     * <p>★ 只清 {@link EscalatorSpeedData#repairAssets}；玩家自己导入的 ogg
     * （{@link EscalatorSpeedData#audioLibrary}）**一个都不动**。
     *
     * @return 之前是否有补丁（没有补丁时返回 false，调用方据此报失败）
     */
    public static boolean deleteFromSave(ServerLevel level) {
        if (level == null) {
            return false;
        }
        EscalatorSpeedData data = EscalatorSpeedManager.getServerData(level);
        if (data.repairAssets.isEmpty()) {
            LOGGER.warn("[SmoothLift/Repair] 没有可删的补丁（这个存档里没导入过）");
            return false;
        }
        int n = data.repairAssets.size();
        data.repairAssets.clear();
        data.setDirty();
        LOGGER.info("[SmoothLift/Repair] 已从存档删除补丁素材：{} 段（玩家自己导入的 ogg 未受影响）", n);
        return true;
    }
}
