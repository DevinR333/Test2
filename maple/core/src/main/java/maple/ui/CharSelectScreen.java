package maple.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import maple.chr.Avatar;
import maple.net.GameClient;
import maple.net.model.CharEntry;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Character select + a basic character creator. Functional stand-in until the v83 Login.img
 * screens are built from the client's own UI layout.
 */
public final class CharSelectScreen {
    private final Wz wz;
    private final GameClient client;
    private final Map<Integer, Avatar> avatars = new HashMap<>();
    private int selected;
    private boolean creating;
    private String newName = "";
    private int gender;
    private boolean nameRequested;
    private boolean wasTouched;
    private final GlyphLayout glyphs = new GlyphLayout();
    private long time;
    public String message;

    public CharSelectScreen(Wz wz, GameClient client) {
        this.wz = wz;
        this.client = client;
    }

    public static int[] lookFor(CharEntry e) {
        int[] ids = new int[e.look.equips.size()];
        int i = 0;
        for (int id : e.look.equips.values()) ids[i++] = id;
        return ids;
    }

    private Avatar avatar(CharEntry e) {
        return avatars.computeIfAbsent(e.stats.id, k -> new Avatar(wz, e.look.skin, e.look.face, e.look.hair, lookFor(e)));
    }

    /** The first option of each MakeCharInfo category: face, hair, hair colour, skin, top, bottom, shoes, weapon. */
    private int[] defaultLook(int gender) {
        WzNode info = wz.get("Etc/MakeCharInfo.img/Info/" + (gender == 0 ? "CharMale" : "CharFemale"));
        int[] out = new int[8];
        int[] fallback = gender == 0
                ? new int[]{20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000}
                : new int[]{21000, 31000, 0, 0, 1041002, 1061002, 1072001, 1302000};
        for (int i = 0; i < 8; i++) {
            WzNode cat = info.get(i);
            WzNode first = cat.get(0);
            out[i] = first.exists() ? first.asInt(fallback[i]) : fallback[i];
        }
        return out;
    }

    // ---- layout (UI units) ----
    private float bx(float uw, int i, int n) { return uw / 2 - (n * 130 + (n - 1) * 20) / 2f + i * 150; }

