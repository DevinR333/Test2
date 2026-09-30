package maple.wz;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A single .wz archive (PKG1). Reads the directory tree eagerly and .img contents lazily,
 * straight out of a memory-mapped buffer so only what a map needs is ever touched.
 */
public final class WzFile {
    public final String name;
    final ByteBuffer data;
    final WzReader reader;
    int dataStart;
    int versionHash;
    public int version = -1;
    public int encryptedVersion;
    WzKey key = WzKey.GMS;
    public final WzNode root;

    public WzFile(String name, ByteBuffer data) {
        this.name = name;
        this.data = data;
        this.reader = new WzReader(this, data);
        String base = name.endsWith(".wz") ? name.substring(0, name.length() - 3) : name;
        this.root = new WzNode(null, base, WzNode.Type.DIR);
        this.root.file = this;
        open();
    }

    public String keyName() { return key.name; }

    private void open() {
        WzReader r = reader;
        r.seek(0);
        String ident = r.ascii(4);
        if (!ident.equals("PKG1")) throw new WzException(name + ": not a WZ file (header '" + ident + "')");
        r.s64(); // file size
        dataStart = r.s32();
        r.seek(dataStart);
        encryptedVersion = r.u16();

        // Pick the string key first: the first directory entry name must decode to readable text.
        key = detectKey();

        // The version is only stored as an 8-bit checksum; try v83 first, then everything else,
        // keeping the first one whose directory parses cleanly.
        List<Integer> tries = new ArrayList<>();
        tries.add(83);
        for (int v = 1; v < 1000; v++) if (v != 83) tries.add(v);
        WzException last = null;
        for (int v : tries) {
            int hash = versionHash(v);
            if (hash == 0) continue;
            versionHash = hash;
            root.children = null;
            try {
                r.seek(dataStart + 2);
                parseDirectory(root, 0);
                version = v;
                return;
            } catch (WzException | IndexOutOfBoundsException | IllegalArgumentException e) {
                last = e instanceof WzException ? (WzException) e : new WzException(e.toString());
            }
        }
        throw new WzException(name + ": could not find a working version (encrypted version " + encryptedVersion + ")", last);
    }

    private int versionHash(int v) {
        String s = Integer.toString(v);
        int hash = 0;
        for (int i = 0; i < s.length(); i++) hash = 32 * hash + s.charAt(i) + 1;
        int a = (hash >>> 24) & 0xFF, b = (hash >>> 16) & 0xFF, c = (hash >>> 8) & 0xFF, d = hash & 0xFF;
        int dec = 0xFF ^ a ^ b ^ c ^ d;
        return dec == encryptedVersion ? hash : 0;
    }

