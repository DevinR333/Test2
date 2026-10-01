package maple.ui.windows;

import com.badlogic.gdx.utils.Align;
import maple.game.SkillInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.KeyMap;
import maple.ui.Scrollbar;
import maple.ui.TabStrip;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The Skill window (UIWindow.img/Skill): job-book tabs (span 34 per tab), book icon at (10, 57) with its
 * name centred over x 43..168 at y 66, four 40px rows (icon 10,102 / name 46,103 / level 46,120 /
 * BtSpUp 131,119), the VScr at (153, 99, 155), remaining SP right-aligned at (84, 265, 27) and BtMacro
 * at (120, 265). Learned skills can be dragged to the quick slots and the Key Config.
 */
public final class SkillWindow extends Window {
    private final World world;
    private TabStrip tabs;
    private final Scrollbar scroll;
    private final Button[] spUp = new Button[4];
    private List<Integer> books = new ArrayList<>();
    private int tab, start, selected = -1;
    private int bookJob = -1;
    private final List<Integer> entries = new ArrayList<>();

    public SkillWindow(Ui ui, World world) {
        super(ui, "Skill", "UIWindow.img/Skill/backgrnd");
        this.world = world;
        scroll = add(new Scrollbar(ui.assets, "VScr4", 153, 99, 155));
        scroll.onChange = p -> start = p;
        for (int r = 0; r < 4; r++) {
            int row = r;
            spUp[r] = add(new Button(ui.assets, "UIWindow.img/Skill/BtSpUp", 131, 119 + 40 * r, () -> learn(row)));
        }
        add(new Button(ui.assets, "Basic.img/BtMacro", 120, 265, () -> {
            SkillMacroWindow m = ui.find(SkillMacroWindow.class);
            if (m != null) ui.close(m);
            else ui.open(new SkillMacroWindow(ui, world));
        }));
        addClose();
        refresh();
    }

    private int book() {
        return books.isEmpty() ? 0 : books.get(Math.min(tab, books.size() - 1));
    }

    @Override
    public void refresh() {
        if (world.data() == null) return;
        int job = world.data().stats.job;
        if (job != bookJob) {
            bookJob = job;
            books = SkillInfo.books(job);
            if (books.size() > 5) books = books.subList(0, 5);
            if (tabs != null) remove(tabs);
            tabs = add(new TabStrip(ui.assets, "UIWindow.img/Skill/Tab", books.size(), 34 * books.size()));
            tab = books.size() - 1;
            tabs.selected = tab;
            tabs.onSelect = i -> {
                tab = i;
                start = 0;
                selected = -1;
                refresh();
            };
            children.remove(tabs);
            children.add(0, tabs);
        }
        entries.clear();
        for (int id : SkillInfo.book(book())) {
            SkillInfo s = SkillInfo.get(id);
            if (s == null) continue;
            int[] learned = world.data().skills.get(id);
            boolean has = learned != null;
            boolean timeLimited = s.node.getInt("timeLimited", 0) != 0;
            if ((has && learned[0] > 0 || !s.invisible) && (has || !timeLimited)) entries.add(id);
        }
        scroll.setRange(Math.max(1, entries.size() - 3), start);
        start = scroll.position;
        for (int r = 0; r < 4; r++) {
            int i = start + r;
            spUp[r].visible = i < entries.size();
            spUp[r].disabled = i >= entries.size() || !canLearn(entries.get(i));
        }
    }

    private int level(int id) {
        int[] l = world.data().skills.get(id);
        return l == null ? 0 : l[0];
    }

    private int master(int id) {
        int[] l = world.data().skills.get(id);
        return l == null ? 0 : l[1];
    }

    /** 004e8f66: fourth-job books (ending in 2) cap at the master level. */
    private static boolean needsMastery(int book) {
        if (book / 100 == 22 || book == 2001) return book == 2217 || book == 2218;
        return book % 100 != 0 && book % 10 == 2;
    }

