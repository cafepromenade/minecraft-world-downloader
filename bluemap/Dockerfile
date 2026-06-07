# BlueMap renderer/server for a downloaded world.
# Renders $WORLD_DIR with bluemap/pipeline.py and serves the 3D map on $BLUEMAP_PORT.
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY bluemap/pipeline.py /app/bluemap/pipeline.py
COPY bluemap/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENV WORLD_DIR=/data/world \
    BLUEMAP_PORT=8100 \
    WORKDIR=/data/bluemap

EXPOSE 8100
ENTRYPOINT ["/app/docker-entrypoint.sh"]
