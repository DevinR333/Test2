package maple.ui;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's on-screen controls: a D-pad plus buttons that each press one keyboard key.
 * Positions are fractions of the screen so a layout works on any phone. Saved in Preferences.
 */
public final class TouchLayout {
    public static final int DPAD = -1;

    public static final class Item {
        public int key;          // libGDX key code, or DPAD
        public float cx, cy;     // centre, as a fraction of screen width / height
        public float size;       // diameter as a fraction of screen height
        public float alpha = 0.5f;

        Item(int key, float cx, float cy, float size) {
            this.key = key;
            this.cx = cx;
            this.cy = cy;
            this.size = size;
        }

        public boolean isDpad() { return key == DPAD; }
    }

    public final List<Item> items = new ArrayList<>();

    public static TouchLayout defaults() {
        TouchLayout l = new TouchLayout();
        l.items.add(new Item(DPAD, 0.13f, 0.72f, 0.42f));
        l.items.add(new Item(Input.Keys.ALT_LEFT, 0.90f, 0.80f, 0.20f));      // jump
        l.items.add(new Item(Input.Keys.CONTROL_LEFT, 0.78f, 0.86f, 0.17f));  // attack
        l.items.add(new Item(Input.Keys.Z, 0.80f, 0.64f, 0.13f));             // pick up
        return l;
    }

    public void save(Preferences p) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) {
            if (sb.length() > 0) sb.append(';');
            sb.append(i.key).append(',').append(i.cx).append(',').append(i.cy).append(',').append(i.size).append(',').append(i.alpha);
        }
        p.putString("touchLayout", sb.toString());
        p.flush();
    }

    public static TouchLayout load(Preferences p) {
        String s = p.getString("touchLayout", "");
        if (s.isEmpty()) return defaults();
        TouchLayout l = new TouchLayout();
        try {
            for (String part : s.split(";")) {
                String[] f = part.split(",");
                Item i = new Item(Integer.parseInt(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3]));
                if (f.length > 4) i.alpha = Float.parseFloat(f[4]);
                l.items.add(i);
            }
        } catch (RuntimeException e) {
            return defaults();
        }
        return l;
    }

    /** Short label for a key, like the keyboard legend. */
    public static String label(int key) {
        if (key == DPAD) return "D-pad";
        switch (key) {
            case Input.Keys.ALT_LEFT: case Input.Keys.ALT_RIGHT: return "Alt";
            case Input.Keys.CONTROL_LEFT: case Input.Keys.CONTROL_RIGHT: return "Ctrl";
            case Input.Keys.SHIFT_LEFT: case Input.Keys.SHIFT_RIGHT: return "Shift";
            case Input.Keys.SPACE: return "Space";
            case Input.Keys.ENTER: return "Enter";
            case Input.Keys.ESCAPE: return "Esc";
            case Input.Keys.TAB: return "Tab";
            case Input.Keys.INSERT: return "Ins";
            case Input.Keys.HOME: return "Home";
            case Input.Keys.PAGE_UP: return "PgUp";
            case Input.Keys.FORWARD_DEL: return "Del";
            case Input.Keys.END: return "End";
            case Input.Keys.PAGE_DOWN: return "PgDn";
            case Input.Keys.UP: return "Up";
            case Input.Keys.DOWN: return "Down";
            case Input.Keys.LEFT: return "Left";
            case Input.Keys.RIGHT: return "Right";
            case Input.Keys.GRAVE: return "`";
            case Input.Keys.MINUS: return "-";
            case Input.Keys.EQUALS: return "=";
            case Input.Keys.LEFT_BRACKET: return "[";
            case Input.Keys.RIGHT_BRACKET: return "]";
            case Input.Keys.SEMICOLON: return ";";
            case Input.Keys.APOSTROPHE: return "'";
            case Input.Keys.COMMA: return ",";
            case Input.Keys.PERIOD: return ".";
            case Input.Keys.SLASH: return "/";
            case Input.Keys.BACKSLASH: return "\\";
            default:
                String n = Input.Keys.toString(key);
                return n == null ? "?" : n;
        }
    }

    /** Every key the picker offers, in keyboard order. */
    public static final int[] PICKABLE = {
            Input.Keys.ESCAPE, Input.Keys.F1, Input.Keys.F2, Input.Keys.F3, Input.Keys.F4, Input.Keys.F5, Input.Keys.F6,
            Input.Keys.F7, Input.Keys.F8, Input.Keys.F9, Input.Keys.F10, Input.Keys.F11, Input.Keys.F12,
            Input.Keys.GRAVE, Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4, Input.Keys.NUM_5,
            Input.Keys.NUM_6, Input.Keys.NUM_7, Input.Keys.NUM_8, Input.Keys.NUM_9, Input.Keys.NUM_0, Input.Keys.MINUS, Input.Keys.EQUALS,
            Input.Keys.TAB, Input.Keys.Q, Input.Keys.W, Input.Keys.E, Input.Keys.R, Input.Keys.T, Input.Keys.Y, Input.Keys.U,
            Input.Keys.I, Input.Keys.O, Input.Keys.P, Input.Keys.LEFT_BRACKET, Input.Keys.RIGHT_BRACKET, Input.Keys.BACKSLASH,
            Input.Keys.A, Input.Keys.S, Input.Keys.D, Input.Keys.F, Input.Keys.G, Input.Keys.H, Input.Keys.J, Input.Keys.K,
            Input.Keys.L, Input.Keys.SEMICOLON, Input.Keys.APOSTROPHE, Input.Keys.ENTER,
            Input.Keys.SHIFT_LEFT, Input.Keys.Z, Input.Keys.X, Input.Keys.C, Input.Keys.V, Input.Keys.B, Input.Keys.N,
            Input.Keys.M, Input.Keys.COMMA, Input.Keys.PERIOD, Input.Keys.SLASH,
            Input.Keys.CONTROL_LEFT, Input.Keys.ALT_LEFT, Input.Keys.SPACE,
            Input.Keys.INSERT, Input.Keys.HOME, Input.Keys.PAGE_UP, Input.Keys.FORWARD_DEL, Input.Keys.END, Input.Keys.PAGE_DOWN,
            Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT, DPAD};
}
