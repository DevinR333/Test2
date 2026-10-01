package maple.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * The client's text: 11-12px Arial (Liberation Sans has the same metrics). Glyphs are rendered at the
 * real screen size and drawn scaled back down, so text stays sharp when the 800x600 screen is enlarged.
 */
public final class Fonts {
    private static FreeTypeFontGenerator regular, bold;
    private static final Map<String, BitmapFont> fonts = new HashMap<>();
    private static float scale = 1;
    private static final String CHARS = FreeTypeFontGenerator.DEFAULT_CHARS + "·•…‘’“”–—★☆♥※→←↑↓";

    private Fonts() {}

    /** Call when the screen scale changes (UI pixels → screen pixels). */
    public static synchronized void setScale(float s) {
        s = Math.max(1f, Math.round(s * 4) / 4f);
        if (Math.abs(s - scale) < 0.01f && !fonts.isEmpty()) return;
        scale = s;
        for (BitmapFont f : fonts.values()) f.dispose();
        fonts.clear();
    }

    /** Arial-metric font, size in UI pixels. */
    public static synchronized BitmapFont get(int size, boolean isBold) {
        String key = size + (isBold ? "b" : "r");
        BitmapFont f = fonts.get(key);
        if (f != null) return f;
        try {
            if (regular == null) {
                regular = new FreeTypeFontGenerator(Gdx.files.classpath("fonts/LiberationSans-Regular.ttf"));
                bold = new FreeTypeFontGenerator(Gdx.files.classpath("fonts/LiberationSans-Bold.ttf"));
            }
            FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = Math.max(6, Math.round(size * scale));
            p.flip = true;
            p.characters = CHARS;
            p.minFilter = Texture.TextureFilter.Linear;
            p.magFilter = Texture.TextureFilter.Linear;
            p.hinting = FreeTypeFontGenerator.Hinting.Full;
            p.incremental = true; // Korean/other glyphs appear on demand
            f = (isBold ? bold : regular).generateFont(p);
        } catch (Throwable t) {
            // No FreeType (should not happen): libGDX's built-in font keeps the game playable.
            f = new BitmapFont(true);
            f.getData().setScale(size / 15f * scale);
        }
        f.getData().setScale(f.getData().scaleX / scale);
        f.setUseIntegerPositions(false);
        f.getData().markupEnabled = false;
        fonts.put(key, f);
        return f;
    }

    public static float scale() { return scale; }

    public static synchronized void dispose() {
        for (BitmapFont f : fonts.values()) f.dispose();
        fonts.clear();
        if (regular != null) regular.dispose();
        if (bold != null) bold.dispose();
        regular = bold = null;
    }
}
