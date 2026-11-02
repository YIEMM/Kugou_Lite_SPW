"""枚举宿主 archive 里所有「源码标记」字符串（CONSTANT_String 常量池条目）。

不解析字节码，只读常量池，因此不会因异常表 / switch 指令而失败。

标记格式：`com.xuncorp.voxzen.ui.main.titlebar.MainTitleBar (MainTitleBar.kt:78)`
- 标记文本本身就是「包名 + "." + 函数名」，无需再找方法
- 出现在**声明类**的常量池里，所以 this_class 就是声明类

用法：
    python tools\\list_markers.py "<正则>" [archive路径]

输出（TSV）：
    声明类 \t 标记文本 \t 文件行号
"""

import re
import sys
import zipfile

# 常量池 tag -> 定长操作数长度
TAG_LEN = {
    3: 4,   # Integer
    4: 4,   # Float
    5: 8,   # Long
    6: 8,   # Double
    7: 2,   # Class
    8: 2,   # String
    9: 4,   # Fieldref
    10: 4,  # Methodref
    11: 4,  # InterfaceMethodref
    12: 4,  # NameAndType
    15: 3,  # MethodHandle
    16: 2,  # MethodType
    17: 4,  # Dynamic        (bootstrap_method_attr_index + name_and_type_index)
    18: 4,  # InvokeDynamic  (同上，两者都是 4 字节，写成 2 会错位)
    19: 2,  # Module
    20: 2,  # Package
}

DEFAULT_ARCHIVE = (
    r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
)

MARKER_RX = re.compile(r"^[A-Za-z_$][\w$]*(?:\.[\w$]+)+\s+\([\w$.]+\.kt:\d+\)$")


def read_cp(data):
    """返回 (utf8, string_idx, this_name)。

    注意：`this_class` 位于常量池**之后**，偏移不固定
    （magic 4 + minor 2 + major 2 + cp_count 2 = 10 起才是常量池），
    所以必须先把整个常量池扫完拿到结束偏移，再读 this_class。
    """
    utf8 = {}
    class_name_idx = {}
    string_idx = {}
    n = int.from_bytes(data[8:10], "big")
    i = 10
    idx = 1
    while idx < n:
        tag = data[i]
        i += 1
        if tag == 1:  # Utf8
            ln = int.from_bytes(data[i:i + 2], "big")
            i += 2
            utf8[idx] = data[i:i + ln].decode("utf-8", "replace")
            i += ln
        elif tag == 7:  # Class
            class_name_idx[idx] = int.from_bytes(data[i:i + 2], "big")
            i += 2
        elif tag == 8:  # String
            string_idx[idx] = int.from_bytes(data[i:i + 2], "big")
            i += 2
        elif tag in (5, 6):  # Long / Double 占两个槽
            i += TAG_LEN[tag]
            idx += 2
            continue
        else:
            i += TAG_LEN.get(tag, 0)
        idx += 1
    # 常量池结束：pos 指向 access_flags(2)，其后的 this_class(2) 才是本类
    this_idx = int.from_bytes(data[i + 2:i + 4], "big")
    this_name = utf8.get(class_name_idx.get(this_idx, 0), "?")
    return utf8, string_idx, this_name


def main():
    pat = sys.argv[1] if len(sys.argv) > 1 else ".*"
    archive = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_ARCHIVE
    rx = re.compile(pat)

    hits = []
    with zipfile.ZipFile(archive) as zf:
        for name in zf.namelist():
            if not name.endswith(".class") or name.startswith("META-INF/"):
                continue
            try:
                data = zf.read(name)
                utf8, string_idx, this_name = read_cp(data)
            except Exception:
                continue
            for _idx, uidx in string_idx.items():
                s = utf8.get(uidx, "")
                if not MARKER_RX.match(s) or not rx.search(s):
                    continue
                hits.append((this_name, s))

    seen = set()
    for this_name, s in sorted(hits):
        if (this_name, s) in seen:
            continue
        seen.add((this_name, s))
        m = re.search(r"\(([^)]+)\)$", s)
        loc = m.group(1) if m else "?"
        print(f"{this_name}\t{s}\t{loc}")
    print(f"# total {len(seen)} markers", file=sys.stderr)


if __name__ == "__main__":
    main()
