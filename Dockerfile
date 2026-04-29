# Stage 1: build fat JAR
FROM gradle:8.10-jdk21 AS builder
WORKDIR /build
COPY . .
RUN gradle shadowJar --no-daemon -q

# Stage 2: minimal JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/build/libs/*-all.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
