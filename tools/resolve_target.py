"""两遍扫描宿主归档，把「原始 Kotlin 名」解析成可直接用于 Hook 的三元组。

宿主归档里存在两种不同性质的名字：

1. **被调用方（callee）**：部分保留了 ASCII 原名（如 `AppNavigationKt.AppNavigation`），
   部分是混淆名（如 `androidx/compose/ui/ୡ` 里的 `Ϳ`）。
2. **调用方（caller）**：几乎全部混淆，这正是 Hook 需要的 `inClass`。

解析链路：

    原始类名/方法名
      └─(第 1 遍：常量池里的 LDC 源码标记 + 方法归属)→ 声明类 + 字节码方法名 + 描述符
            └─(第 2 遍：Methodref 指向「声明类.方法名」)→ 调用方（inClass）+ 调用次数

为什么不用 SMAP：SMAP 只能给出「这个类来自哪个 .kt」，给不出方法名；
LDC 标记形如 `com.xuncorp.voxzen.ui.main.titlebar.MainTitleBar (MainTitleBar.kt:78)`，
既给出原始类名、又给出源文件行号，且作为 UTF-8 常量留在声明类的常量池里，跨版本稳定。

用法：
    python resolve_target.py <原始类名> <原始方法名>
    python resolve_target.py --markers <子串>        只列标记，不做第 2 遍
"""
import sys, zipfile, struct, re

HOST = r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
MARKER = re.compile(r"^([A-Za-z_$][\w$.]*(?:\.[A-Za-z_$][\w$]*)+) \(([^()]+\.kt):(\d+)\)$")
COMPOSER = "Landroidx/compose/runtime/Composer;"


