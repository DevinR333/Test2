package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.game.Names;
import maple.game.NpcTalk;
import maple.game.World;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.RichText;
import maple.ui.Scrollbar;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Widgets;
import maple.ui.Window;
import maple.wz.WzNode;

/**
 * The NPC conversation window (CUtilDlgEx, 009acc20): UtilDlgEx t/c/s chrome 529 wide around a body of
 * 120..240px, script text at x 157 (25 when the speaker stands right) in a 341px column, the NPC's
 * portrait centred at x 80 (450) over the name bar, Prev/Next on the footer shelf and OK / Yes / No /
 * Accept / Decline / End Chat on its border (009a7c8b).
 */
public final class NpcDialog extends Window {
    private static final int TEXT_W = 341, INSET = 8, STEP = 8;
    private final World world;
    private final NpcTalk talk;
    private final RichText text;
    private final boolean input, right, scrolling;
    private final int body;
    private final float textX, textY, measured;
    private Sprite portrait;
    private String npcName = "";
    private final Scrollbar scroll;
    private Widgets.TextField field;
    private int hover = -1;
    private int styleIndex;

    public NpcDialog(Ui ui, World world, NpcTalk talk) {
        super(ui, "UtilDlgEx", null);
        this.world = world;
        this.talk = talk;
        modal = false;
        draggable = false;
        input = talk.type == 2 || talk.type == 3;
        right = (talk.speaker & 2) != 0;
        int inset = input ? 2 : INSET;
        text = RichText.layout(ui.g, displayText(), TEXT_W - inset * 2, new GameMarkup(world, ui.assets), RichText.BLACK);
        float m = (float) Math.ceil(text.height + inset * 2) + (talk.type == 4 ? 24 : 0) + (input ? 24 : 0);
        measured = m;
        int b = (int) Math.max(120, m);
        if (!input && (int) ((m - 240) / STEP) > 1) b = 240;
        b = (int) Math.min(b, Math.max(120, Ui.H - 86));
        body = b;
        scrolling = m > body;
        w = 529;
        h = body + 86;
        textX = (input ? 157 : (right ? 25 : 157)) - (scrolling ? 2 : 0);
        textY = 28 + (scrolling ? -6 : (int) ((body - m - (input ? 20 : 6)) / 2));
        x = Math.round((Ui.W - w) / 2f);
        y = Math.round((Ui.H - h) / 2f) - 30;
        if (y < 0) y = 0;
        keepPosition = true;

        scroll = add(new Scrollbar(ui.assets, "VScr3", textX + TEXT_W - 3, textY + 2, body + 3));
        scroll.setRange(Math.max(1, (int) Math.ceil(Math.max(0, m - body) / STEP) + 1), 0);
        scroll.visible = scrolling;

        loadPortrait();
        buildControls();
    }

    private String displayText() {
        if (talk.type == 7 && talk.styles.length > 0) return talk.text;
        return talk.text;
    }

    private void loadPortrait() {
        if ((talk.speaker & 1) != 0) return; // no NPC picture
        int id = talk.npcId;
        WzNode src = ui.assets.wz.get("Npc/" + String.format("%07d", id) + ".img");
        String link = src.get("info").getString("link", "");
        if (!link.isEmpty()) src = ui.assets.wz.get("Npc/" + link + ".img");
        WzNode frame = src.get("stand").get("0");
        if (!frame.exists()) {
            for (WzNode c : src.children()) {
                if (!c.name.equals("info") && c.get("0").exists()) {
                    frame = c.get("0");
                    break;
                }
            }
        }
        portrait = frame.exists() ? ui.assets.sprite(frame) : null;
        npcName = Names.npc(id);
    }

    private Button button(String name, float bx, float by, Runnable r) {
        return add(new Button(ui.assets, "UIWindow.img/UtilDlgEx/" + name, bx, by, r));
    }

    private void buildControls() {
        float edge = w - (right ? 140 : 10) - (scrolling ? 8 : 0);
        button("BtClose", 9, h - 26, this::endChat);
        switch (talk.type) {
            case 0:
                if (talk.prev) button("BtPrev", edge - 122, h - 77, () -> reply(0, 0, null));
                if (talk.next) button("BtNext", edge - 68, h - 77, () -> reply(1, 0, null));
                if (!talk.next) button("BtOK", w - 54, h - 26, () -> reply(1, 0, null)); // last page (also after Prev)
                break;
            case 1:
                button("BtYes", w - 108, h - 26, () -> reply(1, 0, null));
                button("BtNo", w - 54, h - 26, () -> reply(0, 0, null));
                break;
            case 0x0C:
                button("BtQYes", w - 130, h - 26, () -> reply(1, 0, null));
                button("BtQNo", w - 65, h - 26, () -> reply(0, 0, null));
                break;
            case 2:
            case 3: {
                field = add(new Widgets.TextField(textX + 4, textY + text.height + 8, 200, 15));
                if (talk.type == 2) {
                    field.text = talk.defText;
                    field.maxLength = 64;
                } else {
                    field.text = Integer.toString(talk.def);
                    field.maxLength = 10;
                    field.allowed = c -> Character.isDigit(c) || c == '-';
                }
                field.onEnter = s -> submit();
                Widgets.Focus.set(field);
                button("BtOK", w - 54, h - 26, this::submit);
                break;
            }
            case 7:
                if (talk.styles.length > 0) {
                    button("BtPrev", edge - 122, h - 77, () -> styleIndex = (styleIndex + talk.styles.length - 1) % talk.styles.length);
                    button("BtNext", edge - 68, h - 77, () -> styleIndex = (styleIndex + 1) % talk.styles.length);
                    button("BtOK", w - 54, h - 26, () -> reply(1, styleIndex, null));
                }
                break;
            default:
                break;
        }
    }

