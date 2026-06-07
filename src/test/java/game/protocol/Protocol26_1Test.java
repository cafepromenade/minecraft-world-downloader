package game.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import config.Version;
import config.VersionReporter;
import org.junit.jupiter.api.Test;

/**
 * 26.1 (protocol 775) cannot be exercised by a live bot — mineflayer/minecraft-data have no 26.x
 * protocol data ("unsupported protocol version: 26.1.2"). This test instead asserts that the proxy's
 * 26.1 mapping is wired to the SAME code paths that are verified end-to-end on 1.21.8/1.21.11:
 * the 1.21.5+ chunk handling, the 1.21.3+ Use Item On worldBorderHit field, NBT chat, and the
 * serverbound ChatMessage id used by the auto-reply.
 */
class Protocol26_1Test {
    private static final int PROTO_26_1 = 775;

    @Test
    void mapsTo26_1() {
        assertThat(ProtocolVersionHandler.getInstance()
                .getProtocolByProtocolVersion(PROTO_26_1).getVersion()).isEqualTo("26.1");
    }

    @Test
    void serverboundPacketsForAutoOpenAndReplyArePresent() {
        Protocol p = ProtocolVersionHandler.getInstance().getProtocolByProtocolVersion(PROTO_26_1);
        assertThat(p.serverBound("ChatMessage")).isEqualTo(0x08); // auto-reply
        assertThat(p.serverBound("UseItemOn")).isGreaterThanOrEqualTo(0); // auto-open injection
        assertThat(p.serverBound("ContainerClose")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void resolvesToTheVerifiedModernCodePaths() {
        VersionReporter r = new VersionReporter(PROTO_26_1);
        assertThat(r.isAtLeast(Version.V1_21_5)).isTrue(); // paletted-container length + array heightmaps
        assertThat(r.isAtLeast(Version.V1_21_3)).isTrue(); // Use Item On worldBorderHit field
        assertThat(r.isAtLeast(Version.V1_20_4)).isTrue(); // NBT chat components
        assertThat(r.isAtLeast(Version.V1_19)).isTrue();   // signed-chat serverbound format
    }
}
