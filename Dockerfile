# ─────────────────────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────────────────────
FROM gradle:jdk17 AS builder
WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x ./gradlew

# 의존성만 먼저 받기 — Docker layer caching 최적화
RUN ./gradlew dependencies -x test --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime — Temurin JRE + OTel Agent + 비루트 사용자
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

# OTel Java Agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
    /opt/otel-javaagent.jar
RUN chmod 644 /opt/otel-javaagent.jar

# RDS CA 번들 → JVM truststore (sslMode=VERIFY_IDENTITY 가 신뢰하도록)
ADD https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem \
    /tmp/rds-bundle.pem
RUN cd /tmp && \
    csplit -z -s -f rds-cert- rds-bundle.pem '/-----BEGIN CERTIFICATE-----/' '{*}' && \
    n=0; \
    for cert in rds-cert-*; do \
        n=$((n+1)); \
        keytool -importcert -trustcacerts -noprompt \
            -alias "rds-ca-${n}" \
            -file "$cert" \
            -keystore "$JAVA_HOME/lib/security/cacerts" \
            -storepass changeit; \
    done && \
    rm -f rds-bundle.pem rds-cert-*

# 비루트 사용자 (1000:1000) 로 실행 — security best practice
# Ubuntu 24.04 base 에는 default ubuntu 사용자(UID 1000)가 이미 존재 → 제거 후 재생성
RUN userdel -r ubuntu 2>/dev/null; \
    groupadd -g 1000 drawe && \
    useradd -u 1000 -g drawe -m drawe

WORKDIR /app
COPY --from=builder --chown=drawe:drawe /app/build/libs/*-SNAPSHOT.jar app.jar

USER drawe:drawe
EXPOSE 8080

# JAVA_TOOL_OPTIONS 으로 OTel Agent attach (전체 trace 자동 instrumentation)
# JVM heap 은 컨테이너 메모리의 75% 까지 사용 (XX:MaxRAMPercentage)
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel-javaagent.jar -XX:MaxRAMPercentage=75 -XX:+UseG1GC"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]