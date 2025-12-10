FROM eclipse-temurin:25 AS build

ENV APP_DIR /app

WORKDIR $APP_DIR

COPY . .

RUN ./mvnw package -Dmaven.test.skip=true && \
    mv target/nanopub-query-*-fat.jar $APP_DIR/nanopub-query.jar


FROM eclipse-temurin:25

WORKDIR /app

COPY --from=build /app/nanopub-query.jar .

EXPOSE 9393

ENTRYPOINT ["java","-jar","nanopub-query.jar"]
