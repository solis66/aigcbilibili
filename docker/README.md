# aigcbilibili —— Docker 容器化运行手册

> 本文替代上一轮 `deliverables/software-company/启动方案.md` 与 `虚拟环境方案.md`。
> 口径：**15 个逻辑单元 / 16 个物理容器**（RocketMQ 拆 namesrv + broker 两容器 ⇒ 中间件物理 9 + 后端 6 + 前端 1），
> 另有一次性 `minio-init`（执行完即退出，不计常驻）。
> 前提：本机此前**未安装 Docker**；先照做 [`安装指引.md`](./安装指引.md)（含镜像加速器）。

---

## 0. 目录结构

```
docker/
├── README.md                     # 本文件
├── 安装指引.md                    # Docker Desktop / WSL2+Engine 双路线
├── docker-compose.yml            # 主编排（16 容器，默认全套）
├── docker-compose.dev-front.yml  # 可选：前端 webpack dev server 变体（profiles: [dev]）
├── .env.example                  # 可调参数样例（复制为 .env 生效；不复制则用默认值）
├── maven/settings.xml            # 迁移自上一轮虚拟环境方案的 Maven 配置（阿里云镜像）
├── middleware/{mysql.cnf,redis.conf,broker.conf}
├── mysql/init/{01-schema.sql,02-triggers.sql,03-xxl-job.sql}   # 数字前缀 = 执行顺序
├── config/{gateway,notice,user-center,video,chat,search}/…     # 各服务外置配置（挂载覆盖）
├── backend/Dockerfile            # 后端多阶段构建（参数化 MODULE）
├── frontend/{Dockerfile,nginx.conf}
└── logs/<service>/               # 运行期生成（gitignore）
```

> `docker compose` 的**项目目录 = 本 compose 文件所在目录 `docker/`**。因此
> `build.context` 用 `..`（仓库根），bind 路径 `./config/…`、`./logs/…` 均相对 `docker/`。
> 下面命令假设你已在 `docker/` 目录（或显式 `-f docker/docker-compose.yml`）。

---

## 1. 一键启动 / 停止（纯 compose，无脚本族 —— Q7）

```bash
cd docker

# 首次（可选）：生成 .env（不生成也能跑，走 compose 内默认值）
copy .env.example .env        # Windows
# cp .env.example .env        # Linux/macOS

# 构建 6 个后端镜像 + 前端镜像（首次较久）
docker compose build

# 拉起全套 16 容器
docker compose up -d

# 查看状态（期望：全部 Up / healthy）
docker compose ps

# 停止（保留数据卷，日用此命令）
docker compose down

# 停止并**删除全部命名卷**（= 重置数据；见 §5，慎用）
docker compose down -v
```

从仓库根（不经 `cd`）等价写法：

```bash
docker compose -f docker/docker-compose.yml up -d
```

---

## 2. 低内存降级（lite 子集，D-15）

16GB 机器全量启动属**紧平衡**（预估 11~14.5GB）。跳过 `nacos / xxl-job-admin / zipkin / elasticsearch / search`
可**约省 3.3GB**，且**不造成任何功能退化**——原因见 §6「Nacos 说明」。

```bash
cd docker
docker compose up -d mysql redis minio minio-init rocketmq-namesrv rocketmq-broker \
    gateway notice user-center video chat frontend
```

| 方式 | 命令 | 常驻容器 | 预估内存 |
|---|---|---|---|
| **全套（默认）** | `docker compose up -d` | 16 | ≈ 11.3~13.3 GB |
| **lite 子集** | `docker compose up -d mysql redis minio minio-init rocketmq-namesrv rocketmq-broker gateway notice user-center video chat frontend` | 11（+1 一次性） | ≈ 7.8~9.8 GB |

> **内存上限（D-08）**：`notice` 与 `video` 的 `-Xmx=768m`，对应 `mem_limit=1024m`
> （容器限额需约 ≥1.3× 堆以容纳 Metaspace/线程栈/直接内存/GC 结构；900m/768m=1.17× 有 OOMKill 风险，故取 1024m）。
> 其余：gateway/user-center/chat 640m、search 768m、RocketMQ broker 1024m、ES 1536m。

> **为什么不用 compose `profiles` 表达 lite**：Compose 的 profiles 语义是「带 `profiles` 的服务在未启用该 profile 时**一律不启动**」，
> 无法表达「**默认超集**、可命名子集」——若给 nacos 等打 `profiles:[lite]`，反而会让默认 `up -d` 丢掉它们，
> 违背「默认即全套」。故本方案用**显式服务清单**实现 lite，语义等价且更直观。

---

## 3. 常用运维命令

```bash
docker compose ps                       # 容器状态
docker compose logs -f gateway          # 跟踪某服务日志
docker compose logs --tail=200 notice   # 看最后 200 行
docker compose restart video            # 重启单个服务
docker compose up -d --force-recreate gateway   # 改了 docker/config/ 后重建该服务
docker compose build --no-cache notice  # 强制重建某后端镜像
docker stats                            # 实时内存快照（A-12）
```

**应用日志落点**：各后端 bind mount 到宿主 `docker/logs/<service>/`（D-07），可直接在宿主查看。
**改外置配置**：编辑 `docker/config/<service>/application*.yml` 后 `docker compose up -d --force-recreate <service>`
（外置配置为只读挂载，不需要重建镜像）。

---

## 4. 端口与凭据

### 4.1 宿主端口（全部已变量化，D-11）

| 服务 | 宿主端口 | `.env` 变量 |
|---|---|---|
| **nacos** | **18848** | `NACOS_HOST_PORT` |
| mysql | 3306 | `MYSQL_HOST_PORT` |
| **redis** | **默认不发布** | `REDIS_HOST_PORT`（可选，**16379**） |
| **minio** | **19000 / 19001** | `MINIO_API_HOST_PORT` / `MINIO_CONSOLE_HOST_PORT` |
| rocketmq-namesrv | 9876 | `ROCKETMQ_NAMESRV_HOST_PORT` |
| rocketmq-broker | 10911（10909 仅内部） | `ROCKETMQ_BROKER_HOST_PORT` |
| **elasticsearch** | **19200** | `ES_HOST_PORT` |
| zipkin | 9411 | `ZIPKIN_HOST_PORT` |
| xxl-job-admin | 8080 | `XXL_JOB_HOST_PORT` |
| gateway | 8200 | `GATEWAY_HOST_PORT` |
| notice | 30000 | `NOTICE_HOST_PORT` |
| user-center | 3000 | `USER_CENTER_HOST_PORT` |
| video | 10201 | `VIDEO_HOST_PORT` |
| chat | 1688 | `CHAT_HOST_PORT` |
| search | 8201（9916 仅内部） | `SEARCH_HOST_PORT` |
| frontend | 2023 | `FRONTEND_HOST_PORT` |

> ⚠️ **D-11：Redis 默认不发布到宿主**。本机宿主 `6379` 已被常驻 `redis-server`（PID 6640）占用，
> 写 `6379:6379` 会导致 `docker compose up` **整体失败**。容器内 `redis:6379` 仍对同网络的服务可见。
> 如需宿主直连：取消 compose 中 redis 的 `ports` 两行注释，并在 `.env` 设 `REDIS_HOST_PORT=16379`（**勿用 6379**）。
> 任何端口冲突都只需改 `.env`，无需改 compose。
>
> ⚠️ **minio 用 19000 / 19001 而非默认 9000 / 9001（FIX-12，2026-09-23）**：本机
> `9000`、`9001` 双双落在 Windows **保留端口段 8905–9004** 内（`netsh int ipv4 show excludedportrange protocol=tcp`
> 可见，Hyper-V/WSL 动态占用）。表现是 **minio 容器创建即失败、且没有任何容器日志**，只有一条 daemon 报错
> `bind: An attempt was made to access a socket in a way forbidden by its access permissions`。
> **注意这不是「端口被进程占用」——`netstat` / `Get-NetTCPConnection` 查不到任何占用者**，
> 极易误判为 Docker 故障。详见 §11.1 **FIX-12**。

### 4.2 凭据

| 项 | 值 |
|---|---|
| MySQL root | `root` / `user`（环境变量 `MYSQL_ROOT_PASSWORD`） |
| MySQL 库 | `bilibili`（+ 调度库 `xxl_job`） |
| MinIO | `minioadmin` / `minioadmin`，桶 `user-cover` |
| xxl-job-admin | 控制台 `http://localhost:8080/xxl-job-admin`，账号 **`admin` / `123456`** |
| xxl-job accessToken | `default_token` |

---

## 5. 数据持久化与重置（Q6）

| 数据 | 卷 | 说明 |
|---|---|---|
| MySQL | 命名卷 `mysql-data` | 业务数据 |
| Redis | 命名卷 `redis-data` | 缓存 |
| RocketMQ | 命名卷 `rocketmq-store` | 消息存储 |
| Elasticsearch | 命名卷 `es-data` | 索引 |
| MinIO | 命名卷 `minio-data` | 对象（封面） |
| **初始化 SQL** | bind `./mysql/init/` | **仅在空数据卷时执行一次** |

> ⚠️ **R-9**：`docker compose down -v` 会删除**全部命名卷**（含业务数据）。日常停止请用 `down`（**不加** `-v`）。
> 重置 = `down -v` + 重新 `up -d`（会用 `docker/mysql/init/` 重建空库）。改了初始化 SQL 后必须 `down -v` 才会重跑。

---

## 6. 须知：已知现象与不变量

1. **Nacos 实际未生效（V-1 / D-12）**：源码中**没有任何模块引入** `spring-cloud-starter-alibaba-nacos-discovery`
   （仅出现在父 POM 的 `<dependencyManagement>` 与 `common` 的注释里）。因此各 `application.yml` 里的
   `spring.cloud.nacos.discovery.*` 是**未知键、被静默忽略**，服务注册发现**实际未生效**。
   Nacos 容器仅为「环境完整性 + 后续扩展」保留，**不阻塞任何后端**，省掉它**不会造成功能退化**。
   → 这也解释了 gateway 为何用**显式 `uri: http://…` 路由**而非 `lb://`。
2. **后端无 actuator（V-2）**：6 个后端均未引入 `spring-boot-starter-actuator`，**不存在 `/actuator/health`**。
   故健康检查一律用 **TCP 端口探活**（`bash -c ':> /dev/tcp/127.0.0.1/<port>'`）。
3. **日志目录 `module-name_IS_UNDEFINED`（R-5 / D-07）**：`logback-spring.xml` 的 `${module-name}` 可能解析失败，
   产生 `module-name_IS_UNDEFINED` 子目录——**已知现象，本次不修**，不影响功能（日志会落到 `docker/logs/<service>/`）。
