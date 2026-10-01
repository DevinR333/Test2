package maple.ui.hud;

import com.badlogic.gdx.utils.Align;
import maple.gfx.Sprite;
import maple.ui.UiDraw;

/** Labels drawn over the map: name tags, monster HP bars and chat balloons (screen coordinates). */
public final class WorldLabels {
    private WorldLabels() {}

    /** Name tag: text on a 60% black box with clipped corners, its top at y (HeavenClient NAMETAG). */
    public static void nameTag(UiDraw g, String text, float cx, float y, int color, boolean bold) {
        if (text == null || text.isEmpty()) return;
        float w = g.textWidth(text, 12, bold);
        float left = Math.round(cx - w / 2f) - 2, right = left + w + 4;
        float top = Math.round(y), h = 15;
        g.fill(left, top, right - left, h, 0x99000000);
        g.fill(left - 1, top + 1, 1, h - 2, 0x99000000);
        g.fill(right, top + 1, 1, h - 2, 0x99000000);
        g.text(text, left + 2, top + 1, 12, bold, color);
    }

    /** Monster HP bar 50 x 10 above the head (HeavenClient MobHpBar). */
    public static void hpBar(UiDraw g, float cx, float headY, int percent) {
        int width = 50, height = 10;
        int fill = (int) ((width - 6) * percent / 100f);
        float x = Math.round(cx - width / 2f), y = Math.round(headY - height * 3);
        g.fill(x, y, width, height, 0xFF000000);
        g.fill(x + 1, y + 1, width - 2, 1, 0xFFFFFFFF);
        g.fill(x + 1, y + height - 2, width - 2, 1, 0xFFFFFFFF);
        g.fill(x + 1, y + 2, 1, height - 4, 0xFFFFFFFF);
        g.fill(x + width - 2, y + 2, 1, height - 4, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, fill, 3, 0xFF00FF00);
        g.fill(x + 3, y + 6, fill, 1, 0xFF008000);
    }

    /** ChatBalloon.img/<type> frame around 80px-wide text, its arrow at (cx, bottom). */
    public static void balloon(UiDraw g, int type, String text, float cx, float bottom) {
        String b = "ChatBalloon.img/" + type + "/";
        Sprite n = g.assets.sprite(b + "n"), w = g.assets.sprite(b + "w");
        float textW = Math.min(80, g.textWidth(text, 11, false));
        float textH = g.textHeight(text, 80, 11, false);
        int xtile = n == null ? 1 : Math.max(1, (int) n.w), ytile = w == null ? 1 : Math.max(1, (int) w.h);
        int numHor = (int) (textW / xtile) + 2, numVer = (int) (textH / ytile);
        float width = numHor * xtile, height = Math.max(1, numVer) * ytile;
        float left = Math.round(cx - width / 2), top = Math.round(bottom - height), right = left + width;
        float bot = top + height;
        draw(g, b + "nw", left, top);
        draw(g, b + "sw", left, bot);
        for (float yy = top; yy < bot; yy += ytile) {
            draw(g, b + "w", left, yy);
            draw(g, b + "e", right, yy);
        }
        Sprite c = g.assets.sprite(b + "c");
        if (c != null) g.stretched(c, left, top, width, height);
        for (float xx = left; xx < right; xx += xtile) {
            draw(g, b + "n", xx, top);
            draw(g, b + "s", xx, bot);
        }
        draw(g, b + "ne", right, top);
        draw(g, b + "se", right, bot);
        draw(g, b + "arrow", cx, bot);
        g.text(text, cx - 40, top + (height - textH) / 2f - 1, 80, Align.center, true, 11, false, 0xFF000000);
    }

    private static void draw(UiDraw g, String path, float x, float y) {
        Sprite s = g.assets.sprite(path);
        if (s != null) g.anchored(s, x, y);
    }
}
