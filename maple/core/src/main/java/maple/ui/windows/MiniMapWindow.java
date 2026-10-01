package maple.ui.windows;

import com.badlogic.gdx.utils.Align;
import maple.game.Npc;
import maple.game.World;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.map.Field;
import maple.map.Portal;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Window;
import maple.wz.WzNode;

/**
 * The minimap (UIWindow.img/MiniMap, 00858344): modes 0 expanded (street and map names), 1 compact
 * (260 wide) and 2 title-only (20 high). The map's miniMap canvas is cropped around the player, never
 * scaled; NPC, portal (types 2 and 7) and user markers come from Map.wz/MapHelper.img/minimap. The
 * MiniMap key cycles 1 -> 0 -> 2 -> 1.
 */
public final class MiniMapWindow extends Window {
    private final World world;
    private int mode = 1;
    private int fieldId = -1;
    private Sprite canvas;
    private Animation mark;
    private int centerX, centerY, mag, mapW, mapH;
    private String mapName = "", street = "";
    private Button btMap, btMax, btMin;
    private float viewX, viewY, viewW, viewH;

    public MiniMapWindow(Ui ui, World world) {
        super(ui, "MiniMap", null);
        this.world = world;
        x = 0;
        y = 0;
        keepPosition = true;
        reload();
    }

    private void reload() {
        Field f = world.field;
        fieldId = f == null ? -1 : f.id;
        canvas = null;
        mark = null;
        if (f != null) {
            mapName = f.mapName;
            street = f.streetName;
            WzNode src = ui.assets.wz.get(Field.imgPath(f.id));
            WzNode link = src.get("info").get("link");
            if (link.exists()) src = ui.assets.wz.get(Field.imgPath(link.asInt(0)));
            WzNode mm = src.get("miniMap");
            if (mm.exists() && mm.get("canvas").exists()) {
                canvas = ui.assets.sprite(mm.get("canvas"));
                centerX = mm.getInt("centerX", 0);
                centerY = mm.getInt("centerY", 0);
                mag = mm.getInt("mag", 0);
                mapW = mm.getInt("width", 0);
                mapH = mm.getInt("height", 0);
            }
            String markName = src.get("info").getString("mapMark", "");
            if (!markName.isEmpty() && !markName.equals("None")) {
                Animation a = ui.assets.animation(ui.assets.wz.get("Map/MapHelper.img/mark/" + markName));
                if (!a.isEmpty()) mark = a;
            }
        }
        layoutMode();
    }

    /** M: compact -> expanded -> title-only -> compact. */
    public void cycle() {
        if (canvas == null) return;
        mode = (mode + 2) % 3;
        layoutMode();
    }

    private int displayMode() {
        return canvas == null ? 2 : mode;
    }

    private void layoutMode() {
        int dm = displayMode();
        w = 260;
        if (dm == 0 && canvas != null) {
            float text = Math.max(ui.g.textWidth(street, 12, false), ui.g.textWidth(mapName, 12, false)) + (mark != null ? 60 : 20);
            w = Math.min(600, Math.max(260, Math.max(canvas.w + 12, text)));
            h = Math.min(450, canvas.h + 87);
        } else if (dm == 2) {
            w = Math.max(260, Math.min(600, ui.g.textWidth(titleOnly(), 12, false) + 88));
            h = 20;
        } else {
            h = Math.min(153, (canvas == null ? 110 : canvas.h) + 43);
        }
        dragHeight = 20;
        if (btMap != null) {
            remove(btMap);
            remove(btMax);
            remove(btMin);
        }
        boolean min = dm == 2;
        float bx = w - (min ? 44 : 42), by = min ? 4 : 6;
        btMap = add(new Button(ui.assets, "UIWindow.img/MiniMap/BtMap", bx, by, () -> {}));
        btMax = add(new Button(ui.assets, "Basic.img/BtMax", bx - 14, by, () -> setMode(mode - 1)));
        btMin = add(new Button(ui.assets, "Basic.img/BtMin", bx - 27, by, () -> setMode(mode + 1)));
        btMax.disabled = canvas == null || dm == 0;
        btMin.disabled = canvas == null || dm == 2;
        float top = dm == 0 ? 72 : 29;
        viewW = Math.min(w - 12, canvas == null ? w - 12 : canvas.w);
        viewX = (int) ((w - viewW) / 2);
        viewY = top;
        viewH = Math.max(0, h - top - (dm == 0 ? 15 : 14));
    }

    private void setMode(int m) {
        mode = Math.max(0, Math.min(2, m));
        layoutMode();
    }

