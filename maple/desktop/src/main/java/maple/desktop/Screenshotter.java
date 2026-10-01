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
