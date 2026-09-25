"""Fetch androidx/material jars from yaap/prebuilts_sdk via the gh CLI.

We compile against AOSP prebuilts because the sandbox cannot reach Maven. The
repo tree is walked with the contents API (no giant recursive tree call).
"""
import json, os, subprocess, zipfile

OUT = os.environ.get("OUT", ".scratch/tools/aar")
REF = "fifteen"
REPO = "yaap/prebuilts_sdk"
os.makedirs(OUT, exist_ok=True)

def gh(path):
    r = subprocess.run(
        ["gh", "api", "-H", "Accept: application/vnd.github.raw", path],
        capture_output=True)
    return r.stdout if r.returncode == 0 else None

def fetch_path(path):
    return gh(f"repos/{REPO}/contents/{path}?ref={REF}")

def save_jar(dest, content, src_name):
    if src_name.endswith(".jar"):
        with open(dest, "wb") as f:
            f.write(content)
        return True
    tmp = dest + ".aar.tmp"
    try:
        with open(tmp, "wb") as f:
            f.write(content)
        with zipfile.ZipFile(tmp) as z, z.open("classes.jar") as fin, open(dest, "wb") as fout:
            fout.write(fin.read())
        return True
    except Exception:
        return False
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)

def walk(path):
    data = fetch_path(path)
    if not data:
        return
    for e in json.loads(data):
        p = e["path"]
        if e["type"] == "dir":
            yield from walk(p)
        else:
            low = p.lower()
            if not (low.endswith(".jar") or low.endswith(".aar")):
                continue
            if "sources" in low or "javadoc" in low:
                continue
            yield p

KEEP = [
    "current/androidx/m2repository/androidx/activity/activity/",
    "current/androidx/m2repository/androidx/annotation/",
    "current/androidx/m2repository/androidx/appcompat/appcompat/",
    "current/androidx/m2repository/androidx/cardview/cardview/",
    "current/androidx/m2repository/androidx/collection/",
    "current/androidx/m2repository/androidx/constraintlayout/constraintlayout/",
    "current/androidx/m2repository/androidx/coordinatorlayout/coordinatorlayout/",
    "current/androidx/m2repository/androidx/core/core/",
    "current/androidx/m2repository/androidx/core/core-ktx/",
    "current/androidx/m2repository/androidx/cursoradapter/cursoradapter/",
    "current/androidx/m2repository/androidx/customview/customview/",
    "current/androidx/m2repository/androidx/documentfile/documentfile/",
    "current/androidx/m2repository/androidx/drawerlayout/drawerlayout/",
    "current/androidx/m2repository/androidx/dynamicanimation/dynamicanimation/",
    "current/androidx/m2repository/androidx/fragment/fragment/",
    "current/androidx/m2repository/androidx/interpolator/interpolator/",
    "current/androidx/m2repository/androidx/lifecycle/",
    "current/androidx/m2repository/androidx/loader/loader/",
    "current/androidx/m2repository/androidx/print/print/",
    "current/androidx/m2repository/androidx/recyclerview/recyclerview/",
    "current/androidx/m2repository/androidx/savedstate/savedstate/",
    "current/androidx/m2repository/androidx/tracing/tracing/",
    "current/androidx/m2repository/androidx/transition/transition/",
    "current/androidx/m2repository/androidx/vectordrawable/vectordrawable/",
    "current/androidx/m2repository/androidx/versionedparcelable/versionedparcelable/",
    "current/androidx/m2repository/androidx/viewpager/viewpager/",
    "current/androidx/m2repository/androidx/viewpager2/viewpager2/",
    "current/androidx/m2repository/androidx/webkit/webkit/",
    "current/extras/material-design-x/",
]

count = 0
for prefix in KEEP:
    try:
        for p in walk(prefix.rstrip("/")):
            dest = os.path.join(OUT, os.path.dirname(p).replace("/", "_") + ".jar")
            if os.path.exists(dest):
                continue
            data = fetch_path(p)
            if not data:
                continue
            if save_jar(dest, data, p):
                count += 1
                print("ok", os.path.basename(dest), len(data))
    except Exception as ex:
        print("skip", prefix, ex)
print(f"downloaded {count}")
