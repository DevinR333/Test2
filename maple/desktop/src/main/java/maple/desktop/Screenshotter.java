package maple.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import maple.MapleGame;
import maple.input.Pad;
import maple.net.GameClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Testing aid. --screenshot=out.png [--frames=N] [--play] [--do=FRAME:ACTION,...] [--shot=FRAME:file.png,...]
 * --play creates a character if needed and enters the game. Actions: open:Item, key:SLOT, tap:X:Y (UI
 * coords), hold:right|left|jump..., edit (touch editor), talk (nearest NPC).
 */
final class Screenshotter {
    static void attach(MapleGame game, String[] args) {
        String out = "screenshot.png";
        int frames = 180;
        boolean play = false;
        List<String[]> actions = new ArrayList<>();
        List<String[]> shots = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--screenshot=")) out = a.substring(13);
            else if (a.startsWith("--frames=")) frames = Integer.parseInt(a.substring(9));
            else if (a.equals("--play")) play = true;
            else if (a.startsWith("--do=")) for (String s : a.substring(5).split(",")) actions.add(s.split(":", 2));
            else if (a.startsWith("--shot=")) for (String s : a.substring(7).split(",")) shots.add(s.split(":", 2));
        }
        final String file = out;
        final int total = frames;
        final boolean autoPlay = play;
        final Pad pad = new Pad();
        game.afterFrame = new Runnable() {
            int n;
            int gameFrames, lastF = -1;
            boolean created, selected;

            @Override
            public void run() {
                n++;
                GameClient c = game.client();
                if (autoPlay && c != null && c.state == GameClient.State.CHARACTER_SELECT && game.screen() == MapleGame.Screen.LOGIN) {
                    if (c.characters.isEmpty()) {
                        if (!created) {
                            created = true;
                            c.createCharacter("Mapler", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
                        }
                    } else if (!selected) {
                        selected = true;
                        c.selectCharacter(c.characters.get(0).stats.id);
                    }
                }
                int f = autoPlay ? (game.screen() == MapleGame.Screen.GAME || game.screen() == MapleGame.Screen.CASH_SHOP ? ++gameFrames : gameFrames) : n;
                if (f == lastF) return; // paused (loading): act once per counted frame
                lastF = f;
                for (String[] a : actions) {
                    if (Integer.parseInt(a[0]) == f) act(game, a[1], pad);
                }
                for (String[] s : shots) {
                    if (Integer.parseInt(s[0]) == f) save(s[1]);
                }
                if (f >= total) {
                    save(file);
                    System.out.println("screenshot " + file + " screen=" + game.screen()
                            + (game.player() != null ? " player=" + (int) game.player().phys.x + "," + (int) game.player().phys.y + " state=" + game.player().state : "")
                            + (game.fatalError() != null ? " FATAL=" + game.fatalError() : ""));
                    Gdx.app.exit();
                }
            }
        };
    }

