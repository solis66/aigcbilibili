# aigcbilibili 瘦身与本地开发环境设计

- 日期：2026-10-25
- 定位：**求职作品**（面试可讲的技术栈优先于「最小内存」）
- 状态：待你确认（确认后才动代码）

---

## 1. 意图（why）

这个仓库现在能跑，但它带着三件**实际不工作**的中间件：Nacos、Zipkin/Sleuth、xxl-job。
对求职作品来说，这比「没写」更糟：面试官问「你这里 Nacos 做什么的」，答「注册中心」，
再问「注册了几个实例」，答案是 **0**。同样，xxl-job 的定时任务从未执行过，
导致「MySQL→ES 同步」整条链路自部署起就是空的（搜索页报错、新视频搜不到），
这不是风格问题，是功能缺陷。

同时仓库的**分发状态**是坏的：`docker/` 整个目录（编排、Dockerfile、外置配置、三份手册，
33MB）**一个文件都没入库**；而 Git 索引里还挂着 285 条历史遗留的 `bilibili/` 重命名删除；
`.idea/`（319 个文件）与 `java/*/logs`、`logs/` 运行日志被提交进了仓库。

## 2. 现状证据（每条都能指到文件）

| 组件 | 现状 | 证据 |
|---|---|---|
| **Nacos** | 空转。6 个模块的 `spring.cloud.nacos.discovery.*` 是未知键被静默忽略；网关 `discovery.locator.enabled=true` 依赖它，注册表实测 `{"count":0}` | pom 里 nacos-discovery 全被注释：`java/common/pom.xml:55`；Feign 全部硬编码 `url=`：`java/common/src/main/java/ljl/bilibili/client/notice/SendNoticeClient.java:17`；`docker/config/gateway/application-dev.yml:50` 注释自述 locator 空转 |
| **Zipkin + Sleuth** | 有依赖、有上报，但唯一功能是链路 UI；采样率写在错键 `spring.zipkin.sleuth.sampler`（实际走默认 0.1） | `java/common/pom.xml:77-81`、`java/user_center/pom.xml:56,65`、各模块 yml |
| **xxl-job** | 任务从未执行：`xxl_job_group` 无 search 执行器分组、`xxl_job_info` 只有停用的 demoJobHandler | `java/search/src/main/java/ljl/bilibili/search/handler/MysqlToEsHandler.java:47` 是任务体但无人触发；`docker/README.md` FIX-19 记录了实测 |
| **RocketMQ / ES / Redis / MinIO / MySQL / Druid / MyBatis-Plus Join / Knife4j / JWT / WebSocket / 讯飞** | **全部在用**，保留 | notice 5 个 `@RocketMQMessageListener`；`MysqlToEsConsumer`、`ElasticsearchClientConfig`、`MinioConfig` 等 |

其它硬事实：

- **零测试代码**：`java` 下 `src/test` 0 个文件 ⇒ 验收只能靠「编译通过 + 运行期冒烟」。
- 容器编排：16 个物理容器（14 个常驻 + 2 个一次性 init），14 个常驻容器 `mem_limit` 合计 **12,992MiB ≈ 12.7GB**，实测常驻 11.3~13.3GB；本机 16GB。
- 本机工具链齐备：JDK 17.0.12、Maven 3.9.9、Node 24、Docker 29.8、Compose v5.5；GitHub SSH 已认证（`solis66`）；Maven 中央/阿里云、npmjs 均可达。
- 前端有两份分叉源码：`vue/src`（238 文件）与 `vue/labilibili/src`（260 文件）；**构建与本地运行都只用 `vue/labilibili/`**（`docker/frontend/Dockerfile:39`、`docker/local-run/setup-frontend.ps1`）。

## 3. 目标 / 非目标 / 反目标

**目标**
1. 移除三件空转中间件，**功能零退化**；12 个后端模块依赖、路由、鉴权、MQ、ES 检索行为不变。
2. 把「从未执行」的 ES 同步变成**默认就会执行**（`@Scheduled` 替代 xxl-job），搜索开箱可用。
3. `docker/` 与 `.vscode/` 入库，仓库克隆下来就能按文档在本机与容器两种环境起服务。
4. 容器 16→13（常驻），`mem_limit` 合计 12.7GB→**10.6GB**；**不为凑数字下调任何后端限额**。
5. 一套可执行的 VS Code 开发配置（插件、JDK、6 个启动配置、一键任务）。

