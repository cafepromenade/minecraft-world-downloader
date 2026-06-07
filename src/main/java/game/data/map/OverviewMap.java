package game.data.map;

import config.Config;
import game.data.WorldManager;
import game.data.chunk.Chunk;
import game.data.chunk.ChunkImageFactory;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDim2D;
import gui.images.ImageMode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Headless overview-map renderer.
 *
 * <p>Writes top-down PNG region tiles (512x512 px = 32x32 chunks) under
 * {@code <output>/overview/<dimension>/<mode>/r.<x>.<z>.png} plus a {@code meta.json} index, so the
 * web console can display a live, pannable map without the JavaFX GUI. It reuses the exact per-block
 * colours the GUI map uses (via {@link ChunkImageFactory#computeArgb}) but renders with
 * {@code java.awt}/{@link ImageIO} only — the downloader runs with {@code -Djava.awt.headless=true},
 * so this works in {@code --no-gui} / Docker runs.
 *
 * <p>Enabled automatically in headless mode (see {@link Config#renderOverviewMap()}).
 */
public class OverviewMap {
    public static final int REGION_PX = 512;
    private static final int CHUNK_PX = Chunk.SECTION_WIDTH;               // 16
    private static final int CHUNKS_PER_REGION = REGION_PX / CHUNK_PX;     // 32
    private static final long FLUSH_INTERVAL_MS = 3000;
    /** Cap on in-memory region buffers; each is REGION_PX*REGION_PX ints (~1 MB). Evicted LRU (flushed first). */
    private static final int MAX_BUFFERS = 96;

    private static final OverviewMap instance = new OverviewMap();
    public static OverviewMap getInstance() { return instance; }

    private static final ImageMode[] MODES = { ImageMode.NORMAL, ImageMode.CAVES };

    /** LRU of in-memory region buffers (accessOrder = true). */
    private final LinkedHashMap<String, int[]> buffers = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirty = new HashSet<>();
    /** Every region tile we have ever touched, for building meta.json without scanning the disk. */
    private final Set<String> knownTiles = new HashSet<>();
    private final Object lock = new Object();

    private ScheduledExecutorService scheduler;
    private volatile boolean started = false;

    private OverviewMap() { }

    public boolean isEnabled() {
        return Config.renderOverviewMap();
    }

    /** Lazily start the periodic flush thread and shutdown hook (idempotent). */
    public synchronized void start() {
        if (started || !isEnabled()) {
            return;
        }
        started = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "overview-map-flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flushSafe, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushSafe, "overview-map-shutdown"));
    }

    /**
     * Register a chunk so its overview pixels are blitted into the region tiles. Mirrors the GUI's
     * {@code GuiMap.setChunkLoaded}, but via the JavaFX-free pixel callback.
     */
    public void setChunkLoaded(CoordinateDim2D coord, Chunk chunk) {
        if (!isEnabled() || chunk == null) {
            return;
        }
        start();
        ChunkImageFactory factory = chunk.getChunkImageFactory();
        factory.onPixels((pixelMap, saved) -> blit(coord, pixelMap));
        factory.requestImage();
    }

    private void blit(CoordinateDim2D coord, Map<ImageMode, int[]> pixelMap) {
        try {
            String dim = safe(coord.getDimension().getName());
            int cx = coord.getX();
            int cz = coord.getZ();
            int rx = Math.floorDiv(cx, CHUNKS_PER_REGION);
            int rz = Math.floorDiv(cz, CHUNKS_PER_REGION);
            int ox = Math.floorMod(cx, CHUNKS_PER_REGION) * CHUNK_PX;
            int oz = Math.floorMod(cz, CHUNKS_PER_REGION) * CHUNK_PX;

            synchronized (lock) {
                for (ImageMode mode : MODES) {
                    int[] px = pixelMap.get(mode);
                    if (px == null || px.length != CHUNK_PX * CHUNK_PX) {
                        continue;
                    }
                    String key = key(dim, mode, rx, rz);
                    int[] buf = obtain(key);
                    for (int row = 0; row < CHUNK_PX; row++) {
                        System.arraycopy(px, row * CHUNK_PX, buf, (oz + row) * REGION_PX + ox, CHUNK_PX);
                    }
                    dirty.add(key);
                    knownTiles.add(key);
                }
            }
        } catch (Exception ex) {
            // never let map rendering interfere with the actual download
        }
    }

    /** Obtain a region buffer (from memory, or by loading an existing tile), evicting LRU if over cap. Caller holds {@link #lock}. */
    private int[] obtain(String key) {
        int[] buf = buffers.get(key);
        if (buf != null) {
            return buf;
        }
        buf = loadTile(key);
        if (buf == null) {
            buf = new int[REGION_PX * REGION_PX];
        }
        buffers.put(key, buf);
        while (buffers.size() > MAX_BUFFERS) {
            Map.Entry<String, int[]> oldest = buffers.entrySet().iterator().next();
            String k = oldest.getKey();
            int[] b = oldest.getValue();
            buffers.remove(k);
            if (dirty.remove(k)) {
                writeTile(k, b);
            }
        }
        return buf;
    }

    private void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            // swallow: a failed flush must never crash the downloader
        }
    }

    private void flush() {
        Map<String, int[]> snapshot = new HashMap<>();
        synchronized (lock) {
            for (String k : dirty) {
                int[] b = buffers.get(k);
                if (b != null) {
                    snapshot.put(k, b.clone());   // clone so disk IO happens outside the lock
                }
            }
            dirty.clear();
        }
        for (Map.Entry<String, int[]> e : snapshot.entrySet()) {
            writeTile(e.getKey(), e.getValue());
        }
        if (!snapshot.isEmpty() || started) {
            writeMeta();
        }
    }

    // ---- disk layout -------------------------------------------------------------------------

    private static Path overviewDir() {
        return Paths.get(Config.getWorldOutputDir(), "overview");
    }

    private static Path tilePath(String dim, ImageMode mode, int rx, int rz) {
        String modeDir = mode == ImageMode.NORMAL ? "normal" : "caves";
        return overviewDir().resolve(dim).resolve(modeDir).resolve("r." + rx + "." + rz + ".png");
    }

    private void writeTile(String key, int[] buf) {
        String[] parts = key.split("\\|");
        String dim = parts[0];
        ImageMode mode = ImageMode.valueOf(parts[1]);
        int rx = Integer.parseInt(parts[2]);
        int rz = Integer.parseInt(parts[3]);
        try {
            Path path = tilePath(dim, mode, rx, rz);
            Files.createDirectories(path.getParent());
            BufferedImage img = new BufferedImage(REGION_PX, REGION_PX, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, REGION_PX, REGION_PX, buf, 0, REGION_PX);
            ImageIO.write(img, "png", path.toFile());
        } catch (IOException ex) {
            // tile write failed; it will be retried on the next change to this region
        }
    }

    private int[] loadTile(String key) {
        String[] parts = key.split("\\|");
        String dim = parts[0];
        ImageMode mode = ImageMode.valueOf(parts[1]);
        int rx = Integer.parseInt(parts[2]);
        int rz = Integer.parseInt(parts[3]);
        Path path = tilePath(dim, mode, rx, rz);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(path.toFile());
            if (img == null || img.getWidth() != REGION_PX || img.getHeight() != REGION_PX) {
                return null;
            }
            int[] buf = new int[REGION_PX * REGION_PX];
            img.getRGB(0, 0, REGION_PX, REGION_PX, buf, 0, REGION_PX);
            knownTiles.add(key);
            return buf;
        } catch (IOException ex) {
            return null;
        }
    }

    /** Write a small JSON index the web client polls: which tiles exist, the player position, the current dimension. */
    private void writeMeta() {
        List<String> tiles;
        synchronized (lock) {
            tiles = new ArrayList<>(knownTiles);
        }

        // dim -> mode -> list of [rx, rz]
        Map<String, Map<String, List<int[]>>> grouped = new HashMap<>();
        for (String key : tiles) {
            String[] parts = key.split("\\|");
            String dim = parts[0];
            String mode = parts[1].equals("NORMAL") ? "normal" : "caves";
            int rx = Integer.parseInt(parts[2]);
            int rz = Integer.parseInt(parts[3]);
            grouped.computeIfAbsent(dim, d -> new HashMap<>())
                   .computeIfAbsent(mode, m -> new ArrayList<>())
                   .add(new int[]{ rx, rz });
        }

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"regionPx\":").append(REGION_PX).append(',');
        sb.append("\"chunkPx\":").append(CHUNK_PX).append(',');
        sb.append("\"updated\":").append(System.currentTimeMillis()).append(',');

        String currentDim = null;
        try {
            if (WorldManager.getInstance().getDimension() != null) {
                currentDim = safe(WorldManager.getInstance().getDimension().getName());
            }
        } catch (Exception ignored) { }
        sb.append("\"currentDimension\":").append(currentDim == null ? "null" : ('"' + currentDim + '"')).append(',');

        sb.append("\"player\":");
        try {
            Coordinate3D p = WorldManager.getInstance().getPlayerPosition();
            if (p != null) {
                sb.append("{\"x\":").append(p.getX()).append(",\"y\":").append(p.getY())
                  .append(",\"z\":").append(p.getZ()).append('}');
            } else {
                sb.append("null");
            }
        } catch (Exception ex) {
            sb.append("null");
        }
        sb.append(',');

        sb.append("\"tiles\":{");
        boolean firstDim = true;
        for (Map.Entry<String, Map<String, List<int[]>>> dimEntry : grouped.entrySet()) {
            if (!firstDim) { sb.append(','); }
            firstDim = false;
            sb.append('"').append(dimEntry.getKey()).append("\":{");
            boolean firstMode = true;
            for (Map.Entry<String, List<int[]>> modeEntry : dimEntry.getValue().entrySet()) {
                if (!firstMode) { sb.append(','); }
                firstMode = false;
                sb.append('"').append(modeEntry.getKey()).append("\":[");
                boolean firstTile = true;
                for (int[] rc : modeEntry.getValue()) {
                    if (!firstTile) { sb.append(','); }
                    firstTile = false;
                    sb.append('[').append(rc[0]).append(',').append(rc[1]).append(']');
                }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append("}}");

        try {
            Path metaPath = overviewDir().resolve("meta.json");
            Files.createDirectories(metaPath.getParent());
            Files.write(metaPath, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            // meta write failed; retried next flush
        }
    }

    private static String key(String dim, ImageMode mode, int rx, int rz) {
        return dim + '|' + mode.name() + '|' + rx + '|' + rz;
    }

    /** Make a dimension id safe for use as a folder name (e.g. "minecraft:overworld" -> "minecraft_overworld"). */
    private static String safe(String name) {
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
