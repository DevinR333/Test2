package maple;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.chr.Player;
import maple.game.GameEvents;
import maple.game.ItemInfo;
import maple.game.Mob;
import maple.game.Names;
import maple.game.Npc;
import maple.game.NpcTalk;
import maple.game.Shop;
import maple.game.SkillInfo;
import maple.game.Storage;
import maple.game.World;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.input.Pad;
import maple.input.TouchControls;
import maple.map.Field;
import maple.map.Portal;
import maple.net.GameClient;
import maple.net.PacketWriter;
import maple.net.model.Item;
import maple.screens.LoginScreen;
import maple.ui.Dialogs;
import maple.ui.KeyMap;
import maple.ui.Ui;
import maple.ui.UiAssets;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Widget;
import maple.ui.Widgets;
import maple.ui.Window;
import maple.ui.hud.ChatBar;
import maple.ui.hud.QuickSlots;
import maple.ui.hud.StatusBar;
import maple.ui.hud.StatusMessages;
import maple.ui.hud.WorldLabels;
import maple.ui.windows.EquipWindow;
import maple.ui.windows.ItemWindow;
import maple.ui.windows.KeyConfigWindow;
import maple.ui.windows.MenuWindow;
import maple.ui.windows.MiniMapWindow;
import maple.ui.windows.NpcDialog;
import maple.ui.windows.QuestWindow;
import maple.ui.windows.ReviveNotice;
import maple.ui.windows.SettingsWindow;
import maple.ui.windows.ShopWindow;
import maple.ui.windows.SkillWindow;
import maple.ui.windows.StatWindow;
import maple.ui.windows.StyleDialog;
import maple.ui.windows.UserListWindow;
import maple.ui.windows.MessengerWindow;
import maple.ui.windows.TextPrompt;
import maple.ui.windows.MonsterBookWindow;
import maple.ui.windows.WorldMapWindow;
import maple.ui.windows.StorageWindow;
import maple.wz.Wz;
import net.opcodes.RecvOpcode;
import offline.OfflineServer;
import scripting.AbstractScriptManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The client: the Nexon/Wizet logos while the in-app server starts, the login scenes, then the game
 * (map, character, monsters, HUD and windows) at the v83 125 Hz step. Everything is laid out on an
 * 800x600 (or wider) screen scaled uniformly to the device, with touch controls in the side bars.
 */
public class MapleGame extends ApplicationAdapter {
    public enum Screen { BOOT, LOGIN, LOADING, GAME, CASH_SHOP }
    private static final int TICK_MS = 8;

    private final Wz.Source source;
    private final File saveDir;
    private final AbstractScriptManager.ScriptLoader scripts;
    private Wz wz;
    private Preferences prefs;
    private SpriteBatch batch;
    private UiAssets assets;
    private UiDraw g;
    private Ui ui;
    private final OrthographicCamera cam = new OrthographicCamera();
    private final Matrix4 worldMatrix = new Matrix4();
    private final Bgm bgm = new Bgm();
    public final TouchControls touch = new TouchControls();

    private Screen screen = Screen.BOOT;
    private String fatal;
    private GameClient client;
    private LoginScreen login;
    private World world;
    private Player player;
    private Field field;
    private int[] pendingWarp;
    private boolean loadingShown;

    // HUD
    private StatusBar statusBar;
    private ChatBar chatBar;
    private QuickSlots quickSlots;
    private StatusMessages statusMessages;

    // game state
    private double camX, camY;
    private float accumulator;
    private long timeMs;
    private final Pad pad = new Pad();
    private final boolean[] keySlots = new boolean[90], prevSlots = new boolean[90];
    private boolean prevUp, prevDown, prevJump;
    private final boolean[] gdxKeys = new boolean[256];
    private long lastMoveSent;
    private double lastSentX, lastSentY;
    private int lastSentStance = -1;
    private int portalCooldown;
    private long frameMs;
    private long bootTime;
    private Animation logoNexon, logoWizet;
    private boolean logoSoundNx, logoSoundWz;
    /** A tap or key during the logos skips them. */
    private boolean skipLogos;

    /** Test hooks. */
    public Runnable afterFrame;
    public Pad scriptedPad;

    public MapleGame(Wz.Source source, File saveDir, AbstractScriptManager.ScriptLoader scripts) {
        this.source = source;
        this.saveDir = saveDir;
        this.scripts = scripts;
    }

    public GameClient client() { return client; }
    public World world() { return world; }
    public Player player() { return player; }
    public Ui ui() { return ui; }
    public LoginScreen login() { return login; }
    public Screen screen() { return screen; }
    public String fatalError() { return fatal; }

