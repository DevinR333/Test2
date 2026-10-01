package maple.ui;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Sprite;

/** Message boxes: the in-game Basic.img Notice3/YesNo3 (CUtilDlg) and the login parchment notice. */
public final class Dialogs {
    private Dialogs() {}

    /**
     * In-game notice (OK) or confirmation (OK / Cancel): 266 wide, text at (20,24), 20px rows,
     * buttons at y = height-30 (OK x205 alone, x155 with Cancel at x205), as recovered by openms.
     */
    public static final class Notice extends Window {
        private final String text;
        private final boolean confirm;
        private int rows;
        private Sprite top, mid, bottom;

        public Notice(Ui ui, String text, boolean confirm, Runnable onOk, Runnable onCancel) {
            super(ui, "Notice", null);
            this.text = text;
            this.confirm = confirm;
            modal = true;
            draggable = false;
            String branch = confirm ? "Basic.img/YesNo3" : "Basic.img/Notice3";
            top = ui.assets.sprite(branch + "/t");
            mid = ui.assets.sprite(branch + "/c");
            bottom = ui.assets.sprite(branch + "/s");
            w = 266;
            float tw = ui.g.textWidth(text, 12, false);
            int lines = 1 + (int) (tw / 200);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
            rows = Math.max(2, lines * 14 / 20 + 1);
            h = 76 + rows * 20;
            add(new Button(ui.assets, "Basic.img/BtOK2", confirm ? 155 : 205, h - 30, () -> {
                close();
                if (onOk != null) onOk.run();
            }));
            if (confirm) {
                add(new Button(ui.assets, "Basic.img/BtCancel2", 205, h - 30, () -> {
                    close();
                    if (onCancel != null) onCancel.run();
                }));
            }
            x = Math.round((Ui.W - w) / 2f);
            y = Math.round((Ui.H - h) / 2f);
            keepPosition = true;
        }

        @Override
        protected void drawContent(UiDraw g) {
            g.image(top, 0, 0);
            for (int r = 0; r < rows; r++) g.image(mid, 0, 21 + r * 20);
            g.image(bottom, 0, 21 + rows * 20);
            g.text(text, 20, 24 + 2, 226, Align.center, true, 12, false, 0xFF000000);
        }

        @Override
        public boolean onKey(int keycode) {
            if (keycode == Input.Keys.ENTER) {
                ((Button) children.get(0)).action.run();
            } else if (keycode == Input.Keys.ESCAPE) {
                ((Button) children.get(children.size() - 1)).action.run();
            }
            return true; // modal: swallow keys
        }
    }

    /** The login screen's parchment message (Login.img/Notice/backgrnd/2, 362x219). */
    public static final class LoginNotice extends Window {
        private final String title, text;

        public LoginNotice(Ui ui, String title, String text, boolean confirm, Runnable onOk, Runnable onCancel) {
            super(ui, "LoginNotice", "Login.img/Notice/backgrnd/2");
            this.title = title;
            this.text = text;
            modal = true;
            draggable = false;
            if (w == 0) {
                w = 362;
                h = 219;
            }
            Button ok = add(new Button(ui.assets, "Basic.img/BtOK2", 0, 158, () -> {
                close();
                if (onOk != null) onOk.run();
            }));
            if (confirm) {
                Button cancel = add(new Button(ui.assets, "Basic.img/BtCancel2", 0, 158, () -> {
                    close();
                    if (onCancel != null) onCancel.run();
                }));
                float total = ok.w + 12 + cancel.w;
                ok.x = (w - total) / 2;
                cancel.x = ok.x + ok.w + 12;
            } else {
                ok.x = (w - ok.w) / 2;
            }
            x = Math.round((Ui.W - w) / 2f);
            y = Math.round((Ui.H - h) / 2f);
            keepPosition = true;
        }

        @Override
        protected void drawContent(UiDraw g) {
            if (background == null) {
                g.fill(0, 0, w, h, 0xF0F5E6C8);
                g.outline(0, 0, w, h, 0xFF8B6B3D);
            }
            if (title != null) g.text(title, 110, 30, w - 140, Align.left, false, 12, true, 0xFF000000);
            g.text(text, 110, 56, w - 140, Align.left, true, 12, false, 0xFF000000);
        }

        @Override
        public boolean onKey(int keycode) {
            if (keycode == Input.Keys.ENTER) {
                ((Button) children.get(0)).action.run();
            } else if (keycode == Input.Keys.ESCAPE) {
                ((Button) children.get(children.size() - 1)).action.run();
            }
            return true;
        }
    }
}
