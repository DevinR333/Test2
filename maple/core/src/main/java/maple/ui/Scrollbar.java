package maple.ui;

import maple.gfx.Sprite;

/**
 * Basic.img VScr scrollbar (style 3 = "VScr4" by default, like 004d6d96): arrows, track and thumb.
 * position runs 0..max-1.
 */
public final class Scrollbar extends Widget {
    private final Sprite prev0, prev1, next0, next1, base, thumb0, thumb1, dPrev, dNext, dBase;
    public int position, max = 1;
    public java.util.function.IntConsumer onChange;
    private boolean dragging;
    private float grabOffset;
    private int pressed; // 0 none, 1 prev, 2 next

    public Scrollbar(UiAssets a, String branch, float x, float y, float extent) {
        String b = "Basic.img/" + branch + "/";
        prev0 = a.sprite(b + "enabled/prev0");
        prev1 = a.sprite(b + "enabled/prev1");
        next0 = a.sprite(b + "enabled/next0");
        next1 = a.sprite(b + "enabled/next1");
        base = a.sprite(b + "enabled/base");
        thumb0 = a.sprite(b + "enabled/thumb0");
        thumb1 = a.sprite(b + "enabled/thumb1");
        dPrev = a.sprite(b + "disabled/prev");
        dNext = a.sprite(b + "disabled/next");
        dBase = a.sprite(b + "disabled/base");
        this.x = x;
        this.y = y;
        this.w = prev0 == null ? 12 : prev0.w;
        this.h = extent;
    }

    private float arrow() { return prev0 == null ? 12 : prev0.h; }
    private float thumbH() { return thumb0 == null ? 12 : thumb0.h; }
    private float travel() { return Math.max(0, h - 2 * arrow() - thumbH()); }

    public void setRange(int max, int position) {
        this.max = Math.max(1, max);
        this.position = Math.max(0, Math.min(this.max - 1, position));
    }

    private void set(int p) {
        p = Math.max(0, Math.min(max - 1, p));
        if (p != position) {
            position = p;
            if (onChange != null) onChange.accept(p);
        }
    }

    public void scroll(int delta) {
        set(position + delta);
    }

    @Override
    public void draw(UiDraw g) {
        boolean enabled = max > 1;
        float a = arrow();
        Sprite track = enabled ? base : (dBase != null ? dBase : base);
        if (track != null) g.stretched(track, 0, a, w, h - 2 * a);
        g.image(enabled ? (pressed == 1 ? prev1 : prev0) : (dPrev != null ? dPrev : prev0), 0, 0);
        g.image(enabled ? (pressed == 2 ? next1 : next0) : (dNext != null ? dNext : next0), 0, h - a);
        if (enabled) {
            float ty = a + travel() * position / Math.max(1, max - 1);
            g.image(dragging ? thumb1 : thumb0, 0, ty);
        }
    }

    @Override
    public boolean interactive() { return true; }

    @Override
    public boolean onPress(float lx, float ly) {
        if (max <= 1) return false;
        float a = arrow();
        if (ly < a) {
            pressed = 1;
            set(position - 1);
        } else if (ly >= h - a) {
            pressed = 2;
            set(position + 1);
        } else {
            float ty = a + travel() * position / Math.max(1, max - 1);
            if (ly >= ty && ly < ty + thumbH()) {
                dragging = true;
                grabOffset = ly - ty;
            } else {
                set(position + (ly < ty ? -1 : 1));
            }
        }
        return true;
    }

    @Override
    public void onDrag(float lx, float ly) {
        if (!dragging) return;
        float t = travel();
        if (t <= 0) return;
        float pos = (ly - grabOffset - arrow()) / t;
        set(Math.round(pos * (max - 1)));
    }

    @Override
    public void onRelease(float lx, float ly, boolean inside) {
        dragging = false;
        pressed = 0;
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        scroll(amount);
        return true;
    }
}
