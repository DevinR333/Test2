package maple.ui.hud;

import maple.game.ItemInfo;
import maple.game.SkillInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.KeyMap;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widget;

import java.util.function.IntConsumer;

/**
 * The quick slot panel (StatusBar.img/base/quickSlot) at client (647,427) + 22, eight 32px slots
 * (008de8d5), showing what Shift/Ins/Home/PgUp/Ctrl/Del/End/PgDn are bound to. Tapping a slot presses
 * that key.
 */
public final class QuickSlots extends Widget implements Ui.DropTarget {
    private static final int[][] SLOT = {{7, 8}, {42, 8}, {77, 8}, {112, 8}, {7, 41}, {42, 41}, {77, 41}, {112, 41}};
    private final Ui ui;
    private final World world;
    private final IntConsumer press;
    private final Sprite back;
    private final Sprite[] labels = new Sprite[8];

    public QuickSlots(Ui ui, World world, IntConsumer pressSlot) {
        this.ui = ui;
        this.world = world;
        this.press = pressSlot;
        x = 647;
        y = 427 + 22;
        w = 151;
        h = 84;
        back = ui.assets.sprite("StatusBar.img/base/quickSlot");
        for (int i = 0; i < 8; i++) labels[i] = ui.assets.sprite("StatusBar.img/key/" + i);
    }

    @Override
    public void draw(UiDraw g) {
        g.image(back, 0, 4);
        for (int i = 0; i < 8; i++) {
            int slot = KeyMap.QUICK_SLOTS[i];
            int type = world.keyTypes[slot], action = world.keyActions[slot];
            int sx = SLOT[i][0], sy = SLOT[i][1] + 4;
            Sprite icon = icon(type, action);
            if (icon != null) {
                if (type == KeyMap.ITEM || type == KeyMap.CASH_ITEM) g.anchored(icon, sx, sy + 32);
                else g.image(icon, sx, sy);
            }
            if (type == KeyMap.ITEM) {
                int count = count(action);
                drawCount(g, count, sx, sy);
            }
            if (labels[i] != null) g.image(labels[i], sx, sy);
        }
    }

    private Sprite icon(int type, int action) {
        switch (type) {
            case KeyMap.SKILL: {
                SkillInfo s = SkillInfo.get(action);
                return s == null ? null : ui.assets.sprite(s.icon());
            }
            case KeyMap.ITEM:
            case KeyMap.CASH_ITEM:
                return ui.assets.sprite(ItemInfo.get(action).icon());
            case KeyMap.MENU:
            case KeyMap.ACTION:
            case KeyMap.FACE:
                return ui.assets.sprite("UIWindow.img/KeyConfig/icon/" + action);
            default:
                return null;
        }
    }

    private int count(int itemId) {
        if (world.data() == null) return 0;
        int n = 0;
        for (Item it : world.data().inventory(ItemInfo.inventoryType(itemId)).values()) if (it.itemId == itemId) n += it.quantity;
        return n;
    }

    /** ItemNo digits at the slot's left, bottom-12 (0081df24). */
    private void drawCount(UiDraw g, int count, float x, float y) {
        float cx = x;
        for (char c : Integer.toString(count).toCharArray()) {
            Sprite s = ui.assets.sprite("Basic.img/ItemNo/" + c);
            if (s == null) continue;
            g.image(s, cx, y + 20);
            cx += s.w;
        }
    }

    @Override
    public boolean interactive() { return true; }

    private int slotAt(float lx, float ly) {
        for (int i = 0; i < 8; i++) {
            int sx = SLOT[i][0], sy = SLOT[i][1] + 4;
            if (lx >= sx && lx < sx + 32 && ly >= sy && ly < sy + 32) return i;
        }
        return -1;
    }

    @Override
    public boolean onPress(float lx, float ly) {
        int s = slotAt(lx, ly);
        if (s >= 0) press.accept(KeyMap.QUICK_SLOTS[s]);
        return false;
    }

    @Override
    public boolean drop(Ui.Carry c, float lx, float ly) {
        int s = slotAt(lx, ly);
        if (s < 0) return true;
        int type, action;
        if (c instanceof maple.ui.windows.BindingCarry) {
            maple.ui.windows.BindingCarry b = (maple.ui.windows.BindingCarry) c;
            type = b.type;
            action = b.action;
        } else if (c instanceof maple.ui.windows.ItemCarry) {
            maple.ui.windows.ItemCarry ic = (maple.ui.windows.ItemCarry) c;
            type = maple.ui.windows.KeyConfigWindow.bindingType(ic.item.itemId);
            action = ic.item.itemId;
            if (type == 0) return true;
        } else return false;
        int slot = KeyMap.QUICK_SLOTS[s];
        world.keyTypes[slot] = type;
        world.keyActions[slot] = action;
        world.changeKeys(new int[]{slot});
        return true;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int s = slotAt(lx, ly);
        if (s < 0) return null;
        int slot = KeyMap.QUICK_SLOTS[s];
        int type = world.keyTypes[slot], action = world.keyActions[slot];
        switch (type) {
            case KeyMap.SKILL: {
                SkillInfo si = SkillInfo.get(action);
                return si == null ? null : Tooltip.text(si.name);
            }
            case KeyMap.ITEM:
                return Tooltip.text(ItemInfo.get(action).name);
            case KeyMap.MENU:
                return Tooltip.text(KeyMap.menuName(action));
            case KeyMap.ACTION:
                return Tooltip.text(KeyMap.actionName(action));
            default:
                return null;
        }
    }
}