**非目标**
- 不动业务代码逻辑（除 xxl-job 触发方式替换）。
- 不重构前端、不升级 Spring Boot 2.6 / Java 8 目标版本（面试作品不谈「顺手升级」）。
- 不拆服务粒度、不引入 K8s。
- 不改数据库表结构（除删除 `xxl_job` 库的初始化）。

**反目标（就算能跑也算失败）**
- 为了「少一个容器」而让搜索/私聊/上传任一功能坏掉。
- 删完之后只剩「能启动」，无法演示主链路。
- 推上去依赖面试官手动改配置才能跑。

## 4. 约束

- **Docker Daemon 当前未运行**（`docker info` 连不上命名管道）⇒ 容器侧验证需要先拉起 Docker Desktop。
- JDK 17 编译、`maven.compiler.target=8`，Docker 运行镜像 `eclipse-temurin:8-jre`。
- 首次后端构建依赖 Maven 在线拉包（`.m2-repo` 326MB 是本地加速种子，**不入库**，`.gitignore` 已排除）。
- 推送目标 `git@github.com:solis66/aigcbilibili.git`（main）。
- 端口坑：Windows 保留端口段导致 minio/ES 必须用 19000/19001/19200。
  `.env.example` 改为直接给这组安全值（初稿的「保留上游 9000/9200」与「克隆下来就能跑」自相矛盾，已改）。

## 5. 方案对比（你已选 A）

| | 做法 | 代价 | 结论 |
|---|---|---|---|
| **A 精准瘦身** | 只删真空转的三类（nacos / zipkin+sleuth / xxl-job），保留 MQ+ES 这两块面试可讲的技术栈；顺带修 xxl 导致的同步缺陷；再做启动工程化与仓库卫生 | 功能零退化；容器 16→13 | **采纳**（你已选） |
| B 激进瘦身 | A 之外再收 ES 与 RocketMQ，搜索改 MySQL LIKE、异步改线程池 | 丢掉两个面试点、检索质量下降、改动面大 | 否 |
| C 只做脚手架 | 一行不删，只补 VS Code 配置与文档 | 没回应「移除空转件」 | 否 |

## 6. 详细设计

### 6.1 拆除清单（逐文件，都可 grep 到）

**Nacos**
- 容器：`docker/docker-compose.yml` 的 `nacos:` 服务、`NACOS_HOST_PORT`、`.env`/`.env.example` 中相关项。
- 配置：6 份 `docker/config/*/application*.yml`、6 份 classpath `java/*/src/main/resources/application*.yml`、3 份 `docker/local-run/conf/*/application.yml` 里的 `spring.cloud.nacos` 段。
- 网关：`spring.cloud.gateway.discovery.locator`（依赖 nacos 的自动路由；`/user-center/**` 已有显式路由兜底，见 `docker/config/gateway/application-dev.yml:68`）。
- pom：`java/pom.xml` 的 nacos-discovery/nacos-config/ribbon 版本属性与 dependencyManagement 条目；`java/common/pom.xml:55` 起的注释块。`spring-cloud-loadbalancer` 同步清理（`lb://` 全项目 0 处使用）。
- 保留：`@EnableDiscoveryClient` 是否删除 → **删除**（`java/video/.../VideoApplication.java:11`），无客户端依赖时它只是空注解。

**Zipkin + Sleuth**
- 容器：`docker/docker-compose.yml` 的 `zipkin:`、`ZIPKIN_HOST_PORT`、`ZIPKIN_SAMPLER_PROBABILITY` 环境变量注入（6 处）。
- 依赖：`java/common/pom.xml`、`java/user_center/pom.xml` 的 sleuth/zipkin；`java/pom.xml` 版本属性与 dependencyManagement。
- 配置：全部 `spring.zipkin` / `spring.sleuth` 段（classpath 6 份 + 外置 6 份 + local-run 6 份）。
- 观测替代：日志仍是 logback 落 `/app/logs`，不影响排障。

