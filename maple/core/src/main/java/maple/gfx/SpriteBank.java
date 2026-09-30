package maple.gfx;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;
import maple.wz.WzNode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns WZ canvases into textures. Everything requested before {@link #bake()} is packed into a few
 * big atlas pages (so a whole map draws with very few texture switches); later requests get their own texture.
 */
public final class SpriteBank implements Disposable {
    private static final int PAGE = 2048;

    private PixmapPacker packer;
    private TextureAtlas atlas;
    private final Map<String, Sprite> sprites = new HashMap<>();
    private final Map<String, Sprite> pending = new HashMap<>();
    private final List<Texture> loose = new ArrayList<>();
    private boolean baked;
    public int decodeErrors;
    public String lastError;

    public SpriteBank() {
        packer = new PixmapPacker(PAGE, PAGE, Pixmap.Format.RGBA8888, 2, true);
        packer.setTransparentColor(new com.badlogic.gdx.graphics.Color(0, 0, 0, 0));
    }

    public int size() { return sprites.size(); }

    /** Sprite for a canvas node (links already resolved), or null if it isn't a usable canvas. */
    public Sprite get(WzNode canvas) {
        if (canvas == null || !canvas.isCanvas()) return null;
        String key = canvas.key();
        Sprite s = sprites.get(key);
        if (s != null) return s;
        WzNode origin = canvas.get("origin");
        int w = canvas.width(), h = canvas.height();
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null;
        Pixmap pm;
        try {
            pm = toPixmap(canvas.rgba(), w, h);
        } catch (RuntimeException e) {
            decodeErrors++;
            lastError = canvas.fullPath() + ": " + e.getMessage();
            return null;
        }
        s = new Sprite(w, h, origin.vx(), origin.vy());
        sprites.put(key, s);
        if (!baked && w + 4 <= PAGE && h + 4 <= PAGE) {
            packer.pack(key, pm);
            pending.put(key, s);
        } else {
            Texture t = new Texture(pm);
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            loose.add(t);
            s.region = flipped(new TextureRegion(t));
        }
        pm.dispose();
        return s;
    }

    /** Uploads everything packed so far. Sprites requested afterwards become separate textures. */
    public void bake() {
        if (baked) return;
        baked = true;
        if (!pending.isEmpty()) {
            atlas = packer.generateTextureAtlas(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear, false);
            for (TextureAtlas.AtlasRegion r : atlas.getRegions()) {
                Sprite s = pending.get(r.name);
                if (s != null) s.region = flipped(new TextureRegion(r));
            }
        }
        pending.clear();
        packer.dispose();
        packer = null;
    }

    private static TextureRegion flipped(TextureRegion r) {
        r.flip(false, true); // world is y-down
        return r;
    }

    /** RGBA8888 bytes → premultiplied-alpha pixmap (keeps edges clean when scaled). */
    public static Pixmap toPixmap(byte[] rgba, int w, int h) {
        for (int i = 0; i < rgba.length; i += 4) {
            int a = rgba[i + 3] & 0xFF;
            if (a == 255) continue;
            if (a == 0) {
                rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;
                continue;
            }
            rgba[i] = (byte) (((rgba[i] & 0xFF) * a + 127) / 255);
            rgba[i + 1] = (byte) (((rgba[i + 1] & 0xFF) * a + 127) / 255);
            rgba[i + 2] = (byte) (((rgba[i + 2] & 0xFF) * a + 127) / 255);
        }
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        ByteBuffer px = pm.getPixels();
        px.clear();
        px.put(rgba, 0, Math.min(rgba.length, px.capacity()));
        px.flip();
        return pm;
    }

    @Override
    public void dispose() {
        if (packer != null) packer.dispose();
        if (atlas != null) atlas.dispose();
        for (Texture t : loose) t.dispose();
        loose.clear();
        sprites.clear();
        pending.clear();
    }
}