4. **`spring.servlet.multipart.location`（R-4）**：notice 保留绝对 `/path`（容器内不存在时 Spring 会退回临时目录）；
   video 用相对 `var/temp`（落在 `/app/var/temp`，Dockerfile 已 `mkdir -p`）。
5. **前端构建风险（Top1）**：`vue/labilibili/package.json` 硬锁 `webpack: "4.0.0"`，而 `@vue/cli-service ~5.0.0`
   内部按 webpack 5 编写。**保持源码不动**（D-01），由锁文件决议实际版本；若 `yarn build` 失败，见 §7。
6. **Zipkin 采样率默认 0.1（FIX-1 / Q5 澄清）**：原 `application.yml` 把采样率写在**无效嵌套键**
   `spring.zipkin.sleuth.sampler.probability` 上，Sleuth 并不读它，**实际生效的是 Sleuth 默认值 0.1**。
   本方案改为**合法键** `spring.sleuth.sampler.probability`，**默认 0.1 以保持原实际行为**（而非把采样放大 10 倍）；
   若要在 Zipkin UI 看到完整链路，把环境变量 `ZIPKIN_SAMPLER_PROBABILITY` 设为 `1` 即可（F-16 出口保留）。

---

## 7. 故障排查

| 现象 | 排查 / 处置 |
|---|---|
| `Bind for 0.0.0.0:6379 failed` | 你误把 redis 的 `ports` 打开了。保持注释即可（D-11）。 |
| 后端 `Downloading` 成片、构建极慢 | `.m2-repo/` 未被用于播种。确认 `.dockerignore` **未排除** `.m2-repo/`，且 `docker/backend/Dockerfile` 首行 `COPY .m2-repo/ …` 生效（D-04）。必要时先 `docker build -f docker/backend/Dockerfile --target deps -t aigcbilibili/maven-deps:1 .`。 |
| 前端 `yarn build` 失败（webpack 4 vs vue-cli 5） | ① 确认锁文件解析出的 webpack 版本；② 降级安装：`yarn install --frozen-lockfile \|\| npm install --legacy-peer-deps`（已在 Dockerfile 内置）；③ 若仍失败，试 `pnpm install`（仓库有 `pnpm-lock.yaml`）；④ 结论记回本文（D-05 要求记录降级与原因）。**降级已内置：yarn frozen 失败即回退 npm legacy-peer-deps。** |
| 后端启动即崩、连不上库 | 看 `docker/logs/<service>/` 与 `docker compose logs <service>`；确认 `mysql` 已 `healthy`（`docker compose ps`）。 |
| xxl-job-admin crash-loop | 确认 `03-xxl-job.sql` 已随空数据卷执行（即 `xxl_job` 库 8 表存在）。若曾用旧卷启动，`down -v` 后重来。 |
| ES 启动报 `vm.max_map_count` | 仅 Linux 宿主需 `sudo sysctl -w vm.max_map_count=262144`；Docker Desktop(WSL2) 一般已满足。 |
| 前端接口 502 | gateway 未就绪或路由目标未起；访问 `http://localhost:8200` 直连网关确认。 |

---

## 8. 健康检查依据（D-14：每条都注明「为何该命令在此镜像里一定存在」）

| 容器 | 探针 | 依据 |
|---|---|---|
| mysql | `mysqladmin ping` | mysql 官方镜像自带 mysqladmin |
| redis | `redis-cli ping` | redis:alpine 由镜像自带 redis-cli |
| elasticsearch | `curl /_cluster/health` | ES 官方镜像自带 curl |
| nacos | `curl /nacos/actuator/health` | nacos-server 官方镜像文档化用法即 curl |
| rocketmq-namesrv/broker | `bash -c ':> /dev/tcp/…'` | apache/rocketmq:4.9.7 基于 openjdk:8-jre-slim（Debian，含 bash） |
| xxl-job-admin | `bash -c ':> /dev/tcp/…'` | 基于 openjdk:8-jre-slim（含 bash，无 curl） |
| 6 个后端 | `bash -c ':> /dev/tcp/…'` | eclipse-temurin:8-jre（Debian，含 bash）；V-2 无 actuator |
| zipkin | `wget -q -O - /health` | openzipkin/zipkin:2.24.3 为 Alpine 系，含 busybox wget（无 curl） |
| frontend | `wget -q -O /dev/null /` | nginx:1.25.4-alpine 含 busybox wget（无 curl） |
| **minio** | **无 healthcheck（刻意）** | 基于 **ubi-micro**，**既无 bash 也无 curl**；任何 shell 探针都会永久 unhealthy。改由 `minio-init`（`service_started` + `until` 重试）兜底 |

> **铁律（D-14）**：一个「永远 unhealthy」的检查会经 `depends_on: service_healthy` 造成**级联阻塞**，比不加更糟。
> 故 minio 不加；zipkin / xxl-job-admin 虽加了探针，但**没有任何服务以 `service_healthy` 依赖它们**。

---

## 9. 镜像与版本锁定（D-09 / D-10）

| 组件 | 镜像 | 约束 |
|---|---|---|
| MySQL | `mysql:5.7.44` | 锁 5.7（初始化 SQL 排序规则按 5.7 修好） |
| Redis | `redis:7.0.15-alpine` | — |
| Nacos | `nacos/nacos-server:v1.4.7` | 锁 1.x |
| MinIO | `minio/minio:RELEASE.2023-03-20T20-16-18Z` + `minio/mc:RELEASE.2024-11-21T17-21-54Z` | 无 `latest`；**mc 侧 tag 已于 2026-09-22 实测上调**，原因见 §11 FIX-3 |
| RocketMQ | `apache/rocketmq:4.9.7` | 锁 4.x（starter 仅 2.0.4） |
| Elasticsearch | `elasticsearch:7.13.3`（**Docker Hub**） | 与客户端严格一致 |
| Zipkin | `openzipkin/zipkin:2.24.3` | — |
| xxl-job-admin | `xuxueli/xxl-job-admin:2.3.1` | 与 xxl-job-core 2.3.1 对齐 |
| 后端构建/运行 | `maven:3.8.6-openjdk-8` / `eclipse-temurin:8-jre` | Java 8，严禁 11/17 |
| 前端构建/运行 | `node:16.20.2-alpine` / `nginx:1.25.4-alpine` | Node 16 免除 webpack4 的 openssl flag |

> 全部 tag 均锁定具体版本，**无 `:latest`**。镜像总量约 3.5~4.5GB，首次拉取务必配镜像加速器（见安装指引）。

---

## 10. 实测验证状态（2026-09-22 更新）

> 本节取代原文「本机未安装 Docker 运行时，以下项只能静态验证」的口径。
> 2026-09-22 本机完成 Docker Desktop 4.92.0 / Engine 29.8.0 / Compose **v5.5.1** 部署，
> 下列各项均由**实际运行结果**确认，不再是静态推断：

| 原编号 | 项 | 结果 |
|---|---|---|
| N-1 | 镜像拉取 / 构建 | ✅ 中间件镜像全部拉取成功；**7 个业务镜像全部构建成功** |
| N-2 | 容器启动 / 端口监听 | ✅ **16 个常驻容器全部 Up，除 minio（刻意无探针）外全部 healthy** |
| N-3 | 22 表 + 7 触发器 + xxl_job 8 表导入 | ✅ 实测 `22 / 7 / 8` |
| N-4 | 前端渲染 | ✅ `http://localhost:2023` → HTTP 200，`dist` 正常产出（Build complete） |
| A-08/A-09 | 核心链路 | ✅ 前端 nginx `/api` → 网关 → notice → MySQL 打通：`/api/getNotice/getNoticeCount/1` 返回 `{"code":200,...}` |
| N-5/A-12 | 实际内存占用 | ✅ 全套实测约 **7.4 GiB**（详见 §11「内存观察」） |
| A-10 | 注册 → 落库 → 通知链路 | ✅ **2026-09-22 晚补测**：`POST /api/register/passwordRegister` → `{"code":200,"data":5}`；`user` 表新增 id=5（**未回滚**）；notice 端 `SendDBChangeServiceImpl` 收到 Feign 调用并发 MQ。修复过程见 §11.1 **FIX-11** |
| A-11 | 密码登录 + 令牌下发 | ✅ **2026-09-22 晚补测**：`GET /login/getCaptcha` → 带 `sessionId` cookie → `POST /login/passwordLogin` → `{"code":200,"data":{"userId":5,"status":true}}`，响应带 httpOnly `longAuthorization` cookie |
| A-13 | **视频投稿全链路**（分片 → 合并 → 落库 → 取回播放） | ✅ **2026-09-23 补测**：`POST /api/createCenter/uploadPart`（单分片，标识符 `测试投稿.mp4-<size>-<ts>`，即前端 resumable 的真实格式）→ `{"code":200,"data":["测试投稿.mp4-…",""]}`；`POST /api/createCenter/uploadTotal` → `{"code":200,"data":true}`；库中 `video.url = http://localhost:2023/video/…`、`cover = http://localhost:2023/video-cover/<标题+uuid>.jpg`；**封面 GET 200、视频 GET 200 `video/mp4` 2440944B、`Range: bytes=0-99` → 206**；minio 的 `video` / `video-cover` 桶内均出现对象。修复过程见 §11.1 **FIX-14**（共 6 个独立根因）。<br>— 二轮复测（同日，换**真实可解码的 mp4**）：`uploadPart` 除视频外**还回传了真实首帧封面**（base64 17648B）；写完库后 MQ 消息被 `video-encode-group` **真正消费**，`VideoEncodeConsumer` 跑完「取时长 → 转码 → 回传」三步（日志 `转码成功` / `上传新视频成功`），**`video.length` 由 NULL 变为 `00:03`**；封面 GET 200、视频 GET 200、`Range: bytes=0-99` 206。阻塞点见 §11.1 **FIX-15** |

> 仍未验证（需业务操作触发，本次未做）：私聊 WebSocket、搜索索引生效的端到端检验。
> 视频上传/播放已于 **2026-09-23 补齐**（见 A-13），**含时长回写（`video.length`）**——该字段依赖 MQ 消费，而 MQ 消费此前被 FIX-15 整条阻断（`mysqlToEs` 同步同样受影响，一并恢复）。
> ⚠️ A-13 全程走 HTTP 接口 + 真实浏览器产物中确认过的入参格式，**未**在浏览器里点完整个投稿表单
> （投稿页需登录态，本机无可用账号口令）。

---

## 11. 本机实测记录与修复清单（2026-09-22 / 2026-09-23）

