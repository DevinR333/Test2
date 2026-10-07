package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.game.ItemInfo;
import maple.ui.JobNames;
import maple.game.NpcTalk;
import maple.game.World;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widgets;
import maple.ui.Window;
import maple.wz.WzNode;
import offline.UltimateExplorer;

import java.util.ArrayList;
import java.util.List;

/**
 * The Ultimate Explorer creation screen Empress Cygnus opens (NPC get-text carrying
 * {@link UltimateExplorer#CREATOR}). It is the v83 Explorer creator (Login.img/NewChar): first the
 * name board with the name and the 2nd job, then the look board (face, hair, hair colour, skin,
 * gender, and the Empress's Fine Set it will wear), beside a preview of the new character. The answer
 * goes back as the get-text reply: "name|job|face|hair|color|skin|gender".
 * The same screen serves the Maple Life cash items (a Lv. 30 1st-job character of one of the five
 * classes), whose choice goes out as the item's USE_CASH_ITEM request.
 */
public final class UltimateCreator extends Window {
    private static final String NC = "Login.img/NewChar/";
    private static final int PREVIEW_W = 190, BOARD_X = PREVIEW_W;
    private final World world;
    private final NpcTalk talk;
    /** Maple Life: the item's Cash slot and id (0 for an Ultimate Explorer). */
    private final int lifeSlot, lifeItem;
    private static final String[] LIFE_CLASSES = {"Warrior", "Magician", "Bowman", "Thief", "Pirate"};
    private String error;
    private boolean namePhase = true;
    private String name = "";
    private int jobIndex, gender;
    private final int[] choice = new int[4];
    private final int[][] options = new int[4][];
    private Widgets.TextField nameField;
    private Avatar preview;
    private boolean previewDirty = true;
    private long time;

    public UltimateCreator(Ui ui, World world, NpcTalk talk) {
        this(ui, world, talk, 0, 0);
    }

    /** Maple Life (5431000 / 5432000) from the Cash inventory slot. */
    public static UltimateCreator mapleLife(Ui ui, World world, int slot, int itemId) {
        return new UltimateCreator(ui, world, null, slot, itemId);
    }

    private UltimateCreator(Ui ui, World world, NpcTalk talk, int lifeSlot, int lifeItem) {
        super(ui, "UltimateCreator", null);
        this.world = world;
        this.talk = talk;
        this.lifeSlot = lifeSlot;
        this.lifeItem = lifeItem;
        String rest = talk == null ? "" : talk.text.substring(UltimateExplorer.CREATOR.length()).trim();
        error = rest.isEmpty() ? null : rest;
        w = PREVIEW_W + 225;
        h = 377;
        x = Math.round((Ui.W - w) / 2f);
        y = Math.round((Ui.H - h) / 2f);
        draggable = false;
        keepPosition = true;
        modal = true;
        gender = world.data() == null ? 0 : world.data().stats.gender;
        loadOptions();
        build();
    }

    private int job() {
        return lifeItem != 0 ? jobIndex : UltimateExplorer.JOBS[jobIndex][0];
    }

    private String jobName() {
        return lifeItem != 0 ? LIFE_CLASSES[jobIndex] : JobNames.name(job());
    }

    private int jobCount() {
        return lifeItem != 0 ? LIFE_CLASSES.length : UltimateExplorer.JOBS.length;
    }

