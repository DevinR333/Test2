package maple.ui;

import com.badlogic.gdx.Input.Keys;

import java.util.HashMap;
import java.util.Map;

/**
 * v83 key slots: the client numbers keys by their PC scan code (0..89), and the server stores one
 * binding (type, action) per slot. This maps libGDX key codes to those slots.
 */
public final class KeyMap {
    private static final Map<Integer, Integer> SLOT = new HashMap<>();
    private static final Map<Integer, Integer> KEY = new HashMap<>();

    static {
        put(Keys.ESCAPE, 1);
        int[] digits = {Keys.NUM_1, Keys.NUM_2, Keys.NUM_3, Keys.NUM_4, Keys.NUM_5, Keys.NUM_6, Keys.NUM_7, Keys.NUM_8, Keys.NUM_9, Keys.NUM_0};
        for (int i = 0; i < digits.length; i++) put(digits[i], 2 + i);
        put(Keys.MINUS, 12);
        put(Keys.EQUALS, 13);
        put(Keys.BACKSPACE, 14);
        put(Keys.TAB, 15);
        int[] row1 = {Keys.Q, Keys.W, Keys.E, Keys.R, Keys.T, Keys.Y, Keys.U, Keys.I, Keys.O, Keys.P};
        for (int i = 0; i < row1.length; i++) put(row1[i], 16 + i);
        put(Keys.LEFT_BRACKET, 26);
        put(Keys.RIGHT_BRACKET, 27);
        put(Keys.ENTER, 28);
        put(Keys.CONTROL_LEFT, 29);
        put(Keys.CONTROL_RIGHT, 29);
        int[] row2 = {Keys.A, Keys.S, Keys.D, Keys.F, Keys.G, Keys.H, Keys.J, Keys.K, Keys.L};
        for (int i = 0; i < row2.length; i++) put(row2[i], 30 + i);
        put(Keys.SEMICOLON, 39);
        put(Keys.APOSTROPHE, 40);
        put(Keys.GRAVE, 41);
        put(Keys.SHIFT_LEFT, 42);
        put(Keys.BACKSLASH, 43);
        int[] row3 = {Keys.Z, Keys.X, Keys.C, Keys.V, Keys.B, Keys.N, Keys.M};
        for (int i = 0; i < row3.length; i++) put(row3[i], 44 + i);
        put(Keys.COMMA, 51);
        put(Keys.PERIOD, 52);
        put(Keys.SLASH, 53);
        put(Keys.SHIFT_RIGHT, 42);
        put(Keys.ALT_LEFT, 56);
        put(Keys.ALT_RIGHT, 56);
        put(Keys.SPACE, 57);
        int[] f = {Keys.F1, Keys.F2, Keys.F3, Keys.F4, Keys.F5, Keys.F6, Keys.F7, Keys.F8, Keys.F9, Keys.F10};
        for (int i = 0; i < f.length; i++) put(f[i], 59 + i);
        put(Keys.HOME, 71);
        put(Keys.PAGE_UP, 73);
        put(Keys.END, 79);
        put(Keys.PAGE_DOWN, 81);
        put(Keys.INSERT, 82);
        put(Keys.FORWARD_DEL, 83);
        put(Keys.F11, 87);
        put(Keys.F12, 88);
    }

    private static void put(int key, int slot) {
        SLOT.put(key, slot);
        if (!KEY.containsKey(slot)) KEY.put(slot, key);
    }

    private KeyMap() {}

    /** The v83 key slot for a libGDX key, or -1. */
    public static int slot(int gdxKey) {
        Integer s = SLOT.get(gdxKey);
        return s == null ? -1 : s;
    }

    /** A libGDX key for a slot (for on-screen buttons), or -1. */
    public static int key(int slot) {
        Integer k = KEY.get(slot);
        return k == null ? -1 : k;
    }

    /** Short printable name of a key slot (touch buttons, the key picker). */
    public static String slotName(int slot) {
        switch (slot) {
            case 1: return "Esc";
            case 12: return "-";
            case 13: return "=";
            case 14: return "Bksp";
            case 15: return "Tab";
            case 26: return "[";
            case 27: return "]";
            case 28: return "Enter";
            case 29: return "Ctrl";
            case 39: return ";";
            case 40: return "'";
            case 41: return "`";
            case 42: return "Shift";
            case 43: return "\\";
            case 51: return ",";
            case 52: return ".";
            case 53: return "/";
            case 56: return "Alt";
            case 57: return "Space";
            case 71: return "Home";
            case 73: return "PgUp";
            case 79: return "End";
            case 81: return "PgDn";
            case 82: return "Ins";
            case 83: return "Del";
            case 87: return "F11";
            case 88: return "F12";
            default:
                break;
        }
        if (slot >= 2 && slot <= 11) return Integer.toString((slot - 1) % 10);
        if (slot >= 59 && slot <= 68) return "F" + (slot - 58);
        String row1 = "QWERTYUIOP", row2 = "ASDFGHJKL", row3 = "ZXCVBNM";
        if (slot >= 16 && slot < 26) return String.valueOf(row1.charAt(slot - 16));
        if (slot >= 30 && slot < 39) return String.valueOf(row2.charAt(slot - 30));
        if (slot >= 44 && slot < 51) return String.valueOf(row3.charAt(slot - 44));
        return "#" + slot;
    }

    /** Quick slot keys in order: Shift, Ins, Home, PgUp / Ctrl, Del, End, PgDn. */
    public static final int[] QUICK_SLOTS = {42, 82, 71, 73, 29, 83, 79, 81};

    // binding types
    public static final int SKILL = 1, ITEM = 2, CASH_ITEM = 3, MENU = 4, ACTION = 5, FACE = 6, MACRO = 8;

    // type 5 actions
    public static final int PICKUP = 50, SIT = 51, ATTACK = 52, JUMP = 53, TALK = 54;

    /** Names of type-4 (window) actions, as the Key Config window lists them. */
    public static String menuName(int id) {
        String[] n = {"Equip", "Item", "Stat", "Skill", "Friends", "World Map", "Messenger", "Mini Map", "Quest",
                "Key Settings", "To All", "Whisper", "To Party", "To Friend", "Main Menu", "Quick Slot", "Chat Window",
                "Guild", "To Guild", "Party", "Quest Helper", "To Spouse", "Monster Book", "Cash Shop", "To Alliance",
                "Party Search", "Family", "Medal"};
        return id >= 0 && id < n.length ? n[id] : "";
    }

    public static String actionName(int id) {
        switch (id) {
            case PICKUP: return "Pick Up";
            case SIT: return "Sit";
            case ATTACK: return "Attack";
            case JUMP: return "Jump";
            case TALK: return "NPC Chat";
            default: return "";
        }
    }
}
