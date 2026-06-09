package game.data.container;

import config.Config;
import game.data.coordinates.Coordinate3D;
import game.data.dimension.Dimension;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Appends a human-readable list of the items captured by the (opt-in) auto-open feature
 * ({@link ContainerAutoOpener}) to a log file, so there is a record of what every auto-opened
 * container held without having to inspect the saved world.
 *
 * <p>One block is appended per container, e.g.:
 * <pre>
 * [2026-06-07 01:30:42] minecraft:overworld chest @ 123 64 -45 (3 stacks, 47 items)
 *     minecraft:diamond x12
 *     minecraft:iron_ingot x34
 *     minecraft:enchanted_book x1
 * </pre>
 *
 * <p>Location follows {@link ContainerAutoOpener}: the file lives BESIDE the world folder (its parent
 * dir) by default so it survives outside Docker's ephemeral layer and outside the world data itself;
 * override with --auto-open-log. Writing is best-effort and never affects the download/sweep.
 */
public class AutoOpenItemLog {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Filename-safe timestamp for archived (gzipped) logs. */
    private static final DateTimeFormatter ARCHIVE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Path file;
    private boolean resolved = false;

    private synchronized void ensureResolved() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            // Same "beside the world folder" convention as the --auto-open-state sidecar file.
            file = Config.resolveBesideWorldFile(Config.autoOpenLogFile(), "auto-open-items.log");
            // Archive a previous session's log (gzipped, in a subfolder) so each run starts fresh.
            rotatePreviousLog();
        } catch (Exception e) {
            // best-effort: never let logging break the download
        }
    }

    /**
     * If a log from a previous session exists, move it into an {@code auto-open-logs} subfolder beside
     * the log — gzipped and timestamped — so old session logs are kept (compressed) out of the way and
     * the new session starts with a fresh file. Runs once per app session (the log instance is not reset
     * on reconnect), so it does not fire on every server ping/rejoin. Best-effort: on any failure it
     * simply keeps appending to the existing file.
     */
    private void rotatePreviousLog() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            if (Files.size(file) == 0) {
                Files.deleteIfExists(file);   // empty leftover: nothing worth archiving
                return;
            }
            Path dir = file.getParent();
            Path archiveDir = (dir != null ? dir : file).resolve("auto-open-logs");
            Files.createDirectories(archiveDir);

            String base = file.getFileName().toString();
            String stem = base.toLowerCase().endsWith(".log") ? base.substring(0, base.length() - 4) : base;
            String ts;
            try {
                ts = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault())
                        .format(ARCHIVE_TS);
            } catch (Exception e) {
                ts = LocalDateTime.now().format(ARCHIVE_TS);
            }

            // <stem>-<timestamp>.log.gz, disambiguated if two sessions share the same second.
            Path archive = archiveDir.resolve(stem + "-" + ts + ".log.gz");
            for (int i = 1; Files.exists(archive); i++) {
                archive = archiveDir.resolve(stem + "-" + ts + "-" + i + ".log.gz");
            }

            try (OutputStream out = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW);
                 GZIPOutputStream gz = new GZIPOutputStream(out)) {
                Files.copy(file, gz);
            }
            Files.delete(file);   // start the new session with a fresh log
        } catch (Exception e) {
            // best-effort: if rotation fails, just keep appending to the existing file
        }
    }

    /**
     * Append the contents of one auto-opened container. Items are aggregated by name with their total
     * counts. Empty containers (0 items) are NOT logged — only containers that actually held something.
     */
    public synchronized void log(InventoryWindow window, String type, Dimension dimension) {
        ensureResolved();
        if (file == null || window == null || window.getSlotList() == null) {
            return;
        }

        // Aggregate by display key, preserving first-seen order, summing item counts. Also track the
        // real occupied-slot count ("stacks") and total item count for the header.
        Map<String, Integer> totals = new LinkedHashMap<>();
        int stacks = 0;
        int totalItems = 0;
        for (Slot slot : window.getSlotList()) {
            if (slot == null) {
                continue;
            }
            stacks++;
            totalItems += slot.getCount();
            totals.merge(itemKey(slot), slot.getCount(), Integer::sum);
        }

        // Don't write anything for an empty container — skip logging when there are 0 items.
        if (stacks == 0 || totalItems == 0) {
            return;
        }

        Coordinate3D pos = window.getContainerLocation();
        String dim = dimension != null ? dimension.getName() : "unknown";
        String nl = System.lineSeparator();

        StringBuilder sb = new StringBuilder();
        sb.append('[').append(LocalDateTime.now().format(TIMESTAMP)).append("] ")
                .append(dim).append(' ').append(type).append(" @ ")
                .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                .append(" (").append(stacks).append(stacks == 1 ? " stack, " : " stacks, ")
                .append(totalItems).append(totalItems == 1 ? " item)" : " items)")
                .append(nl);
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            sb.append("    ").append(e.getKey()).append(" x").append(e.getValue()).append(nl);
        }

        try {
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // best-effort
        }
    }

    /**
     * Display/aggregation key for a slot: the registry name, falling back to the raw id when unknown,
     * and distinguishing 1.12 metadata variants (e.g. wool colours) that share one item id.
     */
    private static String itemKey(Slot slot) {
        String name = slot.getItemName();
        if (name == null) {
            name = "unknown(id=" + slot.getItemId() + ")";
        }
        if (slot instanceof Slot_1_12 s && s.getDamage() != 0) {
            name = name + "/" + s.getDamage();
        }
        return name;
    }
}