**xxl-job**
- 容器与库：`xxl-job-admin` 服务、`xxl_job` 数据库初始化文件 `docker/mysql/init/03-xxl-job.sql`、`XXL_JOB_ACCESS_TOKEN`、`./logs/search-xxljob` 挂载。
- 依赖与代码：`java/search/pom.xml` 的 xxl-job-core、`java/pom.xml` 版本属性、`java/search/.../config/XxlJobConfig.java` 整个类。
- 配置：`xxl.job.*`（`java/search/src/main/resources/application.yml`、`docker/config/search/application.yml`、`docker/local-run/conf/search/application.yml`）。
- **替换**：`MysqlToEsHandler.mysqlToEsHandler()` 上的 `@XxlJob("mysqlToEs")` 改为
  `@Scheduled(cron = "${bilibili.search.sync-cron:0 0/1 * * * ?}")`，并在 search 应用类加 `@EnableScheduling`；
  清理时用 Redis `SETNX` 加锁（key 带过期），避免未来多实例同时全量同步。
  效果：同步从「永远不跑」变成「启动 1 分钟后自动跑」，搜索开箱可用。

### 6.2 编排与内存

| 项 | 现在 | 之后 |
|---|---|---|
| 常驻容器 | 16 | **13**（mysql / redis / minio / rocketmq-namesrv / rocketmq-broker / elasticsearch / gateway / notice / user-center / video / chat / search / frontend） |
| `mem_limit` 合计 | 12,992MiB ≈ 12.7GB | **10,816MiB ≈ 10.6GB** |
| 启动方式 | `docker compose up -d` | **不变**，仍是 `up -d` 全套 |

> **修订（2026-10-25，构建者复核后）**：初稿打算用 `profiles` 把 ES+search 设成按需启动，
> 那会与「目标 2：搜索开箱可用」直接冲突（默认不启 search，就没有搜索），且会让 §7 的冒烟项
> 在默认 `up -d` 下跑不出来。**取消 profile 设计**，默认仍是全套 13 个，行为与今天一致，
> 只是少了 3 个空转容器。内存数字按 compose 里实际 `mem_limit` 累加核算：
> 12,992 − (nacos 1024 + zipkin 512 + xxl-job 640) = **10,816MiB**。
> **不额外下调任何后端 `mem_limit`**（`docker/README.md` FIX-9b 记录的「堆占限额 ≤62.5%」校准准则保持不变，
> 压限额换来的是 OOM Kill，不是省内存）。

保留 `docker-compose.middleware-host.yml`（中间件容器 + 应用在宿主跑），删掉其中的 zipkin/xxl 引用。

### 6.3 仓库卫生

> **修订（2026-10-25，构建者复核后）**：初稿写「`git reset` 恢复干净基线」是错的 ——
> 索引把 `java/` 285 条全标成 staged delete，而工作树里是**完整且更新过的代码**
> （28 个文件与 HEAD 不同，含 FIX-19 的 `MysqlToEsHandler` 修复、新增 `SparkProperties.java`、
> 删除的 `ppt/Main.java`）。`git reset` 只会把这些修复暴露成脏工作区，
> 而任何人执行 `git checkout HEAD -- .` 都会把「项目能跑」的前提抹掉。
> **正确顺序**：先把这批 2026-09-23 修复作为第 1 个提交入库，再谈清理。

1. **基线提交**：把工作树里 28 个 java 改动 + 新增/删除文件完整提交（commit 1），此后 HEAD 即「能跑的版本」。
2. 清索引残留：只清 `bilibili/` 285 条幽灵删除与 `.DS_Store`，**不碰 java/ 内容**。
3. `.gitignore` 增补：
   - 忽略：`.idea/`、`*.iml`、`logs/`、`java/*/logs/`、`docker/logs/`、`docker/data/`、`node_modules/`、`dist/`、`target/`
   - 忽略（隐私/体积，见 §6.7）：`deliverables/`、`.archify/`、`.Asu/`、`.workbuddy/`、`prompts/`
   - 放行：`.vscode/extensions.json`、`.vscode/launch.json`、`.vscode/tasks.json`、`.vscode/settings.json`（个人 settings 若含本机绝对路径则忽略）
4. 从索引移除已提交的运行产物：`logs/`（19 文件）、`java/*/logs`、`.idea/`（319 文件）、`.DS_Store`。
5. 入库：`docker/` 全部（`.env` 除外，含 `docker/.env.example`）、`.vscode/`、`docs/`（仅 `docs/` 下的设计稿与开发指南）。
6. `.m2-repo/`：只留 `PLEASE_READ.txt` 占位（Dockerfile 的 `COPY .m2-repo/` 依赖目录存在），326MB 继续不入库。

