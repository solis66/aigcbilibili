<#
.SYNOPSIS
    用宿主本机的 Maven 构建后端 jar（复用仓库里的依赖种子，基本不下载）。

.DESCRIPTION
    容器构建用的是 maven:3.8.6-openjdk-8 镜像；宿主上走本机 maven，两个参数是必带项：
      -s / -gs  docker/maven/settings.xml
                本机全局 Maven 配置指向一个不存在的路径，不显式指定 settings 会直接构建失败。
                该文件同时提供 aliyun 镜像加速。
      -Dmaven.repo.local=<仓库根>/.m2-repo
                复用仓库内约 318MB 的依赖种子（D-04），首构建零依赖下载。

.PARAMETER Service
    不传 = 构建全部模块（含 common）。传单个服务名 = 只构建该模块及其依赖（-pl <module> -am）。

.EXAMPLE
    .\build-backend.ps1
    .\build-backend.ps1 -Service notice
    .\build-backend.ps1 -Service user-center
#>
[CmdletBinding()]
param(
    [ValidateSet('', 'gateway', 'notice', 'user-center', 'video', 'chat', 'search')]
    [string]$Service = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$moduleMap = @{
    'gateway'     = 'gateway'
    'notice'      = 'notice'
    'user-center' = 'user_center'
    'video'       = 'video'
    'chat'        = 'chat'
    'search'      = 'search'
}

$settings = Join-Path $repoRoot 'docker\maven\settings.xml'
$repoLocal = Join-Path $repoRoot '.m2-repo'
$pom = Join-Path $repoRoot 'java\pom.xml'

if (-not (Test-Path $settings)) { throw "缺少 $settings" }
if (-not (Test-Path $repoLocal)) { Write-Host "提示：$repoLocal 不存在，将全量下载依赖。" -ForegroundColor Yellow }

$mvnArgs = @(
    '-f', $pom,
    '-DskipTests',
    '-s', $settings,
    '-gs', $settings,
    ("-Dmaven.repo.local=" + $repoLocal)
)
if ($Service) {
    $mvnArgs += @('-pl', $moduleMap[$Service], '-am')
    $target = $moduleMap[$Service]
} else {
    $target = '全部模块'
}
$mvnArgs += 'package'

Write-Host "mvn package -> $target" -ForegroundColor Green
Write-Host "  pom        ：$pom" -ForegroundColor DarkGray
Write-Host "  依赖仓库   ：$repoLocal" -ForegroundColor DarkGray
Write-Host ''

Set-Location $repoRoot
mvn @mvnArgs
Write-Host "exit=$LASTEXITCODE" -ForegroundColor Green
Write-Host '产物： java/<module>/target/<module>-1.0.jar' -ForegroundColor Green
