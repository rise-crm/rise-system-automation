# Build stage
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

# Copy gradle files for dependency caching
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew

# Download dependencies (this layer will be cached unless build.gradle changes)
RUN ./gradlew dependencies --no-daemon

# Copy source and build
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Safe copy: explicitly avoids the -plain.jar if it exists
COPY --from=builder /app/build/libs/*[^plain].jar app.jar

USER appuser
EXPOSE 8081

# Recommended flags for running Spring Boot in a container
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]