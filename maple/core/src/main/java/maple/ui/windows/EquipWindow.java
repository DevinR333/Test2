package maple.ui.windows;

import maple.game.ItemInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.Button;
import maple.ui.ItemTooltip;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;

import java.util.TreeMap;

/**
 * The Equip window (UIWindow.img/Equip/backgrnd): equipped items at the recovered slot coordinates
 * (index = -position - 1), cash items shown over their normal slot, and the pet-equipment toggle at
 * (102, 280) that attaches Equip/pet beside the window.
 */
public final class EquipWindow extends Window implements Ui.DropTarget {
    private static final int[][] COORDS = new int[50][];

    static {
        int[][] base = {
                {38, 35}, {38, 68}, {71, 101}, {104, 101}, {38, 134}, {38, 167}, {71, 200}, {5, 167}, {5, 134},
                {137, 134}, {104, 134}, {104, 167}, {137, 167}, null, {104, 68}, {137, 68}, {71, 134}, {5, 233},
                {38, 233}, {71, 233}};
        System.arraycopy(base, 0, COORDS, 0, base.length);
        COORDS[48] = new int[]{5, 68};
        COORDS[49] = new int[]{71, 167};
    }

    private final World world;
    private final Button petShow, petHide;
    private boolean pet;
    private final float baseW;

    public EquipWindow(Ui ui, World world) {
        super(ui, "Equip", "UIWindow.img/Equip/backgrnd");
        this.world = world;
        baseW = w;
        petShow = add(new Button(ui.assets, "UIWindow.img/Equip/BtPetEquipShow", 102, 280, this::togglePet));
        petHide = add(new Button(ui.assets, "UIWindow.img/Equip/BtPetEquipHide", 102, 280, this::togglePet));
        petHide.visible = false;
        addClose();
    }

    private void togglePet() {
        pet = !pet;
        petShow.visible = !pet;
        petHide.visible = pet;
        Sprite p = ui.assets.sprite("UIWindow.img/Equip/pet");
        w = pet && p != null ? baseW - 3 + p.w : baseW;
    }

    @Override
    public boolean contains(float lx, float ly) {
        if (lx >= 0 && ly >= 0 && lx < baseW && ly < h) return true;
        if (!pet) return false;
        Sprite p = ui.assets.sprite("UIWindow.img/Equip/pet");
        return p != null && lx >= baseW - 3 && lx < baseW - 3 + p.w && ly >= 123 && ly < 123 + p.h;
    }

    /** The item shown in a slot: the cash cover if worn, else the normal equip. */
    private Item shown(int index) {
        if (world.data() == null) return null;
        TreeMap<Integer, Item> eq = world.data().inventory(-1);
        Item cash = eq.get(-index - 1 - 100);
        return cash != null ? cash : eq.get(-index - 1);
    }

    private int indexAt(float lx, float ly) {
        for (int i = 0; i < COORDS.length; i++) {
            int[] c = COORDS[i];
            if (c != null && lx >= c[0] && lx < c[0] + 32 && ly >= c[1] && ly < c[1] + 32) return i;
        }
        return -1;
    }

    @Override
    public void draw(UiDraw g) {
        if (pet) {
            Sprite p = ui.assets.sprite("UIWindow.img/Equip/pet");
            if (p != null) g.image(p, baseW - 3, 123);
        }
        super.draw(g);
    }

    @Override
    protected void drawContent(UiDraw g) {
        for (int i = 0; i < COORDS.length; i++) {
            int[] c = COORDS[i];
            if (c == null) continue;
            Item it = shown(i);
            if (it == null || carrying(it)) continue;
            Sprite icon = ui.assets.sprite(ItemInfo.get(it.itemId).icon());
            if (icon != null) g.anchored(icon, c[0], c[1] + 32);
        }
    }

    private boolean carrying(Item it) {
        return ui.carry instanceof ItemCarry && ((ItemCarry) ui.carry).item == it;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int i = indexAt(lx, ly);
        if (i < 0) return false;
        Item it = shown(i);
        if (it == null) return false;
        ui.carry = new ItemCarry(ui, world, -1, it.position, it);
        UiSounds.play("DragStart");
        return true;
    }

    @Override
    public void onDoubleClick(float lx, float ly) {
        ui.carry = null;
        int i = indexAt(lx, ly);
        if (i < 0) return;
        Item it = shown(i);
        if (it != null) world.unequip(it.position);
    }

    @Override
    public boolean drop(Ui.Carry c, float lx, float ly) {
        if (!(c instanceof ItemCarry)) return false;
        ItemCarry ic = (ItemCarry) c;
        if (ic.type == 2 && ic.item.itemId / 10000 == 204) { // a scroll onto something being worn
            int i = indexAt(lx, ly);
            Item target = i < 0 ? null : shown(i);
            if (target != null) world.scroll(ic.slot, target.position);
            return true;
        }
        if (ic.type == 1) world.equip(ic.slot);
        return true;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int i = indexAt(lx, ly);
        if (i < 0 || world.data() == null) return null;
        Item it = shown(i);
        if (it == null) return null;
        return new ItemTooltip(ui.assets, it.itemId, it, world.data().stats, true, null);
    }
}