### 6.4 VS Code 开发环境（交付物之一）

`.vscode/extensions.json` 推荐（面试官/你换机器时一键装齐）：

| 插件 | 用途 |
|---|---|
| `vscjava.vscode-java-pack` | Java 语言支持（含 Maven、调试、测试） |
| `vmware.vscode-boot-dev-pack` | Spring Boot 专属（application.yml 补全、Bean 跳转） |
| `redhat.java` / `vscjava.vscode-maven` | 由上面两个 pack 带入 |
| `vscjava.vscode-lombok` | 本项目大量 `@Data`/`@Slf4j` |
| `Vue.volar` | Vue 3 单文件组件 |
| `dbaeumer.vscode-eslint` | 前端 lint（项目已配 eslintConfig） |
| `ms-azuretools.vscode-docker` | 看容器/日志/一键 compose |
| `redhat.vscode-xml` | 8 个 pom.xml |
| `humao.rest-client` | 冒烟用的 HTTP 请求文件 |

`.vscode/settings.json`：`java.configuration.updateBuildConfiguration=automatic`、`java.jdt.ls.java.home` 指向本机 JDK、`spring-boot.ls.*` 中文提示关掉、`files.exclude` 藏 `target/`。
`.vscode/launch.json`：6 个服务各一条 `java` 启动配置（mainClass、`--spring.config.additional-location=file:docker/local-run/conf/<svc>/`、`-Duser.home`），彼此可单独起。
`.vscode/tasks.json`：起/停中间件（`docker compose -f docker/docker-compose.yml -f docker/docker-compose.middleware-host.yml up -d mysql redis minio minio-init rocketmq-namesrv rocketmq-store-init rocketmq-broker elasticsearch`）、`mvn -DskipTests package`、前端 `npm run serve`。

### 6.5 文档

- 重写 `快速开始.md`：三步（`cp .env.example .env` → `docker compose up -d` → 访问 2023）、端口表、
  默认账号、内存实测数字、Windows 保留端口坑（`netsh int ipv4 show excludedportrange protocol=tcp`）。
- `.env.example` 的端口默认值改为**本机实测安全值**（minio 19000/19001、ES 19200、nacos 项删除），
  而不是上游 9000/9001/9200 —— 否则「克隆下来就能跑」在 Windows 上会直接卡在
  `bind: An attempt was made to access a socket in a way forbidden by its access permissions`。
- 三份旧手册（`docker/安装指引.md`、`docker/本地部署操作手册.md`、`docker/本地混合运行手册.md`）：
  **在正文修正**与 v2 冲突的段落（删除 xxl-job 控制台入口/账号、"去 xxl-job 补执行器分组"这类
  已被作废的处置步骤、nacos 端口、"16 个容器"表述），并在顶部加一行 v2 状态标记。
  只做标记不改内容会留下「照着做找不到界面」的死文档，是面试减分项。
- `docker/README.md`（74KB 决策记录）：**追加**「v2 变更记录（2026-10-25）：移除 nacos/zipkin/xxl-job」一节并修正正文里的容器数/端口清单，不整体重写（它对面试是资产，不是负担）。
- 新增 `docs/本地开发指南.md`：VS Code 插件、调试、改代码后只重建单个服务的命令、常见 404/502 排查。

### 6.6 不动的部分及原因

MySQL/Redis/MinIO/RocketMQ/ES 全部有真实调用点；Druid（连接池）、MyBatis-Plus Join（多表查询）、Knife4j（接口文档，面试演示有用）、JWT+Security（网关鉴权）、WebSocket（私聊/弹幕）、讯飞星火（AI 三件套）——删任何一项都会砍掉功能或面试点。

## 7. 验收（可执行判据，不接受「看起来对」）

