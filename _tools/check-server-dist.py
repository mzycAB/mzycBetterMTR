# -*- coding: utf-8 -*-
"""守则：Forge 版**专用服务器**不得在「会被加载的类」里指令级触碰 `net/minecraft/client.*`。

## 背景（2026-10-16 玩家开服崩溃实录）
```
Mod 'smooth_lift' encountered an error in a deferred task:
java.lang.BootstrapMethodError: java.lang.RuntimeException:
    Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER
    at smooth.lift.network.Packets.register(Packets.java:293)
Caused by: java.lang.RuntimeException: … for invalid dist DEDICATED_SERVER
    at net.minecraftforge.fml.loading.RuntimeDistCleaner.processClassWithFlags(RuntimeDistCleaner.java:57)
```
根因链：
1. `Packets.register()` 在 **mod 构造期**（DeferredWorkQueue）执行 `messageBuilder(MbmHelpOpenPacket.class, …)`
   —— 字节码就是 `ldc_w class smooth/lift/network/MbmHelpOpenPacket` ⇒ **该类必然被加载**；
2. HotSpot 加载类时会**验证它的全部方法**（含 lambda 合成方法）；
3. 那个 `handle` 里的 lambda 体（`lambda$handle$1()`）有
   `invokestatic net/minecraft/client/Minecraft.getInstance()Lnet/minecraft/client/Minecraft;`
   —— **指令的操作数/返回类型就是客户端类** ⇒ 验证阶段必须解析它 ⇒ DistCleaner 拒绝 ⇒ 服务器启动失败。
★ 「把客户端代码写进 lambda」**不是**隔离：lambda 体会被编译成**宿主类的私有合成方法**。
★ 反过来，`invokestatic smooth/lift/client/SmoothLiftClientEvents.onAudioSyncChunk(...)` 这类
  **只调用另一个类的方法、而那个方法自己的描述符里没有客户端类型**的写法**不会**触发加载
  （同一个 jar 里 AudioSyncPacket 就是这么写的，从没崩过）—— 所以判据必须落在**指令级**。

## 本脚本的判据
扫 jar 里**非 `smooth/lift/client/`** 的所有 class（client 包只在客户端被加载），
javap -c 逐行找**指令行里出现 `net/minecraft/client`** 的地方。
白名单 = 两个 mixin（{@code @Mixin(ModelBakery.class)} / {@code @Mixin(SoundEngine.class)}，
目标类本身就是客户端类 ⇒ 专用服务器不会加载目标 ⇒ 也不会加载这两个 mixin）。

## 自检（防「假网」）
脚本内置两段人造 javap 文本，验证判据**有鉴别力**：
含 `// class net/minecraft/client/...` 的必须命中，只有
`invokestatic smooth/lift/client/X.m(...)`（描述符无客户端类型）的必须**不**命中。
"""
import io
import os
import re
import subprocess
import sys
import zipfile

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _find_javap():
    """找 javap：环境变量 JAVAP_BIN > 常见 JDK 安装路径 > PATH 上的 javap。"""
    env = os.environ.get("JAVAP_BIN")
    if env and os.path.isfile(env):
        return env
    cands = [
        os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javap.exe"),
        r"C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/javap.exe",
        r"C:/Users/user/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin/javap.exe",
        r"C:/Users/user/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin/javap",
    ]
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return "javap"


JAVAP = _find_javap()
EXPECT_VERSION = "1.34.11201"

# 目标类本身是客户端类 ⇒ 专用服务器不会加载 ⇒ 不会加载 mixin 类本身
WHITELIST = {
    "smooth.lift.mixin.EscalatorModelBakeryMixin",   # @Mixin(net.minecraft.client.resources.model.ModelBakery)
    "smooth.lift.mixin.SoundEngineVolumeMixin",      # @Mixin(net.minecraft.client.sounds.SoundEngine)
}

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)


def judge_line(line):
    """判据本体：一行 javap 输出是否「指令级触碰客户端类型」。"""
    if "net/minecraft/client" not in line:
        return False
    return re.search(r"// (?:class|Method|Field|InterfaceMethod|Interface) ", line) is not None


