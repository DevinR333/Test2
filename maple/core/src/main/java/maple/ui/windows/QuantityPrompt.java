package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widgets;
import maple.ui.Window;

import java.util.function.IntConsumer;

/**
 * The compact numeric dialog (CUtilDlgEx compact mode, 0081d996 / 009a8716): Basic.img Notice3 chrome,
 * 266 wide, prompt at (20,22), edit at (19, h-73) 226 wide, BtOK2 (158, h-30), BtCancel2 (208, h-30).
 */
public final class QuantityPrompt extends Window {
    private final String text;
    private final Widgets.TextField field;
    private final int min, max, def;
    private final IntConsumer onOk;
    private final int textHeight;
    /** Shown when more than the maximum is asked for (why it is the maximum); null: just correct it. */
    public String overMax;

    public QuantityPrompt(Ui ui, String text, int def, int max, IntConsumer onOk) {
        this(ui, text, def, 1, max, onOk);
    }

    public QuantityPrompt(Ui ui, String text, int def, int min, int max, IntConsumer onOk) {
        super(ui, "QuantityPrompt", null);
        this.text = text;
        this.min = min;
        this.max = max;
        this.def = def;
        this.onOk = onOk;
        modal = true;
        draggable = false;
        w = 266;
        textHeight = 18 * Math.max(1, (int) Math.ceil(ui.g.textWidth(text, 12, false) / 225f));
        h = 99 + textHeight;
        field = add(new Widgets.TextField(19, h - 73, 226, 15));
        field.text = Integer.toString(def);
        field.selected = true; // typing replaces the default
        field.maxLength = 10;
        field.allowed = Character::isDigit;
        field.hint = text;
        field.onEnter = s -> ok();
        Widgets.Focus.set(field);
        add(new Button(ui.assets, "Basic.img/BtOK2", 158, h - 30, this::ok));
        add(new Button(ui.assets, "Basic.img/BtCancel2", 208, h - 30, this::close));
        x = Math.round((Ui.W - w) / 2f);
        y = Math.round((Ui.H - h) / 2f);
        keepPosition = true;
    }

    private void ok() {
        int n;
        try {
            n = field.text.trim().isEmpty() ? def : Integer.parseInt(field.text.trim()); // empty: keep the default
        } catch (NumberFormatException e) {
            return;
        }
        if (n < min || n > max) {
            field.text = Integer.toString(Math.max(min, Math.min(max, n)));
            field.selected = true;
            if (n > max && overMax != null) ui.open(new maple.ui.Dialogs.Notice(ui, overMax, false, null, null));
            return;
        }
        close();
        onOk.accept(n);
    }

    @Override
    protected void drawContent(UiDraw g) {
        Sprite t = ui.assets.sprite("Basic.img/Notice3/t"), c = ui.assets.sprite("Basic.img/Notice3/c"), s = ui.assets.sprite("Basic.img/Notice4/s");
        g.image(t, 0, 0);
        for (int y0 = 0; y0 < textHeight; y0 += 20) g.image(c, 0, 21 + y0);
        g.image(s, 0, 21 + textHeight);
        g.text(text, 20, 22, 225, Align.left, true, 12, false, 0xFF000000);
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) close();
        else if (keycode == Input.Keys.ENTER) ok();
        return true;
    }
}
