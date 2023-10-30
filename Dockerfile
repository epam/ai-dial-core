FROM gradle:8.2.0-jdk17-alpine as cache
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /cache
COPY build.gradle settings.gradle ./
RUN gradle --no-daemon build --stacktrace

FROM gradle:8.2.0-jdk17-alpine as builder
COPY --from=cache /cache /home/gradle/.gradle
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle --no-daemon build --stacktrace -PdisableCompression=true
RUN mkdir /build && tar -xf /home/gradle/src/build/distributions/aidial-core*.tar --strip-components=1 -C /build

FROM eclipse-temurin:17-jdk-alpine

ENV AIDIAL_SETTINGS=/app/config/aidial.settings.json
ENV JAVA_OPTS="-Dgflog.config=/app/config/gflog.xml"
WORKDIR /app

RUN adduser -u 1001 --disabled-password --gecos "" appuser

COPY --from=builder --chown=appuser:appuser /build/ .
COPY --chown=appuser:appuser ./config/* /app/config/
RUN mkdir /app/log && chown -R appuser:appuser /app

USER appuser

HEALTHCHECK --start-period=30s --interval=1m --timeout=3s \
  CMD wget --no-verbose --spider --tries=1 http://localhost:8080/health || exit 1

EXPOSE 8080 9464
ENTRYPOINT ["/app/bin/aidial-core"]