def selftest():
    print("===== 1) 判据自检（防假网） =====")
    dirty = "   1: invokestatic  #83  // Method net/minecraft/client/Minecraft.m_91087_:()Lnet/minecraft/client/Minecraft;"
    clean = "  6: invokestatic  #38  // Method smooth/lift/client/SmoothLiftClientEvents.onAudioSyncChunk:(Ljava/lang/String;II[B)V"
    cls_only = "  0: new           #2   // class net/minecraft/client/gui/screens/Screen"
    javadoc = " * 本类不得出现 net/minecraft/client 类型"
    check(judge_line(dirty), "★ 含客户端类型的指令 → 判红（真雷）")
    check(judge_line(cls_only), "★ new 客户端类 → 判红")
    check(not judge_line(clean), "★ 只调 common 类的方法（描述符无客户端类型）→ 不判红（否则会造假阳性）")
    check(not judge_line(javadoc), "★ javadoc/注释行 → 不判红（注释不进字节码）")


def find_jar():
    libs = os.path.join(PROJ, "build", "libs")
    if not os.path.isdir(libs):
        return None
    jars = sorted(f for f in os.listdir(libs)
                  if re.match(r"mzycBetterMTR-[\d.]+\.jar$", f))
    return os.path.join(libs, jars[-1]) if jars else None


def scan_class(jar, cls, work):
    r = subprocess.run([JAVAP, "-p", "-c", "-cp", ".", cls],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", cwd=work)
    return r.stdout


def main():
    selftest()
    print()
    print("===== 2) jar 与版本 =====")
    jar = find_jar()
    check(jar is not None, "找到 build/libs/mzycBetterMTR-*.jar（先 gradlew build）")
    if jar is None:
        print("\n== 失败 %d 项 ==" % len(FAILS))
        sys.exit(1)
    check(EXPECT_VERSION in os.path.basename(jar), "版本号不变（用户点名）",
          "%s / 期望 %s" % (os.path.basename(jar), EXPECT_VERSION))

    z = zipfile.ZipFile(jar)
    names = [n for n in z.namelist() if n.endswith(".class")]

    print()
    print("===== 3) 两个发包类**字节级**不得含 net/minecraft/client =====")
    for rel in ("smooth/lift/network/MbmHelpOpenPacket.class",
                "smooth/lift/network/PsdNearestRequestPacket.class"):
        d = z.read(rel) if rel in names else b""
        check(bool(d), "jar 里有 %s" % rel)
        check(b"net/minecraft/client" not in d,
              "★ %s 字节里没有 net/minecraft/client（有 ⇒ 专用服开服即崩）" % rel)
    # 客户端动作确实挪到了 client 包里的新类
    check("smooth/lift/client/MbmHelpOpen.class" in names,
          "客户端动作类 MbmHelpOpen 已就位（MbmHelpOpenPacket 里只留方法引用）")
    check("smooth/lift/client/PsdNearestRequestHandler.class" in names,
          "客户端动作类 PsdNearestRequestHandler 已就位")

    print()
    print("===== 4) 全 jar 扫描（非 client 包）=====")
    import tempfile
    import shutil
    work = tempfile.mkdtemp(prefix="serverdist_")
    try:
        z.extractall(work)
        hits = []
        for n in names:
            cls = n[:-6].replace("/", ".")
            if cls.startswith("smooth.lift.client."):
                continue
            out = scan_class(jar, cls, work)
            cur = "?"
            for line in out.splitlines():
                m = re.match(r"\s{2}(.+\(.*?\));\s*$", line)
                if m and not line.strip()[0].isdigit():
                    cur = m.group(1).strip()
                    continue
                if judge_line(line):
                    hits.append((cls, cur, line.strip()))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    bad = [h for h in hits if h[0] not in WHITELIST]
    for cls, meth, line in bad:
        print("      ✗ %-44s | %s\n          %s" % (cls, meth[:56], line[:110]))
    check(not bad, "★ 非白名单类里没有指令级客户端类型引用", "%d 条" % len(bad))
    wl = [h for h in hits if h[0] in WHITELIST]
    print("      （白名单命中 %d 条：目标是客户端类的 mixin，服务器不会加载）" % len(wl))

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("   ==> " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()
