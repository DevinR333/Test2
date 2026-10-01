package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Floating damage numbers from Effect.wz/BasicEff.img (NoRed/NoCri for monsters, NoViolet for damage to
 * the player). Spacing, rise and fade follow HeavenClient's DamageNumber (AGPL).
 */
public final class DamageNumbers {
    public enum Type { NORMAL, CRITICAL, TO_PLAYER }

    private static final int[] ADVANCE = {24, 20, 22, 22, 24, 23, 24, 22, 24, 24};
    private final Sprite[][][] digits = new Sprite[3][2][10]; // type, first?0:1 (0 = big first digit set), digit
    private final Sprite[] miss = new Sprite[3];
    private final List<Number> numbers = new ArrayList<>();

    private static final class Number {
        final Type type;
        final String text;
        final boolean miss;
        double x, y, lastY;
        float opacity = 1.5f;
        float shift;

        Number(Type type, int damage, double x, double y) {
            this.type = type;
            this.miss = damage <= 0;
            this.text = miss ? "" : Integer.toString(damage);
            this.x = x;
            this.y = lastY = y;
        }
    }

    public DamageNumbers(Wz wz, SpriteBank bank) {
        WzNode eff = wz.get("Effect/BasicEff.img");
        String[][] sets = {{"NoRed1", "NoRed0"}, {"NoCri1", "NoCri0"}, {"NoViolet1", "NoViolet0"}};
        for (int t = 0; t < 3; t++) {
            for (int k = 0; k < 2; k++) {
                WzNode set = eff.get(sets[t][k]);
                for (int d = 0; d < 10; d++) digits[t][k][d] = bank.get(set.get(d).resolve());
                if (k == 1) {
                    Sprite m = bank.get(set.get("Miss").resolve());
                    if (m == null) m = bank.get(eff.get(sets[t][0]).get("Miss").resolve());
                    miss[t] = m;
                }
            }
        }
        if (miss[1] == null) miss[1] = miss[0];
    }

    private int advance(Type type, char c, boolean first) {
        int i = c - '0';
        if (i < 0 || i > 9) return 0;
        int a = ADVANCE[i];
        if (type == Type.CRITICAL) a += first ? 8 : 4;
        else if (first) a += 2;
        return a;
    }

    /** Adds a number above (x, headY); rows stack upwards for multi-hit attacks. */
    public void add(Type type, int damage, double x, double headY) {
        Number n = new Number(type, damage, x, headY);
        if (n.miss) {
            Sprite m = miss[type.ordinal()];
            n.shift = m == null ? 20 : m.w / 2f;
        } else {
            int total = advance(type, n.text.charAt(0), true);
            for (int i = 1; i < n.text.length(); i++) {
                char c = n.text.charAt(i);
                total += i < n.text.length() - 1 ? (advance(type, c, false) + advance(type, n.text.charAt(i + 1), false)) / 2 : advance(type, c, false);
            }
            n.shift = total / 2f;
        }
        numbers.add(n);
    }

    public static int rowHeight(boolean critical) {
        return critical ? 36 : 30;
    }

    /** One 8 ms step. */
    public void update() {
        for (Iterator<Number> it = numbers.iterator(); it.hasNext(); ) {
            Number n = it.next();
            n.lastY = n.y;
            n.y -= 0.25;
            n.opacity -= 8f / 500f;
            if (n.opacity <= 0) it.remove();
        }
    }

    public void draw(Batch batch, float alpha) {
        for (Number n : numbers) {
            float a = Math.min(1f, n.opacity);
            batch.setColor(a, a, a, a);
            float y = (float) (n.lastY + (n.y - n.lastY) * alpha);
            float x = (float) n.x - n.shift;
            int t = n.type.ordinal();
            if (n.miss) {
                Sprite m = miss[t];
                if (m != null) m.draw(batch, x, y, false);
            } else {
                Sprite first = digits[t][0][n.text.charAt(0) - '0'];
                if (first != null) first.draw(batch, x, y, false);
                x += advance(n.type, n.text.charAt(0), true);
                for (int i = 1; i < n.text.length(); i++) {
                    char c = n.text.charAt(i);
                    Sprite s = digits[t][1][c - '0'];
                    if (s != null) s.draw(batch, x, y + ((i - 1) % 2 == 1 ? -2 : 2), false);
                    x += i < n.text.length() - 1 ? (advance(n.type, c, false) + advance(n.type, n.text.charAt(i + 1), false)) / 2 : advance(n.type, c, false);
                }
            }
        }
        batch.setColor(1, 1, 1, 1);
    }

    public void clear() {
        numbers.clear();
    }
}
