<#
.SYNOPSIS
    停止混合模式的中间件容器。

.DESCRIPTION
    默认只 stop（保留容器与数据卷），下次 start-middleware.ps1 秒起。

.PARAMETER Remove
    改成 docker compose down（删容器与网络，**保留命名卷与数据**）。

.PARAMETER Purge
    ⚠️ 危险：docker compose down -v —— 连同命名卷一起删，MySQL 里 bilibili / xxl_job
    的全部数据会消失，下次 up 会重跑 docker/mysql/init/ 里的建表脚本回到空库。
    需要输入 DELETEVOLUME 二次确认。
#>
[CmdletBinding()]
param(
    [switch]$Remove,
    [switch]$Purge
)

$ErrorActionPreference = 'Stop'

$dockerDir = Split-Path -Parent $PSScriptRoot
Set-Location $dockerDir

$composeArgs = @('-f', 'docker-compose.yml', '-f', 'docker-compose.middleware-host.yml')

if ($Purge) {
    Write-Host '⚠️  this will DELETE all named volumes (MySQL data included).' -ForegroundColor Red
    $answer = Read-Host 'Type DELETEVOLUME to confirm'
    if ($answer -cne 'DELETEVOLUME') {
        Write-Host '已取消，什么都没做。' -ForegroundColor Yellow
        return
    }
    docker compose @composeArgs down -v
} elseif ($Remove) {
    docker compose @composeArgs down
} else {
    docker compose @composeArgs stop
}

Write-Host "exit=$LASTEXITCODE" -ForegroundColor Green
