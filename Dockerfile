# ============================================
# hr-security 多阶段构建（第 4 周）
# 阶段一：maven 镜像内编译打包（settings 内置阿里云镜像加速）
# 阶段二：仅 JDK 运行镜像，镜像体积小、与构建工具解耦
# 构建：docker compose build（或 docker build -t hr-security-app .）
# ============================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 用户级 settings.xml 覆盖中央仓库为阿里云镜像（国内拉依赖加速）
COPY docker-build/settings.xml /root/.m2/settings.xml
# 先只 COPY pom.xml 预拉依赖，利用 Docker 层缓存：以后改代码不用重新下载依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# 测试在外部跑（e2e 脚本），镜像构建阶段跳过单测
RUN mvn -B -q package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/hr-security-0.1.0.jar /app/app.jar
EXPOSE 8080
# 时区与宿主机保持一致，日志时间可读
ENV TZ=Asia/Shanghai
# 连接串/密钥全部来自环境变量（docker-compose 注入），容器内不硬编码任何配置
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
