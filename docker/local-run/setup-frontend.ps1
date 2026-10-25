<#
.SYNOPSIS
    把 vue/labilibili 装成「能在宿主机直接跑 dev server」的状态。

.DESCRIPTION
    做两件事，等价于 docker/frontend/Dockerfile 里 FIX-2 在镜像构建层做的处理：

      1) 把 package.json 里被硬锁的 "webpack": "4.0.0" 改成 "^5.89.0"
         根因：@vue/cli-service ~5.0.0 + vue-loader 17 走 webpack 5 API，而 webpack 4 里没有
         webpack/lib/rules/BasicEffectRulePlugin 这个模块 → serve/build 直接崩：
           Error: Cannot find module 'webpack/lib/rules/BasicEffectRulePlugin'
         这是上游 package.json 的 bug，与包管理器无关（换 pnpm/yarn 一样崩）。
         该改动的 diff 只有一行，原文件备份为 package.json.bak，可随时回滚。

      2) npm install --legacy-peer-deps（默认走 npmmirror 源加速）

.PARAMETER Registry
    npm 源，默认 https://registry.npmmirror.com。想用官方源就传 https://registry.npmjs.org

.EXAMPLE
    .\setup-frontend.ps1
#>
[CmdletBinding()]
param(
    [string]$Registry = 'https://registry.npmmirror.com'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$vueDir = Join-Path $repoRoot 'vue\labilibili'
$pkg = Join-Path $vueDir 'package.json'

if (-not (Test-Path $pkg)) { throw "找不到 $pkg" }

# ---- 1) 修 webpack 版本 pin（文本级替换，保持文件其余部分与缩进原样，且不写 BOM）----
$raw = [System.IO.File]::ReadAllText($pkg, [System.Text.Encoding]::UTF8)
if ($raw -match '"webpack"\s*:\s*"4\.0\.0"') {
    Copy-Item $pkg "$pkg.bak" -Force
    $new = $raw -replace '"webpack"\s*:\s*"4\.0\.0"', '"webpack": "^5.89.0"'
    [System.IO.File]::WriteAllText($pkg, $new, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host '已把 webpack 4.0.0 -> ^5.89.0（原文件备份 package.json.bak）' -ForegroundColor Green
} elseif ($raw -match '"webpack"\s*:\s*"\^5') {
    Write-Host 'webpack 版本已是 ^5.x，跳过修改。' -ForegroundColor DarkGray
} else {
    Write-Host '警告：package.json 里的 webpack 既不是 4.0.0 也不是 ^5.x，请人工确认。' -ForegroundColor Yellow
}

# ---- 2) npm install ----
$nodeExe = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
if (-not $nodeExe) { throw 'PATH 上找不到 node.exe。' }
$nodeDir = Split-Path -Parent $nodeExe

$npmCmd = Join-Path $nodeDir 'npm.cmd'
$npmCli = Join-Path $nodeDir 'node_modules\npm\bin\npm-cli.js'

Set-Location $vueDir
if (Test-Path $npmCmd) {
    Write-Host "npm install（$npmCmd）..." -ForegroundColor Cyan
    & $npmCmd install --legacy-peer-deps "--registry=$Registry"
} elseif (Test-Path $npmCli) {
    Write-Host "npm install（node + npm-cli.js）..." -ForegroundColor Cyan
    & $nodeExe $npmCli install --legacy-peer-deps "--registry=$Registry"
} else {
    throw "找不到 npm.cmd 或 npm-cli.js（node 目录：$nodeDir），请手动执行 npm install --legacy-peer-deps"
}

Write-Host "exit=$LASTEXITCODE" -ForegroundColor Green
Write-Host '装完用 .\start-frontend.ps1 起 dev server（会自动把接口指到宿主 gateway）。' -ForegroundColor Green
