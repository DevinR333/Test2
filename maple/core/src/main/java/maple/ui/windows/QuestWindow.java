package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.game.Names;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.PlayerData;
import maple.ui.Button;
import maple.ui.Dialogs;
import maple.ui.RichText;
import maple.ui.Scrollbar;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quest journal (UIWindow.img/Quest, 0087ec3f): Available / In progress / Completed tabs on the Tab2
 * strip (72, 72, 71 wide from x 7), 14 rows of 22px from y 49 with Quest/icon markers, chain headers
 * (ff9fb5ce) and the selection fill (ff396093), VScr3 at (225, 50, 309). Selecting a quest attaches the
 * 305px detail (backgrnd2) at x 240: title at (35, 40), NPC at (242, 110) with its name, the
 * description pane at (17, 122, 259 x 243) and Give up / Alert or Find NPC on the bottom row.
 */
public final class QuestWindow extends Window {
    private static final String[] TAB_NAMES = {"Available", "In progress", "Completed"};
    private final World world;
    private int tab;
    private final Scrollbar scroll;
    private final List<Object[]> rows = new ArrayList<>(); // {questId or -1, name, chainKey}
    private final java.util.Set<String> collapsed = new java.util.HashSet<>();
    private int selected = -1;
    private RichText detailText;
    private RichText titleText;
    private final Scrollbar detailScroll;
    private final List<Button> detailButtons = new ArrayList<>();
    private final float listW;
    private String signature = "";

    public QuestWindow(Ui ui, World world) {
        super(ui, "Quest", "UIWindow.img/Quest/backgrnd");
        this.world = world;
        listW = w;
        add(new Button(ui.assets, "Basic.img/BtClose", 225, 6, this::close));
        scroll = add(new Scrollbar(ui.assets, "VScr3", 225, 50, 309));
        detailScroll = add(new Scrollbar(ui.assets, "VScr3", 240 + 283, 122, 243));
        detailScroll.visible = false;
        refresh();
    }

    // ------------------------------------------------------------ quest data

    private static WzNode info(World w, int id) {
        return w.wz.get("Quest/QuestInfo.img/" + id);
    }

    /** 00a2a0be: 1 started, 2 completed, 0 available (start check passes), -1 hidden. */
    private int partition(int id) {
        PlayerData d = world.data();
        if (d.completedQuests.containsKey(id)) return 2;
        if (d.startedQuests.containsKey(id)) return 1;
        return available(id) ? 0 : -1;
    }

    private boolean available(int id) {
        PlayerData d = world.data();
        WzNode qi = info(world, id);
        if (qi.getInt("blocked", 0) != 0) return false;
        WzNode c = world.wz.get("Quest/Check.img/" + id + "/0");
        if (!c.exists()) return false;
        if (c.getInt("npc", 0) == 0) return false;
        if (c.getInt("normalAutoStart", 0) != 0) return false;
        int level = d.stats.level;
        if (c.get("lvmin").exists() && level < c.getInt("lvmin", 0)) return false;
        if (c.get("lvmax").exists() && level > c.getInt("lvmax", 999)) return false;
        WzNode jobs = c.get("job");
        if (jobs.exists() && jobs.childCount() > 0) {
            boolean ok = false;
            for (WzNode j : jobs.children()) if (j.asInt(-1) == d.stats.job) ok = true;
            if (!ok) return false;
        }
        for (WzNode q : c.get("quest").children()) {
            int qid = q.getInt("id", 0), state = q.getInt("state", 0);
            int have = d.completedQuests.containsKey(qid) ? 2 : d.startedQuests.containsKey(qid) ? 1 : 0;
            if (have != state) return false;
        }
        return true;
    }

    @Override
    public void refresh() {
        if (world.data() == null) return;
        PlayerData d = world.data();
        String sig = tab + ":" + d.stats.level + ":" + d.stats.job + ":" + d.startedQuests.keySet() + ":" + d.completedQuests.size() + ":" + collapsed;
        if (!sig.equals(signature)) {
            signature = sig;
            rebuild();
        }
        if (selected >= 0 && partition(selected) != tab) select(-1);
    }

