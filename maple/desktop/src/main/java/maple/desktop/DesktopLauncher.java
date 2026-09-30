package maple.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import maple.MapleGame;
import maple.wz.FolderSource;
import provider.DataProviderFactory;
import provider.wz.XMLWZFile;
import scripting.AbstractScriptManager;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PC version, handy for testing. First argument: the folder with your .wz files.
 * Saves go to ./save, scripts come from ./server/scripts.
 */
public final class DesktopLauncher {
    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0] : "C:\\msclassicv83\\MapleStory");
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("MapleStory v83 Offline");
        cfg.setWindowedMode(1280, 720);
        cfg.useVsync(true);
        cfg.setForegroundFPS(60);

        // Developer option: run the server on an XML export of the data instead of the .wz files.
        String xml = System.getProperty("maple.serverXml");
        if (xml != null && !xml.isEmpty()) {
            DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        }
        File saveDir = new File(System.getProperty("maple.save", "save"));
        MapleGame game = new MapleGame(new FolderSource(dir), saveDir, folderScripts(new File(System.getProperty("maple.scripts", "server/scripts"))));
        if (args.length > 1 && args[1].startsWith("--screenshot=")) {
            Screenshotter.attach(game, args);
        }
        new Lwjgl3Application(game, cfg);
    }

    static AbstractScriptManager.ScriptLoader folderScripts(File root) {
        return new AbstractScriptManager.ScriptLoader() {
            @Override
            public Reader open(String path) throws IOException {
                Path p = root.toPath().resolve(path);
                return Files.exists(p) ? Files.newBufferedReader(p, StandardCharsets.UTF_8) : null;
            }

            @Override
            public String[] list(String sub) {
                String[] names = new File(root, sub).list();
                return names == null ? new String[0] : names;
            }
        };
    }
}
