package maple.ui.hud;

import com.badlogic.gdx.utils.Align;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.PlayerData;
import maple.ui.Button;
import maple.ui.JobNames;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widget;

import java.util.function.Consumer;

/**
 * The v83 status bar (UI.wz/StatusBar.img) at its native client coordinates, from openms' recovery of
 * CUIStatusBar (008d01b2 controls, 008d850b gauges, 008d82b8 identity). The window is created at
 * client y 22, so "client" rows are +22 on screen.
 */
public final class StatusBar extends Widget {
    private static final String SB = "StatusBar.img/";
    private final Ui ui;
    private final World world;
    private final Sprite backgrnd, backgrnd2, box, memo, bar, gray, graduation, lbr, rbr, slash, percent;
    private final Sprite[] numbers = new Sprite[10];
    private final Sprite[] levelNo = new Sprite[10];
    private final Sprite hpFlash, mpFlash;
    public final Button quickUp, quickDown;
    public boolean quickSlotsShown = true;
    private long time;

    /** Opens/toggles a named window (Equip, Item, Stat, Skill, KeyConfig, GameMenu, ShortCut, CashShop, NPT, GameLogs). */
    public StatusBar(Ui ui, World world, Consumer<String> open) {
        this.ui = ui;
        this.world = world;
        w = 800;
        h = 600;
        backgrnd = ui.assets.sprite(SB + "base/backgrnd");
        backgrnd2 = ui.assets.sprite(SB + "base/backgrnd2");
        box = ui.assets.sprite(SB + "base/box");
        memo = ui.assets.sprite(SB + "base/iconMemo");
        bar = ui.assets.sprite(SB + "gauge/bar");
        gray = ui.assets.sprite(SB + "gauge/gray");
        graduation = ui.assets.sprite(SB + "gauge/graduation");
        lbr = ui.assets.sprite(SB + "number/Lbracket");
        rbr = ui.assets.sprite(SB + "number/Rbracket");
        slash = ui.assets.sprite(SB + "number/slash");
        percent = ui.assets.sprite(SB + "number/percent");
        hpFlash = ui.assets.sprite(SB + "gauge/hpFlash/0");
        mpFlash = ui.assets.sprite(SB + "gauge/mpFlash/0");
        for (int i = 0; i < 10; i++) {
            numbers[i] = ui.assets.sprite(SB + "number/" + i);
            levelNo[i] = ui.assets.sprite("Basic.img/LevelNo/" + i);
        }
        int top = 515 + 22;
        Object[][] buttons = {
                {"BtClaim", 573, top, "GameLogs"},
                {"EquipKey", 618, top, "Equip"},
                {"InvenKey", 648, top, "Item"},
                {"StatKey", 678, top, "Stat"},
                {"SkillKey", 708, top, "Skill"},
                {"KeySet", 738, top, "KeyConfig"},
                {"BtShop", 573, 543 + 22, "CashShop"},
                {"BtNPT", 629, 543 + 22, "NPT"},
                {"BtMenu", 685, 543 + 22, "GameMenu"},
                {"BtShort", 741, 543 + 22, "ShortCut"},
        };
        for (Object[] b : buttons) {
            final String target = (String) b[3];
            add(new Button(ui.assets, SB + b[0], (Integer) b[1], (Integer) b[2], () -> open.accept(target)));
        }
        quickUp = add(new Button(ui.assets, SB + "QuickSlot", 768, top, () -> open.accept("QuickSlot")));
        quickDown = add(new Button(ui.assets, SB + "QuickSlotD", 768, top, () -> open.accept("QuickSlot")));
        quickUp.visible = !quickSlotsShown;
    }

    @Override
    public void update(long ms) {
        time += ms;
        quickUp.visible = !quickSlotsShown;
        quickDown.visible = quickSlotsShown;
        super.update(ms);
    }

    @Override
    public Widget hit(float lx, float ly) {
        Widget h = super.hit(lx, ly);
        if (h != null) return h;
        // the bar itself swallows clicks so they don't reach the world
        return ly >= 529 && lx >= 0 && lx < 800 ? this : null;
    }

    @Override
    public boolean interactive() { return true; }

