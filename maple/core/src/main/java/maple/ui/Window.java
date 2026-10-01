package maple.ui;

import maple.gfx.Sprite;

/**
 * A game window (Item, Equip, Stat...): its UIWindow.img background, a 20px drag strip along the top
 * and the Basic.img close button at (width-17, 6), as in the v83 client.
 */
public class Window extends Widget {
    public final String name;
    protected final Ui ui;
    protected Sprite background;
    public boolean draggable = true;
    public boolean modal;
    /** Opens where the constructor put it (HUD popups, the minimap, dialogs) instead of cascading. */
    public boolean keepPosition;
    /** Height of the drag strip; the right 24px stay free for the close button. */
    public float dragHeight = 20;
    protected Button close;

    public Window(Ui ui, String name, String backgroundPath) {
        this.ui = ui;
        this.name = name;
        if (backgroundPath != null) {
            background = ui.assets.sprite(backgroundPath);
            if (background != null) {
                w = background.w;
                h = background.h;
            }
        }
    }

    /** Adds the standard close button. */
    protected void addClose() {
        close = add(new Button(ui.assets, "Basic.img/BtClose", w - 17, 6, this::close));
    }

    public void close() {
        ui.close(this);
    }

    /** Called when opened (and when brought back). */
    public void opened() {}

    /** Called after it is removed from the screen. */
    public void closed() {}

    /** Game data changed: refresh what the window shows. */
    public void refresh() {}

    @Override
    public void draw(UiDraw g) {
        if (background != null) g.image(background, 0, 0);
        drawContent(g);
        drawChildren(g);
        drawOver(g);
    }

    /** Drawn above the background, below the buttons. */
    protected void drawContent(UiDraw g) {}

    /** Drawn above everything else in the window. */
    protected void drawOver(UiDraw g) {}

    @Override
    public Widget hit(float lx, float ly) {
        Widget h = super.hit(lx, ly);
        if (h != null) return h;
        if (contains(lx, ly)) return this; // windows swallow clicks on their background
        return null;
    }

    @Override
    public boolean interactive() { return true; }

    private float grabX, grabY;
    private boolean dragging;

    @Override
    public boolean onPress(float lx, float ly) {
        if (draggable && ly < dragHeight && lx < w - 24) {
            dragging = true;
            grabX = lx;
            grabY = ly;
            UiSounds.play("DragStart");
            return true;
        }
        return pressBody(lx, ly);
    }

    /** A press on the window body (not the title strip). Return true to receive drag/release. */
    protected boolean pressBody(float lx, float ly) { return false; }

    @Override
    public void onDrag(float lx, float ly) {
        if (!dragging) {
            dragBody(lx, ly);
            return;
        }
        x = Math.max(-w + 40, Math.min(Ui.W - 40, x + lx - grabX));
        y = Math.max(0, Math.min(600 - 20, y + ly - grabY));
    }

    protected void dragBody(float lx, float ly) {}

    @Override
    public void onRelease(float lx, float ly, boolean inside) {
        if (dragging) {
            dragging = false;
            UiSounds.play("DragEnd");
            ui.rememberPosition(this);
            return;
        }
        releaseBody(lx, ly, inside);
    }

    protected void releaseBody(float lx, float ly, boolean inside) {}

    public boolean grabbable(float lx, float ly) {
        return draggable && ly < dragHeight && lx < w - 24;
    }

    /** A key pressed while this window is in front. True if used. */
    public boolean onKey(int keycode) { return false; }
}
