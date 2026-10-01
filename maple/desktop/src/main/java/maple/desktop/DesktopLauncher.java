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
        boolean safe = Boolean.getBoolean("maple.safe");
        cfg.useVsync(!safe);
        cfg.setForegroundFPS(60);
        if (safe) {
            // Safe mode: no audio device (OpenAL is the usual cause of native crashes on some Windows PCs).
            cfg.disableAudio(true);
        }
        System.out.println("[maple] Desktop start: java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor")
                + "), " + System.getProperty("os.name") + ", safe mode " + safe + ", WZ " + dir.getAbsolutePath());

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
        // On Windows draw through ANGLE (DirectX): works over Remote Desktop and with drivers lacking OpenGL.
        // -Dmaple.gl=opengl forces plain OpenGL.
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String gl = System.getProperty("maple.gl", windows ? "angle" : "opengl");
        if (gl.equals("angle")) useAngle(cfg);
        System.out.println("[maple] Graphics: " + (gl.equals("angle") ? "ANGLE (DirectX)" : "OpenGL"));
        try {
            new Lwjgl3Application(game, cfg);
        } catch (com.badlogic.gdx.utils.GdxRuntimeException e) {
            if (gl.equals("angle") || !String.valueOf(e.getMessage()).contains("Couldn't create window")) throw e;
            System.out.println("[maple] OpenGL window failed, retrying with ANGLE (DirectX)");
            useAngle(cfg);
            new Lwjgl3Application(game, cfg);
        }
    }

    private static void useAngle(Lwjgl3ApplicationConfiguration cfg) {
        cfg.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 0, 0);
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