    private void submit() {
        if (field == null) return;
        if (talk.type == 2) {
            reply(1, 0, field.text);
            return;
        }
        int n;
        try {
            n = Integer.parseInt(field.text.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (n < talk.min || n > talk.max) {
            ui.open(new maple.ui.Dialogs.Notice(ui, "Please enter a number between " + talk.min + " and " + talk.max + ".", false, null, null));
            return;
        }
        reply(1, n, null);
    }

    private void endChat() {
        int action = (talk.type == 4 || talk.type == 2 || talk.type == 3 || talk.type == 7) ? 0 : -1;
        reply(action, 0, null);
    }

    private void reply(int action, int selection, String s) {
        if (world.talk != talk) {
            close();
            return;
        }
        close();
        world.answer(action, selection, s);
    }

    private float scrollOffset() {
        return scrolling ? scroll.position * STEP : 0;
    }

    @Override
    protected void drawContent(UiDraw g) {
        Sprite t = ui.assets.sprite("UIWindow.img/UtilDlgEx/t");
        Sprite c = ui.assets.sprite("UIWindow.img/UtilDlgEx/c");
        Sprite s = ui.assets.sprite("UIWindow.img/UtilDlgEx/s");
        if (t != null) g.image(t, 0, 0);
        float top = t != null ? t.h : 28;
        if (c != null) {
            for (float yy = 0; yy < body; yy += c.h) {
                float hh = Math.min(c.h, body - yy);
                if (hh >= c.h) g.image(c, 0, top + yy);
                else g.part(c, 0, top + yy, 0, 0, (int) c.w, (int) hh);
            }
        }
        if (s != null) g.image(s, 0, h - s.h);
        drawPortrait(g);
        int inset = input ? 2 : INSET;
        float off = scrollOffset();
        text.draw(g, textX + inset, textY + inset - off, hover, off - inset, off - inset + body - 2);
        if (field != null) {
            g.fill(field.x - 2, field.y - 2, field.w + 4, field.h + 4, 0xFFFFFFFF);
            g.outline(field.x - 2, field.y - 2, field.w + 4, field.h + 4, 0xFF999999);
        }
        if (talk.type == 7 && talk.styles.length > 0) {
            String name = styleName(talk.styles[styleIndex]);
            g.text((styleIndex + 1) + " / " + talk.styles.length + "  " + name, textX + inset, textY + text.height + 12, TEXT_W, Align.left, false, 12, true, RichText.BLUE);
        }
    }

    private String styleName(int id) {
        String folder = id / 10000 == 2 ? "Face" : "Hair";
        String n = ui.assets.wz.get("String/Eqp.img/Eqp/" + folder + "/" + id).getString("name", "");
        return n.isEmpty() ? Integer.toString(id) : n;
    }

    private void drawPortrait(UiDraw g) {
        if (portrait == null) return;
        Sprite bar = ui.assets.sprite("UIWindow.img/UtilDlgEx/bar");
        float center = right ? 450 : 80;
        float combined = portrait.h + 23;
        boolean tall = combined > body;
        float top = tall ? h - combined - 62 : 28 + (int) ((body - combined) / 2);
        float nameTop = tall ? h - combined - 52 + portrait.h : top + portrait.h;
        float nameLeft = center - 60;
        g.image(portrait, center - (int) (portrait.w / 2), top);
        if (bar != null) g.image(bar, nameLeft + (int) ((121 - bar.w) / 2), nameTop + 3);
        g.text(npcName, nameLeft, nameTop + 5, 121, Align.center, false, 12, false, 0xFFFFFFFF);
    }

    private int choiceAt(float lx, float ly) {
        int inset = input ? 2 : INSET;
        float off = scrollOffset();
        if (lx < textX || lx >= textX + TEXT_W || ly < textY || ly >= textY + body) return -1;
        return text.choiceAt(lx - textX - inset, ly - textY - inset + off);
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int c = choiceAt(lx, ly);
        if (c >= 0 && talk.type == 4) {
            UiSounds.play("BtMouseClick");
            reply(1, c, null);
            return false;
        }
        return false;
    }

    @Override
    public void onHover(boolean over) {
        if (!over) hover = -1;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        if (talk.type == 4) {
            int c = choiceAt(ui.mouseX - x, ui.mouseY - y);
            if (c != hover && c >= 0) UiSounds.play("BtMouseOver");
            hover = c;
        }
        if (world.talk != talk && isShowing()) close();
    }

    private boolean isShowing() {
        return ui.windows().contains(this);
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        if (scrolling) scroll.scroll(amount);
        return true;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            endChat();
            return true;
        }
        if (keycode == Input.Keys.ENTER) {
            if (field != null) submit();
            else if (talk.type == 0) reply(1, 0, null);
            else if (talk.type == 1 || talk.type == 0x0C) reply(1, 0, null);
            return true;
        }
        return false;
    }
}
