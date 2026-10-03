package maple.ui.windows;

import com.badlogic.gdx.utils.Align;
import maple.game.PlayerStats;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.CharStats;
import maple.ui.Button;
import maple.ui.JobNames;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Window;

/**
 * The Stat window (UIWindow.img/Stat): values at x 61 (008c59ff), AP right-aligned to x 85 at y 215,
 * BtApUp at x 153, the job's disabled-stat overlays, and BtDetail (124, 324) attaching backgrnd2 at
 * (170, 144) with the calculated combat values at x 77.
 */
public final class StatWindow extends Window {
    private static final int[] AP_ROWS = {117, 135, 247, 265, 283, 301};
    private static final int[] AP_MASKS = {0x800, 0x2000, 0x40, 0x80, 0x100, 0x200};
    private static final int[] DETAIL_ROWS = {8, 26, 44, 62, 80, 98, 116, 134, 152};
    private final World world;
    private final Button[] apUp = new Button[6];
    private final Button detail, hide, auto;
    private boolean showDetail;
    private final float baseW;

    public StatWindow(Ui ui, World world) {
        super(ui, "Stat", "UIWindow.img/Stat/backgrnd");
        this.world = world;
        baseW = w;
        for (int i = 0; i < 6; i++) {
            int mask = AP_MASKS[i];
            apUp[i] = add(new Button(ui.assets, "UIWindow.img/Stat/BtApUp", 153, AP_ROWS[i], () -> world.distributeAp(mask)));
        }
        detail = add(new Button(ui.assets, "UIWindow.img/Stat/BtDetail", 124, 324, this::toggleDetail));
        auto = add(new Button(ui.assets, "UIWindow.img/Stat/BtAuto", 91, 203, world::autoAssignMenu)); // beside the AP count
        hide = add(new Button(ui.assets, "Basic.img/BtHide", 170 + 155, 144 + 182, this::toggleDetail));
        if (!hide.present()) {
            remove(hide);
            hide.visible = false;
        }
        hide.visible = false;
        addClose();
        refresh();
    }

    private void toggleDetail() {
        showDetail = !showDetail;
        hide.visible = showDetail;
        Sprite d = ui.assets.sprite("UIWindow.img/Stat/backgrnd2");
        w = showDetail && d != null ? 170 + d.w : baseW;
    }

    @Override
    public boolean contains(float lx, float ly) {
        if (lx >= 0 && ly >= 0 && lx < baseW && ly < h) return true;
        Sprite d = ui.assets.sprite("UIWindow.img/Stat/backgrnd2");
        return showDetail && d != null && lx >= 170 && lx < 170 + d.w && ly >= 144 && ly < 144 + d.h;
    }

    @Override
    public void refresh() {
        CharStats s = world.data() == null ? null : world.data().stats;
        for (Button b : apUp) b.disabled = s == null || s.ap <= 0;
        auto.disabled = s == null || s.ap <= 0;
    }

    private static String withBonus(int base, int total) {
        if (total == base) return Integer.toString(base);
        int bonus = total - base;
        return total + " (" + base + (bonus > 0 ? "+" : "-") + Math.abs(bonus) + ")";
    }

    private void value(UiDraw g, String text, float y) {
        g.text(text, 61, y, 91, Align.left, false, 12, false, 0xFF000000);
    }

    /** 008c6328: the job family picks which primary stats are drawn disabled. */
    private static int disabledMask(int job) {
        if (job % 1000 < 100) return 0;
        int family = (job % 1000) / 100;
        if (family == 1 || family == 3 || family == 5) return 12;
        if (family == 2) return 3;
        if (family == 4) return 5;
        return 0;
    }

    @Override
    public void draw(UiDraw g) {
        if (showDetail) {
            Sprite d = ui.assets.sprite("UIWindow.img/Stat/backgrnd2");
            if (d != null) {
                g.image(d, 170, 144);
                drawDetail(g);
            }
        }
        super.draw(g);
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (world.data() == null) return;
        CharStats s = world.data().stats;
        PlayerStats t = world.stats;
        String[] names = {"STR", "DEX", "INT", "LUK"};
        int mask = disabledMask(s.job);
        for (int i = 0; i < 4; i++) {
            if ((mask & (1 << i)) == 0) continue;
            Sprite o = ui.assets.sprite("UIWindow.img/Stat/Disabled/" + names[i]);
            if (o != null) g.image(o, 8, 244 + 18 * i);
        }
        value(g, s.name, 33);
        value(g, JobNames.name(s.job), 55);
        value(g, Integer.toString(s.level), 79);
        value(g, "-", 96);
        value(g, s.hp + " / " + Math.max(s.maxHp, t.maxHp), 115);
        value(g, s.mp + " / " + Math.max(s.maxMp, t.maxMp), 133);
        value(g, Integer.toString(s.exp), 151);
        value(g, Integer.toString(s.fame), 169);
        g.text(Integer.toString(s.ap), 8, 215, 77, Align.right, false, 12, false, 0xFF000000);
        value(g, withBonus(s.str, t.str), 244);
        value(g, withBonus(s.dex, t.dex), 262);
        value(g, withBonus(s.intel, t.intel), 280);
        value(g, withBonus(s.luk, t.luk), 298);
    }

    private void drawDetail(UiDraw g) {
        if (world.data() == null) return;
        PlayerStats t = world.stats;
        String[] v = {
                t.minDamage + " ~ " + t.maxDamage,
                Integer.toString(t.wdef),
                Integer.toString(t.matk),
                Integer.toString(t.mdef),
                Integer.toString(t.acc),
                Integer.toString(t.avoid),
                Integer.toString(t.hands),
                t.speed + "%",
                t.jump + "%"};
        for (int i = 0; i < v.length; i++) {
            g.text(v[i], 170 + 77, 144 + DETAIL_ROWS[i], 91, Align.left, false, 12, false, 0xFF000000);
        }
    }
}
