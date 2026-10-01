package maple.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Animation;
import maple.gfx.Sprite;

/**
 * Drawing for the interface, in 800x600 UI pixels (y down). WZ art is premultiplied; text is not,
 * so the blend mode is switched as needed.
 */
public final class UiDraw {
    public final SpriteBatch batch;
    public final UiAssets assets;
    private final TextureRegion white;
    private final Texture whiteTex;
    private boolean textMode;
    private final GlyphLayout layout = new GlyphLayout();
    /** Current translation (window origin). */
    public float tx, ty;
    public long timeMs;

    public UiDraw(SpriteBatch batch, UiAssets assets) {
        this.batch = batch;
        this.assets = assets;
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        whiteTex = new Texture(pm);
        pm.dispose();
        white = new TextureRegion(whiteTex);
    }

    private void artBlend() {
        if (textMode) {
            batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
            textMode = false;
        }
    }

    private void textBlend() {
        if (!textMode) {
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            textMode = true;
        }
    }

    public void begin() {
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        textMode = false;
        tx = ty = 0;
        batch.begin();
    }

    public void end() {
        batch.end();
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(1, 1, 1, 1);
    }

    /** Prepares the batch for drawing premultiplied WZ art directly (avatars etc.). */
    public void artMode() {
        artBlend();
    }

    /** Sprite with its top-left at (x, y). */
    public void image(Sprite s, float x, float y) {
        if (s == null) return;
        artBlend();
        s.drawTopLeft(batch, tx + x, ty + y, false);
    }

    public void image(String path, float x, float y) {
        image(assets.sprite(path), x, y);
    }

    /** Sprite with its origin at (x, y). */
    public void anchored(Sprite s, float x, float y) {
        if (s == null) return;
        artBlend();
        s.draw(batch, tx + x, ty + y, false);
    }

    public void anchored(Sprite s, float x, float y, boolean flip) {
        if (s == null) return;
        artBlend();
        s.draw(batch, tx + x, ty + y, flip);
    }

    /** Sprite stretched to w x h with its top-left at (x, y). */
    public void stretched(Sprite s, float x, float y, float w, float h) {
        if (s == null || s.region == null) return;
        artBlend();
        batch.draw(s.region, tx + x, ty + y, w, h);
    }

    /** Draws part of a sprite (source rect sx, sy, sw, sh) at (x, y). */
    public void part(Sprite s, float x, float y, int sx, int sy, int sw, int sh) {
        if (s == null || s.region == null || sw <= 0 || sh <= 0) return;
        artBlend();
        TextureRegion r = s.region;
        float u = r.getU(), v = r.getV(), u2 = r.getU2(), v2 = r.getV2();
        float du = (u2 - u) / s.w, dv = (v2 - v) / s.h;
        TextureRegion p = new TextureRegion(r.getTexture(), u + du * sx, v + dv * sy, u + du * (sx + sw), v + dv * (sy + sh));
        batch.draw(p, tx + x, ty + y, sw, sh);
    }

    /** Part of a sprite (sx, sy, sw, sh) stretched to w x h at (x, y). */
    public void partStretched(Sprite s, float x, float y, float w, float h, int sx, int sy, int sw, int sh) {
        if (s == null || s.region == null || sw <= 0 || sh <= 0) return;
        artBlend();
        TextureRegion r = s.region;
        float u = r.getU(), v = r.getV(), u2 = r.getU2(), v2 = r.getV2();
        float du = (u2 - u) / s.w, dv = (v2 - v) / s.h;
        TextureRegion p = new TextureRegion(r.getTexture(), u + du * (sx + 0.5f), v + dv * sy, u + du * (sx + sw - 0.5f), v + dv * (sy + sh));
        batch.draw(p, tx + x, ty + y, w, h);
    }

    /** Animation frame with its origin at (x, y). */
    public void anim(Animation a, float x, float y, long t) {
        if (a == null || a.isEmpty()) return;
        artBlend();
        a.draw(batch, tx + x, ty + y, false, t, 1f);
    }

    public void alpha(float a) {
        batch.setColor(a, a, a, a);
    }

    public void tint(float r, float g, float b, float a) {
        batch.setColor(r * a, g * a, b * a, a);
    }

    public void resetColor() {
        batch.setColor(1, 1, 1, 1);
    }

    /** Solid rectangle (ARGB). */
    public void fill(float x, float y, float w, float h, int argb) {
        artBlend();
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        batch.setColor(r * a, g * a, b * a, a);
        batch.draw(white, tx + x, ty + y, w, h);
        batch.setColor(1, 1, 1, 1);
    }

    public void outline(float x, float y, float w, float h, int argb) {
        fill(x, y, w, 1, argb);
        fill(x, y + h - 1, w, 1, argb);
        fill(x, y, 1, h, argb);
        fill(x + w - 1, y, 1, h, argb);
    }

    // ---- text ----

    public float textWidth(String s, int size, boolean bold) {
        BitmapFont f = Fonts.get(size, bold);
        layout.setText(f, s);
        return layout.width;
    }

    /** Height of s wrapped to width w. */
    public float textHeight(String s, float w, int size, boolean bold) {
        if (s == null || s.isEmpty()) return 0;
        layout.setText(Fonts.get(size, bold), s, com.badlogic.gdx.graphics.Color.WHITE, w, com.badlogic.gdx.utils.Align.left, true);
        return layout.height;
    }

    /** Text with its top-left at (x, y). Colors are ARGB. */
    public void text(String s, float x, float y, int size, boolean bold, int argb) {
        if (s == null || s.isEmpty()) return;
        BitmapFont f = Fonts.get(size, bold);
        textBlend();
        f.setColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        f.draw(batch, s, tx + x, ty + y + baselineOffset(f, size));
    }

    /** Text aligned in a box of width w (Align.left / center / right), optionally wrapped. */
    public float text(String s, float x, float y, float w, int align, boolean wrap, int size, boolean bold, int argb) {
        if (s == null || s.isEmpty()) return 0;
        BitmapFont f = Fonts.get(size, bold);
        textBlend();
        f.setColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        layout.setText(f, s, f.getColor(), w, align, wrap);
        f.draw(batch, layout, tx + x, ty + y + baselineOffset(f, size));
        return layout.height;
    }

    /** Outlined text (used for names and notices). */
    public void outlined(String s, float x, float y, int size, boolean bold, int argb, int outlineArgb) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0) text(s, x + dx, y + dy, size, bold, outlineArgb);
        text(s, x, y, size, bold, argb);
    }

    /** Shift so (x, y) is the top of the text box, like the client's DrawText. */
    private static float baselineOffset(BitmapFont f, int size) {
        return Math.max(0, (size - f.getCapHeight()) / 2f - 1);
    }

    public float lineHeight(int size) {
        return Fonts.get(size, false).getLineHeight();
    }

    public void dispose() {
        whiteTex.dispose();
    }
}
