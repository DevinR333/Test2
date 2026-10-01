package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/** A reactor (boxes, quest objects, PQ switches): one animation per state, plus the "hit" transition. */
public final class Reactor {
    public final int oid, id;
    public int state;
    public final int x, y;
    private final WzNode src;
    private final SpriteBank bank;
    private final Map<Integer, Animation> stateAnims = new HashMap<>();
    private Animation transition;
    private long transitionStart;
    public boolean destroyed, gone;
    private float alpha = 1;

    public Reactor(Wz wz, SpriteBank bank, int oid, int id, int state, int x, int y) {
        this.oid = oid;
        this.id = id;
        this.state = state;
        this.x = x;
        this.y = y;
        this.bank = bank;
        WzNode s = wz.get("Reactor/" + String.format("%07d", id) + ".img");
        String link = s.get("info").getString("link", "");
        if (!link.isEmpty()) {
            WzNode l = wz.get("Reactor/" + link + ".img");
            if (l.exists()) s = l;
        }
        src = s;
    }

    private Animation stateAnim(int st) {
        Animation a = stateAnims.get(st);
        if (a == null) {
            a = Animation.of(src.get(st), bank);
            stateAnims.put(st, a);
        }
        return a;
    }

    /** Hit (event type 0) reactors can be attacked. */
    public boolean hittable() {
        for (WzNode e : src.get(state).get("event").children()) {
            if (e.getInt("type", -1) == 0) return true;
        }
        return false;
    }

    public void hit(int newState, long timeMs) {
        Animation h = Animation.of(src.get(state).get("hit"), bank);
        transition = h.isEmpty() ? null : h;
        transitionStart = timeMs;
        state = newState;
    }

    public void destroy(int newState, long timeMs) {
        hit(newState, timeMs);
        destroyed = true;
    }

    public void update(long timeMs) {
        if (transition != null && timeMs - transitionStart >= transition.durationMs()) transition = null;
        if (destroyed && transition == null) {
            alpha -= 0.05f;
            if (alpha <= 0) gone = true;
        }
    }

    public void draw(Batch batch, long timeMs) {
        if (gone) return;
        if (transition != null) {
            transition.draw(batch, x, y, false, timeMs - transitionStart, alpha);
            return;
        }
        Animation a = stateAnim(state);
        if (a != null) a.draw(batch, x, y, false, timeMs, alpha);
    }

    public float[] bounds() {
        Animation a = stateAnim(state);
        Sprite s = a == null ? null : a.first();
        if (s == null) return new float[]{x - 30, y - 50, x + 30, y};
        return new float[]{x - s.ox, y - s.oy, x - s.ox + s.w, y - s.oy + s.h};
    }
}