    @Override
    public void draw(UiDraw g) {
        // Wider screens: extend the bar with its own edge columns.
        if (backgrnd != null && Ui.W > 800) {
            float side = (Ui.W - 800) / 2f;
            g.partStretched(backgrnd, -side, 529, side, backgrnd.h, 0, 0, 1, backgrnd.h);
            g.partStretched(backgrnd, 800, 529, side, backgrnd.h, backgrnd.w - 1, 0, 1, backgrnd.h);
        }
        g.image(backgrnd, 0, 529);
        g.image(backgrnd2, 2, 507 + 22);
        g.image(box, 573, 515 + 22);
        g.image(memo, 599, 519 + 22);
        g.image(bar, 218, 567);
        PlayerData d = world.data();
        if (d != null) {
            int maxHp = Math.max(1, world.stats.maxHp > 0 ? world.stats.maxHp : d.stats.maxHp);
            int maxMp = Math.max(1, world.stats.maxMp > 0 ? world.stats.maxMp : d.stats.maxMp);
            long need = expNeeded(d.stats.level);
            gauge(g, 220, 105, d.stats.hp, maxHp);
            gauge(g, 328, 105, d.stats.mp, maxMp);
            gauge(g, 441, 115, d.stats.exp, need);
            g.image(graduation, 218, 544 + 22);
            // low HP/MP warning flash (5 x setting, default 10 -> below 50%)
            if (d.stats.hp * 100L / maxHp < 50 && (time / 130) % 2 == 0) g.image(hpFlash, 218, 580);
            if (d.stats.mp * 100L / maxMp < 50 && (time / 130) % 2 == 0) g.image(mpFlash, 326, 580);
            identity(g, d);
            resource(g, d.stats.hp, maxHp, 237);
            resource(g, d.stats.mp, maxMp, 349);
            experience(g, d.stats.exp, need);
        } else {
            g.image(graduation, 218, 544 + 22);
        }
        drawChildren(g);
    }

    static long expNeeded(int level) {
        try {
            return constants.game.ExpTable.getExpNeededForLevel(Math.max(1, Math.min(200, level)));
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Fill = bar artwork; the gray strip covers the missing part (crop to 15 rows). */
    private void gauge(UiDraw g, int x, int width, long value, long max) {
        int extent = max > 0 ? (int) Math.max(0, Math.min(width, width * value / max)) : 0;
        if (extent < width && gray != null) g.stretched(gray, x + extent, 581, width - extent, 15);
    }

    private void identity(UiDraw g, PlayerData d) {
        String level = Integer.toString(d.stats.level);
        float x = 50 - 6 * (level.length() - 1);
        for (char c : level.toCharArray()) {
            Sprite s = levelNo[c - '0'];
            if (s == null) continue;
            g.image(s, x, 554 + 22);
            x += s.w + 1;
        }
        g.text(JobNames.name(d.stats.job), 87, 545 + 22, 126, Align.left, false, 12, false, 0xFFFFFFFF);
        g.text(d.stats.name, 87, 560 + 22, 126, Align.left, false, 12, false, 0xFFFFFFFF);
    }

    private float digits(UiDraw g, String text, float x) {
        for (char c : text.toCharArray()) {
            Sprite s = c >= '0' && c <= '9' ? numbers[c - '0'] : null;
            if (s != null) g.image(s, x, 549 + 22);
            x += 6;
        }
        return x;
    }

    private void resource(UiDraw g, long cur, long max, float x) {
        g.image(lbr, x, 548 + 22);
        x = digits(g, Long.toString(cur), x + 4);
        g.image(slash, x, 549 + 22);
        x = digits(g, Long.toString(max), x + 8);
        g.image(rbr, x + 1, 548 + 22);
    }

    private void experience(UiDraw g, long exp, long required) {
        float x = digits(g, Long.toString(exp), 466);
        g.image(lbr, x, 548 + 22);
        x += 4;
        long hundredths = required > 0 ? Math.min(10000, exp * 10000 / required) : 0;
        x = digits(g, Long.toString(hundredths / 100), x);
        g.text(".", x, 544 + 22, 12, false, 0xFF000000); // the client draws this period with the font
        String frac = Long.toString(hundredths % 100);
        if (frac.length() < 2) frac = "0" + frac;
        x = digits(g, frac, x + 4);
        g.image(percent, x, 549 + 22);
        g.image(rbr, x + 8, 548 + 22);
    }
}
