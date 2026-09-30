package maple.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import maple.MapleGame;
import maple.ui.Controls;

/**
 * Testing aid: --screenshot=out.png [--map=ID] [--frames=N] [--hold=right|left|jump|up|down,...]
 * Runs the game for N frames with the given buttons held, saves a PNG and exits.
 */
final class Screenshotter {
    static void attach(MapleGame game, String[] args) {
        String out = "screenshot.png";
        int frames = 180;
        boolean play = false;
        String hold = "";
        String script = "";
        for (String a : args) {
            if (a.startsWith("--screenshot=")) out = a.substring(13);
            else if (a.startsWith("--frames=")) frames = Integer.parseInt(a.substring(9));
            else if (a.equals("--play")) play = true;
            else if (a.startsWith("--hold=")) hold = a.substring(7);
            else if (a.startsWith("--script=")) script = a.substring(9);
        }
        final String file = out;
        final int total = frames;
        final boolean autoPlay = play;
        final boolean edit = java.util.Arrays.asList(args).contains("--edit");
        final Controls input = new Controls();
        input.left = hold.contains("left");
        input.right = hold.contains("right");
        input.jump = hold.contains("jump");
        input.up = hold.contains("up");
        input.down = hold.contains("down");
        if (!hold.isEmpty() || !script.isEmpty()) game.scriptedInput = input;
        // --script=right:60,up+right:10,up:120  (buttons held for N frames, in order)
        final java.util.List<String[]> steps = new java.util.ArrayList<>();
        if (!script.isEmpty()) for (String s : script.split(",")) steps.add(s.split(":"));
        game.afterFrame = new Runnable() {
            int n;
            boolean created;

            @Override
            public void run() {
                n++;
                // --play: create a character if there is none, then enter the game with the first one
                if (autoPlay && game.client() != null && game.client().state == maple.net.GameClient.State.CHARACTER_SELECT) {
                    if (game.client().characters.isEmpty()) {
                        if (!created) {
                            created = true;
                            game.charSelect().setName("Mapler");
                            game.charSelect().create();
                        }
                    } else {
                        game.client().selectCharacter(game.client().characters.get(0).stats.id);
                    }
                }
                if (n == 5 && edit) game.controls().editing = true;
                if (!steps.isEmpty()) {
                    int f = n, idx = 0;
                    while (idx < steps.size() && f > Integer.parseInt(steps.get(idx)[1])) {
                        f -= Integer.parseInt(steps.get(idx)[1]);
                        idx++;
                    }
                    String b = idx < steps.size() ? steps.get(idx)[0] : "";
                    boolean wasUp = input.up, wasJump = input.jump, wasDown = input.down;
                    input.left = b.contains("left");
                    input.right = b.contains("right");
                    input.up = b.contains("up");
                    input.down = b.contains("down");
                    input.jump = b.contains("jump");
                    input.upPressed = input.up && !wasUp;
                    input.jumpPressed = input.jump && !wasJump;
                    input.downPressed = input.down && !wasDown;
                }
                if (n >= total) {
                    Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    PixmapIO.writePNG(Gdx.files.absolute(new java.io.File(file).getAbsolutePath()), pm, -1, true);
                    pm.dispose();
                    System.out.println("screenshot " + file + " player=" + (int) game.player().phys.x + "," + (int) game.player().phys.y
                            + " state=" + game.player().state + (game.fatalError() != null ? " FATAL=" + game.fatalError() : ""));
                    Gdx.app.exit();
                }
            }
        };
    }
}
