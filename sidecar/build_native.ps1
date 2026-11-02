# 构建 Rust 边车（kugou_server.dll）与 JNI 桥接层（kugou_bridge.dll）
#
# 产物放到 src/main/resources/native/，供 Gradle 打进 .spmod。
#
# 为什么用脚本而不是 Gradle Exec 任务：
#   1. 需要按顺序构建两个产物，且桥接层依赖 MSVC 环境（vcvars64.bat）；
#   2. Rust 构建需要专门的 CARGO_HOME/RUSTUP_HOME（本机安全策略限制）；
#   3. 失败信息要可读 —— 用脚本能把「缺 rustup」「缺 VS」「TEMP 不可写」
#      这类环境问题讲清楚，而不是抛一堆 cargo 日志。
#
# 用法：
#   .\build_native.ps1              # 构建两个 dll 并放到 resources/native/
#   .\build_native.ps1 -SkipRust    # 只重建桥接层（改 C 代码时）
#   .\build_native.ps1 -SkipBridge  # 只重建 Rust 侧

param(
    [switch]$SkipRust,
    [switch]$SkipBridge,
    [string]$RustHome = "",
    [string]$TempForLink = ""
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$RustDir = Join-Path $PSScriptRoot 'rust'
$BridgeDir = Join-Path $PSScriptRoot 'bridge'
$OutDir = Join-Path $RepoRoot 'src\main\resources\native'

Write-Host "=== 构建原生组件 ===" -ForegroundColor Cyan
Write-Host "  仓库根: $RepoRoot"
Write-Host "  输出到: $OutDir"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---------------------------------------------------------------- Rust
if (-not $SkipRust) {
    Write-Host "`n--- [1/2] Rust 边车 ---" -ForegroundColor Cyan

    # 定位 cargo。默认装到工作区（本机安全策略不允许写 C:\Users\<u>\.cargo\bin）。
    if (-not $RustHome) {
        $RustHome = Join-Path (Split-Path -Parent $RepoRoot) 'build-research\rust-home'
    }
    $CargoHome = Join-Path $RustHome 'cargo'
    $RustupHome = Join-Path $RustHome 'rustup'
    $CargoExe = Join-Path $CargoHome 'bin\cargo.exe'

    if (-not (Test-Path $CargoExe)) {
        throw @"
未找到 cargo：$CargoExe

请先安装 Rust 工具链：
  1. 下载 https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe
  2. 设置环境变量后静默安装（避开 C:\Users 写入限制）：
       `$env:CARGO_HOME  = '$CargoHome'
       `$env:RUSTUP_HOME = '$RustupHome'
       .\rustup-init.exe -y --default-toolchain stable-x86_64-pc-windows-msvc --profile minimal --no-modify-path

或用 -RustHome 指定已有安装位置。
"@
    }

    # MSVC 链接器需要可写的 TEMP。本机 %TEMP% 实测被安全策略拒绝写入
    # （link.exe 报 LNK1104），所以重定向到工作区。
    if (-not $TempForLink) {
        $TempForLink = Join-Path (Split-Path -Parent $RepoRoot) 'build-research\linktmp'
    }
    New-Item -ItemType Directory -Force -Path $TempForLink | Out-Null

    $env:CARGO_HOME = $CargoHome
    $env:RUSTUP_HOME = $RustupHome
    $env:TMP = $TempForLink
    $env:TEMP = $TempForLink
    $env:PATH = "$CargoHome\bin;$env:PATH"

    Push-Location $RustDir
    try {
        & $CargoExe build --release 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw "cargo build 失败，退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    $RustDll = Join-Path $RustDir 'target\release\kugou_server.dll'
    if (-not (Test-Path $RustDll)) { throw "Rust dll 未生成：$RustDll" }

    Copy-Item $RustDll $OutDir -Force
    $sz = [math]::Round((Get-Item $RustDll).Length / 1MB, 2)
    Write-Host "  ✓ kugou_server.dll ($sz MB) -> $OutDir" -ForegroundColor Green
} else {
    Write-Host "`n--- [1/2] Rust 边车：已跳过 ---" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- Bridge
if (-not $SkipBridge) {
    Write-Host "`n--- [2/2] JNI 桥接层 ---" -ForegroundColor Cyan

    $BridgeScript = Join-Path $BridgeDir 'build_bridge.ps1'
    $BridgeOut = Join-Path $BridgeDir 'build'
    & $BridgeScript -OutDir $BridgeOut 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "桥接层构建失败" }

    $BridgeDll = Join-Path $BridgeOut 'kugou_bridge.dll'
    if (-not (Test-Path $BridgeDll)) { throw "桥接 dll 未生成：$BridgeDll" }

    Copy-Item $BridgeDll $OutDir -Force
    $sz = [math]::Round((Get-Item $BridgeDll).Length / 1KB, 1)
    Write-Host "  ✓ kugou_bridge.dll ($sz KB) -> $OutDir" -ForegroundColor Green
} else {
    Write-Host "`n--- [2/2] JNI 桥接层：已跳过 ---" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 汇总
Write-Host "`n=== 完成 ===" -ForegroundColor Green
Get-ChildItem $OutDir -Filter '*.dll' | ForEach-Object {
    Write-Host ("  {0,-24} {1,10:N0} 字节" -f $_.Name, $_.Length)
}
