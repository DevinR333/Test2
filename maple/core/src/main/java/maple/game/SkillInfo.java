package maple.game;

import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/** A skill's data from Skill.wz (levels, actions, effects, icons) and String.wz (name, descriptions). */
public final class SkillInfo {
    public final int id;
    public final WzNode node;
    public final String name, desc;
    public final int maxLevel;
    public final boolean invisible;
    private final WzNode strings;

    private static Wz wz;
    private static final Map<Integer, SkillInfo> cache = new HashMap<>();

    public static void init(Wz w) {
        wz = w;
        cache.clear();
    }

    /** Null if the skill does not exist. */
    public static synchronized SkillInfo get(int id) {
        if (wz == null) return null;
        if (cache.containsKey(id)) return cache.get(id);
        WzNode n = wz.get("Skill/" + String.format("%03d", id / 10000) + ".img/skill/" + String.format("%07d", id));
        if (!n.exists()) n = wz.get("Skill/" + (id / 10000) + ".img/skill/" + id);
        SkillInfo s = n.exists() ? new SkillInfo(id, n) : null;
        cache.put(id, s);
        return s;
    }

    /** Skill ids of a book (job), in Skill.wz order, excluding the native-hidden 1014 / 10001015. */
    public static java.util.List<Integer> book(int bookId) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (wz == null) return out;
        WzNode n = wz.get("Skill/" + String.format("%03d", bookId) + ".img/skill");
        if (!n.exists()) n = wz.get("Skill/" + bookId + ".img/skill");
        for (WzNode c : n.children()) {
            try {
                int id = Integer.parseInt(c.name);
                if (id != 1014 && id != 10001015) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        out.sort(null);
        return out;
    }

    public static WzNode bookIcon(int bookId) {
        WzNode n = wz.get("Skill/" + String.format("%03d", bookId) + ".img/info/icon");
        return n.exists() ? n : wz.get("Skill/" + bookId + ".img/info/icon");
    }

    public static String bookName(int bookId) {
        return wz.get("String/Skill.img/" + String.format("%03d", bookId)).getString("bookName", "");
    }

    /** 004a8c4f: the beginner root book, then each ancestor book of the job. */
    public static java.util.List<Integer> books(int job) {
        java.util.List<Integer> b = new java.util.ArrayList<>();
        b.add(job / 100 == 22 || job == 2001 ? 2001 : (job / 1000) * 1000);
        if ((job % 1000) / 100 == 0) return b;
        int book = (job / 100) * 100;
        b.add(book);
        int branch = (job % 100) / 10;
        if (branch == 0) return b;
        book += branch * 10;
        b.add(book);
        for (int rank = 1; rank <= Math.min(job % 10, 8); rank++) b.add(++book);
        return b;
    }

    private SkillInfo(int id, WzNode node) {
        this.id = id;
        this.node = node;
        strings = wz.get("String/Skill.img/" + String.format("%07d", id));
        name = strings.getString("name", "");
        desc = strings.getString("desc", "");
        int n = 0;
        while (node.get("level").get(n + 1).exists()) n++;
        maxLevel = n;
        invisible = node.getInt("invisible", 0) != 0;
    }

    public WzNode level(int lv) {
        return node.get("level").get(Math.max(1, Math.min(maxLevel, lv)));
    }

    /** "h" + level description line (h1, h2...) or the generic "h". */
    public String levelText(int lv) {
        String h = strings.getString("h" + lv, "");
        if (h.isEmpty()) h = strings.getString("h", "");
        return h;
    }

    public WzNode icon() { return node.get("icon"); }
    public WzNode iconDisabled() { return node.get("iconDisabled"); }
    public WzNode iconMouseOver() { return node.get("iconMouseOver"); }

    /** The body action for this skill (action/0), or "" if it reuses the weapon's attack. */
    public String action() {
        return node.get("action").getString("0", "");
    }

    /** Passive skills have no action, no buff time and no attack. */
    public boolean passive() {
        WzNode l1 = level(1);
        return !node.get("action").exists() && !l1.get("time").exists() && !l1.get("damage").exists()
                && !l1.get("mobCount").exists() && !node.get("effect").exists();
    }

    public boolean attack() {
        WzNode l1 = level(1);
        return l1.get("damage").exists() || l1.get("mobCount").exists() || l1.get("attackCount").exists();
    }

    /** Whether this passive mastery skill applies to weapons of category cat ((id/10000)%100), 0 = any. */
    public boolean masteryFor(int cat) {
        if (!level(1).get("mastery").exists()) return false;
        int[] cats;
        switch (id) {
            case 1100000: case 1200000: case 11100000: cats = new int[]{30, 40}; break;
            case 1100001: cats = new int[]{31, 41}; break;
            case 1200001: cats = new int[]{32, 42}; break;
            case 1300000: cats = new int[]{43}; break;
            case 1300001: case 21100000: cats = new int[]{44}; break;
            case 3100000: case 13100000: cats = new int[]{45}; break;
            case 3200000: cats = new int[]{46}; break;
            case 4100000: case 14100000: cats = new int[]{47}; break;
            case 4200000: cats = new int[]{33}; break;
            case 5100001: case 15100001: cats = new int[]{48}; break;
            case 5200000: cats = new int[]{49}; break;
            default: return false;
        }
        if (cat == 0) return true;
        for (int c : cats) if (c == cat) return true;
        return false;
    }
}
