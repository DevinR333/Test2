package maple.net.model;

/** An item as the client knows it (addItemInfo). Equip stats are only filled for equips. */
public final class Item {
    public int position;
    public int type; // 1 equip, 2 item, 3 pet
    public int itemId;
    public boolean cash;
    public long cashId;
    public long expiration;
    public int quantity = 1;
    public String owner = "";
    public int flag;
    // equip
    public int upgradeSlots, level, str, dex, intel, luk, hp, mp, watk, matk, wdef, mdef, acc, avoid, hands, speed, jump;
    public int itemLevel, itemExp, vicious;
    // pet
    public String petName;
    public int petLevel, petCloseness, petFullness;

    public boolean isEquip() { return type == 1; }
}
