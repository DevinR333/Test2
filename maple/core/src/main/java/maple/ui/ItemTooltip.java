package maple.ui;

import com.badlogic.gdx.utils.Align;
import maple.game.ItemInfo;
import maple.gfx.Sprite;
import maple.net.model.CharStats;
import maple.net.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Item hover box, after openms' recovery of 008f39e1/008f421e/008f5056/008ec366: bold centred title,
 * yellow flags, a 68x68 translucent white panel with the icon at double size, for equipment the
 * UIWindow.img/ToolTip/Equip Can/Cannot requirement and job art, then stats and the description.
 */
public final class ItemTooltip extends Tooltip {
    /** Levels added to yours for level requirements (Empress's Might: 10). */
    public static int levelBonus;
    private static final int WHITE = 0xFFFFFFFF, HEADING = 0xFFFDF514, MUTED = 0xFFBCBCBC, IMPROVED = 0xFFFF8A18;
    private final UiAssets assets;
    private final ItemInfo info;
    private final Item item; // may be null (shop preview)
    private final CharStats me;
    private final boolean equipped;
    private final List<String> flags = new ArrayList<>();
    private final List<Object[]> rows = new ArrayList<>(); // text, color, small?

    public ItemTooltip(UiAssets assets, int itemId, Item item, CharStats me, boolean equipped, String extraLine) {
        this.assets = assets;
        this.info = ItemInfo.get(itemId);
        this.item = item;
        this.me = me;
        this.equipped = equipped;
        if (info.untradeable) flags.add("Untradeable");
        if (info.quest) flags.add("Quest item");
        if (info.only) flags.add("One-of-a-kind item");
        if (extraLine != null) rows.add(new Object[]{extraLine, MUTED, false});
        if (info.isEquip()) {
            stat("STR", "incSTR", item == null ? null : item.str);
            stat("DEX", "incDEX", item == null ? null : item.dex);
            stat("INT", "incINT", item == null ? null : item.intel);
            stat("LUK", "incLUK", item == null ? null : item.luk);
            stat("HP", "incMHP", item == null ? null : item.hp);
            stat("MP", "incMMP", item == null ? null : item.mp);
            stat("WEAPON ATTACK", "incPAD", item == null ? null : item.watk);
            stat("MAGIC ATTACK", "incMAD", item == null ? null : item.matk);
            stat("WEAPON DEF.", "incPDD", item == null ? null : item.wdef);
            stat("MAGIC DEF.", "incMDD", item == null ? null : item.mdef);
            stat("ACCURACY", "incACC", item == null ? null : item.acc);
            stat("AVOIDABILITY", "incEVA", item == null ? null : item.avoid);
            stat("SPEED", "incSpeed", item == null ? null : item.speed);
            stat("JUMP", "incJump", item == null ? null : item.jump);
            int slots = item != null ? item.upgradeSlots : info.tuc;
            if (slots > 0 || info.tuc > 0) rows.add(new Object[]{"NUMBER OF UPGRADES AVAILABLE : " + slots, WHITE, true});
        }
        if (!info.desc.isEmpty()) rows.add(new Object[]{stripMarkup(info.desc), WHITE, false});
        if (itemId / 10000 == 910) packageRows();
    }

    /** A package lists what it holds and what each piece does (each arrives in the Cash Inventory). */
    private void packageRows() {
        java.util.List<int[]> in = ItemInfo.packageContents(info.id);
        rows.add(new Object[]{"This package contains " + in.size() + " item" + (in.size() == 1 ? "" : "s") + ":", WHITE, false});
        for (int[] c : in) {
            ItemInfo p = ItemInfo.get(c[0]);
            String line = "- " + (p.name.isEmpty() ? "Item " + c[0] : p.name) + (c[1] > 1 ? " x" + c[1] : "");
            if (c[2] > 0 && !offline.OfflineOptions.permanentCash) line += " (" + c[2] + " days)";
            rows.add(new Object[]{line, 0xFFFFCC00, false});
            String what = p.isEquip() ? equipSummary(p) : stripMarkup(p.desc).replace('\n', ' ').trim();
            if (what.length() > 150) what = what.substring(0, 147).trim() + "...";
            if (!what.isEmpty()) rows.add(new Object[]{what, MUTED, true});
        }
    }