    public void render(SpriteBatch premult, SpriteBatch text, ShapeRenderer shapes, BitmapFont font,
                       com.badlogic.gdx.math.Matrix4 projection, float uw, float uh, float uiScale, float dt) {
        time += (long) (dt * 1000);
        premult.setProjectionMatrix(projection);
        text.setProjectionMatrix(projection);
        shapes.setProjectionMatrix(projection);
        handleInput(uw, uh, uiScale);
        Gdx.gl.glClearColor(0.12f, 0.16f, 0.24f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        int n = client.characters.size();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (!creating) {
            for (int i = 0; i < n; i++) {
                shapes.setColor(i == selected ? 0.95f : 0.3f, i == selected ? 0.8f : 0.35f, i == selected ? 0.3f : 0.45f, 0.35f);
                shapes.rect(bx(uw, i, n), uh * 0.3f, 130, 190);
            }
        }
        button(shapes, uw / 2 - 170, uh - 80, 160, 50);
        button(shapes, uw / 2 + 10, uh - 80, 160, 50);
        if (creating) {
            button(shapes, uw / 2 - 170, uh * 0.72f, 160, 44);
            button(shapes, uw / 2 + 10, uh * 0.72f, 160, 44);
        }
        shapes.end();

        premult.begin();
        if (creating) {
            int[] look = defaultLook(gender);
            Avatar a = avatars.computeIfAbsent(-1 - gender, k -> new Avatar(wz, look[3], look[0], look[1] + look[2], new int[]{look[4], look[5], look[6], look[7]}));
            a.draw(premult, "stand1", (int) (time / 500) % Math.max(1, a.frameCount("stand1")), uw / 2, uh * 0.55f, false);
        } else {
            for (int i = 0; i < n; i++) {
                CharEntry e = client.characters.get(i);
                Avatar a = avatar(e);
                String st = i == selected ? "walk1" : "stand1";
                a.draw(premult, st, (int) (time / (i == selected ? 180 : 500)) % Math.max(1, a.frameCount(st)), bx(uw, i, n) + 65, uh * 0.3f + 150, false);
            }
        }
        premult.end();

        text.begin();
        font.setColor(1, 1, 1, 1);
        center(text, font, client.worldName.isEmpty() ? "Scania" : client.worldName + "  -  Channel 1", uw / 2, 30);
        if (creating) {
            center(text, font, "New Explorer", uw / 2, uh * 0.15f);
            center(text, font, "Name: " + (newName.isEmpty() ? "(tap Name)" : newName), uw / 2, uh * 0.63f);
            center(text, font, "Name", uw / 2 - 90, uh * 0.72f + 22);
            center(text, font, gender == 0 ? "Male" : "Female", uw / 2 + 90, uh * 0.72f + 22);
            center(text, font, "Back", uw / 2 - 90, uh - 55);
            center(text, font, "Create", uw / 2 + 90, uh - 55);
        } else {
            if (n == 0) center(text, font, "No characters yet. Create one!", uw / 2, uh * 0.45f);
            for (int i = 0; i < n; i++) {
                CharEntry e = client.characters.get(i);
                center(text, font, e.stats.name, bx(uw, i, n) + 65, uh * 0.3f + 165);
                center(text, font, "Lv. " + e.stats.level, bx(uw, i, n) + 65, uh * 0.3f + 182);
            }
            center(text, font, "New", uw / 2 - 90, uh - 55);
            center(text, font, n == 0 ? "-" : "Start", uw / 2 + 90, uh - 55);
        }
        String msg = client.error != null ? client.error : message;
        if (msg != null) {
            font.setColor(1, 0.6f, 0.6f, 1);
            center(text, font, msg, uw / 2, 60);
        }
        text.end();
    }

    private static void button(ShapeRenderer s, float x, float y, float w, float h) {
        s.setColor(0.2f, 0.45f, 0.85f, 0.9f);
        s.rect(x, y, w, h);
    }

    private boolean hit(float x, float y, float bx, float by, float bw, float bh) {
        return x >= bx && x <= bx + bw && y >= by && y <= by + bh;
    }

    private void handleInput(float uw, float uh, float uiScale) {
        boolean touched = Gdx.input.isTouched();
        boolean tap = !touched && wasTouched;
        wasTouched = touched;
        int n = client.characters.size();
        if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT) && selected > 0) selected--;
        if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT) && selected < n - 1) selected++;
        boolean enter = Gdx.input.isKeyJustPressed(Input.Keys.ENTER);
        if (!tap && !enter) return;
        float x = Gdx.input.getX() / uiScale, y = Gdx.input.getY() / uiScale;
        if (creating) {
            if (tap && hit(x, y, uw / 2 - 170, uh * 0.72f, 160, 44)) askName();
            else if (tap && hit(x, y, uw / 2 + 10, uh * 0.72f, 160, 44)) gender = 1 - gender;
            else if (tap && hit(x, y, uw / 2 - 170, uh - 80, 160, 50)) creating = false;
            else if (enter || hit(x, y, uw / 2 + 10, uh - 80, 160, 50)) create();
            return;
        }
        for (int i = 0; i < n; i++) if (tap && hit(x, y, bx(uw, i, n), uh * 0.3f, 130, 190)) selected = i;
        if (tap && hit(x, y, uw / 2 - 170, uh - 80, 160, 50)) {
            creating = true;
            message = null;
            client.error = null;
            if (newName.isEmpty()) askName();
        } else if ((enter || (tap && hit(x, y, uw / 2 + 10, uh - 80, 160, 50))) && n > 0) {
            client.selectCharacter(client.characters.get(Math.min(selected, n - 1)).stats.id);
        }
    }

    private void askName() {
        if (nameRequested) return;
        nameRequested = true;
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override
            public void input(String text) {
                newName = text.trim();
                nameRequested = false;
            }

            @Override
            public void canceled() {
                nameRequested = false;
            }
        }, "Character name", newName, "3 to 12 letters or numbers");
    }

    /** Sets a name directly (for devices/tests without a text dialog). */
    public void setName(String name) {
        newName = name;
    }

    public void startCreating() {
        creating = true;
    }

    public void create() {
        if (!newName.matches("[a-zA-Z0-9]{3,12}")) {
            message = "Names are 3 to 12 letters or numbers.";
            return;
        }
        int[] l = defaultLook(gender);
        client.createCharacter(newName, 1, l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], gender);
        creating = false;
        selected = client.characters.size();
        message = null;
    }

    private void center(SpriteBatch b, BitmapFont f, String s, float x, float y) {
        glyphs.setText(f, s);
        f.draw(b, s, x - glyphs.width / 2, y - glyphs.height / 2);
    }

    public void dispose() {
        for (Avatar a : avatars.values()) a.dispose();
        avatars.clear();
    }
}