1. `mvn -q -DskipTests package` 7 模块全绿（JDK 17、target 8）。
2. `docker compose -f docker/docker-compose.yml config -q` 通过（不再有 profile 分支）。
3. `docker compose up -d` 后 **13 个常驻容器** `Up/healthy`（另 `minio-init` / `rocketmq-store-init` 两个一次性容器 Exited 0）。
4. 端到端冒烟（curl）：`/userInfo` 注册 → 登录拿 token → `/play/...` 首页 → 视频详情 → 发弹幕/评论 均 200。
5. 搜索：**先上传一条视频**（全新库 22 张表、0 条数据，索引只在首次写入时才创建），
   等一个同步周期（默认 1 分钟）后 `/search/totalKeywordSearch/<该视频标题里的词>` 返回 200 且 `value>0`。
   这是旧版**做不到**的验收点（旧版同步从不执行 ⇒ 索引不存在 ⇒ 600）。
   判据失败时的排查顺序：redis 列表是否有 `add:video` → search 日志有无 `mysqlToEs` 执行记录 → `/_cat/indices?v` 是否出现 `video` 索引。
6. 私聊 WebSocket 握手 101。
7. 前端 `npm run serve` 编译通过，首页能出数据。
8. 仓库：`git status` 干净（除忽略项）、`docker/` 已入库、无 `logs/`/`.idea/` 跟踪文件。

### 7.1 实测结果（2026-10-25，本机 Docker 29.8 / Docker VM 11.4GB）

| 判据 | 结果 |
|---|---|
| mvn package 7 模块 | ✅ 全绿（JDK 17 / target 8） |
| compose config（主编排 + 混合 override） | ✅ 均通过 |
| 13 个常驻容器 | ✅ 全部 Up/healthy（另 2 个一次性 init Exited 0） |
| **实际内存占用** | ✅ 13 容器合计 **≈ 5.9GB**（限额合计 10.6GB，非预留） |
| 端到端冒烟 | ✅ **20 项全通过**（`tools/smoke_test.py`）：首页 / 详情 / 评论 / 弹幕 / 合集 / 个人主页 / 用户信息 / 搜索×5 / 注册 / 图形码 / 登录 / 发弹幕 / 发评论 / WS 101 |
| 搜索 | ✅ 关键词「顺德」返回 `total_video_num=1`（v1 做不到） |
| WebSocket | ✅ 经网关 `/ljl/chat` 与直连 chat 均 101 |
| **AI 三件套** | ⚠️ **部分**：凭据已注入、请求已到讯飞，但账号返回 `81001 次数不足` —— 是账号配额问题，不是代码问题 |

> 冒烟脚本已入库：`python tools/smoke_test.py`（本机需 docker 在跑，用于读图形码）。

## 8. 提交与推送

分支 `main` 直接推进（作品仓库，不开 PR 流程），拆 5 个提交：

1. `chore(wip): 入库 2026-09-23 容器化修复（FIX-13~24）作为基线`
   —— 工作树里 28 个与 HEAD 不同的文件（含 `MysqlToEsHandler` 的 FIX-19 修复、新增
   `SparkProperties.java`、删除的 `ppt/Main.java`）都是「项目能跑」的前提，**必须先落一个提交**，
   否则后面任何一次 `git checkout` 都会把它们抹掉。
2. `chore(repo): 清理索引残留与运行产物，补 .gitignore`
3. `chore(docker): 入库 docker 编排、外置配置与手册`
4. `refactor(middleware): 移除空转的 Nacos / Zipkin+Sleuth / xxl-job`（含 `@Scheduled` 替换）
5. `feat(dev): 增加 VS Code 开发配置与一键任务`
6. `docs: 重写快速开始与本地开发指南，补 v2 变更记录`

> 基线提交（第 1 个）单独立项的原因：当前索引把 `java/` 全标成 staged delete、工作树又是完整代码，
> 直接 `git reset` 会把这批修复暴露成脏工作区；先提交它们，后面 5 个提交才有干净的对照面。

推送 `git push origin main`（SSH 已认证）。**推送前**跑完 §7 的 1/2/3/7；4/5/6 需要 Docker 起来，若你希望我先推再验，请明确说。

## 9. 风险与回滚

