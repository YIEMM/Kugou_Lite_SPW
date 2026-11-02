#!/usr/bin/env python3
"""独立探针：扫描宿主归档，找出**真正**调用某个目标方法的全部类。

用途：验证 `resolve_target.py` 给出的「唯一调用方」是否可靠。
之前 `replaceComposable` 注册成功、但 hook 从未触发，怀疑真实调用方不止一个，
而解析工具漏掉了其中一个（于是 inClass 挂错了地方）。

做法：全量扫每个 class 的常量池，找 Methodref / InterfaceMethodref
(owner, name, descriptor) 三连完全匹配的条目，打印包含它的 this_class。
"""
import sys
import struct
import zipfile

JAR = r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"


def cp_parse(data, off):
    """解析常量池，返回 (entries, new_off)。entries[i] = (tag, payload...)"""
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    entries = [None] * count
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            ln = struct.unpack_from(">H", data, off)[0]
            off += 2
            entries[i] = (1, data[off:off + ln])
            off += ln
        elif tag in (7, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package
            entries[i] = (tag, struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag == 15:  # MethodHandle
            entries[i] = (15, struct.unpack_from(">H", data, off)[0],
                          struct.unpack_from(">H", data, off + 2)[0])
            off += 3
        elif tag in (3, 4):  # Integer/Float
            entries[i] = (tag, struct.unpack_from(">I", data, off)[0])
            off += 4
        elif tag in (5, 6):  # Long/Double
            entries[i] = (tag, struct.unpack_from(">Q", data, off)[0])
            off += 8
            i += 1  # 占两个槽
        elif tag in (9, 10, 11, 12, 17, 18):  # Field/Method/InterfaceMethod/NameAndType/Dynamic/InvokeDynamic
            entries[i] = (tag, struct.unpack_from(">H", data, off)[0],
                          struct.unpack_from(">H", data, off + 2)[0])
            off += 4
        else:
            raise ValueError("unknown cp tag %d at %d" % (tag, off))
        i += 1
    return entries, off


def utf8(entries, idx):
    while idx and entries[idx]:
        tag = entries[idx][0]
        if tag == 1:
            return entries[idx][1].decode("utf-8", "replace")
        if tag == 7:
            idx = entries[idx][1]
            continue
        return None
    return None


def class_name(entries, idx):
    e = entries[idx]
    if e and e[0] == 7:
        return utf8(entries, e[1])
    return None


def nat(entries, idx):
    e = entries[idx]
    if e and e[0] == 12:
        return utf8(entries, e[1]), utf8(entries, e[2])
    return None, None


def scan():
    owner = "androidx/compose/ui/" + chr(0x0B8F)
    method = chr(0x037F)
    viewmodel = "androidx/compose/ui/" + chr(0x0CA0)
    desc = ("(Lcom/xuncorp/voxzen/ui/navigation/ScreenRoute$ModConfigContent;"
            "L" + viewmodel + ";Landroidx/compose/runtime/Composer;II)V")

    want = (owner, method, desc)
    callers = {}
    total = 0
    with zipfile.ZipFile(JAR) as zf:
        names = [n for n in zf.namelist() if n.endswith(".class")]
        for n in names:
            total += 1
            try:
                data = zf.read(n)
            except Exception:
                continue
            if data[:4] != b"\xca\xfe\xba\xbe":
                continue
            try:
                entries, _ = cp_parse(data, 8)
            except Exception:
                continue
            this_name = None
            hits = 0
            for e in entries:
                if not e:
                    continue
                if e[0] in (10, 11):  # Methodref / InterfaceMethodref
                    cidx, ntidx = e[1], e[2]
                    mname, mdesc = nat(entries, ntidx)
                    if (class_name(entries, cidx), mname, mdesc) == want:
                        hits += 1
            if hits:
                # this_class 是常量池里第一个 tag=7 指向… 直接从结构读更稳
                callers[n] = hits

    print("扫描 class 总数：", total)
    print("目标：", owner, method, desc)
    print("真实调用方数量：", len(callers))
    for k, v in sorted(callers.items()):
        print("  %-4d %s" % (v, k))


if __name__ == "__main__":
    scan()
