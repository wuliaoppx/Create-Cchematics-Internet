#!/usr/bin/env python3
"""把 Mojang 官方映射（official -> obf）与 MCPConfig 的 joined.tsrg（obf -> SRG）合成为
两个 TSRG 文件，供 tools1201 里的 TsrgRemap 使用：

  mojmap.tsrg : obf -> official       （把混淆客户端 jar 反混淆成可编译的名字）
  toSrg.tsrg  : official -> SRG       （把编好的 mod 类重混淆成 1.20.1 Forge 运行时名字）

用法：
  python MakeMappings.py <client_mappings.txt> <joined.tsrg> <outDir>
"""

import sys
import os

PRIMS = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
         'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}


def split_args(text):
    """按顶层逗号切参数表，忽略泛型里的逗号。"""
    out = []
    depth = 0
    cur = ''
    for ch in text:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [a for a in out if a.strip()]


def jvm_type(t):
    """proguard 的 Java 风格类型 -> JVM 描述符。"""
    t = t.strip()
    if '<' in t:
        head = t[:t.index('<')]
        tail = t[t.rindex('>') + 1:] if '>' in t else ''
        t = head + tail
    t = t.strip()
    if t.endswith('[]'):
        return '[' + jvm_type(t[:-2])
    if t == '...':
        return '[Ljava/lang/Object;'
    if t in PRIMS:
        return PRIMS[t]
    return 'L' + t.replace('.', '/') + ';'


def parse_proguard(path):
    """official -> obf，含成员名。返回 (class_map, per_obf_field, per_obf_method)。

    per_obf_field[obfClass][obfField] = officialField
    per_obf_method[obfClass][(obfName, obfDesc)] = officialName
    """
    official_to_obf = {}
    obf_to_official = {}
    fields = {}
    methods = {}
    pending_methods = []  # (officialClass, officialDesc, officialName, obfName)

    cur = None
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            if not line.strip() or line.startswith('#'):
                continue
            if not line.startswith(' ') and not line.startswith('\t'):
                left, right = line.rstrip('\n').split(' -> ')
                obf = right.rstrip(':')
                # proguard 用点号分隔类名，ASM/tsrg 用斜杠，这里统一成斜杠
                left = left.replace('.', '/')
                official_to_obf[left] = obf
                obf_to_official[obf] = left
                cur = obf
                continue
            if cur is None:
                continue
            body = line.strip()
            if ' -> ' not in body:
                continue
            left, obfName = body.split(' -> ')
            if '(' in left:
                # 常见形式 start:end:retType name(argTypes)；也兼容没有行号的 retType name(argTypes)
                parts = left.split(' ')
                if len(parts) < 2:
                    continue
                cols = parts[0].split(':')
                if len(cols) >= 3:
                    desc = cols[2] + ' ' + parts[1]
                else:
                    desc = parts[0] + ' ' + parts[1]
                if ' ' not in desc:
                    continue
                ret, rest = desc.split(' ', 1)
                name, args = rest.split('(', 1)
                args = args.rstrip(')')
                official_desc = '(' + ''.join(jvm_type(a) for a in split_args(args)) + ')' + jvm_type(ret)
                pending_methods.append((cur, official_desc, name, obfName))
            else:
                parts = left.split(' ')
                if len(parts) != 2:
                    continue
                fields.setdefault(cur, {})[obfName] = parts[1]

    def obf_desc(desc):
        out = []
        i = 0
        while i < len(desc):
            c = desc[i]
            if c == 'L':
                j = desc.find(';', i)
                if j < 0:
                    # 畸形描述符，原样保留，避免整份映射失败
                    out.append(desc[i:])
                    break
                cls = desc[i + 1:j]
                out.append('L' + official_to_obf.get(cls.replace('.', '/'), cls.replace('.', '/')) + ';')
                i = j + 1
            else:
                out.append(c)
                i += 1
        return ''.join(out)

    for obfClass, officialDesc, officialName, obfName in pending_methods:
        od = obf_desc(officialDesc)
        methods.setdefault(obfClass, {})[(obfName, od)] = officialName
    return official_to_obf, obf_to_official, fields, methods


