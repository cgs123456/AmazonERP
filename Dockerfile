# Dockerfile - Amazon ERP 多阶段构建（参数化构建任意微服务）
#
# 用法（默认构建 amz-service-spapi）：
#   docker build -t amazon-erp:latest .
#   docker run -p 8096:8096 amazon-erp:latest
#
# 通过 --build-arg MODULE / PORT 切换构建目标模块和暴露端口：
#   docker build --build-arg MODULE=amz-service/amz-service-spapi --build-arg PORT=8096 -t amz-service-spapi:latest .
#
# 各服务构建命令示例（端口取自各服务 application.yml）：
#   docker build --build-arg MODULE=amz-gateway                            --build-arg PORT=10010 -t amz-gateway:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-user           --build-arg PORT=8080  -t amz-service-user:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-search         --build-arg PORT=8090  -t amz-service-search:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-ai             --build-arg PORT=8091  -t amz-service-ai:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-product        --build-arg PORT=8095  -t amz-service-product:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-spapi          --build-arg PORT=8096  -t amz-service-spapi:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-ad             --build-arg PORT=8097  -t amz-service-ad:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-procurement   --build-arg PORT=8098  -t amz-service-procurement:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-customer       --build-arg PORT=8099  -t amz-service-customer:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-logistics      --build-arg PORT=8100  -t amz-service-logistics:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-ops            --build-arg PORT=8101  -t amz-service-ops:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-report         --build-arg PORT=8102  -t amz-service-report:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-finance        --build-arg PORT=8103  -t amz-service-finance:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-multiplatform  --build-arg PORT=8104  -t amz-service-multiplatform:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-order          --build-arg PORT=8105  -t amz-service-order:latest .
#   docker build --build-arg MODULE=amz-service/amz-service-message        --build-arg PORT=8889  -t amz-service-message:latest .

# ---------- Stage 1: Maven 编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

# 目标构建模块（相对路径，例如 amz-service/amz-service-spapi 或 amz-gateway）
ARG MODULE=amz-service/amz-service-spapi

WORKDIR /build

# 先拷贝 pom 文件利用层缓存加速依赖下载
COPY pom.xml ./
COPY amz-common/pom.xml ./amz-common/
COPY amz-gateway/pom.xml ./amz-gateway/
COPY amz-service/pom.xml ./amz-service/
COPY amz-service/amz-service-user/pom.xml ./amz-service/amz-service-user/
COPY amz-service/amz-service-search/pom.xml ./amz-service/amz-service-search/
COPY amz-service/amz-service-product/pom.xml ./amz-service/amz-service-product/
COPY amz-service/amz-service-order/pom.xml ./amz-service/amz-service-order/
COPY amz-service/amz-service-message/pom.xml ./amz-service/amz-service-message/
COPY amz-service/amz-service-ai/pom.xml ./amz-service/amz-service-ai/
COPY amz-service/amz-service-spapi/pom.xml ./amz-service/amz-service-spapi/
COPY amz-service/amz-service-ad/pom.xml ./amz-service/amz-service-ad/
COPY amz-service/amz-service-procurement/pom.xml ./amz-service/amz-service-procurement/
COPY amz-service/amz-service-customer/pom.xml ./amz-service/amz-service-customer/
COPY amz-service/amz-service-logistics/pom.xml ./amz-service/amz-service-logistics/
COPY amz-service/amz-service-ops/pom.xml ./amz-service/amz-service-ops/
COPY amz-service/amz-service-report/pom.xml ./amz-service/amz-service-report/
COPY amz-service/amz-service-finance/pom.xml ./amz-service/amz-service-finance/
COPY amz-service/amz-service-multiplatform/pom.xml ./amz-service/amz-service-multiplatform/

# 下载依赖（失败不阻断，下次构建会复用 .m2 缓存）
# 依赖缓存挂载：镜像构建每次都要把整个依赖树重新下一遍，实测本机（Windows + Docker Desktop）
# 在密集传输时会把连接切断 —— order 镜像连续两次失败在不同构件上
# （byte-buddy-agent:1.17.8 → httpcore5:5.4.3，均为 Could not transfer artifact ... Remotely closed），
# 第三次重试才过。挂 /root/.m2 让同一台构建机上后续构建复用已下载的构件，
# 把"网络抖一下整个镜像就白跑十几分钟"变成只在首次冷构建时承担风险。
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -Dmaven.test.skip=true || true

# 拷贝源码
COPY amz-common/src ./amz-common/src
COPY amz-gateway/src ./amz-gateway/src
COPY amz-service ./amz-service

# 编译打包目标模块及其依赖（跳过测试，CI 已在 test 阶段执行）
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests -pl ${MODULE} -am

# ---------- Stage 1.5: Skywalking Java Agent 下载 ----------
# 注：dlcdn 镜像不含旧版 Java Agent，改用 Apache Archive 官方存档
FROM busybox:1.36 AS skywalking-downloader
ADD https://archive.apache.org/dist/skywalking/java-agent/9.7.0/apache-skywalking-java-agent-9.7.0.tgz /tmp/skywalking-agent.tgz
RUN tar -xzf /tmp/skywalking-agent.tgz -C / && rm /tmp/skywalking-agent.tgz

