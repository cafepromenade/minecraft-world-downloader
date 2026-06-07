package packets.handler.plugins;

import config.Config;
import config.Option;
import config.Version;
import packets.DataTypeProvider;
import proxy.voicechat.VoiceProxyManager;

public abstract class PluginChannelHandler {

    private static PluginChannelHandler instance;

    public static PluginChannelHandler getInstance() {
        if (instance == null) {
            instance = Config.versionReporter().select(PluginChannelHandler.class,
                   Option.of(Version.V1_12, PluginChannelHandler1_12::new),
                   Option.of(Version.ANY, DefaultPluginChannelHandler::new)
            );
        }
        return instance;
    }

    /**
     * Handle an incoming CustomPayload packet from the server.
     *
     * @return true  -> forward the original packet to the client unchanged
     *         false -> drop the original (a replacement may have been injected)
     */
    public abstract boolean handleCustomPayload(DataTypeProvider provider);

    public static void reset() {
        instance = null;
    }
}

class DefaultPluginChannelHandler extends PluginChannelHandler {
    @Override
    public boolean handleCustomPayload(DataTypeProvider provider) {
        // 1.13+ namespaced channel (e.g. "forge:handshake", "minecraft:brand", a voice-chat channel).
        // The voice proxy may rewrite + re-inject the packet (returning false to drop the original).
        String channel = provider.readString();
        return VoiceProxyManager.getInstance().handleChannel(channel, provider);
    }
}
