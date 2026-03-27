FROM eclipse-temurin:25-jre AS build

ENV APP_DIR /app

WORKDIR $APP_DIR

COPY target/nanopub-query-*-fat.jar $APP_DIR/nanopub-query.jar

EXPOSE 9393

ENTRYPOINT ["java","-jar","nanopub-query.jar"]