    private void rebuild() {
        rows.clear();
        List<int[]> quests = new ArrayList<>();
        WzNode all = world.wz.get("Quest/QuestInfo.img");
        if (tab == 0) {
            for (WzNode q : all.children()) {
                try {
                    int id = Integer.parseInt(q.name);
                    if (partition(id) == 0) quests.add(new int[]{id, q.getInt("order", 0)});
                } catch (NumberFormatException ignored) {
                }
            }
        } else {
            for (int id : (tab == 1 ? world.data().startedQuests.keySet() : world.data().completedQuests.keySet())) {
                WzNode q = all.get(Integer.toString(id));
                if (q.exists() && !q.getString("name", "").isEmpty()) quests.add(new int[]{id, q.getInt("order", 0)});
            }
        }
        quests.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        Map<String, List<Integer>> chains = new LinkedHashMap<>();
        for (int[] q : quests) {
            String parent = all.get(Integer.toString(q[0])).getString("parent", "");
            if (parent.isEmpty()) rows.add(new Object[]{q[0], all.get(Integer.toString(q[0])).getString("name", ""), null});
            else chains.computeIfAbsent(parent, k -> new ArrayList<>()).add(q[0]);
        }
        for (Map.Entry<String, List<Integer>> e : chains.entrySet()) {
            String key = tab + ":" + e.getKey();
            rows.add(new Object[]{-1, e.getKey(), key});
            if (collapsed.contains(key)) continue;
            for (int id : e.getValue()) rows.add(new Object[]{id, all.get(Integer.toString(id)).getString("name", ""), null});
        }
        scroll.setRange(Math.max(1, rows.size() - 13), scroll.position);
    }

    private void select(int id) {
        selected = id;
        for (Button b : detailButtons) remove(b);
        detailButtons.clear();
        if (id < 0) {
            w = listW;
            detailScroll.visible = false;
            detailText = null;
            return;
        }
        Sprite d2 = ui.assets.sprite("UIWindow.img/Quest/backgrnd2");
        w = 240 + (d2 == null ? 305 : d2.w);
        WzNode qi = info(world, id);
        GameMarkup markup = new GameMarkup(world, ui.assets);
        titleText = RichText.layout(ui.g, qi.getString("name", ""), 150, markup, 0xFFFFFFFF);
        String body = qi.getString(Integer.toString(tab), "");
        if (tab == 1) body = body + objectives(id);
        detailText = RichText.layout(ui.g, body, 259, markup, 0xFF222222);
        detailScroll.setRange(Math.max(1, (int) Math.ceil((detailText.height - 243) / 8) + 1), 0);
        detailScroll.visible = detailText.height > 243;
        detailButtons.add(add(new Button(ui.assets, "Basic.img/BtClose", 240 + 285, 6, () -> select(-1))));
        detailButtons.add(add(new Button(ui.assets, "UIWindow.img/Quest/BtDetail", 240 + 125, 94, () -> {})));
        if (tab == 0) {
            detailButtons.add(add(new Button(ui.assets, "UIWindow.img/Quest/BtMarkNpc", 240 + 217, 372, () -> {})));
        } else if (tab == 1) {
            detailButtons.add(add(new Button(ui.assets, "Basic.img/BtQGiveup", 240 + 242, 372, this::giveUp)));
            detailButtons.add(add(new Button(ui.assets, "UIWindow.img/Quest/BtAlert", 240 + 150, 372, () -> {})));
        }
    }

