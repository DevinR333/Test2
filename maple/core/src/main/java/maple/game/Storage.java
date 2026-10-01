package maple.game;

import maple.net.model.Item;

import java.util.ArrayList;
import java.util.List;

/** The storage (Trunk) contents. */
public final class Storage {
    public int npcId, slots, meso;
    public final List<Item> items = new ArrayList<>();
}
