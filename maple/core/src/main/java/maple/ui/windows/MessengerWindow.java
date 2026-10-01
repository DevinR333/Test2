package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.CharLook;
import maple.ui.Button;
import maple.ui.Scrollbar;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widgets;
import maple.ui.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Maple Messenger (UIWindow.img/Messenger; 008505e2): full (295x364), compact (295x240) and minimized
 * (184x22) views, three seats 93px apart with their NameBar and the member standing in it, the log at
 * (9, logY) with VScr3 at (273, logY - 2), the edit at (9, inputY) and BtEnter at (249, inputY - 1).
 * "/invite name" invites, "/q" leaves.
 */
public final class MessengerWindow extends Window {
    private static final String B = "UIWindow.img/Messenger/";
    private static final int[][] MODES = {{295, 364, 150, 154, 318}, {295, 240, 55, 125, 194}, {184, 22, 0, 0, 0}};
    private static final String[] BACKS = {"backgrnd", "backgrnd2", "backgrnd3"};
    private final World world;
    private int mode;
    private Widgets.TextField input;
    private Scrollbar scroll;
    private final Avatar[] avatars = new Avatar[3];
    private final CharLook[] avatarLooks = new CharLook[3];
    private long time;
    private int lastLog = -1;
    private final List<Button> controls = new ArrayList<>();

    public MessengerWindow(Ui ui, World world) {
        super(ui, "Messenger", B + "backgrnd");
        this.world = world;
        build();
        if (!world.social.messengerOpen) world.messengerOpen();
    }

    private void build() {
        for (Button b : controls) remove(b);
        controls.clear();
        if (input != null) remove(input);
        if (scroll != null) remove(scroll);
        input = null;
        scroll = null;
        int[] m = MODES[mode];
        w = m[0];
        h = m[1];
        background = ui.assets.sprite(B + BACKS[mode]);
        int titleY = mode == 2 ? 5 : 6;
        controls.add(add(new Button(ui.assets, "Basic.img/BtClose", w - 19, titleY, this::close)));
        Button min = add(new Button(ui.assets, "Basic.img/BtMin", w - 47, titleY, () -> {
            mode = Math.min(2, mode + 1);
            build();
        }));
        min.disabled = mode >= 2;
        controls.add(min);
        Button max = add(new Button(ui.assets, "Basic.img/BtMax", w - 33, titleY, () -> {
            mode = Math.max(0, mode - 1);
            build();
        }));
        max.disabled = mode <= 0;
        controls.add(max);
        if (mode == 2) return;
        scroll = add(new Scrollbar(ui.assets, "VScr3", 273, m[2] - 2, m[3]));
        input = add(new Widgets.TextField(9, m[4], 207, 15));
        input.maxLength = 70;
        input.onEnter = s -> send();
        controls.add(add(new Button(ui.assets, B + "BtEnter", 249, m[4] - 1, this::send)));
        lastLog = -1;
    }

    private void send() {
        if (input == null) return;
        String t = input.text.trim();
        input.text = "";
        if (t.isEmpty()) return;
        if (t.equalsIgnoreCase("/q")) {
            close();
            return;
        }
        if (t.toLowerCase().startsWith("/invite ")) {
            world.messengerInvite(t.substring(8).trim());
            return;
        }
        world.messengerSay(t);
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("[ Maple Messenger Help ]");
        out.add("Invite : /invite character-name");
        out.add("End : /q");
        for (String s : world.social.messengerLog) {
            String rest = s;
            while (!rest.isEmpty()) {
                int n = rest.length();
                while (n > 1 && ui.g.textWidth(rest.substring(0, n), 12, false) > 250) n--;
                out.add(rest.substring(0, n));
                rest = rest.substring(n);
            }
        }
        return out;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        time += ms;
        if (scroll != null) {
            int visible = MODES[mode][3] / 14;
            int n = lines().size();
            int max = Math.max(1, n - visible + 1);
            if (n != lastLog) {
                scroll.setRange(max, max - 1);
                lastLog = n;
            }
        }
    }

    private Avatar avatar(int seat) {
        CharLook look = world.social.seatLooks[seat];
        if (look == null) return null;
        if (avatarLooks[seat] == look && avatars[seat] != null) return avatars[seat];
        if (avatars[seat] != null) avatars[seat].dispose();
        int[] eq = new int[look.equips.size()];
        int i = 0;
        for (int id : look.equips.values()) eq[i++] = id;
        try {
            avatars[seat] = new Avatar(ui.assets.wz, look.skin, look.face, look.hair, eq);
        } catch (RuntimeException e) {
            avatars[seat] = null;
        }
        avatarLooks[seat] = look;
        return avatars[seat];
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (mode == 2) {
            g.text("Maple Messenger", 8, 4, 12, true, 0xFFFFFFFF);
            return;
        }
        for (int i = 0; i < 3; i++) {
            float x = 9 + i * 93, y = mode == 0 ? 125 : 29;
            String name = world.social.seatNames[i];
            Sprite bar = ui.assets.sprite(B + "NameBar/" + (name != null ? i + 1 : 0));
            if (mode == 0 && name != null) {
                Avatar a = avatar(i);
                if (a != null) {
                    g.artMode();
                    a.draw(g.batch, a.standStance(), 0, g.tx + x + 44, g.ty + 119, false);
                }
            }
            if (bar != null) g.image(bar, x, y);
            if (name != null) g.text(name, x + 2, y + 1, 85, Align.center, false, 12, false, 0xFF000000);
        }
        int[] m = MODES[mode];
        List<String> lines = lines();
        int visible = m[3] / 14;
        int start = scroll == null ? 0 : scroll.position;
        for (int i = 0; i < visible && start + i < lines.size(); i++) {
            g.text(lines.get(start + i), 9, m[2] + i * 14, 12, false, start + i < 3 ? 0xFF777777 : 0xFF333333);
        }
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        if (mode == 2) return false;
        for (int i = 0; i < 3; i++) {
            float x = 9 + i * 93, y = mode == 0 ? 29 : 26, hh = mode == 0 ? 112 : 22;
            if (lx >= x && lx < x + 89 && ly >= y && ly < y + hh && world.social.seatNames[i] == null) {
                ui.open(new TextPrompt(ui, "Enter the character name to invite.", "", 13, world::messengerInvite));
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        if (scroll != null) scroll.scroll(amount);
        return true;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public void closed() {
        world.messengerLeave();
        for (Avatar a : avatars) if (a != null) a.dispose();
    }
}
