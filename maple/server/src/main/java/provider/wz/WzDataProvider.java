package provider.wz;

import maple.wz.WzFile;
import maple.wz.WzNode;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataProvider;

/** DataProvider over one .wz archive, read directly (no XML export needed). */
public final class WzDataProvider implements DataProvider {
    private final WzFile file;
    private WZDirectoryEntry root;

    public WzDataProvider(WzFile file) {
        this.file = file;
    }

    @Override
    public Data getData(String path) {
        if (file == null) return null;
        WzNode n = file.root;
        for (String s : path.split("/")) {
            if (s.isEmpty()) continue;
            n = n.child(s);
            if (n == null) return null;
        }
        return new WzData(n, n.type == WzNode.Type.IMG);
    }

    @Override
    public synchronized DataDirectoryEntry getRoot() {
        if (root == null) {
            root = new WZDirectoryEntry(file == null ? "" : file.name, 0, 0, null);
            if (file != null) fill(file.root, root);
        }
        return root;
    }

    private static void fill(WzNode dir, WZDirectoryEntry entry) {
        for (WzNode c : dir.children()) {
            if (c.type == WzNode.Type.DIR) {
                WZDirectoryEntry sub = new WZDirectoryEntry(c.name, 0, 0, entry);
                entry.addDirectory(sub);
                fill(c, sub);
            } else if (c.type == WzNode.Type.IMG) {
                entry.addFile(new WZFileEntry(c.name, 0, 0, entry));
            }
        }
    }
}
