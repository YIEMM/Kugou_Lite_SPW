# Rust 工具链环境（本机安装位置 D:\Appdata）
#
# ## 为什么需要这个文件
#
# rustup 装到了 `D:\Appdata\cargo` + `D:\Appdata\rustup`（C 盘只剩 9 GB），
# 所以 `CARGO_HOME` / `RUSTUP_HOME` 必须显式指定，否则 rustup 会去找
# `%USERPROFILE%\.cargo`（那里是空目录），报
# "could not choose a version of cargo to run"。
#
# 四个变量已注册到**用户级环境变量**（注册表 HKCU\Environment），
# 新开的终端会自动继承。但如果当前 shell 是注册之前启动的，
# 需要手动 dot-source 本文件或用 `refreshenv`。
#
# ## 用法
#
#   . .\rust-env.ps1          # PowerShell
#   cargo build --release
#
# 镜像用 rsproxy.cn（字节跳动）：清华/阿里云的 dist 路径与 rustup 1.29
# 的 channel 协议不兼容（报 "no release found for 'stable'"），
# rsproxy 是唯一实测可用的。

$env:CARGO_HOME = 'D:\Appdata\cargo'
$env:RUSTUP_HOME = 'D:\Appdata\rustup'
$env:RUSTUP_DIST_SERVER = 'https://rsproxy.cn'
$env:RUSTUP_UPDATE_ROOT = 'https://rsproxy.cn/rustup'

# 把 cargo\bin 放最前，优先于可能存在的旧 shim
$cargoBin = 'D:\Appdata\cargo\bin'
if (($env:Path -split ';') -notcontains $cargoBin) {
    $env:Path = "$cargoBin;$env:Path"
}
