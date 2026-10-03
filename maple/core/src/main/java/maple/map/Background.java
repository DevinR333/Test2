package maple.map;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Animation;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

/** A map background layer: fixed, parallax, tiled and/or scrolling. Drawn in screen space. */
public final class Background {
    enum Type { NORMAL, HTILED, VTILED, TILED, HMOVEA, VMOVEA, HMOVEB, VMOVEB }

    final Animation anim;
    final boolean flipped, front;
    final float opacity;
    final int rx, ry;
    int cx, cy;
    final Type type;
    final double bx, by;
    double hspeed, vspeed;
    double moveX, moveY; // accumulated scroll for moving types

    Background(WzNode src, Wz wz, SpriteBank bank) {
        String set = src.getString("bS", "");
        boolean ani = src.getInt("ani", 0) != 0;
        WzNode node = wz.get("Map/Back/" + set + ".img/" + (ani ? "ani" : "back") + "/" + src.getInt("no", 0));
        anim = set.isEmpty() ? Animation.EMPTY : Animation.of(node, bank);
        flipped = src.getInt("f", 0) != 0;
        front = src.getInt("front", 0) != 0;
        opacity = src.getInt("a", 255) / 255f;
        rx = src.getInt("rx", 0);
        ry = src.getInt("ry", 0);
        cx = src.getInt("cx", 0);
        cy = src.getInt("cy", 0);
        bx = src.getInt("x", 0);
        by = src.getInt("y", 0);
        int t = src.getInt("type", 0);
        type = t >= 0 && t < Type.values().length ? Type.values()[t] : Type.NORMAL;
        if (!anim.isEmpty()) {
            if (cx == 0) cx = Math.max(1, anim.first().w);
            if (cy == 0) cy = Math.max(1, anim.first().h);
        } else {
            if (cx == 0) cx = 1;
            if (cy == 0) cy = 1;
        }
        switch (type) {
            case HMOVEA: case HMOVEB: hspeed = rx / 16.0; break;
            case VMOVEA: case VMOVEB: vspeed = ry / 16.0; break;
            default: break;
        }
    }

    boolean htiled() { return type == Type.HTILED || type == Type.HMOVEA || type == Type.TILED || type == Type.HMOVEB || type == Type.VMOVEB; }
    boolean vtiled() { return type == Type.VTILED || type == Type.VMOVEA || type == Type.TILED || type == Type.HMOVEB || type == Type.VMOVEB; }

    void update() {
        moveX += hspeed;
        moveY += vspeed;
    }

    /**
     * @param viewX,viewY translation from world to screen (screen = world + view)
     * @param vw,vh       view size in world pixels
     */
    void draw(Batch batch, double viewX, double viewY, float vw, float vh, long time) {
        if (anim.isEmpty()) return;
        // Wider than the original 800: backdrops pinned (fully or partly) to the screen were drawn to
        // cover exactly 800 pixels, so they are laid out as on 800x600 and zoomed evenly to the width.
        if (vw > 800.5f && !htiled() && hspeed == 0 && Math.abs(rx) < 100) {
            float s = vw / 800f;
            com.badlogic.gdx.math.Matrix4 old = batch.getTransformMatrix().cpy();
            batch.setTransformMatrix(new com.badlogic.gdx.math.Matrix4(old)
                    .translate(vw / 2f, vh / 2f, 0).scale(s, s, 1).translate(-400f, -vh / 2f, 0));
            drawAt(batch, viewX - (vw - 800) / 2.0, viewY, 800, vh, time);
            batch.setTransformMatrix(old);
            return;
        }
        drawAt(batch, viewX, viewY, vw, vh, time);
    }

    private void drawAt(Batch batch, double viewX, double viewY, float vw, float vh, long time) {
        double woff = vw / 2.0, hoff = vh / 2.0 - 10;
        double x, y;
        if (hspeed != 0) x = bx + moveX + viewX;
        else x = rx * (woff - viewX) / 100.0 + woff + bx;
        if (vspeed != 0) y = by + moveY + viewY;
        else y = ry * (hoff - viewY) / 100.0 + hoff + by;

        int htile = 1, vtile = 1;
        if (htiled()) htile = (int) (vw / cx) + 3;
        if (vtiled()) vtile = (int) (vh / cy) + 3;
        if (htile > 1) {
            x = x % cx;
            if (x > 0) x -= cx;
        }
        if (vtile > 1) {
            y = y % cy;
            if (y > 0) y -= cy;
        }
        int ix = (int) Math.round(x), iy = (int) Math.round(y);
        for (int tx = 0; tx < htile; tx++)
            for (int ty = 0; ty < vtile; ty++)
                anim.draw(batch, ix + tx * cx, iy + ty * cy, flipped, time, opacity);
    }
}
