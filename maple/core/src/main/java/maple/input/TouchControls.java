package maple.input;

import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Sprite;
import maple.ui.KeyMap;
import maple.ui.UiDraw;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen controls for touch screens: an analog stick for the arrow keys and round buttons that each
 * press one v83 key slot, showing the icon of whatever that key is bound to. The gear button opens the
 * editor: drag to move, and Key / Bigger / Smaller / Fade / Delete / Add / Reset. Each control is anchored
 * to its nearer side of the screen: its distance from that side and its size are kept in screen
 * heights, its height as a fraction, so it keeps its shape and spacing on any screen (4:3 or wide).
 * Besides keys, a button can be a mouse action (left/right/middle click, wheel up/down) done at the
 * last tapped point.
 */
public final class TouchControls {
    public static final class Item {
        public boolean stick;
        public boolean right; // anchored to the right side of the screen
        public float ox; // distance of the centre from that side, in screen heights
        public float cy, size; // fraction of the screen height; diameter / screen height
        public int slot;
        public float alpha = 1f;

        Item(boolean stick, boolean right, float ox, float cy, float size, int slot) {
            this.stick = stick;
            this.right = right;
            this.ox = ox;
            this.cy = cy;
            this.size = size;
            this.slot = slot;
        }
    }

    /** Mouse actions a button can do instead of a key (slots past the keyboard's 90). */
    public static final int LEFT_CLICK = 100, RIGHT_CLICK = 101, MIDDLE_CLICK = 102, WHEEL_UP = 103, WHEEL_DOWN = 104;

    public static boolean isMouse(int slot) { return slot >= LEFT_CLICK && slot <= WHEEL_DOWN; }

    public static String mouseName(int slot) {
        switch (slot) {
            case LEFT_CLICK: return "L-Click";
            case RIGHT_CLICK: return "R-Click";
            case MIDDLE_CLICK: return "M-Click";
            case WHEEL_UP: return "Wheel Up";
            case WHEEL_DOWN: return "Wheel Dn";
            default: return null;
        }
    }

    /** Does a mouse button's action. */
    public interface MouseListener {
        void mouse(int slot);
    }

    public MouseListener mouseListener;

    /** What a key slot is bound to, for the button's picture. */
    public interface Bindings {
        Sprite icon(int slot);
        boolean anchoredIcon(int slot);
    }

    public final List<Item> items = new ArrayList<>();
    private Preferences prefs;
    // full screen in UI units
    private float left, top, width = 800, height = 600;
    public boolean enabled = true;

    // live input
    private final Item[] owner = new Item[20];
    private final boolean[] held = new boolean[90];
    private float stickX, stickY;
    private int stickPointer = -1;

    // editor
    public boolean editing;
    private int selected = -1;
    private int dragPointer = -1;
    private float dragDX, dragDY;
    private boolean picker;
    private boolean addAfterPick;
    private Texture circle;

