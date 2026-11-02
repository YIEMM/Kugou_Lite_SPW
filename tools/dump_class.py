"""把宿主归档里指定的类按「类自身的 this_class 名」导出成 ASCII 文件名的 .class，便于 javap 反汇编。

为什么不用 zip 条目名：宿主归档的条目名在非 UTF-8 标志位下会被不同工具按不同代码页解码，
直接按名字匹配会 miss。类自己的 this_class 是常量池 UTF-8，永远准确。

用法：python dump_class.py <internal-name-with-slashes> <out-file>
"""
import sys, zipfile, struct, re

HOST = r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"


def unescape(name):
    """把参数里的 `U+XXXX` / `\\uXXXX` 还原成真实字符。

    控制台代码页（GBK）传不了混淆后的 Unicode 类名，所以约定用转义写法：
        androidx/compose/ui/U+0A72
    也兼容 Python 风格的 `\\u0a72`。
    """
    def sub_u_plus(m):
        return chr(int(m.group(1), 16))

    def sub_py(m):
        return chr(int(m.group(1), 16))

    name = re.sub(r"U\+([0-9A-Fa-f]{4,6})", sub_u_plus, name)
    name = re.sub(r"\\u([0-9A-Fa-f]{4})", sub_py, name)
    return name


def read_cp(data):
    count = struct.unpack(">H", data[8:10])[0]
    cp = [None] * count
    pos = 10
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            ln = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            cp[i] = ("utf8", data[pos:pos + ln])
            pos += ln
        elif tag == 7:
            cp[i] = ("class", struct.unpack(">H", data[pos:pos + 2])[0])
            pos += 2
        elif tag == 12:
            cp[i] = ("nat", struct.unpack(">H", data[pos:pos + 2])[0], struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        elif tag in (10, 11):
            cp[i] = ("ref", struct.unpack(">H", data[pos:pos + 2])[0], struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        elif tag in (8, 16, 19, 20):
            cp[i] = ("idx", struct.unpack(">H", data[pos:pos + 2])[0])
            pos += 2
        elif tag == 15:
            cp[i] = ("mh", data[pos], struct.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (3, 4):
            cp[i] = ("num", data[pos:pos + 4])
            pos += 4
        elif tag in (5, 6):
            cp[i] = ("num", data[pos:pos + 8])
            pos += 8
            i += 1
        elif tag in (9, 17, 18):
            cp[i] = ("fref", struct.unpack(">H", data[pos:pos + 2])[0], struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        else:
            return None, None
        i += 1
    this_idx = struct.unpack(">H", data[pos + 2:pos + 4])[0]  # access_flags 之后就是 this_class
    entry = cp[this_idx]
    if not entry or entry[0] != "class":
        return cp, None
    cls_entry = cp[entry[1]]
    if not cls_entry or cls_entry[0] != "utf8":
        return cp, None
    return cp, cls_entry[1].decode("utf-8")


def main():
    want = unescape(sys.argv[1])
    out = sys.argv[2]
    z = zipfile.ZipFile(HOST)
    for info in z.infolist():
        if not info.filename.endswith(".class"):
            continue
        try:
            data = z.read(info)
        except Exception:
            continue
        if data[:4] != b"\xca\xfe\xba\xbe":
            continue
        _, selfname = read_cp(data)
        if selfname != want:
            continue
        with open(out, "wb") as f:
            f.write(data)
        z.close()
        print("wrote", out, "from entry", info.filename.encode("ascii", "backslashreplace").decode())
        return
    z.close()
    print("NOT FOUND:", want.encode("ascii", "backslashreplace").decode())
    sys.exit(1)


if __name__ == "__main__":
    main()
