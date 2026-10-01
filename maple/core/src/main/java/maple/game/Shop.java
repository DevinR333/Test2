package maple.game;

import java.util.ArrayList;
import java.util.List;

/** An NPC shop's item list (OPEN_NPC_SHOP). */
public final class Shop {
    public int npcId;
    public final List<Entry> items = new ArrayList<>();

    public static final class Entry {
        public int itemId, price, pitch;
        public int quantity = 1, buyable = 1;
        public boolean recharge;
        public double unitPrice;
        public int slotMax;
    }
}