    public void load(Preferences p) {
        prefs = p;
        items.clear();
        // version 2: side-anchored; an older (screen-fraction) layout is replaced by the defaults
        String s = p == null ? "" : p.getString("touch.layout2", "");
        if (!s.isEmpty()) {
            for (String part : s.split(";")) {
                String[] f = part.split(",");
                if (f.length < 7) continue;
                try {
                    Item it = new Item(f[0].equals("s"), f[1].equals("r"), Float.parseFloat(f[2]), Float.parseFloat(f[3]),
                            Float.parseFloat(f[4]), Integer.parseInt(f[5]));
                    it.alpha = Float.parseFloat(f[6]);
                    items.add(it);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (items.isEmpty()) defaults();
    }

    public void save() {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(';');
            sb.append(it.stick ? "s" : "b").append(',').append(it.right ? "r" : "l").append(',').append(it.ox).append(',')
                    .append(it.cy).append(',').append(it.size).append(',').append(it.slot).append(',').append(it.alpha);
        }
        prefs.putString("touch.layout2", sb.toString());
        prefs.flush();
    }

    /** Stick on the left; Attack, Jump, Pick up, NPC chat and the quick-slot keys on the right. */
    public void defaults() {
        items.clear();
        // Kept above the status bar and quick slots (the bottom ~25% on the right, ~12% elsewhere) so
        // those stay tappable; in side-bar layouts the right column sits in the bar.
        items.add(new Item(true, false, 0.19f, 0.64f, 0.30f, -1));
        items.add(new Item(false, true, 0.135f, 0.62f, 0.20f, 29)); // Ctrl: attack
        items.add(new Item(false, true, 0.31f, 0.66f, 0.15f, 56)); // Alt: jump
        items.add(new Item(false, true, 0.30f, 0.50f, 0.12f, 44)); // Z: pick up
        items.add(new Item(false, true, 0.135f, 0.44f, 0.12f, 57)); // Space: NPC chat
        items.add(new Item(false, true, 0.30f, 0.36f, 0.10f, 42)); // Shift
        items.add(new Item(false, true, 0.135f, 0.29f, 0.10f, 82)); // Ins
        items.add(new Item(false, true, 0.30f, 0.23f, 0.10f, 71)); // Home
        items.add(new Item(false, true, 0.135f, 0.16f, 0.10f, 73)); // PgUp
        items.add(new Item(false, false, 0.11f, 0.33f, 0.10f, 28)); // Enter: chat
    }

    /** The whole screen in UI units (it extends past the 800x600 game area into the side bars). */
    public void layout(float left, float top, float width, float height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    private float x(Item it) { return it.right ? left + width - it.ox * height : left + it.ox * height; }
    private float y(Item it) { return top + it.cy * height; }
    private float r(Item it) { return it.size * height / 2; }

    private float gearX() { return left + width - 30; }
    private float gearY() { return top + 28; }

    /** The Edit (gear) button is under this point. */
    public boolean gearAt(float ux, float uy) {
        return enabled && onGear(ux, uy);
    }

    /** A touch button or the stick is under this point (UI units). */
    public boolean covers(float ux, float uy) {
        return enabled && itemAt(ux, uy) != null;
    }

    private boolean onGear(float ux, float uy) {
        float dx = ux - gearX(), dy = uy - gearY();
        return dx * dx + dy * dy < 24 * 24;
    }

    // ------------------------------------------------------------------ state for the game

    public boolean held(int slot) {
        return slot >= 0 && slot < 90 && held[slot];
    }

    public boolean left() { return stickX < -0.4f; }
    public boolean right() { return stickX > 0.4f; }
    public boolean up() { return stickY < -0.5f; }
    public boolean down() { return stickY > 0.5f; }

    public void releaseAll() {
        java.util.Arrays.fill(owner, null);
        java.util.Arrays.fill(held, false);
        stickX = stickY = 0;
        stickPointer = -1;
    }

    private void recomputeHeld() {
        java.util.Arrays.fill(held, false);
        for (Item it : owner) if (it != null && !it.stick && it.slot >= 0 && it.slot < 90) held[it.slot] = true;
    }

    // ------------------------------------------------------------------ pointers (UI units)

    /** True if the touch belongs to the controls (the game/UI must not see it). */
    public boolean touchDown(int pointer, float ux, float uy) {
        if (!enabled || pointer >= owner.length) return false;
        if (onGear(ux, uy) && !picker) {
            editing = !editing;
            selected = -1;
            if (!editing) save();
            releaseAll();
            return true;
        }
        if (editing) {
            editorDown(pointer, ux, uy);
            return true;
        }
        Item hit = itemAt(ux, uy);
        if (hit == null) return false;
        if (!hit.stick && isMouse(hit.slot) && mouseListener != null) mouseListener.mouse(hit.slot);
        owner[pointer] = hit;
        if (hit.stick) {
            stickPointer = pointer;
            moveStick(hit, ux, uy);
        }
        recomputeHeld();
        return true;
    }

    public boolean touchDragged(int pointer, float ux, float uy) {
        if (pointer >= owner.length) return false;
        if (editing) {
            if (pointer == dragPointer && selected >= 0) {
                Item it = items.get(selected);
                float nx = Math.max(left, Math.min(left + width, ux - dragDX));
                it.right = nx > left + width / 2;
                it.ox = (it.right ? left + width - nx : nx - left) / height;
                it.cy = Math.max(0, Math.min(1, (uy - dragDY - top) / height));
            }
            return true;
        }
        Item it = owner[pointer];
        if (it == null) return false;
        if (it.stick) moveStick(it, ux, uy);
        else {
            // sliding a finger from one button to the next presses the new one
            Item now = itemAt(ux, uy);
            if (now != null && !now.stick && now != it && !isMouse(now.slot)) {
                owner[pointer] = now;
                recomputeHeld();
            }
        }
        return true;
    }

    public boolean touchUp(int pointer, float ux, float uy) {
        if (pointer >= owner.length) return false;
        if (editing) {
            if (pointer == dragPointer) dragPointer = -1;
            return true;
        }
        Item it = owner[pointer];
        if (it == null) return false;
        owner[pointer] = null;
        if (it.stick) {
            stickPointer = -1;
            stickX = stickY = 0;
        }
        recomputeHeld();
        return true;
    }

    private void moveStick(Item it, float ux, float uy) {
        float r = r(it);
        float dx = (ux - x(it)) / r, dy = (uy - y(it)) / r;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1) {
            dx /= len;
            dy /= len;
        }
        if (len < 0.25f) dx = dy = 0;
        stickX = dx;
        stickY = dy;
    }

    private Item itemAt(float ux, float uy) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            float dx = ux - x(it), dy = uy - y(it), r = r(it) * (it.stick ? 1.4f : 1.1f);
            if (dx * dx + dy * dy <= r * r) return it;
        }
        return null;
    }

