package maple.ui.windows;

import maple.game.ItemInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widget;

/** An item picked up with the cursor from the inventory or the equipment window. */
public final class ItemCarry extends Ui.Carry {
    public final int type; // 1..5, or -1 for equipped
    public final int slot;
    public final Item item;
    private final Sprite icon;
    private final World world;
    private final Ui ui;

    public ItemCarry(Ui ui, World world, int type, int slot, Item item) {
        this.ui = ui;
        this.world = world;
        this.type = type;
        this.slot = slot;
        this.item = item;
        this.icon = ui.assets.sprite(ItemInfo.get(item.itemId).iconRaw());
    }

    @Override
    public void draw(UiDraw g, float x, float y) {
        if (icon == null) return;
        g.alpha(0.75f);
        g.anchored(icon, x, y);
        g.resetColor();
    }

    @Override
    public void droppedOnWorld(float x, float y) {
        // dropping on the map throws the item (stacks ask how many)
        if (type == -1) return; // equipped items must be unequipped first
        int qty = item.quantity;
        if (qty > 1 && !ItemInfo.get(item.itemId).isRechargeable()) {
            ui.open(new QuantityPrompt(ui, "How many will you drop?", qty, qty, n -> world.moveItem(type, slot, 0, n)));
        } else {
            world.moveItem(type, slot, 0, Math.max(1, qty));
        }
    }

    @Override
    public boolean droppedOn(Widget w, float lx, float ly) {
        return w instanceof Ui.DropTarget && ((Ui.DropTarget) w).drop(this, lx, ly);
    }
}
