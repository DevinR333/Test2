package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.game.ItemInfo;
import maple.game.Names;
import maple.game.World;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.ItemTooltip;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Widgets;
import maple.ui.Window;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Monster Book (UIWindow.img/MonsterBook, 475x349): nine card categories on the left tabs (y 126 +
 * 20n) plus the information tab, a 5 x 5 card grid from (48, 56) with 33 / 45 spacing over cardSlot,
 * and on the right the selected monster with the Basic Info / Episode / Dropping / Found In tabs, which
 * open at 1 / 3 / 4 / 5 cards. Right-click a card to register or release it as the book cover.
 */
public final class MonsterBookWindow extends Window {
    private static final int COLS = 5, PAGE = 25, TEXT = 0xFF33251B;
    private static final String B = "UIWindow.img/MonsterBook/";
    private static final String[] TAB_NAMES = {"Basic Info", "Episode", "Dropping", "Found In"};
    private static final int[] TAB_COUNTS = {1, 3, 4, 5};
    private final World world;
    private final List<List<Integer>> categories = new ArrayList<>();
    private int category = 9, page, selected, tab, detailPage;
    private final Widgets.TextField search;
    private final Button[] arrows = new Button[4];
    private int menuCard;
    private float menuX, menuY;

    public MonsterBookWindow(Ui ui, World world) {
        super(ui, "MonsterBook", B + "backgrnd");
        this.world = world;
        for (int i = 0; i < 9; i++) categories.add(new ArrayList<>());
        for (WzNode c : ui.assets.wz.get("Item/Consume/0238.img").children()) {
            try {
                int id = Integer.parseInt(c.name);
                int cat = (id / 1000) % 10;
                if (cat < 9) categories.get(cat).add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        for (List<Integer> l : categories) l.sort(null);
        add(new Button(ui.assets, B + "BtClose", 429, 8, this::close));
        search = add(new Widgets.TextField(49, 30, 120, 15));
        search.maxLength = 40;
        search.onEnter = s -> search();
        add(new Button(ui.assets, B + "BtSearch", 175, 29, this::search));
        int[][] pos = {{-1, 48}, {1, 185}, {-1, 270}, {1, 407}};
        for (int i = 0; i < 4; i++) {
            int dir = pos[i][0];
            boolean left = i < 2;
            arrows[i] = add(new Button(ui.assets, B + (dir < 0 ? "arrowLeft" : "arrowRight"), pos[i][1], 285, () -> changePage(left, dir)));
        }
        int cover = cover();
        if (cover != 0) select(cover);
        refresh();
    }

    private Map<Integer, Integer> cards() {
        return world.data().monsterCards;
    }

    private int count(int card) {
        Integer c = world.data() == null ? null : cards().get(card);
        return c == null ? 0 : c;
    }

    private int cover() {
        return world.data() == null ? 0 : world.data().monsterBookCover;
    }

    private static int mobOf(UiDraw g, int card, Ui ui) {
        return ui.assets.wz.get("Item/Consume/0238.img/" + String.format("%08d", card) + "/info").getInt("mob", 0);
    }

    private String cardName(int card) {
        return Names.mob(mobOf(ui.g, card, ui));
    }

    private WzNode book(int card) {
        return ui.assets.wz.get("String/MonsterBook.img/" + mobOf(ui.g, card, ui));
    }

    private void select(int card) {
        int cat = (card / 1000) % 10;
        if (cat >= 9) return;
        category = cat;
        List<Integer> ids = categories.get(cat);
        page = Math.max(0, ids.indexOf(card)) / PAGE;
        selected = card;
        tab = 0;
        detailPage = 0;
        refresh();
    }

    private void chooseCategory(int cat) {
        category = cat;
        page = 0;
        tab = 0;
        detailPage = 0;
        if (cat < 9 && !categories.get(cat).isEmpty()) select(categories.get(cat).get(0));
        else {
            selected = 0;
            refresh();
        }
    }

    private int gridPages() {
        return category == 9 ? 1 : Math.max(1, (categories.get(category).size() + PAGE - 1) / PAGE);
    }

    private List<String> episodeLines() {
        String ep = book(selected).getString("episode", "").replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r", "");
        List<String> lines = new ArrayList<>();
        for (String para : ep.split("\n")) {
            String line = "";
            for (String word : para.split(" ")) {
                String t = line.isEmpty() ? word : line + " " + word;
                if (ui.g.textWidth(t, 12, false) > 200 && !line.isEmpty()) {
                    lines.add(line);
                    line = word;
                } else line = t;
            }
            lines.add(line);
        }
        return lines;
    }

    private List<Integer> rewards() {
        List<Integer> r = new ArrayList<>();
        for (WzNode n : book(selected).get("reward").children()) r.add(n.asInt(0));
        return r;
    }

    private List<Object[]> locations() {
        List<Object[]> rows = new ArrayList<>(); // text, heading
        String street = null;
        for (WzNode n : book(selected).get("map").children()) {
            int id = n.asInt(0);
            String st = Names.street(id);
            if (!st.isEmpty() && !st.equals(street)) rows.add(new Object[]{st, true});
            street = st;
            String name = Names.map(id);
            rows.add(new Object[]{name.isEmpty() ? Integer.toString(id) : name, false});
        }
        return rows;
    }

    private int detailPages() {
        if (selected == 0) return 1;
        switch (tab) {
            case 1: return Math.max(1, (episodeLines().size() + 13) / 14);
            case 2: return Math.max(1, (rewards().size() + 19) / 20);
            case 3: return Math.max(1, (locations().size() + 10) / 11);
            default: return 1;
        }
    }

    private void changePage(boolean left, int dir) {
        if (category == 9) return;
        if (left) {
            int p = Math.max(0, Math.min(gridPages() - 1, page + dir));
            if (p == page) return;
            List<Integer> ids = categories.get(category);
            if (p * PAGE < ids.size()) select(ids.get(p * PAGE));
        } else {
            detailPage = Math.max(0, Math.min(detailPages() - 1, detailPage + dir));
        }
        refresh();
    }

    private void search() {
        String q = search.text.replace(" ", "");
        if (q.isEmpty()) return;
        for (List<Integer> ids : categories) {
            for (int id : ids) {
                if (cardName(id).replace(" ", "").equalsIgnoreCase(q)) {
                    Widgets.Focus.set(null);
                    select(id);
                    return;
                }
            }
        }
        ui.open(new maple.ui.Dialogs.Notice(ui, "No monster with that name was found.", false, null, null));
    }

    @Override
    public void refresh() {
        boolean grid = category != 9;
        search.visible = grid;
        for (Button a : arrows) a.visible = grid;
        arrows[0].disabled = page == 0;
        arrows[1].disabled = page + 1 >= gridPages();
        arrows[2].visible = grid && tab != 0;
        arrows[3].visible = grid && tab != 0;
        arrows[2].disabled = detailPage == 0;
        arrows[3].disabled = detailPage + 1 >= detailPages();
    }

    // ------------------------------------------------------------------ drawing

    private void tabArt(UiDraw g, String path, float x, float selX, float y, boolean sel, boolean dis) {
        String state = dis ? "disabled" : sel ? "selected" : "normal";
        Sprite s = ui.assets.sprite(path + "/" + state + "/0");
        if (s != null) g.image(s, sel && !dis ? selX : x, y);
    }

    private void text(UiDraw g, String s, float x, float y, float w, int align, boolean bold) {
        g.text(s, x, y, w, align, true, 12, bold, TEXT);
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (world.data() == null) return;
        // left: category tabs and the grid or the information page
        for (int id = 0; id < 9; id++) tabArt(g, B + "LeftTab/" + id, -7, -2, 126 + id * 20, category == id, false);
        tabArt(g, B + "LeftTabInfo/0", -7, -2, 25, category == 9, false);
        if (category == 9) {
            drawSummary(g);
        } else {
            g.image(ui.assets.sprite(B + "cardSlot"), 40, 25);
            g.fill(search.x, search.y, search.w, search.h, 0xFFFFFFFF);
            List<Integer> ids = categories.get(category);
            for (int i = page * PAGE; i < Math.min(ids.size(), page * PAGE + PAGE); i++) drawCard(g, ids.get(i), i - page * PAGE);
            text(g, (page + 1) + " / " + gridPages(), 89, 287, 74, Align.center, false);
        }
        // right: detail tabs
        float ty = 25;
        int have = count(selected);
        for (int t = 0; t < 4; t++) {
            tabArt(g, B + "RightTab/" + t, 455, 439, ty, category != 9 && tab == t, category == 9 || have < TAB_COUNTS[t]);
            ty += t == 0 ? 39 : 37;
        }
        if (category != 9 && selected != 0) drawDetail(g, have);
        if (menuCard != 0) drawMenu(g);
    }

    private void drawSummary(UiDraw g) {
        Map<Integer, Integer> c = cards();
        int special = 0, normal = 0;
        for (int id : c.keySet()) {
            if ((id / 1000) % 10 == 8) special++;
            else normal++;
        }
        int total = special + normal;
        int level = 0, next = 1;
        do {
            level++;
            next += level * 10;
        } while (total >= next);
        g.image(ui.assets.sprite(B + "infoPage"), 40, 30);
        Sprite icon = ui.assets.sprite(B + "icon/" + (level - 1));
        if (icon != null) g.image(icon, 117, 43);
        text(g, Integer.toString(level), 145, 30, 35, Align.right, false);
        text(g, Integer.toString(total), 145, 81, 35, Align.right, false);
        text(g, Integer.toString(special), 145, 114, 35, Align.right, false);
        text(g, Integer.toString(normal), 145, 133, 35, Align.right, false);
        if (cover() != 0) text(g, cardName(cover()), 81, 171, 100, Align.right, false);
    }

    private void drawCard(UiDraw g, int card, int slot) {
        float x = 48 + (slot % COLS) * 33, y = 56 + (slot / COLS) * 45;
        int n = count(card);
        Sprite raw = ui.assets.sprite(ItemInfo.get(card).iconRaw());
        if (raw != null) {
            if (n == 0) g.alpha(85 / 255f);
            g.image(raw, x, y);
            g.resetColor();
        }
        if (n == 5) g.image(ui.assets.sprite(B + "fullMark"), x + 1, y + 26);
        else if (n > 0) g.image(ui.assets.sprite("Basic.img/ItemNo/" + n), x + 1, y + 26);
        if (cover() == card) g.image(ui.assets.sprite(B + "cover"), x - 2, y - 2);
        if (selected == card) g.image(ui.assets.sprite(B + "select"), x - 2, y - 2);
    }

    private void drawDetail(UiDraw g, int have) {
        text(g, cardName(selected), 250, 30, 195, Align.center, true);
        int mob = mobOf(g, selected, ui);
        if (tab == 0 && have > 0) {
            WzNode src = ui.assets.wz.get("Mob/" + String.format("%07d", mob) + ".img");
            String link = src.get("info").getString("link", "");
            if (!link.isEmpty()) src = ui.assets.wz.get("Mob/" + link + ".img");
            WzNode f = src.get("stand").get("0");
            if (!f.exists()) f = src.get("fly").get("0");
            Sprite s = f.exists() ? ui.assets.sprite(f) : null;
            if (s != null) {
                float scale = Math.min(1f, Math.min(200f / s.w, 200f / s.h));
                float sw = s.w * scale, sh = s.h * scale;
                g.stretched(s, 350 - sw / 2, 250 - sh, sw, sh);
            }
            WzNode info = ui.assets.wz.get("Mob/" + String.format("%07d", mob) + ".img/info");
            String hp = have > 1 ? Integer.toString(info.getInt("maxHP", 0)) : "???";
            String mp = have > 1 ? Integer.toString(info.getInt("maxMP", 0)) : "???";
            text(g, "HP : " + hp + "   MP : " + mp, 278, 289, 130, Align.center, false);
        } else if (tab == 1 && have >= 3) {
            List<String> lines = episodeLines();
            StringBuilder sb = new StringBuilder();
            for (int i = detailPage * 14; i < Math.min(lines.size(), detailPage * 14 + 14); i++) sb.append(lines.get(i)).append('\n');
            g.text(sb.toString(), 250, 52, 200, Align.left, false, 12, false, TEXT);
        } else if (tab == 2 && have >= 4) {
            List<Integer> r = rewards();
            for (int i = detailPage * 20; i < Math.min(r.size(), detailPage * 20 + 20); i++) {
                int p = i - detailPage * 20;
                Sprite ic = ui.assets.sprite(ItemInfo.get(r.get(i)).icon());
                if (ic != null) g.anchored(ic, 278 + (p % 4) * 36, 80 + (p / 4) * 36 + 32);
            }
        } else if (tab == 3 && have >= 5) {
            List<Object[]> rows = locations();
            for (int i = detailPage * 11; i < Math.min(rows.size(), detailPage * 11 + 11); i++) {
                Object[] row = rows.get(i);
                boolean head = (Boolean) row[1];
                float x = head ? 260 : 272, y = 60 + (i - detailPage * 11) * 19;
                String s = (String) row[0];
                while (s.length() > 1 && g.textWidth(s, 12, head) > 440 - x) s = s.substring(0, s.length() - 1);
                g.text(s, x, y, 12, head, TEXT);
            }
        }
        if (tab != 0 && have >= TAB_COUNTS[tab]) text(g, (detailPage + 1) + " / " + detailPages(), 314, 287, 72, Align.center, false);
    }

    private void drawMenu(UiDraw g) {
        g.image(ui.assets.sprite(B + "ContextMenu/t"), menuX, menuY);
        g.image(ui.assets.sprite(B + "ContextMenu/c"), menuX, menuY + 19);
        g.image(ui.assets.sprite(B + "ContextMenu/s"), menuX, menuY + 32);
        boolean canRegister = count(menuCard) > 0 && cover() != menuCard;
        boolean canRelease = cover() != 0;
        g.image(ui.assets.sprite(B + "ContextMenu/BtRegister/" + (canRegister ? "normal" : "disabled") + "/0"), menuX + 2, menuY + 3);
        g.image(ui.assets.sprite(B + "ContextMenu/BtRelease/" + (canRelease ? "normal" : "disabled") + "/0"), menuX + 2, menuY + 18);
    }

    // ------------------------------------------------------------------ input

    private int cardAt(float lx, float ly) {
        if (category == 9) return 0;
        List<Integer> ids = categories.get(category);
        for (int i = page * PAGE; i < Math.min(ids.size(), page * PAGE + PAGE); i++) {
            int slot = i - page * PAGE;
            float x = 48 + (slot % COLS) * 33, y = 56 + (slot / COLS) * 45;
            if (lx >= x && lx < x + 27 && ly >= y && ly < y + 38) return ids.get(i);
        }
        return 0;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        if (menuCard != 0) {
            if (lx >= menuX && lx < menuX + 90 && ly >= menuY + 3 && ly < menuY + 18 && count(menuCard) > 0 && cover() != menuCard) {
                world.setBookCover(menuCard);
            } else if (lx >= menuX && lx < menuX + 90 && ly >= menuY + 18 && ly < menuY + 33 && cover() != 0) {
                world.setBookCover(0);
            }
            menuCard = 0;
            return false;
        }
        // left tabs
        if (lx < 40) {
            if (ly >= 25 && ly < 25 + 90) {
                UiSounds.play("Tab");
                chooseCategory(9);
                return false;
            }
            for (int id = 0; id < 9; id++) {
                float y = 126 + id * 20;
                if (ly >= y && ly < y + 20) {
                    UiSounds.play("Tab");
                    chooseCategory(id);
                    return false;
                }
            }
        }
        // right tabs
        if (lx >= 439) {
            float ty = 25;
            for (int t = 0; t < 4; t++) {
                float th = t == 0 ? 39 : 37;
                if (ly >= ty && ly < ty + th) {
                    if (category != 9 && count(selected) >= TAB_COUNTS[t]) {
                        UiSounds.play("Tab");
                        tab = t;
                        detailPage = 0;
                        refresh();
                    }
                    return false;
                }
                ty += th;
            }
        }
        int card = cardAt(lx, ly);
        if (card != 0) select(card);
        return false;
    }

    @Override
    public void onRightClick(float lx, float ly) {
        int card = cardAt(lx, ly);
        if (card == 0) return;
        select(card);
        menuCard = card;
        menuX = Math.max(0, Math.min(386, lx));
        menuY = Math.max(0, Math.min(291, ly));
    }

    /** Touch screens have no right button: double-tapping a card opens the cover menu. */
    @Override
    public void onDoubleClick(float lx, float ly) {
        onRightClick(lx, ly);
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int card = cardAt(lx, ly);
        if (card != 0) {
            Tooltip t = Tooltip.text(cardName(card));
            t.line(count(card) + " / 5", 0xFFFFFFFF);
            return t;
        }
        if (lx < 40 && category >= 0) {
            for (int id = 0; id < 9; id++) {
                float y = 126 + id * 20;
                if (ly >= y && ly < y + 20) {
                    int owned = 0;
                    for (int c : categories.get(id)) if (count(c) > 0) owned++;
                    return Tooltip.text(owned + " / " + categories.get(id).size());
                }
            }
        }
        if (tab == 2 && category != 9 && count(selected) >= 4) {
            List<Integer> r = rewards();
            for (int i = detailPage * 20; i < Math.min(r.size(), detailPage * 20 + 20); i++) {
                int p = i - detailPage * 20;
                float x = 278 + (p % 4) * 36, y = 80 + (p / 4) * 36;
                if (lx >= x && lx < x + 32 && ly >= y && ly < y + 32) {
                    return new ItemTooltip(ui.assets, r.get(i), null, world.data().stats, false, null);
                }
            }
        }
        if (lx >= 439) {
            float ty = 25;
            for (int t = 0; t < 4; t++) {
                float th = t == 0 ? 39 : 37;
                if (ly >= ty && ly < ty + th) return Tooltip.text(TAB_NAMES[t]);
                ty += th;
            }
        }
        return null;
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        changePage(lx < 237, Integer.signum(amount));
        return true;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            if (menuCard != 0) menuCard = 0;
            else close();
            return true;
        }
        return false;
    }
}
