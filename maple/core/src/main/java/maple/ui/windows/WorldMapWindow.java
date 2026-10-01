package maple.ui.windows;

import com.badlogic.gdx.Input;
import maple.game.Names;
import maple.game.World;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The world map (UIWindow.img/WorldMap border and title, Map.wz/WorldMap/*.img regions; 009eb772..):
 * the region's BaseImg at (13, 35) in a 640x470 inset, its MapLink overlays (shown under the pointer,
 * opened with a click; right-click goes back to the parent region), spot markers from
 * MapHelper.img/worldMap/mapImage with title / description tooltips, and the curPos marker on your map.
 */
public final class WorldMapWindow extends Window {
    private static final int LEFT = 13, TOP = 35;
    private final World world;
    private String region;
    private WzNode node;
    private Sprite base;
    private float ax, ay; // BaseImg origin on the window
    private final List<Object[]> links = new ArrayList<>(); // target, tooltip, canvas node, sprite
    private final List<WzNode> spots = new ArrayList<>();
    private Object[] hovered;
    private final Animation cur;
    private long time;

    public WorldMapWindow(Ui ui, World world) {
        super(ui, "WorldMap", null);
        this.world = world;
        w = 666;
        h = 524;
        keepPosition = true;
        x = Math.round((Ui.W - w) / 2f);
        y = Math.max(0, Math.round((Ui.H - h) / 2f) - 10);
        cur = ui.assets.animation(ui.assets.wz.get("Map/MapHelper.img/worldMap/curPos"));
        addClose();
        show(regionFor(world.field == null ? -1 : world.field.id));
    }

    private WzNode regionNode(String name) {
        return ui.assets.wz.get("Map/WorldMap/" + name + ".img");
    }

    /** The narrowest region whose spot lists the field. */
    private String regionFor(int field) {
        String best = "WorldMap";
        int count = Integer.MAX_VALUE;
        for (WzNode img : ui.assets.wz.get("Map/WorldMap").children()) {
            String name = img.name.replace(".img", "");
            for (WzNode spot : img.get("MapList").children()) {
                WzNode maps = spot.get("mapNo");
                boolean has = false;
                for (WzNode m : maps.children()) if (m.asInt(-1) == field) has = true;
                if (has && maps.childCount() < count) {
                    best = name;
                    count = maps.childCount();
                }
            }
        }
        return best;
    }

    private void show(String name) {
        WzNode n = regionNode(name);
        if (!n.exists()) return;
        region = name;
        node = n;
        base = ui.assets.sprite(n.get("BaseImg").get("0"));
        ax = LEFT + (base == null ? 320 : base.ox);
        ay = TOP + (base == null ? 235 : base.oy);
        links.clear();
        for (WzNode l : n.get("MapLink").children()) {
            WzNode img = l.get("link").get("linkImg");
            links.add(new Object[]{l.get("link").getString("linkMap", ""), l.getString("toolTip", ""), img, ui.assets.sprite(img), null});
        }
        spots.clear();
        for (WzNode s : n.get("MapList").children()) spots.add(s);
        hovered = null;
    }

    private void parent() {
        String p = node == null ? "" : node.get("info").getString("parentMap", "");
        if (!p.isEmpty()) {
            show(p);
            UiSounds.play("BtMouseClick");
        }
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        time += ms;
        hovered = linkAt(ui.mouseX - x, ui.mouseY - y);
    }

    /** The first link whose canvas pixel under the pointer is not transparent (009ee00e). */
    private Object[] linkAt(float lx, float ly) {
        for (Object[] l : links) {
            WzNode c = ((WzNode) l[2]).resolve();
            Sprite s = (Sprite) l[3];
            if (s == null || !c.isCanvas()) continue;
            int px = (int) Math.floor(lx - (ax - s.ox)), py = (int) Math.floor(ly - (ay - s.oy));
            if (px < 0 || py < 0 || px >= c.width() || py >= c.height()) continue;
            if (l[4] == null) l[4] = c.rgba();
            byte[] rgba = (byte[]) l[4];
            if (rgba == null) continue;
            int i = (py * c.width() + px) * 4 + 3;
            if (i < rgba.length && rgba[i] != 0) return l;
        }
        return null;
    }

    private void part(UiDraw g, String p, float px, float py, float pw, float ph) {
        Sprite s = ui.assets.sprite("UIWindow.img/WorldMap/Border/" + p);
        if (s != null) g.stretched(s, px, py, pw, ph);
    }

    @Override
    protected void drawContent(UiDraw g) {
        g.fill(2, 2, w - 4, h - 4, 0xFFFFFFFF);
        g.fill(13, 35, 640, 470, 0xFFD3DBE4);
        g.fill(12, 34, 641, 1, 0xFF6A8594);
        g.fill(12, 34, 1, 470, 0xFF7D98B3);
        g.fill(13, 505, 640, 1, 0xFFEAEFF2);
        g.fill(653, 35, 1, 470, 0xFFEAEFF2);
        float right = w - 7, bottom = h - 18;
        part(g, "0", 0, 0, 7, 33);
        part(g, "1", 7, 0, w - 14, 33);
        part(g, "2", right, 0, 7, 32);
        part(g, "3", 0, 32, 7, bottom - 32);
        part(g, "4", right, 32, 7, bottom - 32);
        part(g, "5", 0, bottom, 7, 18);
        part(g, "6", 7, bottom, w - 14, 18);
        part(g, "7", right, bottom, 7, 18);
        Sprite title = ui.assets.sprite("UIWindow.img/WorldMap/title");
        if (title != null) g.image(title, 15, 10);
        if (base != null) g.image(base, LEFT, TOP);
        if (hovered != null && hovered[3] != null) g.anchored((Sprite) hovered[3], ax, ay);
        int field = world.field == null ? -1 : world.field.id;
        for (WzNode s : spots) {
            Sprite m = ui.assets.sprite(ui.assets.wz.get("Map/MapHelper.img/worldMap/mapImage/" + s.getInt("type", 0)));
            WzNode p = s.get("spot");
            if (m != null) g.anchored(m, ax + p.vx(), ay + p.vy());
        }
        for (WzNode s : spots) {
            boolean here = false;
            for (WzNode mn : s.get("mapNo").children()) if (mn.asInt(-1) == field) here = true;
            WzNode p = s.get("spot");
            if (here && cur != null && !cur.isEmpty()) g.anim(cur, ax + p.vx(), ay + p.vy(), time);
        }
    }

    private WzNode spotAt(float lx, float ly) {
        for (WzNode s : spots) {
            Sprite m = ui.assets.sprite(ui.assets.wz.get("Map/MapHelper.img/worldMap/mapImage/" + s.getInt("type", 0)));
            int r = m == null ? 5 : Math.max(2, m.w / 3);
            WzNode p = s.get("spot");
            float sx = ax + p.vx(), sy = ay + p.vy();
            if (Math.abs(lx - sx) <= r && Math.abs(ly - sy) <= r) return s;
        }
        return null;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        Object[] l = linkAt(lx, ly);
        if (l != null && !((String) l[0]).isEmpty()) {
            show((String) l[0]);
            UiSounds.play("BtMouseClick");
        }
        return false;
    }

    @Override
    public void onRightClick(float lx, float ly) {
        parent();
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        WzNode s = spotAt(lx, ly);
        if (s != null) {
            Tooltip t = new Tooltip();
            String title = s.getString("title", "");
            Set<String> names = new LinkedHashSet<>();
            for (WzNode mn : s.get("mapNo").children()) {
                String n = Names.map(mn.asInt(0));
                if (!n.isEmpty()) names.add(n);
            }
            if (title.isEmpty() && !names.isEmpty()) title = names.iterator().next();
            t.title(title);
            String desc = s.getString("desc", "");
            if (!desc.isEmpty()) t.line(desc, 0xFFFFFFFF);
            if (!(names.size() == 1 && names.contains(title))) for (String n : names) t.line(n, 0xFFFFFFFF);
            t.width = 220;
            return t;
        }
        Object[] l = linkAt(lx, ly);
        if (l != null && !((String) l[1]).isEmpty()) return Tooltip.text((String) l[1]);
        return null;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            if (!back()) close(); // phones: Back walks up the regions, then closes
            return true;
        }
        if (keycode == Input.Keys.BACKSPACE) {
            parent();
            return true;
        }
        return false;
    }

    /** Touch screens have no right button: a back press on a sub-region goes up one level. */
    public boolean back() {
        String p = node == null ? "" : node.get("info").getString("parentMap", "");
        if (p.isEmpty()) return false;
        show(p);
        return true;
    }
}
