# -*- coding: utf-8 -*-
"""离线守则校验：**客户端专属的 mixin 配置不许按「双端共用」注册**（Fabric）。

## 为什么需要它（真实事故）

2026-11-01 用户开专用服务器（dedicated server）直接崩：

```
java.lang.RuntimeException: Cannot load class
  smooth.lift.client.mixin.sodium.SodiumLightMixinPlugin in environment type SERVER
```

根因链（每一环都能在本工程里查证）：

1. `fabric.mod.json` 的 `"mixins"` 数组里，**5 个配置全是裸字符串** —— 裸字符串 =
   `ModEnvironment.UNIVERSAL`（`V1ModMetadataParser#readMixinConfigs` 里写死），
   专用服务器启动时会把它们**全部**交给 Mixin 准备。
2. `smoothlift.sodium.mixins.json` 的 `plugin` 指向 `SodiumLightMixinPlugin`，
   这个类住在 **`src/client/java`**（客户端源码集）。
3. **Loom 在 remapJar 时会给 `src/client` 源码集的每个类自动补 `@Environment(EnvType.CLIENT)`**
   （源码里根本没有这个注解！实测：dev jar 里 4324 字节、无注解；成品 jar 里 4567 字节、有注解）。
4. Mixin 准备配置时要**实例化** plugin 类 ⇒ 服务器环境下加载该类 ⇒
   fabric-loader `FabricTransformer#transform` 扫到 `@Environment(CLIENT)` 与
   `EnvType.SERVER` 不符（`EnvironmentStrippingData#stripEntireClass()`）⇒
   `throw new RuntimeException("Cannot load class " + name + " in environment type " + envType)`。

**正确做法**（V1 schema，唯一被 loader 认的写法）：

```json
"mixins": [
  "双端配置.mixins.json",
  { "config": "客户端配置.mixins.json", "environment": "client" }
]
```

`V1ModMetadata#getMixinConfigs(EnvType)` 会按 `MixinEntry.environment.matches(type)` 过滤，
专用服务器**根本不会去读**客户端配置，plugin 类自然不会被实例化。

★ 两个**看起来对、实际更糟**的写法，本脚本都会拦：

- 顶层 `"client": { "mixins": [...] }` —— loader 0.15.x/0.16.x 的 V1 解析器没有这个分支，
  会当成未知根字段 `warn + skipValue`，结果是**客户端 mixin 全军覆没**（比崩还难查：
  不报错，只是扶梯渲染/屏蔽门/Sodium 取光全失效）。
- 给 plugin 类加 `@Environment(CLIENT)` —— 那正是把「配置被服务器读到」从
  「能加载但没用」升级成「启动即崩」的那一刀。修的是**注册位置**，不是注解。

## 这个脚本查什么

判定「一个配置算不算客户端专属」**不看它躺在哪个 resources 目录**（移植时
1.21.1 就把客户端配置放进了 `src/main/resources`，照样会崩），只看两件事：

- 配置里有 `mixins` / `server` 数组（= 真有双端 mixin）；
- 配置 `plugin` 点名的类落在 `src/main/java` 还是 `src/client/java`。

规则：

1. `fabric.mod.json` 里没有顶层 `client` / `server` 根对象（loader 不认，会静默丢配置）。
2. 有双端 mixin 的配置 ⇒ 必须按裸字符串（universal）注册。
3. 只有 `client` 数组 / 或 plugin 住 `src/client/java` 的配置 ⇒ 必须 `environment: client`。
4. 双端配置的 plugin 必须住 `src/main/java`（住 client 侧就是本次崩因）。
5. 配置里点名的每个 mixin 类都要能在某一侧源码集找到（防拼写错误静默失效）。

用法：`python _tools/check-mixin-env-split.py`（退出码 0 = 全部通过）
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN_RES = os.path.join(ROOT, "src", "main", "resources")
CLIENT_RES = os.path.join(ROOT, "src", "client", "resources")
MAIN_JAVA = os.path.join(ROOT, "src", "main", "java")
CLIENT_JAVA = os.path.join(ROOT, "src", "client", "java")
FABRIC_MOD = os.path.join(MAIN_RES, "fabric.mod.json")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def find_java_class(fqcn):
    """返回 fqcn 落在哪个源码集（'main' / 'client' / None）。"""
    rel = fqcn.replace(".", os.sep) + ".java"
    if os.path.isfile(os.path.join(MAIN_JAVA, rel)):
        return "main"
    if os.path.isfile(os.path.join(CLIENT_JAVA, rel)):
        return "client"
    return None


def all_configs():
    """[(name, abspath)]，两个 resources 目录都扫。"""
    out = []
    for res_dir in (MAIN_RES, CLIENT_RES):
        if not os.path.isdir(res_dir):
            continue
        for n in sorted(os.listdir(res_dir)):
            if n.endswith(".mixins.json"):
                out.append((n, os.path.join(res_dir, n)))
    return out


def registered_env(mod):
    """把 "mixins" 数组摊成 {config: env}；裸字符串 = universal。"""
    out = {}
    for entry in mod.get("mixins") or []:
        if isinstance(entry, str):
            out[entry] = "universal"
        elif isinstance(entry, dict):
            cfg = entry.get("config")
            if cfg:
                out[cfg] = (entry.get("environment") or "universal").lower()
    return out


def main():
    mod = load_json(FABRIC_MOD)
    reg = registered_env(mod)

    print("== fabric.mod.json 的 mixin 注册 ==")
    for cfg in sorted(reg):
        print("  %-38s %s" % (cfg, reg[cfg]))
    print()

    # ---- 0. 顶层 client / server 根对象（loader 不认，静默丢配置） ----
    for bad in ("client", "server"):
        check(bad not in mod,
              "fabric.mod.json 无顶层 \"%s\" 对象" % bad,
              "" if bad not in mod else "★ loader 不解析它 ⇒ 里面的 mixin 配置会被静默丢弃")

    # ---- 1. 每个配置逐个判定「双端 / 客户端专属」 ----
    for name, path in all_configs():
        cfg = load_json(path)
        pkg = cfg.get("package", "")
        has_common = bool(cfg.get("mixins") or cfg.get("server"))
        plugin = cfg.get("plugin")
        where = find_java_class(plugin) if plugin else None

        if plugin and not check(where is not None, "[%s] plugin 类存在: %s" % (name, plugin)):
            continue

        client_only = (not has_common) or where == "client"
        env = reg.get(name)
        if not check(env is not None, "[%s] 已注册进 fabric.mod.json" % name):
            continue

        if client_only:
            check(env == "client",
                  "[%s] 客户端专属 ⇒ 必须 environment=client" % name,
                  "" if env == "client" else
                  "★ 现在是 %s ⇒ 专用服务器会实例化客户端 plugin ⇒ 启动即崩" % env)
        else:
            check(env == "universal",
                  "[%s] 有双端 mixin ⇒ 必须按裸字符串注册" % name,
                  "" if env == "universal" else "现在是 %s ⇒ 另一侧会缺 mixin" % env)
            if plugin:
                check(where == "main",
                      "[%s] 双端配置的 plugin 必须在 src/main/java: %s" % (name, plugin),
                      "" if where == "main" else "实际在 src/client/java ⇒ 专用服务器一读配置就崩")

        # ---- 2. 点名的 mixin 类都得能落地（防拼写错误静默失效） ----
        for key in ("mixins", "client", "server"):
            for simple in cfg.get(key) or []:
                fqcn = pkg + "." + simple if pkg else simple
                check(find_java_class(fqcn) is not None,
                      "[%s] %s 里的 mixin 存在: %s" % (name, key, simple))

    # ---- 3. 注册了但磁盘上没有 ----
    known = {n for n, _ in all_configs()}
    for name in sorted(reg):
        check(name in known, "注册的配置文件存在: %s" % name)

    print()
    if FAILS:
        print("!! %d 项失败：" % len(FAILS))
        for f in FAILS:
            print("   - %s" % f)
        return 1
    print("全部通过：客户端专属配置按 environment=client 注册，专用服务器不会碰到客户端 plugin。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
