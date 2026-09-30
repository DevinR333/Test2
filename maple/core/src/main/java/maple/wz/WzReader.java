package maple.wz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Little-endian cursor over a WZ file with the WZ-specific encodings (compressed ints, encrypted strings). */
public final class WzReader {
    final ByteBuffer buf;
    final WzFile file;
    private int pos;

    WzReader(WzFile file, ByteBuffer source) {
        this.file = file;
        this.buf = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    public int pos() { return pos; }
    public void seek(int p) { pos = p; }
    public void skip(int n) { pos += n; }
    public int limit() { return buf.limit(); }

    public byte s8() { return buf.get(pos++); }
    public int u8() { return buf.get(pos++) & 0xFF; }
    public short s16() { short v = buf.getShort(pos); pos += 2; return v; }
    public int u16() { return s16() & 0xFFFF; }
    public int s32() { int v = buf.getInt(pos); pos += 4; return v; }
    public long s64() { long v = buf.getLong(pos); pos += 8; return v; }
    public float f32() { float v = buf.getFloat(pos); pos += 4; return v; }
    public double f64() { double v = buf.getDouble(pos); pos += 8; return v; }

    public int cint() {
        byte b = s8();
        return b == -128 ? s32() : b;
    }

    public long clong() {
        byte b = s8();
        return b == -128 ? s64() : b;
    }

    public byte[] bytes(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = buf.get(pos + i);
        pos += n;
        return out;
    }

    public String ascii(int n) {
        return new String(bytes(n), StandardCharsets.ISO_8859_1);
    }

    public String cstring() {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = u8()) != 0) sb.append((char) b);
        return sb.toString();
    }

    /** An encrypted WZ string: negative length = 8-bit chars, positive = UTF-16. */
    public String wzString() {
        WzKey key = file.key;
        byte small = s8();
        if (small == 0) return "";
        StringBuilder sb;
        if (small > 0) {
            int len = small == 127 ? s32() : small;
            if (len <= 0 || len > 1 << 20) throw new WzException("bad string length " + len + " at " + (pos - 1));
            sb = new StringBuilder(len);
            int mask = 0xAAAA;
            for (int i = 0; i < len; i++) {
                int c = u16();
                c ^= mask;
                c ^= ((key.at(i * 2 + 1) & 0xFF) << 8) | (key.at(i * 2) & 0xFF);
                sb.append((char) (c & 0xFFFF));
                mask++;
            }
        } else {
            int len = small == -128 ? s32() : -small;
            if (len <= 0 || len > 1 << 20) throw new WzException("bad string length " + len + " at " + (pos - 1));
            sb = new StringBuilder(len);
            int mask = 0xAA;
            for (int i = 0; i < len; i++) {
                int c = u8();
                c ^= mask;
                c ^= key.at(i) & 0xFF;
                sb.append((char) (c & 0xFF));
                mask++;
            }
        }
        return sb.toString();
    }

    public String wzStringAt(int offset) {
        int back = pos;
        pos = offset;
        String s = wzString();
        pos = back;
        return s;
    }

    /** Directory entry offsets are obfuscated with the version hash. */
    public int offset() {
        long start = file.dataStart & 0xFFFFFFFFL;
        long off = ((pos - start) ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
        off = (off * (file.versionHash & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        off = (off - 0x581C3F6DL) & 0xFFFFFFFFL;
        int rot = (int) (off & 0x1F);
        int o = (int) off;
        o = Integer.rotateLeft(o, rot);
        int enc = s32();
        o ^= enc;
        o += (int) (start * 2);
        return o;
    }
}
