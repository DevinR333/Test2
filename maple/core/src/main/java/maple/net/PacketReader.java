package maple.net;

import java.nio.charset.StandardCharsets;

/** Reads a server→client packet. */
public final class PacketReader {
    private final byte[] data;
    private int pos;
    public final int opcode;

    public PacketReader(byte[] data) {
        this.data = data;
        this.opcode = data.length >= 2 ? (data[0] & 0xFF) | (data[1] & 0xFF) << 8 : -1;
        this.pos = 2;
    }

    public int available() { return data.length - pos; }
    public int position() { return pos; }
    public void seek(int p) { pos = p; }
    public void skip(int n) { pos += n; }

    public int readByte() { return data[pos++]; }
    public int readUByte() { return data[pos++] & 0xFF; }
    public boolean readBool() { return data[pos++] != 0; }
    public short readShort() { short v = (short) ((data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8); pos += 2; return v; }
    public int readUShort() { return readShort() & 0xFFFF; }
    public int readInt() {
        int v = (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16 | (data[pos + 3] & 0xFF) << 24;
        pos += 4;
        return v;
    }
    public long readLong() { long lo = readInt() & 0xFFFFFFFFL; long hi = readInt() & 0xFFFFFFFFL; return lo | hi << 32; }

    public byte[] readBytes(int n) {
        byte[] b = new byte[n];
        System.arraycopy(data, pos, b, 0, n);
        pos += n;
        return b;
    }

    public String readString() {
        int len = readUShort();
        String s = new String(data, pos, len, StandardCharsets.ISO_8859_1);
        pos += len;
        return s;
    }

    /** Fixed-length, zero-padded string (character names). */
    public String readFixedString(int len) {
        int end = pos;
        while (end < pos + len && data[end] != 0) end++;
        String s = new String(data, pos, end - pos, StandardCharsets.ISO_8859_1);
        pos += len;
        return s;
    }
}
