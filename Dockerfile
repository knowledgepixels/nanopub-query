FROM maven:3-openjdk-17

ENV APP_DIR /app
ENV TMP_DIR /tmp

WORKDIR $TMP_DIR

COPY . .

RUN mvn clean install && \
    mkdir $APP_DIR && \
    mv target/nanopub-query-*-SNAPSHOT-fat.jar $APP_DIR/nanopub-query.jar && \
    rm -rf $TMP_DIR

WORKDIR $APP_DIR

EXPOSE 9300
EXPOSE 9393

ENTRYPOINT ["java","-jar","nanopub-query.jar"]
