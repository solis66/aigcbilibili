<#
.SYNOPSIS
    混合模式：只把中间件起在 Docker 里（6 个后端与前端跑在宿主）。

.DESCRIPTION
    用 docker-compose.middleware-host.yml 覆盖层启动，等价于：

      cd docker
      docker compose -f docker-compose.yml -f docker-compose.middleware-host.yml up -d `
          mysql redis minio minio-init rocketmq-namesrv rocketmq-store-init rocketmq-broker `
          elasticsearch

    与全套容器模式的区别只有两点（都写在 override 文件里，不改主 compose）：
      · redis 发布到宿主 16379（宿主 6379 被常驻 redis-server 占着）
      · rocketmq-broker 改用 broker.host.conf，brokerIP1 注册成 127.0.0.1，宿主可回连

.PARAMETER Core
    只起「跑通网关→微服务→MySQL」最小链路所需的 7 个（跳过 elasticsearch，
    约省 2.5GB 内存）。此时网关的 /search/** 路由会 502，属预期。

.EXAMPLE
    .\start-middleware.ps1
    .\start-middleware.ps1 -Core
#>
[CmdletBinding()]
param(
    [switch]$Core
)

$ErrorActionPreference = 'Stop'

$dockerDir = Split-Path -Parent $PSScriptRoot
Set-Location $dockerDir

$composeArgs = @('-f', 'docker-compose.yml', '-f', 'docker-compose.middleware-host.yml')

$coreSet = @('mysql', 'redis', 'minio', 'minio-init',
             'rocketmq-namesrv', 'rocketmq-store-init', 'rocketmq-broker')
$extraSet = @('elasticsearch')
$services = if ($Core) { $coreSet } else { $coreSet + $extraSet }

Write-Host "[1/3] 校验编排文件（不启动任何东西）..." -ForegroundColor Cyan
docker compose @composeArgs config --quiet
if ($LASTEXITCODE -ne 0) { throw '编排文件校验失败，先修 compose 再说。' }

Write-Host "[2/3] 启动中间件：$($services -join ', ')" -ForegroundColor Cyan
docker compose @composeArgs up -d @services
if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败，看上面的输出。' }

Write-Host "[3/3] 状态（healthy 需要 30~90s，ES 最慢）..." -ForegroundColor Cyan
Start-Sleep -Seconds 5
docker compose @composeArgs ps

Write-Host ''
Write-Host '宿主侧连接参数（可直接粘进后端配置 / 客户端）：' -ForegroundColor Green
Write-Host '  MySQL      127.0.0.1:3306        root / user      库 bilibili'
Write-Host '  Redis      127.0.0.1:16379       （无密码；不要用 6379，那是宿主常驻实例）'
Write-Host '  MinIO      127.0.0.1:19000       控制台 127.0.0.1:19001  minioadmin / minioadmin'
Write-Host '  RocketMQ   127.0.0.1:9876        broker 127.0.0.1:10911'
Write-Host '  Elasticsearch（-Core 时没起）'
Write-Host '              127.0.0.1:19200'
Write-Host ''
Write-Host '下一步：另开窗口跑后端，例如  .\run-backend.ps1 -Service gateway' -ForegroundColor Green