> 本节记录真机运行暴露的偏差（FIX-1 ~ FIX-20，含 FIX-9b）。全部已修，且每处都给出根因与判据，
> 便于后续换机器时对照。改动原则：**不动 `java/**` 与 `vue/**` 源码**（延续 D-01）；
> 例外只有九处，且都是「纯配置无法覆盖」才动源码：
> - **FIX-11** —— 上游 3 处 Feign url 硬编码 `localhost` 导致所有写操作 500 → 改为占位符（默认值仍是 `localhost`，宿主直跑不受影响）。见 **D-01 修订 R1**。
> - **FIX-13** —— 上游 4 处头像 URL 硬编码线上域名 `https://labilibili.com/` 导致**头像破图** → 同样改为占位符 + 默认值不变。见下表 FIX-13。
> - **FIX-14** —— 5 处「视频/封面 URL 前缀」与 1 处 `cover` 覆盖 + 1 处标识符解析导致**视频投稿整条链路走不通** → 参数化 + 语义修正；另含 2 处纯环境修复（nginx 体积上限、jave 的 ffmpeg 竞态）。见下表 FIX-14。
> - **FIX-15** —— 与源码无关，纯粹是镜像内 **JDK 8u372 的 cgroup v2 探测 bug** 打死了 broker 的**全部** Pull 请求 → 只加一个 JVM 开关。见下表 FIX-15。
> - **FIX-16** —— 纯前端布局缺陷（与部署无关的**上游 UI bug**）：首页搜索按钮被「AI助手 / 专栏」压住甚至点不动。见下表 FIX-16。
> - **FIX-17** —— 「我的消息」页两条报错的**两个独立根因**：① nginx 把前端 dev-server 的代理前缀 `/wschat` 当成了后端路径（chat 真实 WS 端点是 `/ljl/bilibili/chat`）→ 握手 404；② `notice` 模块一条 MyBatis 注解 SQL 的 `WHERE` 引用了**不存在的表别名 `t`** → 接口返回非标准 600。**① 只改 `nginx.conf`**（零源码改动，也不需重建前端镜像）；**② 必须改 `java/notice` 的 mapper** → 重建 notice 镜像。见下表 FIX-17。
> - **FIX-18** —— `/user-center/**` 这一族路径**网关无人接**：前端有 4 处按「`/<服务名>/<接口>`」调用，而网关的 `discovery.locator`（按服务名自动路由）因 **nacos 里 0 个服务注册**而整体空转 → 视频详情页的合集、个人主页合集等全部 404。**只改 `docker/config/gateway/`（纯配置）**，零源码改动。见下表 FIX-18。
> - **FIX-19** —— 搜索页一进去就弹「请求搜索结果的页数和文件数失败」：**ES 里一个索引都没有**（`no such index [video]` → 非标准 600）。顺着查出**整条「MySQL→ES」同步链路自部署起就没跑过**：`xxl-job` 里 search 的执行器已注册但**没有执行器分组、也没有任务**。补齐后又暴露 4 个上游缺陷（`minScore` 绝对阈值把结果全滤掉、增量文档字段名与全量不一致、空 BulkRequest 让任务报 500、删除消息键名不一致删错文档）。**其中 `minScore` 与 `MysqlToEsHandler` 两处需改 `java/search`** → 重建 search 镜像；另一处「注册 xxl-job 分组与任务」是**运行期数据**（写 `xxl_job` 库），不在仓库文件里。见下表 FIX-19。
> - **FIX-20** —— 纯前端布局缺陷（与部署无关的**上游 UI bug**）：「我的消息 → 我的聊天」页右上角的类型切换 tab（文本 / 图片 / PPT）**下半截扎进下方对话框**内部，且被压住的那一段**点不动**。根因是 tab 组被写成 `.chat-content` 的**绝对定位子元素**（`top:10px`），而同行标题只有约 27px 高、card tab 组有 56px ⇒ 下溢 25px 落进 `.chat-box`。改为与对话框**同宽同边距**的 flex 头栏 + `space-between`，右缘自动对齐。⚠️ 改的是 **`vue/labilibili/`（真正打进镜像的源码）** → 必须 `docker compose build frontend`；**不动 `java/**`**。见下表 FIX-20。

### 11.0 前置环境修复（两项，均在仓库外）

| 项 | 问题 | 处置 |
|---|---|---|
| **FIX-1 镜像加速器** | `C:\Users\<用户>\.docker\daemon.json` **完全没有 `registry-mirrors`**；实测官方源 `registry-1.docker.io` 超时。§1 推荐的 4 个加速器实测**已有 3 个停服**（`dockerproxy.com`、`mirror.baidubce.com`、`hub-mirror.c.163.com` 均**域名解析失败**，`docker.xuanyuan.me` 超时） | 改用实测可达的两个：`https://docker.m.daocloud.io`、`https://docker.1ms.run`。改完**必须重启 Docker Desktop**。参考速度：`redis:7.0.15-alpine` 6.8 秒 |
| **FIX-2 WSL2 内存** | `%UserProfile%\.wslconfig` **不存在**，Docker Desktop 按宿主 50% 分配 → `docker info` 实测 `Total Memory: 7.601GiB`，而 lite 子集就要 7.8~9.8GB、全套 11.3~13.3GB ⇒ **两种起法都会 OOM** | 新建 `.wslconfig` 设 `memory=11GB` / `swap=8GB`，重启后 `Total Memory` → **10.69GiB** |

### 11.1 仓库内修复（FIX-3 ~ FIX-24；除 FIX-11 / FIX-13 / FIX-14 / FIX-16 / FIX-17 / FIX-19 / FIX-20 / FIX-22 / FIX-23 / FIX-24 外全部只落在 `docker/`）

| 编号 | 位置 | 现象 | 根因 | 处置 |
|---|---|---|---|---|
| **FIX-3** | `docker-compose.yml` · `minio-init.image` | `minio/mc:RELEASE.2023-03-20T20-16-18Z` 报 `error from registry: denied`；同 tag 的 `minio/minio` **却能正常拉取** | MinIO 已清理该旧 tag（daocloud=`denied`、`docker.1ms.run`=`not found`、`quay.io/minio`=`not found` 三处皆无） | 仅**上调 mc 侧 tag** 为实测可得的最近 RELEASE `2024-11-21`；server 维持 D-10 锁定（mc 走 S3 协议，跨版本兼容），缩小改动面 |
| **FIX-4** | `docker-compose.yml` · 新增 `rocketmq-store-init` 服务 | `rocketmq-broker` 反复退出，**退出码 253、`docker logs` 一行都没有**；连带 `depends_on` 它的 `notice`/`video` 起不来 | 命名卷 `rocketmq-store` 由 Docker 以 `root:root(755)` 创建，而 broker 以 **`uid=3000(rocketmq)`** 运行，镜像内 `/home/rocketmq` 又是 `drwx------` → 非 root 进程无法建 storePath 目录，脚本早期即退出（**故无日志，最难查**） | 新增一次性 init 容器（复用已锁 `redis:7.0.15-alpine` 的 busybox `chown`），以 root 把卷 chown 给 `3000:3000`；broker 用 `depends_on: service_completed_successfully` 串在它之后，**维持非 root 运行** |
| **FIX-5** | `docker-compose.yml` · `minio-init.command` | `mc: -c: line 2: syntax error: unexpected end of file`，`user-cover` 桶**从未创建**（封面/头像上传会失败） | 原写法用 YAML **折叠标量 `>`** 承载多行脚本，而 compose 对**字符串形态**的 `command` 会按空白做 shell 分词 —— 实测容器 Cmd 被拆成 `["until","mc","alias","set",...]`，`sh -c` 只拿到第一个词 `until` | 改为**列表形态**（单元素 + 多行块标量 `\|`），列表不分词，整段脚本完整传入 |
| **FIX-6** | `docker-compose.yml` · `mysql.command` | 日志持续告警 `[Warning] World-writable config file '/etc/mysql/conf.d/zz-aigcbilibili.cnf' is ignored.` —— **外置调优全部未生效** | Docker Desktop(Windows) 的 bind 挂载把宿主文件呈现为 `0777`；MySQL 出于安全拒绝加载 world-writable 配置，**`…:ro` 只读挂载不改变文件 mode，救不了**。后果：`default-time-zone` 丢失 → 服务端 UTC，而 6 个后端 JDBC URL 统一声明 `serverTimezone=GMT+8` ⇒ **服务端 `NOW()` 与应用写入值相差 8 小时**（数据一致性隐患） | 不再依赖外置文件，改在 `command:` 直接下传 mysqld 参数（entrypoint 见首参以 `-` 开头会自动前置 `mysqld`），无需派生镜像、无权限问题。`docker/middleware/mysql.cnf` 保留作历史参照，**已不再被挂载**。实测生效：`time_zone=+08:00`、`character_set_server=utf8mb4`、`max_connections=200` |
| **FIX-7** | `docker-compose.yml` · `frontend.healthcheck` | `frontend` 持续 **`unhealthy`（FailingStreak=19）**，但服务完全正常（容器内 `127.0.0.1` rc=0、宿主 2023 返回 200） | 探针用 `http://localhost/`，容器内 `/etc/hosts` 的 `::1 localhost` 优先，busybox wget 实测 `Connecting to localhost ([::1]:80)` → refused；而 nginx.conf 是 `listen 80;` **只绑 IPv4**。**这正是 D-14 铁律点名的「永远 unhealthy 的假探针」** | 探针改用字面量 `127.0.0.1`，与文件内其余探针（后端统一 `/dev/tcp/127.0.0.1/<port>`）口径统一。未改 nginx.conf 加 IPv6 listen（改动面更大、收益为零） |
| **FIX-8** | `docker/frontend/Dockerfile` · 构建前 patch `package.json` | `yarn build` 直接崩：`ERROR Error: Cannot find module 'webpack/lib/rules/BasicEffectRulePlugin'`（Require stack: `node_modules/vue-loader/dist/pluginWebpack5.js` → `@vue/cli-service/node_modules/webpack/...`） | 上游 `vue/labilibili/package.json` 把 **`"webpack": "4.0.0"` 硬锁**（且误放在 `dependencies`），而 `@vue/cli-service@~5.0.0` + Vue3 的 `vue-loader@17` 走 **webpack 5 API** → 根级 webpack4 与 cli-service 内嵌 webpack5 **版本错配**。**与 package manager 无关**，故本文件 §7 原「改试 pnpm」一条对该故障**无效** | 仅在**镜像构建层内**把该 pin 改写为 `^5.89.0`，使 npm 把**单一 webpack 5** 提升到根级；仓库 `vue/**` 源码保持不动（符合 D-01）。实测修复后 `Build complete`，产物 `dist` 正常 |