    // ------------------------------------------------------------------ editor

    private String[] toolbar() {
        if (selected >= 0) {
            boolean stick = items.get(selected).stick;
            return stick ? new String[]{"Bigger", "Smaller", "Fade", "Delete", "Done"} : new String[]{"Key", "Bigger", "Smaller", "Fade", "Delete", "Done"};
        }
        for (Item it : items) if (it.stick) return new String[]{"Add button", "Reset", "Done"};
        return new String[]{"Add button", "Add stick", "Reset", "Done"};
    }

    private static final float TOOL_W = 100, TOOL_H = 36;

    private float toolX(int i, int n) {
        float total = n * TOOL_W + (n - 1) * 6;
        return left + (width - total) / 2 + i * (TOOL_W + 6);
    }

    private float toolY() { return top + 8; }

    private void editorDown(int pointer, float ux, float uy) {
        if (picker) {
            int slot = pickerAt(ux, uy);
            if (slot >= 0) {
                if (addAfterPick) {
                    Item it = new Item(false, false, width / height / 2, 0.5f, 0.13f, slot);
                    items.add(it);
                    selected = items.size() - 1;
                } else if (selected >= 0) {
                    items.get(selected).slot = slot;
                }
            }
            picker = false;
            addAfterPick = false;
            return;
        }
        String[] tools = toolbar();
        if (uy >= toolY() && uy < toolY() + TOOL_H) {
            for (int i = 0; i < tools.length; i++) {
                float tx = toolX(i, tools.length);
                if (ux >= tx && ux < tx + TOOL_W) {
                    tool(tools[i]);
                    return;
                }
            }
        }
        Item hit = itemAt(ux, uy);
        if (hit != null) {
            selected = items.indexOf(hit);
            dragPointer = pointer;
            dragDX = ux - x(hit);
            dragDY = uy - y(hit);
        } else {
            selected = -1;
        }
    }

    private void tool(String t) {
        Item it = selected >= 0 ? items.get(selected) : null;
        switch (t) {
            case "Key":
                picker = true;
                break;
            case "Bigger":
                if (it != null) it.size = Math.min(0.6f, it.size * 1.15f);
                break;
            case "Smaller":
                if (it != null) it.size = Math.max(0.05f, it.size / 1.15f);
                break;
            case "Fade":
                if (it != null) it.alpha = it.alpha > 0.9f ? 0.6f : it.alpha > 0.5f ? 0.35f : it.alpha > 0.25f ? 0.15f : 1f;
                break;
            case "Delete":
                if (it != null) items.remove(it);
                if (it != null && it.stick) {
                    stickX = stickY = 0;
                    stickPointer = -1;
                }
                selected = -1;
                break;
            case "Add button":
                picker = true;
                addAfterPick = true;
                break;
            case "Add stick":
                items.add(0, new Item(true, false, 0.19f, 0.64f, 0.30f, -1));
                selected = 0;
                break;
            case "Reset":
                defaults();
                selected = -1;
                break;
            case "Done":
                editing = false;
                selected = -1;
                save();
                break;
            default:
                break;
        }
    }

