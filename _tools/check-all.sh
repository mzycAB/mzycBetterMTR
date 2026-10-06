#!/usr/bin/env bash
# 一次跑完全部离线回归校验（不参与打包）。
# 前置：`gradlew build`（要有 build/classes/java/main 与 build/libs 的 jar）
#   以及 _tools/runtime.cp（`gradlew -I _tools/dumpcp.gradle dumpRuntimeClasspath` 生成）。
# 用法：bash _tools/check-all.sh
set -e
cd "$(dirname "$0")/.."

PY="C:/Users/user/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
if [ ! -x "$PY" ]; then PY="python"; fi

TMP="$(mktemp -d)"
rc=0

run() {
    local name="$1"; shift
    echo
    echo "############################################################"
    echo "# $name"
    echo "############################################################"
    if "$@" > "$TMP/$(echo "$name" | tr -cd 'a-zA-Z0-9').log" 2>&1; then
        echo "  ==> 通过"
    else
        echo "  ==> 失败（完整输出见下方）"
        cat "$TMP/$(echo "$name" | tr -cd 'a-zA-Z0-9').log"
        rc=1
    fi
}

run "mixin 插件安全"        "$PY" _tools/check-mixin-plugin-safety.py
run "mixin 包守卫(类)"      "$PY" _tools/check-mixin-package-refs.py
run "扶梯阶梯渲染引擎"      "$PY" _tools/check-escalator-step-engine.py
run "屏蔽门锚点与跳变"      "$PY" _tools/check-psd-anchor.py
run "屏蔽门存档字段"        "$PY" _tools/check-psd-nbt.py
run "屏蔽门提示音素材"      "$PY" _tools/check-psd-tone.py
run "屏蔽门关门提示音对齐"  "$PY" _tools/check-psd-align.py
run "屏蔽门提示音顺序"      "$PY" _tools/check-psd-split.py
run "屏蔽门提前量状态机"    "$PY" _tools/check-psd-predict.py
run "屏蔽门音量与淡入淡出"  "$PY" _tools/check-psd-volume.py
run "屏蔽门站台广播"        "$PY" _tools/check-psd-broadcast.py
run "屏蔽门讲述人只念一次"  "$PY" _tools/check-psd-midium-once.py
run "讲述人默认值/预设/按存档文字" "$PY" _tools/check-narrate-preset-save.py
run "屏蔽门串身份=站台"     "$PY" _tools/check-psd-platform-group.py
run "屏蔽门1.28身份迁移与列车倍率" "$PY" _tools/check-psd-128.py
run "屏蔽门铃声/播报归属"   "$PY" _tools/check-psd-scope.py
run "屏蔽门1.31借用与列车挡" "$PY" _tools/check-psd-131.py
run "MBM批量音频指令" "$PY" _tools/check-mbm-command.py
run "预设选择界面与预设" "$PY" _tools/check-mbm-help.py
run "同步所有按钮与弹窗" "$PY" _tools/check-1.55-sync.py
run "列车音效界面与侧线" "$PY" _tools/check-1.57-train-siding.py
run "直梯界面两列版式"     "$PY" _tools/check-lift-ui-twocol.py
run "MTR3/MTR4 契约"       "$PY" _tools/check-mtr3-contract.py
run "指令树"                bash _tools/check-command-tree.sh
run "PIDS 站台名掩码"      "$PY" _tools/check-pids-name-mask.py

echo
echo "############################################################"
if [ "$rc" -eq 0 ]; then
    echo "# 全部离线回归通过"
else
    echo "# 有回归失败，见上面明细"
fi
echo "############################################################"
exit $rc
