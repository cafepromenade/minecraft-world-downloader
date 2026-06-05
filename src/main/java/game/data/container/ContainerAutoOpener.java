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
    /** Only sweep while the player is in spectator mode (gamemode 3). */
    private static final int SPECTATOR = 3;

    private static final Set<String> OPENABLE = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:brewing_stand"
    );

    /** Positions we have already attempted (captured, blocked, or unopenable) — never retried. */
    private final Set<Coordinate3D> attempted = ConcurrentHashMap.newKeySet();
    private volatile boolean waiting = false;
    /** Set when we inject an open; the matching clientbound OpenScreen is swallowed so the client GUI
     *  never opens (a player cannot move while a container GUI is open, which would stall the sweep). */
    private volatile boolean pendingOpen = false;
    private volatile long waitStartMs = 0;
    private volatile long lastOpenMs = 0;
    /** Latest block-interaction sequence seen from the real client; reused so we never get ahead of it. */
    private volatile int lastSequence = 0;

    public static boolean isOpenable(String blockName) {
        return blockName != null && (OPENABLE.contains(blockName) || blockName.endsWith("_shulker_box"));
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
        if (pendingOpen) {
            pendingOpen = false;
            return true;
        }
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
        // Spectator-only gate: never sweep unless the player is currently in spectator mode.
        if (WorldManager.getInstance().getPlayerGamemode() != SPECTATOR) {
            return;
        }
        long now = System.currentTimeMillis();
        if (waiting) {
            if (now - waitStartMs < OPEN_TIMEOUT_MS) {
                return; // still waiting for this container's content
            }
            // Timed out: container was blocked/obstructed/unopenable. Leave it in `attempted` and move on.
            waiting = false;
        }
        if (now - lastOpenMs < Config.autoOpenDelayMs()) {
            return;
        }

        Coordinate3D target = WorldManager.getInstance()
                .findOpenableContainerNear(playerPos, Config.autoOpenReach(), attempted::contains);
        if (target == null) {
            return;
        }

        attempted.add(target);
        // openWindow() (clientbound thread) associates the upcoming window with lastInteractedWith,
        // and since we inject the open packet (the proxy never parses it) we must set it ourselves.
        WorldManager.getInstance().getContainerManager().lastInteractedWith(target);
        sendOpen(target);
        waiting = true;
        pendingOpen = true;
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
