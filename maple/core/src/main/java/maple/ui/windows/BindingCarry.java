package maple.ui.windows;

import maple.gfx.Sprite;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widget;

/** A key binding (skill, item, menu or action icon) carried to the quick slots or the Key Config. */
public final class BindingCarry extends Ui.Carry {
    public final int type, action;
    /** Key slot it was lifted from in the Key Config, or -1. */
    public final int fromSlot;
    private final Sprite icon;
    private final boolean anchored;
    /** Called when dropped outside every window (removes it from its key). */
    public Runnable onWorld;

    public BindingCarry(int type, int action, Sprite icon, boolean anchored, int fromSlot) {
        this.type = type;
        this.action = action;
        this.icon = icon;
        this.anchored = anchored;
        this.fromSlot = fromSlot;
    }

    @Override
    public void draw(UiDraw g, float x, float y) {
        if (icon == null) return;
        g.alpha(0.75f);
        if (anchored) g.anchored(icon, x, y);
        else g.image(icon, x - icon.w / 2f, y - icon.h / 2f);
        g.resetColor();
    }

    @Override
    public void droppedOnWorld(float x, float y) {
        if (onWorld != null) onWorld.run();
    }

    @Override
    public boolean droppedOn(Widget w, float lx, float ly) {
        return w instanceof Ui.DropTarget && ((Ui.DropTarget) w).drop(this, lx, ly);
    }
}
