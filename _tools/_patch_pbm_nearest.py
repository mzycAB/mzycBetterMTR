# -*- coding: utf-8 -*-
"""【10-05】pbm* 指令「不带 -f」= 落到**离玩家最近的那一串屏蔽门**（fabric 1.20.4）。

做法：把 16 个非 -f 处理器从「写维度默认」改成走 psdNearestRun(...) 的延迟落地：
  runKey == null（无玩家 / 客户端算不出来）⇒ 回落**维度默认**（老行为，脚本照跑）；
  runKey != null                                ⇒ 写**按串**那一层（setDoorPsd* / setServerPsdTone）。

`-f` 分支、所有 show（查询）**一个字不动**。

用法： python _tools/_patch_pbm_nearest.py [--apply]
"""
import sys

P = "src/main/java/smooth/lift/SmoothLift.java"
APPLY = "--apply" in sys.argv

src = open(P, encoding="utf-8").read()
orig = src
done = []


def rep(old, new, tag):
    global src
    n = src.count(old)
    assert n == 1, "[%s] 锚点出现 %d 次（期望 1）" % (tag, n)
    src = src.replace(old, new, 1)
    done.append(tag)


# ---------------------------------------------------------------- pbmmusic
rep('''    /** `/pbmmusic <on|off>` —— 设置**本维度**的总开关。 */
    private static int pbmMusicGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdHelp(level, enabled);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /** `/pbmmusic <on|off>` —— 设置**离玩家最近的那一串**屏蔽门的总开关（不带 -f）。 */
    private static int pbmMusicGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdHelp(level, enabled);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdHelp(level, runKey, enabled);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmMusicGlobal")

rep('''    /** `/pbmmusic <X> to <Y>` —— 本维度总开关正好是 X 时才改成 Y。 */
    private static int pbmMusicFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultPsdHelp(level, from, to)) {
            boolean current = EscalatorSpeedManager.isPsdHelpEnabled(level);
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /** `/pbmmusic <X> to <Y>` —— 最近那一串的总开关正好是 X 时才改成 Y（不带 -f）。 */
    private static int pbmMusicFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                if (!EscalatorSpeedManager.replaceDefaultPsdHelp(level, from, to)) {
                    return false;
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.isDoorPsdHelpEnabled(level, runKey) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdHelp(level, runKey, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmMusicFromTo")

rep('''    /** `/pbmmusic open|close <on|off>` —— 设置**本维度**这一项子开关。 */
    private static int pbmMusicItemGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdToneEnabled(level, which, enabled);
        boolean healed = enabled && healPsdToneOffAudio(level, which);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /** `/pbmmusic open|close <on|off>` —— 设置**最近那一串**这一项子开关（不带 -f）。 */
    private static int pbmMusicItemGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdToneEnabled(level, which, enabled);
                if (enabled) {
                    healPsdToneOffAudio(level, which);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdToneEnabled(level, runKey, which, enabled);
                if (enabled) {
                    healDoorPsdToneOffAudio(level, runKey, which);
                }
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmMusicItemGlobal")

rep('''    /** `/pbmmusic open|close <X> to <Y>` —— 本维度这一项子开关正好是 X 时才改成 Y。 */
    private static int pbmMusicItemFromTo(CommandContext<CommandSourceStack> context, String which,
                                          boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultPsdToneEnabled(level, which, from, to)) {
            boolean current = EscalatorSpeedManager.isPsdToneEnabled(level, which);
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        boolean healed = to && healPsdToneOffAudio(level, which);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /** `/pbmmusic open|close <X> to <Y>` —— 最近那一串这一项正好是 X 时才改成 Y（不带 -f）。 */
    private static int pbmMusicItemFromTo(CommandContext<CommandSourceStack> context, String which,
                                          boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                if (!EscalatorSpeedManager.replaceDefaultPsdToneEnabled(level, which, from, to)) {
                    return false;
                }
                if (to) {
                    healPsdToneOffAudio(level, which);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.isDoorPsdToneEnabled(level, runKey, which) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdToneEnabled(level, runKey, which, to);
            if (to) {
                healDoorPsdToneOffAudio(level, runKey, which);
            }
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmMusicItemFromTo")

# ---- 素材（open|close <名字>）
rep('''        EscalatorSpeedManager.setDefaultPsdToneAudio(level, which, arg.id());
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdToneAudio(lv, which, arg.id());
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else if (!EscalatorSpeedManager.setServerPsdTone(lv, runKey, which, arg.id())) {
                return false;
            } else {
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmMusicItemAudioSet")

rep('''        if (!EscalatorSpeedManager.replaceDefaultPsdToneAudio(level, which, from.id(), to.id())) {
            String current = EscalatorSpeedManager.getPsdToneAudio(level, which);
            // 「不是 X 就没改」按惯例用 sendSuccess（不是错误，只是没命中），与直梯那套一致
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (!EscalatorSpeedManager.replaceDefaultPsdToneAudio(lv, which, from.id(), to.id())) {
                    // 「不是 X 就没改」按惯例算失败，与直梯那套一致
                    return false;
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            String current = EscalatorSpeedManager.getDoorPsdToneAudio(lv, runKey, which);
            if (!from.id().equals(current)) {
                return false;
            }
            if (!EscalatorSpeedManager.setServerPsdTone(lv, runKey, which, to.id())) {
                return false;
            }
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmMusicItemAudioFromTo")

# ---------------------------------------------------------------- pbmloud
rep('''        EscalatorSpeedManager.setDefaultPsdHelpVolume(level, volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"),
                false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdHelpVolume(lv, volume);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdHelpVolume(lv, runKey, volume);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmLoudGlobal")

rep('''        int current = EscalatorSpeedManager.getPsdHelpVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultPsdHelpVolume(level, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdHelpVolume(lv) != from) {
                    return false;
                }
                EscalatorSpeedManager.replaceDefaultPsdHelpVolume(lv, from, to);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.getDoorPsdHelpVolume(lv, runKey) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdHelpVolume(lv, runKey, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmLoudFromTo")

rep('''        EscalatorSpeedManager.setDefaultPsdToneVolume(level, which, volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdToneVolume(lv, which, volume);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdToneVolume(lv, runKey, which, volume);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmLoudItemGlobal")

rep('''        int current = EscalatorSpeedManager.getPsdToneVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultPsdToneVolume(level, which, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdToneVolume(lv, which) != from) {
                    return false;
                }
                EscalatorSpeedManager.replaceDefaultPsdToneVolume(lv, which, from, to);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.getDoorPsdToneVolume(lv, runKey, which) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdToneVolume(lv, runKey, which, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmLoudItemFromTo")

# ---- 到站播报 / 进站报站 各自的音量
rep('''        if ("midium".equals(which)) {
            EscalatorSpeedManager.setDefaultPsdMidiumVolume(level, volume);
        } else {
            EscalatorSpeedManager.setDefaultPsdArriveVolume(level, volume);
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdItemVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            boolean midium = "midium".equals(which);
            if (runKey == null) {
                if (midium) {
                    EscalatorSpeedManager.setDefaultPsdMidiumVolume(lv, volume);
                } else {
                    EscalatorSpeedManager.setDefaultPsdArriveVolume(lv, volume);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                if (midium) {
                    EscalatorSpeedManager.setDoorPsdMidiumVolume(lv, runKey, volume);
                } else {
                    EscalatorSpeedManager.setDoorPsdArriveVolume(lv, runKey, volume);
                }
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmItemLoudGlobal")

rep('''        int current = EscalatorSpeedManager.getPsdItemVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        if ("midium".equals(which)) {
            EscalatorSpeedManager.replaceDefaultPsdMidiumVolume(level, from, to);
        } else {
            EscalatorSpeedManager.replaceDefaultPsdArriveVolume(level, from, to);
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdItemVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            boolean midium = "midium".equals(which);
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdItemVolume(lv, which) != from) {
                    return false;
                }
                if (midium) {
                    EscalatorSpeedManager.replaceDefaultPsdMidiumVolume(lv, from, to);
                } else {
                    EscalatorSpeedManager.replaceDefaultPsdArriveVolume(lv, from, to);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            int current = midium
                    ? EscalatorSpeedManager.getDoorPsdMidiumVolume(lv, runKey)
                    : EscalatorSpeedManager.getDoorPsdArriveVolume(lv, runKey);
            if (current != from) {
                return false;
            }
            if (midium) {
                EscalatorSpeedManager.setDoorPsdMidiumVolume(lv, runKey, to);
            } else {
                EscalatorSpeedManager.setDoorPsdArriveVolume(lv, runKey, to);
            }
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmItemLoudFromTo")

# ---------------------------------------------------------------- pbmclosewait
rep('''        EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(level, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(lv, seconds);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdCloseWaitSeconds(lv, runKey, seconds);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmCloseWaitGlobal")

rep('''        int current = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultPsdCloseWaitSeconds(level, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdCloseWaitSeconds(lv) != from) {
                    return false;
                }
                EscalatorSpeedManager.replaceDefaultPsdCloseWaitSeconds(lv, from, to);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(lv, runKey) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdCloseWaitSeconds(lv, runKey, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }''', "pbmCloseWaitFromTo")

# ---------------------------------------------------------------- pbmmidium
rep('''    /** `/pbmmidium <名字>` —— 只改素材，保留当前等待秒数。 */
    private static int pbmMidiumSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int keep = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        return pbmMidiumApply(source, level, name, keep, null);
    }

    /** `/pbmmidium <名字> <秒>` —— 设置**本维度**。 */
    private static int pbmMidiumGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return pbmMidiumApply(source, level, name, seconds, "；其它维度不变");
    }''',
    '''    /** `/pbmmidium <名字>` —— 只改素材，保留**最近那一串**当前的等待秒数（不带 -f）。 */
    private static int pbmMidiumSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) -> {
            int seconds = runKey == null
                    ? EscalatorSpeedManager.getPsdMidiumWaitSeconds(lv)
                    : EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(lv, runKey);
            return applyMidiumToRun(source, lv, runKey, resolved, seconds);
        });
    }

    /** `/pbmmidium <名字> <秒>` —— 设置**最近那一串**（不带 -f）。 */
    private static int pbmMidiumGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) ->
                applyMidiumToRun(source, lv, runKey, resolved, seconds));
    }''', "pbmMidium(name|global)")

rep('''    /**
     * `本维度` 那条路共用的落地：解析名字 → 落库 → 同步 → 反馈。
     *
     * @param suffix 反馈尾注（`null` = 不带尾注）
     */
    private static int pbmMidiumApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(level, EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdMidium(level, resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
        String tail = suffix == null ? "" : suffix;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /**
     * 【10-05】到站播报的落地：runKey == null ⇒ 维度默认；否则只写那一串。
     */
    private static boolean applyMidiumToRun(CommandSourceStack source, ServerLevel level, Long runKey,
                                            String resolved, int seconds) {
        if (runKey == null) {
            EscalatorSpeedManager.setDefaultPsdMidium(level, resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        } else {
            EscalatorSpeedManager.setDoorPsdMidium(level, runKey, resolved, seconds);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        }
        return true;
    }''', "pbmMidiumApply->applyMidiumToRun")

# ---------------------------------------------------------------- pbmarrive
rep('''    /** `/pbmarrive <名字>` —— 只改素材，保留当前秒数。 */
    private static int pbmArriveSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int keep = EscalatorSpeedManager.getPsdArriveSeconds(level);
        return pbmArriveApply(source, level, name, keep, null);
    }

    /** `/pbmarrive <名字> <X>` —— 设置**本维度**。 */
    private static int pbmArriveGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return pbmArriveApply(source, level, name, seconds, "；其它维度不变");
    }''',
    '''    /** `/pbmarrive <名字>` —— 只改素材，保留**最近那一串**当前的秒数（不带 -f）。 */
    private static int pbmArriveSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) -> {
            int seconds = runKey == null
                    ? EscalatorSpeedManager.getPsdArriveSeconds(lv)
                    : EscalatorSpeedManager.getDoorPsdArriveSeconds(lv, runKey);
            return applyArriveToRun(source, lv, runKey, resolved, seconds);
        });
    }

    /** `/pbmarrive <名字> <X>` —— 设置**最近那一串**（不带 -f）。 */
    private static int pbmArriveGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) ->
                applyArriveToRun(source, lv, runKey, resolved, seconds));
    }''', "pbmArrive(name|global)")

rep('''    /** `本维度` 那条路共用的落地：解析名字 → 落库 → 同步 → 反馈。 */
    private static int pbmArriveApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(level, EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdArrive(level, resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdArriveSeconds(level);
        boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
        String tail = suffix == null ? "" : suffix;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /**
     * 【10-05】进站报站的落地：runKey == null ⇒ 维度默认；否则只写那一串。
     */
    private static boolean applyArriveToRun(CommandSourceStack source, ServerLevel level, Long runKey,
                                            String resolved, int seconds) {
        if (runKey == null) {
            EscalatorSpeedManager.setDefaultPsdArrive(level, resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        } else {
            EscalatorSpeedManager.setDoorPsdArrive(level, runKey, resolved, seconds);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        }
        return true;
    }''', "pbmArriveApply->applyArriveToRun")

# ---------------------------------------------------------------- pbmnarrate
rep('''    /** `/pbmnarrate <样式>` —— 设置**本维度**的讲述人样式（★ 秒数原样保留）。 */
    private static int pbmNarrateGlobal(CommandContext<CommandSourceStack> context, int mode) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        // ★ 秒数不在本指令里 ⇒ 把本维度**当前**的维度默认秒数原样传回去（别顺手清零）。
        EscalatorSpeedManager.setDefaultPsdNarrate(level, mode,
                EscalatorSpeedManager.getPsdNarrateSeconds(level));
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdNarrateMode(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }''',
    '''    /** `/pbmnarrate <样式>` —— 设置**最近那一串**的讲述人样式（不带 -f；★ 秒数原样保留）。 */
    private static int pbmNarrateGlobal(CommandContext<CommandSourceStack> context, int mode) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                // 秒数不在本指令里 ⇒ 把本维度**当前**的维度默认秒数原样传回去（别顺手清零）。
                EscalatorSpeedManager.setDefaultPsdNarrate(level, mode,
                        EscalatorSpeedManager.getPsdNarrateSeconds(level));
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                // 按串那一层：秒数是它自己那一格，本指令只动样式。
                EscalatorSpeedManager.setDoorPsdNarrate(level, runKey, mode);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }''', "pbmNarrateGlobal")

print("已改写 %d 处：" % len(done))
for d in done:
    print("  -", d)
print("残留长句抽查见 check-pbm-nearest.py")

if APPLY:
    open(P, "w", encoding="utf-8", newline="\n").write(src)
    print(">>> 已落盘 (原 %d -> 新 %d)" % (len(orig), len(src)))
else:
    print(">>> dry-run（未落盘）")
