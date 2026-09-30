package maple.net.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything the server sends about your character when you log in (SET_FIELD character info). */
public final class PlayerData {
    public int channel;
    public final CharStats stats = new CharStats();
    public int buddyCapacity;
    public String linkedName;
    public int meso;
    public final int[] slotLimits = new int[6]; // by inventory type 1..5
    /** Inventories by type: -1 equipped, 1 equip, 2 use, 3 setup, 4 etc, 5 cash. Key = slot. */
    public final Map<Integer, TreeMap<Integer, Item>> inventories = new LinkedHashMap<>();
    public final Map<Integer, int[]> skills = new LinkedHashMap<>(); // id -> {level, masterLevel}
    public final Map<Integer, Integer> cooldowns = new LinkedHashMap<>();
    public final Map<Integer, String> startedQuests = new LinkedHashMap<>();
    public final Map<Integer, Long> completedQuests = new LinkedHashMap<>();
    public final int[] teleportMaps = new int[5];
    public final int[] vipTeleportMaps = new int[10];
    public int monsterBookCover;
    public final Map<Integer, Integer> monsterCards = new LinkedHashMap<>();
    public final Map<Integer, String> areaInfo = new LinkedHashMap<>();
    public final List<Integer> ringIds = new ArrayList<>();

    public TreeMap<Integer, Item> inventory(int type) {
        return inventories.computeIfAbsent(type, k -> new TreeMap<>());
    }
}