    /** In-progress requirements: monsters hunted and items collected so far. */
    private String objectives(int id) {
        StringBuilder sb = new StringBuilder();
        WzNode check = world.wz.get("Quest/Check.img/" + id + "/1");
        String progress = world.data().startedQuests.getOrDefault(id, "");
        int index = 0;
        for (WzNode mob : check.get("mob").children()) {
            int mobId = mob.getInt("id", 0), count = mob.getInt("count", 0);
            int have = 0;
            if (progress.length() >= (index + 1) * 3) {
                try {
                    have = Integer.parseInt(progress.substring(index * 3, index * 3 + 3));
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append("\n#b").append(Names.mob(mobId)).append(" : ").append(Math.min(have, count)).append(" / ").append(count).append("#k");
            index++;
        }
        for (WzNode item : check.get("item").children()) {
            int itemId = item.getInt("id", 0), count = item.getInt("count", 0);
            if (count <= 0) continue;
            sb.append("\n#b#t").append(itemId).append("# : #c").append(itemId).append("# / ").append(count).append("#k");
        }
        return sb.length() == 0 ? "" : "\n" + sb;
    }

    private void giveUp() {
        int id = selected;
        ui.open(new Dialogs.Notice(ui, "Are you sure you want to give up on this quest?", true, () -> {
            world.questAction(3, id, 0, 0);
            select(-1);
        }, null));
    }

    // ------------------------------------------------------------ drawing

    private void drawTabs(UiDraw g) {
        g.image(ui.assets.sprite("Basic.img/Tab2/left" + (tab == 0 ? 1 : 0)), 3, 23);
        float x = 7;
        for (int i = 0; i < 3; i++) {
            int width = i < 2 ? 72 : 71;
            boolean on = i == tab;
            Sprite fill = ui.assets.sprite("Basic.img/Tab2/fill" + (on ? 1 : 0));
            if (fill != null) g.stretched(fill, x, 23, width, fill.h);
            String edge = i == 2 ? "right" + (on ? 1 : 0) : "middle" + (on ? 1 : (tab == i + 1 ? 2 : 0));
            g.image(ui.assets.sprite("Basic.img/Tab2/" + edge), x + width, 23);
            Sprite label = ui.assets.sprite("UIWindow.img/Quest/Tab/" + (on ? "enabled" : "disabled") + "/" + i);
            if (label != null) g.image(label, x + (int) (width / 2) - (int) (label.w / 2), 23 + 2 + 9 - (int) (label.h / 2));
            x += width + 8;
        }
    }

    @Override
    public void draw(UiDraw g) {
        if (selected >= 0) {
            Sprite d2 = ui.assets.sprite("UIWindow.img/Quest/backgrnd2");
            if (d2 != null) g.image(d2, 240, 0);
            drawDetail(g);
        }
        super.draw(g);
    }

    @Override
    protected void drawContent(UiDraw g) {
        drawTabs(g);
        if (world.data() == null) return;
        if (rows.isEmpty()) {
            Sprite n = ui.assets.sprite("UIWindow.img/Quest/notice" + tab);
            if (n != null) g.image(n, (int) ((210 - n.w) / 2) - 2, 50 + (int) ((309 - n.h) / 2));
        }
        int start = scroll.position;
        for (int i = start; i < Math.min(rows.size(), start + 14); i++) {
            Object[] row = rows.get(i);
            float y = 49 + (i - start) * 22;
            int id = (Integer) row[0];
            boolean header = id < 0;
            boolean sel = id >= 0 && id == selected;
            if (header) g.fill(10, y, 208, 19, 0xFF9FB5CE);
            else if (sel) g.fill(28, y + 1, 192, 19, 0xFF396093);
            if (header) {
                Sprite b = ui.assets.sprite("Basic.img/" + (collapsed.contains((String) row[2]) ? "BtMax" : "BtMin") + "/normal/0");
                if (b != null) g.image(b, 13, y + 3);
            } else {
                String icon = tab == 0 ? "UIWindow.img/Quest/icon0" : tab == 2 ? "UIWindow.img/Quest/icon4" : "UIWindow.img/Quest/icon1";
                Sprite s = ui.assets.sprite(icon);
                if (s != null) g.image(s, 13, y + 2);
            }
            String name = (String) row[1];
            while (name.length() > 1 && g.textWidth(name, 11, false) > 188) name = name.substring(0, name.length() - 1);
            g.text(name, 30, y + 3, 11, false, sel ? 0xFFFFFFFF : 0xFF222222);
        }
    }

    private void drawDetail(UiDraw g) {
        float dx = 240;
        if (titleText != null) titleText.draw(g, dx + 35, 40, -1, 0, 48);
        WzNode c = world.wz.get("Quest/Check.img/" + selected + "/" + (tab == 0 ? 0 : 1));
        int npcId = c.getInt("npc", 0);
        if (npcId == 0) npcId = world.wz.get("Quest/Check.img/" + selected + "/0").getInt("npc", 0);
        if (npcId != 0) {
            WzNode src = world.wz.get("Npc/" + String.format("%07d", npcId) + ".img");
            String link = src.get("info").getString("link", "");
            if (!link.isEmpty()) src = world.wz.get("Npc/" + link + ".img");
            WzNode f = src.get("stand").get("0");
            Sprite p = f.exists() ? ui.assets.sprite(f) : null;
            if (p != null) g.anchored(p, dx + 242, 110);
            g.text(Names.npc(npcId), dx + 191, 104, 102, Align.center, false, 12, false, 0xFFFFFFFF);
        }
        if (detailText != null) {
            float off = detailScroll.visible ? detailScroll.position * 8 : 0;
            detailText.draw(g, dx + 17, 122 - off, -1, off, off + 243);
        }
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        if (ly >= 23 && ly < 42 && lx >= 7 && lx < 240) {
            float x = 7;
            for (int i = 0; i < 3; i++) {
                int width = i < 2 ? 72 : 71;
                if (lx >= x && lx < x + width + 4) {
                    if (tab != i) {
                        tab = i;
                        UiSounds.play("Tab");
                        select(-1);
                        scroll.setRange(1, 0);
                        refresh();
                    }
                    return false;
                }
                x += width + 8;
            }
        }
        if (lx >= 10 && lx < 220 && ly >= 49 && ly < 49 + 14 * 22) {
            int i = scroll.position + (int) ((ly - 49) / 22);
            if (i < rows.size()) {
                Object[] row = rows.get(i);
                int id = (Integer) row[0];
                if (id < 0) {
                    String key = (String) row[2];
                    if (!collapsed.remove(key)) collapsed.add(key);
                    refresh();
                } else {
                    select(id);
                }
            }
        }
        return false;
    }

    @Override
    public boolean contains(float lx, float ly) {
        if (lx >= 0 && ly >= 0 && lx < listW && ly < h) return true;
        return selected >= 0 && lx >= 240 && lx < w && ly >= 0 && ly < h;
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        if (lx >= 240 && detailScroll.visible) detailScroll.scroll(amount);
        else scroll.scroll(amount);
        return true;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        refresh();
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    static String tabName(int i) {
        return TAB_NAMES[i];
    }
}
