# -*- coding: utf-8 -*-
"""离线校验：屏蔽门提示音同步包 psd_chime_sync 的写序 / 读序必须逐位成对。

★★【LOG-Hs1 事故复盘】2026-10-06 用户报 fabric 1.20.1 存档进不去、被踢回主界面：
  根因就是 buildPsdChimePacket（写侧）把 defaultPsdDepartDelaySeconds 写在 closeWait 之后，
  而 SmoothLiftClient（读侧）把它读在**末尾** ⇒ 从 midiumAudio 起逐字段错位 ⇒ 客户端 join 存档
  在 psd_chime_sync 通道 StringIndexOutOfBoundsException ⇒ netty 通道错误 ⇒ 连接中断被踢。
  本守卫就是把「写侧方法」与「客户端读侧 receiver」都抠出来，按序比对 read/write 类型。

判据（两类断言）：
  1. 写侧字段数 == 读侧字段数；
  2. 逐位类型成对（writeXxx ↔ readXxx，Utf/VarInt/Boolean 一一对应，writeNarrateUserTexts 特判）。

用法：python check-psd-chime-pair.py （build/classes 不必在；纯源码断言）。
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."

fails = []


def strip_comments(s):
    # 去掉 /* */ 与 // 注释（保留字符串里的 // 就不管了——该工程源码注释规则一致）
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return "\n".join(l.split("//")[0] for l in s.split("\n"))


def find_method(src, sig):
    """按签名开头定位方法体（括号深度配对，含签名行）。"""
    i = src.find(sig)
    if i < 0:
        return None
    k = src.index("{", i)
    d = 0
    for j in range(k, len(src)):
        if src[j] == "{":
            d += 1
        elif src[j] == "}":
            d -= 1
            if d == 0:
                return src[i:j + 1]
    return src[i:]


def extract_fields(body, prefix):
    """抽取方法体里 buf./data. 的 write*/read* 调用序列（含参数个数不关心，只要类型名）。"""
    return re.findall(r"\b" + prefix + r"\.(write\w+|read\w+)\([^;{}]*", body)


# 写侧
esm_path = os.path.join(ROOT, "src/main/java/smooth/lift/EscalatorSpeedManager.java")
esm = strip_comments(io.open(esm_path, encoding="utf-8").read())
wbody = find_method(esm, "private static FriendlyByteBuf buildPsdChimePacket")
if not wbody:
    fails.append("抠不出 EscalatorSpeedManager.buildPsdChimePacket()")
else:
    writes = extract_fields(wbody, "buf")
    # 读侧：SmoothLiftClient 的 psd_chime_sync receiver（fabric 布局）
    client_path = os.path.join(ROOT, "src/client/java/smooth/lift/client/SmoothLiftClient.java")
    if not os.path.exists(client_path):
        client_path = os.path.join(ROOT, "src/main/java/smooth/lift/client/SmoothLiftClient.java")
    cli = strip_comments(io.open(client_path, encoding="utf-8").read())
    rbody = find_method(cli, "PSD_CHIME_SYNC_CHANNEL")
    if not rbody:
        fails.append("抠不出 SmoothLiftClient 里 psd_chime_sync 的接收器")
    else:
        reads = extract_fields(rbody, "buf")

        def pair(a, b):
            if a == "writeNarrateUserTexts":
                return b.startswith("readNarrateUserTexts")
            return a[len("write"):] == b[len("read"):]

        for i, (a, b) in enumerate(zip(writes, reads)):
            if not pair(a, b):
                fails.append("第 %d 格 写=%s 读=%s 不对位" % (i + 1, a, b))
        if len(writes) != len(reads):
            fails.append("写侧 %d 格、读侧 %d 格（长度不一致 ⇒ 有人只改了一边）"
                         % (len(writes), len(reads)))

for f in fails:
    print("==> 失败  %s" % f)
print("%d 项失败" % len(fails))
raise SystemExit(1 if fails else 0)