package maple.gfx;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/** A WZ canvas ready to draw: its texture region plus the origin point it is drawn around. */
public final class Sprite {
    public TextureRegion region; // filled in when the owning bank is baked
    public final int w, h, ox, oy;

    Sprite(int w, int h, int ox, int oy) {
        this.w = w;
        this.h = h;
        this.ox = ox;
        this.oy = oy;
    }

    /** Draws with the origin at (x, y) in y-down world coordinates. Mirrored sprites flip around the origin. */
    public void draw(Batch batch, float x, float y, boolean flip) {
        if (region == null) return;
        if (flip) batch.draw(region, x + ox, y - oy, -w, h);
        else batch.draw(region, x - ox, y - oy, w, h);
    }

    /** Draws with the top-left corner at (x, y), ignoring the origin. */
    public void drawTopLeft(Batch batch, float x, float y, boolean flip) {
        if (region == null) return;
        if (flip) batch.draw(region, x + w, y, -w, h);
        else batch.draw(region, x, y, w, h);
    }
}