    private String titleOnly() {
        if (!mapName.isEmpty() && !street.isEmpty()) return mapName + "  " + street;
        return mapName.isEmpty() ? street : mapName;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        int id = world.field == null ? -1 : world.field.id;
        if (id != fieldId) reload();
    }

    private void framePart(UiDraw g, String path, float px, float py, float pw, float ph) {
        Sprite s = ui.assets.sprite(path);
        if (s != null && pw > 0 && ph > 0) g.stretched(s, px, py, pw, ph);
    }

    @Override
    protected void drawContent(UiDraw g) {
        int dm = displayMode();
        String b = "UIWindow.img/MiniMap/";
        if (dm == 2) {
            g.alpha(180 / 255f);
            framePart(g, b + "Min/w", 0, 0, 8, 20);
            framePart(g, b + "Min/c", 8, 0, w - 12, 20);
            framePart(g, b + "Min/e", w - 4, 0, 4, 20);
            g.resetColor();
            g.outlined(titleOnly(), 6, 3, 12, false, 0xFFFFFFFF, 0xFF52759C);
            return;
        }
        String p = b + (dm == 0 ? "MaxMap/" : "MinMap/");
        Sprite nw = ui.assets.sprite(p + "nw"), ne = ui.assets.sprite(p + "ne"), sw = ui.assets.sprite(p + "sw");
        float top = nw == null ? 20 : nw.h, left = nw == null ? 6 : nw.w;
        float right = ne == null ? 6 : ne.w, bottom = sw == null ? 6 : sw.h;
        float[] ws = {left, w - left - right, right}, hs = {top, h - top - bottom, bottom};
        String[] parts = {"nw", "n", "ne", "w", "c", "e", "sw", "s", "se"};
        float yy = 0;
        for (int r = 0; r < 3; r++) {
            float xx = 0;
            for (int c = 0; c < 3; c++) {
                framePart(g, p + parts[r * 3 + c], xx, yy, ws[c], hs[r]);
                xx += ws[c];
            }
            yy += hs[r];
        }
        Sprite title = ui.assets.sprite(b + "title");
        if (title != null) g.image(title, 8, 10);
        if (dm == 0) {
            float nx = mark != null ? 48 : 8;
            if (mark != null) g.anim(mark, 6, 23, g.timeMs);
            g.outlined(street, nx, 27, 12, false, 0xFFFFFFFF, 0xFF52759C);
            g.outlined(mapName, nx, 43, 12, false, 0xFFFFFFFF, 0xFF52759C);
        }
        if (canvas == null) return;
        float px = (float) world.player.phys.x, py = (float) world.player.phys.y;
        int cropX = crop(px, centerX, mapW, (int) viewW, (int) canvas.w);
        int cropY = crop(py, centerY, mapH, (int) viewH, (int) canvas.h);
        g.part(canvas, viewX, viewY, cropX, cropY, (int) Math.min(viewW, canvas.w - cropX), (int) Math.min(viewH, canvas.h - cropY));
        Sprite npcM = ui.assets.sprite(ui.assets.wz.get("Map/MapHelper.img/minimap/npc"));
        Sprite portalM = ui.assets.sprite(ui.assets.wz.get("Map/MapHelper.img/minimap/portal"));
        Sprite userM = ui.assets.sprite(ui.assets.wz.get("Map/MapHelper.img/minimap/user"));
        if (world.field != null && portalM != null) {
            for (Portal pt : world.field.portals) if (pt.type == 2 || pt.type == 7) marker(g, portalM, pt.x, pt.y, cropX, cropY);
        }
        if (npcM != null) for (Npc n : world.npcs.values()) if (n.visible && !n.hideName) marker(g, npcM, n.x, n.y, cropX, cropY);
        if (userM != null) marker(g, userM, px, py, cropX, cropY);
    }

    private int crop(float value, int center, int extent, int viewport, int canvasExtent) {
        int pixels = viewport << mag;
        int worldV = Math.min(Math.max((int) value - pixels / 2, -center), extent - center - pixels);
        return Math.max(0, Math.min((worldV + center) >> mag, canvasExtent - viewport));
    }

    private void marker(UiDraw g, Sprite s, float wx, float wy, int cropX, int cropY) {
        int mx = (((int) wx + centerX) >> mag) - cropX;
        int my = (((int) wy + centerY) >> mag) - cropY;
        if (mx < 0 || my < 0 || mx >= viewW || my >= viewH) return;
        g.image(s, viewX + mx - (int) (s.w / 2), viewY + my - (int) (s.h / 2));
    }
}
