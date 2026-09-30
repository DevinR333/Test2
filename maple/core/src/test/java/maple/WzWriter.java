package maple;

import maple.wz.WzKey;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Writes small GMS-style v83 .wz files for tests (synthetic content only).
 * Mirrors the reader's format so the reader, map loader and renderer can be exercised without game data.
 */
public final class WzWriter {
    // ---- tree model ----
    public static class Dir {
        final Map<String, Object> entries = new LinkedHashMap<>(); // Dir or Prop (img)
        public Dir dir(String name) { return (Dir) entries.computeIfAbsent(name, k -> new Dir()); }
        public Prop img(String name) { return (Prop) entries.computeIfAbsent(name, k -> new Prop()); }
    }

    public static class Prop {
        final Map<String, Object> values = new LinkedHashMap<>();
        public Prop sub(String name) { return (Prop) values.computeIfAbsent(name, k -> new Prop()); }
        public Prop sub(int name) { return sub(Integer.toString(name)); }
        public Prop set(String name, Object v) { values.put(name, v); return this; }
        public Canvas canvas(String name, int w, int h, int argb, int ox, int oy) {
            Canvas c = new Canvas(w, h, argb);
            c.props.set("origin", new Vec(ox, oy));
            values.put(name, c);
            return c;
        }
    }

    public static class Vec { final int x, y; public Vec(int x, int y) { this.x = x; this.y = y; } }
    public static class Uol { final String path; public Uol(String p) { path = p; } }

    public static class Canvas {
        final int w, h;
        final int argb;
        public final Prop props = new Prop();
        Canvas(int w, int h, int argb) { this.w = w; this.h = h; this.argb = argb; }
    }

    public static class Sound { final byte[] data; public Sound(byte[] d) { data = d; } }

    // ---- writer ----
    private final WzKey key = WzKey.GMS;
    private static final int VERSION = 83;
    private final int dataStart = 60;
    private int hash;

    public void write(Dir root, File out) throws IOException {
        hash = 0;
        String vs = Integer.toString(VERSION);
        for (int i = 0; i < vs.length(); i++) hash = 32 * hash + vs.charAt(i) + 1;
        int enc = 0xFF ^ ((hash >>> 24) & 0xFF) ^ ((hash >>> 16) & 0xFF) ^ ((hash >>> 8) & 0xFF) ^ (hash & 0xFF);

        // Layout: header | version | directories... | images...
        // Sizes of directory blocks don't depend on offsets (offsets are fixed 4 bytes), so do two passes.
        List<Dir> dirs = new ArrayList<>();
        collect(root, dirs);
        Map<Dir, Integer> dirOffset = new LinkedHashMap<>();
        Map<Prop, byte[]> imgBytes = new LinkedHashMap<>();
        Map<Prop, Integer> imgOffset = new LinkedHashMap<>();
        for (Dir d : dirs)
            for (Object o : d.entries.values())
                if (o instanceof Prop) imgBytes.put((Prop) o, image((Prop) o));

        int pos = dataStart + 2;
        for (Dir d : dirs) {
            dirOffset.put(d, pos);
            pos += dirBlock(d, pos, dirOffset, imgOffset, true).length;
        }
        for (Map.Entry<Prop, byte[]> e : imgBytes.entrySet()) {
            imgOffset.put(e.getKey(), pos);
            pos += e.getValue().length;
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("PKG1".getBytes(StandardCharsets.ISO_8859_1));
        le64(file, pos - dataStart);
        le32(file, dataStart);
        byte[] copyright = "Package file v1.0 Copyright 2002 Wizet, ZMS".getBytes(StandardCharsets.ISO_8859_1);
        file.write(copyright);
        file.write(0);
        while (file.size() < dataStart) file.write(0);
        le16(file, enc);
        for (Dir d : dirs) file.write(dirBlock(d, file.size(), dirOffset, imgOffset, false));
        for (byte[] b : imgBytes.values()) file.write(b);
        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(file.toByteArray());
        }
    }

    private void collect(Dir d, List<Dir> out) {
        out.add(d);
        for (Object o : d.entries.values()) if (o instanceof Dir) collect((Dir) o, out);
    }

