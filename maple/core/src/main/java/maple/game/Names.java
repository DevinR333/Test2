package maple.game;

import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/** Names from String.wz: NPCs, monsters, maps, quests. */
public final class Names {
    private static Wz wz;
    private static final Map<Integer, String> maps = new HashMap<>();

    private Names() {}

    public static void init(Wz w) {
        wz = w;
        maps.clear();
    }

    public static String npc(int id) {
        return wz == null ? "" : wz.get("String/Npc.img/" + id).getString("name", "");
    }

    public static String mob(int id) {
        return wz == null ? "" : wz.get("String/Mob.img/" + id).getString("name", "");
    }

    /** String/Map.img/<region>/<id>: the map's region holder is searched once and cached. */
    public static synchronized String map(int id) {
        if (wz == null) return "";
        String cached = maps.get(id);
        if (cached != null) return cached;
        String name = "";
        for (WzNode region : wz.get("String/Map.img").children()) {
            WzNode m = region.get(Integer.toString(id));
            if (m.exists()) {
                name = m.getString("mapName", "");
                break;
            }
        }
        maps.put(id, name);
        return name;
    }

    public static String street(int id) {
        if (wz == null) return "";
        for (WzNode region : wz.get("String/Map.img").children()) {
            WzNode m = region.get(Integer.toString(id));
            if (m.exists()) return m.getString("streetName", "");
        }
        return "";
    }

    public static String quest(int id) {
        return wz == null ? "" : wz.get("Quest/QuestInfo.img/" + id).getString("name", "");
    }
}
