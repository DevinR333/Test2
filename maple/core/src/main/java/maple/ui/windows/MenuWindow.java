package maple.ui.windows;

import com.badlogic.gdx.Input;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.Window;

import java.util.function.Consumer;

/**
 * The HUD's Menu (UIWindow.img/GameMenu at (666, 423)) and Shortcut (UIWindow.img/ShortCut at (707, 296))
 * popups: buttons at x 6, y 24 + 26n (00849f3e / 0084a6bc). A choice closes the popup and opens its target.
 */
public final class MenuWindow extends Window {
    public static final String[][] GAME_MENU = {{"BtChannel", "Channel"}, {"BtGameOpt", "GameOpt"}, {"BtSysOpt", "SysOpt"}, {"BtQuit", "Quit"}};
    public static final String[][] SHORTCUT = {{"BtItem", "Item"}, {"BtEquip", "Equip"}, {"BtStat", "Stat"}, {"BtSkill", "Skill"},
            {"BtComm", "Friends"}, {"BtQuest", "Quest"}, {"BtMobbook", "MonsterBook"}, {"BtMessenger", "Messenger"}};

    public MenuWindow(Ui ui, String name, String[][] entries, float px, float py, Consumer<String> activate) {
        super(ui, name, "UIWindow.img/" + name + "/backgrnd");
        draggable = false;
        x = px;
        y = py;
        keepPosition = true;
        for (int i = 0; i < entries.length; i++) {
            String target = entries[i][1];
            Button b = add(new Button(ui.assets, "UIWindow.img/" + name + "/" + entries[i][0], 6, 24 + 26 * i, () -> {
                close();
                activate.accept(target);
            }));
            if (!b.present()) remove(b);
        }
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            close();
            return true;
        }
        return false;
    }
}
