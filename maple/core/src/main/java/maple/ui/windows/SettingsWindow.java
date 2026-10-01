package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * The offline options (opened from Menu > Game Options / System Options): screen shape, the optional
 * extras, touch controls, music/sound and save export/import. Basic.img Notice3/Notice4 chrome with
 * CheckBox rows.
 */
public final class SettingsWindow extends Window {
    /** What the options change. */
    public interface Host {
        Ui.Aspect aspect();
        void setAspect(Ui.Aspect a);
        boolean option(String key);
        void setOption(String key, boolean on);
        void editTouch();
        void exportSave();
        void importSave();
        void logOut();
    }

    private final Host host;
    private final List<Object[]> rows = new ArrayList<>(); // label, kind ("aspect"/"opt"/"action"/"header"), value
    private static final int ROW = 18, TOP = 30;

    public SettingsWindow(Ui ui, Host host) {
        super(ui, "SysOpt", null);
        this.host = host;
        rows.add(new Object[]{"Screen", "header", null});
        rows.add(new Object[]{"4:3 (original 800x600)", "aspect", Ui.Aspect.ORIGINAL_4_3});
        rows.add(new Object[]{"16:9 widescreen", "aspect", Ui.Aspect.WIDE_16_9});
        rows.add(new Object[]{"Fill the screen", "aspect", Ui.Aspect.FILL});
        rows.add(new Object[]{"Sound", "header", null});
        rows.add(new Object[]{"Background music", "opt", "music"});
        rows.add(new Object[]{"Sound effects", "opt", "sound"});
        rows.add(new Object[]{"Controls", "header", null});
        rows.add(new Object[]{"Show touch controls", "opt", "touch"});
        rows.add(new Object[]{"Edit touch controls...", "action", (Runnable) () -> {
            close();
            host.editTouch();
        }});
        rows.add(new Object[]{"Extras (off = original game)", "header", null});
        rows.add(new Object[]{"All hairstyles and faces at creation", "opt", "allStyles"});
        rows.add(new Object[]{"Cash Shop items drop from monsters", "opt", "cashDrops"});
        rows.add(new Object[]{"Save data", "header", null});
        rows.add(new Object[]{"Export save...", "action", (Runnable) host::exportSave});
        rows.add(new Object[]{"Import save...", "action", (Runnable) host::importSave});
        rows.add(new Object[]{"Log out to character select", "action", (Runnable) () -> {
            close();
            host.logOut();
        }});
        w = 266;
        h = TOP + rows.size() * ROW + 47;
        x = Math.round((Ui.W - w) / 2f);
        y = Math.max(0, Math.round((Ui.H - h) / 2f));
        keepPosition = true;
        add(new Button(ui.assets, "Basic.img/BtOK2", 208, h - 30, this::close));
    }

    @Override
    protected void drawContent(UiDraw g) {
        Sprite t = ui.assets.sprite("Basic.img/Notice3/t"), c = ui.assets.sprite("Basic.img/Notice3/c"), s = ui.assets.sprite("Basic.img/Notice4/s");
        if (t != null) g.image(t, 0, 0);
        float top = t == null ? 21 : t.h;
        float bottom = h - (s == null ? 47 : s.h);
        if (c != null) for (float yy = top; yy < bottom; yy += c.h) g.image(c, 0, yy);
        if (s != null) g.image(s, 0, bottom);
        g.text("Options", 0, 6, w, Align.center, false, 12, true, 0xFF000000);
        Sprite off = ui.assets.sprite("Basic.img/CheckBox/0"), on = ui.assets.sprite("Basic.img/CheckBox/1");
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            float ry = TOP + i * ROW;
            String kind = (String) r[1];
            if (kind.equals("header")) {
                g.text((String) r[0], 16, ry + 2, 240, Align.left, false, 12, true, 0xFF3060A0);
                continue;
            }
            if (kind.equals("action")) {
                g.text((String) r[0], 32, ry + 2, 220, Align.left, false, 12, false, 0xFF0000C0);
                continue;
            }
            boolean checked = kind.equals("aspect") ? host.aspect() == r[2] : host.option((String) r[2]);
            Sprite box = checked ? on : off;
            if (box != null) g.image(box, 18, ry + 3);
            else {
                g.outline(18, ry + 3, 12, 12, 0xFF000000);
                if (checked) g.fill(21, ry + 6, 6, 6, 0xFF000000);
            }
            g.text((String) r[0], 36, ry + 2, 220, Align.left, false, 12, false, 0xFF000000);
        }
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int i = (int) ((ly - TOP) / ROW);
        if (ly < TOP || i < 0 || i >= rows.size()) return false;
        Object[] r = rows.get(i);
        String kind = (String) r[1];
        switch (kind) {
            case "aspect":
                host.setAspect((Ui.Aspect) r[2]);
                x = Math.round((Ui.W - w) / 2f);
                break;
            case "opt":
                host.setOption((String) r[2], !host.option((String) r[2]));
                break;
            case "action":
                ((Runnable) r[2]).run();
                break;
            default:
                return false;
        }
        UiSounds.play("BtMouseClick");
        return false;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.ENTER) {
            close();
            return true;
        }
        return false;
    }
}
