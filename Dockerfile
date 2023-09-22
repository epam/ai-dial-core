FROM eclipse-temurin:17-jdk-alpine

ADD ./build/distributions/aidial-core*.tar /opt/epam/aidial/
RUN mv /opt/epam/aidial/aidial-core-*/* /opt/epam/aidial/
RUN rmdir /opt/epam/aidial/aidial-core-*
COPY ./config/* /opt/epam/aidial/config/

ENV AIDIAL_SETTINGS=/opt/epam/aidial/config/aidial.settings.json
ENV JAVA_OPTS="-Dgflog.config=/opt/epam/aidial/config/gflog.xml"

EXPOSE 80
EXPOSE 9464
ENTRYPOINT ["/opt/epam/aidial/bin/aidial-core"]