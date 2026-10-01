package maple.game;

import maple.net.model.Item;
import maple.net.model.PlayerData;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client knows about quests from Quest.wz (Check.img, QuestInfo.img, Say.img): who starts
 * and finishes each quest, whether the character may start one now or has done what it asks, the
 * marker over an NPC's head, and the conversation pages of quests without a server script.
 */
public final class QuestBook {
    private final World world;
    private Map<Integer, List<Integer>> startAt, endAt;

    QuestBook(World world) {
        this.world = world;
    }

    private void index() {
        if (startAt != null) return;
        startAt = new HashMap<>();
        endAt = new HashMap<>();
        for (WzNode q : world.wz.get("Quest/Check.img").children()) {
            int id;
            try {
                id = Integer.parseInt(q.name);
            } catch (NumberFormatException e) {
                continue;
            }
            int s = q.get("0").getInt("npc", 0), e = q.get("1").getInt("npc", 0);
            if (s != 0) startAt.computeIfAbsent(s, k -> new ArrayList<>()).add(id);
            if (e != 0) endAt.computeIfAbsent(e, k -> new ArrayList<>()).add(id);
        }
    }

    private WzNode check(int id, int stage) {
        return world.wz.get("Quest/Check.img/" + id + "/" + stage);
    }

    public String name(int id) {
        return world.wz.get("Quest/QuestInfo.img/" + id).getString("name", "Quest " + id);
    }

    /** 0 not started, 1 in progress, 2 completed. */
    public int state(int id) {
        PlayerData d = world.data();
        if (d == null) return 0;
        if (d.completedQuests.containsKey(id)) return 2;
        if (d.startedQuests.containsKey(id)) return 1;
        return 0;
    }

