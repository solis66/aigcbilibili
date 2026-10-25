<#
.SYNOPSIS
    在宿主上启动前端 dev server（vue-cli-service serve），接口指向本机 gateway。

.DESCRIPTION
    vue/labilibili/vue.config.js 支持用环境变量切换代理目标（未设时行为不变，仍代理线上）：
      VUE_APP_API_TARGET=http://127.0.0.1:8200   → /api 转发到宿主 gateway，并去掉 /api 前缀
      VUE_APP_WS_TARGET =http://127.0.0.1:1688   → /wschat 转发到宿主 chat 的 WebSocket
    所以只要在启动前把这两个变量设好，源码一行都不用改。

    ⚠️ 两个端口坑：
      · dev server 默认 2023，**和前端容器同一个端口**。不要同时起，否则第二个会因端口占用失败。
      · 想只用前端容器（不跑 npm）也行，但容器内 nginx 的上游写的是 gateway:8200（容器名），
        指向的是容器网络里的网关，够不到宿主上的网关 —— 那种情况要么把 gateway 也放回容器，
        要么改 docker/frontend/nginx.conf 的 upstream（见手册 §5）。

.PARAMETER Port
    dev server 端口，默认 2023。

.EXAMPLE
    .\start-frontend.ps1
    .\start-frontend.ps1 -Port 2024
#>
[CmdletBinding()]
param(
    [int]$Port = 2023
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$vueDir = Join-Path $repoRoot 'vue\labilibili'

if (-not (Test-Path (Join-Path $vueDir 'node_modules'))) {
    throw 'vue/labilibili/node_modules 不存在，先跑 .\setup-frontend.ps1'
}

$nodeExe = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
if (-not $nodeExe) { throw 'PATH 上找不到 node.exe。' }
$nodeDir = Split-Path -Parent $nodeExe
$npmCmd = Join-Path $nodeDir 'npm.cmd'
$npmCli = Join-Path $nodeDir 'node_modules\npm\bin\npm-cli.js'

# 端口占用预检：2023 被前端容器占着是常见情况
$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $owner = (Get-Process -Id $busy[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    throw "端口 $Port 已被占用（$owner）。如果那是前端容器：docker compose stop frontend；或换 -Port。"
}

$env:VUE_APP_API_TARGET = 'http://127.0.0.1:8200'
$env:VUE_APP_WS_TARGET = 'http://127.0.0.1:1688'

Write-Host "dev server: http://localhost:$Port" -ForegroundColor Green
Write-Host '  /api    -> http://127.0.0.1:8200  (gateway，去掉 /api 前缀)' -ForegroundColor DarkGray
Write-Host '  /wschat -> http://127.0.0.1:1688  (chat WebSocket)' -ForegroundColor DarkGray
Write-Host '  Ctrl+C 停止' -ForegroundColor DarkGray
Write-Host ''

Set-Location $vueDir
if (Test-Path $npmCmd) {
    & $npmCmd run serve -- --port $Port
} elseif (Test-Path $npmCli) {
    & $nodeExe $npmCli run serve -- --port $Port
} else {
    throw "找不到 npm.cmd 或 npm-cli.js（node 目录：$nodeDir）"
}
