# Multi-stage build for Minecraft World Downloader
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy project files
COPY pom.xml .
COPY src ./src

# Build the project with Maven
RUN mvn clean package -DskipTests -q

# Final runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy built JAR from builder
COPY --from=builder /build/target/world-downloader.jar .

# Set entrypoint
ENTRYPOINT ["java", "-jar", "world-downloader.jar"]
CMD []
