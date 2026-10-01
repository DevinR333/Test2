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
