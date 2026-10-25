<#
.SYNOPSIS
    在宿主上启动某个后端服务（中间件留在 Docker 里）。

.DESCRIPTION
    与容器模式的差别，只在这几件事上：
      1) 配置来源换成 docker/local-run/conf/<service>/（里面的地址全是 127.0.0.1）；
         走 --spring.config.additional-location，优先级**高于** jar 内的 classpath 配置，
         所以只写「与默认值不同的键」即可，其余（含网关的 routes 路由表）自动沿用。
      2) 端口用命令行参数**显式钉住**（--server.port）。原因：Windows 上如果存在
         SERVER_PORT 这类环境变量，其优先级高于配置文件，会让服务去抢一个莫名其妙的端口；
         而命令行参数是最高优先级，能盖过环境变量。踩过一次，故钉死。
      3) JVM 工作目录固定到 docker/local-run/，日志与临时文件都落在这里，不污染仓库根。
      4) user.home 指向 docker/local-run/.home（D-07：防 RocketMQ 客户端日志写到真实用户目录）。
      注：配置目录故意叫 conf/ 而不是 config/ —— Spring Boot 默认会**通配加载**
         file:./config/*/，若用 config/<service>/，6 份配置会互相串味（每个服务都读到别人的键）。

.PARAMETER Service
    gateway / notice / user-center / video / chat / search

.PARAMETER JavaExe
    用哪个 java.exe，不传则用 PATH 上的。
    项目口径是 **Java 8**（D-04）；若检测到大版本 ≥ 9，脚本会补 --add-opens 并打印警告。

.PARAMETER Heap
    -Xmx 值，默认 512m。宿主要同时跑 6 个服务 + Docker 里的中间件，别调太大。

.EXAMPLE
    .\run-backend.ps1 -Service gateway
    .\run-backend.ps1 -Service notice -Heap 768m
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('gateway', 'notice', 'user-center', 'video', 'chat', 'search')]
    [string]$Service,

    [string]$JavaExe,
    [string]$Heap = '512m'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # docker/local-run -> 仓库根
$moduleMap = @{
    'gateway'     = 'gateway'
    'notice'      = 'notice'
    'user-center' = 'user_center'
    'video'       = 'video'
    'chat'        = 'chat'
    'search'      = 'search'
}
# 宿主侧端口：与容器模式保持一致，网关路由 / 前端代理都不用改
$portMap = @{
    'gateway'     = 8200
    'notice'      = 30000
    'user-center' = 3000
    'video'       = 10201
    'chat'        = 1688
    'search'      = 8201
}

$module = $moduleMap[$Service]
$port = $portMap[$Service]
$jar = Join-Path $repoRoot "java\$module\target\$module-1.0.jar"

if (-not (Test-Path $jar)) {
    throw "没找到 jar：$jar`n先构建： .\build-backend.ps1 -Service $Service"
}

if (-not $JavaExe) {
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'PATH 上找不到 java.exe，用 -JavaExe 指定完整路径。' }
    $JavaExe = $cmd.Source
}
if (-not (Test-Path $JavaExe)) { throw "找不到 java.exe：$JavaExe" }

# --- 判定 JDK 大版本：读 JDK 自带的 release 文件，比解析 `java -version` 的输出可靠 ---
$javaMajor = 8
$javaHome = Split-Path -Parent (Split-Path -Parent $JavaExe)
$releaseFile = Join-Path $javaHome 'release'
if (Test-Path $releaseFile) {
    $hit = Select-String -Path $releaseFile -Pattern 'JAVA_VERSION="([^"]+)"' | Select-Object -First 1
    if ($hit) {
        $v = $hit.Matches[0].Groups[1].Value
        $javaMajor = if ($v -like '1.*') { [int]$v.Split('.')[1] } else { [int]$v.Split('.')[0] }
    }
}

# --- 工作目录与运行期目录 ---
Set-Location $PSScriptRoot
foreach ($d in @('.home', 'var\temp', 'logs')) {
    $p = Join-Path $PSScriptRoot $d
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
}

$jvmArgs = @(
    "-Duser.home=$PSScriptRoot\.home",
    '-Dfile.encoding=UTF-8',
    '-Xms256m',
    "-Xmx$Heap"
)

if ($javaMajor -ge 9) {
    Write-Host "警告：当前 java 是 JDK $javaMajor（$JavaExe）" -ForegroundColor Yellow
    Write-Host '      项目口径是 Java 8（D-04，容器运行镜像是 temurin:8-jre），JDK 9+ 属未验证组合。' -ForegroundColor Yellow
    Write-Host '      实测 JDK 17 下 Spring 上下文能正常起来（2026-09-22 已验证），但长期建议装 JDK 8。' -ForegroundColor Yellow
    Write-Host '      已自动补 --add-opens 兜底。' -ForegroundColor Yellow
    $jvmArgs += @(
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
        '--add-opens=java.base/java.util=ALL-UNNAMED',
        '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
        '--add-opens=java.base/java.io=ALL-UNNAMED',
        '--add-opens=java.base/java.text=ALL-UNNAMED'
    )
}

$appArgs = @(
    "--spring.config.additional-location=file:./conf/$Service/",
    "--server.port=$port"
)

Write-Host "启动 $Service  (module=$module, java=$javaMajor, heap=$Heap, port=$port)" -ForegroundColor Green
Write-Host "  配置覆盖： $(Join-Path $PSScriptRoot "conf\$Service")" -ForegroundColor DarkGray
Write-Host "  工作目录： $PSScriptRoot" -ForegroundColor DarkGray
Write-Host '  Ctrl+C 停止' -ForegroundColor DarkGray
Write-Host ''

& $JavaExe @jvmArgs -jar $jar @appArgs
