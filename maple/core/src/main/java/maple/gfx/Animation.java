package maple.gfx;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.List;

/** A WZ animation: numbered canvas frames with per-frame delay and optional alpha fade (a0 → a1). */
public final class Animation {
    public final Sprite[] frames;
    final int[] delays;
    final int[] a0, a1;
    final int[] starts;
    final int total;

    private Animation(List<Sprite> f, List<int[]> meta) {
        int n = f.size();
        frames = f.toArray(new Sprite[0]);
        delays = new int[n];
        a0 = new int[n];
        a1 = new int[n];
        starts = new int[n];
        int t = 0;
        for (int i = 0; i < n; i++) {
            int[] m = meta.get(i);
            delays[i] = m[0];
            a0[i] = m[1];
            a1[i] = m[2];
            starts[i] = t;
            t += m[0];
        }
        total = Math.max(1, t);
    }

    public static final Animation EMPTY = new Animation(new ArrayList<Sprite>(), new ArrayList<int[]>());

    /** Builds from a canvas (one frame) or a node with children 0, 1, 2... */
    public static Animation of(WzNode node, SpriteBank bank) {
        node = node.resolve();
        List<Sprite> frames = new ArrayList<>();
        List<int[]> meta = new ArrayList<>();
        if (node.isCanvas()) {
            add(node, bank, frames, meta);
        } else if (node.exists()) {
            for (int i = 0; ; i++) {
                WzNode f = node.get(i);
                if (!f.exists()) break;
                if (f.isCanvas()) add(f, bank, frames, meta);
            }
        }
        return frames.isEmpty() ? EMPTY : new Animation(frames, meta);
    }

    private static void add(WzNode c, SpriteBank bank, List<Sprite> frames, List<int[]> meta) {
        Sprite s = bank.get(c);
        if (s == null) return;
        int delay = Math.abs(c.getInt("delay", 100));
        if (delay == 0) delay = 100;
        int alpha0 = c.getInt("a0", 255);
        int alpha1 = c.getInt("a1", alpha0);
        frames.add(s);
        meta.add(new int[]{delay, alpha0, alpha1});
    }

    public boolean isEmpty() { return frames.length == 0; }
    public int durationMs() { return total; }

    public int frameAt(long timeMs) {
        if (frames.length <= 1) return 0;
        int t = (int) (timeMs % total);
        for (int i = frames.length - 1; i > 0; i--) if (t >= starts[i]) return i;
        return 0;
    }

    public float alphaAt(long timeMs, int frame) {
        if (a0[frame] == 255 && a1[frame] == 255) return 1f;
        float p = frames.length <= 1 ? (timeMs % total) / (float) total
                : ((timeMs % total) - starts[frame]) / (float) delays[frame];
        p = Math.max(0, Math.min(1, p));
        return (a0[frame] + (a1[frame] - a0[frame]) * p) / 255f;
    }

    public Sprite first() { return frames.length == 0 ? null : frames[0]; }

    /** Draws the frame for the given time. Returns false if there's nothing to draw. */
    public boolean draw(Batch batch, float x, float y, boolean flip, long timeMs, float alpha) {
        if (frames.length == 0) return false;
        int f = frameAt(timeMs);
        float a = alpha * alphaAt(timeMs, f);
        if (a < 0.999f) {
            batch.setColor(a, a, a, a); // premultiplied alpha
            frames[f].draw(batch, x, y, flip);
            batch.setColor(1, 1, 1, 1);
        } else {
            frames[f].draw(batch, x, y, flip);
        }
        return true;
    }
}
