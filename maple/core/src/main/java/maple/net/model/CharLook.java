package maple.net.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a character wears (addCharLook): visible and hidden equips by slot, plus cash weapon and pets. */
public final class CharLook {
    public int gender, skin, face, hair;
    public boolean mega;
    public final Map<Integer, Integer> equips = new LinkedHashMap<>();
    public final Map<Integer, Integer> masked = new LinkedHashMap<>();
    public int cashWeapon;
    public final int[] pets = new int[3];
}
