"""扫描宿主归档，按「常量池里的 LDC 源码标记」建立 原始名 → 混淆类名 的索引。

Kotlin 编译器会在方法体里插入一条 LDC 字符串，形如：

    com.xuncorp.voxzen.ui.main.titlebar.MainTitleBar (MainTitleBar.kt:374)

这条标记作为 UTF-8 常量**留在声明它的那个类的常量池里**。所以：

- 「哪个混淆类声明了 `MainTitleBar`」→ 找常量池含该标记的类
- 标记本身跨版本稳定（宿主重排混淆名时它不变）

只解析常量池，不解析字节码 —— 比遍历 Code 属性稳健得多。

用法：
    python resolve_markers.py --list                列出全部可解析的声明目标
    python resolve_markers.py <原始类名> <原始方法名>   精确解析一个目标
    python resolve_markers.py --grep <子串>          按子串列出候选
"""
import sys, zipfile, struct, re

HOST = r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"

MARKER = re.compile(r"^([A-Za-z_$][\w$.]*(?:\.[A-Za-z_$][\w$]*)+) \(([^()]+\.kt):(\d+)\)$")


def read_pool(data):
    """返回 (this_class_name, [utf8 字符串]) —— 只解析常量池。"""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None, None
    count = struct.unpack(">H", data[8:10])[0]
    cp = [None] * count
    pos = 10
    i = 1
    try:
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
                pos += 4
            elif tag in (10, 11, 9, 17, 18):
                pos += 4
            elif tag in (8, 16, 19, 20):
                pos += 2
            elif tag == 15:
                pos += 3
            elif tag in (3, 4):
                pos += 4
            elif tag in (5, 6):
                pos += 8
                i += 1
            else:
                return None, None
            i += 1
        this_idx = struct.unpack(">H", data[pos + 2:pos + 4])[0]
        entry = cp[this_idx]
        this_name = None
        if entry and entry[0] == "class":
            ce = cp[entry[1]]
            if ce and ce[0] == "utf8":
                this_name = ce[1].decode("utf-8", "backslashreplace")
        utf8s = [e[1].decode("utf-8", "backslashreplace") for e in cp if e and e[0] == "utf8"]
        return this_name, utf8s
    except Exception:
        return None, None


def main():
    z = zipfile.ZipFile(HOST)
    index = {}      # (原始类名, 原始方法名) -> [(声明它的混淆类名, 源文件, 行号)]
    scanned = 0
    for info in z.infolist():
        if not info.filename.endswith(".class"):
            continue
        scanned += 1
        try:
            data = z.read(info)
        except Exception:
            continue
        this_name, utf8s = read_pool(data)
        if not this_name or not utf8s:
            continue
        for s in utf8s:
            m = MARKER.match(s)
            if not m:
                continue
            original, source, line = m.group(1), m.group(2), int(m.group(3))
            # 标记里的最后一段就是方法名
            method = original.rsplit(".", 1)[-1]
            index.setdefault((original, method), []).append((this_name, source, line))
    z.close()

    def esc(s):
        return s.encode("ascii", "backslashreplace").decode("ascii")

    args = sys.argv[1:]
    if args and args[0] == "--list":
        print("scanned:", scanned, "declaring classes:", len({v[0][0] for v in index.values()}),
              "targets:", len(index))
        for key in sorted(index.keys()):
            original, method = key
            hits = index[key]
            print(f"{esc(original)}.{method}\t{len(hits)} decl\t{esc(hits[0][0])}\t{hits[0][1]}:{hits[0][2]}")
        return

    if args and args[0] == "--grep":
        needle = args[1].lower()
        print("scanned:", scanned)
        for key in sorted(index.keys()):
            original, method = key
            if needle in original.lower() or needle in method.lower():
                hits = index[key]
                print(f"{esc(original)}.{method}\t{len(hits)} decl\t{esc(hits[0][0])}\t{hits[0][1]}:{hits[0][2]}")
        return

    if len(args) >= 2:
        want_cls, want_m = args[0], args[1]
        hits = index.get((want_cls, want_m))
        if not hits:
            # 退一步：只按方法名 + 类名后缀匹配
            cands = [(k, v) for k, v in index.items() if k[1] == want_m and k[0].endswith(want_cls)]
            if not cands:
                print("NOT FOUND:", want_cls, want_m)
                sys.exit(1)
            hits = cands[0][1]
        print(f"{esc(want_cls)}.{want_m} -> {len(hits)} decl(s)")
        for this_name, source, line in hits:
            print(f"  declaringClass={esc(this_name)}")
            print(f"  source={source}:{line}")
        return

    print(__doc__)
    sys.exit(1)


if __name__ == "__main__":
    main()