    // ---- for tests and tools: where things are (UI units) ----

    /** Centre of an editor toolbar button, or null if it is not shown. */
    public float[] toolCenter(String name) {
        String[] tools = toolbar();
        for (int i = 0; i < tools.length; i++) {
            if (tools[i].equals(name)) return new float[]{toolX(i, tools.length) + TOOL_W / 2, toolY() + TOOL_H / 2};
        }
        return null;
    }

    public float[] center(Item it) { return new float[]{x(it), y(it)}; }

    public float radius(Item it) { return r(it); }

    /** Centre of a key in the key picker. */
    public float[] pickerCenter(int slot) {
        for (int i = 0; i < PICK.length; i++) {
            if (PICK[i] == slot) {
                float[] c = pickCell(i);
                return new float[]{c[0] + c[2] / 2, c[1] + c[2] / 2};
            }
        }
        return null;
    }

    public boolean pickerOpen() { return picker; }

    public Item selectedItem() { return selected >= 0 && selected < items.size() ? items.get(selected) : null; }

    // The key picker shows the Key Config keyboard (cells 32px at its recovered coordinates).
    private static final int[] PICK = {1, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88, 41, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 43, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 28, 42, 44, 45, 46, 47,
            48, 49, 50, 51, 52, 53, 29, 56, 57, 82, 71, 73, 83, 79, 81, LEFT_CLICK, RIGHT_CLICK, MIDDLE_CLICK, WHEEL_UP, WHEEL_DOWN};

    private static final int PICK_COLS = 14;

    /** Keys as big as the screen allows (rows of 14 under the title). */
    private float pickSize() {
        int rows = (PICK.length + PICK_COLS - 1) / PICK_COLS;
        return Math.min((width - 24) / PICK_COLS, (height - 70) / rows);
    }

    private float[] pickCell(int i) {
        float cell = pickSize();
        float total = PICK_COLS * cell;
        float px = left + (width - total) / 2 + (i % PICK_COLS) * cell;
        float py = top + 56 + (i / PICK_COLS) * cell;
        return new float[]{px, py, cell - 4};
    }

