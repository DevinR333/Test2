package maple.ui;

import com.badlogic.gdx.Preferences;
import maple.gfx.Animation;

import java.util.ArrayList;
import java.util.List;

/**
 * The interface on the 800x600 client screen: the HUD layer, the windows above it (last = front),
 * the cursor and tooltips. Turns screen pointer/touch input into widget clicks; anything not on the
 * interface goes to {@link #worldClick} (talk to NPCs, pick targets...).
 */
public final class Ui {
    /** Interface height is always the client's 600. Width: 800 (4:3 original) or wider (16:9 / fill). */
    public static final int H = 600;
    public static float W = 800;

    public enum Aspect { ORIGINAL_4_3, WIDE_16_9, FILL }
    public Aspect aspect = Aspect.ORIGINAL_4_3;

    public final UiAssets assets;
    public final UiDraw g;
    /** Always-there interface below the windows (status bar, chat, quick slots, minimap...). */
    public final Widget hud = new Widget(0, 0, W, H);
    private final List<Window> windows = new ArrayList<>();
    /** Above the windows: notices, dialogs that must stay on top. */
    public final Widget overlay = new Widget(0, 0, W, H);
    private Preferences prefs;

    // Screen mapping: screen pixel = offset + ui pixel * scale
    public float scale = 1, offsetX, offsetY;

    // Pointer
    public float mouseX = -1, mouseY = -1;
    private Widget pressed, hovered;
    private int pressedPointer = -1;
    private Widget lastClick;
    private long lastClickTime;
    public boolean mouseVisible;
    /** A touchscreen device: a single tap talks to an NPC even if a stylus or mouse has hovered. */
    public boolean touchDevice;
    /** Show the cursor at the last tapped point (a touch mouse button was used). */
    public boolean touchCursor;
    private long time;
    private final Animation[] cursor = new Animation[13];
    private int cursorState;
    private long cursorStart;

    /** Something carried by the cursor (an item or skill being dragged). */
    public Carry carry;

    public interface WorldClick {
        /** A press on the game world at UI coords; return true if it was used. */
        boolean click(float x, float y, boolean doubleClick);
        /** Cursor over the world: true if something clickable (NPC) is there. */
        boolean hover(float x, float y);
    }

    public WorldClick worldClick;

    /** Something being dragged with the cursor (item, skill, key binding). */
    public abstract static class Carry {
        /** True once the pointer moved while pressed: dropping happens on release. */
        public boolean dragged;
        public abstract void draw(UiDraw g, float x, float y);
        /** Dropped on the world (outside every window). */
        public void droppedOnWorld(float x, float y) {}
        /** Dropped on a widget: true if it took it. */
        public boolean droppedOn(Widget w, float lx, float ly) {
            return w instanceof DropTarget && ((DropTarget) w).drop(this, lx, ly);
        }
    }

    public interface DropTarget {
        boolean drop(Carry c, float lx, float ly);
    }

    public Ui(UiAssets assets, UiDraw g) {
        this.assets = assets;
        this.g = g;
        for (int i = 0; i < cursor.length; i++) {
            Animation a = assets.animation("Basic.img/Cursor/" + i);
            cursor[i] = a.isEmpty() ? null : a;
        }
    }

    public void setPrefs(Preferences p) {
        prefs = p;
    }

    /** Fits the 800x600 screen into the real screen (letterboxed), returns nothing. */
    public void layout(float screenW, float screenH) {
        float a = screenW / Math.max(1f, screenH);
        float want;
        switch (aspect) {
            case WIDE_16_9: want = Math.round(H * 16f / 9f); break;
            case FILL: want = Math.round(H * a); break;
            default: want = 800;
        }
        W = Math.max(800, Math.min(want, Math.round(H * Math.max(a, 4f / 3f))));
        hud.w = overlay.w = W;
        scale = Math.min(screenW / W, screenH / H);
        offsetX = (screenW - W * scale) / 2f;
        offsetY = (screenH - H * scale) / 2f;
        Fonts.setScale(scale);
    }

    public float toUiX(float sx) { return (sx - offsetX) / scale; }
    public float toUiY(float sy) { return (sy - offsetY) / scale; }

    public boolean onScreen(float sx, float sy) {
        float x = toUiX(sx), y = toUiY(sy);
        return x >= 0 && y >= 0 && x < W && y < H;
    }

    // ---- windows ----

    public List<Window> windows() { return windows; }

    public <T extends Window> T find(Class<T> type) {
        for (Window w : windows) if (type.isInstance(w)) return type.cast(w);
        return null;
    }

    public Window find(String name) {
        for (Window w : windows) if (w.name.equals(name)) return w;
        return null;
    }

    public boolean isOpen(String name) { return find(name) != null; }

    /** Shows a window (or brings it to the front). New windows open where they were last left. */
    public void open(Window w) {
        if (windows.contains(w)) {
            front(w);
            return;
        }
        if (!restorePosition(w) && !w.keepPosition) {
            int n = 0;
            for (Window o : windows) if (!o.modal) n++;
            w.x = Math.round((W - w.w) / 2f + n * 18);
            w.y = 70 + n * 18;
        }
        clampOnScreen(w);
        windows.add(w);
        w.opened();
        w.refresh();
    }

