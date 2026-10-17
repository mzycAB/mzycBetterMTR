#!/usr/bin/env bash
# 临时校验脚本（不参与打包）：无障碍提示音「音乐」的 NBT 兼容性回归
#（1.39 的单一字段 → 1.41 的进 / 出两套，旧存档行为必须逐字节不变）。
# 需要先跑过一次 `gradlew compileJava`（要有 build/classes/java/main），以及 _tools/runtime.cp
# （由 `gradlew -I _tools/dumpcp.gradle dumpRuntimeClasspath` 生成）。
set -e
cd "$(dirname "$0")/.."
JDK17="C:/Users/user/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"

CP="build/classes/java/main;$(cat _tools/runtime.cp)"

rm -rf _tools/out-nbt && mkdir -p _tools/out-nbt
"$JDK17/bin/javac.exe" -encoding UTF-8 -nowarn -d _tools/out-nbt -cp "$CP" _tools/NbtCompatCheck.java
"$JDK17/bin/java.exe" -cp "_tools/out-nbt;$CP" smooth.lift.NbtCompatCheck
