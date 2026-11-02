"""精确扫描：找出常量池里存在 Methodref / InterfaceMethodref 指向指定 owner 的 class。

比字符串搜索准 —— 字符串只能说明"提到过这个名字"，常量池引用才说明"真的调用了它"。

用法：python scan_refs.py <owner-slash-form> [methodName]
"""
import sys, zipfile, struct

HOST = r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"

CONSTANT_Utf8 = 1
CONSTANT_Class = 7
CONSTANT_NameAndType = 12
CONSTANT_Methodref = 10
CONSTANT_InterfaceMethodref = 11


def parse_cp(data):
    """返回 (常量池列表, 解析结果) —— 只解析到能拿到 Class/NameAndType 的程度。"""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    count = struct.unpack(">H", data[8:10])[0]
    cp = [None] * count
    pos = 10
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == CONSTANT_Utf8:
            ln = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            cp[i] = ("utf8", data[pos:pos + ln])
            pos += ln
        elif tag in (CONSTANT_Class, 7):
            cp[i] = ("class", struct.unpack(">H", data[pos:pos + 2])[0])
            pos += 2
        elif tag == CONSTANT_NameAndType:
            cp[i] = ("nat", struct.unpack(">H", data[pos:pos + 2])[0],
                     struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        elif tag in (CONSTANT_Methodref, CONSTANT_InterfaceMethodref):
            cp[i] = ("ref", struct.unpack(">H", data[pos:pos + 2])[0],
                     struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        elif tag in (8, 16, 19, 20):  # String, MethodType, Module, Package
            cp[i] = ("idx", struct.unpack(">H", data[pos:pos + 2])[0])
            pos += 2
        elif tag in (15,):  # MethodHandle
            cp[i] = ("mh", data[pos], struct.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (3, 4):  # Integer, Float
            cp[i] = ("num", data[pos:pos + 4])
            pos += 4
        elif tag in (5, 6):  # Long, Double
            cp[i] = ("num", data[pos:pos + 8])
            pos += 8
            i += 1  # 占两个槽
        elif tag in (9, 17, 18):  # Fieldref, Dynamic, InvokeDynamic
            cp[i] = ("fref", struct.unpack(">H", data[pos:pos + 2])[0],
                     struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        else:
            return None
        i += 1
    return cp


def utf8(cp, idx):
    entry = cp[idx] if 0 <= idx < len(cp) else None
    if entry and entry[0] == "utf8":
        return entry[1].decode("utf-8", "backslashreplace")
    return None


def class_name(cp, idx):
    entry = cp[idx] if 0 <= idx < len(cp) else None
    if entry and entry[0] == "class":
        return utf8(cp, entry[1])
    return None


def main():
    owner = sys.argv[1]
    want_method = sys.argv[2] if len(sys.argv) > 2 else None

    z = zipfile.ZipFile(HOST)
    hits = []
    total = 0
    for info in z.infolist():
        name = info.filename
        if not name.endswith(".class"):
            continue
        total += 1
        try:
            data = z.read(info)
        except Exception:
            continue
        cp = parse_cp(data)
        if not cp:
            continue
        for entry in cp:
            if not entry or entry[0] != "ref":
                continue
            ref_owner = class_name(cp, entry[1])
            if ref_owner != owner:
                continue
            nat = cp[entry[2]] if entry[2] < len(cp) else None
            if not nat or nat[0] != "nat":
                continue
            mname = utf8(cp, nat[1])
            if want_method and mname != want_method:
                continue
            hits.append((name, mname))
    z.close()
    print("scanned:", total, "hits:", len(hits))
    for name, mname in hits:
        safe = name.encode("ascii", "backslashreplace").decode("ascii")
        print(f"{safe}\t{mname}")


if __name__ == "__main__":
    main()