    private static void act(MapleGame game, String action, Pad pad) {
        String[] p = action.split(":");
        switch (p[0]) {
            case "open":
                try {
                    java.lang.reflect.Method m = MapleGame.class.getDeclaredMethod("openWindow", String.class);
                    m.setAccessible(true);
                    m.invoke(game, p[1]);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                break;
            case "key":
                try {
                    java.lang.reflect.Method m = MapleGame.class.getDeclaredMethod("pressSlot", int.class);
                    m.setAccessible(true);
                    m.invoke(game, Integer.parseInt(p[1]));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                break;
            case "tap": {
                float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                float sx = game.ui().offsetX + x * game.ui().scale, sy = game.ui().offsetY + y * game.ui().scale;
                Gdx.input.getInputProcessor().touchDown((int) sx, (int) sy, 0, 0);
                Gdx.input.getInputProcessor().touchUp((int) sx, (int) sy, 0, 0);
                break;
            }
            case "tapnpc": {
                maple.game.Npc n = game.world().npcs.values().iterator().next();
                float[] u = game.worldToUi(n.x, n.y - 20);
                act(game, "tap:" + u[0] + ":" + u[1], pad);
                System.out.println("tapped NPC " + n.name + " at " + (int) u[0] + "," + (int) u[1]);
                break;
            }
            case "pointnpc": { // put the touch cursor on the first NPC without tapping
                maple.game.Npc n = game.world().npcs.values().iterator().next();
                float[] u = game.worldToUi(n.x, n.y - 20);
                game.ui().mouseX = u[0];
                game.ui().mouseY = u[1];
                break;
            }
            case "mouse": // a touch button set to a mouse action
                try {
                    java.lang.reflect.Method m = MapleGame.class.getDeclaredMethod("touchMouse", int.class);
                    m.setAccessible(true);
                    m.invoke(game, Integer.parseInt(p[1]));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                break;
            case "tapbtn": { // tap a window button by name (e.g. BtNo) like a finger would
                maple.ui.Button found = null;
                for (maple.ui.Window w : game.ui().windows()) found = findButton(w, p[1], found);
                if (found == null) {
                    System.out.println("no button " + p[1]);
                    break;
                }
                float bx = found.screenX() + found.w / 2, by = found.screenY() + found.h / 2;
                System.out.println("tapping " + p[1] + " at " + (int) bx + "," + (int) by);
                act(game, "tap:" + bx + ":" + by, pad);
                break;
            }
            case "audit": // every window: can each button be tapped?
                audit(game, p.length > 1 ? p[1] : "Equip|Item|Stat|Skill|Quest|KeyConfig|MiniMap|GameMenu|ShortCut|SysOpt|Quit|Friends|Party|Guild|Messenger|MonsterBook|WorldMap");
                break;
            case "serverwin": { // a server-opened window: shop or storage
                client.Character chr = net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Mapler");
                if (p[1].equals("shop")) server.ShopFactory.getInstance().getShopForNPC(1012004).sendShop(chr.getClient());
                else chr.getStorage().sendStorage(chr.getClient(), 1012009);
                break;
            }
            case "appswitch": // what Android does when you leave the app and come back
                game.pause();
                game.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                game.resume();
                break;
            case "report": {
                StringBuilder sb = new StringBuilder("REPORT windows=");
                for (maple.ui.Window w : game.ui().windows()) sb.append(w.name).append(' ');
                sb.append(" npcTalk=").append(game.world() != null && game.world().talk != null);
                sb.append(" screen=").append(game.screen()).append(" hud=");
                for (maple.ui.Widget w : game.ui().hud.children) sb.append(w.getClass().getSimpleName()).append(' ');
                System.out.println(sb);
                break;
            }
            case "hold":
                pad.left = action.contains("left");
                pad.right = action.contains("right");
                pad.up = action.contains("up");
                pad.down = action.contains("down");
                pad.jump = action.contains("jump");
                game.scriptedPad = pad;
                break;
            case "release":
                game.scriptedPad = null;
                break;
            case "edit":
                game.touch.enabled = true;
                game.touch.editing = true;
                break;
            case "touch": // behave like a phone
                game.touch.enabled = true;
                game.ui().touchDevice = true;
                break;
            default:
                System.out.println("unknown action " + action);
        }
    }

    private static void audit(MapleGame game, String names) {
        int checked = 0, bad = 0;
        for (String name : names.split("[|]")) {
            if (!name.equals("-")) {
                for (String n : name.split(";")) invoke(game, "openWindow", n);
            }
            int[] r = auditOpen(game, name);
            checked += r[0];
            bad += r[1];
            invoke(game, "closeAllWindows", null);
        }
        // the HUD on its own
        int[] r = auditWidget(game, game.ui().hud, "HUD");
        checked += r[0];
        bad += r[1];
        System.out.println("AUDIT done: " + checked + " controls checked, " + bad + " not tappable");
    }

    private static int[] auditOpen(MapleGame game, String label) {
        int checked = 0, bad = 0;
        if (game.ui().windows().isEmpty()) System.out.println("AUDIT " + label + ": no window opened");
        for (maple.ui.Window w : game.ui().windows()) {
            int[] r = auditWidget(game, w, label + "/" + w.name);
            checked += r[0];
            bad += r[1];
        }
        return new int[]{checked, bad};
    }

    private static int[] auditWidget(MapleGame game, maple.ui.Widget w, String where) {
        int checked = 0, bad = 0;
        if (!w.visible) return new int[]{0, 0};
        if (w.interactive() && !(w instanceof maple.ui.Window) && !(w instanceof maple.ui.Button && ((maple.ui.Button) w).disabled)) {
            float cx = w.screenX() + w.w / 2, cy = w.screenY() + w.h / 2;
            maple.ui.Widget hit = game.ui().widgetAt(cx, cy);
            boolean onScreen = cx >= -game.ui().offsetX / game.ui().scale && cx <= maple.ui.Ui.W + game.ui().offsetX / game.ui().scale && cy >= 0 && cy <= maple.ui.Ui.H;
            String id = w instanceof maple.ui.Button ? ((maple.ui.Button) w).path : w.getClass().getSimpleName();
            checked++;
            String problem = null;
            if (!onScreen) problem = "off screen at " + (int) cx + "," + (int) cy;
            else if (game.touch.gearAt(cx, cy)) problem = "under the touch Edit gear";
            else if (where.startsWith("HUD") && game.touch.covers(cx, cy)) problem = "under a touch button";
            else if (hit != w && (hit == null || !isAncestor(w, hit))) problem = "covered by " + (hit == null ? "nothing (falls through)" : hit.getClass().getSimpleName()
                    + (hit instanceof maple.ui.Button ? " " + ((maple.ui.Button) hit).path : ""));
            if (problem != null) {
                bad++;
                System.out.println("AUDIT BAD " + where + ": " + id + " " + problem);
            }
        }
        for (maple.ui.Widget c : new java.util.ArrayList<>(w.children)) {
            int[] r = auditWidget(game, c, where);
            checked += r[0];
            bad += r[1];
        }
        return new int[]{checked, bad};
    }

    private static boolean isAncestor(maple.ui.Widget a, maple.ui.Widget b) {
        for (maple.ui.Widget p = b.parent; p != null; p = p.parent) if (p == a) return true;
        return false;
    }

    private static void invoke(MapleGame game, String method, String arg) {
        try {
            java.lang.reflect.Method m = arg == null ? MapleGame.class.getDeclaredMethod(method) : MapleGame.class.getDeclaredMethod(method, String.class);
            m.setAccessible(true);
            if (arg == null) m.invoke(game);
            else m.invoke(game, arg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static maple.ui.Button findButton(maple.ui.Widget w, String name, maple.ui.Button found) {
        if (w instanceof maple.ui.Button && ((maple.ui.Button) w).path.endsWith("/" + name) && w.visible) found = (maple.ui.Button) w;
        for (maple.ui.Widget c : w.children) found = findButton(c, name, found);
        return found;
    }

    private static void save(String file) {
        Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        PixmapIO.writePNG(Gdx.files.absolute(new java.io.File(file).getAbsolutePath()), pm, -1, true);
        pm.dispose();
        System.out.println("saved " + file);
    }
}
