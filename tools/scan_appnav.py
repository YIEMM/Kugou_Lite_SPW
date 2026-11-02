"""扫描宿主归档，找出所有引用 AppNavigation 的 class（含被混淆到假包的类）。

只做常量池 UTF8 原始字节搜索：快、无需完整 class 解析。
"""
import sys, zipfile, re

HOST = r"E:\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
NEEDLES = [b"AppNavigation", b"NavigationContent", b"MainWindowContent", b"MainTitleBar"]

def main():
    z = zipfile.FileArchive = None
    z = zipfile.ZipFile(HOST)
    hits = {}
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
        found = [n.decode() for n in NEEDLES if n in data]
        if found:
            hits[name] = found
    z.close()
    print("scanned classes:", total)
    print("hit classes:", len(hits))
    for name, found in sorted(hits.items()):
        # 宿主把混淆类塞进 androidx.compose.ui.* 假包，名字是 Unicode 码点，GBK 控制台打不出来
        safe = name.encode("ascii", "backslashreplace").decode("ascii")
        print(f"{safe}\t{','.join(found)}")

if __name__ == "__main__":
    main()
