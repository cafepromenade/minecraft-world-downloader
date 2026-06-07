package game.data.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class AutoChatReplyTest {
    private static final String TRIGGER = "You have been warned by Console for";

    /** The motivating case: yellow label (opening a quote) + the red phrase to echo back. */
    @Test
    public void matchesWarnFormatAndReturnsRedText() {
        String json = "{\"text\":\"\",\"extra\":["
                + "{\"color\":\"yellow\",\"text\":\"You have been warned by Console for \\\"\"},"
                + "{\"color\":\"red\",\"text\":\"Two things are infinite.\\\" -Albert Einstein\"}"
                + "]}";

        String reply = AutoChatReply.computeReplyFromJson(json, TRIGGER);

        assertThat(reply).isEqualTo("Two things are infinite.\" -Albert Einstein");
    }

    /** The trigger may be written with or without the trailing quote/spaces — both should match. */
    @Test
    public void normalizationIgnoresSurroundingQuotesAndSpaces() {
        String json = "[{\"color\":\"yellow\",\"text\":\"  Echo this:  \"},{\"color\":\"red\",\"text\":\"hello world\"}]";

        assertThat(AutoChatReply.computeReplyFromJson(json, "Echo this:")).isEqualTo("hello world");
        assertThat(AutoChatReply.computeReplyFromJson(json, "\"Echo this:\"")).isEqualTo("hello world");
    }

    /** Colour is inherited from the parent component when a child does not override it. */
    @Test
    public void inheritsColourFromParent() {
        String json = "{\"color\":\"yellow\",\"text\":\"Say \",\"extra\":["
                + "{\"text\":\"now\"},"
                + "{\"color\":\"red\",\"text\":\"banana\"}"
                + "]}";

        // yellow = "Say now"; red = "banana"
        assertThat(AutoChatReply.computeReplyFromJson(json, "Say now")).isEqualTo("banana");
    }

    @Test
    public void noReplyWhenYellowDoesNotMatch() {
        String json = "[{\"color\":\"yellow\",\"text\":\"Different label\"},{\"color\":\"red\",\"text\":\"secret\"}]";
        assertThat(AutoChatReply.computeReplyFromJson(json, TRIGGER)).isNull();
    }

    @Test
    public void noReplyWhenThereIsNoRedText() {
        String json = "[{\"color\":\"yellow\",\"text\":\"Echo this:\"},{\"color\":\"green\",\"text\":\"nope\"}]";
        assertThat(AutoChatReply.computeReplyFromJson(json, "Echo this:")).isNull();
    }

    @Test
    public void noReplyWhenTriggerBlank() {
        String json = "[{\"color\":\"yellow\",\"text\":\"x\"},{\"color\":\"red\",\"text\":\"y\"}]";
        assertThat(AutoChatReply.computeReplyFromJson(json, "")).isNull();
        assertThat(AutoChatReply.computeReplyFromJson(json, null)).isNull();
    }

    /** Plain string components (no colour) never match yellow/red. */
    @Test
    public void plainStringComponentDoesNotMatch() {
        assertThat(AutoChatReply.computeReplyFromJson("\"just a plain message\"", TRIGGER)).isNull();
    }
}
