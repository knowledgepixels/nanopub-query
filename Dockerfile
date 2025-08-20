FROM eclipse-temurin:21 AS build

ENV APP_DIR /app

WORKDIR $APP_DIR

COPY . .

RUN ./mvnw package -Dmaven.test.skip=true && \
    mv target/nanopub-query-*-fat.jar $APP_DIR/nanopub-query.jar


FROM eclipse-temurin:21

WORKDIR /app

COPY --from=build /app/nanopub-query.jar .

EXPOSE 9300
EXPOSE 9393

ENTRYPOINT ["java","-jar","nanopub-query.jar"]
