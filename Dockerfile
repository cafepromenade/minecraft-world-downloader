# syntax=docker/dockerfile:1
# Minecraft World Downloader + web management console.

# ---- Stage 1: build the downloader jar ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 1) Copy ONLY the pom first and pre-download all dependencies + plugins into ~/.m2. Because this is a
#    separate layer that depends only on pom.xml, Docker caches it and re-downloads ONLY when the pom
#    changes — source-only edits reuse this layer (the deps are baked into the cached builder layer).
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# 2) Now copy the sources and build. The deps are already in ~/.m2 from the cached layer above, so this
#    step just recompiles + repackages without re-downloading.
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# (Faster alternative if you build with BuildKit: replace the two RUNs with a shared cache mount, e.g.
#   RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
#  — that reuses ~/.m2 across builds on the host, but keeps the deps OUT of the image layers.)

# ---- Stage 2: runtime (uses pre-built base image with JRE + Python + Node deps) ----
ARG BASE_IMAGE=ghcr.io/cafepromenade/minecraft-world-downloader-web-base:latest
FROM ${BASE_IMAGE}

WORKDIR /app
COPY --from=builder /build/target/world-downloader.jar /app/world-downloader.jar
COPY web /app/web
COPY scraper /app/scraper
