package maple.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import maple.MapleGame;
import maple.wz.FolderSource;

import java.io.File;

/** PC version, handy for testing. Pass the folder with your .wz files as the first argument. */
public final class DesktopLauncher {
    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0] : "C:\\msclass");
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("MapleStory v83 Offline");
        cfg.setWindowedMode(1280, 720);
        cfg.useVsync(true);
        cfg.setForegroundFPS(60);
        MapleGame game = new MapleGame(new FolderSource(dir));
        if (args.length > 1 && args[1].startsWith("--screenshot=")) {
            Screenshotter.attach(game, args);
        }
        new Lwjgl3Application(game, cfg);
    }
}