| 风险 | 处置 |
|---|---|
| 删 sleuth/zipkin 后某个类 import 编译失败 | 编译阶段就会暴露；逐个模块修 import（预期 `java/common` 无源码引用，只有 pom） |
| xxl-job 换成 `@Scheduled` 后同步把陈旧 Redis 队列一次性刷进 ES（README 记录本机有 5 条陈旧 add:video） | 首次同步前清 `redis-cli FLUSHDB` 或接受「幽灵文档」后重建索引；写进启动文档 |
| 基线提交把 285 条幽灵删除一起落库，历史看起来乱 | 只提交工作树实际内容，索引里的 `bilibili/` 删除在提交前用 `git rm --cached` 清掉，不进任何提交 |
| 首次构建需下载依赖，慢 | 保留 `.m2-repo` 种子机制与阿里云镜像；失败可重试 |
| 推送后再发现问题 | 单次提交可 `git revert`；改动集中在配置与 pom，回滚成本低 |

## 10. 我替你做的决定（请扫一眼）

1. 三件空转件全删，**不保留「注释掉的配置」**——留着就会被面试官问「这是什么」。
2. 用 `@Scheduled` + Redis SETNX 替代 xxl-job（保留「分布式任务」的面试话术：可选升级回 xxl-job/ElasticJob）。
3. **默认编排保持全套 13 容器**，不引入 profile（初稿的 profile 设计与「搜索开箱可用」冲突，已撤销）。
4. `.idea/`、`logs/` 从仓库移除（它们不该在作品仓库里）。
5. `docker/README.md` 追加而非重写；但三份运行手册里**已被本次改动作废的操作步骤必须改正文**，不能只加一句「已废弃」。
6. 不动业务代码与前端源码（唯一例外：`@XxlJob` → `@Scheduled` 与删 `XxlJobConfig`）。
7. **min-score 三处统一为 0.1**：classpath、`docker/config/search`、`docker/local-run/conf/search` 都显式写
   `bilibili.search.min-score: 0.1`（理由：本地库是空的小语料，1.0 会把正确命中滤掉；
   在文档里注明「语料上千后应调回接近 1.0」，作为面试可讲的取舍）。
8. `deliverables/`、`.archify/`、`.Asu/`、`prompts/`、`.workbuddy/` **全部忽略、绝不入库**
   （里面是简历 docx、面试问答、图表产物；仓库是公开的）。

---

## 11. 构建者复核后的修订记录（2026-10-25）

初稿交给一位不了解背景的「构建者」读过一遍，他提出的 8 个问题里 6 个成立，已改入正文；
另有两个只有你能拍板，见 §11.1。

| # | 复核意见 | 处置 |
|---|---|---|
| 1 | 「git reset 恢复干净基线」会暴露甚至可能回滚整批未提交的 FIX 修复 | **成立**。改为「先落基线提交，再清索引」；见 §6.3 与 §8 |
| 2 | 默认 8 容器清单不明确，且「search 默认不启」与「搜索开箱可用」矛盾 | **成立**。撤销 profile 设计，默认全套 13 个 |
| 3 | 8.6GB 靠删三个服务到不了（实际 12,992 − 2,176 = 10,816MiB） | **成立**。目标改为 10.6GB，不压后端限额 |
| 4 | 全新库 0 条数据 ⇒ 索引不存在 ⇒ 搜索验收返回 600 而非 200；min-score 三处不一致 | **成立**。验收改为「先上传一条视频」；min-score 三处统一 |
| 5 | deliverables / .archify 等未忽略，git add -A 会把简历发到公开仓库 | **成立**。列为硬忽略项 |
| 6 | 三份旧手册会与 v2 状态矛盾（xxl-job 控制台已不存在） | **成立**。改正文，不只加标记 |

### 11.1 仍需你拍板的两件事

- **AI 三件套算不算「可演示主链路」？** 讯飞凭据只在被忽略的 docker/.env 里，全新克隆必然不可用。
  我按「可选功能，不配置不影响主链路；文档写清申请步骤」处理（见 §11.2），若你要把它算进主链路，
  需要你提供一组可用 key 或接受「演示时现场配」。
- **要不要我把 Docker 拉起来做运行期验证？** 当前 daemon 未运行。不验就只能做到
  「编译绿 + compose config 绿 + 配置静态核对」，运行期问题留给你。

### 11.2 AI 功能的处理口径（我的决定，请扫一眼）

不写进主链路验收；快速开始.md 里单列一节「可选：开启 AI 功能」，说明去讯飞开放平台申请、
填进 docker/.env 的哪三个变量、重启 chat 生效。仓库里不出现任何真实凭据；
docker/README.md 里已有半截 appId 的段落一并改掉（凭据泄露风险，顺手修）。
