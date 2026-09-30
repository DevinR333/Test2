package maple.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Input as keyboard keys. A physical keyboard works as in the PC client (arrows move, Alt jumps...).
 * On a touch screen, each on-screen button presses the key it is set to. The player edits the layout
 * with the gear button: add, move, resize, re-key, fade or delete buttons.
 */
public final class Controls extends InputAdapter {
    // What the game reads (derived from keys each frame).
    public boolean left, right, up, down, jump;
    public boolean upPressed, downPressed, jumpPressed;

    private final boolean[] keys = new boolean[256];
    private final boolean[] prevKeys = new boolean[256];
    private final boolean[] pressedNow = new boolean[256];

    /** Developer shortcuts on a PC keyboard (not part of the game UI). */
    public boolean debugToggle, travelToggle;

    private TouchLayout layout = TouchLayout.defaults();
    private Preferences prefs;
    private float sw = 1, sh = 1, uiScale = 1;

    // Editor state
    public boolean editing;
    private int selected = -1;
    private int dragging = -1;
    private float dragDX, dragDY;
    private boolean picker;
    private int pickerTarget = -1; // -1 = add a new button
    private final GlyphLayout glyphs = new GlyphLayout();

    public void load(Preferences p) {
        prefs = p;
        layout = TouchLayout.load(p);
    }

    public void layout(float w, float h, float scale) {
        sw = w;
        sh = h;
        uiScale = scale;
    }

    private float uw() { return sw / uiScale; }
    private float uh() { return sh / uiScale; }

    public boolean isDown(int key) { return key >= 0 && key < 256 && keys[key]; }
    public boolean justPressed(int key) { return key >= 0 && key < 256 && pressedNow[key]; }

    public void poll() {
        java.util.Arrays.fill(keys, false);
        Input in = Gdx.input;
        for (int k = 0; k < 256; k++) if (in.isKeyPressed(k)) keys[k] = true;
        if (!editing) {
            for (int i = 0; i < 10; i++) {
                if (!in.isTouched(i)) continue;
                float x = in.getX(i) / uiScale, y = in.getY(i) / uiScale;
                if (hitGear(x, y)) continue;
                pressTouch(x, y);
            }
        }
        for (int k = 0; k < 256; k++) {
            pressedNow[k] = keys[k] && !prevKeys[k];
            prevKeys[k] = keys[k];
        }
        debugToggle = in.isKeyJustPressed(Input.Keys.F9);
        travelToggle = in.isKeyJustPressed(Input.Keys.F10);

        left = keys[Input.Keys.LEFT];
        right = keys[Input.Keys.RIGHT];
        up = keys[Input.Keys.UP];
        down = keys[Input.Keys.DOWN];
        jump = keys[Input.Keys.ALT_LEFT] || keys[Input.Keys.ALT_RIGHT];
        upPressed = justPressed(Input.Keys.UP);
        downPressed = justPressed(Input.Keys.DOWN);
        jumpPressed = justPressed(Input.Keys.ALT_LEFT) || justPressed(Input.Keys.ALT_RIGHT);
    }

    private void pressTouch(float x, float y) {
        // topmost (last drawn) item wins
        for (int i = layout.items.size() - 1; i >= 0; i--) {
            TouchLayout.Item it = layout.items.get(i);
            float cx = it.cx * uw(), cy = it.cy * uh(), r = it.size * uh() / 2;
            float dx = x - cx, dy = y - cy;
            float d2 = dx * dx + dy * dy;
            if (it.isDpad()) {
                if (d2 > r * r * 1.5f) continue;
                if (d2 > (r * 0.2f) * (r * 0.2f)) {
                    double ang = Math.toDegrees(Math.atan2(dy, dx)); // 0 = right, 90 = down
                    if (ang > -67.5 && ang < 67.5) keys[Input.Keys.RIGHT] = true;
                    if (ang > 112.5 || ang < -112.5) keys[Input.Keys.LEFT] = true;
                    if (ang > 22.5 && ang < 157.5) keys[Input.Keys.DOWN] = true;
                    if (ang < -22.5 && ang > -157.5) keys[Input.Keys.UP] = true;
                }
                return;
            }
            if (d2 <= r * r * 1.2f) {
                if (it.key >= 0 && it.key < 256) keys[it.key] = true;
                return;
            }
        }
    }

    /** Edge flags fire on the first physics tick of a frame only. */
    public void consumeEdges() {
        upPressed = downPressed = jumpPressed = false;
    }

    // ---- gear + editor ----

    private float gearX() { return uw() - 34; }
    private float gearY() { return 30; }

    private boolean hitGear(float x, float y) {
        float dx = x - gearX(), dy = y - gearY();
        return dx * dx + dy * dy < 26 * 26;
    }

    private String[] toolbar() {
        if (selected >= 0) return new String[]{"Key", "Bigger", "Smaller", "Fade", "Delete", "Done"};
        return new String[]{"Add button", "Reset", "Done"};
    }

