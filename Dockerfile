# syntax=docker/dockerfile:1.7

ARG JAVA_VERSION=21
ARG APP_VERSION=0.0.1-SNAPSHOT

# Build stage: use a full JDK because Maven needs javac to compile the app.
FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true package

# Extract stage: split the Spring Boot executable jar into stable Docker layers.
FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy AS extract
WORKDIR /workspace

COPY --from=build /workspace/target/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Runtime stage: use only a JRE and the extracted layers, then run as non-root.
FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy AS runtime
WORKDIR /app

ARG APP_VERSION

LABEL org.opencontainers.image.title="QuanLyThuVien" \
      org.opencontainers.image.description="Spring Boot library management service" \
      org.opencontainers.image.version="${APP_VERSION}"

RUN groupadd --system --gid 1001 spring \
    && useradd --system --uid 1001 --gid spring --home-dir /app --shell /usr/sbin/nologin spring

COPY --from=extract --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /workspace/extracted/application/ ./

USER spring:spring

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1

# Spring Boot 4 tools extraction creates application.jar plus lib/.
ENTRYPOINT ["java", "-jar", "application.jar"]
