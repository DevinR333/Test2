package maple.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/** A travel list of towns and hunting grounds. Tap one to go there. */
public final class MapMenu {
    public static final int[] IDS = {
            100000000, 101000000, 102000000, 103000000, 104000000, 105040300, 120000000,
            200000000, 211000000, 220000000, 221000000, 222000000, 230000000, 240000000,
            250000000, 260000000, 600000000, 800000000, 910000000,
            10000, 60000, 1000000, 104040000};
    /** Display names; the game replaces these with the names from String.wz. */
    public final String[] names = {
            "Henesys", "Ellinia", "Perion", "Kerning City", "Lith Harbor", "Sleepywood", "Nautilus Harbor",
            "Orbis", "El Nath", "Ludibrium", "Omega Sector", "Korean Folk Town", "Aquarium", "Leafre",
            "Mu Lung", "Ariant", "New Leaf City", "Mushroom Shrine", "Free Market Entrance",
            "Mushroom Town", "Southperry", "Amherst", "Henesys Hunting Ground I"};

    public boolean open;
    private float x, y, w, h, rowH;
    private int scroll;
    private float dragStartY = -1, dragScroll;
    private boolean wasTouched, moved;

    public void layout(float uw, float uh) {
        w = Math.min(520, uw - 40);
        h = uh - 80;
        x = (uw - w) / 2;
        y = 40;
        rowH = 46;
    }

    /** Returns the chosen map id, or -1. */
    public int poll(float uiScale) {
        if (!open) return -1;
        boolean touched = Gdx.input.isTouched();
        float tx = Gdx.input.getX() / uiScale, ty = Gdx.input.getY() / uiScale;
        int result = -1;
        if (touched && !wasTouched) {
            dragStartY = ty;
            dragScroll = scroll;
            moved = false;
        } else if (touched) {
            if (Math.abs(ty - dragStartY) > 12) moved = true;
            int maxScroll = (int) Math.max(0, IDS.length * rowH - (h - 60));
            scroll = (int) Math.max(0, Math.min(maxScroll, dragScroll - (ty - dragStartY)));
        } else if (wasTouched && !moved) {
            float lx = Gdx.input.getX() / uiScale, ly = Gdx.input.getY() / uiScale;
            if (lx < x || lx > x + w || ly < y || ly > y + h) {
                open = false;
            } else {
                int row = (int) ((ly - y - 50 + scroll) / rowH);
                if (ly > y + 50 && row >= 0 && row < IDS.length) {
                    result = IDS[row];
                    open = false;
                }
            }
        }
        wasTouched = touched;
        return result;
    }

    public void draw(ShapeRenderer sr, SpriteBatch batch, BitmapFont font) {
        if (!open) return;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(0.05f, 0.08f, 0.15f, 0.92f);
        sr.rect(x, y, w, h);
        sr.setColor(0.2f, 0.4f, 0.8f, 0.9f);
        sr.rect(x, y, w, 44);
        sr.end();
        batch.begin();
        font.setColor(1, 1, 1, 1);
        font.draw(batch, "Travel  (tap a place, tap outside to close)", x + 14, y + 13);
        for (int i = 0; i < IDS.length; i++) {
            float ry = y + 50 + i * rowH - scroll;
            if (ry < y + 44 || ry + rowH > y + h) continue;
            font.draw(batch, names[i] + "   (" + IDS[i] + ")", x + 18, ry + 14);
        }
        batch.end();
    }
}