    /** "Hat, req. Lv. 10: STR +2, Speed +5" for an equip inside a package. */
    private static String equipSummary(ItemInfo p) {
        StringBuilder b = new StringBuilder(slotName(p.id));
        if (p.reqLevel > 0) b.append(", req. Lv. ").append(p.reqLevel);
        String[][] stats = {{"incSTR", "STR"}, {"incDEX", "DEX"}, {"incINT", "INT"}, {"incLUK", "LUK"}, {"incMHP", "HP"},
                {"incMMP", "MP"}, {"incPAD", "Weapon ATT"}, {"incMAD", "Magic ATT"}, {"incPDD", "Weapon DEF"},
                {"incMDD", "Magic DEF"}, {"incACC", "Accuracy"}, {"incEVA", "Avoid"}, {"incSpeed", "Speed"}, {"incJump", "Jump"}};
        String sep = ": ";
        for (String[] s : stats) {
            int v = p.info.getInt(s[0], 0);
            if (v == 0) continue;
            b.append(sep).append(s[1]).append(v > 0 ? " +" : " ").append(v);
            sep = ", ";
        }
        if (p.desc.length() > 0) b.append(". ").append(stripMarkup(p.desc).replace('\n', ' ').trim());
        return b.toString();
    }

    private static String slotName(int id) {
        switch (id / 10000) {
            case 100: return "Hat";
            case 101: return "Face accessory";
            case 102: return "Eye accessory";
            case 103: return "Earrings";
            case 104: return "Top";
            case 105: return "Overall";
            case 106: return "Bottom";
            case 107: return "Shoes";
            case 108: return "Gloves";
            case 109: return "Shield";
            case 110: return "Cape";
            case 111: return "Ring";
            case 112: return "Pendant";
            case 180: case 181: case 182: case 183: return "Pet equipment";
            case 190: return "Mount";
            case 191: return "Saddle";
            default:
                if (id / 10000 == 170) return "Weapon (cash cover)";
                if (id / 10000 >= 130 && id / 10000 < 170) return "Weapon";
                return "Equipment";
        }
    }

    private void stat(String label, String key, Integer actual) {
        int base = info.info.getInt(key, 0);
        int value = actual == null ? base : actual;
        if (value == 0) return;
        int bonus = value - base;
        String text = label + " : " + (value > 0 ? "+" : "") + value + (bonus != 0 ? " (" + base + (bonus > 0 ? " + " : " - ") + Math.abs(bonus) + ")" : "");
        rows.add(new Object[]{text, bonus != 0 ? IMPROVED : WHITE, true});
    }

    static String stripMarkup(String s) {
        return s.replace("\\n", "\n").replace("\\r", "").replaceAll("#[a-zA-Z]", "");
    }

    private String title() {
        String name = info.name.isEmpty() ? "Item " + info.id : info.name;
        if (info.id == offline.UltimateExplorer.MEDAL && item != null && !item.owner.isEmpty()) name = item.owner + "'s Successor";
        if (info.id / 10000 == 204 && !name.matches(".*\\d+\\s*%.*")) {
            int rate = info.info.getInt("success", -1);
            if (rate >= 0) name += " " + rate + "%";
        }
        if (item != null && item.level > 0) name += " (+" + item.level + ")";
        return name;
    }

