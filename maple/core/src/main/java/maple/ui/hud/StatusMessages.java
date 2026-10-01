package maple.ui.hud;

import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * Pickup / EXP / meso notices at the bottom right, newest at the bottom: right-aligned at (W - 6, 455),
 * 14px apart, at most six, each fading out over eight seconds.
 */
public final class StatusMessages extends Widget {
    private static final long FADE = 8000;
    private final List<Object[]> lines = new ArrayList<>(); // text, color, start
    private long time;

    public void add(String text, int color) {
        lines.add(0, new Object[]{text, color, time});
        while (lines.size() > 6) lines.remove(lines.size() - 1);
    }

    @Override
    public void update(long ms) {
        time += ms;
        lines.removeIf(l -> time - (Long) l[2] >= FADE);
    }

    @Override
    public void draw(UiDraw g) {
        float y = 455;
        for (Object[] l : lines) {
            float a = 1f - (time - (Long) l[2]) / (float) FADE;
            int alpha = Math.max(0, Math.min(255, (int) (a * 255)));
            String s = (String) l[0];
            int color = ((Integer) l[1] & 0xFFFFFF) | (alpha << 24);
            float w = g.textWidth(s, 12, false);
            float x = Ui.W - 6 - w;
            g.text(s, x + 1, y + 1, 12, false, alpha << 24);
            g.text(s, x, y, 12, false, color);
            y -= 14;
        }
    }
}
