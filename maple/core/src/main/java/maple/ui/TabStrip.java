package maple.ui;

import maple.gfx.Sprite;

/**
 * The native "type1" tab strip (Basic.img/Tab2 parts at client y 23, 004dd790): a left cap, one fill
 * per tab with a middle edge between tabs and a right cap, and each tab's enabled/disabled label art.
 */
public final class TabStrip extends Widget {
    private final UiAssets a;
    private final String labelBase;
    private final int count;
    private final int[] tabX, tabW;
    public int selected;
    public java.util.function.IntConsumer onSelect;

    /** span: 170 for the Item window, 34 * count for the Skill window. */
    public TabStrip(UiAssets a, String labelBase, int count, int span) {
        this.a = a;
        this.labelBase = labelBase;
        this.count = count;
        tabX = new int[count];
        tabW = new int[count];
        int width = (span - (count - 1) * 8 - 8) / count;
        int remainder = span - (width + 8) * count;
        int x = 7;
        for (int i = 0; i < count; i++) {
            int size = width + (i < remainder ? 1 : 0);
            tabX[i] = x;
            tabW[i] = size;
            x += size + 8;
        }
        this.x = 0;
        this.y = 23;
        this.w = x + 4;
        this.h = 19;
    }

    private Sprite part(String name) {
        return a.sprite("Basic.img/Tab2/" + name);
    }

    @Override
    public void draw(UiDraw g) {
        g.image(part(selected == 0 ? "left1" : "left0"), 3, 0);
        for (int i = 0; i < count; i++) {
            boolean on = i == selected;
            g.stretched(part(on ? "fill1" : "fill0"), tabX[i], 0, tabW[i], 19);
            boolean last = i == count - 1;
            String edge = last ? (on ? "right1" : "right0") : (on ? "middle1" : (i + 1 == selected ? "middle2" : "middle0"));
            g.image(part(edge), tabX[i] + tabW[i], 0);
            Sprite label = a.sprite(labelBase + (on ? "/enabled/" : "/disabled/") + i);
            if (label != null) g.image(label, tabX[i] + tabW[i] / 2 - label.w / 2, 2 + 9 - label.h / 2);
        }
    }

    @Override
    public boolean interactive() { return true; }

    @Override
    public boolean onPress(float lx, float ly) {
        for (int i = 0; i < count; i++) {
            if (lx >= tabX[i] && lx < tabX[i] + tabW[i] + 8) {
                if (selected != i) {
                    selected = i;
                    UiSounds.play("Tab");
                    if (onSelect != null) onSelect.accept(i);
                }
                return false;
            }
        }
        return false;
    }
}
