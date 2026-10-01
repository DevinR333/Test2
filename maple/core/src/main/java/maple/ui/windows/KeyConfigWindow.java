package maple.ui.windows;

import com.badlogic.gdx.Input;
import maple.game.ItemInfo;
import maple.game.SkillInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.Button;
import maple.ui.KeyMap;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard settings (UIWindow.img/KeyConfig, 0083665b / 008354e4): the keyboard cells at the recovered
 * coordinates, the 18 x 3 action palette from (9, 267), OK / Cancel / Default / Delete / QuickSlot at
 * y 236. Edits go to a draft that is sent to the server on OK; Default, Delete and closing with
 * changes ask through KeyConfig/notice 0 / 1 / 2.
 */
public final class KeyConfigWindow extends Window implements Ui.DropTarget {
    private static final int[][] KEY_XY = new int[91][];
    private static final int[][] DEFAULTS = {{2, 4, 10}, {3, 4, 12}, {4, 4, 13}, {5, 4, 18}, {6, 4, 24}, {7, 4, 21}, {16, 4, 8},
            {17, 4, 5}, {18, 4, 0}, {19, 4, 4}, {23, 4, 1}, {24, 4, 25}, {25, 4, 19}, {26, 4, 14}, {27, 4, 15}, {29, 5, 52},
            {31, 4, 2}, {33, 4, 26}, {34, 4, 17}, {35, 4, 11}, {37, 4, 3}, {38, 4, 20}, {39, 4, 27}, {40, 4, 16}, {41, 4, 23},
            {43, 4, 9}, {44, 5, 50}, {45, 5, 51}, {46, 4, 6}, {48, 4, 22}, {50, 4, 7}, {56, 5, 53}, {57, 5, 54}, {59, 6, 100},
            {60, 6, 101}, {61, 6, 102}, {62, 6, 103}, {63, 6, 104}, {64, 6, 105}, {65, 6, 106}};

    static {
        int[] raw = {2, 48, 66, 3, 82, 66, 4, 116, 66, 5, 150, 66, 6, 184, 66, 7, 218, 66, 8, 252, 66, 9, 286, 66, 10, 320, 66,
                11, 354, 66, 12, 388, 66, 13, 422, 66, 16, 64, 99, 17, 98, 99, 18, 132, 99, 19, 166, 99, 20, 200, 99, 21, 234, 99,
                22, 268, 99, 23, 302, 99, 24, 336, 99, 25, 370, 99, 26, 404, 99, 27, 438, 99, 29, 22, 198, 30, 81, 132, 31, 115, 132,
                32, 149, 132, 33, 183, 132, 34, 217, 132, 35, 251, 132, 36, 285, 132, 37, 319, 132, 38, 353, 132, 39, 387, 132,
                40, 421, 132, 41, 14, 66, 42, 38, 165, 43, 472, 99, 44, 98, 165, 45, 132, 165, 46, 166, 165, 47, 200, 165, 48, 234, 165,
                49, 268, 165, 50, 302, 165, 51, 336, 165, 52, 370, 165, 54, 457, 165, 56, 122, 198, 57, 233, 198, 59, 82, 27,
                60, 116, 27, 61, 150, 27, 62, 184, 27, 63, 226, 27, 64, 260, 27, 65, 294, 27, 66, 328, 27, 67, 370, 27, 68, 404, 27,
                71, 548, 66, 73, 582, 66, 79, 548, 99, 81, 582, 99, 82, 514, 66, 83, 514, 99, 87, 438, 27, 88, 472, 27, 89, 461, 198,
                90, 348, 198};
        for (int i = 0; i < raw.length; i += 3) KEY_XY[raw[i]] = new int[]{raw[i + 1], raw[i + 2]};
    }

    /** Right Shift / Ctrl / Alt share the left key's binding. */
    private static int canonical(int index) {
        if (index == 54) return 42;
        if (index == 89) return 29;
        if (index == 90) return 56;
        return index;
    }

    private final World world;
    private final int[] types = new int[90], actions = new int[90];

    public KeyConfigWindow(Ui ui, World world) {
        super(ui, "KeyConfig", "UIWindow.img/KeyConfig/backgrnd");
        this.world = world;
        System.arraycopy(world.keyTypes, 0, types, 0, 90);
        System.arraycopy(world.keyActions, 0, actions, 0, 90);
        String b = "UIWindow.img/KeyConfig/";
        add(new Button(ui.assets, b + "BtOK", 8, 236, this::save));
        add(new Button(ui.assets, b + "BtCancel", 58, 236, this::cancel));
        add(new Button(ui.assets, b + "BtDefault", 112, 236, () -> ui.open(new KeyNotice(0))));
        add(new Button(ui.assets, b + "BtDelete", 177, 236, () -> ui.open(new KeyNotice(1))));
        add(new Button(ui.assets, b + "BtQuickSlot", 260, 236, () -> {}));
        close = add(new Button(ui.assets, b + "BtClose", w - 17, 6, this::cancel));
        if (!close.present()) {
            remove(close);
            close = add(new Button(ui.assets, "Basic.img/BtClose", w - 17, 6, this::cancel));
        }
    }

