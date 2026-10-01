package maple.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.map.Field;
import maple.net.GameClient;
import maple.net.model.CharEntry;
import maple.ui.Button;
import maple.ui.Dialogs;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Widget;
import maple.ui.Widgets;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The v83 login flow after the automatic sign-in: character select, race select and the Explorer /
 * Cygnus Knight / Aran creators, on the UI.wz MapLogin.img scenery with Login.img controls.
 * Coordinates are the v83 client's own (as recovered by openms) unless marked "estimated".
 */
public final class LoginScreen {
    /** Scene pages: the camera centre is y = -8 - 600 * page (005fc0e4). */
    private static final int PAGE_SELECT = 2, PAGE_RACE = 3, PAGE_CREATE_BASE = 4;

    private enum Stage { SELECT, RACE, CREATE }

    private final Wz wz;
    private final Ui ui;
    private final GameClient client;
    public final Field scene;
    private Stage stage = Stage.SELECT;
    private double camY, camFromY, camToY;
    private int camTime, camDuration;
    private long time;
    private final Widget root = new Widget();
    private int selected, page;
    private final Map<Integer, Avatar> avatars = new HashMap<>();
    /** Whether every hairstyle/face in Character.wz is offered (option), not just the starter ones. */
    public static boolean allStyles;

    // creation state
    private int race = 1; // 0 knight, 1 explorer, 2 aran
    private boolean namePhase = true;
    private String newName = "";
    private int gender;
    private final int[] choice = new int[8];
    private int[][] options = new int[8][];
    private Avatar preview;
    private boolean previewDirty = true;
    private Widgets.TextField nameField;
    private boolean waitingName;
    private long sinceStage;
    public boolean entering;

    public LoginScreen(Wz wz, Ui ui, GameClient client) {
        this.wz = wz;
        this.ui = ui;
        this.client = client;
        scene = new Field(wz, wz.get("UI/MapLogin.img"), -1, false);
        camY = centerY(PAGE_SELECT);
        camToY = camY;
        buildSelect();
    }

    private static double centerY(int page) {
        return -8 - 600.0 * page;
    }

    private String loginPath(String p) {
        return "Login.img/" + p;
    }

    private String createBranch() {
        return race == 0 ? "NewCharKnight" : race == 2 ? "NewCharAran" : "NewChar";
    }

    private void moveCamera(int pageNo) {
        camFromY = camY;
        camToY = centerY(pageNo);
        int pages = (int) Math.round(Math.abs(camToY - camFromY) / 600.0);
        camDuration = 500 + 300 * pages; // native stage duration
        camTime = 0;
    }

    // ------------------------------------------------------------------ building

    private void attach() {
        ui.hud.clear();
        root.clear();
        root.x = (Ui.W - 800) / 2f;
        root.w = 800;
        root.h = 600;
        ui.hud.add(root);
    }

    private void frame() {
        // The book frame (Login.img/Common/frame, 800x600) behind the stage controls.
        Sprite f = ui.assets.sprite(loginPath("Common/frame"));
        if (f != null) root.add(new Widgets.Image(f, 0, 0, false));
    }

