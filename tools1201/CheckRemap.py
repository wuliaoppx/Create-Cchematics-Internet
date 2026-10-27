"""扫描重映射后的 jar，找出仍然以「可读名」引用 Minecraft 成员的常量池条目。

判定依据：build/mappings-1.20.1.tsrg 里 named!=srg 的条目集合。
若某条引用的 (owner, name+desc) 命中该集合，但字节码里用的是 named 名，则说明漏了重映射，
运行时会 NoSuchMethodError / NoSuchFieldError。
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TMP = ROOT / "build" / "1201" / "chk_remap"
MAPPINGS = ROOT / "build" / "mappings-1.20.1.tsrg"
JAR = ROOT / "build" / "1201" / "remapped.jar"

MC_PREFIXES = ("net/minecraft/", "com/mojang/blaze3d/")


def load_mappings(path):
    """返回 {'owners': set, 'members': {(owner, kind, name, desc): srg}}"""
    owners = set()
    members = {}
    cur = None
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line:
                continue
            if not line.startswith("\t"):
                parts = line.split()
                if len(parts) >= 2:
                    cur = parts[1]  # srg owner
                    owners.add(cur)
                continue
            parts = line.strip().split()
            if cur is None or len(parts) < 2:
                continue
            name, desc, srg = parts[0], parts[1], parts[2]
            kind = "M" if "(" in desc else "F"
            members[(cur, kind, name, desc)] = srg
    return owners, members


def main():
    _, members = load_mappings(MAPPINGS)
    # 只需要 named != srg 的（即真的被混淆的）成员
    renamed = {k for k, v in members.items() if v != k[2]}
    print(f"[check] mappings: {len(members)} members, {len(renamed)} renamed")

    if TMP.exists():
        import shutil

        shutil.rmtree(TMP)
    TMP.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(JAR) as zf:
        classes = [n for n in zf.namelist()
                   if n.endswith(".class") and n.startswith("dev/cmsbp/")]
        zf.extractall(TMP)
    print(f"[check] jar classes: {len(classes)}")

    ref_re = re.compile(
        r"//\s+(?:Method|InterfaceMethod|Field)\s+([\w/$]+)\.([\w$<>]+):(\S+)")
    problems = []
    owners_missing = set()

    for cls in sorted(classes):
        binary = cls[:-len(".class")].replace("/", ".")
        out = subprocess.run(
            ["javap", "-p", "-c", "-classpath", str(TMP), binary],
            capture_output=True, text=True, errors="replace").stdout
        for m in ref_re.finditer(out):
            owner, name, desc = m.group(1), m.group(2), m.group(3)
            if not owner.startswith(MC_PREFIXES):
                continue
            if name.startswith(("<", "m_", "f_", "lambda$")):
                continue
            kind = "M" if "(" in desc else "F"
            key = (owner, kind, name, desc)
            if key in renamed:
                problems.append((cls, owner, name, desc, renamed[key]))
            elif owner not in members and False:
                pass

    # 再查：owner 是否是 srg 类名（若 mapping 里该 owner 的 srg 名不同，说明类名也漏了）
    if problems:
        print(f"\n[FAIL] {len(problems)} 处未重映射的 Minecraft 成员引用：")
        for cls, owner, name, desc, want in problems:
            print(f"  {cls}\n    {owner}.{name}{desc}  -> 应为 {want}")
        return 1

    print("\n[OK] 没有发现漏重映射的 Minecraft 成员引用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
