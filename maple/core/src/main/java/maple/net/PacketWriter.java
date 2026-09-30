package maple.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds a client→server packet (little endian, v83 string encoding). */
public final class PacketWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(32);

    public PacketWriter(int opcode) {
        writeShort(opcode);
    }

    public PacketWriter writeByte(int v) { out.write(v); return this; }
    public PacketWriter writeBool(boolean v) { return writeByte(v ? 1 : 0); }
    public PacketWriter writeShort(int v) { out.write(v); out.write(v >> 8); return this; }
    public PacketWriter writeInt(int v) { out.write(v); out.write(v >> 8); out.write(v >> 16); out.write(v >> 24); return this; }
    public PacketWriter writeLong(long v) { writeInt((int) v); return writeInt((int) (v >>> 32)); }
    public PacketWriter writeBytes(byte[] b) { out.write(b, 0, b.length); return this; }

    public PacketWriter writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        writeShort(b.length);
        return writeBytes(b);
    }

    public byte[] bytes() { return out.toByteArray(); }
}
