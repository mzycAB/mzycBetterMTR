# -*- coding: utf-8 -*-
"""Assemble a production client (PCL-style) to verify refmap fix with loader 0.19.5."""
import json, io, os, hashlib, urllib.request, sys

BASE = os.path.dirname(os.path.abspath(__file__))
os.chdir(BASE)
PROD = "prod_test"
os.makedirs(PROD + "/libs", exist_ok=True)
os.makedirs(PROD + "/game", exist_ok=True)

d = json.load(io.open("../_crash_1004_938/1.21.1-Fabric 0.19.5.json", encoding="utf-8-sig"))

def sha1(p):
    h = hashlib.sha1()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def fetch(url, dst, sha=None):
    if os.path.exists(dst) and (sha is None or sha1(dst) == sha):
        return False
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    urls = [url]
    if "libraries.minecraft.net" in url or "piston" in url:
        urls.append(url.replace("https://libraries.minecraft.net/", "https://bmclapi2.bangbang93.com/maven/")
                         .replace("https://piston-data.mojang.com/", "https://bmclapi2.bangbang93.com/")
                         .replace("https://piston-meta.mojang.com/", "https://bmclapi2.bangbang93.com/"))
    last = None
    for u in urls:
        for attempt in range(3):
            try:
                req = urllib.request.Request(u, headers={"User-Agent": "mzycBetterMTR-prod-test"})
                with urllib.request.urlopen(req, timeout=90) as r, open(dst + ".tmp", "wb") as f:
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                os.replace(dst + ".tmp", dst)
                if sha is not None and sha1(dst) != sha:
                    raise IOError("sha mismatch from " + u)
                return True
            except Exception as e:
                last = e
    raise last

cp = []
# 1. vanilla client jar
c = d["downloads"]["client"]
p = PROD + "/minecraft/client.jar"
if fetch(c["url"], p, c["sha1"]):
    print("downloaded client.jar")
cp.append(os.path.abspath(p))

# 2. libraries (windows set)
n_dl = 0
for l in d["libraries"]:
    dl = l.get("downloads", {})
    arts = []
    if "artifact" in dl:
        arts.append((dl["artifact"]["url"], dl["artifact"]["path"], dl["artifact"].get("sha1")))
    for k, v in dl.get("classifiers", {}).items():
        if "windows" in k:
            arts.append((v["url"], v["path"], v.get("sha1")))
    if not arts and l.get("name") and l.get("url"):
        # PCL/fabric 风格：url = 仓库根，name = maven 坐标
        g, a, v = l["name"].split(":")
        rel = "%s/%s/%s/%s-%s.jar" % (g.replace(".", "/"), a, v, a, v)
        arts.append((l["url"].rstrip("/") + "/" + rel, rel, None))
    for url, path, sha in arts:
        dst = PROD + "/libs/" + path
        if fetch(url, dst, sha):
            n_dl += 1
        cp.append(os.path.abspath(dst))
print("libs downloaded:", n_dl, "| classpath entries:", len(cp))

# 3. fabric-api (fat jar) + our mod jar
def find_one(root, name):
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f == name:
                return os.path.join(dirpath, f)
    return None

fapi = find_one(".gradle_home/caches/modules-2", "fabric-api-0.116.17+1.21.1.jar")
mod = "build/libs/mzycBetterMTR-1.30.1211.jar"
assert fapi and os.path.exists(mod), (fapi, mod)
mods_dir = PROD + "/game/mods"
os.makedirs(mods_dir, exist_ok=True)
import shutil
shutil.copy(fapi, mods_dir + "/fabric-api-0.116.17+1.21.1.jar")
shutil.copy(mod, mods_dir + "/mzycBetterMTR-1.30.1211.jar")
print("mods:", os.listdir(mods_dir))

io.open(PROD + "/classpath.txt", "w", encoding="utf-8").write(";".join(cp))
print("classpath written:", len(cp), "entries")
