package maple.game;

import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/** Item data from Item.wz / Character.wz and the names in String.wz, cached per item id. */
public final class ItemInfo {
    public final int id;
    public final String name, desc;
    /** The item's own node (".../info" lives under it). */
    public final WzNode node;
    public final WzNode info;
    public final int slotMax, price, reqLevel, reqJob, reqStr, reqDex, reqInt, reqLuk, reqPop, tuc;
    public final boolean cash, quest, untradeable, only;

    private static Wz wz;
    private static final Map<Integer, ItemInfo> cache = new HashMap<>();
    private static WzNode strEqp, strConsume, strIns, strEtc, strCash, strPet;

    public static void init(Wz w) {
        wz = w;
        cache.clear();
        strEqp = w.get("String/Eqp.img/Eqp");
        strConsume = w.get("String/Consume.img");
        strIns = w.get("String/Ins.img");
        strEtc = w.get("String/Etc.img/Etc");
        strCash = w.get("String/Cash.img");
        strPet = w.get("String/Pet.img");
    }

    public static synchronized ItemInfo get(int id) {
        ItemInfo i = cache.get(id);
        if (i == null) {
            i = new ItemInfo(id);
            cache.put(id, i);
        }
        return i;
    }

    /** 1 equip, 2 use, 3 setup, 4 etc, 5 cash (inventory type from the id). */
    public static int inventoryType(int id) {
        int t = id / 1000000;
        return t >= 1 && t <= 5 ? t : 0;
    }

    public static String equipFolder(int id) {
        int p = id / 10000;
        switch (p) {
            case 100: return "Cap";
            case 101: case 102: case 103: case 112: case 113: case 114: case 115: return "Accessory";
            case 104: return "Coat";
            case 105: return "Longcoat";
            case 106: return "Pants";
            case 107: return "Shoes";
            case 108: return "Glove";
            case 109: return "Shield";
            case 110: return "Cape";
            case 111: return "Ring";
            case 180: case 181: case 182: case 183: return "PetEquip";
            case 190: case 191: case 192: case 193: case 198: return "TamingMob";
            default:
                if (id / 10000 >= 130 && id / 10000 < 170) return "Weapon";
                if (id / 10000 == 20 || id / 10000 == 21) return "Face";
                if (id / 10000 == 3) return "Hair";
                return "Accessory";
        }
    }

    private static WzNode itemNode(int id) {
        int type = id / 1000000;
        String sid = String.format("%08d", id);
        switch (type) {
            case 1:
                return wz.get("Character/" + equipFolder(id) + "/" + sid + ".img");
            case 2:
                return wz.get("Item/Consume/" + String.format("%04d", id / 10000) + ".img/" + sid);
            case 3:
                return wz.get("Item/Install/" + String.format("%04d", id / 10000) + ".img/" + sid);
            case 4:
                return wz.get("Item/Etc/" + String.format("%04d", id / 10000) + ".img/" + sid);
            case 5:
                if (id / 10000 == 500) return wz.get("Item/Pet/" + id + ".img");
                return wz.get("Item/Cash/" + String.format("%04d", id / 10000) + ".img/" + sid);
            default:
                if (id / 10000 == 900) return wz.get("Item/Special/0900.img/" + sid);
                return WzNode.MISSING;
        }
    }

    private static WzNode stringNode(int id) {
        String s = Integer.toString(id);
        switch (id / 1000000) {
            case 1: {
                WzNode n = strEqp.get(equipFolder(id)).get(s);
                return n;
            }
            case 2: return strConsume.get(s);
            case 3: return strIns.get(s);
            case 4: return strEtc.get(s);
            case 5: return id / 10000 == 500 ? strPet.get(s) : strCash.get(s);
            default: return WzNode.MISSING;
        }
    }

    private ItemInfo(int id) {
        this.id = id;
        node = itemNode(id);
        info = node.get("info");
        WzNode str = stringNode(id);
        name = str.getString("name", "");
        desc = str.getString("desc", "");
        slotMax = info.getInt("slotMax", id / 1000000 == 1 ? 1 : 100);
        price = info.getInt("price", 0);
        reqLevel = info.getInt("reqLevel", 0);
        reqJob = info.getInt("reqJob", 0);
        reqStr = info.getInt("reqSTR", 0);
        reqDex = info.getInt("reqDEX", 0);
        reqInt = info.getInt("reqINT", 0);
        reqLuk = info.getInt("reqLUK", 0);
        reqPop = info.getInt("reqPOP", 0);
        tuc = info.getInt("tuc", 0);
        cash = info.getInt("cash", 0) != 0 || id / 1000000 == 5;
        quest = info.getInt("quest", 0) != 0;
        untradeable = info.getInt("tradeBlock", 0) != 0;
        only = info.getInt("only", 0) != 0;
    }

    /** The inventory icon (info/icon). */
    public WzNode icon() {
        WzNode i = info.get("icon");
        if (!i.exists() && id / 10000 == 500) i = info.get("icon"); // pets keep it in info too
        return i;
    }

    /** The icon used for drops and the cursor (info/iconRaw). */
    public WzNode iconRaw() {
        WzNode i = info.get("iconRaw");
        return i.exists() ? i : icon();
    }

    public boolean isEquip() { return id / 1000000 == 1; }

    public boolean isRechargeable() {
        int t = id / 10000;
        return t == 207 || t == 233;
    }

    /** Weapon type number (id / 10000 % 100): 30 1h sword, 31 1h axe, 32 1h bw, 33 dagger, 37 wand, 38 staff,
     * 40 2h sword, 41 2h axe, 42 2h bw, 43 spear, 44 polearm, 45 bow, 46 crossbow, 47 claw, 48 knuckle, 49 gun. */
    public int weaponType() {
        return isEquip() && id / 10000 >= 130 && id / 10000 < 150 ? id / 10000 % 100 : 0;
    }

    /** Equip slot index (negative position) for an equip id, like the client's body part table. */
    public static int equipSlot(int id) {
        int p = id / 10000;
        switch (p) {
            case 100: return -1;   // cap
            case 101: return -2;   // face accessory
            case 102: return -3;   // eye accessory
            case 103: return -4;   // earrings
            case 104: case 105: return -5; // top / overall
            case 106: return -6;   // bottom
            case 107: return -7;   // shoes
            case 108: return -8;   // gloves
            case 110: return -9;   // cape
            case 109: return -10;  // shield
            case 111: return -12;  // ring
            case 112: return -17;  // pendant
            case 113: return -50;  // belt
            case 114: return -49;  // medal
            default:
                if (p >= 130 && p < 170) return -11;
                if (p == 180) return -121;
                if (p == 190) return -18;
                if (p == 191) return -19;
                if (p == 192) return -20;
                return 0;
        }
    }
}
