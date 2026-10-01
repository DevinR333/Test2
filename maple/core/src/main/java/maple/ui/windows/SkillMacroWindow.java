package maple.ui.windows;

import com.badlogic.gdx.Input;
import maple.game.SkillInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.KeyMap;
import maple.ui.Scrollbar;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Widgets;
import maple.ui.Window;

/**
 * Skill macros (UIWindow.img/SkillMacro, 207x289, attached right of the Skill window; 008a8504): three
 * visible groups of 45px from y 56 with three 32px skill slots at x 20 + 37n and the macro icon at 146,
 * VScr at (185, 54, 125), the name edit at (59, 215, 117), the shout check at (166, 241) and
 * BtCancel2 / BtOK2 at (103 / 154, 265). Drag a macro icon onto a key or quick slot to use it.
 */
public final class SkillMacroWindow extends Window implements Ui.DropTarget {
    private static final int ROWS = 3;
    private final World world;
    private final World.SkillMacro[] draft = new World.SkillMacro[5];
    private final Scrollbar scroll;
    private final Widgets.TextField name;
    private int selected = -1;

    public SkillMacroWindow(Ui ui, World world) {
        super(ui, "SkillMacro", "UIWindow.img/SkillMacro/backgrnd");
        this.world = world;
        keepPosition = true;
        for (int i = 0; i < 5; i++) {
            World.SkillMacro src = world.macros[i];
            World.SkillMacro m = new World.SkillMacro();
            if (src != null) {
                m.name = src.name;
                m.shout = src.shout;
                System.arraycopy(src.skills, 0, m.skills, 0, 3);
            }
            draft[i] = m;
        }
        scroll = add(new Scrollbar(ui.assets, "VScr4", 185, 54, 125));
        scroll.setRange(3, 0);
        name = add(new Widgets.TextField(59, 215, 117, 16));
        name.maxLength = 12;
        add(new Button(ui.assets, "Basic.img/BtCancel2", 103, 265, this::close));
        add(new Button(ui.assets, "Basic.img/BtOK2", 154, 265, this::save));
        follow();
    }

    /** Stays attached to the Skill window's right edge. */
    private void follow() {
        SkillWindow s = ui.find(SkillWindow.class);
        if (s != null) {
            x = s.x + s.w;
            y = s.y;
        }
    }

    private void save() {
        commitName();
        for (int i = 0; i < 5; i++) {
            World.SkillMacro m = draft[i];
            boolean empty = m.name.isEmpty() && m.skills[0] == 0 && m.skills[1] == 0 && m.skills[2] == 0;
            world.macros[i] = empty ? null : m;
        }
        world.saveMacros();
        close();
    }

    private void commitName() {
        if (selected >= 0) draft[selected].name = name.text.trim();
    }

    private void select(int i) {
        commitName();
        selected = i;
        name.text = i >= 0 ? draft[i].name : "";
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        if (ui.find(SkillWindow.class) == null) {
            close();
            return;
        }
        follow();
    }

    private Sprite macroIcon(int i) {
        return ui.assets.sprite("UIWindow.img/SkillMacro/Macroicon/" + i + "/icon");
    }

    @Override
    protected void drawContent(UiDraw g) {
        String b = "UIWindow.img/SkillMacro/";
        g.image(ui.assets.sprite(b + "line01"), 17, 49);
        g.image(ui.assets.sprite(b + "line02"), 7, 190);
        g.image(ui.assets.sprite(b + "macroname"), 17, 195);
        for (int r = 0; r < ROWS; r++) {
            int i = scroll.position + r;
            float y = 56 + 45 * r;
            if (i == selected) g.image(ui.assets.sprite(b + "macroslot2"), 15, y - 5);
            g.image(ui.assets.sprite(b + "macroslot"), 17, y - 3);
            g.image(ui.assets.sprite(b + "macroslot1"), 143, y - 3);
            Sprite mi = macroIcon(i);
            if (mi != null) g.image(mi, 146, y);
            for (int k = 0; k < 3; k++) {
                int id = draft[i].skills[k];
                if (id == 0 || carryingSlot(i, k)) continue;
                SkillInfo s = SkillInfo.get(id);
                Sprite ic = s == null ? null : ui.assets.sprite(s.icon());
                if (ic != null) g.image(ic, 20 + 37 * k, y);
            }
        }
        if (selected >= 0) {
            Sprite mi = macroIcon(selected);
            if (mi != null) g.image(mi, 22, 200);
        }
        boolean shout = selected >= 0 && draft[selected].shout;
        g.image(ui.assets.sprite("Basic.img/CheckBox/" + (shout ? 1 : 0)), 166, 241);
        g.fill(name.x, name.y, name.w, name.h, selected >= 0 ? 0xFFFFFFFF : 0x00000000);
    }

