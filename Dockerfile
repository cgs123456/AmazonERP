# Dockerfile - Amazon ERP 多阶段构建
# 用法：
#   docker build -t amazon-erp:latest .
#   docker run -p 8086:8086 amazon-erp:latest --module=amz-service-user
#
# 默认启动 amz-service-spapi（可覆盖 MODULE 环境变量切换其他微服务）

# ---------- Stage 1: Maven 编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

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

# 编译打包（跳过测试，CI 已在 test 阶段执行）
RUN mvn -B -q clean package -DskipTests -pl amz-service/amz-service-spapi -am

# ---------- Stage 2: JRE 运行 ----------
# openjdk 官方镜像已下架，改用 Eclipse Temurin（Adoptium 官方维护）
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="AmazonERP"
LABEL org.opencontainers.image.description="Amazon ERP 微服务跨境电商管理平台"
LABEL org.opencontainers.image.source="https://github.com/cgs123456/AmazonERP"

WORKDIR /app

# 拷贝构建产物
COPY --from=builder /build/amz-service/amz-service-spapi/target/*.jar /app/app.jar

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# JVM 参数（容器环境优化）
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8096

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
