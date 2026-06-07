package game.data.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import config.Config;
import config.Version;
import game.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import packets.builder.PacketBuilder;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.StringTag;
import se.llbit.nbt.Tag;

/**
 * EXPERIMENTAL, opt-in (--auto-reply). Watches incoming chat (clientbound SystemChat) for a message
 * whose YELLOW text exactly matches a configured trigger, and, when it matches, sends that same
 * message's RED text back to the server as a normal chat message.
 *
 * <p>Motivating case: a server posts a line like
 * <pre>You have been warned by Console for "&lt;some phrase&gt;"</pre>
 * where the label ("You have been warned by Console for") is yellow and the phrase is red. With
 * {@code --auto-reply-trigger "You have been warned by Console for"} the downloader will say the red
 * phrase in chat automatically.
 *
 * <p>Cautions:
 * <ul>
 *   <li>This sends a REAL chat message to the server (visible to everyone). Use only where permitted.</li>
 *   <li>The message is sent UNSIGNED. Servers that enforce secure chat (vanilla
 *       {@code enforce-secure-profile=true}) may reject it and disconnect you. Most plugin/offline
 *       servers accept unsigned chat.</li>
 *   <li>Replies are rate-limited (--auto-reply-delay) and identical repeats are suppressed to avoid
 *       spam / anti-cheat kicks.</li>
 * </ul>
 */
public class AutoChatReply {
    /** Suppress an identical reply that recurs within this window (loop / repeat guard). */
    private static final long DEDUPE_WINDOW_MS = 5000;
    /** Minecraft chat messages are capped at 256 characters; longer messages are rejected. */
    private static final int MAX_MESSAGE_LENGTH = 256;

    private long lastSendMs = 0;
    private String lastReply = null;
    private boolean warnedUnsupported = false;

    /** A flattened run of text with its effective (inherited) colour. */
    record Run(String color, String text) { }

    /** Handle a chat component delivered as NBT (1.20.3+ servers). */
    public void onComponentNbt(SpecificTag tag) {
        if (tag == null) {
            return;
        }
        List<Run> runs = new ArrayList<>();
        flattenNbt(tag, null, runs);
        process(runs);
    }

    /** Handle a chat component delivered as a JSON string (pre-1.20.3 servers). */
    public void onComponentJson(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        List<Run> runs = new ArrayList<>();
        try {
            flattenJson(JsonParser.parseString(json), null, runs);
        } catch (Exception ex) {
            return; // malformed component; nothing to match against
        }
        process(runs);
    }

    /**
     * Flatten an NBT text component into ordered colour runs, inheriting the parent colour where a
     * child does not set its own (mirrors how the client renders nested components).
     */
    static void flattenNbt(SpecificTag tag, String inheritedColor, List<Run> out) {
        if (tag instanceof StringTag s) {
            out.add(new Run(inheritedColor, s.value));
            return;
        }
        if (tag instanceof ListTag list) {
            for (SpecificTag child : list) {
                flattenNbt(child, inheritedColor, out);
            }
            return;
        }
        if (tag instanceof CompoundTag compound) {
            String color = inheritedColor;
            Tag colorTag = compound.get("color");
            if (colorTag instanceof StringTag c) {
                color = c.value;
            }
            Tag textTag = compound.get("text");
            if (textTag instanceof StringTag t && !t.value.isEmpty()) {
                out.add(new Run(color, t.value));
            }
            Tag extra = compound.get("extra");
            if (extra instanceof ListTag list) {
                for (SpecificTag child : list) {
                    flattenNbt(child, color, out);
                }
            }
        }
    }