    public void toggle(Window w) {
        if (windows.contains(w)) close(w);
        else open(w);
    }

    public void close(Window w) {
        if (!windows.remove(w)) return;
        if (pressed != null && pressed.window() == w) pressed = null;
        if (hovered != null && hovered.window() == w) hovered = null;
        w.closed();
    }

    public void front(Window w) {
        if (windows.remove(w)) windows.add(w);
        // a modal window always stays in front
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window m = windows.get(i);
            if (m.modal && m != w) {
                windows.remove(i);
                windows.add(m);
                break;
            }
        }
    }

    public Window frontWindow() {
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    public Window modal() {
        for (int i = windows.size() - 1; i >= 0; i--) if (windows.get(i).modal) return windows.get(i);
        return null;
    }

    /** Closes the front window (Esc). True if one was closed. */
    public boolean closeFront() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window w = windows.get(i);
            close(w);
            return true;
        }
        return false;
    }

    public void refreshAll() {
        for (int i = 0; i < windows.size(); i++) windows.get(i).refresh();
        refreshWidgets(hud);
        refreshWidgets(overlay);
    }

    private void refreshWidgets(Widget w) {
        if (w instanceof Refreshable) ((Refreshable) w).refresh();
        for (int i = 0; i < w.children.size(); i++) refreshWidgets(w.children.get(i));
    }

    public interface Refreshable {
        void refresh();
    }

    private void clampOnScreen(Window w) {
        w.x = Math.max(0, Math.min(W - w.w, w.x));
        w.y = Math.max(0, Math.min(H - Math.min(w.h, 100), w.y));
    }

    void rememberPosition(Window w) {
        if (prefs == null) return;
        prefs.putString("win." + w.name, Math.round(w.x) + "," + Math.round(w.y));
        prefs.flush();
    }

    private boolean restorePosition(Window w) {
        if (prefs == null || w.modal) return false;
        String s = prefs.getString("win." + w.name, "");
        if (s.isEmpty()) return false;
        try {
            String[] p = s.split(",");
            w.x = Integer.parseInt(p[0]);
            w.y = Integer.parseInt(p[1]);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- input ----

    /** Widget under a UI point: front window first, then the overlay, then the HUD. */
    public Widget widgetAt(float x, float y) {
        Widget h = overlay.hit(x, y);
        if (h != null) return h;
        Window m = modal();
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window w = windows.get(i);
            if (!w.visible) continue;
            Widget hit = w.hit(x - w.x, y - w.y);
            if (hit != null) return hit;
            if (m != null) return null; // a modal window blocks everything behind it
        }
        if (m != null) return null;
        return hud.hit(x, y);
    }

    /** Pointer pressed at screen coords. True if the interface took it. */
    public boolean pointerDown(int pointer, float sx, float sy, boolean rightButton) {
        float x = toUiX(sx), y = toUiY(sy);
        mouseX = x;
        mouseY = y;
        if (pressed != null) return true; // one pointer at a time on the UI
        Widget w = widgetAt(x, y);
        if (w == null) {
            if (modal() != null) return onScreen(sx, sy);
            if (carry != null) {
                Carry c = carry;
                carry = null;
                c.droppedOnWorld(x, y);
                return true;
            }
            if (worldClick != null && onScreen(sx, sy)) {
                long now = System.currentTimeMillis();
                boolean dbl = lastClick == null && now - lastClickTime < 400;
                lastClick = null;
                lastClickTime = now;
                return worldClick.click(x, y, dbl);
            }
            return false;
        }
        Window win = w.window();
        if (win != null) front(win);
        float lx = x - absX(w), ly = y - absY(w);
        if (rightButton) {
            w.onRightClick(lx, ly);
            return true;
        }
        long now = System.currentTimeMillis();
        boolean dbl = w == lastClick && now - lastClickTime < 400;
        lastClick = w;
        lastClickTime = now;
        if (dbl && (carry == null || !carry.dragged)) {
            carry = null;
            w.onDoubleClick(lx, ly);
            lastClick = null;
            return true;
        }
        if (carry != null) {
            Carry c = carry;
            carry = null;
            dropCarry(c, x, y);
            return true;
        }
        if (dbl) {
            w.onDoubleClick(lx, ly);
            lastClick = null;
            return true;
        }
        pressX = x;
        pressY = y;
        if (w.onPress(lx, ly)) {
            pressed = w;
            pressedPointer = pointer;
        }
        setHover(w);
        cursorPress();
        return true;
    }

    private float pressX, pressY;

    private void dropCarry(Carry c, float x, float y) {
        Widget w = widgetAt(x, y);
        if (w == null) {
            c.droppedOnWorld(x, y);
            return;
        }
        Widget t = w;
        while (t != null && !(t instanceof DropTarget)) t = t.parent;
        if (t != null) c.droppedOn(t, x - absX(t), y - absY(t));
        UiSounds.play("DragEnd");
    }

    public boolean pointerMove(int pointer, float sx, float sy) {
        float x = toUiX(sx), y = toUiY(sy);
        mouseX = x;
        mouseY = y;
        if (carry != null && pressed != null && Math.abs(x - pressX) + Math.abs(y - pressY) > 6) carry.dragged = true;
        if (pressed != null && pointer == pressedPointer) {
            pressed.onDrag(x - absX(pressed), y - absY(pressed));
            setHover(pressed.contains(x - absX(pressed), y - absY(pressed)) ? pressed : null);
            return true;
        }
        return false;
    }

    public boolean pointerUp(int pointer, float sx, float sy) {
        float x = toUiX(sx), y = toUiY(sy);
        mouseX = x;
        mouseY = y;
        if (pressed == null || pointer != pressedPointer) return false;
        Widget p = pressed;
        pressed = null;
        pressedPointer = -1;
        float lx = x - absX(p), ly = y - absY(p);
        p.onRelease(lx, ly, p.contains(lx, ly));
        if (carry != null && carry.dragged) {
            // drag-and-drop: drop where the finger/mouse was released
            Carry c = carry;
            carry = null;
            dropCarry(c, x, y);
        }
        cursorRelease();
        if (!mouseVisible) setHover(null); // touch: no hover after the finger lifts
        return true;
    }

    /** Mouse moved with no button (desktop): hover effects and the cursor. */
    public void mouseMoved(float sx, float sy) {
        mouseVisible = true;
        mouseX = toUiX(sx);
        mouseY = toUiY(sy);
        if (pressed == null) setHover(widgetAt(mouseX, mouseY));
    }

    public boolean scrolled(float sx, float sy, int amount) {
        float x = toUiX(sx), y = toUiY(sy);
        Widget w = widgetAt(x, y);
        while (w != null) {
            if (w.onScroll(x - absX(w), y - absY(w), amount)) return true;
            w = w.parent;
        }
        return false;
    }

    /** True when a press at these screen coords would land on the interface. */
    public boolean wantsPointer(float sx, float sy) {
        return widgetAt(toUiX(sx), toUiY(sy)) != null || modal() != null && onScreen(sx, sy);
    }

    public boolean keyDown(int keycode) {
        Window f = frontWindow();
        if (f != null && f.onKey(keycode)) return true;
        return false;
    }

    public boolean busy() { return pressed != null || carry != null; }

    public void cancelCarry() { carry = null; }

    private void setHover(Widget w) {
        if (w == hovered) return;
        if (hovered != null) hovered.onHover(false);
        hovered = w;
        if (hovered != null) hovered.onHover(true);
    }

    private static float absX(Widget w) { return w.screenX(); }
    private static float absY(Widget w) { return w.screenY(); }

    // ---- cursor ----

    private void cursorPress() {
        setCursor(12);
    }

    private void cursorRelease() {
        setCursor(0);
    }

    private void setCursor(int s) {
        if (s == cursorState) return;
        cursorState = s;
        cursorStart = time;
    }

    private int hoverCursor() {
        if (pressed != null) return 12;
        if (hovered != null) {
            if (hovered instanceof Window) {
                Window w = (Window) hovered;
                return w.grabbable(mouseX - w.screenX(), mouseY - w.screenY()) ? 5 : 0;
            }
            return hovered.interactive() ? 4 : 0;
        }
        if (worldClick != null && mouseX >= 0 && worldClick.hover(mouseX, mouseY)) return 4;
        return 0;
    }

    // ---- frame ----

    public void update(long ms) {
        time += ms;
        hud.update(ms);
        for (int i = 0; i < windows.size(); i++) windows.get(i).update(ms);
        overlay.update(ms);
        if (mouseVisible) {
            Widget h = pressed != null ? pressed : widgetAt(mouseX, mouseY);
            if (pressed == null) setHover(h);
            setCursor(hoverCursor());
        }
    }

    /** Draws the HUD, windows, overlay, carried item, tooltip and cursor. Batch must be begun via g. */
    public void draw() {
        g.timeMs = time;
        drawLayer(hud);
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i);
            if (!w.visible) continue;
            g.tx = w.x;
            g.ty = w.y;
            w.draw(g);
            g.tx = g.ty = 0;
        }
        drawLayer(overlay);
        if (carry != null && mouseX >= 0) carry.draw(g, mouseX, mouseY);
        if (hovered != null && pressed == null && carry == null) {
            Tooltip t = hovered.tooltip(mouseX - absX(hovered), mouseY - absY(hovered));
            if (t != null) t.draw(g, mouseX, mouseY);
        }
        if ((mouseVisible || touchCursor) && mouseX >= 0) {
            Animation c = cursor[cursorState] != null ? cursor[cursorState] : cursor[0];
            if (c != null) g.anim(c, mouseX, mouseY, time - cursorStart);
        }
    }

    private void drawLayer(Widget layer) {
        g.tx = layer.x;
        g.ty = layer.y;
        layer.draw(g);
        g.tx = g.ty = 0;
    }
}
