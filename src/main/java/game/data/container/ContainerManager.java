package game.data.container;

import config.Config;
import game.data.chunk.ChunkEntities;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDim3D;
import game.data.WorldManager;
import game.data.chunk.Chunk;
import game.data.chunk.palette.BlockState;
import org.apache.commons.lang3.ArrayUtils;
import packets.DataTypeProvider;
import packets.builder.Chat;
import packets.builder.MessageTarget;
import packets.builder.PacketBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ContainerManager {
    private static final int PLAYER_INVENTORY = 0;

    // volatile: written on the serverbound thread (auto-opener tick / real UseItemOn) and read on the
    // clientbound thread (openWindow when OpenScreen arrives). Without the memory barrier the clientbound
    // thread could observe null or a stale target, registering an auto-opened window at the wrong/no
    // location so its contents are never saved.
    private volatile Coordinate3D lastInteractedWith;
    private final Map<Integer, InventoryWindow> knownWindows;
    private final Map<CoordinateDim3D, InventoryWindow> storedWindows;
    private final PlayerInventory playerInventory;

    public ContainerManager() {
        knownWindows = new HashMap<>();
        storedWindows = new HashMap<>();
        playerInventory = new PlayerInventory();
    }

    public PlayerInventory getPlayerInventory() {
        return playerInventory;
    }

    public void lastInteractedWith(Coordinate3D coordinates) {
        lastInteractedWith = coordinates;
    }

    public void openWindow_1_12(int windowId, int numSlots, String windowTitle) {
        if (windowId == PLAYER_INVENTORY) {
            return;
        }

        if (lastInteractedWith != null) {
            InventoryWindow window = new InventoryWindow(windowTitle, lastInteractedWith, numSlots);

            // if a window has 0 slots, ignore it
            if (window.getSlotCount() > 0) {
                knownWindows.put(windowId, window);
            }
        }
    }

    public void openWindow(int windowId, int windowType, String windowTitle) {
        if (windowId == PLAYER_INVENTORY) {
            return;
        }

        if (lastInteractedWith != null) {
            InventoryWindow window = new InventoryWindow(windowType, windowTitle, lastInteractedWith);

            // if a window has 0 slots, ignore it
            if (window.getSlotCount() > 0) {
                knownWindows.put(windowId, window);
            }
        }
    }

    public void closeWindow(int windowId) {
        if (!knownWindows.containsKey(windowId)) { return; }

        InventoryWindow window = knownWindows.remove(windowId);
        if (window.getSlotList() == null) { return; }

        closeWindow(window);

        // Always confirm — the inventory is remembered and will be written even if the chunk loads later.
        sendInventorySavedMessage(window);
    }

    private void closeWindow(InventoryWindow window) {
        if (window.getSlotList() == null) { return; }

        Chunk c = WorldManager.getInstance().getChunk(window.containerLocation.globalToChunk().addDimension(WorldManager.getInstance().getDimension()));
        BlockState block = (c != null) ? c.getBlockStateAt(window.getContainerLocation().withinChunk()) : null;

        // Double chests: split into two halves (each half is stored/applied via the recursive call).
        if (c != null && block != null && window.getSlotList().size() == 54 && block.hasProperty("type") && block.isDoubleChest()) {
            WorldManager.getInstance().touchChunk(c);
            addDoubleChestInventory(block, window);
            return;
        }
        if (c != null && block != null && window.getSlotList().size() == 54 && !block.hasProperty("type") && block.isChest()) {
            WorldManager.getInstance().touchChunk(c);
            handleChest1_12(block, window);
            return;
        }

        // Always remember the inventory so it survives chunk re-sends / block updates and is (re)applied
        // whenever this chunk is (re)loaded — even if the chunk isn't loaded right now.
        storedWindows.put(window.containerLocation.addDimension3D(WorldManager.getInstance().getDimension()), window);

        if (c != null && block != null) {
            WorldManager.getInstance().touchChunk(c);
            c.addInventory(window, false);
        }
    }

    private void sendInventorySavedMessage(InventoryWindow window) {
        if (!Config.sendInfoMessages() || Config.getPacketInjector() == null) {
            return;
        }
        Coordinate3D pos = window.getContainerLocation();

        // Number of items = non-empty container slots (empty slots are read as null; player-inventory
        // slots are already excluded by InventoryWindow#setSlots).
        int count = 0;
        if (window.getSlotList() != null) {
            for (Slot s : window.getSlotList()) {
                if (s != null) { count++; }
            }
        }

        // Container type from the block at the captured location (e.g. "chest", "barrel", "hopper").
        String type = resolveContainerType(pos);

        // Message text is NOT hardcoded: it comes from a configurable template (--container-message-format)
        // with {type} {count} {x} {y} {z} placeholders, so each deployment can choose its own format.
        String text = Config.containerMessageFormat()
                .replace("{type}", type)
                .replace("{count}", Integer.toString(count))
                .replace("{x}", Integer.toString(pos.getX()))
                .replace("{y}", Integer.toString(pos.getY()))
                .replace("{z}", Integer.toString(pos.getZ()));

        Chat message = new Chat(text);
        message.setColor("green");
        // Show only on the action bar (above the hotbar), not in the chat box.
        Config.getPacketInjector().enqueuePacket(PacketBuilder.constructClientMessage(message, MessageTarget.GAMEINFO));
    }

    /** Resolve the container type name (e.g. "chest", "barrel") from the block at the given position. */
    private String resolveContainerType(Coordinate3D pos) {
        Chunk c = WorldManager.getInstance().getChunk(
                pos.globalToChunk().addDimension(WorldManager.getInstance().getDimension()));
        if (c != null) {
            BlockState bs = c.getBlockStateAt(pos.withinChunk());
            if (bs != null && bs.getName() != null) {
                return bs.getName().replace("minecraft:", "");
            }
        }
        return "container";
    }


    /**
     * Handles double chests in 1.12.
     */
    private void handleChest1_12(BlockState block, InventoryWindow window) {
        Direction facing = Direction.valueOf(block.getProperty("facing").toUpperCase());
        Coordinate3D pos = window.getContainerLocation();

        Coordinate3D beforePos = pos.add(facing.clockwise().toCoordinate());
        BlockState blockBefore = WorldManager.getInstance().blockStateAt(beforePos);

        InventoryWindow[] chests = window.split();

        // for some reason the ordering of double chests depends on the direction they are facing in 1.12 (wtf?)
        if (facing.equals(Direction.NORTH) || facing.equals(Direction.EAST)) {
            ArrayUtils.swap(chests, 0, 1);
        }

        // if it's the left half of the chest
        if (blockBefore == block) {
            chests[0].adjustContainerLocation(facing.clockwise().toCoordinate());
        } else {
            // otherwise it must be the right half ... we don't support triple chests
            chests[1].adjustContainerLocation(facing.counterClockwise().toCoordinate());
        }


        closeWindow(chests[0]);
        closeWindow(chests[1]);
    }

    /**
     * Split Window into two halves, for two halves of a double chest.
     */
    private void addDoubleChestInventory(BlockState block, InventoryWindow window) {
        InventoryWindow[] chests = window.split();
        Coordinate2D companionDirection = getCompanionChestDirection(block);

        int adjustPositionOf = block.getProperty("type").equals("left") ? 0 : 1;
        chests[adjustPositionOf].adjustContainerLocation(companionDirection);

        closeWindow(chests[0]);
        closeWindow(chests[1]);
    }

    private Coordinate2D getCompanionChestDirection(BlockState block) {
        Direction direction = Direction.valueOf(block.getProperty("facing").toUpperCase());

        if (block.getProperty("type").equals("left")) {
            return direction.clockwise().toCoordinate();
        } else {
            return direction.counterClockwise().toCoordinate();
        }
    }

    public void items(int windowId, int count, DataTypeProvider provider) {
        // window 0 is the player's own inventory; remember it so it can be written to level.dat
        if (windowId == PLAYER_INVENTORY) {
            playerInventory.setSlots(provider.readSlots(count));
            return;
        }

        InventoryWindow window = knownWindows.get(windowId);

        if (window != null) {
            List<Slot> slots = provider.readSlots(count);

            window.setSlots(slots);

            // Auto-open mode: the player never opened this container and won't close it. Save it now,
            // then have the auto-opener close it server-side and advance to the next one.
            if (Config.autoOpenContainers()) {
                ContainerAutoOpener opener = WorldManager.getInstance().getContainerAutoOpener();
                if (opener.isWaiting() && opener.getPendingMinecart() != null) {
                    // Container minecart: contents belong to the ENTITY, not a block. Attach them to the
                    // minecart so they are written into the saved chunk's entity NBT, then log + advance.
                    game.data.entity.specific.ContainerMinecart mc = opener.getPendingMinecart();
                    try {
                        mc.setItems(window.getSlotList());
                        WorldManager.getInstance().getEntityRegistry().markEntityChunkUnsaved(mc);
                        try {
                            WorldManager.getInstance().getAutoOpenItemLog().log(window,
                                    mc.getTypeName() == null ? "minecart" : mc.getTypeName().replace("minecraft:", ""),
                                    WorldManager.getInstance().getDimension());
                        } catch (RuntimeException logEx) {
                            System.out.println("auto-open: failed to log minecart items: " + logEx.getMessage());
                        }
                    } catch (RuntimeException ex) {
                        System.out.println("auto-open: failed to capture minecart " + windowId + ": " + ex.getMessage());
                    } finally {
                        knownWindows.remove(windowId);
                        opener.onContentCaptured(windowId);
                    }
                } else if (opener.isWaiting()) {
                    try {
                        closeWindow(windowId);
                        // Saved OK — record what we just captured (auto-open only). Uses the retained
                        // `window` reference since closeWindow has removed it from knownWindows. Kept in
                        // its own try so a logging failure is never reported as a save failure, and never
                        // stalls the sweep.
                        try {
                            WorldManager.getInstance().getAutoOpenItemLog().log(
                                    window, resolveContainerType(window.getContainerLocation()),
                                    WorldManager.getInstance().getDimension());
                        } catch (RuntimeException logEx) {
                            System.out.println("auto-open: failed to log items for container " + windowId
                                    + ": " + logEx.getMessage());
                        }
                    } catch (RuntimeException ex) {
                        // A failure to save one container must not silently lose it AND stall the sweep:
                        // DataReader swallows handler throwables, so without this catch the auto-opener
                        // would stay waiting=true until its 1.5s timeout on every bad container. Log + go on.
                        System.out.println("auto-open: failed to save container " + windowId + ": " + ex.getMessage());
                    } finally {
                        // Always free the server-side window and advance to the next container, even if the
                        // local save threw — otherwise one bad container halts the entire sweep.
                        opener.onContentCaptured(windowId);
                    }
                }
            }
        }
    }

    /**
     * Single-slot update. Only window 0 (the player's own inventory) is tracked here; container
     * slot updates are still captured via the full-content packet on close.
     */
    public void setSlot(int windowId, int slot, Slot slotData) {
        if (windowId == PLAYER_INVENTORY) {
            playerInventory.setSlot(slot, slotData);
        }
    }

    public void loadPreviousInventoriesAt(ChunkEntities c, CoordinateDim3D location) {
        if (storedWindows.containsKey(location)) {
            c.addInventory(storedWindows.get(location), false);
        }

    }
}

