package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widgets;
import maple.ui.Window;

import java.util.function.Consumer;

/**
 * Text entry in the compact dialog (names for friends, party invites, guilds, blacklist...), same
 * chrome as the numeric one. The compact numeric dialog layout: (CUtilDlgEx compact mode, 0081d996 / 009a8716): Basic.img Notice3 chrome,
 * 266 wide, prompt at (20,22), edit at (19, h-73) 226 wide, BtOK2 (158, h-30), BtCancel2 (208, h-30).
 */
public final class TextPrompt extends Window {
    private final String text;
    private final Widgets.TextField field;
    private final Consumer<String> onOk;
    private final int textHeight;

    public TextPrompt(Ui ui, String text, String def, int maxLength, Consumer<String> onOk) {
        super(ui, "TextPrompt", null);
        this.text = text;
        this.onOk = onOk;
        modal = true;
        draggable = false;
        keepPosition = true;
        w = 266;
        textHeight = 18 * Math.max(1, (int) Math.ceil(ui.g.textWidth(text, 12, false) / 225f));
        h = 99 + textHeight;
        field = add(new Widgets.TextField(19, h - 73, 226, 15));
        field.text = def == null ? "" : def;
        field.maxLength = maxLength;
        field.hint = text;
        field.onEnter = s -> ok();
        Widgets.Focus.set(field);
        field.onPress(0, 0); // phones: open the keyboard
        add(new Button(ui.assets, "Basic.img/BtOK2", 158, h - 30, this::ok));
        add(new Button(ui.assets, "Basic.img/BtCancel2", 208, h - 30, this::close));
        x = Math.round((Ui.W - w) / 2f);
        y = Math.round((Ui.H - h) / 2f);
    }

    private void ok() {
        String t = field.text.trim();
        if (t.isEmpty()) return;
        close();
        onOk.accept(t);
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