    private void buildSelect() {
        stage = Stage.SELECT;
        sinceStage = 0;
        attach();
        List<CharEntry> chars = client.characters;
        page = Math.min(page, Math.max(0, (chars.size() - 1) / 3));
        // spotlight behind the selected character
        if (!chars.isEmpty()) {
            float sx = 280 + 125 * (selected % 3);
            root.add(new Widgets.Anim(ui.assets.animation(loginPath("CharSelect/effect/0")), sx, 0, false));
            root.add(new Widgets.Anim(ui.assets.animation(loginPath("CharSelect/effect/1")), sx, 0, true));
        }
        frame();
        root.add(new Widgets.Image(ui.assets.sprite(loginPath("Common/step/3")), 0, 0, false)); // estimated: frame corner
        for (int i = 0; i < 3; i++) {
            int idx = page * 3 + i;
            float fx = 280 + 125 * i, fy = 370;
            if (idx < chars.size()) {
                CharEntry e = chars.get(idx);
                int job = e.stats.job;
                String sign = job / 1000 == 1 ? "knight" : (job / 100 == 21 || job == 2000) ? "aran" : "adventure";
                root.add(new Widgets.Anim(ui.assets.animation(loginPath("CharSelect/" + sign)), fx, fy, false));
                root.add(new AvatarView(e, fx, fy, idx));
                root.add(new NameTag(e.stats.name, i, idx));
                root.add(new SlotHit(idx, fx - 40, fy - 90, 80, 100));
            } else {
                root.add(new Widgets.Anim(ui.assets.animation(loginPath("CharSelect/character/0")), fx, fy, false));
                root.add(new Widgets.Anim(ui.assets.animation(loginPath("CharSelect/character/1")), fx, fy, false));
            }
        }
        if (!chars.isEmpty()) root.add(new InfoPanel(chars.get(Math.min(selected, chars.size() - 1)), 180 + 130 * (selected % 3), 160));
        Button select = root.add(new Button(ui.assets, loginPath("CharSelect/BtSelect"), 604, 147, this::enter));
        select.disabled = chars.isEmpty();
        Button create = root.add(new Button(ui.assets, loginPath("CharSelect/BtNew"), 604, 185, this::startCreate));
        create.disabled = chars.size() >= Math.max(3, client.characterSlots + chars.size());
        Button delete = root.add(new Button(ui.assets, loginPath("CharSelect/BtDelete"), 604, 234, this::askDelete));
        delete.disabled = chars.isEmpty();
        int pages = Math.max(1, (chars.size() + 2) / 3 + (chars.size() % 3 == 0 && chars.size() < client.characterSlots + chars.size() ? 1 : 0));
        if (page > 0) root.add(new PageButton(loginPath("CharSelect/pageL"), 140, 293, -1));
        if ((page + 1) * 3 < chars.size() + 1 && (page + 1) * 3 < Math.max(3, chars.size() + client.characterSlots)) {
            root.add(new PageButton(loginPath("CharSelect/pageR"), 588, 295, 1));
        }
        root.add(new Button(ui.assets, loginPath("Common/BtExit"), 0, 548, () -> Gdx.app.exit())); // estimated: frame corner
    }

    private void buildRace() {
        stage = Stage.RACE;
        sinceStage = 0;
        attach();
        frame();
        root.add(new Widgets.Image(ui.assets.sprite(loginPath("Common/step/4")), 0, 0, false));
        // Estimated layout (not recovered): the three portraits side by side, description below.
        String[][] races = {{"knight", "BtKnight"}, {"normal", "BtNormal"}, {"aran", "BtAran"}};
        float x = 78;
        int[] order = {0, 1, 2};
        for (int k : order) {
            final int r = k;
            String base = loginPath("RaceSelect/" + races[k][0]);
            Sprite on = ui.assets.sprite(base + "/OnAnimation/0");
            Button b = new Button(ui.assets, base + "/" + races[k][1], x, 110, () -> {
                race = r;
                UiSounds.play("RaceSelect");
                buildRace();
            });
            if (race == k && on != null) root.add(new Widgets.Anim(ui.assets.animation(base + "/OnAnimation"), x, 110, false));
            else root.add(b);
            x += b.w + 15;
        }
        String[] texts = {"knight", "normal", "aran"};
        root.add(new Widgets.Image(ui.assets.sprite(loginPath("RaceSelect/" + texts[race] + "/text/0")), 110, 340, false));
        root.add(new Button(ui.assets, loginPath("RaceSelect/BtSelect"), 400 - 36, 515, () -> {
            UiSounds.play("BtMouseClick");
            startLook();
        }));
        root.add(new Button(ui.assets, loginPath("Common/BtStart"), 0, 548, this::backToSelect)); // estimated
    }

    private void startCreate() {
        UiSounds.play("BtMouseClick");
        race = 1;
        buildRace();
        moveCamera(PAGE_RACE);
    }

    private void backToSelect() {
        buildSelect();
        moveCamera(PAGE_SELECT);
    }

    private void startLook() {
        stage = Stage.CREATE;
        namePhase = true;
        newName = "";
        gender = client.characters.isEmpty() ? 0 : client.characters.get(0).stats.gender;
        loadOptions();
        buildCreate();
        moveCamera(PAGE_CREATE_BASE + race);
    }