    private float toolX(int i, int n) {
        float w = 110, gap = 8;
        float total = n * w + (n - 1) * gap;
        return (uw() - total) / 2 + i * (w + gap);
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        float x = screenX / uiScale, y = screenY / uiScale;
        if (!editing) {
            if (hitGear(x, y)) {
                editing = true;
                selected = -1;
                return true;
            }
            return false;
        }
        if (picker) {
            int k = pickerHit(x, y);
            if (k == Integer.MIN_VALUE) {
                picker = false;
            } else if (k != Integer.MAX_VALUE) {
                if (pickerTarget < 0) {
                    TouchLayout.Item it = new TouchLayout.Item(k, 0.5f, 0.5f, k == TouchLayout.DPAD ? 0.4f : 0.15f);
                    layout.items.add(it);
                    selected = layout.items.size() - 1;
                } else {
                    layout.items.get(pickerTarget).key = k;
                }
                picker = false;
                save();
            }
            return true;
        }
        String[] tb = toolbar();
        if (y >= 10 && y <= 50) {
            for (int i = 0; i < tb.length; i++) {
                float bx = toolX(i, tb.length);
                if (x >= bx && x <= bx + 110) {
                    toolbarAction(tb[i]);
                    return true;
                }
            }
        }
        for (int i = layout.items.size() - 1; i >= 0; i--) {
            TouchLayout.Item it = layout.items.get(i);
            float cx = it.cx * uw(), cy = it.cy * uh(), r = it.size * uh() / 2;
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                selected = i;
                dragging = i;
                dragDX = x - cx;
                dragDY = y - cy;
                return true;
            }
        }
        selected = -1;
        return true;
    }

    private void toolbarAction(String a) {
        switch (a) {
            case "Add button": picker = true; pickerTarget = -1; break;
            case "Reset": layout = TouchLayout.defaults(); selected = -1; save(); break;
            case "Done": editing = false; selected = -1; picker = false; save(); break;
            case "Key": picker = true; pickerTarget = selected; break;
            case "Bigger": resize(1.15f); break;
            case "Smaller": resize(1 / 1.15f); break;
            case "Fade": {
                TouchLayout.Item it = layout.items.get(selected);
                it.alpha = it.alpha <= 0.15f ? 0.8f : it.alpha - 0.15f;
                save();
                break;
            }
            case "Delete": layout.items.remove(selected); selected = -1; save(); break;
            default: break;
        }
    }

    private void resize(float f) {
        TouchLayout.Item it = layout.items.get(selected);
        it.size = Math.max(0.06f, Math.min(0.8f, it.size * f));
        save();
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!editing || dragging < 0 || dragging >= layout.items.size()) return editing;
        float x = screenX / uiScale, y = screenY / uiScale;
        TouchLayout.Item it = layout.items.get(dragging);
        it.cx = Math.max(0, Math.min(1, (x - dragDX) / uw()));
        it.cy = Math.max(0, Math.min(1, (y - dragDY) / uh()));
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (dragging >= 0) {
            dragging = -1;
            save();
        }
        return editing;
    }

    @Override
    public boolean keyDown(int keycode) {
        if (keycode == Input.Keys.BACK && editing) {
            if (picker) picker = false;
            else { editing = false; save(); }
            return true;
        }
        return false;
    }

    private void save() {
        if (prefs != null) layout.save(prefs);
    }

    // picker grid
    private static final float CELL_W = 70, CELL_H = 40;

    private int pickerCols() { return Math.max(4, (int) ((uw() - 40) / CELL_W)); }

    /** Key under (x,y); MIN_VALUE = cancel; MAX_VALUE = nothing. */
    private int pickerHit(float x, float y) {
        int cols = pickerCols();
        float left = (uw() - cols * CELL_W) / 2, top = 70;
        if (y < top - 10) return Integer.MIN_VALUE;
        int col = (int) ((x - left) / CELL_W), row = (int) ((y - top) / CELL_H);
        if (x < left || col >= cols) return Integer.MAX_VALUE;
        int idx = row * cols + col;
        if (idx < 0 || idx >= TouchLayout.PICKABLE.length) return Integer.MAX_VALUE;
        return TouchLayout.PICKABLE[idx];
    }

    // ---- drawing (UI units, y down) ----

    public void draw(ShapeRenderer sr, SpriteBatch batch, BitmapFont font) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        if (editing) {
            sr.begin(ShapeRenderer.ShapeType.Filled);
            sr.setColor(0, 0, 0, 0.45f);
            sr.rect(0, 0, uw(), uh());
            sr.end();
        }
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < layout.items.size(); i++) drawItem(sr, layout.items.get(i), i == selected);
        // gear
        sr.setColor(0, 0, 0, 0.35f);
        sr.circle(gearX(), gearY(), 20, 32);
        sr.setColor(1, 1, 1, 0.8f);
        for (int t = 0; t < 8; t++) {
            double a = Math.PI * 2 * t / 8;
            sr.rectLine(gearX(), gearY(), gearX() + (float) Math.cos(a) * 15, gearY() + (float) Math.sin(a) * 15, 5);
        }
        sr.setColor(0.2f, 0.2f, 0.2f, 1f);
        sr.circle(gearX(), gearY(), 6, 16);
        sr.end();
        if (editing) {
            sr.begin(ShapeRenderer.ShapeType.Line);
            for (int i = 0; i < layout.items.size(); i++) {
                TouchLayout.Item it = layout.items.get(i);
                sr.setColor(i == selected ? 1 : 0.8f, i == selected ? 0.85f : 0.8f, i == selected ? 0.1f : 0.8f, 1);
                sr.circle(it.cx * uw(), it.cy * uh(), it.size * uh() / 2, 40);
            }
            sr.end();
        }
        batch.begin();
        font.setColor(1, 1, 1, 0.9f);
        for (TouchLayout.Item it : layout.items) {
            if (it.isDpad()) continue;
            center(batch, font, TouchLayout.label(it.key), it.cx * uw(), it.cy * uh());
        }
        batch.end();
        if (editing) drawEditor(sr, batch, font);
    }

    private void drawItem(ShapeRenderer sr, TouchLayout.Item it, boolean sel) {
        float cx = it.cx * uw(), cy = it.cy * uh(), r = it.size * uh() / 2;
        float a = editing ? Math.max(0.35f, it.alpha) : it.alpha;
        if (it.isDpad()) {
            sr.setColor(0, 0, 0, a * 0.5f);
            sr.circle(cx, cy, r, 48);
            float off = r * 0.62f, s = r * 0.26f;
            arrow(sr, cx + off, cy, s, 0, keys[Input.Keys.RIGHT] ? a + 0.3f : a);
            arrow(sr, cx - off, cy, s, 180, keys[Input.Keys.LEFT] ? a + 0.3f : a);
            arrow(sr, cx, cy - off, s, 270, keys[Input.Keys.UP] ? a + 0.3f : a);
            arrow(sr, cx, cy + off, s, 90, keys[Input.Keys.DOWN] ? a + 0.3f : a);
        } else {
            boolean down = it.key >= 0 && it.key < 256 && keys[it.key];
            sr.setColor(1, 1, 1, Math.min(1, (down ? a + 0.25f : a) * 0.6f));
            sr.circle(cx, cy, r, 40);
        }
    }

    private void drawEditor(ShapeRenderer sr, SpriteBatch batch, BitmapFont font) {
        String[] tb = toolbar();
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < tb.length; i++) {
            sr.setColor(0.15f, 0.35f, 0.75f, 0.95f);
            sr.rect(toolX(i, tb.length), 10, 110, 40);
        }
        if (picker) {
            sr.setColor(0.05f, 0.07f, 0.12f, 0.96f);
            sr.rect(0, 0, uw(), uh());
            int cols = pickerCols();
            float left = (uw() - cols * CELL_W) / 2, top = 70;
            for (int i = 0; i < TouchLayout.PICKABLE.length; i++) {
                float x = left + (i % cols) * CELL_W, y = top + (i / cols) * CELL_H;
                sr.setColor(0.2f, 0.25f, 0.35f, 1);
                sr.rect(x + 2, y + 2, CELL_W - 4, CELL_H - 4);
            }
        }
        sr.end();
        batch.begin();
        font.setColor(1, 1, 1, 1);
        if (picker) {
            font.draw(batch, pickerTarget < 0 ? "Pick the key for the new button (tap the top to cancel)"
                    : "Pick a key for this button (tap the top to cancel)", 20, 24);
            int cols = pickerCols();
            float left = (uw() - cols * CELL_W) / 2, top = 70;
            for (int i = 0; i < TouchLayout.PICKABLE.length; i++) {
                float x = left + (i % cols) * CELL_W, y = top + (i / cols) * CELL_H;
                center(batch, font, TouchLayout.label(TouchLayout.PICKABLE[i]), x + CELL_W / 2, y + CELL_H / 2);
            }
        } else {
            for (int i = 0; i < tb.length; i++) center(batch, font, tb[i], toolX(i, tb.length) + 55, 30);
            center(batch, font, selected >= 0 ? "Drag to move. Use the buttons above to change it."
                    : "Drag buttons to move them. Tap one to change its key, size or fade.", uw() / 2, 70);
        }
        batch.end();
    }

    private static void arrow(ShapeRenderer sr, float cx, float cy, float s, float deg, float alpha) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), n = (float) Math.sin(r);
        float tipX = cx + c * s, tipY = cy + n * s;
        float bx = cx - c * s * 0.6f, by = cy - n * s * 0.6f;
        float px = -n * s * 0.8f, py = c * s * 0.8f;
        sr.setColor(1, 1, 1, Math.min(1, alpha));
        sr.triangle(tipX, tipY, bx + px, by + py, bx - px, by - py);
    }

    private void center(SpriteBatch batch, BitmapFont font, String text, float x, float y) {
        glyphs.setText(font, text);
        font.draw(batch, text, x - glyphs.width / 2, y - glyphs.height / 2);
    }
}