    private WzKey detectKey() {
        WzKey best = WzKey.GMS;
        int bestScore = -1;
        for (WzKey k : WzKey.ALL) {
            key = k;
            int score = 0;
            try {
                reader.seek(dataStart + 2);
                int count = reader.cint();
                for (int i = 0; i < Math.min(count, 8); i++) {
                    int t = reader.u8();
                    String nm;
                    if (t == 1) { reader.s32(); reader.s16(); reader.s32(); continue; }
                    if (t == 2) {
                        int so = reader.s32();
                        int back = reader.pos();
                        reader.seek(dataStart + so);
                        reader.u8();
                        nm = reader.wzString();
                        reader.seek(back);
                    } else if (t == 3 || t == 4) {
                        nm = reader.wzString();
                    } else break;
                    reader.cint();
                    reader.cint();
                    reader.s32();
                    score += readable(nm);
                }
            } catch (RuntimeException e) {
                // wrong key garbage; score stays low
            }
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    private static int readable(String s) {
        if (s.isEmpty()) return 0;
        int good = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_') good++;
            else return 0;
        }
        return good + (s.endsWith(".img") ? 10 : 0);
    }

    private void parseDirectory(WzNode dir, int depth) {
        if (depth > 16) throw new WzException("directory too deep");
        WzReader r = reader;
        int count = r.cint();
        if (count < 0 || count > 100000) throw new WzException("bad entry count " + count);
        List<WzNode> subdirs = new ArrayList<>();
        List<Integer> subdirOffsets = new ArrayList<>();
        int size = data.limit();
        for (int i = 0; i < count; i++) {
            int type = r.u8();
            String nm;
            int remember;
            switch (type) {
                case 1:
                    r.s32(); r.s16(); r.offset();
                    continue;
                case 2: {
                    int so = r.s32();
                    remember = r.pos();
                    r.seek(dataStart + so);
                    type = r.u8();
                    nm = r.wzString();
                    r.seek(remember);
                    break;
                }
                case 3: case 4:
                    nm = r.wzString();
                    break;
                default:
                    throw new WzException("bad directory entry type " + type + " at " + (r.pos() - 1));
            }
            int fsize = r.cint();
            r.cint(); // checksum
            int off = r.offset();
            if (off < 0 || off >= size) throw new WzException("entry " + nm + " offset out of range");
            if (type == 3) {
                WzNode sub = new WzNode(dir, nm, WzNode.Type.DIR);
                sub.file = this;
                dir.add(sub);
                subdirs.add(sub);
                subdirOffsets.add(off);
            } else if (type == 4) {
                WzNode img = new WzNode(dir, nm, WzNode.Type.IMG);
                img.file = this;
                img.offset = off;
                img.length = fsize;
                img.parsed = false;
                dir.add(img);
            } else {
                throw new WzException("bad entry type " + type);
            }
        }
        // Sanity-check one image header so a wrong version hash is caught here.
        if (dir.children != null) {
            for (WzNode n : dir.children.values()) {
                if (n.type == WzNode.Type.IMG) {
                    int b = data.get(n.offset) & 0xFF;
                    if (b != 0x73) throw new WzException("image " + n.name + " has a bad header");
                    break;
                }
            }
        }
        for (int i = 0; i < subdirs.size(); i++) {
            r.seek(subdirOffsets.get(i));
            parseDirectory(subdirs.get(i), depth + 1);
        }
    }

    // ---- .img parsing ----

    synchronized void parseImage(WzNode img) {
        WzReader r = new WzReader(this, data);
        int base = img.offset;
        r.seek(base);
        int b = r.u8();
        if (b != 0x73) throw new WzException(img.fullPath() + ": bad image header " + b);
        String kind = r.wzString();
        if (!kind.equals("Property")) throw new WzException(img.fullPath() + ": unexpected image kind '" + kind + "'");
        r.u16();
        parsePropertyList(r, img, base);
    }

    private String stringBlock(WzReader r, int base) {
        int t = r.u8();
        switch (t) {
            case 0: case 0x73: return r.wzString();
            case 1: case 0x1B: return r.wzStringAt(base + r.s32());
            default: throw new WzException("bad string block type " + t + " at " + (r.pos() - 1));
        }
    }

    private void parsePropertyList(WzReader r, WzNode parent, int base) {
        int count = r.cint();
        if (count < 0 || count > 1000000) throw new WzException(parent.fullPath() + ": bad property count " + count);
        for (int i = 0; i < count; i++) {
            String nm = stringBlock(r, base);
            int t = r.u8();
            WzNode n;
            switch (t) {
                case 0:
                    n = new WzNode(parent, nm, WzNode.Type.NULL);
                    break;
                case 2: case 11:
                    n = new WzNode(parent, nm, WzNode.Type.SHORT);
                    n.lvalue = r.s16();
                    break;
                case 3: case 19:
                    n = new WzNode(parent, nm, WzNode.Type.INT);
                    n.lvalue = r.cint();
                    break;
                case 20:
                    n = new WzNode(parent, nm, WzNode.Type.LONG);
                    n.lvalue = r.clong();
                    break;
                case 4: {
                    n = new WzNode(parent, nm, WzNode.Type.FLOAT);
                    int ft = r.u8();
                    n.dvalue = ft == 0x80 ? r.f32() : 0;
                    break;
                }
                case 5:
                    n = new WzNode(parent, nm, WzNode.Type.DOUBLE);
                    n.dvalue = r.f64();
                    break;
                case 8:
                    n = new WzNode(parent, nm, WzNode.Type.STRING);
                    n.svalue = stringBlock(r, base);
                    break;
                case 9: {
                    int size = r.s32();
                    int end = r.pos() + size;
                    n = parseExtended(r, parent, nm, base, end);
                    r.seek(end);
                    break;
                }
                default:
                    throw new WzException(parent.fullPath() + "/" + nm + ": unknown property type " + t);
            }
            n.file = this;
            parent.add(n);
        }
    }

    private WzNode parseExtended(WzReader r, WzNode parent, String nm, int base, int end) {
        String kind = stringBlock(r, base);
        switch (kind) {
            case "Property": {
                WzNode n = new WzNode(parent, nm, WzNode.Type.PROP);
                n.file = this;
                r.skip(2);
                parsePropertyList(r, n, base);
                return n;
            }
            case "Canvas": {
                WzNode n = new WzNode(parent, nm, WzNode.Type.CANVAS);
                n.file = this;
                r.skip(1);
                if (r.u8() == 1) {
                    r.skip(2);
                    parsePropertyList(r, n, base);
                }
                n.width = r.cint();
                n.height = r.cint();
                int f1 = r.cint();
                int f2 = r.u8();
                n.format = f1 + f2;
                r.skip(4);
                int len = r.s32();
                r.skip(1);
                n.offset = r.pos();
                n.length = len - 1;
                return n;
            }
            case "Shape2D#Vector2D": {
                WzNode n = new WzNode(parent, nm, WzNode.Type.VECTOR);
                n.x = r.cint();
                n.y = r.cint();
                return n;
            }
            case "Shape2D#Convex2D": {
                WzNode n = new WzNode(parent, nm, WzNode.Type.CONVEX);
                n.file = this;
                int count = r.cint();
                for (int i = 0; i < count; i++) {
                    WzNode c = parseExtended(r, n, Integer.toString(i), base, end);
                    c.file = this;
                    n.add(c);
                }
                return n;
            }
            case "Sound_DX8": {
                WzNode n = new WzNode(parent, nm, WzNode.Type.SOUND);
                r.skip(1);
                int dataLen = r.cint();
                n.lvalue = r.cint(); // length in ms
                int headerStart = r.pos();
                r.seek(headerStart + 51);
                int wavLen = r.u8();
                r.seek(headerStart + 51 + 1 + wavLen);
                n.offset = r.pos();
                n.length = dataLen;
                return n;
            }
            case "UOL": {
                WzNode n = new WzNode(parent, nm, WzNode.Type.UOL);
                r.skip(1);
                int t = r.u8();
                if (t == 0) n.svalue = r.wzString();
                else n.svalue = r.wzStringAt(base + r.s32());
                return n;
            }
            default:
                // Unknown object: keep an empty placeholder so parsing continues after it.
                return new WzNode(parent, nm, WzNode.Type.NULL);
        }
    }

    // ---- raw data ----

    byte[] readRaw(int offset, int length) {
        byte[] out = new byte[length];
        ByteBuffer d = data.duplicate();
        d.position(offset);
        d.get(out);
        return out;
    }

    private static boolean zlibHeader(int b0, int b1) {
        return b0 == 0x78 && (b1 == 0x9C || b1 == 0xDA || b1 == 0x01 || b1 == 0x5E);
    }

    byte[] inflateCanvas(WzNode c) {
        byte[] raw = readRaw(c.offset, c.length);
        byte[] zipped = raw;
        if (raw.length < 2 || !zlibHeader(raw[0] & 0xFF, raw[1] & 0xFF)) {
            // Encrypted variant: blocks of [int size][size bytes XOR key].
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
            int p = 0;
            while (p + 4 <= raw.length) {
                int size = (raw[p] & 0xFF) | (raw[p + 1] & 0xFF) << 8 | (raw[p + 2] & 0xFF) << 16 | (raw[p + 3] & 0xFF) << 24;
                p += 4;
                if (size < 0 || p + size > raw.length) break;
                for (int i = 0; i < size; i++) out.write((raw[p + i] ^ key.at(i)) & 0xFF);
                p += size;
            }
            zipped = out.toByteArray();
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(zipped);
            int expected = expectedSize(c);
            byte[] buf = new byte[Math.max(expected, 64)];
            int total = 0;
            while (!inf.finished()) {
                if (total == buf.length) {
                    byte[] nb = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, nb, 0, total);
                    buf = nb;
                }
                int n = inf.inflate(buf, total, buf.length - total);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                total += n;
            }
            if (total != buf.length) {
                byte[] nb = new byte[total];
                System.arraycopy(buf, 0, nb, 0, total);
                buf = nb;
            }
            return buf;
        } catch (DataFormatException e) {
            throw new WzException(c.fullPath() + ": corrupt image data", e);
        } finally {
            inf.end();
        }
    }

