"""校验重映射后的 jar 引用的「运行时类」是否都真实存在。

重点抓两类问题：
1. catnip（net.createmod.*）被 jarjar 打包，如果实际没被 Forge 解出来就会 ClassNotFoundError；
2. 引用了某个版本不存在的 Create 内部类。

运行时 classpath 直接读版本 JSON 的 libraries 列表（和我手工列的那几个相比不会漏 brigadier 这类）。
"""
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build" / "1201"
JAR = BUILD / "remapped.jar"
TMP = BUILD / "chk_link"
VERSION_JSON = BUILD / "1.20.1.json"
LIBS = Path(r"D:\mc\.minecraft\libraries")
GAME_MODS = Path(r"D:\mc\.minecraft\versions\1.20.1-Forge_47.4.28\mods")


def runtime_jars():
    """版本 JSON 的库 + Forge universal + 本实例的 Create/CyberGoggles + 解出来的 catnip。"""
    jars = []
    data = json.loads(VERSION_JSON.read_text(encoding="utf-8"))
    for lib in data.get("libraries", []):
        artifact = lib.get("downloads", {}).get("artifact")
        if not artifact or not artifact.get("path"):
            continue
        path = LIBS / artifact["path"]
        if path.is_file():
            jars.append(path)

    for pattern in (
        "net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-universal.jar",
        # client jar 不在 version JSON 的 libraries 里（它是主 artifact），必须单独加
        "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar",
    ):
        path = LIBS / pattern
        if path.is_file():
            jars.append(path)

    for extra in (
        GAME_MODS / "create-1.20.1-6.0.8.jar",
        GAME_MODS / "CreateCyberGoggles-1.20.1-7.2.3-Forge.jar",
        BUILD / "ponder-catnip.jar",
    ):
        if extra.is_file():
            jars.append(extra)
    return jars


# 这些包由 JDK / LWJGL / log4j 等提供，单独放过
IGNORE_PREFIXES = (
    "java/", "javax/", "jdk/", "sun/", "org/joml/", "org/lwjgl/",
    "org/slf4j/", "com/google/", "org/apache/", "it/unimi/", "org/spongepowered/",
    "dev/cmsbp/",
)

CLS_REF = re.compile(r"//\s+class\s+([\w/$]+)")


def runtime_classes():
    names = set()
    jars = runtime_jars()
    for p in jars:
        try:
            with zipfile.ZipFile(p) as zf:
                for n in zf.namelist():
                    if n.endswith(".class"):
                        names.add(n[:-len(".class")])
        except Exception as exc:  # noqa: BLE001
            print(f"[warn] 读不了 {p.name}：{exc}")
    print(f"[link] 运行时类 {len(names)} 个（来自 {len(jars)} 个 jar）")
    return names


def main():
    if TMP.exists():
        import shutil
        shutil.rmtree(TMP)
    TMP.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(JAR) as zf:
        zf.extractall(TMP)
        classes = [n for n in zf.namelist()
                   if n.endswith(".class") and n.startswith("dev/cmsbp/")]

    known = runtime_classes()
    missing = {}
    for cls in sorted(classes):
        binary = cls[:-6].replace("/", ".")
        out = subprocess.run(
            ["javap", "-p", "-c", "-classpath", str(TMP), binary],
            capture_output=True, text=True, errors="replace").stdout
        for m in CLS_REF.finditer(out):
            name = m.group(1)
            if name.startswith(IGNORE_PREFIXES) or "$" in name:
                # 内部类由外层类文件一起打包，跳过（catnip 的 $ 内部类在 jar 里也已核对）
                if name.startswith(IGNORE_PREFIXES):
                    continue
            if name not in known:
                missing.setdefault(name, []).append(cls)

    if missing:
        print(f"\n[FAIL] {len(missing)} 个被引用类在运行时 classpath 里找不到：")
        for name, users in sorted(missing.items()):
            print(f"  {name}   <- {', '.join(sorted(set(users))[:3])}")
        return 1
    print("\n[OK] 所有被引用的运行时类都存在")
    return 0


if __name__ == "__main__":
    sys.exit(main())
