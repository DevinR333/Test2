package maple;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import maple.chr.Avatar;
import maple.chr.Player;
import maple.map.Field;
import maple.map.Portal;
import maple.ui.Controls;
import maple.ui.CharSelectScreen;
import maple.net.GameClient;
import maple.net.PacketWriter;
import net.opcodes.RecvOpcode;
import offline.OfflineServer;
import scripting.AbstractScriptManager;
import java.io.File;
import maple.wz.Wz;
import maple.wz.WzNode;

/** The game: loads maps from the WZ files, runs the 125 Hz physics and draws everything. */
public class MapleGame extends ApplicationAdapter {
    private enum Mode { BOOT, CHAR_SELECT, PLAYING }
    private static final float TICK_MS = 8f;
    private static final float BASE_VIEW_HEIGHT = 600f;

    private final Wz.Source source;
    private Wz wz;
    private SpriteBatch batch, textBatch;
    private ShapeRenderer shapes;
    private BitmapFont font, uiFont;
    private final OrthographicCamera worldCam = new OrthographicCamera();
    private final OrthographicCamera bgCam = new OrthographicCamera();
    private final OrthographicCamera uiCam = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();

    private Field field;
    private final Player player = new Player();
    private Avatar avatar;
    private final Controls controls = new Controls();
    private Mode mode = Mode.BOOT;
    private GameClient client;
    private CharSelectScreen charSelect;
    private final File saveDir;
    private final AbstractScriptManager.ScriptLoader scripts;
    private int pendingSpawnId;
    private int pendingX = Integer.MIN_VALUE, pendingY;
    private long lastMoveSent;
    private double lastSentX, lastSentY;
    private int lastSentStance = -1;
    private boolean avatarFromServer;
    private final Bgm bgm = new Bgm();
    private Preferences prefs;

    private float viewW, viewH, uiScale = 1;
    private double camX, camY;
    private float accumulator;
    private long timeMs;
    private int pendingMap = -1;
    private String pendingPortal;
    private boolean loadingDrawn;
    private String fatal;
    private String toast;
    private long toastUntil;
    private boolean debug;
    private int touchPortalCooldown;

    /** Optional hook for automated screenshots/tests: runs after each frame. */
    public Runnable afterFrame;
    /** Test hook: fixed input instead of the touch screen. */
    public Controls scriptedInput;

    public MapleGame(Wz.Source source, File saveDir, AbstractScriptManager.ScriptLoader scripts) {
        this.source = source;
        this.saveDir = saveDir;
        this.scripts = scripts;
    }

    public GameClient client() { return client; }
    public CharSelectScreen charSelect() { return charSelect; }

    public Player player() { return player; }
    public Controls controls() { return controls; }
    public Field field() { return field; }
    public String fatalError() { return fatal; }

