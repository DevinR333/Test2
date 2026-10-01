package maple.ui;

import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.wz.WzNode;

/**
 * A WZ button: normal / mouseOver / pressed / disabled (/ keyFocused) states under one path,
 * each a canvas or a frame list. Every state is drawn relative to the normal state's origin.
 */
public class Button extends Widget {
    public enum State { NORMAL, MOUSE_OVER, PRESSED, DISABLED }

    private final Animation[] states = new Animation[4];
    private int nox, noy;
    public boolean disabled;
    private boolean hover, pressed;
    public Runnable action;
    public String tip;
    /** Optional: draw this state regardless of input (e.g. a selected tab). */
    public State forced;
    public final String path;

    public Button(UiAssets a, String path, float x, float y, Runnable action) {
        this.path = path;
        this.x = x;
        this.y = y;
        this.action = action;
        WzNode base = a.node(path);
        String[] names = {"normal", "mouseOver", "pressed", "disabled"};
        for (int i = 0; i < 4; i++) {
            WzNode n = base.get(names[i]);
            if (n.exists()) states[i] = a.animation(n);
        }
        Sprite normal = states[0] == null ? null : states[0].first();
        if (normal != null) {
            w = normal.w;
            h = normal.h;
            nox = normal.ox;
            noy = normal.oy;
        }
    }

    /** True if the WZ data had this button at all. */
    public boolean present() {
        return states[0] != null && !states[0].isEmpty();
    }

    public State state() {
        if (forced != null) return forced;
        if (disabled) return State.DISABLED;
        if (pressed && hover) return State.PRESSED;
        if (hover) return State.MOUSE_OVER;
        return State.NORMAL;
    }

    @Override
    public void draw(UiDraw g) {
        int s = state().ordinal();
        Animation a = states[s] != null && !states[s].isEmpty() ? states[s] : states[0];
        if (a == null || a.isEmpty()) return;
        g.anim(a, nox, noy, g.timeMs);
    }

    @Override
    public boolean interactive() { return true; }

    @Override
    public boolean onPress(float lx, float ly) {
        if (disabled) return false;
        pressed = true;
        hover = true;
        return true;
    }

    @Override
    public void onRelease(float lx, float ly, boolean inside) {
        boolean was = pressed;
        pressed = false;
        if (!inside) hover = false;
        if (was && inside && !disabled && action != null) {
            UiSounds.play("BtMouseClick");
            action.run();
        }
    }

    @Override
    public void onHover(boolean over) {
        if (over && !hover && !disabled) UiSounds.play("BtMouseOver");
        hover = over;
        if (!over) pressed = false;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        return tip == null ? null : Tooltip.text(tip);
    }
}