    private int pickerAt(float ux, float uy) {
        for (int i = 0; i < PICK.length; i++) {
            float[] c = pickCell(i);
            if (ux >= c[0] && ux < c[0] + c[2] && uy >= c[1] && uy < c[1] + c[2]) return PICK[i];
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    private Texture circle() {
        if (circle == null) {
            int n = 128;
            Pixmap pm = new Pixmap(n, n, Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            float c = (n - 1) / 2f;
            for (int yy = 0; yy < n; yy++) {
                for (int xx = 0; xx < n; xx++) {
                    float d = (float) Math.sqrt((xx - c) * (xx - c) + (yy - c) * (yy - c));
                    float a = Math.max(0, Math.min(1, c - d + 0.5f));
                    int v = Math.round(a * 255); // premultiplied white
                    pm.drawPixel(xx, yy, (v << 24) | (v << 16) | (v << 8) | v);
                }
            }
            circle = new Texture(pm, true);
            circle.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            pm.dispose();
        }
        return circle;
    }

    private void disc(UiDraw g, float cx, float cy, float r, int rgb, float a) {
        float rr = ((rgb >> 16) & 0xFF) / 255f, gg = ((rgb >> 8) & 0xFF) / 255f, bb = (rgb & 0xFF) / 255f;
        g.artMode();
        g.batch.setColor(rr * a, gg * a, bb * a, a);
        g.batch.draw(circle(), cx - r, cy - r, r * 2, r * 2, 0, 0, 1, 1);
        g.batch.setColor(1, 1, 1, 1);
    }

    public void draw(UiDraw g, Bindings bindings) {
        if (!enabled) return;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float cx = x(it), cy = y(it), r = r(it);
            float a = it.alpha;
            boolean pressed = false;
            for (Item o : owner) if (o == it) pressed = true;
            boolean sel = editing && i == selected;
            if (it.stick) {
                disc(g, cx, cy, r + 2, 0xFFFFFF, 0.35f * a);
                disc(g, cx, cy, r, 0x101820, 0.45f * a);
                float kx = cx + stickX * r * 0.55f, ky = cy + stickY * r * 0.55f;
                if (stickPointer < 0) kx = cx;
                if (stickPointer < 0) ky = cy;
                disc(g, kx, ky, r * 0.42f + 2, 0xFFFFFF, 0.6f * a);
                disc(g, kx, ky, r * 0.42f, 0x3A4A5A, 0.8f * a);
            } else {
                disc(g, cx, cy, r + 2, sel ? 0xFFD040 : 0xFFFFFF, (sel ? 0.9f : 0.45f) * a);
                disc(g, cx, cy, r, pressed ? 0x506070 : 0x101820, 0.55f * a);
                Sprite icon = bindings == null ? null : bindings.icon(it.slot);
                if (icon != null) {
                    float s = Math.min(r * 1.25f / Math.max(icon.w, icon.h), r * 1.25f / 32f);
                    float iw = icon.w * s, ih = icon.h * s;
                    g.artMode();
                    g.batch.setColor(a, a, a, a);
                    g.stretched(icon, cx - iw / 2, cy - ih / 2, iw, ih);
                    g.batch.setColor(1, 1, 1, 1);
                }
                String name = KeyMap.slotName(it.slot);
                int size = r > 30 ? 12 : 11;
                int alpha = Math.round(255 * a);
                if (icon == null) {
                    g.text(name, cx - r, cy - 7, r * 2, Align.center, false, size, true, (alpha << 24) | 0xFFFFFF);
                } else {
                    g.text(name, cx - r, cy + r * 0.45f, r * 2, Align.center, false, 11, true, (alpha << 24) | 0xFFFFFF);
                }
            }
        }
        // gear
        disc(g, gearX(), gearY(), 20, 0xFFFFFF, editing ? 0.85f : 0.4f);
        disc(g, gearX(), gearY(), 18, 0x101820, 0.7f);
        g.text(editing ? "OK" : "Edit", gearX() - 20, gearY() - 7, 40, Align.center, false, 11, true, 0xFFFFFFFF);
        if (!editing) return;
        String[] tools = toolbar();
        for (int i = 0; i < tools.length; i++) {
            float tx = toolX(i, tools.length);
            g.fill(tx, toolY(), TOOL_W, TOOL_H, 0xE0203040);
            g.outline(tx, toolY(), TOOL_W, TOOL_H, 0xFFC0D0E0);
            g.text(tools[i], tx, toolY() + 10, TOOL_W, Align.center, false, 14, true, 0xFFFFFFFF);
        }
        if (selected < 0 && !picker) {
            g.text("Drag a control to move it. Tap one to change its key, size or fade.", left, toolY() + TOOL_H + 8, width, Align.center, false, 14, false, 0xFFFFFFFF);
        }
        if (picker) {
            g.fill(left, top, width, height, 0xC0000000);
            g.text(addAfterPick ? "Choose the key for the new button" : "Choose a key", left, top + 26, width, Align.center, false, 16, true, 0xFFFFFFFF);
            for (int i = 0; i < PICK.length; i++) {
                float[] c = pickCell(i);
                int slot = PICK[i];
                g.fill(c[0], c[1], c[2], c[2], 0xE0303A48);
                g.outline(c[0], c[1], c[2], c[2], 0xFF8090A0);
                Sprite icon = bindings == null ? null : bindings.icon(slot);
                int font = c[2] >= 60 ? 16 : c[2] >= 46 ? 14 : 12;
                String label = KeyMap.slotName(slot);
                if (icon != null) {
                    // icon above, label below
                    float room = c[2] - font - 8;
                    float s = Math.min(room / 32f, room / Math.max(icon.w, icon.h));
                    g.stretched(icon, c[0] + (c[2] - icon.w * s) / 2, c[1] + 3 + (room - icon.h * s) / 2, icon.w * s, icon.h * s);
                    g.text(label, c[0], c[1] + c[2] - font - 3, c[2], Align.center, false, font, true, 0xFFFFFFFF);
                } else {
                    // mouse actions are two words: smaller so they fit
                    int f = label.length() > 5 ? Math.max(11, font - 3) : font + 2;
                    g.text(label, c[0], c[1] + (c[2] - f) / 2 - 1, c[2], Align.center, false, f, true, 0xFFFFFFFF);
                }
            }
        }
    }

    public void dispose() {
        if (circle != null) circle.dispose();
        circle = null;
    }
}
