package maple.wz;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One node of a WZ tree: a directory, an .img, a sub-property or a leaf value.
 * .img nodes parse their contents the first time a child is asked for.
 */
public class WzNode {
    public enum Type { DIR, IMG, PROP, NULL, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, VECTOR, CANVAS, CONVEX, SOUND, UOL }

    public static final WzNode MISSING = new WzNode(null, "", Type.NULL);

    public final String name;
    public final WzNode parent;
    public final Type type;

    Map<String, WzNode> children;
    long lvalue;
    double dvalue;
    String svalue;
    int x, y;

    // IMG: offset of the image; CANVAS/SOUND: offset of the raw data.
    WzFile file;
    int offset;
    int length;
    int width, height, format;
    boolean parsed = true;

    WzNode(WzNode parent, String name, Type type) {
        this.parent = parent;
        this.name = name;
        this.type = type;
    }

    public boolean exists() { return this != MISSING; }

    void add(WzNode child) {
        if (children == null) children = new LinkedHashMap<>();
        children.put(child.name, child);
    }

    private void ensureParsed() {
        if (!parsed) {
            synchronized (this) {
                if (!parsed) {
                    file.parseImage(this);
                    parsed = true;
                }
            }
        }
    }

    public Collection<WzNode> children() {
        ensureParsed();
        return children == null ? Collections.<WzNode>emptyList() : children.values();
    }

    public int childCount() {
        ensureParsed();
        return children == null ? 0 : children.size();
    }

    /** Direct child (resolving links). Never null: returns {@link #MISSING}. */
    public WzNode get(String child) {
        if (this == MISSING) return MISSING;
        ensureParsed();
        WzNode n = children == null ? null : children.get(child);
        if (n == null) return MISSING;
        return n.resolve();
    }

    public WzNode get(int child) { return get(Integer.toString(child)); }

    /** Direct child without following links (null if missing). */
    public WzNode child(String name) {
        if (this == MISSING) return null;
        ensureParsed();
        return children == null ? null : children.get(name);
    }

    public long longValue() { return lvalue; }
    public double doubleValue() { return dvalue; }
    public String stringValue() { return svalue; }
    public boolean isFloat() { return type == Type.FLOAT; }

    /** Slash-separated path such as "info/bgm". ".." goes up. */
    public WzNode path(String path) {
        WzNode n = this;
        int start = 0;
        int len = path.length();
        while (start <= len && n != MISSING) {
            int slash = path.indexOf('/', start);
            if (slash < 0) slash = len;
            String part = path.substring(start, slash);
            if (part.equals("..")) n = n.parent == null ? MISSING : n.parent;
            else if (!part.isEmpty() && !part.equals(".")) n = n.get(part);
            start = slash + 1;
        }
        return n;
    }

    /** Follows UOL links ("../../front/head") to the node they point at. */
    public WzNode resolve() {
        WzNode n = this;
        for (int guard = 0; n.type == Type.UOL && guard < 16; guard++) {
            WzNode target = n.parent == null ? MISSING : n.parent.path(n.svalue);
            if (target == MISSING) return MISSING;
            n = target;
        }
        return n;
    }

    public String fullPath() {
        if (parent == null) return name;
        return parent.fullPath() + "/" + name;
    }

    // ---- values ----

    public int asInt(int def) {
        switch (type) {
            case SHORT: case INT: case LONG: return (int) lvalue;
            case FLOAT: case DOUBLE: return (int) dvalue;
            case STRING:
                try { return Integer.parseInt(svalue.trim()); } catch (NumberFormatException e) { return def; }
            default: return def;
        }
    }

    public double asDouble(double def) {
        switch (type) {
            case SHORT: case INT: case LONG: return lvalue;
            case FLOAT: case DOUBLE: return dvalue;
            case STRING:
                try { return Double.parseDouble(svalue.trim()); } catch (NumberFormatException e) { return def; }
            default: return def;
        }
    }

    public String asString(String def) {
        switch (type) {
            case STRING: return svalue;
            case SHORT: case INT: case LONG: return Long.toString(lvalue);
            case FLOAT: case DOUBLE: return Double.toString(dvalue);
            default: return def;
        }
    }

    public int getInt(String child, int def) { return get(child).asInt(def); }
    public double getDouble(String child, double def) { return get(child).asDouble(def); }
    public String getString(String child, String def) { return get(child).asString(def); }
    public boolean getBool(String child) { return get(child).asInt(0) != 0; }

    public boolean isVector() { return type == Type.VECTOR; }
    public int vx() { return type == Type.VECTOR ? x : 0; }
    public int vy() { return type == Type.VECTOR ? y : 0; }

    public boolean isCanvas() { return type == Type.CANVAS; }
    public int width() { return width; }
    public int height() { return height; }
    public int format() { return format; }

    /** Decoded RGBA8888 pixels of a canvas, row by row. */
    public byte[] rgba() {
        if (type != Type.CANVAS) throw new WzException(fullPath() + " is not a canvas");
        return file.decodeCanvas(this);
    }

    /** Raw bytes of a sound (usually MP3 for background music). */
    public byte[] soundData() {
        if (type != Type.SOUND) throw new WzException(fullPath() + " is not a sound");
        return file.readRaw(offset, length);
    }

    /** Stable key for caches. */
    public String key() {
        return (file == null ? "?" : file.name) + ":" + offset + ":" + fullPath();
    }

    @Override
    public String toString() {
        switch (type) {
            case SHORT: case INT: case LONG: return name + "=" + lvalue;
            case FLOAT: case DOUBLE: return name + "=" + dvalue;
            case STRING: return name + "=\"" + svalue + "\"";
            case VECTOR: return name + "=(" + x + "," + y + ")";
            case CANVAS: return name + " canvas " + width + "x" + height + " fmt" + format;
            case UOL: return name + " -> " + svalue;
            default: return name + " [" + type + "]";
        }
    }
}