    /** The Explorer starter faces, hairs, hair colours and skins (Etc/MakeCharInfo.img), or every style with the option on. */
    private void loadOptions() {
        WzNode src = ui.assets.wz.get("Etc/MakeCharInfo.img/Info/" + (gender == 0 ? "CharMale" : "CharFemale"));
        for (int cat = 0; cat < 4; cat++) {
            List<Integer> ids = new ArrayList<>();
            for (WzNode n : src.get(cat).children()) ids.add(n.asInt(0));
            if (offline.OfflineOptions.allStyles && cat < 2) ids = allIds(cat == 0 ? "Face" : "Hair", cat == 1);
            if (ids.isEmpty()) ids.add(cat == 0 ? 20000 : cat == 1 ? 30000 : 0);
            options[cat] = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) options[cat][i] = ids.get(i);
            choice[cat] = 0;
        }
        previewDirty = true;
    }

    private List<Integer> allIds(String folder, boolean hair) {
        List<Integer> out = new ArrayList<>();
        for (WzNode n : ui.assets.wz.get("Character/" + folder).children()) {
            try {
                int id = Integer.parseInt(n.name.replace(".img", ""));
                int g = (id / 1000) % 10;
                if ((gender == 0 && g != 0 && g != 2) || (gender == 1 && g != 1 && g != 2)) continue;
                if (hair && id % 10 != 0) continue;
                out.add(id);
            } catch (NumberFormatException ignored) {
                // not a style image
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private void build() {
        clear();
        if (namePhase) {
            add(new Widgets.Image(ui.assets.sprite(NC + "charName"), BOARD_X + 12, 60, false));
            nameField = add(new Widgets.TextField(BOARD_X + 12 + 36, 60 + 108, 120, 15));
            nameField.center = true;
            nameField.text = name;
            nameField.hint = "Name (4-12 letters or numbers)";
            nameField.allowed = c -> Character.isLetterOrDigit(c) && c < 128;
            nameField.onEnter = s -> next();
            Widgets.Focus.set(nameField);
            add(new Button(ui.assets, NC + "BtLeft", BOARD_X + 12 + 20, 60 + 148, () -> cycleJob(-1)));
            add(new Button(ui.assets, NC + "BtRight", BOARD_X + 12 + 166, 60 + 148, () -> cycleJob(1)));
            add(new Widgets.Label(BOARD_X + 12 + 36, 60 + 147, 130, () -> jobName()).align(Align.center));
            add(new Button(ui.assets, NC + "BtYes", BOARD_X + 12 + 27, 60 + 178, this::next));
            add(new Button(ui.assets, NC + "BtNo", BOARD_X + 12 + 101, 60 + 178, this::cancel));
        } else {
            add(new Widgets.Image(ui.assets.sprite(NC + "charSet"), BOARD_X, 0, false));
            for (int row = 0; row < 9; row++) {
                final int r = row;
                float ry = 105 + 18 * row;
                boolean fixed = row >= 4 && row <= 7; // the Empress's Fine Set, not chosen
                add(new Widgets.Image(ui.assets.sprite(NC + "avatarSel/" + row + (fixed ? "/disabled" : "/normal")), BOARD_X + 11, ry, false));
                add(new Widgets.Label(BOARD_X + 11 + 72, ry + 2, 112, () -> optionText(r)).align(Align.center));
                if (!fixed) {
                    add(new Button(ui.assets, NC + "BtLeft", BOARD_X + 11 + 57, ry + 1, () -> cycle(r, -1)));
                    add(new Button(ui.assets, NC + "BtRight", BOARD_X + 11 + 185, ry + 1, () -> cycle(r, 1)));
                }
            }
            add(new Button(ui.assets, NC + "BtYes", BOARD_X + 37, 330, this::create));
            add(new Button(ui.assets, NC + "BtNo", BOARD_X + 111, 330, () -> {
                namePhase = true;
                build();
            }));
        }
    }

    private void cycleJob(int d) {
        int n = jobCount();
        jobIndex = ((jobIndex + d) % n + n) % n;
        previewDirty = true;
    }

    private void cycle(int row, int d) {
        if (row == 8) {
            gender = 1 - gender;
            loadOptions();
            return;
        }
        int n = options[row].length;
        choice[row] = ((choice[row] + d) % n + n) % n;
        previewDirty = true;
    }

    private String optionText(int row) {
        if (lifeItem != 0 && row >= 4 && row <= 7) return "-"; // given by class on creation
        int[] set = lifeItem != 0 ? new int[5] : UltimateExplorer.fineSet(job());
        switch (row) {
            case 0: return styleName("Face", options[0][choice[0]]);
            case 1: return styleName("Hair", options[1][choice[1]]);
            case 2: {
                String[] n = {"Black", "Red", "Orange", "Blonde", "Green", "Blue", "Purple", "Brown"};
                int c = options[2][choice[2]];
                return c >= 0 && c < n.length ? n[c] : Integer.toString(c);
            }
            case 3: {
                String[] n = {"Light", "Tan", "Dark", "Pale", "Ashen", "Green"};
                int c = options[3][choice[3]];
                return c >= 0 && c < n.length ? n[c] : Integer.toString(c);
            }
            case 4: return short_(ItemInfo.get(set[1]).name);
            case 5: return "-";
            case 6: return short_(ItemInfo.get(set[3]).name);
            case 7: return short_(ItemInfo.get(set[4]).name);
            default: return gender == 0 ? "Male" : "Female";
        }
    }

    private static String short_(String s) {
        return s.replace("Empress's Fine ", "Fine ");
    }

    private String styleName(String folder, int id) {
        String n = ui.assets.wz.get("String/Eqp.img/Eqp/" + folder + "/" + id).getString("name", "");
        return n.isEmpty() ? Integer.toString(id) : n;
    }

    private void next() {
        if (nameField != null) name = nameField.text.trim();
        if (!name.matches("[A-Za-z0-9]{4,12}")) {
            error = "Your name must be 4 to 12 letters or numbers.";
            return;
        }
        error = null;
        namePhase = false;
        build();
    }

    private void create() {
        if (lifeItem != 0) {
            close();
            world.mapleLife(lifeSlot, lifeItem, name, options[0][choice[0]], options[1][choice[1]], options[2][choice[2]],
                    options[3][choice[3]], gender, jobIndex);
            return;
        }
        String answer = name + "|" + job() + "|" + options[0][choice[0]] + "|" + options[1][choice[1]] + "|"
                + options[2][choice[2]] + "|" + options[3][choice[3]] + "|" + gender;
        close();
        if (world.talk == talk) world.answer(1, 0, answer);
    }

    private void cancel() {
        close();
        if (talk != null && world.talk == talk) world.answer(0, 0, null);
    }

    private Avatar preview() {
        if (!previewDirty && preview != null) return preview;
        previewDirty = false;
        if (preview != null) preview.dispose();
        preview = null;
        int hair = options[1][choice[1]] + options[2][choice[2]];
        int[] set = lifeItem != 0 ? new int[0] : UltimateExplorer.fineSet(job());
        try {
            preview = new Avatar(ui.assets.wz, options[3][choice[3]], options[0][choice[0]], hair, set);
        } catch (RuntimeException e) {
            preview = null;
        }
        return preview;
    }

    @Override
    public void update(long ms) {
        super.update(ms);
        time += ms;
        if (talk != null && world.talk != talk && ui.windows().contains(this)) close();
    }

    @Override
    public void closed() {
        if (preview != null) preview.dispose();
        preview = null;
    }

    @Override
    protected void drawContent(UiDraw g) {
        g.fill(0, 0, PREVIEW_W, h, 0xD0102030);
        g.outline(0, 0, PREVIEW_W, h, 0xFFB0C8E8);
        g.text(lifeItem != 0 ? "Maple Life" : "Ultimate Explorer", 0, 14, PREVIEW_W, Align.center, false, 14, true, 0xFFFFE08A);
        if (lifeItem == 0) g.text("Successor of " + (world.data() == null ? "" : world.data().stats.name), 0, 34, PREVIEW_W, Align.center, false, 11, false, 0xFFD8E4F4);
        g.text("Lv. " + (lifeItem != 0 ? 30 : UltimateExplorer.START_LEVEL) + "  " + jobName(), 0, 50, PREVIEW_W, Align.center, false, 11, false, 0xFFD8E4F4);
        float cx = PREVIEW_W / 2f, feet = 250;
        Sprite shadow = ui.assets.sprite("UIWindow.img/UtilDlgEx_Avatar/shadow");
        if (shadow != null) g.image(shadow, cx - shadow.w / 2f, feet - 4);
        Avatar a = preview();
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
        String sub = namePhase ? (lifeItem != 0 ? "Choose a name and a class." : "Choose a name and a 2nd job.") : "Choose a look.";
        g.text(sub, 8, 272, PREVIEW_W - 16, Align.center, true, 11, false, 0xFFD8E4F4);
        if (error != null) g.text(error, 8, 300, PREVIEW_W - 16, Align.center, true, 11, true, 0xFFFF7070);
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            cancel();
            return true;
        }
        return super.onKey(keycode);
    }
}
