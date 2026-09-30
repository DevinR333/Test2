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
import maple.ui.MapMenu;
import maple.wz.Wz;
import maple.wz.WzNode;

/** The game: loads maps from the WZ files, runs the 125 Hz physics and draws everything. */
public class MapleGame extends ApplicationAdapter {
    public static final int START_MAP = 100000000; // Henesys
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
    private final MapMenu menu = new MapMenu();
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
    private boolean menuNamesLoaded;

    /** Optional hook for automated screenshots/tests: runs after each frame. */
    public Runnable afterFrame;
    /** Test hook: fixed input instead of the touch screen. */
    public Controls scriptedInput;

    public MapleGame(Wz.Source source) {
        this.source = source;
    }

    public Player player() { return player; }
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
        wz = new Wz(source);
        Log.info("WZ source: " + source.describe());
        try {
            wz.file("Map");
        } catch (RuntimeException e) {
            fatal = "Could not read Map.wz\n\n" + Log.brief(e) + "\n\nWZ folder: " + source.describe();
            Log.error("open Map.wz", e);
            return;
        }
        try {
            avatar = new Avatar(wz, 0, 20000, 30000, new int[]{1040002, 1060002, 1072001, 1302000});
            if (!avatar.problems.isEmpty()) showToast("Character: " + avatar.problems);
            player.setAvatar(avatar);
        } catch (RuntimeException e) {
            Log.error("avatar", e);
            showToast("Character could not load: " + Log.brief(e));
        }
        int start = prefs.getInteger("map", START_MAP);
        pendingMap = start;
        pendingPortal = null;
    }

    public void warp(int mapId, String portal) {
        pendingMap = mapId;
        pendingPortal = portal;
        loadingDrawn = false;
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
                if (id != START_MAP) {
                    showToast("Map " + id + " failed, going to Henesys");
                    warp(START_MAP, null);
                    return;
                }
                fatal = "Could not load map " + id + "\n\n" + Log.brief(e);
            } else {
                showToast("Map " + id + " could not load: " + Log.brief(e));
            }
            return;
        }
        if (field != null) field.dispose();
        field = next;
        Portal sp = field.spawnPortal(portal);
        double sx = sp != null ? sp.x : (field.left + field.right) / 2.0;
        double sy = sp != null ? sp.y - 10 : field.top;
        player.spawn(sx, sy);
        setupView();
        camX = sx;
        camY = sy - 50;
        clampCamera();
        bgm.play(wz, field.bgm);
        prefs.putInteger("map", id);
        prefs.flush();
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
        menu.layout(sw / uiScale, sh / uiScale);
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
            drawMessage(fatal);
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
            if (!menu.open) {
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
        if (controls.debugButton.clicked) debug = !debug;
        if (controls.mapsButton.clicked || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            menu.open = !menu.open;
            if (menu.open && !menuNamesLoaded) {
                menuNamesLoaded = true;
                WzNode strings = wz.get("String/Map.img");
                for (int i = 0; i < MapMenu.IDS.length; i++) {
                    for (WzNode region : strings.children()) {
                        WzNode m = region.resolve().get(Integer.toString(MapMenu.IDS[i]));
                        if (m.exists()) {
                            String n = m.getString("mapName", "");
                            String st = m.getString("streetName", "");
                            if (!n.isEmpty()) menu.names[i] = st.isEmpty() || n.contains(st) ? n : st + " : " + n;
                            break;
                        }
                    }
                }
            }
            return;
        }
        int chosen = menu.poll(uiScale);
        if (chosen >= 0) warp(chosen, null);
    }

    private void tick(Controls in) {
        player.update(field, in);
        field.update(timeMs);
        if (touchPortalCooldown > 0) touchPortalCooldown--;

        Portal p = field.portalAt(player.phys.x, player.phys.y);
        if (p != null) {
            boolean use = (in.upPressed && !p.isTouch()) || (p.isTouch() && touchPortalCooldown == 0);
            if (use) {
                if (p.hasTarget()) {
                    touchPortalCooldown = 60;
                    if (p.targetMap == field.id) {
                        Portal dest = field.spawnPortal(p.targetName);
                        if (dest != null) player.spawn(dest.x, dest.y - 10);
                    } else {
                        warp(p.targetMap, p.targetName);
                    }
                } else if (in.upPressed && !p.script.isEmpty()) {
                    showToast("This portal needs a script (not supported yet): " + p.script);
                }
            }
        }
        if (player.phys.y >= field.footholds.borderBottom - 1) {
            Portal sp = field.spawnPortal(null);
            if (sp != null) player.spawn(sp.x, sp.y - 10);
        }
        updateCamera();
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
        controls.showTouch = true;
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
        menu.draw(shapes, textBatch, uiFont);
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
        if (prefs != null && field != null) {
            prefs.putInteger("map", field.id);
            prefs.flush();
        }
    }

    @Override
    public void resume() {
        bgm.resume();
    }

    @Override
    public void dispose() {
        bgm.stop();
        if (field != null) field.dispose();
        if (avatar != null) avatar.dispose();
        batch.dispose();
        textBatch.dispose();
        shapes.dispose();
        font.dispose();
        uiFont.dispose();
    }
}