    private byte[] dirBlock(Dir d, int blockPos, Map<Dir, Integer> dirOff, Map<Prop, Integer> imgOff, boolean sizing) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        cint(b, d.entries.size());
        for (Map.Entry<String, Object> e : d.entries.entrySet()) {
            boolean isDir = e.getValue() instanceof Dir;
            b.write(isDir ? 3 : 4);
            wzString(b, e.getKey());
            cint(b, 100);
            cint(b, 0);
            Integer target = isDir ? dirOff.get(e.getValue()) : imgOff.get(e.getValue());
            int t = sizing || target == null ? 0 : target;
            int at = blockPos + b.size();
            le32(b, encOffset(at, t));
        }
        return b.toByteArray();
    }

    private int encOffset(int at, int target) {
        long start = dataStart;
        long off = ((at - start) ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
        off = (off * (hash & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        off = (off - 0x581C3F6DL) & 0xFFFFFFFFL;
        int o = Integer.rotateLeft((int) off, (int) (off & 0x1F));
        return o ^ (int) (target - start * 2);
    }

    private byte[] image(Prop p) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x73);
        wzString(b, "Property");
        le16(b, 0);
        propList(b, p);
        return b.toByteArray();
    }

    private void propList(ByteArrayOutputStream b, Prop p) throws IOException {
        cint(b, p.values.size());
        for (Map.Entry<String, Object> e : p.values.entrySet()) {
            b.write(0);
            wzString(b, e.getKey());
            Object v = e.getValue();
            if (v == null) { b.write(0); }
            else if (v instanceof Integer) { b.write(3); cint(b, (Integer) v); }
            else if (v instanceof Short) { b.write(2); le16(b, (Short) v); }
            else if (v instanceof Float) { b.write(4); b.write(0x80); le32(b, Float.floatToIntBits((Float) v)); }
            else if (v instanceof Double) { b.write(5); le64(b, Double.doubleToLongBits((Double) v)); }
            else if (v instanceof String) { b.write(8); b.write(0); wzString(b, (String) v); }
            else {
                b.write(9);
                ByteArrayOutputStream ex = new ByteArrayOutputStream();
                extended(ex, v);
                le32(b, ex.size());
                b.write(ex.toByteArray());
            }
        }
    }

    private void extended(ByteArrayOutputStream b, Object v) throws IOException {
        b.write(0x73);
        if (v instanceof Prop) {
            wzString(b, "Property");
            le16(b, 0);
            propList(b, (Prop) v);
        } else if (v instanceof Vec) {
            wzString(b, "Shape2D#Vector2D");
            cint(b, ((Vec) v).x);
            cint(b, ((Vec) v).y);
        } else if (v instanceof Uol) {
            wzString(b, "UOL");
            b.write(0);
            b.write(0);
            wzString(b, ((Uol) v).path);
        } else if (v instanceof Canvas) {
            Canvas c = (Canvas) v;
            wzString(b, "Canvas");
            b.write(0);
            if (c.props.values.isEmpty()) b.write(0);
            else { b.write(1); le16(b, 0); propList(b, c.props); }
            cint(b, c.w);
            cint(b, c.h);
            cint(b, 2); // BGRA8888
            b.write(0);
            le32(b, 0);
            byte[] px = new byte[c.w * c.h * 4];
            for (int i = 0; i < c.w * c.h; i++) {
                int x = i % c.w, y = i / c.w;
                boolean border = x == 0 || y == 0 || x == c.w - 1 || y == c.h - 1;
                int col = border ? (c.argb & 0xFF000000) | ((c.argb & 0xFEFEFE) >> 1) : c.argb;
                px[i * 4] = (byte) col;
                px[i * 4 + 1] = (byte) (col >> 8);
                px[i * 4 + 2] = (byte) (col >> 16);
                px[i * 4 + 3] = (byte) (col >>> 24);
            }
            Deflater d = new Deflater();
            d.setInput(px);
            d.finish();
            byte[] z = new byte[px.length + 1024];
            int n = d.deflate(z);
            d.end();
            le32(b, n + 1);
            b.write(0);
            b.write(z, 0, n);
        } else if (v instanceof Sound) {
            byte[] data = ((Sound) v).data;
            wzString(b, "Sound_DX8");
            b.write(0);
            cint(b, data.length);
            cint(b, 1000);
            b.write(new byte[51]);
            b.write(0); // wav format length
            b.write(data);
        } else {
            throw new IllegalArgumentException("unsupported value " + v);
        }
    }

    private void wzString(ByteArrayOutputStream b, String s) {
        boolean ascii = true;
        for (char c : s.toCharArray()) if (c > 0x7F) ascii = false;
        if (s.isEmpty()) { b.write(0); return; }
        if (ascii) {
            if (s.length() >= 128) { b.write(-128); le32(b, s.length()); }
            else b.write(-s.length());
            int mask = 0xAA;
            for (int i = 0; i < s.length(); i++) {
                b.write((s.charAt(i) ^ mask ^ (key.at(i) & 0xFF)) & 0xFF);
                mask++;
            }
        } else {
            if (s.length() >= 127) { b.write(127); le32(b, s.length()); }
            else b.write(s.length());
            int mask = 0xAAAA;
            for (int i = 0; i < s.length(); i++) {
                int k = ((key.at(i * 2 + 1) & 0xFF) << 8) | (key.at(i * 2) & 0xFF);
                le16(b, (s.charAt(i) ^ mask ^ k) & 0xFFFF);
                mask++;
            }
        }
    }

    private static void cint(ByteArrayOutputStream b, int v) {
        if (v > 127 || v <= -128) { b.write(-128); le32(b, v); }
        else b.write(v);
    }

    private static void le16(ByteArrayOutputStream b, int v) { b.write(v); b.write(v >> 8); }
    private static void le32(ByteArrayOutputStream b, int v) { b.write(v); b.write(v >> 8); b.write(v >> 16); b.write(v >> 24); }
    private static void le64(ByteArrayOutputStream b, long v) { le32(b, (int) v); le32(b, (int) (v >>> 32)); }
}