    private boolean dirty() {
        for (int i = 0; i < 90; i++) if (types[i] != world.keyTypes[i] || actions[i] != world.keyActions[i]) return true;
        return false;
    }

    private void save() {
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            if (types[i] != world.keyTypes[i] || actions[i] != world.keyActions[i]) {
                world.keyTypes[i] = types[i];
                world.keyActions[i] = actions[i];
                changed.add(i);
            }
        }
        if (!changed.isEmpty()) {
            int[] slots = new int[changed.size()];
            for (int i = 0; i < slots.length; i++) slots[i] = changed.get(i);
            world.changeKeys(slots);
        }
        close();
    }

    private void cancel() {
        if (dirty()) ui.open(new KeyNotice(2));
        else close();
    }

    private void set(int slot, int type, int action) {
        slot = canonical(slot);
        if (slot < 0 || slot >= 90) return;
        types[slot] = type;
        actions[slot] = action;
    }

    private int keyAt(float lx, float ly) {
        for (int i = 0; i < KEY_XY.length; i++) {
            int[] k = KEY_XY[i];
            if (k != null && lx >= k[0] && lx < k[0] + 32 && ly >= k[1] && ly < k[1] + 32) return canonical(i);
        }
        return -1;
    }

    /** Palette entries: menus 0..27, actions 50..54, faces 100..106. */
    private static int[] palette(int cell) {
        if (cell < 28) return new int[]{KeyMap.MENU, cell};
        if (cell < 33) return new int[]{KeyMap.ACTION, cell + 22};
        if (cell < 40) return new int[]{KeyMap.FACE, cell + 67};
        return null;
    }

    private int paletteAt(float lx, float ly) {
        for (int cell = 0; cell < 54; cell++) {
            float px = 9 + 34 * (cell % 18), py = 267 + 34 * (cell / 18);
            if (lx >= px && lx < px + 32 && ly >= py && ly < py + 32) return cell;
        }
        return -1;
    }

    private boolean bound(int type, int action) {
        for (int i = 0; i < 90; i++) if (types[i] == type && actions[i] == action) return true;
        return false;
    }

    private Sprite icon(int type, int action) {
        switch (type) {
            case KeyMap.SKILL: {
                SkillInfo s = SkillInfo.get(action);
                return s == null ? null : ui.assets.sprite(s.icon());
            }
            case KeyMap.ITEM:
            case KeyMap.CASH_ITEM:
                return ui.assets.sprite(ItemInfo.get(action).icon());
            case KeyMap.MENU:
            case KeyMap.ACTION:
            case KeyMap.FACE:
                return ui.assets.sprite("UIWindow.img/KeyConfig/icon/" + action);
            default:
                return null;
        }
    }

    @Override
    protected void drawContent(UiDraw g) {
        for (int i = 0; i < KEY_XY.length; i++) {
            int[] k = KEY_XY[i];
            if (k == null) continue;
            int slot = canonical(i);
            int type = types[slot], action = actions[slot];
            if (carrying(slot)) type = 0;
            Sprite ic = type == 0 ? null : icon(type, action);
            if (ic != null) {
                if (type == KeyMap.ITEM || type == KeyMap.CASH_ITEM) g.anchored(ic, k[0], k[1] + 32);
                else g.image(ic, k[0], k[1]);
            }
            Sprite label = ui.assets.sprite("UIWindow.img/KeyConfig/key/" + slot);
            int off = slot == 42 ? 2 : slot == 57 ? 0 : 4;
            if (label != null) g.image(label, k[0] + off, k[1] + 4);
            if (type == KeyMap.ITEM) {
                int n = 0;
                if (world.data() != null) for (Item it : world.data().inventory(ItemInfo.inventoryType(action)).values()) if (it.itemId == action) n += it.quantity;
                float cx = k[0];
                for (char c : Integer.toString(n).toCharArray()) {
                    Sprite d = ui.assets.sprite("Basic.img/ItemNo/" + c);
                    if (d == null) continue;
                    g.image(d, cx, k[1] + 20);
                    cx += d.w;
                }
            }
        }
        for (int cell = 0; cell < 40; cell++) {
            int[] p = palette(cell);
            if (bound(p[0], p[1])) continue;
            Sprite ic = ui.assets.sprite("UIWindow.img/KeyConfig/icon/" + p[1]);
            if (ic != null) g.image(ic, 9 + 34 * (cell % 18), 267 + 34 * (cell / 18));
        }
    }

    private boolean carrying(int slot) {
        return ui.carry instanceof BindingCarry && ((BindingCarry) ui.carry).fromSlot == slot;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int k = keyAt(lx, ly);
        if (k >= 0 && types[k] != 0) {
            int type = types[k], action = actions[k];
            boolean anchored = type == KeyMap.ITEM || type == KeyMap.CASH_ITEM;
            BindingCarry c = new BindingCarry(type, action, icon(type, action), anchored, k);
            c.onWorld = () -> set(k, 0, 0);
            ui.carry = c;
            UiSounds.play("DragStart");
            return true;
        }
        int cell = paletteAt(lx, ly);
        if (cell >= 0 && cell < 40) {
            int[] p = palette(cell);
            if (!bound(p[0], p[1])) {
                ui.carry = new BindingCarry(p[0], p[1], icon(p[0], p[1]), false, -1);
                UiSounds.play("DragStart");
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRightClick(float lx, float ly) {
        int k = keyAt(lx, ly);
        if (k >= 0) set(k, 0, 0);
    }

    @Override
    public boolean drop(Ui.Carry c, float lx, float ly) {
        int type, action, from = -1;
        if (c instanceof BindingCarry) {
            BindingCarry b = (BindingCarry) c;
            type = b.type;
            action = b.action;
            from = b.fromSlot;
        } else if (c instanceof ItemCarry) {
            ItemCarry ic = (ItemCarry) c;
            type = bindingType(ic.item.itemId);
            action = ic.item.itemId;
            if (type == 0) return true;
        } else return false;
        int k = keyAt(lx, ly);
        if (k < 0) {
            // dropped back on the palette (or the window body): remove it from the key it came from
            if (from >= 0) set(from, 0, 0);
            return true;
        }
        if (from >= 0) {
            // swap with what was on the target key
            int ot = types[k], oa = actions[k];
            set(from, ot, oa);
        } else if (type >= KeyMap.MENU && type <= KeyMap.FACE) {
            for (int i = 0; i < 90; i++) if (types[i] == type && actions[i] == action) set(i, 0, 0);
        }
        set(k, type, action);
        return true;
    }

    /** 004f38f4: which items can sit on a key. */
    public static int bindingType(int itemId) {
        int group = itemId / 10000;
        int inv = ItemInfo.inventoryType(itemId);
        if (inv == 2) {
            int[] use = {200, 201, 202, 205, 212, 221, 226, 227, 236, 238, 245};
            for (int u : use) if (group == u) return KeyMap.ITEM;
            if (itemId / 1000 == 2109 || itemId == 2100067) return KeyMap.ITEM;
            return 0;
        }
        if (inv == 3) return group == 301 ? KeyMap.ITEM : 0;
        if (inv == 5) {
            if (group == 524 || group == 530) return KeyMap.ITEM;
            if (group == 516) return KeyMap.CASH_ITEM;
        }
        return 0;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int k = keyAt(lx, ly);
        int type, action;
        if (k >= 0 && types[k] != 0) {
            type = types[k];
            action = actions[k];
        } else {
            int cell = paletteAt(lx, ly);
            if (cell < 0 || cell >= 40) return null;
            int[] p = palette(cell);
            type = p[0];
            action = p[1];
        }
        switch (type) {
            case KeyMap.SKILL: {
                SkillInfo s = SkillInfo.get(action);
                return s == null ? null : Tooltip.text(s.name);
            }
            case KeyMap.ITEM:
            case KeyMap.CASH_ITEM:
                return Tooltip.text(ItemInfo.get(action).name);
            case KeyMap.MENU:
                return Tooltip.text(KeyMap.menuName(action));
            case KeyMap.ACTION:
                return Tooltip.text(KeyMap.actionName(action));
            case KeyMap.FACE:
                return Tooltip.text("Facial Expression " + (action - 99));
            default:
                return null;
        }
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            cancel();
            return true;
        }
        return false;
    }

    /** KeyConfig/notice/0..2 with BtOK (160, 55) and BtCancel (210, 55) (00836877). */
    private final class KeyNotice extends Window {
        KeyNotice(int kind) {
            super(KeyConfigWindow.this.ui, "KeyConfigNotice", "UIWindow.img/KeyConfig/notice/" + kind);
            modal = true;
            draggable = false;
            if (w == 0) {
                w = 266;
                h = 85;
            }
            x = Math.round((Ui.W - w) / 2f);
            y = Math.round((Ui.H - h) / 2f);
            keepPosition = true;
            add(new Button(ui.assets, "UIWindow.img/KeyConfig/BtOK", 160, 55, () -> answer(kind, true)));
            add(new Button(ui.assets, "UIWindow.img/KeyConfig/BtCancel", 210, 55, () -> answer(kind, false)));
        }

        private void answer(int kind, boolean ok) {
            close();
            if (kind == 2) {
                if (ok) save();
                else KeyConfigWindow.this.close();
            } else if (ok) {
                for (int i = 0; i < 90; i++) {
                    types[i] = 0;
                    actions[i] = 0;
                }
                if (kind == 0) for (int[] d : DEFAULTS) set(d[0], d[1], d[2]);
            }
        }

        @Override
        public boolean onKey(int keycode) {
            if (keycode == Input.Keys.ESCAPE) answer(-1, false);
            else if (keycode == Input.Keys.ENTER) return false;
            return true;
        }
    }
}