    private int carrySlotIndex = -1, carrySlotPos = -1;

    private boolean carryingSlot(int i, int k) {
        return ui.carry != null && carrySlotIndex == i && carrySlotPos == k;
    }

    private int[] slotAt(float lx, float ly) {
        for (int r = 0; r < ROWS; r++) {
            float y = 56 + 45 * r;
            if (ly < y || ly >= y + 32) continue;
            for (int k = 0; k < 3; k++) {
                float x0 = 20 + 37 * k;
                if (lx >= x0 && lx < x0 + 32) return new int[]{scroll.position + r, k};
            }
            if (lx >= 146 && lx < 178) return new int[]{scroll.position + r, -1};
        }
        return null;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        if (lx >= 42 && lx < 178 && ly >= 239 && ly < 255 && selected >= 0) {
            draft[selected].shout = !draft[selected].shout;
            UiSounds.play("BtMouseClick");
            return false;
        }
        int[] s = slotAt(lx, ly);
        if (s == null) return false;
        select(s[0]);
        if (s[1] < 0) {
            ui.carry = new BindingCarry(KeyMap.MACRO, s[0], macroIcon(s[0]), false, -1);
            UiSounds.play("DragStart");
            return true;
        }
        int id = draft[s[0]].skills[s[1]];
        if (id == 0) return false;
        SkillInfo si = SkillInfo.get(id);
        BindingCarry c = new BindingCarry(KeyMap.SKILL, id, si == null ? null : ui.assets.sprite(si.icon()), false, -1);
        int mi = s[0], mk = s[1];
        carrySlotIndex = mi;
        carrySlotPos = mk;
        c.onWorld = () -> {
            draft[mi].skills[mk] = 0;
            carrySlotIndex = carrySlotPos = -1;
        };
        ui.carry = c;
        UiSounds.play("DragStart");
        return true;
    }

    @Override
    public void onRightClick(float lx, float ly) {
        int[] s = slotAt(lx, ly);
        if (s != null && s[1] >= 0) draft[s[0]].skills[s[1]] = 0;
    }

    @Override
    public boolean drop(Ui.Carry c, float lx, float ly) {
        int fromI = carrySlotIndex, fromK = carrySlotPos;
        carrySlotIndex = carrySlotPos = -1;
        if (!(c instanceof BindingCarry)) return false;
        BindingCarry b = (BindingCarry) c;
        int[] s = slotAt(lx, ly);
        if (s == null || s[1] < 0) return true;
        if (b.type != KeyMap.SKILL) {
            ui.open(new maple.ui.Dialogs.Notice(ui, "Only skills can be placed in a macro.", false, null, null));
            return true;
        }
        SkillInfo si = SkillInfo.get(b.action);
        if (si == null || si.passive()) return true;
        if (fromI >= 0) {
            // moved within the macros: swap
            draft[fromI].skills[fromK] = draft[s[0]].skills[s[1]];
        }
        draft[s[0]].skills[s[1]] = b.action;
        select(s[0]);
        return true;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int[] s = slotAt(lx, ly);
        if (s == null) return null;
        if (s[1] < 0) {
            World.SkillMacro m = draft[s[0]];
            Tooltip t = Tooltip.text(m.name.isEmpty() ? "Skill macro " + (s[0] + 1) : m.name);
            for (int id : m.skills) {
                SkillInfo si = id == 0 ? null : SkillInfo.get(id);
                if (si != null) t.line(si.name, 0xFFFFFFFF);
            }
            return t;
        }
        int id = draft[s[0]].skills[s[1]];
        SkillInfo si = id == 0 ? null : SkillInfo.get(id);
        if (si == null) return null;
        int[] l = world.data() == null ? null : world.data().skills.get(id);
        return new SkillWindow.SkillTooltip(ui, si, l == null ? 0 : l[0], l == null ? 0 : l[1]);
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        scroll.scroll(amount);
        return true;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            close();
            return true;
        }
        if (keycode == Input.Keys.ENTER) {
            save();
            return true;
        }
        return false;
    }
}
