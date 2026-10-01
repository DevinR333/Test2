package maple.ui;

import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Interface artwork straight from UI.wz (and the other archives). Paths are "UIWindow.img/Item/backgrnd" style,
 * relative to UI.wz unless they start with another archive name ("Map/MapHelper.img/...").
 */
public final class UiAssets {
    public final Wz wz;
    private final SpriteBank bank = new SpriteBank();
    private final Map<String, Animation> animations = new HashMap<>();

    public UiAssets(Wz wz) {
        this.wz = wz;
        bank.bake(); // UI art is loaded on demand: one texture per canvas
    }

    /** The node for a path ("UIWindow.img/Item" = UI.wz; "Map/MapHelper.img/minimap" = Map.wz). */
    public WzNode node(String path) {
        int slash = path.indexOf('/');
        if (slash > 0 && !path.substring(0, slash).endsWith(".img")) {
            return wz.get(path);
        }
        return wz.get("UI/" + path);
    }

    /** The canvas at path, or path/0 when path is a frame list. Null if missing. */
    public Sprite sprite(String path) {
        return sprite(node(path));
    }

    public Sprite sprite(WzNode n) {
        if (n == null || !n.exists()) return null;
        n = n.resolve();
        if (!n.isCanvas()) n = n.get(0);
        return n.isCanvas() ? bank.get(n) : null;
    }

    public Animation animation(String path) {
        Animation a = animations.get(path);
        if (a == null) {
            a = Animation.of(node(path), bank);
            animations.put(path, a);
        }
        return a;
    }

    public Animation animation(WzNode n) {
        return Animation.of(n, bank);
    }

    public boolean exists(String path) {
        return node(path).exists();
    }

    public void dispose() {
        bank.dispose();
        animations.clear();
    }
}
