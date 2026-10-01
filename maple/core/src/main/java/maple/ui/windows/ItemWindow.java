package maple.ui.windows;

import com.badlogic.gdx.utils.Align;
import maple.game.ItemInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.Button;
import maple.ui.ItemTooltip;
import maple.ui.Scrollbar;
import maple.ui.TabStrip;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Widget;
import maple.ui.Window;

import java.util.TreeMap;

/**
 * The Item window (UIWindow.img/Item): five tabs on the Tab2 strip, 4 x 6 visible slots at
 * x 8 + 36 * col, y 50 + 34 * row (0081e2c8), the VScr scrollbar at (152, 51, 200), mesos right-aligned
 * at (26..138, 266) with BtCoin at (7, 266), BtGather / BtSort at the top right.
 */
public final class ItemWindow extends Window implements Ui.DropTarget {
    private final World world;
    private final TabStrip tabs;
    private final Scrollbar scroll;
    private int tab; // 0..4 -> inventory type 1..5
    private int start; // first visible slot - 1
    private final Button gather, sort;
    private boolean sorted;

    public ItemWindow(Ui ui, World world) {
        super(ui, "Item", "UIWindow.img/Item/backgrnd");
        this.world = world;
        tabs = add(new TabStrip(ui.assets, "UIWindow.img/Item/Tab", 5, 170));
        tabs.onSelect = i -> {
            tab = i;
            start = 0;
            refresh();
        };
        scroll = add(new Scrollbar(ui.assets, "VScr4", 152, 51, 200));
        scroll.onChange = p -> start = p * 4;
        add(new Button(ui.assets, "UIWindow.img/Item/BtCoin", 7, 266, this::dropMesos));
        gather = add(new Button(ui.assets, "UIWindow.img/Item/BtGather", w - 32, 6, () -> {
            world.sortInventory(tab + 1, false);
            sorted = true;
            refresh();
        }));
        sort = add(new Button(ui.assets, "UIWindow.img/Item/BtSort", w - 32, 6, () -> {
            world.sortInventory(tab + 1, true);
            sorted = false;
            refresh();
        }));
        sort.visible = false;
        addClose();
        if (!gather.present()) gather.visible = false;
    }

    private int type() { return tab + 1; }

    private int capacity() {
        if (world.data() == null) return 24;
        int c = world.data().slotLimits[type()];
        return c > 0 ? c : 24;
    }

    @Override
    public void refresh() {
        int rows = (capacity() + 3) / 4;
        scroll.setRange(Math.max(1, rows - 5), start / 4);
        start = scroll.position * 4;
        gather.visible = !sorted && gather.present();
        sort.visible = sorted && sort.present();
    }

    private void dropMesos() {
        if (world.data() == null) return;
        int max = Math.min(50000, world.data().meso);
        if (max < 10) {
            ui.open(new maple.ui.Dialogs.Notice(ui, "You don't have enough mesos to drop.", false, null, null));
            return;
        }
        ui.open(new QuantityPrompt(ui, "How many mesos will you drop?", 10, 10, max, world::dropMeso));
    }

    private static float slotX(int index) { return 8 + 36 * (index % 4); }
    private static float slotY(int index) { return 50 + 34 * (index / 4); }

    /** Inventory slot under a local point (1-based), or 0. */
    private int slotAt(float lx, float ly) {
        for (int i = 0; i < 24; i++) {
            float sx = slotX(i), sy = slotY(i);
            if (lx >= sx && lx < sx + 32 && ly >= sy && ly < sy + 32) {
                int slot = start + i + 1;
                return slot <= capacity() ? slot : 0;
            }
        }
        return 0;
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (world.data() == null) return;
        TreeMap<Integer, Item> inv = world.data().inventory(type());
        Sprite disabled = ui.assets.sprite("UIWindow.img/Item/disabled");
        int cap = capacity();
        for (int i = 0; i < 24; i++) {
            int slot = start + i + 1;
            float sx = slotX(i), sy = slotY(i);
            if (slot > cap) {
                if (disabled != null) g.image(disabled, sx, sy);
                continue;
            }
            Item it = inv.get(slot);
            if (it == null || (carrying() && carriedSlot() == slot)) continue;
            drawItem(g, ui, it, sx, sy, true);
        }
        g.text(String.format(java.util.Locale.US, "%,d", world.data().meso), 26, 266, 112, Align.right, false, 12, false, 0xFF000000);
    }

    private boolean carrying() {
        return ui.carry instanceof ItemCarry && ((ItemCarry) ui.carry).type == type();
    }

    private int carriedSlot() {
        return ((ItemCarry) ui.carry).slot;
    }

    /** Icon at its bottom-left origin (y + 32) and the ItemNo count (0081df24). */
    static void drawItem(UiDraw g, Ui ui, Item it, float x, float y, boolean count) {
        Sprite icon = ui.assets.sprite(ItemInfo.get(it.itemId).icon());
        if (icon != null) g.anchored(icon, x, y + 32);
        int cat = it.itemId / 1000000;
        if (count && ((cat >= 2 && cat <= 4) || it.quantity > 1)) {
            float cx = x;
            for (char c : Integer.toString(it.quantity).toCharArray()) {
                Sprite d = ui.assets.sprite("Basic.img/ItemNo/" + c);
                if (d == null) continue;
                g.image(d, cx, y + 20);
                cx += d.w;
            }
        }
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int slot = slotAt(lx, ly);
        if (slot == 0 || world.data() == null) return false;
        Item it = world.data().inventory(type()).get(slot);
        if (it == null) return false;
        ui.carry = new ItemCarry(ui, world, type(), slot, it);
        UiSounds.play("DragStart");
        return true;
    }

    @Override
    public void onDoubleClick(float lx, float ly) {
        ui.carry = null;
        int slot = slotAt(lx, ly);
        if (slot == 0 || world.data() == null) return;
        Item it = world.data().inventory(type()).get(slot);
        if (it == null) return;
        if (type() == 1) world.equip(slot);
        else if (type() == 2) world.useItem(slot);
        else if (type() == 3) world.useItemId(it.itemId); // chairs
    }

    @Override
    public boolean drop(Ui.Carry c, float lx, float ly) {
        if (!(c instanceof ItemCarry)) return false;
        ItemCarry ic = (ItemCarry) c;
        int slot = slotAt(lx, ly);
        if (slot == 0) return true;
        if (ic.type == -1) {
            if (type() == 1) world.moveItem(1, ic.slot, slot, 1); // unequip into this slot
            return true;
        }
        if (ic.type != type()) return true;
        if (ic.slot != slot) world.moveItem(type(), ic.slot, slot, ic.item.quantity);
        return true;
    }

    @Override
    public Widget hit(float lx, float ly) {
        return super.hit(lx, ly);
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int slot = slotAt(lx, ly);
        if (slot == 0 || world.data() == null) return null;
        Item it = world.data().inventory(type()).get(slot);
        if (it == null) return null;
        return new ItemTooltip(ui.assets, it.itemId, it, world.data().stats, false, null);
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        scroll.scroll(amount);
        return true;
    }
}