    /** Korean-only quests left in the v83 data never ran in GMS (and the font has no Korean). */
    public static boolean korean(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x1100 && c <= 0x11FF || c >= 0x3130 && c <= 0x318F || c >= 0xAC00 && c <= 0xD7A3) return true;
        }
        return false;
    }

    /** The start conditions hold (00a2a0be): level, job, earlier quests, dates, items. */
    public boolean canStart(int id) {
        PlayerData d = world.data();
        if (d == null || state(id) != 0) return false;
        if (korean(name(id))) return false;
        WzNode qi = world.wz.get("Quest/QuestInfo.img/" + id);
        if (qi.getInt("blocked", 0) != 0) return false;
        WzNode c = check(id, 0);
        if (!c.exists() || c.getInt("npc", 0) == 0) return false;
        if (c.getInt("normalAutoStart", 0) != 0) return false;
        int level = d.stats.level;
        if (c.get("lvmin").exists() && level < c.getInt("lvmin", 0)) return false;
        if (c.get("lvmax").exists() && level > c.getInt("lvmax", 999)) return false;
        WzNode jobs = c.get("job");
        if (jobs.exists() && jobs.childCount() > 0) {
            boolean ok = false;
            for (WzNode j : jobs.children()) if (j.asInt(-1) == d.stats.job) ok = true;
            if (!ok) return false;
        }
        for (WzNode q : c.get("quest").children()) {
            if (state(q.getInt("id", 0)) != q.getInt("state", 0)) return false;
        }
        String end = c.getString("end", "");
        if (!end.isEmpty() && !offline.OfflineOptions.holidays && pastDate(end)) return false;
        for (WzNode it : c.get("item").children()) {
            int need = it.getInt("count", 0);
            if (need > 0 && count(it.getInt("id", 0)) < need) return false;
        }
        return true;
    }

    private static boolean pastDate(String yyyymmddhh) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(Integer.parseInt(yyyymmddhh.substring(0, 4)), Integer.parseInt(yyyymmddhh.substring(4, 6)) - 1,
                    Integer.parseInt(yyyymmddhh.substring(6, 8)), Integer.parseInt(yyyymmddhh.substring(8, 10)), 0);
            return cal.getTimeInMillis() < System.currentTimeMillis();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Started, and what it asks for is done (monsters hunted, items in the bag, earlier quests). */
    public boolean ready(int id) {
        PlayerData d = world.data();
        if (d == null || state(id) != 1) return false;
        WzNode c = check(id, 1);
        String progress = d.startedQuests.getOrDefault(id, "");
        int index = 0;
        for (WzNode mob : c.get("mob").children()) {
            int need = mob.getInt("count", 0), have = 0;
            if (progress.length() >= (index + 1) * 3) {
                try {
                    have = Integer.parseInt(progress.substring(index * 3, index * 3 + 3));
                } catch (NumberFormatException ignored) {
                }
            }
            if (have < need) return false;
            index++;
        }
        for (WzNode it : c.get("item").children()) {
            int need = it.getInt("count", 0);
            if (need > 0 && count(it.getInt("id", 0)) < need) return false;
        }
        for (WzNode q : c.get("quest").children()) {
            if (state(q.getInt("id", 0)) != q.getInt("state", 0)) return false;
        }
        if (c.get("lvmin").exists() && d.stats.level < c.getInt("lvmin", 0)) return false;
        return true;
    }

    private int count(int itemId) {
        PlayerData d = world.data();
        int n = 0;
        for (Item it : d.inventory(ItemInfo.inventoryType(itemId)).values()) if (it.itemId == itemId) n += it.quantity;
        return n;
    }

    /** Quests this NPC can start now. */
    public List<Integer> startable(int npcId) {
        index();
        List<Integer> out = new ArrayList<>();
        for (int id : startAt.getOrDefault(npcId, java.util.Collections.emptyList())) if (canStart(id)) out.add(id);
        return out;
    }

    /** Quests in progress that this NPC finishes (ready or not). */
    public List<Integer> finishing(int npcId) {
        index();
        List<Integer> out = new ArrayList<>();
        for (int id : endAt.getOrDefault(npcId, java.util.Collections.emptyList())) if (state(id) == 1) out.add(id);
        return out;
    }

    /**
     * The marker over the NPC (UIWindow.img/QuestIcon/N): 2 a quest is ready to complete, 0 one can be
     * started, 1 one is in progress; -1 none. Ready beats available beats in progress.
     */
    public int marker(int npcId) {
        long now = System.currentTimeMillis();
        if (now - markersAt > 500) { // quest, level and bag changes show within half a second
            markers.clear();
            markersAt = now;
        }
        return markers.computeIfAbsent(npcId, this::computeMarker);
    }

    private final Map<Integer, Integer> markers = new HashMap<>();
    private long markersAt;

    private int computeMarker(int npcId) {
        int state = -1;
        for (int id : finishing(npcId)) {
            if (ready(id)) return 2;
            state = 1;
        }
        if (!startable(npcId).isEmpty()) state = 0;
        return state;
    }

    public boolean scriptedStart(int id) {
        return !check(id, 0).getString("startscript", "").isEmpty();
    }

    public boolean scriptedEnd(int id) {
        return !check(id, 1).getString("endscript", "").isEmpty();
    }

    /** Say.img pages: stage 0 start / 1 end, part "" (main pages), "yes", "no" or "stop". */
    public List<String> say(int id, int stage, String part) {
        WzNode n = world.wz.get("Quest/Say.img/" + id + "/" + stage);
        if (!part.isEmpty()) n = n.get(part);
        if (part.equals("stop")) {
            // the first reason with text (item, mob, npc, quest, ...)
            for (WzNode reason : n.children()) {
                List<String> l = pages(reason);
                if (!l.isEmpty()) return l;
            }
            return new ArrayList<>();
        }
        return pages(n);
    }

    private static List<String> pages(WzNode n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            WzNode p = n.get(Integer.toString(i));
            if (!p.exists()) break;
            String s = p.asString("");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Dialogue has an accept question at the end (it has yes/no answers). */
    public boolean asks(int id) {
        WzNode n = world.wz.get("Quest/Say.img/" + id + "/0");
        return n.get("yes").exists() || n.get("no").exists() || n.getInt("ask", 0) != 0;
    }
}
