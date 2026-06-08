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

# ---- Stage 2: runtime (JRE for the downloader + Python for the web console) ----
FROM eclipse-temurin:21-jre

ENV DEBIAN_FRONTEND=noninteractive
# python3 = web console; nodejs = the mineflayer auto-explore bot (run from the console)
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pip curl ca-certificates gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/world-downloader.jar /app/world-downloader.jar
COPY web /app/web
RUN pip3 install --no-cache-dir --break-system-packages -r /app/web/requirements.txt

# auto-explore bot (mineflayer); deps installed at build so the console can launch it
COPY scraper /app/scraper
RUN cd /app/scraper && npm install --no-audit --no-fund --omit=dev

ENV JAR_PATH=/app/world-downloader.jar \
    DATA_DIR=/data \
    WEB_PORT=8080

RUN mkdir -p /data
VOLUME ["/data"]

# 8080 = web console, 25565 = the Minecraft proxy clients connect to
EXPOSE 8080 25565

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/healthz').status==200 else 1)" || exit 1

ENTRYPOINT ["python3", "/app/web/app.py"]