# ---------- Stage 2: JRE 运行 ----------
# openjdk 官方镜像已下架，改用 Eclipse Temurin（Adoptium 官方维护）
FROM eclipse-temurin:17-jre

ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="AmazonERP" \
      org.opencontainers.image.description="Amazon ERP 微服务跨境电商管理平台" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/cgs123456/AmazonERP"

# 安装 curl 供 HEALTHCHECK 使用（--no-install-recommends 避免冗余包，随后清理 apt 缓存减小镜像体积）
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 拷贝 Skywalking Java Agent
COPY --from=skywalking-downloader /skywalking-agent /skywalking-agent

# 默认 reporter 是 gRPC（见 config/agent.config 的 collector.backend_service），
# optional-reporter-plugins 下的 Kafka reporter 及其捆绑依赖默认不加载，
# 其中 lz4-java 1.6.0 带 High CVE 且 1.x 无修复版本（Grype 会扫到文件系统里的它）。
# 移除未启用的可选 reporter 插件以缩小镜像攻击面；若将来要启用 Kafka reporter，
# 必须同时引入修复版依赖，不能直接把它拷回 plugins/。
RUN rm -rf /skywalking-agent/optional-reporter-plugins

# 官方 9.7.0 agent 包内混有 AppleDouble 元数据文件（._*.jar，实测 211 个、各 163 字节，
# 连 ._skywalking-agent.jar 都在）。agent 的插件加载器会按 *.jar 一并解析，
# 干净构建出来的镜像每次启动实测产生 163 条
# "AgentClassLoader : ._xxx-plugin-9.7.0.jar jar file can't be resolved" ERROR：
# 那是纯噪声，但它淹在启动日志里，且每次重启都重来一遍。删掉元数据文件本身无副作用。
RUN find /skywalking-agent -name '._*' -delete

# 创建非 root 运行用户，避免容器内以 root 身份运行 JVM（安全加固）
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

# 在 final stage 重新声明 MODULE 和 PORT（ARG 跨 stage 不保留，需在每个 stage 重新声明）
ARG MODULE=amz-service/amz-service-spapi
ARG PORT=8096

# 拷贝构建产物（直接以非 root 属主拷贝，避免额外 chown 层）
# MODULE 形如 amz-service/amz-service-spapi 或 amz-gateway，jar 位于 /build/${MODULE}/target/*.jar
COPY --from=builder --chown=appuser:appuser /build/${MODULE}/target/*.jar /app/app.jar

# 创建日志目录并赋权（logback oper-log appender 写入 logs/oper-log.log，
# agent 日志写 logs/sw-agent —— 默认目标 /skywalking-agent/logs 属主是 root，非 root 用户写不进去）
RUN mkdir -p /app/logs /app/logs/sw-agent && chown -R appuser:appuser /app/logs

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 将 PORT 固化为环境变量，供 HEALTHCHECK 在 shell 形式下展开
ENV SERVER_PORT=${PORT}

# JVM 调参与 agent 挂载分成两个变量：JAVA_OPTS 可被部署侧整体覆盖，SW_AGENT_OPTS 只负责挂载。
# 历史缺陷：-javaagent 原本只写在 JAVA_OPTS 默认值里，而 docker-compose 传 JAVA_OPTS=${JAVA_OPTS:-}、
# k8s ConfigMap 也自带 JAVA_OPTS，两条部署路径的整体覆盖都会把 agent 一起摘掉。
# agent 真实读取的变量名（见镜像内 config/agent.config）；旧 SW_COLLECTOR 从未被引用，是死变量。
# 镜像不再给 SW_AGENT_NAME 默认值：服务名必须由部署侧逐服务显式声明，否则 16 个服务会塌缩成同一个名字。
ENV SW_AGENT_COLLECTOR_BACKEND_SERVICES=skywalking-oap:11800
ENV SW_AGENT_NAMESPACE=amz-erp
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SW_AGENT_OPTS="-javaagent:/skywalking-agent/skywalking-agent.jar"
ENV SW_LOGGING_DIR=/app/logs/sw-agent

# 服务端口（与目标模块 application.yml 一致）
EXPOSE ${PORT}

# 切换为非 root 用户运行后续指令与 ENTRYPOINT
USER appuser

# 健康检查：JVM 启动后 60s 起每 30s 探测一次，curl 收到任意 HTTP 响应即视为存活
# （连不上才判定不健康），连续 3 次失败标记 unhealthy。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -s -o /dev/null http://localhost:${SERVER_PORT}/ || exit 1

# exec 让 JVM 成为 PID 1，SIGTERM 才能直达进程（否则滚动更新要等到 grace period 被 SIGKILL）。
# 拼命令前先从 JAVA_OPTS 里去掉 -javaagent，避免运维沿用旧 .env 时同一个 agent 被挂载两次。
ENTRYPOINT ["sh", "-c", "JAVA_OPTS=$(printf '%s' \"$JAVA_OPTS\" | sed -e 's#-javaagent:/skywalking-agent/skywalking-agent.jar##g'); exec java ${SW_AGENT_OPTS} ${JAVA_OPTS} -jar /app/app.jar"]
