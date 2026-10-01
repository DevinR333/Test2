package offline;

import maple.wz.WzNode;
import maple.wz.WzPatches;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Items the offline game adds to the data as it is read (game and server see the same thing): the
 * Level Up Potion, and extra Cash Shop entries (Mark of the Beta, the Maple Bandanas, the potion).
 * Also decides which of the Cash Shop's retired (seasonal, limited) entries are offered.
 */
public final class OfflineItems {
    /** Use: grants one level. Free in every shop and in the Cash Shop. */
    public static final int LEVEL_POTION = 2002031;
    /** Its picture is borrowed from this item. */
    private static final int LEVEL_POTION_LOOK = 2002028;

    /** Extra Cash Shop entries: SN, item, count, price. SN encodes the tab (cat = SN/1e7, sub = SN/1e5 % 100). */
    private static final int[][] EXTRA_COMMODITIES = {
            {20099901, 1002419, 1, 0}, // Mark of the Beta (Equip > Hat)
            {20099902, 1002515, 1, 0}, // Maple Bandana White
            {20099903, 1002516, 1, 0}, // Maple Bandana Yellow
            {20099904, 1002517, 1, 0}, // Maple Bandana Red
            {20099905, 1002518, 1, 0}, // Maple Bandana Blue
            {30099901, LEVEL_POTION, 1, 0}, // Use > Scroll
    };

    /**
     * Holiday monsters that only ever came from timed events, added as extra spawns on a map of their
     * level (copying that map's spawn points): mob, map, how many, the map's own monster whose drops
     * they share (they have none of their own).
     */
    private static final int[][] HOLIDAY_SPAWNS = {
            {9400505, 100040100, 8, 210100},   // Turkey (lv 6): The Forest of Wisdom
            {9500195, 104040001, 6, 1210101},  // Jack-o-Lantern (lv 10): Henesys Hunting Ground II
            {9400568, 106000100, 8, 1140100},  // Turkey Commando (lv 20): Deep Valley II
            {9400508, 105040000, 8, 2230100},  // Mad Turkey (lv 27): Swampy Land in a Deep Forest
            {9420508, 106000130, 6, 4230400},  // Octobunny (lv 43): The Burnt Land IV
            {9500317, 209080000, 3, 210100},   // Kid Snowman: Happyville, Extra Frosty Snow Zone
            {9500318, 209080000, 3, 4230103},  // Angry Snowman (lv 40): same
    };

    /**
     * What an NPC shop pays for one: its own price, or for cash items (which have none) 10 mesos per
     * NX of their Cash Shop price, at least 1,000.
     */
    public static int sellPrice(int itemId, int ownPrice) {
        if (ownPrice > 0) return ownPrice;
        if (!server.ItemInformationProvider.getInstance().isCash(itemId)) return ownPrice;
        return Math.max(1000, server.CashShop.CashItemFactory.lowestPrice(itemId) * 10);
    }

    /** The monster whose drops a holiday monster uses, or 0. */
    public static int dropTemplate(int mobId) {
        for (int[] s : HOLIDAY_SPAWNS) if (s[0] == mobId) return s[3];
        return 0;
    }

    private static boolean installed;

    private OfflineItems() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        WzPatches.register("Item.wz/Consume/0200.img", img -> {
            String id = "0" + LEVEL_POTION;
            if (img.child(id) != null) return;
            WzNode look = img.get("0" + LEVEL_POTION_LOOK).get("info");
            WzNode item = img.addProp(id);
            WzNode info = item.addProp("info");
            info.addExisting(look.child("icon")).addExisting(look.child("iconRaw"));
            info.addInt("price", 0).addInt("slotMax", 100);
            item.addProp("spec");
        });
        WzPatches.register("String.wz/Consume.img", img -> {
            String id = Integer.toString(LEVEL_POTION);
            if (img.child(id) != null) return;
            img.addProp(id).addString("name", "Level Up Potion")
                    .addString("desc", "A mysterious potion that instantly raises your level by 1.");
        });
        for (int[] s : HOLIDAY_SPAWNS) {
            int mob = s[0], count = s[2];
            WzPatches.register("Map.wz/Map/Map" + s[1] / 100000000 + "/" + s[1] + ".img", img -> {
                if (!OfflineOptions.holidays) return;
                WzNode life = img.get("life");
                if (!life.exists()) return;
                java.util.List<WzNode> spots = new java.util.ArrayList<>();
                for (WzNode e : life.children()) if ("m".equals(e.getString("type", ""))) spots.add(e);
                if (spots.isEmpty()) return;
                int name = 10000 + life.childCount();
                for (int i = 0; i < count; i++) {
                    WzNode at = spots.get(i * spots.size() / count);
                    WzNode n = life.addProp(Integer.toString(name++));
                    n.addString("type", "m").addString("id", Integer.toString(mob));
                    for (String k : new String[]{"x", "y", "fh", "cy", "rx0", "rx1", "f"}) n.addInt(k, at.getInt(k, 0));
                    n.addInt("mobTime", at.getInt("mobTime", 0));
                }
            });
        }
        WzPatches.register("Etc.wz/Commodity.img", img -> {
            int next = 1_000_000;
            for (int[] c : EXTRA_COMMODITIES) {
                img.addProp(Integer.toString(next++)).addInt("SN", c[0]).addInt("ItemId", c[1]).addInt("Count", c[2])
                        .addInt("Price", c[3]).addInt("Period", 0).addInt("Priority", 9).addInt("Gender", 2)
                        .addInt("OnSale", 1);
            }
        });
    }

    /**
     * The retired entries worth offering: one per item that is not on sale anywhere else, preferring
     * its own tab over the New/Event lists. entries: {sn, itemId, onSale(0/1)}.
     */
    public static Set<Integer> limitedOffers(Iterable<int[]> entries) {
        Set<Integer> onSale = new HashSet<>();
        Map<Integer, Integer> best = new HashMap<>();
        for (int[] e : entries) {
            if (e[2] != 0) {
                onSale.add(e[1]);
                continue;
            }
            if (e[1] == 0) continue;
            Integer cur = best.get(e[1]);
            if (cur == null || better(e[0], cur)) best.put(e[1], e[0]);
        }
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : best.entrySet()) if (!onSale.contains(e.getKey())) out.add(e.getValue());
        return out;
    }

    private static boolean better(int sn, int than) {
        boolean ownTab = sn / 10000000 != 1, thanOwnTab = than / 10000000 != 1;
        if (ownTab != thanOwnTab) return ownTab;
        return sn < than;
    }
}