| **FIX-9** | `docker-compose.yml` · `nacos` / `rocketmq-broker` / `xxl-job-admin` 的 `mem_limit` | 稳态占用**贴近自身限额**：`rocketmq-broker` **968.8 MiB / 1024m = 94.6%**、`nacos` 92.7%、`xxl-job-admin` 92.6% | 首版限额系**无实测数据的估算值**，按「估算堆 + 固定余量」设定，未经真机 RSS 回归。broker 尤甚：RocketMQ 以 **mmap 写 commitlog，页缓存计入 cgroup**，压力随消息吞吐增长，**触顶即 OOM Kill broker，并连带 `notice` / `video` 因 `depends_on: service_healthy` 起不来** —— 故障半径远大于 broker 自身 | 上调为 **1280m / 1024m / 640m**，**JVM 堆一律不动**（broker 仍 `-Xmx768m`、nacos 仍 `-Xmx512m`）。限额是**上限而非预留** → 常驻占用不变。已记入 `01-主理人决策.md` **D-08 修订 R1**。实测占用率降至 **76.1% / 67.4% / 76.1%** |
| **FIX-9b** | `docker-compose.yml` · `gateway` / `user-center` / `chat` / `search` 的 `mem_limit` | 同规则续行：修完上一组后新一次 `docker stats` 暴露 **gateway 85.6% / user-center 87.4% / chat 86.2% / search 86.5%** | **同源缺陷**：堆固定，但 `-Xms` 仅 256m（search 384m），**堆随负载向 `-Xmx` 增长时 RSS 还会再涨约 120m** → 实测 86% 已属**实际可触顶**（`gateway` 被杀 = 全站 502，`chat` 长连接会持续累积线程栈与直接内存） | 统一校准为 **1024m**（堆占限额 50%~62.5%，符合通行准则 `限额 ≥ 堆 ×1.5`）。实测占用率降至 **58.2% / 54.7% / 52.8% / 58.7%** |
| **FIX-10** | `docker/frontend/nginx.conf` + `docker-compose.yml` · `frontend.volumes` | 重建 `gateway` 容器后，`http://localhost:2023/api/...` **立刻 502**，而**直连 `:8200` 仍 200**；重启 frontend 容器即恢复 | `proxy_pass http://gateway:8200/;` 的**上游是字面主机名**，nginx **只在配置加载时解析一次并永久缓存 IP**；容器重建会换 IP，nginx 仍把请求发往旧 IP → `connect() failed (111: Connection refused)`。**实测证据**（nginx error.log）：`upstream: "http://172.18.0.6:8200/..."`，而 gateway 当时真实 IP 已是 **`172.18.0.7`** | 改为**变量形式** `proxy_pass` + Docker 内置 DNS `127.0.0.11` 动态解析，`valid=10s`（**不显式给则沿用 DNS 的 600s TTL，等于没修**）；变量形式下 nginx 不再自动剥前缀，故显式用 `rewrite ^/api/(.*)$ /$1 break` 等价改写（`/wschat` 无 rewrite，语义与原写法一致）。同时把 nginx.conf **只读挂载**进容器 → 以后改配置 `docker exec labilibili-front nginx -s reload` **秒级生效，无需重建镜像**。**回归验证**：用占位容器抢占旧 IP 强制 gateway 换 IP（`.7` → `.18`）后，`/api` **20.2s 自动恢复，全程未重启前端** |
| **FIX-11** | `java/common/.../client/` 下 3 个 Feign 接口 + `docker/config/<svc>/` 5 份配置 | 页面点**注册** → 500（响应体**为空**）；`docker logs gateway` 里是 `feign.RetryableException: Connection refused executing POST http://localhost:30000/DBChange/sendDBChangeNotice`。**容器全部 healthy、端口全在**，因为**它连的是它自己** | 3 个 Feign 客户端的 url 是**注解字面量** `http://localhost:<port>`（notice:30000 / video:10201 / search:8201）。容器内 `localhost` = 本容器 → 拒连。`SendNoticeClient` 被 **gateway（注册/登录）、user-center、video（上传/点赞/弹幕/评论/收藏）、chat** 共用 → **几乎所有写操作都会 500**。注册那条还带 `@Transactional` → **已插入的 user/privilege 一并回滚**，表现为「点完什么都没发生」。**属上游源码缺陷，非容器化引入**；且 `spring.cloud.openfeign.client.config.<name>.*` **没有 `url` 项**，纯配置无解 | 把 3 处注解改为**占位符 + 默认值**：`url = "${service.notice.url:http://localhost:30000}"`（其余两处同理）。**默认值即宿主直跑所需** → 混搭模式零配置；容器内由 `docker/config/<svc>/` 新增的 `service:` 段覆盖为容器名（gateway/chat/user-center → notice:30000，video → notice:30000 + search:8201，notice → video:10201）。**这是本项目首处源码改动**，已记入 `01-主理人决策.md` **D-01 修订 R1** |