    /** Where a map point is on the 800x600 (or wider) interface (testing aid). */
    public float[] worldToUi(double wx, double wy) {
        return new float[]{(float) (wx - Math.round(camX) + Ui.W / 2.0), (float) (wy - Math.round(camY) + 300)};
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void create() {
        Log.captureConsole();
        prefs = Gdx.app.getPreferences("maple-offline");
        batch = new SpriteBatch(4000);
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        Gdx.input.setInputProcessor(new InputHandler());
        offline.OfflineItems.install(); // before anything reads the data (it adds whole new images too)
        wz = new Wz(source);
        Log.info("WZ source: " + source.describe() + ", build " + Log.build());
        try {
            wz.file("Map");
            wz.file("UI");
        } catch (RuntimeException e) {
            fatal = "Could not read the game files (Map.wz / UI.wz)\n\n" + Log.brief(e) + "\n\nWZ folder: " + source.describe();
            Log.error("open wz", e);
            return;
        }
        maple.map.Physics.load(wz.get("Map/Physics.img"));
        ItemInfo.init(wz);
        SkillInfo.init(wz);
        Names.init(wz);
        UiSounds.init(wz);
        assets = new UiAssets(wz);
        g = new UiDraw(batch, assets);
        ui = new Ui(assets, g);
        ui.setPrefs(prefs);
        try {
            ui.aspect = Ui.Aspect.valueOf(prefs.getString("aspect", Ui.Aspect.ORIGINAL_4_3.name()));
        } catch (IllegalArgumentException e) {
            ui.aspect = Ui.Aspect.ORIGINAL_4_3;
        }
        applyOptions();
        touch.load(prefs);
        touch.mouseListener = this::touchMouse;
        ui.touchDevice = Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android;
        logoNexon = assets.animation("Logo.img/Nexon");
        logoWizet = assets.animation("Logo.img/Wizet");
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        SaveTransfer.applyPendingImport(saveDir);
        OfflineServer.start(wz, saveDir, scripts);
        bootTime = System.currentTimeMillis();
        Log.info("Logos: Nexon " + (logoNexon == null ? 0 : logoNexon.durationMs()) + " ms, Wizet " + (logoWizet == null ? 0 : logoWizet.durationMs()) + " ms");
    }

    private boolean option(String key) {
        boolean def = key.equals("music") || key.equals("sound") || key.equals("freeCashShop") || key.equals("permanentCash") || key.equals("limitedCash") || key.equals("holidays") || key.equals("petLoot") || key.equals("empressBlessing") || key.equals("touch") && Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android;
        return prefs.getBoolean("opt." + key, def);
    }

    /** EXP / meso / drop rate (defaults: 3x EXP, 5x meso, 1x drops). */
    private int rate(String key) {
        int def = key.equals("exp") ? 3 : key.equals("meso") ? 5 : 1;
        return prefs == null ? def : Math.max(1, prefs.getInteger("rate." + key, def));
    }

    private void applyOptions() {
        LoginScreen.allStyles = option("allStyles");
        offline.OfflineOptions.allStyles = option("allStyles");
        offline.OfflineOptions.cashDrops = option("cashDrops");
        offline.OfflineOptions.freeCashShop = option("freeCashShop");
        offline.OfflineOptions.permanentCash = option("permanentCash");
        offline.OfflineOptions.limitedCash = option("limitedCash");
        offline.OfflineOptions.holidays = option("holidays");
        offline.OfflineOptions.petLoot = option("petLoot");
        offline.OfflineOptions.empressBlessing = option("empressBlessing");
        offline.OfflineOptions.expRate = rate("exp");
        offline.OfflineOptions.mesoRate = rate("meso");
        offline.OfflineOptions.dropRate = rate("drop");
        OfflineServer.applyRates();
        bgm.volume = option("music") ? 0.6f : 0f;
        bgm.applyVolume();
        UiSounds.volume = option("sound") ? 0.7f : 0f;
        touch.enabled = option("touch");
    }

    @Override
    public void resize(int width, int height) {
        if (ui == null) return;
        ui.layout(width, height);
        float s = ui.scale;
        cam.setToOrtho(true, width / s, height / s);
        cam.position.set(width / s / 2f - ui.offsetX / s, height / s / 2f - ui.offsetY / s, 0);
        cam.update();
        touch.layout(-ui.offsetX / s, -ui.offsetY / s, width / s, height / s);
        if (screen == Screen.CASH_SHOP) {
            // the Cash Shop replaces the HUD: keep it (Android resizes on every return to the app)
            if (cashScreen != null) cashScreen.x = (Ui.W - cashScreen.w) / 2f;
        } else if (statusBar != null) {
            buildHud();
        }
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void render() {
        long ms = (long) Math.min(250, Gdx.graphics.getDeltaTime() * 1000);
        frameMs = ms;
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (fatal != null || ui == null) {
            drawMessage(fatal == null ? "Starting..." : fatal + "\n\nBuild " + Log.build());
            after();
            return;
        }
        try {
            if (client != null) client.update();
            switch (screen) {
                case BOOT: boot(ms); break;
                case LOGIN: loginFrame(ms); break;
                case LOADING: loadingFrame(); break;
                case GAME: gameFrame(ms); break;
                case CASH_SHOP: cashShopFrame(ms); break;
                default: break;
            }
        } catch (RuntimeException e) {
            Log.error("frame", e);
            fatal = "[" + Log.build() + "] Something went wrong:\n\n" + Log.details(e);
        }
        after();
    }

    private void after() {
        if (afterFrame != null) afterFrame.run();
    }

    // ---- boot: logos while the server starts and logs us in

    private void boot(long ms) {
        if (OfflineServer.failure() != null) {
            fatal = "[" + Log.build() + "] The game server could not start:\n\n" + Log.details(OfflineServer.failure());
            return;
        }
        if (client == null && OfflineServer.isOnline()) {
            client = new GameClient();
            client.warpHandler = this::onWarp;
            client.start();
        }
        if (client != null && client.state == GameClient.State.FAILED) {
            fatal = client.error;
            return;
        }
        long t = System.currentTimeMillis() - bootTime;
        long nexonLen = logoNexon == null || logoNexon.isEmpty() ? 0 : logoNexon.durationMs();
        long wizetLen = logoWizet == null || logoWizet.isEmpty() ? 0 : logoWizet.durationMs();
        if (skipLogos) t = nexonLen + wizetLen;
        boolean logosDone = t >= nexonLen + wizetLen;
        if (client != null && client.state == GameClient.State.CHARACTER_SELECT && logosDone) {
            openLogin();
            return;
        }
        begin();
        if (t < nexonLen) {
            if (!logoSoundNx) {
                logoSoundNx = true;
                UiSounds.playPath("BgmUI.img/NxLogo");
            }
            g.anim(logoNexon, Ui.W / 2f, 300, t);
        } else if (t < nexonLen + wizetLen) {
            if (!logoSoundWz) {
                logoSoundWz = true;
                UiSounds.playPath("BgmUI.img/WzLogo");
            }
            g.anim(logoWizet, Ui.W / 2f, 300, t - nexonLen);
        } else {
            String msg = client == null ? "Starting the game server..." : "Logging in...";
            if (client != null && System.currentTimeMillis() - client.lastProgress > 20000) {
                StringBuilder sb = new StringBuilder("No answer from the game server for 20 seconds. Its last messages:\n");
                for (String l : Log.recent(12)) sb.append(l.length() > 140 ? l.substring(0, 140) : l).append('\n');
                msg = sb.toString();
            }
            g.text(msg, 20, 20, Ui.W - 40, Align.left, true, 12, false, 0xFFFFFFFF);
        }
        end();
    }

    private void openLogin() {
        closeAllWindows();
        ui.hud.clear();
        if (login != null) login.dispose();
        login = new LoginScreen(wz, ui, client);
        screen = Screen.LOGIN;
        String music = login.scene.bgm;
        bgm.play(wz, music == null || music.isEmpty() ? "BgmUI/Title" : music);
    }

    // ---- login scenes

    private void loginFrame(long ms) {
        if (client.state == GameClient.State.FAILED) {
            fatal = client.error;
            return;
        }
        if (login == null) return;
        login.update(ms);
        ui.update(ms);
        begin();
        enableScissor();
        login.drawScene(batch, Ui.W, Ui.H);
        worldMatrix.set(cam.combined).translate((float) (-login.viewX()), (float) (-login.viewY()), 0);
        batch.setProjectionMatrix(worldMatrix);
        login.drawLayers(batch);
        batch.setProjectionMatrix(cam.combined);
        login.drawFronts(batch, Ui.W, Ui.H);
        disableScissor();
        ui.draw();
        end();
    }

    // ---- entering the game and changing maps

    /** SET_FIELD from the server (login or warp). */
    private void onWarp(int[] w) {
        pendingWarp = w;
        loadingShown = false;
        // The world must exist before the next packet: the server sends key bindings, buffs, NPCs...
        // right after SET_FIELD, in the same read.
        if (world == null) enterGame();
        else world.beginField();
        if (screen == Screen.GAME) screen = Screen.LOADING;
    }

    private void enterGame() {
        closeAllWindows();
        ui.hud.clear();
        if (login != null) {
            login.dispose();
            login = null;
        }
        player = new Player();
        player.name = client.player.stats.name;
        world = new World(wz, client, player);
        world.events = new Events();
        world.lookChanged = this::rebuildAvatar;
        world.bgmChange = path -> bgm.play(wz, path);
        client.inGameHandler = world::handle;
        client.cashShopHandler = this::openCashShop;
        rebuildAvatar();
        world.recomputeStats();
        world.beginField();
        buildHud();
        screen = Screen.LOADING;
        loadingShown = false;
    }

    // ---- the Cash Shop

    private maple.ui.CashShopScreen cashScreen;
    private boolean backFromCashShop;

    private void openCashShop() {
        closeAllWindows();
        ui.hud.clear();
        ui.worldClick = null;
        cashScreen = new maple.ui.CashShopScreen(ui, world, this::leaveCashShop);
        ui.hud.add(cashScreen);
        touch.releaseAll();
        screen = Screen.CASH_SHOP;
        bgm.play(wz, "BgmUI/ShopBgm");
    }

    private void leaveCashShop() {
        if (screen != Screen.CASH_SHOP) return;
        client.leaveCashShop();
        closeAllWindows();
        ui.hud.clear();
        if (cashScreen != null) cashScreen.dispose();
        cashScreen = null;
        backFromCashShop = true;
        screen = Screen.LOADING;
        loadingShown = false;
    }

    private void cashShopFrame(long ms) {
        ui.update(ms);
        begin();
        ui.draw();
        end();
    }

    private void rebuildAvatar() {
        if (client == null || client.player == null || player == null) return;
        java.util.TreeMap<Integer, Item> eq = client.player.inventory(-1);
        List<Integer> ids = new ArrayList<>();
        for (Item it : eq.values()) {
            int pos = it.position;
            if (pos <= -100) {
                ids.add(it.itemId); // cash cover
            } else if (!eq.containsKey(pos - 100) || pos == -11) { // a weapon cover needs the weapon under it
                ids.add(it.itemId);
            }
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        Avatar old = player.avatar();
        try {
            player.setAvatar(new Avatar(wz, client.player.stats.skin, client.player.stats.face, client.player.stats.hair, arr));
            if (old != null) old.dispose();
        } catch (RuntimeException e) {
            Log.error("avatar", e);
        }
        if (world != null) world.recomputeStats();
    }

    private void loadingFrame() {
        if (!loadingShown) {
            loadingShown = true;
            begin();
            end();
            return;
        }
        int[] w = pendingWarp;
        pendingWarp = null;
        if (w == null) {
            screen = field == null ? Screen.LOADING : Screen.GAME;
            return;
        }
        Field next;
        try {
            long t0 = System.currentTimeMillis();
            next = new Field(wz, w[0]);
            Log.info("Loaded map " + w[0] + " (" + next.mapName + ") in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (RuntimeException e) {
            Log.error("load map " + w[0], e);
            if (field == null) fatal = "Could not load map " + w[0] + "\n\n" + Log.brief(e);
            else screen = Screen.GAME;
            return;
        }
        if (field != null) field.dispose();
        field = next;
        world.enterField(field);
        world.field = field;
        Portal sp = field.portalById(w[1]);
        if (sp == null) sp = field.spawnPortal(null);
        double sx = sp != null ? sp.x : (field.left + field.right) / 2.0;
        double sy = sp != null ? sp.y - 10 : field.top;
        if (w[2] != Integer.MIN_VALUE) {
            sx = w[2];
            sy = w[3] - 10;
        }
        player.spawn(sx, sy);
        camX = sx;
        camY = sy - 50;
        clampCamera();
        bgm.play(wz, field.bgm);
        portalCooldown = 60;
        client.mapLoaded();
        if (backFromCashShop) {
            backFromCashShop = false;
            rebuildAvatar();
            buildHud();
        }
        if (ui.find(MiniMapWindow.class) == null && prefs.getBoolean("minimap", true)) ui.open(miniMap());
        screen = Screen.GAME;
    }

    // ---- HUD

    private void buildHud() {
        ui.hud.clear();
        if (world == null) return;
        boolean quick = statusBar == null || statusBar.quickSlotsShown;
        statusMessages = new StatusMessages();
        ui.hud.add(statusMessages);
        statusBar = new StatusBar(ui, world, this::openWindow);
        statusBar.quickSlotsShown = quick;
        quickSlots = new QuickSlots(ui, world, this::pressSlot);
        quickSlots.visible = quick;
        ChatBar old = chatBar;
        chatBar = new ChatBar(ui, this::sendChat);
        if (old != null) chatBar.copyLog(old);
        // wider screens: the 800-wide bottom HUD sits in the middle (the bar extends to both edges)
        float side = (Ui.W - 800) / 2f;
        statusBar.x = side;
        quickSlots.x += side;
        chatBar.x += side;
        ui.hud.add(statusBar);
        ui.hud.add(quickSlots);
        ui.hud.add(chatBar);
        ui.worldClick = new Ui.WorldClick() {
            @Override
            public boolean click(float x, float y, boolean doubleClick) {
                if (world == null || field == null) return false;
                Npc n = world.npcAt((float) (x + camX - Ui.W / 2.0), (float) (y + camY - 300));
                if (n != null && (doubleClick || !ui.mouseVisible || ui.touchDevice)) {
                    world.talkTo(n);
                    return true;
                }
                return false;
            }

            @Override
            public boolean hover(float x, float y) {
                return world != null && world.npcAt((float) (x + camX - Ui.W / 2.0), (float) (y + camY - 300)) != null;
            }
        };
    }

    /** Chat: "/w name text" whispers, otherwise the chat bar's target. */
    private void sendChat(String text) {
        if (world == null) return;
        if (text.startsWith("/w ") || text.startsWith("/W ")) {
            String rest = text.substring(3).trim();
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                chatBar.whisperTo = rest.substring(0, sp);
                chatBar.target = 1;
                world.chatTo(1, chatBar.whisperTo, rest.substring(sp + 1));
            }
            return;
        }
        world.chatTo(chatBar.target, chatBar.whisperTo, text);
    }

    /** HUD buttons, the Menu/Shortcut popups and type-4 key bindings open windows by name. */
    private void openWindow(String name) {
        if (world == null) return;
        switch (name) {
            case "Equip": toggle(EquipWindow.class, () -> new EquipWindow(ui, world)); break;
            case "Item": toggle(ItemWindow.class, () -> new ItemWindow(ui, world)); break;
            case "Stat": toggle(StatWindow.class, () -> new StatWindow(ui, world)); break;
            case "Skill": toggle(SkillWindow.class, () -> new SkillWindow(ui, world)); break;
            case "Quest": toggle(QuestWindow.class, () -> new QuestWindow(ui, world)); break;
            case "KeyConfig": toggle(KeyConfigWindow.class, () -> new KeyConfigWindow(ui, world)); break;
            case "MiniMap": {
                MiniMapWindow m = ui.find(MiniMapWindow.class);
                if (m == null) {
                    ui.open(miniMap());
                    prefs.putBoolean("minimap", true).flush();
                } else m.cycle();
                break;
            }
            case "GameMenu": popup("GameMenu", MenuWindow.GAME_MENU, 666, 423); break;
            case "ShortCut": popup("ShortCut", MenuWindow.SHORTCUT, 707, 296); break;
            case "QuickSlot":
                statusBar.quickSlotsShown = !statusBar.quickSlotsShown;
                quickSlots.visible = statusBar.quickSlotsShown;
                break;
            case "GameOpt":
            case "SysOpt":
                toggle(SettingsWindow.class, () -> new SettingsWindow(ui, new Options()));
                break;
            case "Quit":
                ui.open(new Dialogs.Notice(ui, "Are you sure you want to quit the game?", true, this::logOut, null));
                break;
            case "Friends": userList(0); break;
            case "Party": userList(1); break;
            case "Guild": userList(2); break;
            case "Messenger": toggle(MessengerWindow.class, () -> new MessengerWindow(ui, world)); break;
            case "MonsterBook": toggle(MonsterBookWindow.class, () -> new MonsterBookWindow(ui, world)); break;
            case "WorldMap": toggle(WorldMapWindow.class, () -> new WorldMapWindow(ui, world)); break;
            case "CashShop":
                if (!client.inCashShop) client.enterCashShop();
                break;
            case "Channel":
                ui.open(new Dialogs.Notice(ui, "This world has a single channel.", false, null, null));
                break;
            case "Chat":
                chatBar.beginTyping();
                break;
            case "ChatLog":
                chatBar.toggleExpanded();
                break;
            default:
                statusMessages.add("That is not available in the offline game.", 0xFFFFFFFF);
                break;
        }
    }

    private void userList(int tab) {
        UserListWindow w = ui.find(UserListWindow.class);
        if (w != null) {
            ui.close(w);
            return;
        }
        ui.open(new UserListWindow(ui, world, tab, (t, name) -> chatBar.setTarget(t, name)));
    }

    private MiniMapWindow miniMap() {
        MiniMapWindow m = new MiniMapWindow(ui, world);
        m.onWorldMap = () -> openWindow("WorldMap");
        return m;
    }

    private <T extends Window> void toggle(Class<T> type, java.util.function.Supplier<T> make) {
        T w = ui.find(type);
        if (w != null) ui.close(w);
        else {
            ui.open(make.get());
            UiSounds.play("MenuUp");
        }
    }

    private void popup(String name, String[][] entries, float x, float y) {
        Window w = ui.find(name);
        if (w != null) {
            ui.close(w);
            return;
        }
        ui.open(new MenuWindow(ui, name, entries, x + (Ui.W - 800) / 2f, y, this::openWindow));
    }

    private void closeAllWindows() {
        for (Window w : new ArrayList<>(ui.windows())) ui.close(w);
        ui.carry = null;
        Widgets.Focus.set(null);
    }

    private void logOut() {
        if (client == null) return;
        new Thread(OfflineServer::saveNow, "save").start();
        closeAllWindows();
        ui.hud.clear();
        ui.worldClick = null;
        statusBar = null;
        chatBar = null;
        if (field != null) field.dispose();
        field = null;
        if (world != null) world.dispose();
        world = null;
        player = null;
        pendingWarp = null;
        client.inGameHandler = null;
        client.logout();
        screen = Screen.BOOT;
        skipLogos = true; // no logos again
    }

    // ---- the game

    private long autosaveAt;
    private final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();

    /** Every 30 seconds in game: save in the background, then a yellow "Autosaved" line (lower right, like meso gains). */
    private void autosave() {
        long now = System.currentTimeMillis();
        if (autosaveAt == 0) autosaveAt = now;
        if (now - autosaveAt < 30000 || !OfflineServer.isOnline() || !saving.compareAndSet(false, true)) return;
        autosaveAt = now;
        new Thread(() -> {
            try {
                OfflineServer.saveNow();
                Gdx.app.postRunnable(() -> {
                    if (statusMessages != null && screen == Screen.GAME) statusMessages.add("Autosaved", 0xFFFFFF00);
                });
            } finally {
                saving.set(false);
            }
        }, "autosave").start();
    }

    private void gameFrame(long ms) {
        if (client.state == GameClient.State.FAILED) {
            fatal = client.error;
            return;
        }
        autosave();
        if (field == null) return;
        readKeys();
        accumulator += ms;
        boolean first = true;
        while (accumulator >= TICK_MS) {
            accumulator -= TICK_MS;
            timeMs += TICK_MS;
            tick();
            if (first) {
                pad.consumeEdges();
                first = false;
            }
            if (screen != Screen.GAME) break;
        }
        ui.update(ms);
        float alpha = accumulator / TICK_MS;
        drawGame(alpha);
    }

    private boolean inputBlocked() {
        if (world == null || world.talk != null || ui.modal() != null) return true;
        Widgets.Focusable f = Widgets.Focus.get();
        if (f instanceof Widget && !ui.attached((Widget) f)) Widgets.Focus.set(null); // left over from a closed window
        else if (f != null) return true; // typing
        return touch.editing || ui.find(KeyConfigWindow.class) != null && ui.carry != null;
    }

    /** Merges the keyboard and the touch controls into key slots, then acts on new presses. */
    private void readKeys() {
        java.util.Arrays.fill(keySlots, false);
        boolean blocked = inputBlocked();
        boolean left = false, right = false, up = false, down = false;
        if (!blocked) {
            for (int k = 0; k < 256; k++) {
                if (!gdxKeys[k]) continue;
                int slot = KeyMap.slot(k);
                if (slot >= 0 && slot < 90) keySlots[slot] = true;
            }
            for (int s = 0; s < 90; s++) if (touch.held(s)) keySlots[s] = true;
            left = gdxKeys[Input.Keys.LEFT] || touch.left();
            right = gdxKeys[Input.Keys.RIGHT] || touch.right();
            up = gdxKeys[Input.Keys.UP] || touch.up();
            down = gdxKeys[Input.Keys.DOWN] || touch.down();
        }
        if (scriptedPad != null) {
            left = scriptedPad.left;
            right = scriptedPad.right;
            up = scriptedPad.up;
            down = scriptedPad.down;
        }
        boolean jump = false;
        for (int s = 0; s < 90; s++) {
            int type = world.keyTypes[s], action = world.keyActions[s];
            if (keySlots[s] && type == KeyMap.ACTION && action == KeyMap.JUMP) jump = true;
        }
        if (scriptedPad != null) jump = scriptedPad.jump;
        if (player.sitting() && (left || right || jump)) {
            world.standUp();
            jump = false; // the press that gets you up does not also jump
        }
        pad.left = left;
        pad.right = right;
        pad.up = up;
        pad.down = down;
        pad.jump = jump;
        pad.upPressed |= up && !prevUp;
        pad.downPressed |= down && !prevDown;
        pad.jumpPressed |= jump && !prevJump;
        prevUp = up;
        prevDown = down;
        prevJump = jump;
        for (int s = 0; s < 90; s++) {
            boolean now = keySlots[s];
            if (now && !prevSlots[s]) pressSlot(s);
            else if (now) holdSlot(s);
            prevSlots[s] = now;
        }
    }

    /** A key slot was pressed (keyboard, touch button or quick slot tap). */
    private void pressSlot(int slot) {
        if (world == null || slot < 0 || slot >= 90) return;
        int type = world.keyTypes[slot], action = world.keyActions[slot];
        switch (type) {
            case KeyMap.SKILL:
                world.useSkill(action);
                break;
            case KeyMap.ITEM:
                world.useItemId(action);
                break;
            case KeyMap.CASH_ITEM:
                if (action / 10000 == 516) world.faceExpression(action - 5159992); // emotion items
                else world.useItemId(action);
                break;
            case KeyMap.MENU:
                menuAction(action);
                break;
            case KeyMap.ACTION:
                if (action == KeyMap.ATTACK) world.attack();
                else if (action == KeyMap.PICKUP) world.pickup();
                else if (action == KeyMap.TALK) talkToNearest();
                else if (action == KeyMap.SIT) {
                    if (player.sitting()) world.standUp();
                    else world.sitOnSeat();
                }
                break;
            case KeyMap.FACE:
                world.faceExpression(action - 99);
                break;
            case KeyMap.MACRO:
                world.runMacro(action);
                break;
            default:
                break;
        }
    }

    /** Keys held down repeat attacks, attack skills and pickup. */
    private void holdSlot(int slot) {
        int type = world.keyTypes[slot], action = world.keyActions[slot];
        if (type == KeyMap.ACTION && action == KeyMap.ATTACK) world.attack();
        else if (type == KeyMap.ACTION && action == KeyMap.PICKUP) world.pickup();
        else if (type == KeyMap.SKILL) {
            SkillInfo s = SkillInfo.get(action);
            if (s != null && s.attack()) world.useSkill(action);
        }
    }

    /** Type-4 key actions (00a0773d). */
    private void menuAction(int id) {
        String[] names = {"Equip", "Item", "Stat", "Skill", "Friends", "WorldMap", "Messenger", "MiniMap", "Quest", "KeyConfig",
                "Chat", "Chat", "Chat", "Chat", "ShortCut", "QuickSlot", "ChatLog", "Guild", "Chat", "Party", "QuestAlarm", "Chat",
                "MonsterBook", "CashShop", "Chat", "PartySearch", "Family", "Medal"};
        // chat-target keys: 10 all, 11 whisper, 12 party, 13 buddy, 18 guild, 24 alliance
        int chat = id == 10 ? 0 : id == 11 ? 1 : id == 12 ? 2 : id == 13 ? 3 : id == 18 ? 4 : id == 24 ? 5 : -1;
        if (chat >= 0) {
            chatBar.setTarget(chat, null);
            return;
        }
        if (id >= 0 && id < names.length) openWindow(names[id]);
    }

    private void talkToNearest() {
        Npc best = null;
        double bestD = Double.MAX_VALUE;
        for (Npc n : world.npcs.values()) {
            if (!n.visible) continue;
            double dx = Math.abs(n.x - player.phys.x), dy = Math.abs(n.y - player.phys.y);
            if (dx > 150 || dy > 100) continue;
            if (dx < bestD) {
                bestD = dx;
                best = n;
            }
        }
        if (best != null) world.talkTo(best);
    }

    private void tick() {
        player.update(field, pad);
        world.update();
        field.update(timeMs);
        if (portalCooldown > 0) portalCooldown--;
        Portal p = field.portalAt(player.phys.x, player.phys.y);
        if (p != null && !player.dead) {
            boolean use = (pad.upPressed && !p.isTouch()) || (p.isTouch() && portalCooldown == 0);
            if (use) {
                portalCooldown = 60;
                if (!p.script.isEmpty()) {
                    sendMovement(true);
                    client.send(new PacketWriter(RecvOpcode.CHANGE_MAP_SPECIAL.getValue()).writeByte(0).writeString(p.name).writeShort(0));
                } else if (p.hasTarget() && p.targetMap == field.id) {
                    Portal dest = field.spawnPortal(p.targetName);
                    if (dest != null) player.spawn(dest.x, dest.y - 10);
                    UiSounds.game("Portal");
                } else if (p.hasTarget()) {
                    sendMovement(true);
                    UiSounds.game("Portal");
                    client.send(new PacketWriter(RecvOpcode.CHANGE_MAP.getValue())
                            .writeByte(0).writeInt(-1).writeString(p.name).writeByte(0).writeByte(0).writeByte(0));
                }
            }
        }
        if (player.phys.y >= field.footholds.borderBottom - 1) {
            Portal sp = field.spawnPortal(null);
            if (sp != null) player.spawn(sp.x, sp.y - 10);
        }
        sendMovement(false);
        updateCamera();
    }

    /** Tells the server where we are (the client's movement packets), at most every 200 ms. */
    private void sendMovement(boolean force) {
        int stance = player.stanceByte();
        boolean moved = Math.abs(player.phys.x - lastSentX) >= 1 || Math.abs(player.phys.y - lastSentY) >= 1 || stance != lastSentStance;
        if (!moved || (!force && timeMs - lastMoveSent < 200)) return;
        int duration = (int) Math.min(1000, Math.max(8, timeMs - lastMoveSent));
        lastMoveSent = timeMs;
        lastSentX = player.phys.x;
        lastSentY = player.phys.y;
        lastSentStance = stance;
        PacketWriter w = new PacketWriter(RecvOpcode.MOVE_PLAYER.getValue());
        w.writeBytes(new byte[9]);
        w.writeByte(1);
        w.writeByte(0);
        w.writeShort((int) Math.round(player.phys.x));
        w.writeShort((int) Math.round(player.phys.y));
        w.writeShort((int) Math.round(player.phys.hspeed * 125));
        w.writeShort((int) Math.round(player.phys.vspeed * 125));
        w.writeShort(player.phys.fhid);
        w.writeByte(stance);
        w.writeShort(duration);
        client.send(w);
    }

    private void updateCamera() {
        double tx = player.phys.x, ty = player.phys.y - 50;
        double hd = tx - camX, vd = ty - camY;
        if (Math.abs(hd) >= 5) camX += hd * (12.0 / Ui.W) * 1.5;
        if (Math.abs(vd) >= 5) camY += vd * (12.0 / 600) * 1.5;
        clampCamera();
    }

    private void clampCamera() {
        double minX = field.left + Ui.W / 2.0, maxX = field.right - Ui.W / 2.0;
        if (minX > maxX) camX = (field.left + field.right) / 2.0;
        else camX = Math.max(minX, Math.min(maxX, camX));
        double minY = field.top + 300, maxY = field.bottom - 300;
        if (minY > maxY) camY = (field.top + field.bottom) / 2.0;
        else camY = Math.max(minY, Math.min(maxY, camY));
    }

    private void drawGame(float alpha) {
        double cx = Math.round(camX), cy = Math.round(camY);
        double viewX = Ui.W / 2.0 - cx, viewY = 300 - cy;
        begin();
        enableScissor();
        field.drawBackgrounds(batch, viewX, viewY, Ui.W, Ui.H, timeMs);
        worldMatrix.set(cam.combined).translate((float) viewX, (float) viewY, 0);
        batch.setProjectionMatrix(worldMatrix);
        int playerLayer = Math.max(0, Math.min(7, player.layer()));
        for (int layer = 0; layer < 8; layer++) {
            field.drawLayer(batch, layer, alpha, timeMs);
            world.drawLayer(batch, layer, alpha);
            if (layer == playerLayer) player.draw(batch, alpha);
        }
        field.drawPortals(batch, timeMs, player.phys.x, player.phys.y);
        world.drawTop(batch, alpha);
        batch.setProjectionMatrix(cam.combined);
        field.drawForegrounds(batch, viewX, viewY, Ui.W, Ui.H, timeMs);
        drawLabels(alpha, viewX, viewY);
        world.drawScreenEffect(batch, Ui.W / 2f, 300);
        disableScissor();
        ui.draw();
        touch.draw(g, new SlotIcons());
        end();
    }

    private final Animation[] questIcons = new Animation[3];

    /** Name tags, NPC names, monster HP bars, quest markers and the chat balloon. */
    private void drawLabels(float alpha, double viewX, double viewY) {
        for (Npc n : world.npcs.values()) {
            if (!n.visible || n.hideName) continue;
            float x = (float) (n.x + viewX), y = (float) (n.y + viewY);
            WorldLabels.nameTag(g, n.name, x, y + 2, 0xFFFFFF00, true);
            if (n.func != null && !n.func.isEmpty()) WorldLabels.nameTag(g, n.func, x, y + 24, 0xFFFFFF00, true);
        }
        // pet name tags
        for (maple.game.Pet p : world.pets) {
            if (p == null) continue;
            WorldLabels.nameTag(g, p.name, (float) (Math.round(p.phys.drawX(alpha)) + viewX), (float) (Math.round(p.phys.drawY(alpha)) + viewY + 2), 0xFFFFFFFF, false);
        }
        // quest markers over NPC heads: 0 available, 1 in progress, 2 ready to complete
        for (Npc n : world.npcs.values()) {
            if (!n.visible) continue;
            int m = world.quests.marker(n.id);
            if (m < 0) continue;
            Animation a = questIcons[m];
            if (a == null) a = questIcons[m] = assets.animation("UIWindow.img/QuestIcon/" + m);
            if (a != null) g.anim(a, (float) (n.x + viewX), (float) (n.y + viewY - n.height() - 8), timeMs);
        }
        for (Mob m : world.mobs.values()) {
            if (m.hpVisible(world.timeMs)) WorldLabels.hpBar(g, (float) (m.headX() + viewX), (float) (m.headY() + viewY), m.hpPercent);
        }
        float px = (float) (Math.round(player.phys.drawX(alpha)) + viewX), py = (float) (Math.round(player.phys.drawY(alpha)) + viewY);
        WorldLabels.nameTag(g, player.name, px, py + 2, 0xFFFFFFFF, false);
        if (world.chatBalloon != null && world.timeMs < world.chatBalloonUntil) {
            WorldLabels.balloon(g, 0, world.chatBalloon, px, py - 85);
        }
    }

    /**
     * A touch button set to a mouse action: it clicks, right-clicks or scrolls at the last tapped
     * point (the middle of the screen before any tap), and from then on the cursor is shown there.
     */
    private void touchMouse(int slot) {
        if (ui == null) return;
        if (ui.mouseX < 0) {
            ui.mouseX = Ui.W / 2f;
            ui.mouseY = Ui.H / 2f;
        }
        ui.touchCursor = true;
        float sx = ui.offsetX + ui.mouseX * ui.scale, sy = ui.offsetY + ui.mouseY * ui.scale;
        final int pointer = 19; // past any finger, so it never collides with a real touch
        switch (slot) {
            case TouchControls.LEFT_CLICK:
            case TouchControls.RIGHT_CLICK:
                ui.pointerDown(pointer, sx, sy, slot == TouchControls.RIGHT_CLICK);
                ui.pointerUp(pointer, sx, sy);
                break;
            case TouchControls.WHEEL_UP:
                ui.scrolled(sx, sy, -1);
                break;
            case TouchControls.WHEEL_DOWN:
                ui.scrolled(sx, sy, 1);
                break;
            default:
                break; // middle click: v83 gives it no action
        }
    }

    /** Icons for the touch buttons: whatever their key is bound to. */
    private final class SlotIcons implements TouchControls.Bindings {
        @Override
        public Sprite icon(int slot) {
            if (world == null || slot < 0 || slot >= 90) return null;
            int type = world.keyTypes[slot], action = world.keyActions[slot];
            switch (type) {
                case KeyMap.SKILL: {
                    SkillInfo s = SkillInfo.get(action);
                    return s == null ? null : assets.sprite(s.icon());
                }
                case KeyMap.ITEM:
                case KeyMap.CASH_ITEM:
                    return assets.sprite(ItemInfo.get(action).iconRaw());
                case KeyMap.MENU:
                case KeyMap.ACTION:
                case KeyMap.FACE:
                    return assets.sprite("UIWindow.img/KeyConfig/icon/" + action);
                case KeyMap.MACRO:
                    return assets.sprite("UIWindow.img/SkillMacro/Macroicon/" + action + "/icon");
                default:
                    return null;
            }
        }

        @Override
        public boolean anchoredIcon(int slot) {
            return false;
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private void begin() {
        batch.setProjectionMatrix(cam.combined);
        g.begin();
    }

    private void end() {
        g.end();
    }

    private void enableScissor() {
        batch.flush();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        int x = Math.round(ui.offsetX), y = Math.round(ui.offsetY);
        int w = Math.round(Ui.W * ui.scale), h = Math.round(Ui.H * ui.scale);
        Gdx.gl.glScissor(x, Gdx.graphics.getBackBufferHeight() - y - h, w, h);
    }

    private void disableScissor() {
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private void drawMessage(String msg) {
        SpriteBatch b = batch != null ? batch : new SpriteBatch();
        com.badlogic.gdx.graphics.g2d.BitmapFont font = new com.badlogic.gdx.graphics.g2d.BitmapFont(true);
        OrthographicCamera c = new OrthographicCamera();
        float s = Math.max(1f, Math.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()) / 540f);
        c.setToOrtho(true, Gdx.graphics.getWidth() / s, Gdx.graphics.getHeight() / s);
        b.setProjectionMatrix(c.combined);
        b.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.begin();
        font.draw(b, msg, 20, 20, c.viewportWidth - 40, Align.left, true);
        b.end();
        font.dispose();
        if (b != batch) b.dispose();
    }

    // ------------------------------------------------------------------ events from the game

    private final class Events implements GameEvents {
        @Override
        public void chat(String text, int color) {
            if (chatBar != null) chatBar.add(text, color);
        }

        @Override
        public void status(String text, int color) {
            if (statusMessages != null) statusMessages.add(text, color);
        }

        @Override
        public void npcTalk(NpcTalk talk) {
            NpcDialog old = ui.find(NpcDialog.class);
            if (old != null) ui.close(old);
            StyleDialog oldStyle = ui.find(StyleDialog.class);
            if (oldStyle != null) ui.close(oldStyle);
            maple.ui.windows.UltimateCreator oldCreator = ui.find(maple.ui.windows.UltimateCreator.class);
            if (oldCreator != null) ui.close(oldCreator);
            if (talk == null) return;
            if (talk.type == 2 && talk.text.startsWith(offline.UltimateExplorer.CREATOR)) ui.open(new maple.ui.windows.UltimateCreator(ui, world, talk));
            else if (talk.type == 7 && talk.styles.length > 0) ui.open(new StyleDialog(ui, world, talk));
            else ui.open(new NpcDialog(ui, world, talk));
        }

        @Override
        public void mapleLife(int slot, int itemId) {
            if (ui.find(maple.ui.windows.UltimateCreator.class) == null) ui.open(maple.ui.windows.UltimateCreator.mapleLife(ui, world, slot, itemId));
        }

        @Override
        public void shop(Shop shop) {
            ShopWindow old = ui.find(ShopWindow.class);
            if (old != null) ui.close(old);
            ui.open(new ShopWindow(ui, world, shop));
        }

        @Override
        public void shopResult(int code) {
            ShopWindow s = ui.find(ShopWindow.class);
            if (s != null) s.result(code);
        }

        @Override
        public void refresh() {
            ui.refreshAll();
        }

        @Override
        public void popup(String text) {
            ui.open(new Dialogs.Notice(ui, text, false, null, null));
        }

        @Override
        public void storage(Storage storage) {
            StorageWindow s = ui.find(StorageWindow.class);
            if (s != null) s.update(storage);
            else ui.open(new StorageWindow(ui, world, storage));
        }

        @Override
        public void died() {
            if (ui.find(ReviveNotice.class) != null || ui.find(maple.ui.Dialogs.Notice.class) != null) return;
            long wheels = world.data() == null ? 0 : world.data().inventory(5).values().stream()
                    .filter(it -> it.itemId == 5510000).mapToLong(it -> it.quantity).sum();
            if (wheels > 0) { // Wheel of Destiny: revive where you fell (the server refuses where it may not be used)
                ui.open(new maple.ui.Dialogs.Notice(ui, "Use the Wheel of Destiny to revive here? (" + wheels + " left)", true,
                        () -> world.revive(true), () -> ui.open(new ReviveNotice(ui, world::revive))));
            } else {
                ui.open(new ReviveNotice(ui, world::revive));
            }
        }

        @Override
        public void keymap(int[] types, int[] actions) {
        }

        @Override
        public void partyInvite(int partyId, String from) {
            ui.open(new Dialogs.Notice(ui, from + " has invited you to a party. Would you like to join?", true,
                    () -> world.partyJoin(partyId), null));
        }

        @Override
        public void guildNamePrompt() {
            ui.open(new TextPrompt(ui, "Enter the name of your guild.", "", 12, world::guildCreate));
        }
    }

    /** A notice from any thread. */
    private void notice(String text) {
        Gdx.app.postRunnable(() -> ui.open(new Dialogs.Notice(ui, text, false, null, null)));
    }

    private final class Options implements SettingsWindow.Host {
        @Override
        public Ui.Aspect aspect() {
            return ui.aspect;
        }

        @Override
        public void setAspect(Ui.Aspect a) {
            ui.aspect = a;
            prefs.putString("aspect", a.name()).flush();
            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        @Override
        public boolean option(String key) {
            return MapleGame.this.option(key);
        }

        @Override
        public int rate(String key) {
            return MapleGame.this.rate(key);
        }

        @Override
        public void setRate(String key, int value) {
            prefs.putInteger("rate." + key, value).flush();
            applyOptions();
        }

        @Override
        public void setOption(String key, boolean on) {
            prefs.putBoolean("opt." + key, on).flush();
            applyOptions();
        }

        @Override
        public void editTouch() {
            touch.enabled = true;
            touch.editing = true;
        }

        @Override
        public void exportLog() {
            java.io.File log = Gdx.files.local("maple-log.txt").file();
            if (!log.exists()) {
                notice("There is no log yet.");
                return;
            }
            SaveTransfer.platform.exportFile(log, "maple-log-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".txt", MapleGame.this::notice);
        }

        @Override
        public void exportSave() {
            SaveTransfer.exportSave(saveDir, MapleGame.this::notice);
        }

        @Override
        public void importSave() {
            SaveTransfer.importSave(saveDir, MapleGame.this::notice);
        }

        @Override
        public void logOut() {
            MapleGame.this.logOut();
        }
    }

    // ------------------------------------------------------------------ input

    private final class InputHandler extends InputAdapter {
        @Override
        public boolean keyDown(int keycode) {
            if (keycode >= 0 && keycode < 256) gdxKeys[keycode] = true;
            if (ui == null) return false;
            if (screen == Screen.BOOT) {
                skipLogos = true;
                return true;
            }
            if (keycode == Input.Keys.BACK) keycode = Input.Keys.ESCAPE;
            if (touch.editing && keycode == Input.Keys.ESCAPE) {
                touch.editing = false;
                touch.save();
                return true;
            }
            if (screen == Screen.LOGIN && login != null) {
                if (ui.keyDown(keycode)) return true;
                return login.keyDown(keycode);
            }
            if (screen == Screen.CASH_SHOP) {
                if (ui.keyDown(keycode)) return true;
                return cashScreen != null && cashScreen.keyDown(keycode);
            }
            if (screen != Screen.GAME) return false;
            if (chatBar != null && chatBar.keyDown(keycode)) return true;
            if (ui.keyDown(keycode)) return true;
            if (keycode == Input.Keys.ESCAPE) {
                Window f = ui.frontWindow();
                if (f != null && !f.modal) ui.close(f);
                else if (f == null) openWindow("GameMenu");
                return true;
            }
            if (keycode == Input.Keys.ENTER && Widgets.Focus.get() == null && chatBar != null) {
                int slot = KeyMap.slot(keycode);
                if (world == null || world.keyTypes[slot] == 0) {
                    chatBar.beginTyping();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean keyUp(int keycode) {
            if (keycode >= 0 && keycode < 256) gdxKeys[keycode] = false;
            return false;
        }

        @Override
        public boolean keyTyped(char character) {
            return Widgets.Focus.keyTyped(character);
        }

        /**
         * A tap on a real interface control (a button, a slot, a window) goes to the interface even
         * where a touch button overlaps it; the bars' empty background does not count. The gear
         * and the touch editor always come first.
         */
        private boolean uiFirst(int sx, int sy) {
            if (touch.editing || !ui.onScreen(sx, sy) || touch.gearAt(ui.toUiX(sx), ui.toUiY(sy))) return false;
            Widget w = ui.widgetAt(ui.toUiX(sx), ui.toUiY(sy));
            return w != null && w != statusBar && w != chatBar;
        }

        private float ux(int sx) { return ui.toUiX(sx); }
        private float uy(int sy) { return ui.toUiY(sy); }

        @Override
        public boolean touchDown(int sx, int sy, int pointer, int button) {
            if (ui == null) return false;
            if (screen == Screen.BOOT) {
                skipLogos = true;
                return true;
            }
            if (screen == Screen.GAME && !uiFirst(sx, sy) && touch.touchDown(pointer, ux(sx), uy(sy))) return true;
            return ui.pointerDown(pointer, sx, sy, button == Input.Buttons.RIGHT);
        }

        @Override
        public boolean touchDragged(int sx, int sy, int pointer) {
            if (ui == null) return false;
            if (screen == Screen.GAME && touch.touchDragged(pointer, ux(sx), uy(sy))) return true;
            return ui.pointerMove(pointer, sx, sy);
        }

        @Override
        public boolean touchUp(int sx, int sy, int pointer, int button) {
            if (ui == null) return false;
            if (screen == Screen.GAME && touch.touchUp(pointer, ux(sx), uy(sy))) return true;
            return ui.pointerUp(pointer, sx, sy);
        }

        @Override
        public boolean mouseMoved(int sx, int sy) {
            if (ui != null) ui.mouseMoved(sx, sy);
            return true;
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (ui == null) return false;
            return ui.scrolled(Gdx.input.getX(), Gdx.input.getY(), (int) Math.signum(amountY));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void pause() {
        bgm.pause();
        touch.releaseAll();
        java.util.Arrays.fill(gdxKeys, false);
        if (OfflineServer.isOnline()) new Thread(OfflineServer::saveNow, "save").start();
    }

    @Override
    public void resume() {
        bgm.resume();
    }

    @Override
    public void dispose() {
        bgm.stop();
        if (client != null) client.close();
        OfflineServer.saveNow();
        OfflineServer.stop();
        if (login != null) login.dispose();
        if (field != null) field.dispose();
        if (world != null) world.dispose();
        touch.dispose();
        if (g != null) g.dispose();
        if (batch != null) batch.dispose();
        UiSounds.dispose();
    }
}
