package game.data.container;

import config.Config;
import game.data.coordinates.Coordinate3D;
import game.data.dimension.Dimension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

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
        } catch (Exception e) {
            // best-effort: never let logging break the download
        }
    }

    /**
     * Append the contents of one auto-opened container. Items are aggregated by name with their total
     * counts. Empty containers are logged too (so the record shows the container was visited/empty).
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
        if (totals.isEmpty()) {
            sb.append("    (empty)").append(nl);
        } else {
            for (Map.Entry<String, Integer> e : totals.entrySet()) {
                sb.append("    ").append(e.getKey()).append(" x").append(e.getValue()).append(nl);
            }
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