    private boolean canLearn(int id) {
        if (world.data() == null) return false;
        int sp = world.data().stats.sp;
        if (sp <= 0) return false;
        SkillInfo s = SkillInfo.get(id);
        if (s == null) return false;
        int bookId = id / 10000;
        int job = world.data().stats.job;
        // the job's own books only; beginner skills only while a beginner
        if (bookId % 1000 == 0 && job % 1000 >= 100) return false;
        if (!SkillInfo.books(job).contains(bookId)) return false;
        int cap = needsMastery(bookId) ? master(id) : s.maxLevel;
        if (level(id) >= cap) return false;
        for (WzNode req : s.node.get("req").children()) {
            try {
                if (level(Integer.parseInt(req.name)) < req.asInt(0)) return false;
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    private void learn(int row) {
        int i = start + row;
        if (i >= entries.size()) return;
        int id = entries.get(i);
        if (canLearn(id)) world.distributeSp(id);
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (world.data() == null) return;
        int book = book();
        Sprite bi = ui.assets.sprite(SkillInfo.bookIcon(book));
        if (bi != null) g.image(bi, 10, 57);
        g.text(SkillInfo.bookName(book), 43, 66, 125, Align.center, false, 12, false, 0xFF000000);
        Sprite row0 = ui.assets.sprite("UIWindow.img/Skill/skill0"), row1 = ui.assets.sprite("UIWindow.img/Skill/skill1");
        Sprite line = ui.assets.sprite("UIWindow.img/Skill/line");
        for (int r = 0; r < 4; r++) {
            int i = start + r;
            if (i >= entries.size()) break;
            int id = entries.get(i);
            SkillInfo s = SkillInfo.get(id);
            int lv = level(id);
            float ry = 99 + 40 * r;
            Sprite back = canLearn(id) ? row1 : row0;
            if (back != null) g.image(back, 7, ry);
            if (r < 3 && i + 1 < entries.size() && line != null) g.image(line, 7, ry + 37);
            Sprite icon = ui.assets.sprite(lv > 0 ? s.icon() : (s.iconDisabled().exists() ? s.iconDisabled() : s.icon()));
            if (icon != null) g.image(icon, 10, 102 + 40 * r);
            String name = s.name;
            while (name.length() > 1 && g.textWidth(name, 12, false) > 84) name = name.substring(0, name.length() - 1);
            if (!name.equals(s.name)) name = name.substring(0, Math.max(1, name.length() - 2)) + "..";
            g.text(name, 46, 103 + 40 * r, 111, Align.left, false, 12, false, 0xFF000000);
            int m = master(id);
            String lvText = Integer.toString(lv);
            if (needsMastery(id / 10000)) lvText += " / " + m;
            g.text(lvText, 46, 120 + 40 * r, 88, Align.left, false, 12, false, 0xFF000000);
        }
        g.text(Integer.toString(world.data().stats.sp), 84, 265, 27, Align.right, false, 12, false, 0xFF000000);
    }

    private int rowAt(float lx, float ly) {
        for (int r = 0; r < 4; r++) {
            if (lx >= 7 && lx < 133 && ly >= 99 + 40 * r && ly < 99 + 40 * r + 38) {
                int i = start + r;
                return i < entries.size() ? i : -1;
            }
        }
        return -1;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int i = rowAt(lx, ly);
        if (i < 0) return false;
        int id = entries.get(i);
        selected = id;
        SkillInfo s = SkillInfo.get(id);
        // only learned, active skills can be bound to keys
        if (level(id) <= 0 || s.passive()) return true;
        ui.carry = new BindingCarry(KeyMap.SKILL, id, ui.assets.sprite(s.icon()), false, -1);
        UiSounds.play("DragStart");
        return true;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int i = rowAt(lx, ly);
        if (i < 0 || lx < 10 || lx >= 42) return null;
        int id = entries.get(i);
        return new SkillTooltip(ui, SkillInfo.get(id), level(id), master(id));
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        scroll.scroll(amount);
        return true;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        refresh();
    }

    /** Skill hover box: icon on the translucent backing, name, description and current/next level text. */
    public static final class SkillTooltip extends Tooltip {
        private final Ui ui;
        private final SkillInfo s;
        private final int level, master;

        public SkillTooltip(Ui ui, SkillInfo s, int level, int master) {
            this.ui = ui;
            this.s = s;
            this.level = level;
            this.master = master;
        }

        private static String clean(String t) {
            return t.replace("\\n", "\n").replace("\\r", "").replaceAll("#[a-zA-Z]", "").replace("#", "");
        }

        @Override
        public void draw(UiDraw g, float px, float py) {
            float width = 320, inner = width - 18;
            String desc = clean(s.desc);
            if (master > 0) desc = "[Master Level : " + master + "]\n" + desc;
            List<String[]> blocks = new ArrayList<>();
            if (level > 0) blocks.add(new String[]{"[Current Level " + level + "]", clean(s.levelText(level))});
            if (level < s.maxLevel) blocks.add(new String[]{"[Next Level " + (level + 1) + "]", clean(s.levelText(level + 1))});
            float descH = g.textHeight(desc, inner - 82, 12, false);
            float h = 32 + Math.max(76, descH + 12);
            float blocksH = 0;
            for (String[] b : blocks) blocksH += 16 + g.textHeight(b[1], inner, 12, false) + 4;
            h += blocksH + 6;
            float x = px + 12, y = py + 18;
            if (x + width > Ui.W) x = px - width - 4;
            if (y + h > Ui.H) y = Ui.H - h;
            x = Math.max(0, x);
            y = Math.max(0, y);
            g.fill(x, y, width, h, BACK);
            g.outline(x, y, width, h, 0xFFFFFFFF);
            g.text(s.name, x + 9, y + 10, inner, Align.center, false, 12, true, 0xFFFFFFFF);
            g.fill(x + 10, y + 32, 68, 68, 0xA0FFFFFF);
            Sprite icon = ui.assets.sprite(s.icon());
            if (icon != null) g.stretched(icon, x + 12, y + 34, icon.w * 2, icon.h * 2);
            g.text(desc, x + 91, y + 32, inner - 82, Align.left, true, 12, false, 0xFFFFFFFF);
            float ty = y + 32 + Math.max(76, descH + 12);
            if (!blocks.isEmpty()) g.fill(x + 9, ty - 4, inner, 1, 0x80FFFFFF);
            for (String[] b : blocks) {
                g.text(b[0], x + 9, ty, inner, Align.left, false, 12, false, 0xFFFFFFFF);
                ty += 16;
                ty += g.text(b[1], x + 9, ty, inner, Align.left, true, 12, false, 0xFFFFFFFF) + 4;
            }
        }
    }
}
