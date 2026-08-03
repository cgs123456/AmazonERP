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
COPY amz-service/*/pom.xml ./amz-service/

# 下载依赖（失败不阻断，下次构建会复用 .m2 缓存）
RUN mvn -B -q dependency:go-offline -Dmaven.test.skip=true || true

# 拷贝源码
COPY amz-common/src ./amz-common/src
COPY amz-gateway/src ./amz-gateway/src
COPY amz-service ./amz-service

# 编译打包目标模块及其依赖（跳过测试，CI 已在 test 阶段执行）
RUN mvn -B -q clean package -DskipTests -pl ${MODULE} -am

# ---------- Stage 1.5: Skywalking Java Agent 下载 ----------
FROM busybox:1.36 AS skywalking-downloader
WORKDIR /skywalking
ADD https://dlcdn.apache.org/skywalking/java-agent/9.3.0/apache-skywalking-java-agent-9.3.0.tgz /tmp/skywalking-agent.tgz
RUN tar -xzf /tmp/skywalking-agent.tgz && mv /skywalking-agent skywalking-agent && rm /tmp/skywalking-agent.tgz

# ---------- Stage 2: JRE 运行 ----------
# openjdk 官方镜像已下架，改用 Eclipse Temurin（Adoptium 官方维护）
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="AmazonERP"
LABEL org.opencontainers.image.description="Amazon ERP 微服务跨境电商管理平台"
LABEL org.opencontainers.image.source="https://github.com/cgs123456/AmazonERP"

# 安装 curl 供 HEALTHCHECK 使用（--no-install-recommends 避免冗余包，随后清理 apt 缓存减小镜像体积）
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 拷贝 Skywalking Java Agent
COPY --from=skywalking-downloader /skywalking-agent /skywalking-agent

# 创建非 root 运行用户，避免容器内以 root 身份运行 JVM（安全加固）
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

# 在 final stage 重新声明 MODULE 和 PORT（ARG 跨 stage 不保留，需在每个 stage 重新声明）
ARG MODULE=amz-service/amz-service-spapi
ARG PORT=8096

# 拷贝构建产物（直接以非 root 属主拷贝，避免额外 chown 层）
# MODULE 形如 amz-service/amz-service-spapi 或 amz-gateway，jar 位于 /build/${MODULE}/target/*.jar
COPY --from=builder --chown=appuser:appuser /build/${MODULE}/target/*.jar /app/app.jar

# 创建日志目录并赋权（logback oper-log appender 写入 logs/oper-log.log）
RUN mkdir -p /app/logs && chown -R appuser:appuser /app/logs

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 将 PORT 固化为环境变量，供 HEALTHCHECK 在 shell 形式下展开
ENV SERVER_PORT=${PORT}

# JVM 参数（容器环境优化，含 Skywalking Java Agent）
# 通过环境变量 SW_AGENT_NAME 控制 Skywalking 上报的服务名（默认 amz-service）
# JAVA_OPTS 运行时可覆盖：docker run -e JAVA_OPTS="..."
ENV SW_AGENT_NAME=amz-service
ENV SW_COLLECTOR=skywalking-oap:11800
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -javaagent:/skywalking-agent/skywalking-agent.jar"

# 服务端口（与目标模块 application.yml 一致）
EXPOSE ${PORT}

# 切换为非 root 用户运行后续指令与 ENTRYPOINT
USER appuser

# 健康检查：JVM 启动后 60s 起每 30s 探测一次，curl 收到任意 HTTP 响应即视为存活
# （连不上才判定不健康），连续 3 次失败标记 unhealthy。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -s -o /dev/null http://localhost:${SERVER_PORT}/ || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
