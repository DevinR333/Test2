package maple.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Something on screen that can be drawn and clicked. Positions are relative to the parent, in UI pixels.
 * Children are drawn in order; the last one gets clicks first.
 */
public class Widget {
    public float x, y, w, h;
    public boolean visible = true;
    public Widget parent;
    public final List<Widget> children = new ArrayList<>();

    public Widget() {}

    public Widget(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public <T extends Widget> T add(T child) {
        child.parent = this;
        children.add(child);
        return child;
    }

    public void remove(Widget child) {
        children.remove(child);
        child.parent = null;
    }

    public void clear() {
        for (Widget c : children) c.parent = null;
        children.clear();
    }

    /** Position on the 800x600 screen. */
    public float screenX() { return parent == null ? x : parent.screenX() + x; }
    public float screenY() { return parent == null ? y : parent.screenY() + y; }

    public boolean contains(float lx, float ly) {
        return lx >= 0 && ly >= 0 && lx < w && ly < h;
    }

    /** Called every frame before drawing. */
    public void update(long ms) {
        for (int i = 0; i < children.size(); i++) {
            Widget c = children.get(i);
            if (c.visible) c.update(ms);
        }
    }

    /** Draws this widget at screen offset (g.tx, g.ty already include our position). */
    public void draw(UiDraw g) {
        drawChildren(g);
    }

    protected void drawChildren(UiDraw g) {
        for (int i = 0; i < children.size(); i++) {
            Widget c = children.get(i);
            if (!c.visible) continue;
            g.tx += c.x;
            g.ty += c.y;
            c.draw(g);
            g.tx -= c.x;
            g.ty -= c.y;
        }
    }

    /** The deepest visible widget at local (lx, ly) that wants the pointer, or null. */
    public Widget hit(float lx, float ly) {
        Widget h = hitChildren(lx, ly);
        if (h != null) return h;
        return interactive() && contains(lx, ly) ? this : null;
    }

    /** Only the children (for full-screen layers that must not take taps everywhere). */
    protected Widget hitChildren(float lx, float ly) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget c = children.get(i);
            if (!c.visible) continue;
            Widget h = c.hit(lx - c.x, ly - c.y);
            if (h != null) return h;
        }
        return null;
    }

    /** Whether this widget itself takes pointer input (buttons, slots, drag bars...). */
    public boolean interactive() { return false; }

    // Pointer callbacks (local coordinates). Return true to keep the pointer captured.
    public boolean onPress(float lx, float ly) { return false; }
    public void onDrag(float lx, float ly) {}
    public void onRelease(float lx, float ly, boolean inside) {}
    public void onDoubleClick(float lx, float ly) {}
    public void onRightClick(float lx, float ly) {}
    public void onHover(boolean over) {}
    /** Mouse wheel / two-finger scroll; true if used. */
    public boolean onScroll(float lx, float ly, int amount) { return false; }

    /** Text shown in a tooltip while hovered, or null. */
    public Tooltip tooltip(float lx, float ly) { return null; }

    public Window window() {
        Widget w = this;
        while (w != null && !(w instanceof Window)) w = w.parent;
        return (Window) w;
    }
}
