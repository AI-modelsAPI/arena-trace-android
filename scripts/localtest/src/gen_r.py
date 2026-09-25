"""Generate com.ati.arena.R from res/ resources (subset used by the sources)."""
import os, re, sys

RES = sys.argv[1]
OUT = sys.argv[2]

def collect_values(root):
    project = {}
    for vt in sorted(os.listdir(root)):
        d = os.path.join(root, vt)
        if not (vt.startswith("values") and os.path.isdir(d)):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".xml"):
                continue
            text = open(os.path.join(d, f), encoding="utf-8").read()
            for m in re.finditer(r'<(string|color|bool|dimen|integer|plurals|style|item)\s+[^>]*name="([^"]+)"', text):
                typ, name = m.group(1), m.group(2)
                if name.startswith("android:"):
                    name = name.split(":", 1)[1]
                if typ == "item":
                    t = re.search(r'type="(\w+)"', m.group(0))
                    typ = t.group(1) if t else "id"
                project.setdefault(typ, set()).add(name)
    return project

project = collect_values(RES)

ids = set()
for layout_dir in ["layout", "layout-land", "layout-sw600dp"]:
    d = os.path.join(RES, layout_dir)
    if not os.path.isdir(d):
        continue
    for f in sorted(os.listdir(d)):
        text = open(os.path.join(d, f), encoding="utf-8").read()
        for m in re.finditer(r'@\+id/([\w.]+)', text):
            ids.add(m.group(1))
        for m in re.finditer(r'@id/([\w.]+)', text):
            ids.add(m.group(1))
for root, _, files in os.walk(os.path.dirname(RES.rstrip("/"))):
    for f in files:
        if f.endswith((".kt", ".xml")) and "/res/" not in os.path.join(root, f):
            for m in re.finditer(r'R\.id\.(\w+)', open(os.path.join(root, f), errors="ignore").read()):
                ids.add(m.group(1))

drawables = set()
d = os.path.join(RES, "drawable")
if os.path.isdir(d):
    for f in sorted(os.listdir(d)):
        drawables.add(f.rsplit(".", 1)[0])

layouts = set()
d = os.path.join(RES, "layout")
if os.path.isdir(d):
    for f in sorted(os.listdir(d)):
        layouts.add(f.rsplit(".", 1)[0])

lines = ["package com.ati.arena", "object R {"]
def emit(cls, names, base):
    lines.append(f"  object {cls} {{")
    for i, n in enumerate(sorted(names)):
        lines.append(f"    const val {n} : Int = 0x{base+i:08x}")
    lines.append("  }")

emit("id", sorted(ids), 0x7f0a0000)
emit("string", project.get("string", []), 0x7f0b0000)
emit("color", project.get("color", []), 0x7f0c0000)
emit("drawable", sorted(drawables), 0x7f0d0000)
emit("layout", sorted(layouts), 0x7f100000)
emit("bool", project.get("bool", []), 0x7f0e0000)
emit("dimen", project.get("dimen", []), 0x7f0f0000)
lines.append("}")
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w").write("\n".join(lines) + "\n")
print(f"R.kt: ids={len(ids)} string={len(project.get('string', []))} drawable={len(drawables)} layout={len(layouts)}")
