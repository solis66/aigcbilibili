# =============================================================================
# 后端多阶段构建（参数化 MODULE）—— 6 个服务共用本文件
# 约束落实（D-04）：
#   - 构建阶段用 JDK 8 + Maven 3.8.6；运行阶段用 JRE 8（严禁 11/17）
#   - -s 与 -gs 必须**同时**指定，均指向 docker/maven/settings.xml
#     （本机全局 Maven 配置指向不存在的路径，只给 -s 绕不开；该 settings 内无 localRepository）
#   - 依赖播种：COPY .m2-repo/ -> /root/.m2/repository/（架构 §6.2 选定方案 B，镜像层播种）
#     ⚠️ 种子为**可选加速**：仓库/源码包里只有 .m2-repo/PLEASE_READ.txt 占位，
#        此时 Maven 走 docker/maven/settings.xml 的阿里云镜像在线拉依赖（首次较慢，属预期）。
#     6 个服务共用同一 Dockerfile + 同一上下文，BuildKit 对 deps 阶段只计算一次 → 只播种一次
#   - 构建上下文 = 仓库根目录（compose 中 context: ..），故路径以仓库根为基准
# 用法：docker build -f docker/backend/Dockerfile --build-arg MODULE=gateway -t aigcbilibili/gateway:1.0 .
# =============================================================================

# ---------- 共享依赖层（deps）----------
FROM maven:3.8.6-openjdk-8 AS deps
WORKDIR /build
# 1) 播种迁出的 .m2-repo（317.8MB）→ 首构建零依赖下载（D-04 验收点）
COPY .m2-repo/ /root/.m2/repository/
COPY java/ /build/java/
COPY docker/maven/settings.xml /build/docker/maven/settings.xml

# ---------- 预置 jave 的 ffmpeg 二进制（FIX-14，2026-09-23 本机实测新增）----------
#   背景：jave 的 DefaultFFMPEGLocator 在**首次使用**时才把 ffmpeg 从 jar 拷到
#         $java.io.tmpdir/jave/ffmpeg-<os.arch>-<jave版本>，随后用**外部 /bin/chmod 进程**
#         补可执行位，而两者之间**没有任何等待** → 紧接着 exec 该文件就存在竞态。
#   现象：容器启动后**第一次**用到 jave（video 投稿分片截帧 / notice 视频转码）必抛
#         ws.schild.jave.EncoderException: Cannot run program
#           "/tmp/jave/ffmpeg-amd64-3.3.1": error=13, Permission denied
#         （实测：容器内 ls -l 该文件已是 -rwxr-xr-x、进程是 uid=0、/ 也不是 noexec，
#           即「文件本身没问题，只是 chmod 还没落到该 inode 上」）
#         后果：投稿的第一个分片返回 600，前端显示失败；再点一次/等 resumable 重试才会成功。
#   处置：镜像里就把二进制放到 jave 期望的路径且带可执行位 —— jave 检测到 exists 即跳过
#         copy+chmod 分支，竞态从根上消失（无需改 java 源码）。
#   ⚠️ 路径与文件名必须严格匹配 jave 的拼接规则 ffmpeg-<os.arch>-<Version.getVersion()>。
#      此处是 linux/amd64；若换 arm64 机器，需改用 jave-nativebin-linux-arm64 并同步改文件名。
# FIX-15（分发修复）：无 .m2-repo 种子时（从源码包/GitHub 首次构建），该 jar
#   尚未在本地仓库中，而本 RUN 位于 mvn 之前 → 原写法 `jar xf` 必报
#   "Unable to access jarfile" 并中断整个构建。
#   处置：按需先从镜像源拉取该 artifact（有种子时命中缓存，行为与本机完全一致）。
RUN mkdir -p /jave-seed \
 && JAR=/root/.m2/repository/ws/schild/jave-nativebin-linux64/3.3.1/jave-nativebin-linux64-3.3.1.jar \
 && if [ ! -f "$JAR" ]; then \
      mvn -q -f /build/java/pom.xml dependency:get \
          -Dartifact=ws.schild:jave-nativebin-linux64:3.3.1 \
          -s /build/docker/maven/settings.xml \
          -gs /build/docker/maven/settings.xml \
          -Dmaven.repo.local=/root/.m2/repository; \
    fi \
 && cd /jave-seed \
 && jar xf "$JAR" ws/schild/jave/nativebin/ffmpeg-amd64 \
 && mv ws/schild/jave/nativebin/ffmpeg-amd64 ./ffmpeg-amd64-3.3.1 \
 && chmod +x ./ffmpeg-amd64-3.3.1 \
 && ls -l ./ffmpeg-amd64-3.3.1

# ---------- 按模块构建 ----------
FROM deps AS build
ARG MODULE
# -pl ${MODULE} -am：只构建目标模块及其依赖（common），避免重复构建整个 reactor
RUN mvn -f /build/java/pom.xml -pl ${MODULE} -am -DskipTests \
        -s /build/docker/maven/settings.xml \
        -gs /build/docker/maven/settings.xml \
        -Dmaven.repo.local=/root/.m2/repository \
        clean package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:8-jre AS runtime
ARG MODULE
WORKDIR /app
# var/temp 供 video 的 multipart 相对路径落点（spring.servlet.multipart.location=var/temp）
RUN mkdir -p /app/logs /app/config /app/var/temp /tmp/jave
# FIX-14：把预置的 ffmpeg 放到 jave 期望的路径（原理见上面 deps 阶段的说明）。
#   注意 /tmp 属于镜像层 —— 容器启动时 Docker **不会**清空 /tmp，故运行时可见，
#   jave 的 `ffmpegFile.exists()` 判定为真 → 直接短路，不再走 copy+chmod 的竞态分支。
COPY --from=deps /jave-seed/ffmpeg-amd64-3.3.1 /tmp/jave/ffmpeg-amd64-3.3.1
RUN chmod +x /tmp/jave/ffmpeg-amd64-3.3.1 && ls -l /tmp/jave
COPY --from=build /build/java/${MODULE}/target/*.jar /app/app.jar
ENV JAVA_OPTS=""
ENV MODULE=${MODULE}
# -Duser.home=/app 硬编码兜底（D-07：防 RocketMQ 客户端日志外溢，对应上一轮 D7）
ENTRYPOINT ["sh", "-c", "java -Duser.home=/app $JAVA_OPTS -jar /app/app.jar"]
