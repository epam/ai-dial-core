FROM gradle:8.10-jdk21-alpine AS builder

#COPY --from=cache /cache /home/gradle/.gradle
COPY --chown=gradle:gradle . /home/gradle/src

WORKDIR /home/gradle/src
RUN --mount=type=secret,id=GPR_USERNAME,env=GPR_USERNAME --mount=type=secret,id=GPR_PASSWORD,env=GPR_PASSWORD gradle --no-daemon build --stacktrace -PdisableCompression=true -x test
RUN mkdir /build && tar -xf /home/gradle/src/server/build/distributions/server*.tar --strip-components=1 -C /build

FROM eclipse-temurin:21-jdk-alpine

ENV OTEL_TRACES_EXPORTER="none"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

# Local storage dir configured in the default aidial.settings.json
ENV STORAGE_DIR=/app/data
ENV LOG_DIR=/app/log

WORKDIR /app

RUN adduser -u 1001 --disabled-password --gecos "" appuser

COPY --from=builder --chown=appuser:appuser /build/ .
RUN chown -R appuser:appuser /app

COPY --chown=appuser:appuser docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# upgrade/install packages
RUN apk update && apk upgrade --no-cache libcrypto3 libssl3 libexpat binutils gpgv gnutls libpng zlib musl musl-utils
RUN apk add --no-cache su-exec

HEALTHCHECK --start-period=30s --interval=1m --timeout=3s \
  CMD wget --no-verbose --spider --tries=1 http://localhost:8080/health || exit 1

EXPOSE 8080 9464

RUN mkdir -p "$LOG_DIR" && \
    chown -R appuser:appuser "$LOG_DIR" && \
    mkdir -p "$STORAGE_DIR" && \
    chown -R appuser:appuser "$STORAGE_DIR"

ENTRYPOINT ["docker-entrypoint.sh"]