| **FIX-12** | `docker/.env` + `.env.example` · `MINIO_API_HOST_PORT` / `MINIO_CONSOLE_HOST_PORT` | `docker compose up -d` 之后 **`minio` 始终是 `Exited (0)`（还停留在上次 `down` 的时间）**，容器**没有任何日志**；`minio-init` 卡在 `until` 循环里刷 `lookup minio on 127.0.0.11:53: no such host`；连带**前端所有头像/封面 URL 404**。手动 `docker compose up -d minio` 才吐出唯一线索 | 宿主 `9000`/`9001` 双双落在 **Windows 保留端口段 `8905–9004`**（`netsh int ipv4 show excludedportrange protocol=tcp` 可见，Hyper-V/WSL 动态占用），daemon 报 `listen tcp 0.0.0.0:9000: bind: An attempt was made to access a socket in a way forbidden by its access permissions`。⚠️ **这不是「端口被进程占用」**（那会报 `address already in use`）——故 `netstat` / `Get-NetTCPConnection` **查不到任何占用者**，极易误判成 Docker 故障 | `.env` 改为 **`19000` / `19001`**（`netstat` 无占用 ≠ 可用，判定手段只有 `excludedportrange`）。`.env.example` 同步加注说明。**注**：minio 端口映射失败时容器**根本不会被创建**，所以问题现场没有任何 minio 日志可看，唯一判据是 `docker compose up -d minio` 的那条 daemon 报错 |
| **FIX-13** | `java/common/.../handler/MyMetaObjectHandler.java`、`java/gateway/.../LoginServiceImpl.java`（×2）、`java/user_center/.../UserInfoServiceImpl.java` + `docker/config/<svc>/*.yml`（6 份）+ `docker/frontend/nginx.conf` + `docker-compose.yml`（minio-init 播种） | **前端头像不显示**（浏览器 broken-image 图标）：用户中心接口返回 `cover = https://labilibili.com/user-cover/default.png`，该地址实测 **404**（域名能解析，但那是作者线上环境的存储，与本地 minio 无关） | 两重根因：① 上游 **4 处硬编码**线上域名 —— `MyMetaObjectHandler.insertFill` 用 `setFieldValByName` **无条件覆盖** cover（注册必中）、`LoginServiceImpl` 手机号/邮箱注册各 1 处、`UserInfoServiceImpl` 的 `String filePath="https://labilibili.com/"`（上传头像必中）；② 本地 minio 的 `user-cover` 桶里**从来没有 `default.png` 对象**（FIX-12 导致 minio 从未成功运行过） | ① 4 处改为占位符 `${...:原默认值}`（**默认值不变**，宿主直跑零配置；由 `@ComponentScan("ljl.*")` 可知 6 个服务都扫到 `MyMetaObjectHandler`，故 ② 6 份外置配置统一覆盖为 `http://localhost:2023/...`）；③ nginx 新增 `location ^~ /user-cover/` 反代到 `minio:9000`——**`^~` 不可省**，否则会被下一条 `~* \.(png\|jpg\|…)$` 静态资源正则抢走而 404；写法沿用 FIX-10 的变量形式 + `valid=10s`；④ `minio-init` 增加 `mc cp` 播种 `default.png`（素材 `docker/minio/default.png`，源自 `vue/src/assets/img/avater.png`）使**可复现**；⑤ 库中历史数据 `REPLACE(cover,'https://labilibili.com/','http://localhost:2023/')`。**回归判据**：`curl http://localhost:2023/user-cover/default.png` → `200` / `image/png` / 70253B；`GET /api/userInfo/getUserInfo/2/2` 返回的 `cover` 指向本地入口。**改 `java/common` 需重建全部 6 个后端镜像**（同 FIX-11 约束） |
| **FIX-14** | `docker/frontend/nginx.conf`（`client_max_body_size` + 2 个 `location`）、`docker/docker-compose.yml`（minio-init 建桶列表）、`docker/.env` / `.env.example`、`java/video/.../UploadAndEditServiceImpl.java`（×3）、`java/notice/.../VideoEncodeConsumer.java`、`java/common/.../MyMetaObjectHandler.java`、`docker/config/{video,notice}/application.yml`、`docker/backend/Dockerfile` | **视频投稿整条链路走不通**。沿链路逐层实测：① 传一个 2.4MB 的 mp4 → **nginx 直接 413**（返回 HTML 页，请求根本没到网关）；② 修掉后 → `{"code":600,"msg":"…String index out of range: -1"}`；③ 只有传**带逗号**的标识符才 200，但回传的首帧封面是空串；④ 投稿写库成功，但 `video.url` = `http://localhost:9000/video/…`（浏览器打不开）、`video.cover` **被改成默认头像**；⑤ 容器**首次**调用 jave 必抛 `Cannot run program "/tmp/jave/ffmpeg-amd64-3.3.1": error=13, Permission denied` | **六个互相独立的根因**：① nginx 默认 `client_max_body_size=1m`，而前端分片大小就是 **6MB**（`upLoad.vue` 的 `chunkSize`）→ 每个分片都超限，**与视频本身多大无关，全都传不上去**；② `createCenter/uploadPart` 无条件 `substring(0, indexOf(','))`，而前端 resumable 的 `generateUniqueIdentifier` 产出的是 `${name}-${size}-${lastModified}`，**不含逗号** → `substring(0, -1)` 抛 `StringIndexOutOfBoundsException`；③ `video` / `video-cover` 两个桶**从未被创建**（`minio-init` 只建 `user-cover`），且 `MinioServiceImpl.uploadImgFile` 把异常整个吞掉（catch 后仍 `return true`）→ 现象是「封面静默失败、视频分片接口报失败」，极易误判成前端问题；④ `UploadAndEditServiceImpl`（2 处）与 `VideoEncodeConsumer`（1 处）把 URL 前缀硬编码为 `http://localhost:9000/`，该值**直接落库并被前端当 `<video :src>` / `<img :src>` 使用**（是「浏览器侧地址」而非服务端访问地址）；⑤ `MyMetaObjectHandler.insertFill` **无条件**覆盖 `cover`，而 `Video.createTime` 带 `@TableField(fill = FieldFill.INSERT)` → 投稿的 `videoMapper.insert` 会触发同一个填充器，把刚设好的封面覆盖成默认头像；⑥ jave 的 `DefaultFFMPEGLocator` 首次使用才把 ffmpeg 拷出，随后用**外部 `/bin/chmod` 进程**补可执行位，与紧接着的 exec 之间**没有任何等待** → 竞态（实测容器内该文件已是 `-rwxr-xr-x`、进程为 root、`/` 也不是 noexec，即「文件没问题，只是 chmod 还没落盘」） | ① nginx 加 `client_max_body_size 360m`（**刻意略大于**后端 `max-request-size=350MB`，让后端成为权威限制并返回 JSON，而不是被 nginx 用无信息的 413 HTML 页拦掉）；② 改成「**有逗号才截断**」，两种标识符格式都能用；③ `minio-init` 改为按**桶列表**建桶并统一 `mc anonymous set download`（`.env` 的 `MINIO_BUCKET` → `MINIO_BUCKETS=user-cover video video-cover`）；④ 3 处参数化为 `${minio.file-path:原字面量}`，容器侧在 `docker/config/{video,notice}/application.yml` 覆盖为 `http://localhost:2023/`，并由 nginx 新增 `location ^~ /video/`（`proxy_buffering off`，否则大文件要等 nginx 全量缓冲完才开播；Range 头默认透传，勿覆盖成空值）与 `^~ /video-cover/`；⑤ 改为**仅在 `cover` 为 null 时**才填默认值 —— 语义上「默认值」本就该只对缺省生效，注册流程显式传入的头像不受影响；⑥ `docker/backend/Dockerfile` 在**镜像里就把 ffmpeg 预置到 jave 期望的 `/tmp/jave/ffmpeg-amd64-3.3.1`（带 +x）**，jave 检测到 `exists` 即走短路分支 → 竞态从根上消失（Docker 不会清空镜像层的 `/tmp`）。**回归判据（实测）**：`POST /api/createCenter/uploadPart` → `{"code":200,…}`；`/api/createCenter/uploadTotal` → `{"code":200}`；库中 `url = http://localhost:2023/video/测试投稿.mp4-…`、`cover = http://localhost:2023/video-cover/<标题+uuid>.jpg`；封面 GET → **200**、视频 GET → **200 `video/mp4`**、`Range: bytes=0-99` → **206**；`minio` 的 `video` / `video-cover` 桶内均出现对象。**本次同时改了 `java/common` 与 6 服务共用的 `docker/backend/Dockerfile` → 重建了全部 6 个后端镜像** |
| **FIX-15** | `docker/docker-compose.yml` · `rocketmq-broker.environment.JAVA_OPT_EXT`（**唯一改动，加一个 JVM 开关**） | **broker 的 Pull 请求 100% 失败 ⇒ 全站 MQ 消费停摆**。症状是前端可见的「投稿后**时长不显示**」（`video.length` 恒为 `NULL`），但实际影响面大得多：`video-encode-group` 的 `Diff` 只涨不降（恒为 2）、`mysql-es-group`（MySQL→ES 同步）同样报错，**两个消费者组自容器启动第一秒起就没消费过任何一条消息**。`mqadmin consumerConnection` 显示消费者**在线**（`172.18.0.17@7`、订阅 `video-encode *` 正常），`docker compose restart notice` 无效 —— 极易误判成「消费者没注册」或「消息被跳过」 | **不在应用层，而在 broker 所依赖的 JDK 上**。broker 侧 `remoting.log` 从启动即刷 `ERROR PullMessageThread - process request exception` + `java.lang.NoClassDefFoundError: Could not initialize class org.apache.rocketmq.store.StoreUtil`（`at DefaultMessageStore.checkInDiskByCommitOffset(DefaultMessageStore.java:1264)`，该方法是每次 `getMessage` 的**必经路径**）。在 broker 容器内 `javac` 写 10 行复现程序后拿到完整因果链：`StoreUtil.<clinit>` → `ManagementFactory.getOperatingSystemMXBean()` → `sun.management.OperatingSystemImpl.<init>(:182)` → `Container.metrics()` → `CgroupSubsystemFactory.create()` → **`jdk.internal.platform.cgroupv2.CgroupV2Subsystem.getInstance()` 抛 NPE**。即 **JDK 8u372 的 cgroup v2 探测在 Docker Desktop（WSL2，cgroup v2）下失败**，`StoreUtil` 类初始化永久失败。`apache/rocketmq:4.9.7` 恰好基于 `openjdk:8u372`（本项目其余后端的 `eclipse-temurin:8-jre` 是 **8u502**，无此 bug，故**仅 rocketmq 镜像中招**） | `JAVA_OPT_EXT` 追加 **`-XX:-UseContainerSupport`**（关闭 JVM 容器感知）。实测同一复现程序由 `ExceptionInInitializerError` 变 `INIT_OK`。**安全性**：`-Xms/-Xmx/-Xmn` 已显式写死（768m/768m/256m），关闭后 JVM **不会**按宿主内存重算堆，故不存在「堆涨过 `mem_limit` 被 OOM Kill」的风险；唯一代价是 `StoreUtil` 改用宿主内存（≈11.5G）而非 cgroup 限额（1280m）估算「消息驻留内存还是落盘」的阈值（`accessMessageInMemoryMaxRatio`），属纯启发式，无副作用。**试过但无效**（勿再试）：`-Djdk.internal.platform.metrics.disabled=true`。**判据**：① `docker exec rocketmq-broker grep -c NoClassDefFoundError /home/rocketmq/logs/rocketmqlogs/remoting.log` 恒为 `0`；② `mqadmin consumerProgress -g video-encode-group` 的 `Diff Total` 归 `0`；③ `video.length` 由 `NULL` 变 `00:03` |
| **FIX-16** | `vue/labilibili/src/pages/main/MainPage.vue`（**上游前端布局缺陷，与部署无关**） | 首页**搜索框右侧的「搜索」按钮被「AI助手 / 专栏」图标区压住**：视口 1280 时整个按钮被覆盖、1366 压 60px、1440 压 23px，**且点不动**（覆盖层虽无背景色但可命中，会吃掉点击）；两个图标之间的竖分隔线还会横穿按钮 | `.icon-box` 是 `position:absolute; right:0; width:400px`，而 `SearchPanel` 的 `.search-box` 是 `margin:auto` 居中的 **702px** 固定块 → **视口 < 1542px 必然重叠**。属上游写死的绝对定位与居中块的冲突 | 改为 `.head-bar` 内**左右等宽侧栏**（`flex:0 1 400px; min-width:168px`，两侧同为 `margin:0 2rem`），图标内容 `justify-content:flex-end` 靠右，竖线改挂在 `.icon-box-content` 的 `border-left`。**回归判据**：1280 / 1366 / 1440 / 1920 四档零重叠、0 横向滚动条、按钮各点 `elementFromPoint` 全命中 `search-btn`、搜索框仍精确居中。⚠️ **改的是 `vue/labilibili`（真正打进镜像的源码）→ 必须 `docker compose build frontend`**；只改 `src` 时 `yarn install` 层命中缓存，构建约 55s |
| **FIX-17** | ① `docker/frontend/nginx.conf` · `location /wschat`；② `java/notice/.../mapper/NoticeServiceMapper.java` · `getCommentLikeNotice` | **「我的消息」页一进去就弹两层红条**：`WebSocket error出错：[object Event]` ×2 + `获得点赞消息失败：接受响应后的错误：` ×2。**是两个互不相关的缺陷叠在同一页上**，很容易当成一个「消息服务坏了」 | **① WebSocket 握手 404**：前端 `ChatContent.vue` 连的是 `new WebSocket("/wschat")`，而 `/wschat` **只是前端 dev-server 的代理前缀**（`vue/labilibili/vue.config.js` 的 `devServer.proxy` 里 `/wschat` 一段），**后端没有这个路径** —— chat 服务真注册的端点是 `chat/constant/Constant.WEBSOCKET_CONNECT_PATH = "/ljl/bilibili/chat"`（见 `chat/config/WebSocketConfig.registerWebSocketHandlers`）。nginx 原来把 `/wschat` 原样转发给 `chat:1688` → 实测 `HTTP/1.1 404 {"path":"/wschat"}` ⇒ 握手失败 ⇒ 浏览器 `onerror`。**报错文案里看不到真因**：`onerror` 收到的是 `Event` 对象，模板串拼接后只剩 `[object Event]`，真原因只出现在浏览器 Console（`WebSocket connection to 'ws://localhost:2023/wschat' failed`）。**② SQL 引用了不存在的别名**：`getCommentLikeNotice` 的 `WHERE t.user_id = #{userId}` 里 `t` **在整条语句中从未定义**（FROM 用的是 `c1 / c2 / u / ln`）→ MySQL `Unknown column 't.user_id' in 'where clause'` → `GlobalExceptionHandler` 返 **600** → axios 把非 2xx 走 error 分支 ⇒ 前端 catch 里那句 `接受响应后的错误：`（该文案来自 `api/index.js` 的响应错误拦截器，是**通用**兜底，所以看不出是哪个接口）。同一语句还有两处连带缺陷：`INNER JOIN comment c2 ON (c2.id = ln.sender_id)` 把**用户 id 当 comment id** 去 join（`like_notice.sender_id` 是**点赞人**的 user id，见 `LikeRequest.toAddOrDeleteNotice()` 与同文件 `getVideoLikeNotice` 的 `u.id = ln.sender_id`）——既取错人，又会**静默丢行**；且漏选 `createTime`（VO 有该字段、`ThumbsUpMe.vue` 也在渲染它，原来恒为空白） | ① nginx 的 `/wschat` 段加 `rewrite ^/wschat$ /ljl/bilibili/chat break;`（**与同文件 `/api/` 完全同款**：变量形式 `proxy_pass` 下 nginx 不做任何自动改写，必须显式 rewrite）。**改的是挂载进容器的 `nginx.conf`，`docker exec labilibili-front nginx -s reload` 秒级生效，不需要重建前端镜像、也不动 `vue/**` 任何一行**（dev 模式仍走 webpack 代理，两不干扰）。② `WHERE t.user_id` → `WHERE c1.user_id`（收件人 = **被点赞评论的作者**；口径对齐 `getVideoLikeNotice` 的 `v.user_id` 与 `getNoticeCount` 里 `commentLikeCount` 的 `eq(Comment::getUserId, userId)`）；顺带把 `INNER JOIN comment c2 ON (c2.id = ln.sender_id)` 换成 `INNER JOIN user u ON (u.id = ln.sender_id)`，并补 `ln.create_time AS createTime` + `ORDER BY ln.create_time DESC`。**判据**：① 真实 WS 客户端连 `ws://localhost:2023/wschat` → `OPEN` + 收到服务端主动推的 `{"type":"sessionId",…}` + **零 `error` 事件**且连接保持（原来 404）；② `curl /api/getNotice/getLikeNotice/6` → `{"code":200,…}` 且返回 `userId/nickName/userCover` 是**点赞人**、`content` 是**被点赞的评论**、`createTime` 非空（原来 600）；③ 消息页调用的**全部 8 个接口**（`getNoticeCount` / `getLikeNotice` / `getCommentNotice` / `getDynamicVideo` + 3 个 `changeNoticeStatus/*` + `chat/getHistoryChatSession` / `getHistoryChat`）逐个 200。⚠️ 改 `java/notice` → 需 `docker compose build notice` 并 `up -d --force-recreate notice` |
| **FIX-18** | 仅 `docker/config/gateway/application-dev.yml`（**纯外置配置，零源码改动**，也不需重建镜像） | **视频详情页右侧「其它视频合集」弹红条**：`查看视频合集中的视频失败：接受响应后的错误：`；横幅提示与页面内容都不出来。同族还有 3 处（个人主页合集、新建/修改合集、最近点赞）也一律失败 | **网关没有 `/user-center/**` 这条路由。** 前端的调用风格是混的：绝大多数接口直接写 `/<接口>`（如 `/ensemble/**`、`/selfCenter/**`，网关 `route2` 已覆盖），但有 **4 处**写成了 `/user-center/<接口>`（`vue/labilibili/src/api/video.js` 的 240 / 335 / 354 / 386 行）。网关配置里开着 `spring.cloud.gateway.discovery.locator.enabled: true` + `lower-case-service-id: true`，其作用正是把 `/<服务名>/**` 自动路由到该服务并去掉前缀 —— 但它**依赖 nacos 里有服务注册**，而本机 `nacos/v1/ns/service/list` 实测 **`{"count":0}`**（且 `java/video`、`java/search` 的源码里 nacos 段本来就是注释掉的）⇒ 该机制**整体空转**，`/user-center/**` 只能落到网关自己的兜底路由 → **`404 {"path":"/user-center/ensemble/…"}`**。**这不是前端写错**：`java/gateway/.../SecurityConfig.java` 第 33 行的放行白名单里明确列着 `"/user-center/**"`，说明上游线上就是靠 locator 自动生成这条路由的。**报错文案同样指不到真因**：axios 的响应错误拦截器（`api/index.js`）把原因吞成固定串 `接受响应后的错误：` | 在路由列表里补一条**显式路由**，复刻 locator 的行为（`Path=/user-center/**` + `StripPrefix=1`，等价于 locator 生成的 `RewritePath=/user-center/(?<remaining>.*), /${remaining}`）：
```yaml
- id: route2b
  uri: http://user-center:3000
  predicates:
    - Path=/user-center/**
  filters:
    - StripPrefix=1
```
**判据**：`curl http://localhost:8200/user-center/ensemble/getAllVideoInEnsemble/1/1` 由 **404 → 200**；造一套真实合集（建合集 → 加两条视频 → 查）后，该接口返回 `totalVideoCount:2 / placeInTotal:1 / videoInEnsembleResponses:[两条]`，`getEnsembleList` 返回合集本体，原有不带前缀的 `/ensemble/**` 回归仍 200。⚠️ 只改了 `docker/config/gateway/`（bind 挂载）→ `docker compose restart gateway` 即可生效，**无需重建任何镜像、不动 `java/**` 与 `vue/**`** |
| **FIX-19** | ① `java/search/.../service/impl/SearchServiceImpl.java`（3 处 `minScore`）；② `java/search/.../handler/MysqlToEsHandler.java`（增量同步 5 处）；③ `docker/config/search/application.yml`（新增 `bilibili.search.min-score`）；④ **运行期数据**：`xxl_job` 库补「执行器分组 + 任务」 | **搜索页一进去就弹红条**：`请求搜索结果的页数和文件数失败：接受响应后的错误：`，页面任何结果都没有。同一条链路上还躺着两个更隐蔽的缺陷：**新投稿的视频搜不到**、**已删除的视频删不掉**（搜索里搜得到、点进去 404） | **根因 0（直接原因）**：`GET /search/totalKeywordSearch/{kw}` 到达 search 服务后，ES 里**一个索引都没有** → `Elasticsearch exception [type=index_not_found_exception, reason=no such index [video]]` → `GlobalExceptionHandler` 返非标准 **600** → axios 走 error 分支 ⇒ 前端那句**通用**兜底文案（`api/search.js` 的 `fetchVideoResNum`）。`curl http://localhost:19200/_cat/indices?v` 实测**只有一个表头、零索引**。**根因 1（为什么没有索引）**：索引不是自动建的，靠「MySQL→ES」同步链路 —— video/user 服务的写操作发 MQ（`topic=mysqlToEs`）→ notice 的 `MysqlToEsConsumer` 落进 redis 列表（`add:video` / `update:video` / `delete:video` / `add:user` / `update:user`）→ search 的 **xxl-job 任务 `mysqlToEs`** 消费这些列表写入 ES（见 `search/handler/MysqlToEsHandler` 与 `search/service/impl/MysqlToEsServiceImpl`）。而本机 `xxl_job_info` 里**只有一条停用的 `demoJobHandler`**、`xxl_job_group` 里**根本没有 search 执行器的分组**。⚠️ **最易误判的一点**：search 的执行器其实**已注册成功** —— `xxl_job_registry` 里有 `demo-service-impl → http://172.18.0.15:9916/`（配置见 `docker/config/search/application.yml` 的 `xxl.job.executor.appname`）。**「执行器注册成功」≠「有执行器分组、有任务」**，两者在 admin 里是三张不同的表。⇒ 该任务自部署起**一次都没执行过**，redis 里积压了 5 条陈旧 `add:video`（其中 4 条对应的视频早已被我删掉——若直接触发同步会把它们当「幽灵文档」写进 ES）+ 3 条 `add:user`。**根因 2（补齐后仍搜不到）**：`minScore(1.0f)` 是**绝对**分数阈值，而 ES 的 BM25 分数量级随索引规模变化 —— 本机只有 1 条视频时，「顺德」精确命中标题「顺德一日行」的 score 仅 **0.36**，被 >1.0 的阈值直接滤掉 ⇒ **接口返回 200、结果恒为空**（A/B 实证：同一查询加 `min_score:1.0` → `value:0`；去掉 → `value:1, max_score:0.36464313`）。注意 `userPageKeywordSearch` **没有**这个阈值，所以「用户搜索」是好的、只有视频两条路径受影响。**根因 3（新投稿搜不到）**：增量与全量写的**字段名不一致** —— 增量消息由 video 服务用 SNAKE_CASE 序列化 `Video` 实体，键是 `name` / `user_id` / `video_length`；而全量同步（`MysqlToEsServiceImpl.videoMysqlToEs`）写的是 `video_name` / `author_id` / `author_name` / `length`。而 `totalKeywordSearch` / `videoPageKeywordSearch` **只检索 `video_name` / `author_name` / `intro`** ⇒ 新视频的文档在 ES 里**确实存在**、但只有一个 `name` 字段，**永远搜不到**（实测：投稿后轮询 150s 恒 0 条；`GET /video/_doc/11` 显示字段名就是 `name`）。**根因 4（任务报 500）**：update 分支用布隆过滤器「判定不在 ES 里就跳过」，而过滤器**只在首次全量同步时填过一次**（在 `hasSynchronousVideo` 那个 `if` 里，此后永不刷新）⇒ 新视频的更新必然被判为「不存在」而跳过；**全部跳过时会发出一个空的 `BulkRequest`** → ES `ActionRequestValidationException: Validation Failed: 1: no requests added;` → `xxl_job_log.handle_code=500`。**根因 5（删不掉）**：删除消息的 id 键名**又是第三套** —— video 的 `delete()` 用 `TABLE_ID`（= `"id"`）下发 `{table, type=delete, id}`，而 search 的删除分支硬读 `VIDEO_INDEX_ID`（= `video_id`）→ 取到 `null` → `DeleteRequest(indexName, "null")` ⇒ 删的是一个不存在的文档。（实测 redis 里拿到的是 `{"@type":…,"id":10}`，直接印证。）另有一处：`bulkOpreateUntilAllSucess` 重试时误传 `bulkRequest`（而非只含失败项的 `bulkRequest1`）⇒ 一有失败就整批重发。 | ① **补 xxl-job（运行期数据，与仓库文件无关）**：`xxl_job_group` 加一行 `app_name='demo-service-impl'`，`xxl_job_info` 加一行 `executor_handler='mysqlToEs'` + `schedule_type='CRON'` + `schedule_conf='0 0/1 * * * ?'` + `trigger_status=1` + `trigger_next_time=0`（等价于 admin 页面「执行器管理→新增」+「任务管理→新增」；**`address_list` 不用手填**，注册表里的地址会在 ≤30s 内被自动回填）。② `minScore(1.0f)`（3 处）→ `@Value("${bilibili.search.min-score:1.0}")`（**默认值原样保留，上游线上零影响**），本地在 `docker/config/search/application.yml` 覆盖为 **0.1**（只做「完全不相干」的兜底去噪；**语料涨到几百条后应调回 1.0 附近**，否则搜索噪声会变多）。③ `MysqlToEsHandler` 新增 `normalizeVideoDocument()`：把增量文档的 `name→video_name`、`user_id→author_id`（并据 `author_id` 查 `UserMapper` 补 `author_name`，否则「按作者名搜」搜不到新视频）、`video_length→length`，同时丢掉 redis 反序列化带进来的 `@type` 与 notice 侧残留的 `table`。④ update 分支**删掉布隆过滤器判定**，改用 `.doc(map).docAsUpsert(true)`（存在则局部更新、不存在则创建 —— 不需要猜存在性），连带移除那套只填一次的过滤器字段与刷新代码；`mysqlAddToEs()` 末尾加 `bulkRequest.numberOfActions()==0` 兜底返回，杜绝空 BulkRequest。⑤ 删除分支兼容 `video_id` 与 `id` 两种键名。⑥ 重试改传 `bulkRequest1`。⚠️ ②~⑥ 全在 `java/search` → 需 `docker compose build search` 并 `up -d --force-recreate search`；**② 另需改 `docker/config/search/application.yml`（bind 挂载，随容器重建生效）**。**判据**：`curl /api/search/totalKeywordSearch/顺德` 由 **600 → 200** 且 `total_video_num=1`；`videoKeywordSearch/顺德` 返回真实视频（含 `video_name`/`author_name`/`cover`/`url`/`length`/`play_count`）；**真实投稿一条视频后，不等任何重启、在一个调度周期内即可搜到**（`GET /video/_doc/<新id>` 应同时有 `video_name`+`author_name`+`length`，且**不含** `play_count` —— 「不含」正是它由增量路径写入的判据）；调真实删除接口 `POST /createCenter/delete` 后，ES 文档在一个周期内消失、搜索归零；`xxl_job_log.handle_code` 连续为 **200**（修复前 14:50 那条为 **500**） |
| **FIX-20** | `vue/labilibili/src/pages/message/chat/ChatPage.vue`（模板 + 样式；**上游前端布局缺陷**） | 「我的消息 → 我的聊天」页面右上角的类型切换 tab（文本 / 图片 / PPT，`type="card"`）**下半截扎进下方对话框**（`.chat-box`）内部；被压住的那一段**点不动**（点击被对话框吃掉），tab 与对话框右边缘也不齐 | `ChatPage.vue` 把 tab 组写成 `.chat-content`（`display:flex; flex-flow:column nowrap; align-items:baseline`）的**绝对定位子元素**：`position:absolute; top:10px; right:10px`。而它同一行的标题 `.chat-content-item` **只有约 20~27px 高**，带边框的 card tab 组却有 **56px** ⇒ **必然下溢**；`.chat-box` 的 `margin-top:1rem` 又恰好落在这段下溢区间内。**实测（1600 视口，rem=14）**：tabs `y=133 → bottom=189`（h=56），`.chat-box` `y=164` ⇒ `overlapY=25px`、`gap=−25px`；`document.elementFromPoint(中心)` 命中 `el-tabs__item`（中心点能点），而 `elementFromPoint(下沿−3px)` 命中 **`el-scrollbar__wrap > el-scrollbar > chat-history > chat-box`** —— 即**底部 25px 的点击被对话框吃掉**（这就是「看着被压住」的可判定证据）。属上游绝对定位与流式内容的高度冲突 | 去掉 `.chat-content` 上的 `position:relative`，把 tab 从 `position:absolute` 改为**普通流式子元素**，与标题一起放进新增的 `.chat-content-head` 头栏：`display:flex; flex-flow:row nowrap; align-items:center; justify-content:space-between`，并给头栏**与 `.chat-box` 完全相同的 `width:85%` + `margin:0.4rem 1rem 0 1rem`**（`≥1020px` 时同步提为 `96%`，与 `.chat-box` 的媒体查询一致）⇒ `space-between` 使 tab **右缘自动与对话框右缘对齐**，结构上不可能再重叠。另把 `::v-deep .el-tabs__header` 的 `margin-bottom` 归零（默认 15px 会把头栏撑高、把整个对话框往下推）。**回归判据（5 档视口 1600/1366/1280/1024/900）**：`overlapY=0`、`gap(tab 下沿 → 对话框上沿)=12.1px`、`tabsRight − boxRight = 0`、`overflowX=false`、`elementFromPoint(tab.bottom−3)` 命中 **tab 自身**、点「图片」tab 后 `active: 文本 → 图片 ✓`、`pageerrors: none`。⚠️ **改的是 `vue/labilibili`（真正打进镜像的源码；`vue/src` 是旧副本，改了无效）→ 必须 `docker compose build frontend`**（源码层命中缓存时约 55s）+ `docker compose up -d frontend`；**不动 `java/**`** |

