package packets.builder;

import game.data.coordinates.Coordinate3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packets.DataTypeProvider;
import packets.UUID;
import packets.lib.ByteQueue;
import packets.version.DataTypeProvider_1_13;
import packets.version.DataTypeProvider_1_14;
import se.llbit.nbt.*;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PacketBuilderAndParserTest {

    protected PacketBuilder builder;
    protected DataTypeProvider parser;

    @BeforeEach
    public void beforeEach() {
        builder = new PacketBuilder(0x00);
    }

    @AfterEach
    public void afterEach() {
        assertThat(parser.hasNext()).isFalse();
    }

    /**
     * Get a DataTypeProvider built from the exiting packet builder.
     */
    protected DataTypeProvider getParser() {
        ByteQueue built = builder.build();
        byte[] arr = new byte[built.size()];
        built.copyTo(arr);

        parser = new DataTypeProvider_1_14(arr);
        int length = parser.readVarInt();
        assertThat(length).isGreaterThan(0);

        int packetId = parser.readVarInt();
        return parser;
    }

    @Test
    void varInt() {
        int before = 10;
        builder.writeVarInt(before);

        int after = getParser().readVarInt();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void varIntBig() {
        int before = 2 << 26 - 1;
        builder.writeVarInt(before);

        int after = getParser().readVarInt();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void varIntNegative() {
        int before = - 1337;
        builder.writeVarInt(before);

        int after = getParser().readVarInt();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void intTest() {
        int before = 10;
        builder.writeInt(before);

        int after = getParser().readInt();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void stringTest() {
        String before = "This is a string with very many words in it.";
        builder.writeString(before);

        String after = getParser().readString();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void stringUtf8Test() {
        // Multi-byte UTF-8: accents, section sign (color codes), CJK and an emoji. The old reader
        // appended each byte as a code point (Latin-1) and turned these into mojibake.
        String before = "Café ñ §6日本語 🧱 box";
        builder.writeString(before);

        String after = getParser().readString();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void shortTest() {
        int before = 42;
        builder.writeShort(before);

        int after = getParser().readShort();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void shortNegativeTest() {
        int before = -42;
        builder.writeShort(before);

        int after = getParser().readShort();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void byteArray() {
        byte[] before = {1, 2, 3, 2, 1};
        builder.writeByteArray(before);

        byte[] after = getParser().readByteArray(before.length);

        assertThat(after).isEqualTo(before);
    }

    @Test
    void boolTrue() {
        builder.writeBoolean(true);

        assertThat(getParser().readBoolean()).isTrue();
    }

    @Test
    void boolFalse() {
        builder.writeBoolean(false);

        assertThat(getParser().readBoolean()).isFalse();
    }

    @Test
    void nbtCompoundTest() {
        CompoundTag before = new CompoundTag();
        before.add("someKey", new IntTag(30));

        builder.writeNbt(before);

        assertThat(getParser().readNbtTag()).isEqualTo(before);
    }

    @Test
    void nbtListTest() {
        ListTag before = new ListTag(Tag.TAG_STRING, Arrays.asList(
                new StringTag("a"),
                new StringTag("b"),
                new StringTag("c")
        ));

        builder.writeNbt(before);

        assertThat(getParser().readNbtTag()).isEqualTo(before);
    }

    @Test
    void longTest() {
        long before = 2L^48 - 2L^12;
        builder.writeLong(before);

        long after = getParser().readLong();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void varLongLarge() {
        // Needs >5 bytes, with bits above position 32. The old reader shifted an int, wrapping at
        // 32 bits and corrupting the high bits.
        long before = 1234567890123456789L;
        parser = new DataTypeProvider(encodeVarLong(before));

        assertThat(parser.readVarLong()).isEqualTo(before);
    }

    @Test
    void varLongNegative() {
        // Negative VarLongs are sign-extended to the full 10 bytes; the high bits must survive.
        long before = -42L;
        parser = new DataTypeProvider(encodeVarLong(before));

        assertThat(parser.readVarLong()).isEqualTo(before);
    }

    /** Standard Minecraft VarLong encoding (7-bit groups, MSB continuation), used to feed readVarLong. */
    private static byte[] encodeVarLong(long value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        do {
            byte temp = (byte) (value & 0b01111111);
            value >>>= 7;
            if (value != 0) {
                temp |= (byte) 0b10000000;
            }
            out.write(temp);
        } while (value != 0);
        return out.toByteArray();
    }

    @Test
    void floatTest() {
        float before = 0.12345f;
        builder.writeFloat(before);

        float after = getParser().readFloat();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void uuidTest() {
        UUID before = new UUID(2L^42, 2L^12);
        builder.writeUUID(before);

        UUID after = getParser().readUUID();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void parseCoordinates() {
        long x = -1337;
        long y = 64;
        long z = -4201;
        long encoded = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF);

        builder.writeLong(encoded);

        Coordinate3D after = getParser().readCoordinates();

        assertThat(after).isEqualTo(new Coordinate3D(x, y, z));
    }

    @Test
    void parseBit() {
        BitSet before = new BitSet();
        before.set(2);
        before.set(4);
        before.set(6);
        before.set(9);
        builder.writeBitSet(before);

        BitSet after = getParser().readBitSet();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void parseCoordinatesOther() {
        long x = 1000;
        long y = 72;
        long z = 9800;
        long encoded = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF);

        builder.writeLong(encoded);

        Coordinate3D after = getParser().readCoordinates();

        assertThat(after).isEqualTo(new Coordinate3D(x, y, z));
    }
}