package packets.handler;

import config.Config;
import config.Option;
import config.Version;
import game.NetworkMode;
import proxy.ConnectionManager;

import java.util.HashMap;
import java.util.Map;

public class ClientBoundLoginPacketHandler extends PacketHandler {
    private HashMap<String, PacketOperator> operations = new HashMap<>();
    public ClientBoundLoginPacketHandler(ConnectionManager connectionManager) {
        super(connectionManager);

        operations.put("LoginDisconnect", provider -> {
            String reason = provider.readString();
            // The server refused the login (the usual cause of an "instantly disconnected" connection,
            // e.g. wrong version, not whitelisted, online-mode auth, anti-bot plugin). Show readable
            // text plus the raw chat component for debugging.
            System.out.println("[disconnect] server rejected the login: " + chatText(reason));
            System.out.println("[disconnect] raw reason: " + reason);
            return true;
        });

        operations.put("Hello", provider -> {
            String serverId = provider.readString();
            byte[] pubKey = provider.readByteArray(provider.readVarInt());
            byte[] nonce = provider.readByteArray(provider.readVarInt());

            if (Config.versionReporter().isAtLeast(Version.V1_20_6)) {
                boolean shouldAuthenticate = provider.readBoolean();
                getConnectionManager().getEncryptionManager().setServerEncryptionRequest(pubKey, nonce, serverId, shouldAuthenticate);
            } else {
                getConnectionManager().getEncryptionManager().setServerEncryptionRequest(pubKey, nonce, serverId);
            }

            return false;
        });
        operations.put("GameProfile", provider -> {
            String uuid = Config.versionReporter().select(String.class,
                    Option.of(Version.V1_16, () -> provider.readUUID().toString()),
                    Option.of(Version.ANY, provider::readString)
            );

            String username = provider.readString();
            System.out.println("Login success: " + username + " logged in with uuid " + uuid);

            if (!Config.versionReporter().isAtLeast(Version.V1_20_2)) {
                getConnectionManager().setMode(NetworkMode.GAME);
            }
            return true;
        });
        operations.put("LoginCompression", provider -> {
            int limit = provider.readVarInt();
            getConnectionManager().getCompressionManager().enableCompression(limit);
            return true;
        });

    }

    /** Best-effort readable text from a chat-component JSON (concatenates all "text" fields); falls back to the raw string. */
    private static String chatText(String json) {
        if (json == null) { return ""; }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            StringBuilder sb = new StringBuilder();
            while (m.find()) { sb.append(m.group(1)); }
            String t = sb.toString().replace("\\n", " ").trim();
            return t.isEmpty() ? json : t;
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public Map<String, PacketOperator> getOperators() {
        return operations;
    }

    @Override
    public boolean isClientBound() {
        return true;
    }
}