| **FIX-22** | `java/chat/.../constant/Constant.java`（删 5 个常量）、**新增** `java/chat/.../config/SparkProperties.java`、`BigModelHandler` / `PPTHandler` / `WebSocketHandler` / `ChatServiceImpl`（4 处调用点改为注入）、`java/chat/src/main/resources/application.yml`、`docker/config/chat/application.yml`、`docker/docker-compose.yml`（chat 的 `environment`）、`docker/.env.example` | **星火（讯飞）凭据是硬编码占位符，改配置文件完全不生效**；且 `getImage()` 里 TTI 域名 `s291394db` 为应用专属硬编码域名 | `Constant` 里 `public static final String appId = "xxx"` 等 **4 个凭据常量 + `MODEL_DOMAIN_VALUE`**：`static final String` + 字面量属 **JLS 常量变量**，`javac` 会把字面量**内联进每个调用点的 .class**（改前证据：`git show HEAD:java/chat/.../constant/Constant.java` 可见 `appId/apiSecret/apiKey = "xxx"`）⇒ 运行期改配置、甚至反射改字段都**无效**，必须改源码重编译。另：对话(v3.5) / TTI 文生图 / AIPPT 在讯飞是**三个独立服务与计费**，原代码却共用同一组常量 —— **「对话能跑」会误导人以为另外两套也能跑** | 新增 `java/chat/.../config/SparkProperties`（`@ConfigurationProperties(prefix="spark")`，`chat` / `tti` / `aippt` 三组 + 启动期**脱敏**日志），4 处调用点全部改为注入；`Constant` 删除那 5 个常量。凭据链路：**`docker/.env`（已在 .gitignore）→ compose 的 `chat.environment` → 容器环境变量 → yml 里 `${SPARK_*:}` 占位符 → `SparkProperties`**；`tti` / `aippt` 未单独配置时在 yml 层**回落复用 chat 凭据**（与改造前行为一致，后续拿到独立 appId 只需往 `.env` 加 `SPARK_TTI_*` / `SPARK_AIPPT_*`，**不用改代码**）。**判据**：① `docker exec chat printenv SPARK_CHAT_APP_ID` 返回真值；② 启动日志 `[星火配置] chat domain=generalv3.5 appId=7f203f3b apiKey=2c3d****8a4b[len=32]`（首尾各 4 位，密钥不落日志）且 `tti` / `aippt` 回落到同一 appId；③ 镜像内 `app.jar` 的 `Constant.class` / `BigModelHandler.class` 已无 `xxx` 字面量、`SparkProperties.class` 在位。⚠️ **只动 `java/chat`** ⇒ 只需 `docker compose build chat` + `up -d chat`（**动 `java/common` 才要重建全部 6 个后端镜像**）。**顺带一条实测结论（勿当故障查）**：讯飞 `APISecret` 直接用控制台**原文**做 HMAC-**SHA256** 签名，**不要做任何编码/解码** —— 真实握手实测：原文 ⇒ `HTTP/1.1 101 Switching Protocols`；把它 base64 解成 hex 再签名 ⇒ `HTTP/1.1 401 Unauthorized {"message":"HMAC signature does not match"}` |