    /** JSON-string counterpart of {@link #flattenNbt}. */
    static void flattenJson(JsonElement el, String inheritedColor, List<Run> out) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonPrimitive()) {
            out.add(new Run(inheritedColor, el.getAsString()));
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                flattenJson(child, inheritedColor, out);
            }
            return;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            String color = inheritedColor;
            if (o.has("color") && o.get("color").isJsonPrimitive()) {
                color = o.get("color").getAsString();
            }
            if (o.has("text") && o.get("text").isJsonPrimitive()) {
                String t = o.get("text").getAsString();
                if (!t.isEmpty()) {
                    out.add(new Run(color, t));
                }
            }
            if (o.has("extra") && o.get("extra").isJsonArray()) {
                for (JsonElement child : o.get("extra").getAsJsonArray()) {
                    flattenJson(child, color, out);
                }
            }
        }
    }

    private void process(List<Run> runs) {
        String reply = computeReply(runs, Config.autoReplyTrigger());
        if (reply != null) {
            trySend(reply);
        }
    }

    /**
     * Pure matching: concatenate the message's yellow and red text; if the yellow text matches the
     * trigger, return the sanitized red text to send back, otherwise null. Visible for testing.
     */
    static String computeReply(List<Run> runs, String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return null; // nothing configured to match
        }

        StringBuilder yellow = new StringBuilder();
        StringBuilder red = new StringBuilder();
        for (Run r : runs) {
            if (r.color() == null) {
                continue;
            }
            if (r.color().equalsIgnoreCase("yellow")) {
                yellow.append(r.text());
            } else if (r.color().equalsIgnoreCase("red")) {
                red.append(r.text());
            }
        }

        if (red.length() == 0 || !matches(yellow.toString(), trigger)) {
            return null;
        }
        String reply = sanitize(red.toString());
        return reply.isEmpty() ? null : reply;
    }

    /** Convenience for tests: match against a JSON-string chat component. */
    static String computeReplyFromJson(String json, String trigger) {
        List<Run> runs = new ArrayList<>();
        flattenJson(JsonParser.parseString(json), null, runs);
        return computeReply(runs, trigger);
    }

    /** Exact match of the message's yellow text against the trigger, ignoring surrounding quotes/space. */
    private static boolean matches(String yellow, String trigger) {
        return normalize(yellow).equalsIgnoreCase(normalize(trigger));
    }

    /** Trim whitespace and strip surrounding double-quotes (the warn format opens a quote in yellow). */
    private static String normalize(String s) {
        s = s.trim();
        while (!s.isEmpty() && (s.charAt(0) == '"' || Character.isWhitespace(s.charAt(0)))) {
            s = s.substring(1);
        }
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (last != '"' && !Character.isWhitespace(last)) {
                break;
            }
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Strip control characters / section signs and clamp to the chat length limit. */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r') {
                sb.append(' ');
            } else if (ch >= 0x20 && ch != '§') {
                sb.append(ch);
            }
        }
        String out = sb.toString().trim();
        return out.length() > MAX_MESSAGE_LENGTH ? out.substring(0, MAX_MESSAGE_LENGTH) : out;
    }

    private synchronized void trySend(String reply) {
        long now = System.currentTimeMillis();
        if (now - lastSendMs < Config.autoReplyDelayMs()) {
            return; // rate limit
        }
        if (reply.equals(lastReply) && now - lastSendMs < DEDUPE_WINDOW_MS) {
            return; // identical repeat — avoid loops / spam
        }
        if (sendChat(reply)) {
            lastSendMs = now;
            lastReply = reply;
            if (Config.sendInfoMessages()) {
                System.out.println("[auto-reply] sent: " + reply);
            }
        }
    }

    /** Build and inject an (unsigned) serverbound chat message. Returns false if unsupported. */
    private boolean sendChat(String message) {
        Protocol protocol = Config.versionReporter().getProtocol();
        int packetId = protocol.serverBound("ChatMessage");
        if (packetId < 0 || Config.getServerBoundInjector() == null) {
            if (!warnedUnsupported) {
                warnedUnsupported = true;
                System.out.println("[auto-reply] sending chat is not supported for this game version; skipping.");
            }
            return false;
        }

        PacketBuilder packet = new PacketBuilder(packetId);
        packet.writeString(message);
        packet.writeLong(System.currentTimeMillis());   // timestamp
        packet.writeLong(0L);                            // salt
        packet.writeBoolean(false);                      // no signature (unsigned message)
        packet.writeVarInt(0);                           // acknowledged-messages offset / count
        packet.writeByteArray(new byte[]{0, 0, 0});      // acknowledged bitset (fixed 20 bits = 3 bytes)
        if (Config.versionReporter().isAtLeast(Version.V1_21_5)) {
            packet.writeByte((byte) 0);                  // acknowledgement checksum (added in 1.21.5)
        }
        Config.getServerBoundInjector().enqueuePacket(packet);
        return true;
    }
}