    private static int expectedSize(WzNode c) {
        int w = c.width, h = c.height;
        switch (c.format) {
            case 1: case 513: return w * h * 2;
            case 2: return w * h * 4;
            case 517: return w * h / 128;
            case 1026: case 2050: return w * h;
            default: return w * h * 4;
        }
    }

    byte[] decodeCanvas(WzNode c) {
        int w = c.width, h = c.height;
        byte[] px = inflateCanvas(c);
        byte[] out = new byte[w * h * 4];
        switch (c.format) {
            case 1: { // BGRA4444
                int n = Math.min(w * h, px.length / 2);
                for (int i = 0; i < n; i++) {
                    int lo = px[i * 2] & 0xFF, hi = px[i * 2 + 1] & 0xFF;
                    int b = lo & 0x0F, g = lo >>> 4, r = hi & 0x0F, a = hi >>> 4;
                    out[i * 4] = (byte) (r | r << 4);
                    out[i * 4 + 1] = (byte) (g | g << 4);
                    out[i * 4 + 2] = (byte) (b | b << 4);
                    out[i * 4 + 3] = (byte) (a | a << 4);
                }
                break;
            }
            case 2: { // BGRA8888
                int n = Math.min(w * h, px.length / 4);
                for (int i = 0; i < n; i++) {
                    out[i * 4] = px[i * 4 + 2];
                    out[i * 4 + 1] = px[i * 4 + 1];
                    out[i * 4 + 2] = px[i * 4];
                    out[i * 4 + 3] = px[i * 4 + 3];
                }
                break;
            }
            case 513: { // RGB565
                int n = Math.min(w * h, px.length / 2);
                for (int i = 0; i < n; i++) put565(out, i, (px[i * 2] & 0xFF) | (px[i * 2 + 1] & 0xFF) << 8);
                break;
            }
            case 517: { // RGB565, one colour per 16x16 block
                int bw = Math.max(1, w / 16);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int bi = (y / 16) * bw + (x / 16);
                        int v = bi * 2 + 1 < px.length ? (px[bi * 2] & 0xFF) | (px[bi * 2 + 1] & 0xFF) << 8 : 0;
                        put565(out, y * w + x, v);
                    }
                }
                break;
            }
            case 1026: dxt(px, out, w, h, false); break;
            case 2050: dxt(px, out, w, h, true); break;
            default:
                throw new WzException(c.fullPath() + ": unsupported pixel format " + c.format);
        }
        return out;
    }

    private static void put565(byte[] out, int i, int v) {
        int r = (v >>> 11) & 0x1F, g = (v >>> 5) & 0x3F, b = v & 0x1F;
        out[i * 4] = (byte) ((r << 3) | (r >>> 2));
        out[i * 4 + 1] = (byte) ((g << 2) | (g >>> 4));
        out[i * 4 + 2] = (byte) ((b << 3) | (b >>> 2));
        out[i * 4 + 3] = (byte) 0xFF;
    }

    /** DXT3 / DXT5 (not used by v83 but cheap to support). */
    private static void dxt(byte[] in, byte[] out, int w, int h, boolean dxt5) {
        int[] colors = new int[4];
        int[] alphas = new int[16];
        int p = 0;
        for (int by = 0; by < h; by += 4) {
            for (int bx = 0; bx < w; bx += 4) {
                if (p + 16 > in.length) return;
                if (dxt5) {
                    int a0 = in[p] & 0xFF, a1 = in[p + 1] & 0xFF;
                    long bits = 0;
                    for (int i = 0; i < 6; i++) bits |= (long) (in[p + 2 + i] & 0xFF) << (8 * i);
                    int[] pal = new int[8];
                    pal[0] = a0; pal[1] = a1;
                    if (a0 > a1) for (int i = 2; i < 8; i++) pal[i] = ((8 - i) * a0 + (i - 1) * a1) / 7;
                    else { for (int i = 2; i < 6; i++) pal[i] = ((6 - i) * a0 + (i - 1) * a1) / 5; pal[6] = 0; pal[7] = 255; }
                    for (int i = 0; i < 16; i++) alphas[i] = pal[(int) ((bits >>> (3 * i)) & 7)];
                } else {
                    for (int i = 0; i < 8; i++) {
                        int v = in[p + i] & 0xFF;
                        alphas[i * 2] = (v & 0xF) * 17;
                        alphas[i * 2 + 1] = (v >>> 4) * 17;
                    }
                }
                int c0 = (in[p + 8] & 0xFF) | (in[p + 9] & 0xFF) << 8;
                int c1 = (in[p + 10] & 0xFF) | (in[p + 11] & 0xFF) << 8;
                colors[0] = rgb565(c0);
                colors[1] = rgb565(c1);
                colors[2] = mix(colors[0], colors[1], 2, 1);
                colors[3] = mix(colors[0], colors[1], 1, 2);
                int idx = (in[p + 12] & 0xFF) | (in[p + 13] & 0xFF) << 8 | (in[p + 14] & 0xFF) << 16 | (in[p + 15] & 0xFF) << 24;
                for (int i = 0; i < 16; i++) {
                    int x = bx + (i & 3), y = by + (i >> 2);
                    if (x >= w || y >= h) continue;
                    int c = colors[(idx >>> (2 * i)) & 3];
                    int o = (y * w + x) * 4;
                    out[o] = (byte) (c >>> 16);
                    out[o + 1] = (byte) (c >>> 8);
                    out[o + 2] = (byte) c;
                    out[o + 3] = (byte) alphas[i];
                }
                p += 16;
            }
        }
    }

    private static int rgb565(int v) {
        int r = (v >>> 11) & 0x1F, g = (v >>> 5) & 0x3F, b = v & 0x1F;
        return ((r << 3) | (r >>> 2)) << 16 | ((g << 2) | (g >>> 4)) << 8 | ((b << 3) | (b >>> 2));
    }

    private static int mix(int a, int b, int wa, int wb) {
        int r = (((a >>> 16) & 0xFF) * wa + ((b >>> 16) & 0xFF) * wb) / (wa + wb);
        int g = (((a >>> 8) & 0xFF) * wa + ((b >>> 8) & 0xFF) * wb) / (wa + wb);
        int bl = ((a & 0xFF) * wa + (b & 0xFF) * wb) / (wa + wb);
        return r << 16 | g << 8 | bl;
    }
}
