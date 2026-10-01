package maple.game;

import java.util.ArrayList;
import java.util.List;

/** What the Cash Shop session knows: balances, the cash inventory (locker) and the wish list. */
public final class CashShopState {
    public static final class Entry {
        public long cashId;
        public int itemId, sn, quantity;
        public String giftFrom = "";
        public long expiration;
    }

    public int nxCredit, maplePoints, nxPrepaid;
    public final List<Entry> locker = new ArrayList<>();
    public final int[] wishlist = new int[10];
    public final List<Entry> gifts = new ArrayList<>();
    /** Last result message to show (filled by the packet handler, taken by the screen). */
    public String message;
}
