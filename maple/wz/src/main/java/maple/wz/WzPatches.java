package maple.wz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additions made to images as they are read, e.g. offline-only items. The game and the in-process
 * server read the same parsed tree, so both see them. Keyed by the image's full path
 * ("Item.wz/Consume/0200.img").
 */
public final class WzPatches {
    /** Adds to a freshly parsed image. */
    public interface Patch {
        void apply(WzNode img);
    }

    private static final Map<String, List<Patch>> PATCHES = new ConcurrentHashMap<>();

    private WzPatches() {}

    public static synchronized void register(String imagePath, Patch patch) {
        PATCHES.computeIfAbsent(imagePath, k -> new ArrayList<>()).add(patch);
    }

    static void apply(WzNode img) {
        if (PATCHES.isEmpty()) return;
        List<Patch> l = PATCHES.get(img.fullPath());
        if (l == null) return;
        for (Patch p : l) p.apply(img);
    }
}
