#!/usr/bin/env bash
# 逐个跑离线回归脚本，只打印「脚本 → 失败条数」以及失败条目文本，便于与基线对比。
# 用法: bash _tools/_dump-check-fails.sh > /tmp/xxx.log
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
cd "$ROOT" || exit 1
PY="C:/Users/user/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
SCRIPTS="check-audio-category.py check-chime-tiers.py check-command-tree.sh check-escalator-light-repair.py check-escalator-step-engine.py check-escalator-ui-twocol.py check-fade-curves.py check-lift-chime.py check-lift-move-sound.py check-lift-tone.py check-lift-track-look.py check-mbm-command.py check-mbm-help.py check-mixin-package-refs.py check-mixin-plugin-safety.py check-narrate-preset-save.py check-narrate-ui-hk.py check-open-folder.py check-pids-name-mask.py check-psd-128.py check-psd-131.py check-psd-align.py check-psd-anchor.py check-psd-broadcast.py check-psd-depart.py check-psd-midium-once.py check-psd-nbt.py check-psd-platform-group.py check-psd-predict.py check-psd-scope.py check-psd-split.py check-psd-tone.py check-psd-volume.py check-sodium-light-repair.py check-train-announce.py check-view-tilt.py check-zhaji-tone.py"
for s in $SCRIPTS; do
  if [ ! -f "_tools/$s" ]; then echo "### $s  (缺失)"; continue; fi
  case "$s" in
    *.sh) out="$(bash _tools/$s 2>&1)";;
    *)    out="$("$PY" _tools/$s 2>&1)";;
  esac
  n="$(printf '%s\n' "$out" | grep -a -c -E '^\[FAIL\]|^  ==> 失败')"
  echo "### $s  失败=$n"
  printf '%s\n' "$out" | grep -a -E '^\[FAIL\]|^  ==> 失败' | sed 's/^/    /'
done
