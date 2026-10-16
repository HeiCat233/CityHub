# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
# 先只拷 pom，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
ENV TZ=Asia/Shanghai

# 2核2G 的 ECS 上必须限制 JVM 堆，否则容器容易被 OOM Killer 干掉。
# 这里给出保守默认值，docker-compose.yml 里会用 JAVA_OPTS 覆盖。
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=160m -Xss512k -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

COPY --from=build /build/target/*.jar app.jar
EXPOSE 8081

# 用 sh -c 是为了让 $JAVA_OPTS 被 shell 展开（exec 形式不会展开环境变量）。
# exec 保证 java 进程成为 PID 1，能正确接收 docker stop 的 SIGTERM 信号。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