    @Override
    public void draw(UiDraw g, float px, float py) {
        boolean equip = info.isEquip();
        float width = equip ? 236 : 290;
        float inner = width - 18;
        // measure
        float flagsH = flags.size() * 19;
        float textY = equip ? 32 + flagsH + 126 : 32 + flagsH + 12;
        float textH = 0;
        for (Object[] r : rows) textH += rowHeight(g, r, equip ? inner : inner - 82);
        float height = equip ? textY + textH + 10 : Math.max(32 + flagsH + 76, 32 + flagsH + 12 + textH + 10);
        float x = px + 12, y = py + 18;
        if (x + width > Ui.W) x = px - width - 4;
        if (y + height > Ui.H) y = Ui.H - height;
        x = Math.max(0, x);
        y = Math.max(0, y);
        g.fill(x, y, width, height, Tooltip.BACK);
        g.outline(x, y, width, height, 0xFFFFFFFF);
        g.text(title(), x + 9, y + 10, inner, Align.center, true, 12, true, WHITE);
        float fy = y + 30;
        for (String f : flags) {
            g.text(f, x + 9, fy, inner, Align.center, false, 12, false, HEADING);
            fy += 19;
        }
        float off = flagsH;
        g.fill(x + 10, y + 32 + off, 68, 68, 0xA0FFFFFF);
        Sprite icon = assets.sprite(info.iconRaw());
        if (icon != null) {
            g.batch.flush();
            float ix = x + 12 + (32 - icon.w) / 2f * 2, iy = y + 98 + off - icon.h * 2;
            g.stretched(icon, ix + icon.ox * 0, iy, icon.w * 2, icon.h * 2);
        }
        if (equip) {
            String[][] req = {{"reqLEV", "reqLevel"}, {"reqSTR", "reqSTR"}, {"reqDEX", "reqDEX"}, {"reqINT", "reqINT"}, {"reqLUK", "reqLUK"}, {"reqPOP", "reqPOP"}};
            int[] need = {info.reqLevel, info.reqStr, info.reqDex, info.reqInt, info.reqLuk, info.reqPop};
            int[] have = me == null ? new int[6] : new int[]{me.level + levelBonus, me.str, me.dex, me.intel, me.luk, me.fame};
            for (int i = 0; i < 6; i++) {
                boolean can = me == null || have[i] >= need[i];
                String prefix = "UIWindow.img/ToolTip/Equip/" + (can ? "Can" : "Cannot") + "/";
                float ry = y + 32 + off + i * 12;
                Sprite label = assets.sprite(prefix + req[i][0]);
                if (label != null) g.image(label, x + 94, ry);
                else g.text("REQ " + req[i][0].substring(3) + " :", x + 94, ry - 1, 9, false, can ? WHITE : 0xFFF20303);
                String digits = need[i] == 0 && i == 5 ? "-" : Integer.toString(need[i]);
                float dx = x + 144;
                for (char c : digits.toCharArray()) {
                    Sprite d = assets.sprite(prefix + (c == '-' ? "none" : String.valueOf(c)));
                    if (d != null) {
                        g.image(d, dx, ry);
                        dx += d.w + 1;
                    } else {
                        g.text(String.valueOf(c), dx, ry - 1, 9, false, can ? WHITE : 0xFFF20303);
                        dx += 6;
                    }
                }
            }
            String[][] jobs = {{"beginner", "0", "10"}, {"warrior", "1", "52"}, {"magician", "2", "92"}, {"bowman", "4", "132"}, {"thief", "8", "171"}, {"pirate", "16", "197"}};
            int mask = info.reqJob;
            for (String[] j : jobs) {
                int bit = Integer.parseInt(j[1]);
                boolean allowed = mask == 0 || (bit == 0 ? mask == -1 : mask != -1 && (mask & bit) != 0);
                Sprite s = assets.sprite("UIWindow.img/ToolTip/Equip/" + (allowed ? "Can" : "Cannot") + "/" + j[0]);
                if (s != null) g.image(s, x + Integer.parseInt(j[2]), y + 133 + off);
            }
        }
        float ty = equip ? y + textY : y + 32 + off + 12;
        float tx = equip ? x + 9 : x + 9 + 82;
        float tw = equip ? inner : inner - 82;
        for (Object[] r : rows) {
            boolean small = (Boolean) r[2];
            g.text((String) r[0], tx, ty, tw, Align.left, true, small ? 9 : 12, false, (Integer) r[1]);
            ty += rowHeight(g, r, tw);
        }
    }

    private static float rowHeight(UiDraw g, Object[] r, float w) {
        boolean small = (Boolean) r[2];
        int size = small ? 9 : 12;
        float lineH = small ? 13 : 16;
        String s = (String) r[0];
        int lines = 0;
        for (String part : s.split("\n")) lines += Math.max(1, (int) Math.ceil(g.textWidth(part, size, false) / Math.max(1, w)));
        return lines * lineH;
    }
}
