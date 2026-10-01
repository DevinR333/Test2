package maple.ui;

import maple.gfx.Sprite;

import java.util.ArrayList;
import java.util.List;

/** Hover text: a title plus lines, on the client's translucent navy box with a white border. */
public class Tooltip {
    public static final int BACK = 0xA0000040;

    public static final class Line {
        public final String text;
        public final int color;
        public final boolean bold;
        public final boolean center;
        public final int size;

        Line(String text, int color, boolean bold, boolean center, int size) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.center = center;
            this.size = size;
        }
    }

    public final List<Line> lines = new ArrayList<>();
    /** Optional icon drawn left of the description (items/skills). */
    public Sprite icon;
    public float width = 0; // 0 = fit text
    public int iconBack = 0;

    public static Tooltip text(String s) {
        Tooltip t = new Tooltip();
        for (String part : s.split("\n")) t.line(part, 0xFFFFFFFF);
        return t;
    }

    public Tooltip title(String s) {
        lines.add(new Line(s, 0xFFFFFFFF, true, true, 14));
        return this;
    }

    public Tooltip line(String s, int color) {
        lines.add(new Line(s, color, false, false, 12));
        return this;
    }

    public Tooltip center(String s, int color) {
        lines.add(new Line(s, color, false, true, 12));
        return this;
    }

    public Tooltip bold(String s, int color) {
        lines.add(new Line(s, color, true, false, 12));
        return this;
    }

    /** Draws the tooltip near (px, py), kept on the 800x600 screen. */
    public void draw(UiDraw g, float px, float py) {
        float maxW = width > 0 ? width : 0;
        if (maxW == 0) {
            for (Line l : lines) maxW = Math.max(maxW, g.textWidth(l.text, l.size, l.bold));
            maxW = Math.min(maxW, 280);
        }
        float iconW = icon != null ? Math.max(icon.w, 32) + 10 : 0;
        float textH = 0;
        for (Line l : lines) textH += lineH(g, l, maxW);
        float boxW = maxW + iconW + 16, boxH = Math.max(textH, icon != null ? icon.h : 0) + 16;
        float x = px + 12, y = py + 18;
        if (x + boxW > Ui.W) x = px - boxW - 4;
        if (y + boxH > 600) y = 600 - boxH;
        x = Math.max(0, x);
        y = Math.max(0, y);
        g.fill(x, y, boxW, boxH, BACK);
        g.outline(x, y, boxW, boxH, 0xFFFFFFFF);
        float ty = y + 8;
        if (icon != null) {
            if (iconBack != 0) g.fill(x + 8, ty, 34, 34, iconBack);
            g.image(icon, x + 8 + Math.max(0, (32 - icon.w) / 2f), ty + Math.max(0, (32 - icon.h) / 2f));
        }
        for (Line l : lines) {
            float lx = x + 8 + iconW;
            g.text(l.text, lx, ty, maxW, l.center ? com.badlogic.gdx.utils.Align.center : com.badlogic.gdx.utils.Align.left,
                    true, l.size, l.bold, l.color);
            ty += lineH(g, l, maxW);
        }
    }

    private static float lineH(UiDraw g, Line l, float w) {
        if (l.text.isEmpty()) return 8;
        float tw = g.textWidth(l.text, l.size, l.bold);
        int rows = Math.max(1, (int) Math.ceil(tw / Math.max(1, w)));
        return rows * (l.size + 4);
    }
}
