package smooth.lift;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 【补丁】**服务端**那一半：把补丁文件夹里的音频**导入存档 / 从存档里删掉**。
 *
 * <h2>一、补丁到底存在哪（用户点名的口径）</h2>
 * <blockquote>
 *   「补丁导入之后是直接把那些补丁音频**融入存档**的，而不是修补 jar 文件…
 *     点击删除补丁按钮之后删除融入存档的补丁音频，玩家自己导入的其他 ogg 不要动
 *     …补丁不要改模组 jar 文件内容！只修改存档！」
 * </blockquote>
 * 所以：
 * <ul>
 *   <li>解出来的 ogg 写进 **{@link EscalatorSpeedData#repairAssets}**
 *       —— 与音频库平级的**另一个存档字段**（存进世界的 `level.dat` 数据里，
 *       跟着存档走、换世界就换一份）；</li>
 *   <li>**绝不动 jar**：本类不写任何模组目录 / 游戏目录下的文件，
 *       只读 {@code <存档>/MBM_Repair/} 下那个压缩包、只写存档的 SavedData；</li>
 *   <li>**不碰玩家自己导入的 ogg**：那批在 {@link EscalatorSpeedData#audioLibrary} 里，
 *       与 {@code repairAssets} 是两张表 ⇒ 删除补丁只清后者。</li>
 * </ul>
 *
 * <h2>二、为什么解密放在服务端</h2>
 * 文件在**服务端的世界目录**下（`getWorldPath(LevelResource.ROOT)/MBM_Repair/`），
 * 而且写进的是服务端的存档数据 —— 客户端只需要最终那几段字节（走已有的分块音频同步）。
 * 解密用的是纯 JDK 的 {@link RepairCrypto}（它因此住在公共源集里）。
 *
 * <h2>三、与「内置素材」的关系</h2>
 * 导入之后，播放端 {@code EscalatorAudioPlayer.bundledBytes} 会**优先**取这里的字节
 * （见 {@code RepairPatch.bytes}）；删除之后自动退回 jar 里随包分发的自合成素材。
 *
 * <h2>四、★ 2026-10-15 新增：玩家自制（**未加密**）的补丁包</h2>
 * <blockquote>
 *   「如果玩家放进去同名压缩包（Repair.zip）但是没有密码，并且里面的 ogg 文件名字也一样，
 *     就是补丁包里的 ogg 换成了玩家自己录制的音频 ogg，那么按下「导入补丁」按钮之后
 *     就把模组默认音频改为这个「补丁包」里的音频…
 *     记住，替换补丁过程是：「删除旧的补丁音频，再导入新的补丁音频。」所以如果新补丁音频
 *     缺少一些，就缺少的地方不管他，变成模组默认…
 *     如果补丁文件夹（MBM_Repair）里的补丁压缩包大于 1 个就在玩家按下「导入补丁」按钮之后
 *     不执行任何操作，也不给任何报错提示，只有补丁文件夹里的补丁压缩包数量为 1 时才可以导入补丁」
 * </blockquote>
 * 落地成三条 —— 前两条**本来就已经成立**，本条只补第三条：
 * <ol>
 *   <li><b>读取</b>：{@link RepairCrypto#readAesZip} 本来就**同时支持**「AES 条目」与
 *       「未加密条目」两条路（看它 {@code strength == 0} 那一支）⇒ 玩家用资源管理器 / 7-Zip
 *       压出来的普通 zip 直接就能读，**没有新增解析代码**；zip 的名字也不再要求叫
 *       {@link #ZIP_NAME} —— 文件夹里**唯一的那个 `.zip`** 就是补丁包（见 {@link #patchZips}）。</li>
 *   <li><b>替换</b>：{@link #importFromSave} 是 {@code repairAssets.clear()} → 再只写本次
 *       认出来的键 ⇒ **少哪个键，哪个就退回 jar 里的内置素材**
 *       （`up` / `down` 本来就是「无音频」，所以「新补丁没带 up.ogg」= 直梯上行仍然没声）。</li>
 *   <li><b>门禁（本轮新增）</b>：文件夹里 zip **多于一个** ⇒ 返回 {@link #IMPORT_SILENT}：
 *       什么都不做、也**不给任何报错提示**（连失败都不记）；**一个都没有** = 失败
 *       （保持原行为：日志 warn + 界面会话记失败）；**正好一个** 才导入。</li>
 * </ol>
 * ★ 为什么第 3 条要写成常量而不是字面量 {@code -1}：调用方必须能**区分**
 * 「静默」与「失败」这两个都「什么都没干」的结果 —— 分不清就会多给玩家一句
 * 「UI执行失败」，而那正是用户点名不要的提示。见 {@code SmoothLift.serverImportRepair}。
 */
public final class RepairStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 补丁文件夹（相对存档根目录）—— 用户点名：`存档文件夹\MBM_Repair`。 */
    public static final String FOLDER = "MBM_Repair";
    /**
     * 补丁压缩包的**约定名**（`_tools/make_repair_zip.py` 产出的就是它）。
     *
     * <p>★ 2026-10-15 起名字**不再有约束**：规则是「文件夹里唯一的那个 `.zip`」。
     * 这个常量现在只用于日志 / 文档里那句「约定名」。
     */
    public static final String ZIP_NAME = "Repair.zip";
    /** 认的扩展名（大小写不敏感）。其它扩展名（`.rar` / `.7z`）不算补丁包、也不参与计数。 */
    public static final String ZIP_EXT = ".zip";

    /**
     * ★★ **静默**哨兵（用户点名，2026-10-15）：补丁文件夹里 zip **多于一个**时，
     * 按「导入补丁」**什么都不做、也不给任何报错提示**。
     *
     * <p>⚠️ 调用方拿到它时**不许**调 {@code noteUiResult(player, false)} —— 一记失败，
     * 玩家退出界面就会收到一条「UI执行失败」，那正是用户不要的那句提示。
     */
    public static final int IMPORT_SILENT = -1;

    private RepairStore() {
    }

    /** 补丁文件夹的完整路径（服务端视角）；拿不到世界路径时返回 {@code null}。 */
    public static Path folder(ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            return level.getServer().getWorldPath(LevelResource.ROOT).resolve(FOLDER);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 补丁文件夹里**直接放着**的压缩包（只这一层，不递归），按文件名排序。
     *
     * <p>判据：是普通文件 + 文件名以 {@link #ZIP_EXT} 结尾（**大小写不敏感** ——
     * 玩家从别处考过来的包常叫 `.ZIP`）。文件夹不存在 / 列不出来 ⇒ 空表
     * （调用方按「一个都没有」处理 = 失败）。
     */
    public static List<Path> patchZips(ServerLevel level) {
        Path dir = folder(level);
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ZIP_EXT))
                    .sorted()
                    .forEach(out::add);
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift/Repair] 列补丁文件夹失败：{}", t.toString());
            return List.of();
        }
        return out;
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
     * 从存档目录的补丁文件夹里导入补丁。
     *
     * <p>★ 门禁（用户点名）：文件夹里 zip **多于一个** ⇒ {@link #IMPORT_SILENT}
     * （什么都不做、也不给提示）；**一个都没有** ⇒ 0（失败）。**正好一个**才往下走。
     *
     * @return 真正导进去的段数；{@link #IMPORT_SILENT} = 静默什么都别做；
     *         0 = 失败（没有包 / 解不开 / 里面没有可用素材）
     */
    public static int importFromSave(ServerLevel level) {
        List<Path> zips = patchZips(level);
        // ★★ 用户点名：补丁文件夹里的补丁压缩包**大于 1 个** ⇒ 不执行任何操作、也不给任何报错提示。
        //    这里只留一条 info 日志给自己排障 —— 它不是给玩家的「提示」：
        //    既不 noteUiResult、界面里也不写任何小字（退出界面时照旧只有那条汇总）。
        if (zips.size() > 1) {
            LOGGER.info("[SmoothLift/Repair] 补丁文件夹里有 {} 个 zip（{}）⇒ 按用户规则：什么都不做",
                    zips.size(), zips);
            return IMPORT_SILENT;
        }
        if (zips.isEmpty()) {
            LOGGER.warn("[SmoothLift/Repair] 补丁文件夹里一个 zip 都没有：{}（约定名 {}；请把补丁包放进去）",
                    folder(level), ZIP_NAME);
            return 0;
        }
        Path zip = zips.get(0);
        Map<String, byte[]> picked;
        try {
            // ★ 未加密的普通 zip 也走这一条（readAesZip 里 strength == 0 那一支就是它）。
            picked = RepairCrypto.pickOggs(RepairCrypto.readAesZip(Files.readAllBytes(zip)));
        } catch (RepairCrypto.WrongPasswordException e) {
            LOGGER.warn("[SmoothLift/Repair] 补丁包解密失败（不是本模组的补丁包，或文件被改过）：{}", zip);
            return 0;
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift/Repair] 读取补丁包失败：{}", e.toString());
            return 0;
        }
        if (picked.isEmpty()) {
            LOGGER.warn("[SmoothLift/Repair] 补丁包里没有任何可用素材（期望条目名 {}）", RepairCrypto.KEYS);
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
        // ★★ 用户点名的「替换补丁过程 = 删除旧的补丁音频、再导入新的」：
        //    先 clear 再 putAll ⇒ **本次没带上的键自动回模组默认**（不是「保留上一版」）。
        data.repairAssets.clear();
        data.repairAssets.putAll(next);
        data.setDirty();
        long total = 0L;
        for (byte[] b : next.values()) {
            total += b.length;
        }
        LOGGER.info("[SmoothLift/Repair] 补丁已融入存档：{} 段（{}）、共 {}KB；来源 {}",
                next.size(), next.keySet(), total / 1024, zip.getFileName());
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
