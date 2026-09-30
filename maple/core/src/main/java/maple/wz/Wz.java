package maple.wz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** All the game's .wz archives, addressed by paths like "Map/Map/Map1/100000000.img/info". */
public final class Wz {
    /** Where the .wz files come from: a folder on PC, the APK's assets on Android. */
    public interface Source {
        /** Memory-map (or load) the named file, e.g. "Map.wz". Returns null if it does not exist. */
        ByteBuffer open(String fileName) throws IOException;
        String describe();
    }

    public static final String[] FILES = {
            "Base", "Character", "Effect", "Etc", "Item", "Map", "Mob", "Morph", "Npc",
            "Quest", "Reactor", "Skill", "Sound", "String", "TamingMob", "UI"};

    private final Source source;
    private final Map<String, WzFile> files = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();

    public Wz(Source source) {
        this.source = source;
    }

    public Source source() { return source; }

    /** The archive for "Map" (loaded on first use). Throws with a readable message if missing or broken. */
    public synchronized WzFile file(String base) {
        WzFile f = files.get(base);
        if (f != null) return f;
        if (errors.containsKey(base)) throw new WzException(errors.get(base));
        try {
            ByteBuffer buf = source.open(base + ".wz");
            if (buf == null) throw new WzException(base + ".wz not found in " + source.describe());
            f = new WzFile(base + ".wz", buf);
            files.put(base, f);
            return f;
        } catch (IOException e) {
            String msg = base + ".wz could not be opened: " + e.getMessage();
            errors.put(base, msg);
            throw new WzException(msg, e);
        } catch (WzException e) {
            errors.put(base, e.getMessage());
            throw e;
        }
    }

    public boolean has(String base) {
        try {
            file(base);
            return true;
        } catch (WzException e) {
            return false;
        }
    }

    /** "Map/Back/grassySoil.img/back/0" → node, or {@link WzNode#MISSING}. */
    public WzNode get(String path) {
        int slash = path.indexOf('/');
        String base = slash < 0 ? path : path.substring(0, slash);
        WzFile f;
        try {
            f = file(base);
        } catch (WzException e) {
            return WzNode.MISSING;
        }
        return slash < 0 ? f.root : f.root.path(path.substring(slash + 1));
    }
}
