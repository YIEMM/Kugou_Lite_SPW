# 构建 JNI 桥接 dll（kugou_bridge.dll）
#
# 为什么单独写脚本而不用 Cargo：
#   桥接层只是薄薄一层转发（~200 行 C），不需要整个 Rust 工具链。
#   用 MSVC 的 cl.exe 直接编译最快，也避免为它引入 build.rs。
#
# 前置：需要 Visual Studio 的 C++ 工具链（cl.exe + jni.h）
#   jni.h 来自 JDK 的 include 目录，脚本自动从 $env:JAVA_HOME 找。
#
# 用法：
#   .\build_bridge.ps1
#   .\build_bridge.ps1 -OutDir 'C:\dist'

param(
    [string]$OutDir = ""
)

$ErrorActionPreference = 'Stop'

$Here = $PSScriptRoot
$Source = Join-Path $Here 'kugou_bridge.c'

if (-not (Test-Path $Source)) {
    throw "源文件缺失：$Source"
}

# ---- 定位 JDK 的 jni.h --------------------------------------------------
if (-not $env:JAVA_HOME) {
    throw "未设置 JAVA_HOME；JNI 编译需要 JDK 的 include 目录（jni.h）"
}
$JniInclude = Join-Path $env:JAVA_HOME 'include'
$JniIncludeWin = Join-Path $JniInclude 'win32'

foreach ($p in @($JniInclude, $JniIncludeWin)) {
    if (-not (Test-Path $p)) { throw "缺少 JNI 头文件目录：$p" }
}

# ---- 定位 MSVC ----------------------------------------------------------
$VsWhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $VsWhere)) {
    throw "未找到 vswhere；请安装 Visual Studio（含「使用 C++ 的桌面开发」工作负载）"
}

$VsPath = & $VsWhere -latest -products * -property installationPath 2>$null
if (-not $VsPath) {
    throw "vswhere 未返回 VS 安装路径"
}

# 找最新的 MSVC 工具集
$MsvcRoot = Get-ChildItem (Join-Path $VsPath 'VC\Tools\MSVC') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $MsvcRoot) {
    throw "未找到 MSVC 工具集（VC\Tools\MSVC）"
}

$ClExe = Join-Path $MsvcRoot.FullName 'bin\HostX64\x64\cl.exe'
if (-not (Test-Path $ClExe)) {
    throw "未找到 cl.exe：$ClExe"
}
Write-Host "MSVC: $($MsvcRoot.Name)" -ForegroundColor Cyan

# ---- 编译 ---------------------------------------------------------------
# 需要先进入 VS 开发者环境才有 INCLUDE/LIB 环境变量。
# 用 vcvars64.bat 一次性导入，比手工拼 INCLUDE 路径稳。
$VcVars = Join-Path $VsPath 'VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path $VcVars)) {
    throw "未找到 vcvars64.bat：$VcVars"
}

$BuildDir = Join-Path $Here 'build'
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$OutDll = Join-Path $BuildDir 'kugou_bridge.dll'

# 用 cmd 串联 vcvars + cl，确保环境变量生效
$Cmd = @"
call "$VcVars" >nul 2>&1
"$ClExe" /nologo /O2 /MT /LD /W3 /Fe:"$OutDll" "$Source" /I"$JniInclude" /I"$JniIncludeWin" /link /DEFAULTLIB:user32.lib
"@

Write-Host "编译中…" -ForegroundColor Cyan
$TmpCmd = Join-Path $BuildDir 'build.cmd'
Set-Content -Path $TmpCmd -Value $Cmd -Encoding ASCII

& cmd.exe /c "`"$TmpCmd`"" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) {
    throw "cl.exe 编译失败，退出码 $LASTEXITCODE"
}

if (-not (Test-Path $OutDll)) {
    throw "dll 未生成：$OutDll"
}

$Size = [math]::Round((Get-Item $OutDll).Length / 1KB, 1)
Write-Host "Built: $OutDll ($Size KB)" -ForegroundColor Green

if ($OutDir) {
    # 调用方可能把 -OutDir 指到 build/（本目录），此时源与目标是同一文件，
    # Copy-Item 会报 "Cannot overwrite the item ... with itself"，需要先判等。
    $srcFull = [System.IO.Path]::GetFullPath($OutDll)
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $dstFull = [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path $OutDir).Path 'kugou_bridge.dll'))

    if ($dstFull -eq $srcFull) {
        Write-Host "输出目录即产物所在目录，跳过复制：$OutDll" -ForegroundColor Yellow
    } else {
        Copy-Item $OutDll $OutDir -Force
        Write-Host "Copied to: $OutDir" -ForegroundColor Green
    }
}
