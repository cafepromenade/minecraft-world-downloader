#!/bin/sh
# Render the world once, then serve the 3D map. Re-render periodically so the map stays current.
set -e
WORLD_DIR="${WORLD_DIR:-/data/world}"
WORKDIR="${WORKDIR:-/data/bluemap}"
PORT="${BLUEMAP_PORT:-8100}"
WEBROOT="$WORKDIR/web"

mkdir -p "$WORKDIR"
cat > "$WORKDIR/settings.json" <<JSON
{ "acceptDownload": true, "renderThreadCount": 0, "webserverEnabled": true, "webserverPort": $PORT }
JSON

if [ ! -d "$WORLD_DIR" ]; then
  echo "Waiting for world at $WORLD_DIR ..."
  while [ ! -d "$WORLD_DIR" ]; do sleep 5; done
fi

echo "Rendering $WORLD_DIR with BlueMap ..."
python3 /app/bluemap/pipeline.py render --world "$WORLD_DIR" --out "$WEBROOT" \
  --workdir "$WORKDIR" --settings "$WORKDIR/settings.json" --allow-nonzero || true

echo "Serving BlueMap on :$PORT (re-rendering every 15 min) ..."
# background re-render loop so the map follows new downloads
( while true; do
    sleep 900
    python3 /app/bluemap/pipeline.py render --world "$WORLD_DIR" --out "$WEBROOT" \
      --workdir "$WORKDIR" --settings "$WORKDIR/settings.json" --allow-nonzero || true
  done ) &

exec python3 /app/bluemap/pipeline.py serve --config "$WORKDIR/bluemap-config" --workdir "$WORKDIR"