def parse_tsrg(path):
    """obf -> SRG。返回 (class_map, fields, methods)。

    fields[obfClass][obfField] = srgField
    methods[obfClass][(obfName, obfDesc)] = srgName
    """
    classes = {}
    fields = {}
    methods = {}
    cur = None
    modifiers = {'static', 'final', 'abstract', 'private', 'protected', 'public',
                 'synthetic', 'bridge', 'native', 'transient', 'volatile', 'interface'}
    with open(path, 'r', encoding='utf-8') as f:
        for raw in f:
            line = raw.rstrip('\n')
            if not line or line.startswith('#') or line.startswith('tsrg'):
                continue
            if not line.startswith('\t') and not line.startswith(' '):
                parts = line.split(' ')
                if len(parts) < 2:
                    continue
                classes[parts[0]] = parts[1]
                cur = parts[0]
                continue
            if cur is None:
                continue
            parts = line.strip().split(' ')
            if not parts:
                continue
            head = parts[0]
            if head in modifiers or head.isdigit():
                # 修饰符行 / 参数行（0 o p_123_ 456）
                continue
            if len(parts) >= 3 and parts[1].startswith('('):
                # 方法：name desc srgName [id]
                methods.setdefault(cur, {})[(parts[0], parts[1])] = parts[2]
            elif len(parts) >= 2:
                # 字段：name srgName [id]
                fields.setdefault(cur, {})[parts[0]] = parts[1]
    return classes, fields, methods


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 1
    pg, tsrg, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(outdir, exist_ok=True)

    o2o, obf2off, pg_fields, pg_methods = parse_proguard(pg)
    tsrg_classes, tsrg_fields, tsrg_methods = parse_tsrg(tsrg)
    print(f'proguard: {len(o2o)} 类；tsrg: {len(tsrg_classes)} 类')

    # ---- mojmap.tsrg : obf -> official ----
    lines = []
    n_cls = n_fld = n_mth = 0
    for obf, official in obf2off.items():
        if obf.startswith('net/minecraft/') or obf.startswith('com/mojang/'):
            # 本来就没混淆的类（Main 之类）不需要映射
            continue
        lines.append(f'{obf} {official}\n')
        n_cls += 1
        for obf_field, official_field in pg_fields.get(obf, {}).items():
            lines.append(f'\t{obf_field} {official_field}\n')
            n_fld += 1
        for (name, desc), official_name in pg_methods.get(obf, {}).items():
            if name in ('<init>', '<clinit>'):
                continue
            lines.append(f'\t{name} {desc} {official_name}\n')
            n_mth += 1
    with open(os.path.join(outdir, 'mojmap.tsrg'), 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(lines)
    print(f'mojmap.tsrg: {n_cls} 类 / {n_fld} 字段 / {n_mth} 方法')

    # ---- toSrg.tsrg : official -> SRG ----
    lines = []
    n_cls = n_fld = n_mth = 0
    for obf, srg_class in tsrg_classes.items():
        official = obf2off.get(obf)
        if official is None:
            continue
        # 运行时类名用官方名，成员名用 SRG；这里按官方名建索引
        lines.append(f'{official} {official}\n')
        n_cls += 1
        for obf_field, srg_field in tsrg_fields.get(obf, {}).items():
            official_field = pg_fields.get(obf, {}).get(obf_field)
            if official_field is None:
                continue
            lines.append(f'\t{official_field} {srg_field}\n')
            n_fld += 1
        for (name, desc), srg_name in tsrg_methods.get(obf, {}).items():
            official_name = pg_methods.get(obf, {}).get((name, desc))
            if official_name is None or name in ('<init>', '<clinit>'):
                continue
            # 描述符要从 obf 转成 official，remap 时按 official 签名查找
            off_desc = []
            i = 0
            while i < len(desc):
                c = desc[i]
                if c == 'L':
                    j = desc.index(';', i)
                    cls = desc[i + 1:j]
                    off_desc.append('L' + obf2off.get(cls, cls) + ';')
                    i = j + 1
                else:
                    off_desc.append(c)
                    i += 1
            lines.append(f'\t{official_name} {"".join(off_desc)} {srg_name}\n')
            n_mth += 1
    with open(os.path.join(outdir, 'toSrg.tsrg'), 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(lines)
    print(f'toSrg.tsrg: {n_cls} 类 / {n_fld} 字段 / {n_mth} 方法')

    # ---- toMojmap.tsrg : SRG -> official（用于把 Forge 的 srg 成员 jar 变回可读名，只为编译） ----
    lines = []
    n_cls = n_fld = n_mth = 0
    for obf, _srg_class in tsrg_classes.items():
        official = obf2off.get(obf)
        if official is None:
            continue
        lines.append(f'{official} {official}\n')
        n_cls += 1
        for obf_field, srg_field in tsrg_fields.get(obf, {}).items():
            official_field = pg_fields.get(obf, {}).get(obf_field)
            if official_field is None:
                continue
            lines.append(f'\t{srg_field} {official_field}\n')
            n_fld += 1
        for (name, desc), srg_name in tsrg_methods.get(obf, {}).items():
            official_name = pg_methods.get(obf, {}).get((name, desc))
            if official_name is None or name in ('<init>', '<clinit>'):
                continue
            off_desc = []
            i = 0
            while i < len(desc):
                c = desc[i]
                if c == 'L':
                    j = desc.find(';', i)
                    if j < 0:
                        off_desc.append(desc[i:])
                        break
                    cls = desc[i + 1:j]
                    off_desc.append('L' + obf2off.get(cls, cls) + ';')
                    i = j + 1
                else:
                    off_desc.append(c)
                    i += 1
            lines.append(f'\t{srg_name} {"".join(off_desc)} {official_name}\n')
            n_mth += 1
    with open(os.path.join(outdir, 'toMojmap.tsrg'), 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(lines)
    print(f'toMojmap.tsrg: {n_cls} 类 / {n_fld} 字段 / {n_mth} 方法')
    # ---- obf2srg.tsrg : 混淆名 -> (官方类名, SRG 成员名)，用于一次性把原生 jar 变成可编译 jar ----
    lines = []
    n_cls = n_fld = n_mth = 0
    for obf, _srg_class in tsrg_classes.items():
        official = obf2off.get(obf, obf)
        if obf.startswith('net/minecraft/') or obf.startswith('com/mojang/'):
            continue
        lines.append(f'{obf} {official}\n')
        n_cls += 1
        for obf_field, srg_field in tsrg_fields.get(obf, {}).items():
            lines.append(f'\t{obf_field} {srg_field}\n')
            n_fld += 1
        for (name, desc), srg_name in tsrg_methods.get(obf, {}).items():
            if name in ('<init>', '<clinit>'):
                continue
            lines.append(f'\t{name} {desc} {srg_name}\n')
            n_mth += 1
    with open(os.path.join(outdir, 'obf2srg.tsrg'), 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(lines)
    print(f'obf2srg.tsrg: {n_cls} 类 / {n_fld} 字段 / {n_mth} 方法')
    return 0


if __name__ == '__main__':
    sys.exit(main())
