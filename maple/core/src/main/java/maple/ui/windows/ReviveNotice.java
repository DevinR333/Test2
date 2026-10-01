package maple.ui.windows;

import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.Window;

/** Death notice (UIWindow.img/Notice/0 at (257, 227), BtOK2 at (124, 115); 00898117). */
public final class ReviveNotice extends Window {
    public ReviveNotice(Ui ui, Runnable revive) {
        super(ui, "Revive", "UIWindow.img/Notice/0");
        modal = true;
        draggable = false;
        if (w == 0) {
            w = 286;
            h = 146;
        }
        x = 257 + (Ui.W - 800) / 2f;
        y = 227;
        keepPosition = true;
        add(new Button(ui.assets, "Basic.img/BtOK2", 124, 115, () -> {
            close();
            revive.run();
        }));
    }

    @Override
    public boolean onKey(int keycode) {
        return true; // not dismissable
    }
}
