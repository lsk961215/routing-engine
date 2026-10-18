# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

COPY . .

RUN chmod +x gradlew \
    && ./gradlew --no-daemon :routing-api:bootJar

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app

COPY --from=builder --chown=app:app \
    /build/routing-api/build/libs/routing-api-0.0.1-SNAPSHOT.jar \
    /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
