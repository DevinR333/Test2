package maple.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzException;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A loaded map: backgrounds, 8 layers of tiles and objects, footholds, ladders, portals, NPCs and mobs. */
public final class Field {
    public final int id;
    public final String bgm;
    public final int returnMap;
    public final int left, right, top, bottom;
    public final String mapName, streetName;
    public final FootholdTree footholds;
    public final List<Ladder> ladders = new ArrayList<>();
    public final List<Portal> portals = new ArrayList<>();
    /** Map chairs (seat/<n>: x, y), sat on with the Sit key. Index = seat id sent to the server. */
    public final List<int[]> seats = new ArrayList<>();
    public final List<Life> life = new ArrayList<>();
    public final SpriteBank bank = new SpriteBank();
    final List<Background> backs = new ArrayList<>();
    final List<Background> fronts = new ArrayList<>();
    final List<List<Piece>> layers = new ArrayList<>();
    final Animation portalAnim;
    public int tiles, objects;

    /** A tile or object: an animation at a fixed spot with a z order. */
    static final class Piece {
        final Animation anim;
        final int x, y, z, order;
        final boolean flip;

        Piece(Animation anim, int x, int y, int z, int order, boolean flip) {
            this.anim = anim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.order = order;
            this.flip = flip;
        }
    }

    public static String imgPath(int mapId) {
        String s = String.format("%09d", mapId);
        return "Map/Map/Map" + (mapId / 100000000) + "/" + s + ".img";
    }

    public Field(Wz wz, int mapId) {
        this(wz, wz.get(imgPath(mapId)), mapId, false);
    }

    /**
     * A map from any map-shaped image (UI.wz/MapLogin.img for the login screens).
     * withLife adds the NPCs/mobs listed in the map itself (the server spawns them in game).
     */
    public Field(Wz wz, WzNode src, int mapId, boolean withLife) {
        this.id = mapId;
        if (!src.exists()) throw new WzException("Map " + mapId + " does not exist");
        WzNode info = src.get("info");
        WzNode link = info.get("link");
        if (link.exists()) {
            WzNode linked = wz.get(imgPath(link.asInt(0)));
            if (linked.exists()) src = linked;
        }

        String bgmPath = info.getString("bgm", "");
        bgm = bgmPath;
        returnMap = info.getInt("returnMap", 999999999);

        footholds = new FootholdTree(src.get("foothold"));
        if (info.get("VRLeft").exists() && info.getInt("VRRight", 0) > info.getInt("VRLeft", 0)) {
            left = info.getInt("VRLeft", 0);
            right = info.getInt("VRRight", 0);
            top = info.getInt("VRTop", 0);
            bottom = info.getInt("VRBottom", 0);
        } else {
            left = footholds.wallLeft - 25;
            right = footholds.wallRight + 25;
            top = footholds.borderTop + 300 - 360;
            bottom = footholds.borderBottom - 100 + 60;
        }

        // Names from String.wz/Map.img/<region>/<id>
        String mn = "", sn = "";
        WzNode strings = wz.get("String/Map.img");
        for (WzNode region : strings.children()) {
            WzNode m = region.resolve().get(Integer.toString(mapId));
            if (m.exists()) {
                mn = m.getString("mapName", "");
                sn = m.getString("streetName", "");
                break;
            }
        }
        mapName = mn;
        streetName = sn;

        for (WzNode st : src.get("seat").children()) {
            WzNode v = st.resolve();
            try {
                int idx = Integer.parseInt(st.name);
                while (seats.size() <= idx) seats.add(null);
                seats.set(idx, new int[]{v.vx(), v.vy()});
            } catch (NumberFormatException ignored) {
            }
        }

        for (WzNode b : src.get("back").children()) {
            Background bg = new Background(b.resolve(), wz, bank);
            (bg.front ? fronts : backs).add(bg);
        }

        for (int layer = 0; layer < 8; layer++) {
            List<Piece> pieces = new ArrayList<>();
            WzNode ln = src.get(layer);
            String tileSet = ln.get("info").getString("tS", "");
            int order = 0;
            for (WzNode o : ln.get("obj").children()) {
                o = o.resolve();
                String path = "Map/Obj/" + o.getString("oS", "") + ".img/" + o.getString("l0", "") + "/"
                        + o.getString("l1", "") + "/" + o.getString("l2", "");
                Animation a = Animation.of(wz.get(path), bank);
                if (a.isEmpty()) continue;
                pieces.add(new Piece(a, o.getInt("x", 0), o.getInt("y", 0), o.getInt("z", 0), order++, o.getInt("f", 0) != 0));
                objects++;
            }
            List<Piece> tilePieces = new ArrayList<>();
            if (!tileSet.isEmpty()) {
                for (WzNode t : ln.get("tile").children()) {
                    t = t.resolve();
                    WzNode c = wz.get("Map/Tile/" + tileSet + ".img/" + t.getString("u", "") + "/" + t.getInt("no", 0));
                    Animation a = Animation.of(c, bank);
                    if (a.isEmpty()) continue;
                    int z = c.getInt("z", 0);
                    if (z == 0) z = t.getInt("zM", 0);
                    tilePieces.add(new Piece(a, t.getInt("x", 0), t.getInt("y", 0), z, order++, false));
                    tiles++;
                }
            }
            sortByZ(pieces);
            sortByZ(tilePieces);
            pieces.addAll(tilePieces); // objects under tiles, like the original client
            layers.add(pieces);
        }

        for (WzNode l : src.get("ladderRope").children()) {
            l = l.resolve();
            ladders.add(new Ladder(l.getInt("l", 0) != 0, l.getInt("uf", 1) != 0,
                    l.getInt("x", 0), l.getInt("y1", 0), l.getInt("y2", 0), l.getInt("page", 0)));
        }

        for (WzNode p : src.get("portal").children()) {
            WzNode r = p.resolve();
            Portal portal = new Portal(r.getString("pn", ""), r.getInt("pt", 0), r.getInt("x", 0), r.getInt("y", 0),
                    r.getInt("tm", 999999999), r.getString("tn", ""), r.getString("script", ""));
            try {
                portal.id = Integer.parseInt(p.name);
            } catch (NumberFormatException e) {
                portal.id = portals.size();
            }
            portals.add(portal);
        }
        portalAnim = Animation.of(wz.get("Map/MapHelper.img/portal/game/pv"), bank);

        for (WzNode n : withLife ? src.get("life").children() : java.util.Collections.<WzNode>emptyList()) {
            n = n.resolve();
            try {
                Life lf = new Life(n, wz, bank, footholds);
                lf.hidden = n.getInt("hide", 0) != 0;
                life.add(lf);
            } catch (RuntimeException e) {
                // a broken NPC/mob shouldn't stop the map from loading
            }
        }
        bank.bake();
    }

