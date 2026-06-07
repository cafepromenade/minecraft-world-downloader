package game.data.container;

import config.Config;
import game.data.WorldManager;
import game.data.coordinates.Coordinate3D;
import game.protocol.Protocol;
import packets.builder.PacketBuilder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EXPERIMENTAL, opt-in (--auto-open-containers). Automatically opens nearby containers, one at a
 * time and rate-limited, so a passive world-download captures their contents without the player
 * manually clicking each chest.
 *
 * <p>How it works: as the player moves, {@link #tick(Coordinate3D)} (called on the client->server
 * thread) finds the closest not-yet-captured container within reach and injects a serverbound
 * "UseItemOn" packet to open it. The server replies with OpenScreen + ContainerSetContent, which the
 * existing capture path records ({@link ContainerManager#items}). When that content arrives,
 * {@link #onContentCaptured(int)} (on the clientbound thread) injects a serverbound "ContainerClose"
 * so the server frees the container, and a cooldown elapses before the next one.
 *
 * <p>Limitations / cautions:
 * <ul>
 *   <li>The server only sends contents for containers within normal reach, so the player must still
 *       travel near every container.</li>
 *   <li>Rapid mass-opening can trip server anti-cheat (kick/ban). The --auto-open-delay gives a
 *       conservative gap; raise it if needed.</li>
 *   <li>Assumes default centering (--center 0): container positions are compared in real global
 *       coordinates against the player position.</li>
 *   <li>This sends real interactions to the server. Use only where you are permitted to.</li>
 * </ul>
 */
public class ContainerAutoOpener {
    /** How long to wait for a container's content after sending the open, before giving up on it. */
    private static final long OPEN_TIMEOUT_MS = 1500;
    /** A pending (us-opened) flag older than this is considered stale and must NOT swallow a screen —
     *  otherwise an auto-open the server never answered would eat the player's next real open. */
    private static final long PENDING_TTL_MS = 2000;

    private static final Set<String> OPENABLE = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:brewing_stand", "minecraft:crafter", "minecraft:lectern"
    );

    /** Packed (class-independent) keys of positions we have already attempted (captured, blocked, or
     *  unopenable) — never retried. Persisted per-world so a block is opened at most once EVER, even
     *  across restarts (this runs with restart:always, which would otherwise reset the set and
     *  re-open every container again). */
    private final Set<Long> attempted = ConcurrentHashMap.newKeySet();
    private volatile boolean attemptedLoaded = false;
    private java.nio.file.Path persistFile;
    private volatile boolean waiting = false;
    /** Set when we inject an open; the matching clientbound OpenScreen is swallowed so the client GUI
     *  never opens (a player cannot move while a container GUI is open, which would stall the sweep). */
    private volatile boolean pendingOpen = false;
    private volatile long pendingOpenMs = 0;
    private volatile long waitStartMs = 0;
    private volatile long lastOpenMs = 0;
    /** Latest block-interaction sequence seen from the real client; reused so we never get ahead of it. */
    private volatile int lastSequence = 0;

    public static boolean isOpenable(String blockName) {
        return blockName != null && (OPENABLE.contains(blockName) || blockName.endsWith("_shulker_box"));
    }

    /** Class-independent key for a block position: pack x(26)|y(12)|z(26). Unique per (x,y,z). */
    private static long keyOf(Coordinate3D c) {
        return ((long) (c.getX() & 0x3FFFFFF) << 38)
             | ((long) (c.getY() & 0xFFF) << 26)
             | ((long) (c.getZ() & 0x3FFFFFF));
    }

    /** Lazily load the persisted attempted-set (per world) so a block is never re-opened after a restart. */
    private void ensureLoaded() {
        if (attemptedLoaded) {
            return;
        }
        synchronized (this) {
            if (attemptedLoaded) {
                return;
            }
            try {
                // Where to persist the "already opened" set. Default: a file BESIDE the world folder
                // (its parent dir) rather than inside it — that parent is the bind-mounted host dir, so
                // the state lives outside Docker's ephemeral layer and outside the world data itself.
                String custom = Config.autoOpenStateFile();
                if (custom != null && !custom.isEmpty()) {
                    persistFile = java.nio.file.Paths.get(custom);
                } else {
                    String dir = Config.getWorldOutputDir();
                    if (dir != null && !dir.isEmpty()) {
                        java.nio.file.Path world = java.nio.file.Paths.get(dir).toAbsolutePath();
                        java.nio.file.Path parent = world.getParent();
                        persistFile = (parent != null ? parent : world).resolve("auto-open-attempted.txt");
                    }
                }
                if (persistFile != null && java.nio.file.Files.exists(persistFile)) {
                    for (String line : java.nio.file.Files.readAllLines(persistFile)) {
                        String[] p = line.trim().split("\\s+");
                        if (p.length >= 3) {
                            try {
                                attempted.add(keyOf(new Coordinate3D(
                                        Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]))));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Best-effort: never let persistence block or break the download.
            }
            attemptedLoaded = true;
        }
    }

    /** Append a newly-attempted position so it is never opened again, even after a restart. */
    private void persist(Coordinate3D pos) {
        if (persistFile == null) {
            return;
        }
        try {
            java.nio.file.Files.write(persistFile,
                    (pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Best-effort.
        }
    }

    public void setLastSequence(int sequence) {
        this.lastSequence = sequence;
    }

    public boolean isWaiting() {
        return waiting;
    }

    /**
     * Called by the clientbound OpenScreen handler. Returns true (and consumes the flag) when the
     * screen was opened by us, signalling that it must NOT be forwarded to the player's client.
     */
    public boolean claimPending() {
        if (pendingOpen && (System.currentTimeMillis() - pendingOpenMs) < PENDING_TTL_MS) {
            pendingOpen = false;
            return true;
        }
        // Stale or unset: clear it so an auto-open the server never answered can NEVER swallow the
        // player's own (real) container open later. This is the "have to click multiple times" fix.
        pendingOpen = false;
        return false;
    }

    /**
     * Called for each player movement packet (client->server thread). Opens at most one container
     * per call, respecting the configured delay and the one-at-a-time wait.
     */
    public void tick(Coordinate3D playerPos) {
        if (!Config.autoOpenContainers() || playerPos == null) {
            return;
        }
        ensureLoaded();
        // Gamemode gate. Config.autoOpenGamemodes() == null means "all gamemodes" (no gate — runs even
        // before the gamemode is known, so it works in survival on join without toggling). A non-null
        // set restricts to those gamemodes; an unknown gamemode (-1) is never in the set, so a
        // restricted sweep only starts once the mode has been observed (e.g. switching into spectator).
        java.util.Set<Integer> allowed = Config.autoOpenGamemodes();
        if (allowed != null && !allowed.contains(WorldManager.getInstance().getPlayerGamemode())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (waiting) {
            if (now - waitStartMs < OPEN_TIMEOUT_MS) {
                return; // still waiting for this container's content
            }
            // Timed out: container was blocked/obstructed/unopenable. Drop the stale pending flag too
            // (so it can't swallow a real open), leave it in `attempted`, and move on to the next one.
            waiting = false;
            pendingOpen = false;
        }
        if (now - lastOpenMs < Config.autoOpenDelayMs()) {
            return;
        }

        Coordinate3D target = WorldManager.getInstance()
                .findOpenableContainerNear(playerPos, Config.autoOpenReach(), pos -> attempted.contains(keyOf(pos)));
        if (target == null) {
            return;
        }

        attempted.add(keyOf(target));
        persist(target);
        // openWindow() (clientbound thread) associates the upcoming window with lastInteractedWith,
        // and since we inject the open packet (the proxy never parses it) we must set it ourselves.
        WorldManager.getInstance().getContainerManager().lastInteractedWith(target);
        sendOpen(target);
        waiting = true;
        pendingOpen = true;
        pendingOpenMs = now;
        waitStartMs = now;
        lastOpenMs = now;
    }

    /** Called from ContainerManager when an auto-opened window's content has been captured + saved. */
    public void onContentCaptured(int windowId) {
        sendClose(windowId);
        waiting = false;
        lastOpenMs = System.currentTimeMillis(); // start the cooldown from capture, not from open
    }

    private void sendOpen(Coordinate3D pos) {
        Protocol protocol = Config.versionReporter().getProtocol();
        int packetId = protocol.serverBound("UseItemOn");
        if (packetId < 0 || Config.getServerBoundInjector() == null) {
            return;
        }
        // Mirror DataTypeProvider.readCoordinates(): x<<38 | y<<26 | z.
        long packed = ((long) (pos.getX() & 0x3FFFFFF) << 38)
                | ((long) (pos.getY() & 0xFFF) << 26)
                | ((long) (pos.getZ() & 0x3FFFFFF));

        PacketBuilder packet = new PacketBuilder(packetId);
        packet.writeVarInt(0);          // hand: main
        packet.writeLong(packed);       // target block position
        packet.writeVarInt(1);          // face: top
        packet.writeFloat(0.5f);        // cursor x
        packet.writeFloat(0.5f);        // cursor y
        packet.writeFloat(0.5f);        // cursor z
        packet.writeBoolean(false);     // head inside block
        packet.writeVarInt(lastSequence); // block-change sequence (MC 1.19+)
        Config.getServerBoundInjector().enqueuePacket(packet);
        // The per-container action-bar line is shown on capture (with the item count), in
        // ContainerManager#sendInventorySavedMessage, formatted as "<type> - (<items>) <x> <y> <z>".
    }

    private void sendClose(int windowId) {
        Protocol protocol = Config.versionReporter().getProtocol();
        int packetId = protocol.serverBound("ContainerClose");
        if (packetId < 0 || Config.getServerBoundInjector() == null) {
            return;
        }
        PacketBuilder packet = new PacketBuilder(packetId);
        packet.writeByte((byte) windowId);
        Config.getServerBoundInjector().enqueuePacket(packet);
    }
}
