package provider.wz;

import compat.awt.Point;
import maple.wz.WzNode;
import provider.Data;
import provider.DataEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Server view of a node in the player's .wz files. Behaves like the XML export the server was
 * written against: links are not followed, numbers keep their short/int/float/double types.
 */
public final class WzData implements Data {
    final WzNode node;
    private final boolean imageRoot;

    WzData(WzNode node, boolean imageRoot) {
        this.node = node;
        this.imageRoot = imageRoot;
    }

    @Override
    public String getName() {
        return node.name;
    }

    @Override
    public DataType getType() {
        switch (node.type) {
            case IMG: case PROP: case DIR: return DataType.PROPERTY;
            case CANVAS: return DataType.CANVAS;
            case CONVEX: return DataType.CONVEX;
            case SOUND: return DataType.SOUND;
            case UOL: return DataType.UOL;
            case DOUBLE: return DataType.DOUBLE;
            case FLOAT: return DataType.FLOAT;
            case INT: case LONG: return DataType.INT;
            case SHORT: return DataType.SHORT;
            case STRING: return DataType.STRING;
            case VECTOR: return DataType.VECTOR;
            case NULL: return DataType.IMG_0x00;
            default: return DataType.UNKNOWN_TYPE;
        }
    }

    @Override
    public List<Data> getChildren() {
        Collection<WzNode> kids = node.children();
        List<Data> out = new ArrayList<>(kids.size());
        for (WzNode k : kids) out.add(new WzData(k, false));
        return out;
    }

    @Override
    public Data getChildByPath(String path) {
        String[] segments = path.split("/");
        if (segments[0].equals("..")) {
            Data parent = (Data) getParent();
            return parent == null ? null : parent.getChildByPath(path.substring(path.indexOf('/') + 1));
        }
        WzNode n = node;
        for (String s : segments) {
            if (s.isEmpty()) continue;
            n = n.child(s);
            if (n == null) return null;
        }
        return new WzData(n, false);
    }

    @Override
    public Object getData() {
        switch (node.type) {
            case DOUBLE: return node.doubleValue();
            case FLOAT: return (float) node.doubleValue();
            case INT: case LONG: return (int) node.longValue();
            case SHORT: return (short) node.longValue();
            case STRING: case UOL: return node.stringValue();
            case VECTOR: return new Point(node.vx(), node.vy());
            default: return null;
        }
    }

    @Override
    public DataEntity getParent() {
        if (imageRoot || node.parent == null) return null;
        WzNode p = node.parent;
        return new WzData(p, p.type == WzNode.Type.IMG);
    }

    @Override
    public Iterator<Data> iterator() {
        return getChildren().iterator();
    }

    @Override
    public String toString() {
        return node.fullPath();
    }
}
