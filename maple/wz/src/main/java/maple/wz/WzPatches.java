package maple.wz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additions made to images as they are read, e.g. offline-only items. The game and the in-process
 * server read the same parsed tree, so both see them. Keyed by the image's full path
 * ("Item/Consume/0200.img").
 */
public final class WzPatches {
    /** Adds to a freshly parsed image. */
    public interface Patch {
        void apply(WzNode img);
    }

    private static final Map<String, List<Patch>> PATCHES = new ConcurrentHashMap<>();
    /** New images that start as a copy of another in the same directory: dir -> {name, base name}. */
    private static final Map<String, List<String[]>> COPIES = new ConcurrentHashMap<>();

    private WzPatches() {}

    public static synchronized void register(String imagePath, Patch patch) {
        PATCHES.computeIfAbsent(imagePath, k -> new ArrayList<>()).add(patch);
    }

    /**
     * Adds an image that does not exist in the data, starting as a copy of another image in the same
     * directory (patches registered for the new path then change it): e.g. a new equip that borrows
     * another's look. Must be registered before the .wz file is opened.
     */
    public static synchronized void copyImage(String imagePath, String basePath) {
        int a = imagePath.lastIndexOf('/'), b = basePath.lastIndexOf('/');
        String dir = imagePath.substring(0, a);
        if (!dir.equals(basePath.substring(0, b))) throw new IllegalArgumentException("copies stay in one directory");
        COPIES.computeIfAbsent(dir, k -> new ArrayList<>()).add(new String[]{imagePath.substring(a + 1), basePath.substring(b + 1)});
    }

    static List<String[]> copiesIn(String dirPath) {
        return COPIES.get(dirPath);
    }

    static void apply(WzNode img) {
        if (PATCHES.isEmpty()) return;
        List<Patch> l = PATCHES.get(img.fullPath());
        if (l == null) return;
        for (Patch p : l) p.apply(img);
    }
}