| **FIX-23** | `java/chat/.../vo/response/ImageResponse.java`、`java/chat/.../ppt/MyUtil.java`、`docker/docker-compose.yml`（chat 的 `environment` 补 TTI / AIPPT 透传） | 星火**文生图恒失败**：`GET /chat/getImage/{text}` 一律返回 `HTTP 600 {"code":600,"msg":"...","data":null}`，前端 `subImage()` 只看到"生成失败"，容易误判成「密钥没填对」 | **上游给响应新增字段，打穿了 DTO 反序列化**：`payload.choices.text[]` 里多了 `polishContent`（讯飞自动润色后的提示词），而 `ImageResponse$Payload$Choices$Text` 只声明了 `content / index / role`。Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true` ⇒ 抛 `UnrecognizedPropertyException`；`MyUtil.doPostJson` 用 `catch (Exception e) { e.printStackTrace(); }` **吞掉** ⇒ `imageResponse` 保持 `null` ⇒ 方法末尾 `imageResponse.getPayload()...` **必然 NPE**。**最终吐给前端的 600 与真实原因（未知字段）毫无关系，日志里那条 NPE 才是第一现场**。⚠️ **与凭据完全无关**：同一组凭据直接调讯飞 TTI 端点返回 `code:0 Success` + 1.93MB PNG Base64（实测），鉴权与配额都正常 | `ImageResponse` 每一级加 `@JsonIgnoreProperties(ignoreUnknown = true)`（防上游再加字段）并**显式声明 `polishContent`**（保留上游给出的可用信息）；`MyUtil.doPostJson` 去掉调试残留 `System.out.println(getEntity())`、**不再吞异常**，改为显式校验 `header.code != 0` 与 `payload.choices.text` 空值后**带上下文抛出**（新增 `brief()` 截断响应体，避免把 2MB Base64 拼进日志）。另：`docker/.env` **只是 compose 的变量源、不会自动注入容器**，故给 chat 的 `environment` 补齐 `SPARK_TTI_*` / `SPARK_AIPPT_*` 透传（用嵌套默认值 `${SPARK_TTI_APP_ID:-${SPARK_CHAT_APP_ID:-}}` 完成回落）—— ⚠️ **compose 的 `${V:-d}` 与 Spring 的 `${V:d}` 语义不同**（前者"未设**或为空**都用 d"，后者"定义为空串则取空串"），若图省事裸透传空串，反而会把 yml 里的默认值顶成空、**打断回落**。**判据**：① 端到端 `GET http://localhost:2023/api/chat/getImage/a%20cat` 返回 **1,980,584 字节** Base64（原先只有 44 字节的 600），解码后 PNG 签名 `89 50 4E 47 0D 0A 1A 0A`、尺寸 **1024×1024**（存证 `logs/tti-after-fix.png`）；② 同窗口 chat 日志按键 `getImage\|ERROR\|NullPointer\|Unrecognized` **命中 0 条**；③ `docker compose config` 展开后 `SPARK_TTI_APP_ID` = chat 的 appId、`SPARK_TTI_DOMAIN=s291394db`。⚠️ **只动 `java/chat` + compose** ⇒ `docker compose build chat` + `up -d chat` |