    private static void sortByZ(List<Piece> list) {
        Collections.sort(list, (a, b) -> a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.order, b.order));
    }

    public Portal spawnPortal(String name) {
        if (name != null && !name.isEmpty()) {
            for (Portal p : portals) if (p.name.equals(name)) return p;
        }
        for (Portal p : portals) if (p.isSpawn()) return p;
        return portals.isEmpty() ? null : portals.get(0);
    }

    /** The portal with this id (the server's spawn point number), or the first spawn point. */
    public Portal portalById(int id) {
        for (Portal p : portals) if (p.id == id) return p;
        return spawnPortal(null);
    }

    public Ladder ladderAt(double x, double y, boolean upwards) {
        for (Ladder l : ladders) if (l.inRange(x, y, upwards)) return l;
        return null;
    }

    public Portal portalAt(double x, double y) {
        for (Portal p : portals) {
            if (p.isSpawn()) continue;
            if (p.inRange(x, y)) return p;
        }
        return null;
    }

    public void update(long now) {
        for (Background b : backs) b.update();
        for (Background b : fronts) b.update();
        for (Life l : life) l.update(footholds, now);
    }

    public void drawBackgrounds(Batch batch, double viewX, double viewY, float vw, float vh, long now) {
        for (Background b : backs) b.draw(batch, viewX, viewY, vw, vh, now);
    }

    public void drawForegrounds(Batch batch, double viewX, double viewY, float vw, float vh, long now) {
        for (Background b : fronts) b.draw(batch, viewX, viewY, vw, vh, now);
    }

    /** Draws layer contents; {@code between} is called after each layer's tiles for characters on that layer. */
    public void drawLayer(Batch batch, int layer, float alpha, long now) {
        for (Piece p : layers.get(layer)) p.anim.draw(batch, p.x, p.y, p.flip, now, 1f);
        for (Life l : life) if (l.layer == layer && !l.isMob) l.draw(batch, alpha, now);
        for (Life l : life) if (l.layer == layer && l.isMob) l.draw(batch, alpha, now);
    }

    public void drawPortals(Batch batch, long now) {
        if (portalAnim.isEmpty()) return;
        for (Portal p : portals) if (p.isVisible()) portalAnim.draw(batch, p.x, p.y, false, now, 1f);
    }

    public void drawDebug(ShapeRenderer sr) {
        sr.setColor(Color.RED);
        for (Foothold f : footholds.all()) sr.line(f.x1, f.y1, f.x2, f.y2);
        sr.setColor(Color.YELLOW);
        for (Ladder l : ladders) sr.line(l.x, l.y1, l.x, l.y2);
        sr.setColor(Color.CYAN);
        for (Portal p : portals) sr.rect(p.x - 25, p.y - 100, 50, 125);
    }

    public Sprite anySprite() {
        for (List<Piece> l : layers) for (Piece p : l) return p.anim.first();
        return null;
    }

    public void dispose() {
        bank.dispose();
    }
}