def parse_class(data):
    """解析 class 文件，返回 (this_name, methods, refs)。

    methods: [(method_name, descriptor, [ldc 字符串])]
    refs:    [(owner_class, method_name, descriptor)]  仅 Methodref / InterfaceMethodref
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None, None, None
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
        elif tag in (9, 10, 11, 12, 17, 18):
            if tag == 12:
                cp[i] = ("nat", struct.unpack(">H", data[pos:pos + 2])[0],
                         struct.unpack(">H", data[pos + 2:pos + 4])[0])
            elif tag in (10, 11):
                cp[i] = ("ref", struct.unpack(">H", data[pos:pos + 2])[0],
                         struct.unpack(">H", data[pos + 2:pos + 4])[0])
            pos += 4
        elif tag in (8, 16, 19, 20):
            cp[i] = ("idx", struct.unpack(">H", data[pos:pos + 2])[0])
            pos += 2
        elif tag == 15:
            cp[i] = ("mh", data[pos], struct.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        else:
            return None, None, None
        i += 1

    def utf8(idx, depth=0):
        # 注意：ldc 加载的字符串常量是 CONSTANT_String(tag 8)，它再指向 UTF-8 项，
        # 所以这里必须跟进一层，否则 ldc 取到的全是 None。
        e = cp[idx] if 0 <= idx < count else None
        if not e:
            return None
        if e[0] == "utf8":
            return e[1].decode("utf-8", "backslashreplace")
        if e[0] == "idx" and depth < 4:
            return utf8(e[1], depth + 1)
        return None

    def cls_name(idx):
        e = cp[idx] if 0 <= idx < count else None
        if e and e[0] == "class":
            return utf8(e[1])
        return None

    this_name = cls_name(struct.unpack(">H", data[pos + 2:pos + 4])[0])
    pos += 6  # access_flags(2) + this_class(2) + super_class(2)

    def skip_attrs(p):
        n = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        for _ in range(n):
            p += 2
            alen = struct.unpack(">I", data[p:p + 4])[0]
            p += 4 + alen
        return p

    ifc = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2 + ifc * 2
    fcount = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2
    for _ in range(fcount):
        pos = skip_attrs(pos + 6)

    methods = []
    mcount = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2
    for _ in range(mcount):
        mname = utf8(struct.unpack(">H", data[pos + 2:pos + 4])[0])
        mdesc = utf8(struct.unpack(">H", data[pos + 4:pos + 6])[0])
        p = pos + 6
        n = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        ldcs = []
        for _ in range(n):
            aname = utf8(struct.unpack(">H", data[p:p + 2])[0])
            alen = struct.unpack(">I", data[p + 2:p + 6])[0]
            body = p + 6
            p = body + alen
            if aname != "Code":
                continue
            code_len = struct.unpack(">I", data[body + 4:body + 8])[0]
            code = data[body + 8:body + 8 + code_len]
            # 只认 ldc(0x12) / ldc_w(0x13)；遇到其他字节就前进 1 字节。
            # 这条标记是长 ASCII 串，误判概率极低；不做完整指令表遍历。
            j = 0
            while j < len(code):
                op = code[j]
                if op == 0x12 and j + 1 < len(code):
                    s = utf8(code[j + 1])
                    if s:
                        ldcs.append(s)
                    j += 2
                elif op == 0x13 and j + 2 < len(code):
                    s = utf8(struct.unpack(">H", code[j + 1:j + 3])[0])
                    if s:
                        ldcs.append(s)
                    j += 3
                else:
                    j += 1
        methods.append((mname, mdesc, ldcs))
        pos = p

    refs = []
    for e in cp:
        if not e or e[0] != "ref":
            continue
        owner = cls_name(e[1])
        nat = cp[e[2]] if 0 <= e[2] < count else None
        if not owner or not nat or nat[0] != "nat":
            continue
        rname = utf8(nat[1])
        rdesc = utf8(nat[2])
        if rname and rdesc:
            refs.append((owner, rname, rdesc))
    return this_name, methods, refs


def split_desc(desc):
    """把 `(Ljava/lang/String;I)V` 拆成 (参数列表, 返回描述符)。"""
    params = []
    i = 1
    while i < len(desc) and desc[i] != ")":
        start = i
        while desc[i] == "[":
            i += 1
        if desc[i] == "L":
            i = desc.index(";", i) + 1
        else:
            i += 1
        params.append(desc[start:i])
    return params, desc[i + 1:]


def business_params(desc):
    """Hook 的 parameterTypes 只含业务参数：剔除 Composer 及其后的合成 int。"""
    params, _ = split_desc(desc)
    out = []
    for p in params:
        if p == COMPOSER:
            break
        out.append(p)
    return out


def esc(s):
    return s.encode("ascii", "backslashreplace").decode("ascii")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    original_cls, original_method = sys.argv[1], sys.argv[2]
    want_markers_only = original_cls == "--markers"
    needle = original_method if want_markers_only else original_cls
    needle_bytes = needle.encode("utf-8")

    z = zipfile.ZipFile(HOST)

    # ---------- 第 1 遍：找声明类 + 字节码方法名 ----------
    decls = []   # [(declaringClass, bytecodeMethod, descriptor, source, line)]
    marker_rows = []
    scanned = 0
    for info in z.infolist():
        if not info.filename.endswith(".class"):
            continue
        scanned += 1
        try:
            data = z.read(info)
        except Exception:
            continue
        if needle_bytes not in data:
            continue
        this_name, methods, _ = parse_class(data)
        if not this_name or not methods:
            continue
        for mname, mdesc, ldcs in methods:
            for s in ldcs:
                m = MARKER.match(s)
                if not m:
                    continue
                g_cls, g_src, g_line = m.group(1), m.group(2), int(m.group(3))
                if want_markers_only:
                    if needle not in g_cls:
                        continue
                    marker_rows.append((g_cls, mname, this_name, g_src, g_line, mdesc))
                else:
                    if g_cls != original_cls:
                        continue
                    if g_cls.rsplit(".", 1)[-1] != original_method:
                        continue
                    decls.append((this_name, mname, mdesc, g_src, g_line))

    if want_markers_only:
        print("scanned:", scanned, "markers:", len(marker_rows))
        for g_cls, mname, this_name, g_src, g_line, mdesc in marker_rows:
            print(f"{esc(g_cls)}  ->  declaringClass={esc(this_name)} "
                  f"method={esc(mname)} desc={mdesc} src={g_src}:{g_line}")
        z.close()
        return

    if not decls:
        print("NOT FOUND (no LDC marker matched):", esc(original_cls), original_method)
        z.close()
        sys.exit(1)

    print(f"target {esc(original_cls)}.{original_method} -> {len(decls)} declaration(s)")
    for this_name, mname, mdesc, src, line in decls:
        bp = business_params(mdesc)
        print(f"  declaringClass : {esc(this_name)}")
        print(f"  bytecodeMethod : {esc(mname)}")
        print(f"  descriptor     : {mdesc}")
        print(f"  businessParams : {bp if bp else '(none)'}")
        print(f"  source         : {src}:{line}")

    # ---------- 第 2 遍：找调用方（inClass）----------
    # 必须按**完整三元组 (owner, name, desc)** 匹配。
    # 只按 (owner, name) 匹配会把同名重载全部算进来，得出虚假的「多调用方」结论：
    # MainWindowContent 实际只有 1 个外部调用方，但按 name 匹配会报 12 个。
    targets = {(d[0], d[1], d[2]) for d in decls}
    callers = {}      # callerClass -> [(callerMethod, [marker strs])]
    for info in z.infolist():
        if not info.filename.endswith(".class"):
            continue
        try:
            data = z.read(info)
        except Exception:
            continue
        # 便宜预筛：类里必须出现目标声明类的内部名
        if not any(t[0].encode("utf-8") in data for t in targets):
            continue
        this_name, methods, refs = parse_class(data)
        if not this_name:
            continue
        hit = False
        for owner, rname, rdesc in refs:
            if (owner, rname, rdesc) in targets:
                hit = True
                break
        if not hit:
            continue
        # 注意：**不要排除自引用**。
        #
        # 早期版本这里有一句 `if any(this_name == t[0] ...): continue`，注释说
        # 「声明类自己引用同一函数不是宿主调用点」。这个判断是**错的**，并且直接导致
        # 一个真机故障：ModConfigScreen 的实际调用方有 2 个 ——
        #   1) androidx/compose/ui/ஏ（声明类自身，合成 lambda 里的转发调用）
        #   2) ComposableSingletons$AppNavigation_desktopKt（导航分发）
        # 排除自引用后只剩 1 个，工具报「inClass 唯一」，插件把 hook 挂到
        # AppNavigation 分发处；但宿主实际执行的是**声明类内部那条转发调用**，
        # 于是 hook 注册成功却永不触发（真机现象：导航到了正确路由，
        # 页面仍是宿主原生的「模组管理」，插件页从不出现）。
        #
        # 对 replaceComposable 来说，声明类内部的转发调用恰恰是最常见的真实调用点，
        # 必须计入。宁可多报让调用方自己去消歧，也不能漏报。
        per_method = []
        for mname, mdesc, ldcs in methods:
            ms = [s for s in ldcs if MARKER.match(s)]
            if ms:
                per_method.append((mname, ms))
        callers[this_name] = per_method
    z.close()

    print(f"\ncall sites: {len(callers)} caller class(es)")
    for caller, per_method in callers.items():
        print(f"  inClass={esc(caller)}")
        for mname, ms in per_method:
            for s in ms:
                print(f"    callerMethod={esc(mname)}  marker={s}")
    if len(callers) == 1:
        print("\n=> inClass 唯一，可直接用。")
    else:
        print("\n=> 调用方不唯一，需按 marker 消歧（见上）。")


if __name__ == "__main__":
    main()