| **FIX-24** | `java/chat/.../handler/PPTHandler.java`（重写缺陷部分）、`java/chat/.../service/impl/ChatServiceImpl.java`（`getPPT` 重写）、`java/chat/.../vo/response/PPTResponse.java`（+`pptId`/`pptUrl`）、`java/chat/.../ppt/ApiClient.java`（错误路径不再打 stdout）、**删除** `java/chat/.../ppt/Main.java`、`vue/labilibili/src/pages/message/chat/ChatContent.vue`（下载入口 + loading + 异常提示） | **AIPPT（AI 生成 PPT）只到大纲+正文、从不出 pptx 文件**；且同一条链路上埋着 3 个能拖垮 chat 服务的缺陷。接口实测**是通的**（`GET /api/chat/getPPT/{describe}` 能返回封面图与逐章节正文），但 `PPTResponse` 只有 `coverImgSrc` / `pptWordList`，前端也只渲染文字大纲 ⇒ **用户拿不到真正的 PPT 文件** | ① **能力缺口**：`getPPT` 从未调用 `createPptByOutline` / `checkProgress`，只走 `createOutline` + 星火逐章节扩写。② **`System.exit(0)`**：`PPTHandler.onFailure` 里星火一抖动就**杀掉整个 chat JVM** —— AIPPT 一次生成为**每个子章节**各建一条 WS 连接（实测 8~10 条），缺陷概率被放大 N 倍。③ **死等**：`while (pptDetail.getGenerateEnding() == false) { sleep(200); log.info("还没打印完"); }` **无上限**，星火不返回 `status==2` 会永久占住 Tomcat 请求线程；且每 200ms 一条日志，一次生成把容器日志撑大数 MB。④ **发送后立即 close**：`run()` 里 `send()` 后仅 `sleep(200)` 就 `close(1000,"")`，而星火 v3.5 是**流式**返回，200ms 内必然收不完 ⇒ 与 `onMessage` 形成竞态，可能丢尾包（`status==2`）→ 直接诱发 ③。⑤ 单章异常 `throw new RuntimeException` ⇒ 整个请求 600。⑥ `canAddHistory()` 无条件 `remove(0..4)`，列表不足 5 条时越界；调用方拿到 `false` 后**又** `remove(0)`，属双重删除。⑦ `Main.java` 是 SDK 示例类，含 `appId="xxx"` 凭据占位与死循环轮询示例，会被打进 jar | ① `getPPT` 末尾补齐 **`createPptByOutline` → `checkProgress` 轮询**（上限 `PPT_RENDER_TIMEOUT_MS=180s`、间隔 5s），返回 `pptId` / `pptUrl`；文件生成失败**不影响**大纲与正文返回。② `PPTHandler.onFailure` 去掉 `System.exit(0)`，改为 `markFailed()` + 释放连接（新增 `volatile finished` / `errorMsg` 与 `isFinished()` / `getErrorMsg()`）；`onFailure` / `onClosed` 双向兜底，超时与失败**都能退出等待**。③ 等待改为 `while (!isFinished() && now < deadline)`，`PPT_CHAPTER_TIMEOUT_MS=30s`；超时或单章失败**按已收到内容降级**，不再整体 600；删掉每 200ms 的进度日志。④ `run()` 不再提前 close，统一由 `onMessage(status==2)` 收尾关连接。⑤ `send()` 给 `OkHttpClient` 加 `connectTimeout=10s` / `readTimeout=60s`（原先无任何超时）。⑥ `canAddHistory()` 改为「从最老开始裁剪、保留至少 1 条」，调用方只负责 add。⑦ 删除 `Main.java`（全仓无引用）；`ApiClient` 错误路径不再 `System.out.println` 整个响应体，改为把**截断后**的 body 带进异常。前端 `ChatContent.vue`：PPT 卡片新增「下载 PPTX 文件」入口（`v-if="chatItem.pptUrl"`、`target="_blank"`）、`subPPt` 接收 `pptUrl` 并补 `try/catch` + `ElMessage` 失败提示、`submit` 的 `type=='2'` 分支置 `loading`（全流程实测 ~67s，原先无任何反馈、用户会重复点击**每次都新建一轮计费请求**）。**判据**：① 上游契约实测 —— `createByOutline` 的 `outline` 参数**必须是 JSON 字符串**（传对象 ⇒ `code:-1 请求体body有误`），故沿用 `getOutline()` 的 String 值，**不要"顺手改成对象"**；`progress` 实测 `30% → 70% → 100%` 后返回 `pptUrl`。② 端到端 `GET http://localhost:2023/api/chat/getPPT/{describe}` **67s / 5,465 字节**，含 `pptUrl` 与 10 个有正文的章节。③ 下载该链接：HTTP 200、**1,717,482 字节**、头 `50 4B 03 04`、含 `[Content_Types].xml`、**18 张幻灯片**（存证 `logs/aippt-after-fix.pptx`）。④ `docker exec labilibili-front grep -rl '下载 PPTX 文件' /usr/share/nginx/html` 命中 ⇒ 改动确实进了前端产物。⚠️ 需 `docker compose build chat frontend` + `up -d chat frontend`（**后端编译 33 个源文件** —— 34 减掉删除的 `Main.java`）。**遗留**：`pptId` 实测为 `null`（上游 `progress` 只回 `pptUrl`），前端只用 `pptUrl`；整条链路是**同步阻塞**（一次 ~67s 占一个 Tomcat 线程），并发量上来需改异步 + WS 推送 |

> **补充实测（无需修改，仅记录）**：
> - **ES 索引与 xxl-job 任务都是「一次性运行期资产」，不在仓库里**：换机器 / `down -v` 清库后，`xxl_job` 库里的分组与任务会**一起消失**，需要照 FIX-19 ① 重新补；否则搜索永远 600、且**不会有任何报错指向 xxl-job**。快速自检：`docker exec mysql mysql -uroot -puser xxl_job -e "select id,executor_handler,trigger_status from xxl_job_info"` 应能看到 `mysqlToEs`。
> - **搜不到时先分清「没索引 / 没数据 / 被阈值滤掉」三种**：`_cat/indices?v` 看索引是否在 → `_search?pretty` 看文档是否在、字段名对不对 → 同一查询加/去 `min_score` 看是否被阈值吃掉。三者症状都是「接口 200、结果为空」，**只看应用日志区分不出来**。
> - **别用 `curl` 在 Windows 控制台直接传中文关键词**：控制台按 GBK 编码发送 → 后端收到乱码 → 中文查询恒 0 命中（**看起来像搜索坏了**）。要么用 `%E9%A1%BA%E5%BE%B7` 这种 UTF-8 百分号编码，要么用 Python/浏览器发起。
> - **无封面投稿在搜索结果里的封面是「默认头像」而非首帧**：首帧由 `VideoEncodeConsumer` 截取后**写回 MySQL**，而它发出的增量消息只带 `video_length`、不带 `cover` ⇒ ES 里那份文档的封面停留在插入时的值，直到 search 重启触发一次全量同步。**前端真实流程会把 `uploadPart` 返回的 base64 封面回传给 `uploadTotal`，所以正常投稿不受影响**；只有「调用方不传封面」时才会看到。
> - `yarn install --frozen-lockfile` **必然失败**（`Your lockfile needs to be updated`）—— `yarn.lock` 与已被增补过的 `package.json` 不同步，属仓库既有状态；Dockerfile 的 `\|\| (rm -rf node_modules && npm install --legacy-peer-deps)` 降级链是**常规路径而非兜底**。
> - `docker compose pull` **一旦遇到某个镜像 `denied` 会中断整批**，把其余仍在下载的镜像一并标为 `Interrupted`。逐个 `docker pull` 可绕过，也便于定位。
> - `docker manifest inspect` **不走 registry mirrors**（直连 `registry-1.docker.io`，实测被 Docker Desktop 内部代理 `http.docker.internal:3128` 返回 502），**不能用它判断 tag 是否可拉**；只能真实 `docker pull`。
> - **验证「容器换 IP」类修复时，`--force-recreate` 往往不够**：Docker IPAM 取「最低空闲 IP」，重建后常把**同一个 IP 还给同一容器**（实测 nacos / broker / xxl-job-admin / gateway 连续重建多次，IP 均未变化，一度让 FIX-10 的回归测试得出**假阳性**）。要确定性验证，须先用**占位容器抢占旧 IP**，再让目标容器重连（方法见 FIX-10）。
> - **不要用 `docker network disconnect/connect` 手动改容器 IP**：它会在宿主留下**陈旧的端口代理**（实测 `localhost:8200` 与 `127.0.0.1:8200` 均变成「基础连接已经关闭：连接被意外关闭」，`wslrelay.exe` 与 `com.docker.backend.exe` 各持一个监听），**必须 `docker compose up -d --force-recreate <svc>` 才能恢复干净的端口发布**。该方法**仅限一次性诊断**，勿作常规运维手段。
> - ⚠️ **造测试视频时别用「头部 + 填充」拼文件**：曾经为了绕开「没有真视频素材」而手工拼了一个 2.4MB 的文件（`ftyp` + `free` + `mdat`，**没有 `moov` box**，且 `mdat` 长度还是虚标的）。这种文件**上传链路全部通过**（nginx 200 / 落库 / minio / `Range` 206），只有 `VideoEncodeConsumer` 里的 jave 会报 `ws.schild.jave.InputFormatException: Invalid data found when processing input` —— **看起来像解码器故障，其实是测试样本本身不是合法 MP4**。判据是文件里有没有 `moov` box（可用 Python 遍历顶层 box 检查）。正确的自造样本：直接借容器内 jave 自带的 ffmpeg 生成，例如 `docker exec notice /tmp/jave/ffmpeg-amd64-3.3.1 -f lavfi -i testsrc=duration=3:size=640x360:rate=24 -c:v libx264 -pix_fmt yuv420p -y /tmp/real.mp4`，再 `docker cp` 出来（**宿主没有 ffmpeg 也能造样本**）。

### 11.2 内存限额校准（2026-09-22 已完成，对应 D-08 修订 R1）

全套 16 容器稳定后实测合计约 **7.4 GiB / 10.69 GiB（69%）**，比 §2 的预估（11.3~13.3GB）宽裕不少。
**但首版 `mem_limit` 是估算值，真机一跑就有 7 个容器贴顶。** 已按「实测占用 ≥85% 即校准」统一处理：

| 容器 | 堆 | 原限额 | 实测占用 | 原占比 | 校准后 | 校准后占比 |
|---|---|---|---|---|---|---|
| `rocketmq-broker` | `-Xmx768m` | 1024m | 968.8 MiB | **94.6%** | **1280m** | 76.1% |
| `nacos` | `-Xmx512m` | 768m | ~712 MiB | 92.7% | **1024m** | 67.4% |
| `xxl-job-admin` | （未设堆） | 512m | ~474 MiB | 92.6% | **640m** | 76.1% |
| `gateway` | `-Xmx512m` | 640m | 547.5 MiB | 85.6% | **1024m** | 58.2% |
| `chat` | `-Xmx512m` | 640m | 551.6 MiB | 86.2% | **1024m** | 52.8% |
| `search` | `-Xmx640m` | 768m | 664.2 MiB | 86.5% | **1024m** | 58.7% |
| `user-center` | `-Xmx512m` | 640m | 559.3 MiB | 87.4% | **1024m** | 54.7% |

**未触发阈值、维持原限额**：`notice` 75.4%、`video` 64.8%、`elasticsearch` 56.4%、`zipkin` 53.2%，其余 <50%。

- **只上调 cgroup 限额，不动 JVM 堆**。限额是**上限而非预留** → 抬高后常驻占用不变。
- **后续维护规则**：调大某容器 `-Xmx` 时，须同步确认 `mem_limit ≥ -Xmx × 1.5`；
  每次改动后 `docker stats --no-stream` 复核，**占用率应 <80%**。
- 完整裁定与理由见 `deliverables/software-company/docker/01-主理人决策.md` 的 **D-08 修订 R1**。
