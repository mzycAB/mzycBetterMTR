#!/usr/bin/env bash
# 临时校验脚本（不参与打包）：脱离游戏环境把真·指令树建出来，检查 /futihelp 的每个分支。
# 需要先跑过一次 `gradlew build`（要有 build/classes/java/main），以及 _tools/runtime.cp
# （由 `gradlew -I _tools/dumpcp.gradle dumpRuntimeClasspath` 生成）。
set -e
cd "$(dirname "$0")/.."
JDK17="C:/Users/user/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"

CP="build/classes/java/main;$(cat _tools/runtime.cp)"

rm -rf _tools/out && mkdir -p _tools/out
"$JDK17/bin/javac.exe" -encoding UTF-8 -nowarn -d _tools/out -cp "$CP" _tools/CmdTreeCheck.java
"$JDK17/bin/java.exe" -cp "_tools/out;$CP" smooth.lift.CmdTreeCheck