    @Override
    public void create() {
        batch = new SpriteBatch();
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA); // sprites are premultiplied
        textBatch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = new BitmapFont(true);
        font.setUseIntegerPositions(false);
        uiFont = new BitmapFont(true);
        prefs = Gdx.app.getPreferences("maple-offline");
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        controls.load(prefs);
        Gdx.input.setInputProcessor(controls);
        wz = new Wz(source);
        Log.info("WZ source: " + source.describe() + ", build " + Log.build());
        try {
            wz.file("Map");
        } catch (RuntimeException e) {
            fatal = "Could not read Map.wz\n\n" + Log.brief(e) + "\n\nWZ folder: " + source.describe();
            Log.error("open Map.wz", e);
            return;
        }
        maple.map.Physics.load(wz.get("Map/Physics.img"));
        Log.info(maple.map.Physics.describe());
        // The game server runs inside the app, on this device only.
        OfflineServer.start(wz, saveDir, scripts);
        mode = Mode.BOOT;
    }

    /** The server moved us to a map (login or portal). */
    private void serverWarp(int[] w) {
        pendingMap = w[0];
        pendingSpawnId = w[1];
        pendingX = w[2];
        pendingY = w[3];
        pendingPortal = null;
        loadingDrawn = false;
        if (!avatarFromServer && client.player != null) {
            avatarFromServer = true;
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (maple.net.model.Item it : client.player.inventory(-1).values()) {
                if (it.position > -100 || it.position == -111) ids.add(it.itemId);
            }
            int[] eq = new int[ids.size()];
            for (int i = 0; i < eq.length; i++) eq[i] = ids.get(i);
            try {
                avatar = new Avatar(wz, client.player.stats.skin, client.player.stats.face, client.player.stats.hair, eq);
                player.setAvatar(avatar);
            } catch (RuntimeException e) {
                Log.error("avatar", e);
            }
            player.name = client.player.stats.name;
        }
    }

    private void startClient() {
        client = new GameClient();
        client.warpHandler = this::serverWarp;
        client.start();
        charSelect = new CharSelectScreen(wz, client);
    }

    private void loadPending() {
        int id = pendingMap;
        String portal = pendingPortal;
        pendingMap = -1;
        Field next;
        try {
            long t0 = System.currentTimeMillis();
            next = new Field(wz, id);
            Log.info("Loaded map " + id + " (" + next.mapName + ") in " + (System.currentTimeMillis() - t0) + " ms: "
                    + next.tiles + " tiles, " + next.objects + " objects, " + next.life.size() + " life, "
                    + next.bank.size() + " sprites" + (next.bank.decodeErrors > 0 ? ", " + next.bank.decodeErrors + " bad images (" + next.bank.lastError + ")" : ""));
        } catch (RuntimeException e) {
            Log.error("load map " + id, e);
            if (field == null) {
                fatal = "Could not load map " + id + "\n\n" + Log.brief(e);
            } else {
                showToast("Map " + id + " could not load: " + Log.brief(e));
            }
            return;
        }
        if (field != null) field.dispose();
        field = next;
        Portal sp = portal != null ? field.spawnPortal(portal) : field.portalById(pendingSpawnId);
        double sx = sp != null ? sp.x : (field.left + field.right) / 2.0;
        double sy = sp != null ? sp.y - 10 : field.top;
        if (pendingX != Integer.MIN_VALUE) {
            sx = pendingX;
            sy = pendingY - 10;
        }
        mode = Mode.PLAYING;
        player.spawn(sx, sy);
        setupView();
        camX = sx;
        camY = sy - 50;
        clampCamera();
        bgm.play(wz, field.bgm);
        String title = field.streetName.isEmpty() ? field.mapName : field.streetName + " : " + field.mapName;
        showToast(title.isEmpty() ? "Map " + id : title);
        touchPortalCooldown = 60;
    }

    private void setupView() {
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        float aspect = sw / Math.max(1f, sh);
        float vh = BASE_VIEW_HEIGHT, vw = vh * aspect;
        if (field != null) {
            float mw = field.right - field.left, mh = field.bottom - field.top;
            float zoom = 1;
            if (mw > 0 && mw < vw) zoom = Math.max(zoom, vw / mw);
            if (mh > 0 && mh < vh) zoom = Math.max(zoom, vh / mh);
            zoom = Math.min(zoom, 1.7f);
            vw /= zoom;
            vh /= zoom;
        }
        viewW = vw;
        viewH = vh;
        worldCam.setToOrtho(true, viewW, viewH);
        bgCam.setToOrtho(true, viewW, viewH);
        bgCam.update();
        uiScale = Math.max(1f, Math.min(sw, sh) / 540f);
        uiCam.setToOrtho(true, sw / uiScale, sh / uiScale);
        uiCam.update();
        controls.layout(sw, sh, uiScale);
    }

    @Override
    public void resize(int width, int height) {
        setupView();
    }

    private void showToast(String msg) {
        toast = msg;
        toastUntil = System.currentTimeMillis() + 3500;
        Log.info(msg);
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (fatal != null) {
            drawMessage(fatal + "\n\nBuild " + Log.build());
            after();
            return;
        }
        if (client != null) client.update();
        if (mode == Mode.BOOT) {
            if (OfflineServer.failure() != null) {
                fatal = "The game server could not start:\n\n" + Log.details(OfflineServer.failure());
            } else if (client == null && OfflineServer.isOnline()) {
                startClient();
            } else if (client != null && client.state == GameClient.State.FAILED) {
                fatal = client.error;
            } else if (client != null && client.state == GameClient.State.CHARACTER_SELECT) {
                mode = Mode.CHAR_SELECT;
            }
            drawMessage((client == null ? "Starting MapleStory..." : "Logging in...") + "\n\nBuild " + Log.build());
            after();
            return;
        }
        if (mode == Mode.CHAR_SELECT && pendingMap < 0) {
            if (client.state == GameClient.State.FAILED) fatal = client.error;
            charSelect.render(batch, textBatch, shapes, uiFont, uiCam.combined, uiCam.viewportWidth, uiCam.viewportHeight, uiScale, Gdx.graphics.getDeltaTime());
            after();
            return;
        }
        if (pendingMap >= 0) {
            if (!loadingDrawn) {
                drawMessage("Loading...");
                loadingDrawn = true;
                after();
                return;
            }
            loadingDrawn = false;
            loadPending();
            if (field == null) {
                after();
                return;
            }
        }
        if (field == null) {
            drawMessage("Loading...");
            after();
            return;
        }

        Controls in = scriptedInput != null ? scriptedInput : controls;
        if (scriptedInput == null) controls.poll();
        handleButtons();

        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.25f);
        accumulator += dt * 1000f;
        boolean first = true;
        while (accumulator >= TICK_MS) {
            accumulator -= TICK_MS;
            timeMs += (long) TICK_MS;
            if (!controls.editing) {
                tick(in);
                if (first) {
                    in.consumeEdges();
                    first = false;
                }
            }
            if (pendingMap >= 0) break;
        }
        float alpha = accumulator / TICK_MS;
        draw(alpha);
        after();
    }

    private void after() {
        if (afterFrame != null) afterFrame.run();
    }

    private void handleButtons() {
        if (controls.debugToggle) debug = !debug;
    }

    private void tick(Controls in) {
        player.update(field, in);
        field.update(timeMs);
        if (touchPortalCooldown > 0) touchPortalCooldown--;

        Portal p = field.portalAt(player.phys.x, player.phys.y);
        if (p != null) {
            boolean use = (in.upPressed && !p.isTouch()) || (p.isTouch() && touchPortalCooldown == 0);
            if (use) {
                touchPortalCooldown = 60;
                if (!p.script.isEmpty()) {
                    sendMovement(true);
                    client.send(new PacketWriter(RecvOpcode.CHANGE_MAP_SPECIAL.getValue()).writeByte(0).writeString(p.name).writeShort(0));
                } else if (p.hasTarget() && p.targetMap == field.id) {
                    Portal dest = field.spawnPortal(p.targetName);
                    if (dest != null) player.spawn(dest.x, dest.y - 10);
                } else if (p.hasTarget()) {
                    sendMovement(true);
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

    /** Tells the server where we are (like the client's movement packets), at most every 200 ms. */
    private void sendMovement(boolean force) {
        if (client == null) return;
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
        w.writeByte(1); // one movement fragment: absolute move
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
        if (Math.abs(hd) >= 5) camX += hd * (12.0 / viewW) * 1.5;
        if (Math.abs(vd) >= 5) camY += vd * (12.0 / viewH) * 1.5;
        clampCamera();
    }

    private void clampCamera() {
        double minX = field.left + viewW / 2.0, maxX = field.right - viewW / 2.0;
        if (minX > maxX) camX = (field.left + field.right) / 2.0;
        else camX = Math.max(minX, Math.min(maxX, camX));
        double minY = field.top + viewH / 2.0, maxY = field.bottom - viewH / 2.0;
        if (minY > maxY) camY = (field.top + field.bottom) / 2.0;
        else camY = Math.max(minY, Math.min(maxY, camY));
    }

    private void draw(float alpha) {
        float cx = Math.round(camX), cy = Math.round(camY);
        worldCam.position.set(cx, cy, 0);
        worldCam.update();
        double viewX = viewW / 2.0 - cx, viewY = viewH / 2.0 - cy;

        batch.setProjectionMatrix(bgCam.combined);
        batch.begin();
        field.drawBackgrounds(batch, viewX, viewY, viewW, viewH, timeMs);
        batch.setProjectionMatrix(worldCam.combined);
        int playerLayer = Math.max(0, Math.min(7, player.layer()));
        for (int layer = 0; layer < 8; layer++) {
            field.drawLayer(batch, layer, alpha, timeMs);
            if (layer == playerLayer) player.draw(batch, alpha);
        }
        field.drawPortals(batch, timeMs);
        batch.setProjectionMatrix(bgCam.combined);
        field.drawForegrounds(batch, viewX, viewY, viewW, viewH, timeMs);
        batch.end();

        drawNameTag(alpha);

        if (debug) {
            shapes.setProjectionMatrix(worldCam.combined);
            shapes.begin(ShapeRenderer.ShapeType.Line);
            field.drawDebug(shapes);
            shapes.setColor(Color.GREEN);
            shapes.circle((float) player.phys.x, (float) player.phys.y, 4);
            shapes.end();
        }
        drawHud();
    }

    private void drawNameTag(float alpha) {
        if (player.avatar() == null) return;
        float x = (float) Math.round(player.phys.drawX(alpha)), y = (float) Math.round(player.phys.drawY(alpha));
        layout.setText(font, player.name);
        float w = layout.width + 8, h = layout.height + 6;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(worldCam.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0, 0, 0, 0.6f);
        shapes.rect(x - w / 2, y + 2, w, h);
        shapes.end();
        textBatch.setProjectionMatrix(worldCam.combined);
        textBatch.begin();
        font.setColor(Color.WHITE);
        font.draw(textBatch, player.name, x - layout.width / 2, y + 5);
        textBatch.end();
    }

    private void drawHud() {
        shapes.setProjectionMatrix(uiCam.combined);
        textBatch.setProjectionMatrix(uiCam.combined);
        controls.draw(shapes, textBatch, uiFont);
        textBatch.begin();
        uiFont.setColor(Color.WHITE);
        String title = field.streetName.isEmpty() ? field.mapName : field.streetName + " : " + field.mapName;
        uiFont.draw(textBatch, (title.isEmpty() ? "Map" : title) + "  (" + field.id + ")", 12, 12);
        if (debug) {
            uiFont.draw(textBatch, Gdx.graphics.getFramesPerSecond() + " fps   x=" + (int) player.phys.x + " y=" + (int) player.phys.y
                    + "  fh=" + player.phys.fhid + "  layer=" + player.layer() + "  " + player.state, 12, 32);
        }
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            layout.setText(uiFont, toast);
            uiFont.draw(textBatch, toast, (uiCam.viewportWidth - layout.width) / 2, 60);
        }
        textBatch.end();
    }

    private void drawMessage(String msg) {
        if (textBatch == null) return;
        OrthographicCamera cam = new OrthographicCamera();
        float s = Math.max(1f, Math.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()) / 540f);
        cam.setToOrtho(true, Gdx.graphics.getWidth() / s, Gdx.graphics.getHeight() / s);
        textBatch.setProjectionMatrix(cam.combined);
        textBatch.begin();
        uiFont.setColor(Color.WHITE);
        uiFont.draw(textBatch, msg, 30, 30, cam.viewportWidth - 60, com.badlogic.gdx.utils.Align.left, true);
        textBatch.end();
    }

    @Override
    public void pause() {
        bgm.pause();
    }

    @Override
    public void resume() {
        bgm.resume();
    }

    @Override
    public void dispose() {
        bgm.stop();
        if (client != null) client.close();
        if (charSelect != null) charSelect.dispose();
        OfflineServer.stop();
        if (field != null) field.dispose();
        if (avatar != null) avatar.dispose();
        batch.dispose();
        textBatch.dispose();
        shapes.dispose();
        font.dispose();
        uiFont.dispose();
    }
}