    /** MakeCharInfo.img lists the starter choices per race and gender. */
    private void loadOptions() {
        WzNode mci = wz.get("Etc/MakeCharInfo.img");
        String g = gender == 0 ? "CharMale" : "CharFemale";
        WzNode src;
        if (race == 0) src = mci.get("Premium" + g);
        else if (race == 2) src = mci.get("Orient" + g);
        else src = mci.get("Info").get(g);
        if (!src.exists()) src = mci.get("Info").get(g);
        for (int cat = 0; cat < 8; cat++) {
            List<Integer> ids = new ArrayList<>();
            for (WzNode n : src.get(cat).children()) ids.add(n.asInt(0));
            if (allStyles && (cat == 0 || cat == 1)) ids = allIds(cat == 0 ? "Face" : "Hair", cat == 1);
            if (ids.isEmpty()) ids.add(0);
            options[cat] = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) options[cat][i] = ids.get(i);
            choice[cat] = 0;
        }
        previewDirty = true;
    }

    /** Every face (or hair style, colour 0) for the current gender in Character.wz. */
    private List<Integer> allIds(String folder, boolean hair) {
        List<Integer> out = new ArrayList<>();
        for (WzNode n : wz.get("Character/" + folder).children()) {
            String s = n.name.replace(".img", "");
            try {
                int id = Integer.parseInt(s);
                int genderDigit = (id / 1000) % 10;
                if ((gender == 0 && genderDigit != 0 && genderDigit != 2) || (gender == 1 && genderDigit != 1 && genderDigit != 2)) continue;
                if (hair && id % 10 != 0) continue;
                out.add(id);
            } catch (NumberFormatException ignored) {
                // not an item image
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private void buildCreate() {
        sinceStage = 0;
        attach();
        frame();
        String nc = loginPath(createBranch());
        root.add(new PreviewView(422, 339));
        if (namePhase) {
            root.add(new Widgets.Image(ui.assets.sprite(nc + "/charName"), 509, 95, false));
            nameField = root.add(new Widgets.TextField(545, 203, 120, 15));
            nameField.center = true;
            nameField.text = newName;
            nameField.hint = "Character name (4-12 letters or numbers)";
            nameField.allowed = c -> Character.isLetterOrDigit(c) && c < 128;
            nameField.onEnter = s -> confirmName();
            Widgets.Focus.set(nameField);
            root.add(new Button(ui.assets, nc + "/BtYes", 536, 273, this::confirmName));
            root.add(new Button(ui.assets, nc + "/BtNo", 610, 273, () -> {
                buildRace();
                moveCamera(PAGE_RACE);
            }));
        } else {
            root.add(new Widgets.Image(ui.assets.sprite(nc + "/charSet"), 509, 95, false));
            String[] labels = {"face", "hair", "hairColor", "skin", "top", "bottom", "shoes", "weapon"};
            for (int row = 0; row < 9; row++) {
                final int r = row;
                float ry = 200 + 18 * row;
                root.add(new Widgets.Image(ui.assets.sprite(nc + "/avatarSel/" + row + "/normal"), 520, ry, false));
                root.add(new Widgets.Label(520 + 72, ry + 2, 112, () -> optionText(r)).align(Align.center));
                root.add(new Button(ui.assets, nc + "/BtLeft", 520 + 57, ry + 1, () -> cycle(r, -1)));
                root.add(new Button(ui.assets, nc + "/BtRight", 520 + 185, ry + 1, () -> cycle(r, 1)));
            }
            root.add(new Button(ui.assets, nc + "/BtYes", 546, 425, this::create));
            root.add(new Button(ui.assets, nc + "/BtNo", 620, 425, () -> {
                namePhase = true;
                buildCreate();
            }));
        }
    }

    private String optionText(int row) {
        if (row == 8) return gender == 0 ? "Male" : "Female";
        int id = options[row][choice[row]];
        switch (row) {
            case 2: return colorName(id);
            case 3: return skinName(id);
            default: {
                String name = itemName(row, id);
                return name.isEmpty() ? Integer.toString(id) : name;
            }
        }
    }

    private String itemName(int row, int id) {
        if (row == 0) return wz.get("String/Eqp.img/Eqp/Face/" + id).getString("name", "");
        if (row == 1) return wz.get("String/Eqp.img/Eqp/Hair/" + id).getString("name", "");
        return maple.game.ItemInfo.get(id).name;
    }

    private static String colorName(int c) {
        String[] n = {"Black", "Red", "Orange", "Blonde", "Green", "Blue", "Purple", "Brown"};
        return c >= 0 && c < n.length ? n[c] : Integer.toString(c);
    }

    private static String skinName(int c) {
        String[] n = {"Light", "Tan", "Dark", "Pale", "Ashen", "Green"};
        return c >= 0 && c < n.length ? n[c] : Integer.toString(c);
    }

    private void cycle(int row, int dir) {
        if (row == 8) {
            gender = 1 - gender;
            loadOptions();
            return;
        }
        int n = options[row].length;
        choice[row] = ((choice[row] + dir) % n + n) % n;
        previewDirty = true;
    }

    private void confirmName() {
        if (nameField != null) newName = nameField.text.trim();
        if (!newName.matches("[A-Za-z0-9]{4,12}")) {
            ui.open(new Dialogs.LoginNotice(ui, null, "Your name must be 4 to 12 letters or numbers.", false, null, null));
            return;
        }
        waitingName = true;
        client.checkName(newName);
    }

    private void create() {
        int face = options[0][choice[0]];
        int hair = options[1][choice[1]];
        int color = options[2][choice[2]];
        int skin = options[3][choice[3]];
        int[] gear = {options[4][choice[4]], options[5][choice[5]], options[6][choice[6]], options[7][choice[7]]};
        int job = race == 0 ? 0 : race == 2 ? 2 : 1;
        client.createCharacter(newName, job, face, hair, color, skin, gear[0], gear[1], gear[2], gear[3], gender);
        creating = true;
    }

    private boolean creating;

    private void enter() {
        if (client.characters.isEmpty() || entering) return;
        UiSounds.play("CharSelect");
        entering = true;
        client.selectCharacter(client.characters.get(Math.min(selected, client.characters.size() - 1)).stats.id);
    }

    private void askDelete() {
        if (client.characters.isEmpty()) return;
        CharEntry e = client.characters.get(Math.min(selected, client.characters.size() - 1));
        ui.open(new Dialogs.LoginNotice(ui, "Delete character", "Are you sure you want to delete " + e.stats.name + "?", true, () -> {
            client.deleteCharacter(e.stats.id);
            avatars.remove(e.stats.id);
        }, null));
    }

    private void select(int idx) {
        if (idx == selected) return;
        selected = idx;
        UiSounds.play("CharSelect");
        buildSelect();
    }

    // ------------------------------------------------------------------ frame

    private int lastCount = -1;

    public void update(long ms) {
        time += ms;
        sinceStage += ms;
        if (camTime < camDuration) {
            camTime = (int) Math.min(camDuration, camTime + ms);
            double t = camTime / (double) camDuration;
            double s = t * t * (3 - 2 * t);
            camY = camFromY + (camToY - camFromY) * s;
        } else {
            camY = camToY;
        }
        scene.update(time);
        if (waitingName && client.nameAvailable != null) {
            waitingName = false;
            if (client.nameAvailable) {
                namePhase = false;
                buildCreate();
            } else {
                ui.open(new Dialogs.LoginNotice(ui, null, "This name is already in use.", false, null, null));
            }
        }
        if (creating && client.state == GameClient.State.CHARACTER_SELECT) {
            creating = false;
            if (client.error != null) {
                ui.open(new Dialogs.LoginNotice(ui, null, "The character could not be created.", false, null, null));
                client.error = null;
            } else {
                selected = Math.max(0, client.characters.size() - 1);
                page = selected / 3;
                backToSelect();
            }
        }
        if (stage == Stage.SELECT && client.characters.size() != lastCount) {
            lastCount = client.characters.size();
            selected = Math.min(selected, Math.max(0, lastCount - 1));
            buildSelect();
        }
        if (stage == Stage.SELECT && client.error != null && !creating) {
            ui.open(new Dialogs.LoginNotice(ui, null, client.error, false, null, null));
            client.error = null;
        }
    }

    public boolean keyDown(int key) {
        if (stage == Stage.SELECT) {
            int n = client.characters.size();
            if (key == Input.Keys.LEFT && selected > 0) {
                selected--;
                page = selected / 3;
                buildSelect();
                return true;
            }
            if (key == Input.Keys.RIGHT && selected < n - 1) {
                selected++;
                page = selected / 3;
                buildSelect();
                return true;
            }
            if (key == Input.Keys.ENTER) {
                enter();
                return true;
            }
        }
        return false;
    }

    /** The scene camera's top-left in world coords for a view width of Ui.W. */
    public double viewX() { return -Ui.W / 2.0; }

    public double viewY() { return camY - 300; }

    public void drawScene(SpriteBatch batch, float vw, float vh) {
        double cx = 0, cy = Math.round(camY);
        double viewX = vw / 2.0 - cx, viewY = vh / 2.0 - cy;
        scene.drawBackgrounds(batch, viewX, viewY, vw, vh, time);
    }

    public void drawLayers(SpriteBatch batch) {
        for (int layer = 0; layer < 8; layer++) scene.drawLayer(batch, layer, 1, time);
    }

    public void drawFronts(SpriteBatch batch, float vw, float vh) {
        double cx = 0, cy = Math.round(camY);
        scene.drawForegrounds(batch, vw / 2.0 - cx, vh / 2.0 - cy, vw, vh, time);
    }

    public void dispose() {
        scene.dispose();
        for (Avatar a : avatars.values()) a.dispose();
        avatars.clear();
        if (preview != null) preview.dispose();
    }

    // ------------------------------------------------------------------ widgets

    private Avatar avatarFor(CharEntry e) {
        return avatars.computeIfAbsent(e.stats.id, k -> {
            int[] ids = new int[e.look.equips.size()];
            int i = 0;
            for (int id : e.look.equips.values()) ids[i++] = id;
            return new Avatar(wz, e.look.skin, e.look.face, e.look.hair, ids);
        });
    }

    /** A character standing on its slot; the selected one walks (060599b sends action 2 vs 4). */
    private final class AvatarView extends Widget {
        final CharEntry e;
        final int idx;

        AvatarView(CharEntry e, float x, float y, int idx) {
            this.e = e;
            this.idx = idx;
            this.x = x;
            this.y = y;
        }

        @Override
        public void draw(UiDraw g) {
            Avatar a = avatarFor(e);
            boolean sel = idx == selected;
            String st = sel ? a.walkStance() : a.standStance();
            int n = Math.max(1, a.frameCount(st));
            int total = 0;
            for (int i = 0; i < n; i++) total += a.delay(st, i);
            long t = time % Math.max(1, total);
            int f = 0;
            for (int i = 0; i < n; i++) {
                t -= a.delay(st, i);
                if (t < 0) { f = i; break; }
            }
            g.artMode();
            a.draw(g.batch, st, f, g.tx, g.ty, false);
        }
    }

    /** The click area over a slot. */
    private final class SlotHit extends Widget {
        final int idx;

        SlotHit(int idx, float x, float y, float w, float h) {
            super(x, y, w, h);
            this.idx = idx;
        }

        @Override
        public boolean interactive() { return true; }

        @Override
        public boolean onPress(float lx, float ly) {
            select(idx);
            return false;
        }

        @Override
        public void onDoubleClick(float lx, float ly) {
            select(idx);
            enter();
        }
    }

    /** CharSelect/nameTag: 9px middle tiles between two caps, text centred (00605b52). */
    private final class NameTag extends Widget {
        final String name;
        final int slot, idx;

        NameTag(String name, int slot, int idx) {
            this.name = name;
            this.slot = slot;
            this.idx = idx;
        }

        @Override
        public void draw(UiDraw g) {
            int state = idx == selected ? 1 : 0;
            float tw = g.textWidth(name, 12, false);
            int width = (int) Math.min(216, tw < 41 ? 58 : tw + 18);
            float left = 286 + slot * 125 - width / 2f;
            float top = 370;
            Sprite l = ui.assets.sprite(loginPath("CharSelect/nameTag/" + state + "/0"));
            Sprite m = ui.assets.sprite(loginPath("CharSelect/nameTag/" + state + "/1"));
            Sprite r = ui.assets.sprite(loginPath("CharSelect/nameTag/" + state + "/2"));
            float ox = g.tx, oy = g.ty;
            g.tx = root.screenX();
            g.ty = root.screenY();
            if (m != null) for (int i = 0; i * 9 < width - 10; i++) g.image(m, left + i * 9, top);
            if (l != null) g.image(l, left, top);
            if (r != null) g.image(r, left + width - 10, top);
            g.text(name, left + (int) ((width - tw) / 2) - 1, top + 2, 12, false, state == 1 ? 0xFFFFFFFF : 0xFFFFFFFF);
            g.tx = ox;
            g.ty = oy;
        }
    }

    /** The selected character's details on a scroll (0060292f / 00603dbc). */
    private final class InfoPanel extends Widget {
        final CharEntry e;

        InfoPanel(CharEntry e, float x, float y) {
            this.e = e;
            this.x = x;
            this.y = y;
            this.w = 183;
            this.h = 115;
        }

        @Override
        public void draw(UiDraw g) {
            g.image(loginPath("CharSelect/scroll/0/3"), -20, -25);
            g.fill(0, 0, 183, 112, 0x30FFFF00);
            g.image(loginPath("CharSelect/charInfo"), 0, 0);
            int black = 0xFF000000;
            g.text(maple.ui.JobNames.name(e.stats.job), 46, 1, 135, Align.left, false, 12, false, black);
            g.text(Integer.toString(e.stats.level), 46, 19, 45, Align.left, false, 12, false, black);
            g.text(Integer.toString(e.stats.fame), 136, 19, 45, Align.left, false, 12, false, black);
            g.text(Integer.toString(e.stats.str), 46, 37, 45, Align.left, false, 12, false, black);
            g.text(Integer.toString(e.stats.intel), 136, 37, 45, Align.left, false, 12, false, black);
            g.text(Integer.toString(e.stats.dex), 46, 55, 45, Align.left, false, 12, false, black);
            g.text(Integer.toString(e.stats.luk), 136, 55, 45, Align.left, false, 12, false, black);
            g.text("Ranking Not Available", 36, 99, 145, Align.left, false, 12, false, black);
        }
    }

    /** pageL/pageR: two authored image states (0 normal, 1 hover). */
    private final class PageButton extends Widget {
        final Animation normal, over;
        final int dir;
        boolean hover;

        PageButton(String path, float x, float y, int dir) {
            this.x = x;
            this.y = y;
            this.dir = dir;
            normal = ui.assets.animation(path + "/0");
            over = ui.assets.animation(path + "/1");
            Sprite s = normal.first();
            w = s == null ? 40 : s.w;
            h = s == null ? 40 : s.h;
        }

        @Override
        public boolean interactive() { return true; }

        @Override
        public void onHover(boolean o) { hover = o; }

        @Override
        public boolean onPress(float lx, float ly) {
            UiSounds.play("BtMouseClick");
            page = Math.max(0, page + dir);
            selected = Math.min(page * 3, Math.max(0, client.characters.size() - 1));
            buildSelect();
            return false;
        }

        @Override
        public void draw(UiDraw g) {
            Animation a = hover && !over.isEmpty() ? over : normal;
            Sprite s = a.first();
            if (s != null) g.anim(a, s.ox, s.oy, time);
        }
    }

    /** The new character, standing at its preview spot (feet 422,339). */
    private final class PreviewView extends Widget {
        PreviewView(float x, float y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public void draw(UiDraw g) {
            if (previewDirty) {
                previewDirty = false;
                if (preview != null) preview.dispose();
                int face = options[0][choice[0]], hair = options[1][choice[1]] + options[2][choice[2]];
                int skin = options[3][choice[3]];
                int[] eq = {options[4][choice[4]], options[5][choice[5]], options[6][choice[6]], options[7][choice[7]]};
                preview = new Avatar(wz, skin, face, hair, eq);
            }
            if (preview == null) return;
            String st = preview.standStance();
            int n = Math.max(1, preview.frameCount(st));
            int f = (int) ((time / 500) % n);
            g.artMode();
            preview.draw(g.batch, st, f, g.tx, g.ty, false);
        }
    }
}
