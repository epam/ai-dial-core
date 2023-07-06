FROM eclipse-temurin:17-jdk-alpine

ADD ./build/distributions/proxy*.tar /opt/deltix/dial/
RUN mv /opt/deltix/dial/proxy-* /opt/deltix/dial/proxy
COPY ./config/* /opt/deltix/dial/proxy/config/

ENV PROXY_SETTINGS=/opt/deltix/dial/proxy/config/proxy.settings.json
ENV JAVA_OPTS="-Dgflog.config=/opt/deltix/dial/proxy/config/gflog.xml"

EXPOSE 80
ENTRYPOINT ["/opt/deltix/dial/proxy/bin/proxy"]