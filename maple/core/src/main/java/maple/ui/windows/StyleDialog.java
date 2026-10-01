package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.game.NpcTalk;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.CharStats;
import maple.net.model.Item;
import maple.ui.Button;
import maple.ui.RichText;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The style picker NPCs open for hair, face and skin (script sendStyle, NPC_TALK type 7):
 * UIWindow.img/UtilDlgEx_Avatar, 419x306. Your character is shown wearing each offered style, with
 * Prev / Next, the style's name on the name tag, the NPC's text beside it, OK (BtOn) and End Chat (BtExit).
 * The positions inside the window are laid out against its art (no recovered coordinates).
 */
public final class StyleDialog extends Window {
    private static final String B = "UIWindow.img/UtilDlgEx_Avatar/";
    private final World world;
    private final NpcTalk talk;
    private final RichText text;
    private int index;
    private final Map<Integer, Avatar> avatars = new HashMap<>();
    private long time;

    public StyleDialog(Ui ui, World world, NpcTalk talk) {
        super(ui, "UtilDlgEx_Avatar", B + "backgrnd");
        this.world = world;
        this.talk = talk;
        if (w == 0) {
            w = 419;
            h = 306;
        }
        draggable = false;
        keepPosition = true;
        x = Math.round((Ui.W - w) / 2f);
        y = Math.round((Ui.H - h) / 2f) - 20;
        text = RichText.layout(ui.g, talk.text, 190, new GameMarkup(world, ui.assets), RichText.BLACK);
        add(new Button(ui.assets, B + "BtPrev", 22, 236, () -> step(-1)));
        add(new Button(ui.assets, B + "BtNext", 132, 236, () -> step(1)));
        add(new Button(ui.assets, B + "BtOn", w - 186, h - 30, this::ok));
        add(new Button(ui.assets, B + "BtExit", w - 96, h - 30, this::exit));
    }

    private void step(int d) {
        int n = talk.styles.length;
        if (n == 0) return;
        index = (index + d + n) % n;
    }

    private void ok() {
        close();
        if (world.talk == talk) world.answer(1, index, null);
    }

    private void exit() {
        close();
        if (world.talk == talk) world.answer(0, 0, null);
    }

    /** Your look with this hair / face / skin. */
    private Avatar avatar(int style) {
        Avatar a = avatars.get(style);
        if (a != null) return a;
        if (world.data() == null) return null;
        CharStats s = world.data().stats;
        int skin = s.skin, face = s.face, hair = s.hair;
        if (style / 10000 == 2) face = style;
        else if (style / 10000 == 3) hair = style;
        else if (style >= 0 && style < 100) skin = style;
        java.util.TreeMap<Integer, Item> eq = world.data().inventory(-1);
        List<Integer> ids = new ArrayList<>();
        for (Item it : eq.values()) {
            if (it.position <= -100 || !eq.containsKey(it.position - 100)) ids.add(it.itemId);
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        try {
            a = new Avatar(ui.assets.wz, skin, face, hair, arr);
        } catch (RuntimeException e) {
            return null;
        }
        avatars.put(style, a);
        return a;
    }

    private String styleName(int id) {
        if (id >= 0 && id < 100) return "Skin " + (id + 1);
        String folder = id / 10000 == 2 ? "Face" : "Hair";
        String n = ui.assets.wz.get("String/Eqp.img/Eqp/" + folder + "/" + id).getString("name", "");
        return n.isEmpty() ? Integer.toString(id) : n;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        time += ms;
        if (world.talk != talk && ui.windows().contains(this)) close();
    }

    @Override
    protected void drawContent(UiDraw g) {
        float cx = 110, feet = 200;
        Sprite shadow = ui.assets.sprite(B + "shadow");
        if (shadow != null) g.image(shadow, cx - shadow.w / 2f, feet - 4);
        if (talk.styles.length > 0) {
            Avatar a = avatar(talk.styles[index]);
            if (a != null) {
                String st = a.standStance();
                int n = Math.max(1, a.frameCount(st));
                long total = 0;
                for (int i = 0; i < n; i++) total += Math.max(1, a.delay(st, i));
                long t = time % Math.max(1, total);
                int f = 0;
                for (int i = 0; i < n; i++) {
                    t -= Math.max(1, a.delay(st, i));
                    if (t < 0) {
                        f = i;
                        break;
                    }
                }
                g.artMode();
                a.draw(g.batch, st, f, g.tx + cx, g.ty + feet, false);
            }
            Sprite tag = ui.assets.sprite(B + "nameTag");
            float tagX = cx - (tag == null ? 86 : tag.w / 2f), tagY = feet + 12;
            if (tag != null) g.image(tag, tagX, tagY);
            g.text(styleName(talk.styles[index]), tagX, tagY + 3, tag == null ? 173 : tag.w, Align.center, false, 12, false, 0xFFFFFFFF);
            g.text((index + 1) + " / " + talk.styles.length, cx - 40, 248, 80, Align.center, false, 11, false, 0xFF000000);
        }
        text.draw(g, 210, 34, -1, 0, h - 80);
    }

    @Override
    public boolean onKey(int keycode) {
        switch (keycode) {
            case Input.Keys.LEFT: step(-1); return true;
            case Input.Keys.RIGHT: step(1); return true;
            case Input.Keys.ENTER: ok(); return true;
            case Input.Keys.ESCAPE: exit(); return true;
            default: return true;
        }
    }

    @Override
    public void closed() {
        for (Avatar a : avatars.values()) a.dispose();
        avatars.clear();
    }
}
