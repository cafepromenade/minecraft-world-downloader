package packets.handler;

import config.Version;
import game.NetworkMode;
import java.util.HashMap;
import java.util.Map;

import config.Config;
import game.data.WorldManager;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDouble3D;
import proxy.ConnectionManager;

public class ServerBoundGamePacketHandler extends PacketHandler {
    private HashMap<String, PacketOperator> operations = new HashMap<>();
    public ServerBoundGamePacketHandler(ConnectionManager connectionManager) {
        super(connectionManager);

        PacketOperator updatePlayerPosition = provider -> {
            double x = provider.readDouble();
            double y = provider.readDouble();
            double z = provider.readDouble();

            WorldManager.getInstance().setPlayerPosition(x, y, z);
            WorldManager.getInstance().getContainerAutoOpener()
                    .tick(WorldManager.getInstance().getPlayerPosition());

            return true;
        };

        PacketOperator updatePlayerRotation = provider -> {
            double yaw = provider.readFloat() % 360;
            provider.readFloat(); // pitch
            provider.readBoolean(); // on ground
            WorldManager.getInstance().setPlayerRotation(yaw);
            return true;
        };

        operations.put("MovePlayerPos", updatePlayerPosition);
        operations.put("MovePlayerRot", updatePlayerRotation);
        operations.put("MovePlayerPosRot", (provider) -> {
            updatePlayerPosition.apply(provider);
            updatePlayerRotation.apply(provider);
            return true;
        });

        operations.put("MoveVehicle", updatePlayerPosition);

        operations.put("UseItem", provider -> {
            // newer versions first include a VarInt with the hand
            if (Config.versionReporter().isAtLeast(Version.V1_14)) {
                provider.readVarInt();
            }

            return true;
        });

        operations.put("ContainerClose", provider -> {
            final byte windowId = provider.readNext();
            WorldManager.getInstance().getContainerManager().closeWindow(windowId);
            WorldManager.getInstance().getVillagerManager().closeWindow(windowId);
            return true;
        });

        // block placements
        operations.put("UseItemOn", provider -> {
            // The field order differs before 1.14: pre-1.14 is location, face, hand, cursor x/y/z; from
            // 1.14 it is hand, location, face, cursor x/y/z, inside-block, [sequence 1.19+]. We only need
            // the targeted block to associate the next opened container with it.
            if (!Config.versionReporter().isAtLeast(Version.V1_14)) {
                Coordinate3D coords = provider.readCoordinates();
                WorldManager.getInstance().getContainerManager().lastInteractedWith(coords);
                return true;
            }
            provider.readVarInt();  // Hand
            Coordinate3D coords = provider.readCoordinates();
            provider.readVarInt();  // Block face
            provider.readFloat();   // Cursor x
            provider.readFloat();   // Cursor y
            provider.readFloat();   // Cursor z
            provider.readBoolean(); // If the player's head is inside of a block
            if (Config.versionReporter().isAtLeast(Version.V1_21_3)) {
                provider.readBoolean(); // worldBorderHit (added in 1.21.2/1.21.3)
            }
            // MC 1.19+ appends a block-change sequence; remember it so auto-open opens never run ahead
            // of the real client's sequence.
            if (Config.versionReporter().isAtLeast(Version.V1_19)) {
                WorldManager.getInstance().getContainerAutoOpener().setLastSequence(provider.readVarInt());
            }

            WorldManager.getInstance().getContainerManager().lastInteractedWith(coords);
            return true;
        });

        operations.put("SetCommandBlock", provider -> {
            WorldManager.getInstance().getCommandBlockManager().readAndStoreCommandBlock(provider);
            return true;
        });

        operations.put("Interact", provider -> {
            WorldManager.getInstance().getVillagerManager().lastInteractedWith(provider);
            return true;
        });

        operations.put("ConfigurationAcknowledged", provider ->{
            getConnectionManager().setMode(NetworkMode.CONFIGURATION);
            return true;
        });
    }

    @Override
    public Map<String, PacketOperator> getOperators() {
        return operations;
    }

    @Override
    public boolean isClientBound() {
        return false;
    }
}

