FROM gradle:8.2.0-jdk17-alpine as cache

WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /cache
COPY build.gradle settings.gradle ./
# just pull dependencies for cache
RUN gradle --no-daemon build --stacktrace

FROM gradle:8.2.0-jdk17-alpine as builder

COPY --from=cache /cache /home/gradle/.gradle
COPY --chown=gradle:gradle . /home/gradle/src

WORKDIR /home/gradle/src
RUN gradle --no-daemon build --stacktrace -PdisableCompression=true -x test
RUN mkdir /build && tar -xf /home/gradle/src/build/distributions/aidial-core*.tar --strip-components=1 -C /build

FROM eclipse-temurin:17-jdk-alpine

# fix CVE-2023-5363
# TODO remove the fix once a new version is released
RUN apk update && apk upgrade --no-cache libcrypto3 libssl3
# fix CVE-2023-52425
RUN apk upgrade --no-cache libexpat
RUN apk add --no-cache su-exec

ENV OTEL_TRACES_EXPORTER="none"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

# Local storage dir configured in the default aidial.settings.json
ENV STORAGE_DIR /app/data
ENV LOG_DIR /app/log

WORKDIR /app

RUN adduser -u 1001 --disabled-password --gecos "" appuser

COPY --from=builder --chown=appuser:appuser /build/ .
RUN chown -R appuser:appuser /app

COPY --chown=appuser:appuser docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

HEALTHCHECK --start-period=30s --interval=1m --timeout=3s \
  CMD wget --no-verbose --spider --tries=1 http://localhost:8080/health || exit 1

EXPOSE 8080 9464

RUN mkdir -p "$LOG_DIR" && \
    chown -R appuser:appuser "$LOG_DIR" && \
    mkdir -p "$STORAGE_DIR" && \
    chown -R appuser:appuser "$STORAGE_DIR"

ENTRYPOINT ["docker-entrypoint.sh"]