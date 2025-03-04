FROM maven:3.9.9-eclipse-temurin-21

ENV APP_DIR /app
ENV TMP_DIR /tmp

WORKDIR $TMP_DIR

COPY pom.xml pom.xml

RUN mvn install

COPY src src

RUN mvn install -o && \
    mkdir $APP_DIR && \
    mv target/nanopub-query-*-fat.jar $APP_DIR/nanopub-query.jar && \
    rm -rf $TMP_DIR

WORKDIR $APP_DIR

EXPOSE 9300
EXPOSE 9393

ENTRYPOINT ["java","-jar","nanopub-query.jar"]
