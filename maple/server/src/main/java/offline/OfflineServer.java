package offline;

import config.YamlConfig;
import maple.wz.Wz;
import net.server.Server;
import provider.DataProviderFactory;
import scripting.AbstractScriptManager;
import tools.DatabaseConnection;

import java.io.File;

/**
 * Runs the game server inside the app, on this device only (127.0.0.1), for single-player offline play.
 */
public final class OfflineServer {
    public static final String HOST = "127.0.0.1";
    public static final int LOGIN_PORT = 8484;

    private static volatile Thread thread;
    private static volatile Throwable failure;

    private OfflineServer() {}

    public static boolean isOnline() {
        return Server.getInstance().isOnline();
    }

    public static Throwable failure() {
        return failure;
    }

    /** Starts the server in the background. Safe to call more than once. */
    public static synchronized void start(Wz wz, File saveDir, AbstractScriptManager.ScriptLoader scripts) {
        if (thread != null) return;
        if (wz != null) DataProviderFactory.wz = wz;
        DatabaseConnection.saveDir = saveDir;
        if (scripts != null) AbstractScriptManager.loader = scripts;
        Server.exitOnShutdown = false;
        YamlConfig.config.server.SHUTDOWNHOOK = false;
        thread = new Thread(() -> {
            try {
                Server.getInstance().init();
            } catch (Throwable t) {
                failure = t;
                org.slf4j.LoggerFactory.getLogger(OfflineServer.class).error("Server failed to start", t);
            }
        }, "offline-server");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Saves every logged-in character now (app going to the background, before switching characters).
     * Runs on the caller's thread; safe to call any time.
     */
    public static void saveNow() {
        try {
            for (net.server.world.World w : Server.getInstance().getWorlds()) {
                for (client.Character chr : w.getPlayerStorage().getAllCharacters()) {
                    if (chr != null && chr.isLoggedin()) chr.saveCharToDB(false);
                }
            }
            DatabaseConnection.checkpoint();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(OfflineServer.class).warn("Save failed", t);
        }
    }

    /** Saves everyone and stops (e.g. when the app closes, or before exporting the save). */
    public static synchronized void stop() {
        if (thread == null) return;
        try {
            Server.getInstance().shutdown(false).run();
        } catch (Throwable ignored) {
            // best effort
        }
        DatabaseConnection.shutdown();
        thread = null;
    }
}
