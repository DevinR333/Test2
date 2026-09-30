package maple;

import com.badlogic.gdx.Gdx;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Writes problems to the console and to maple-log.txt in the app's local storage. */
public final class Log {
    private Log() {}

    public static void info(String msg) {
        System.out.println("[maple] " + msg);
        append(msg);
    }

    public static void error(String what, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        System.err.println("[maple] " + what + ": " + sw);
        append(what + ": " + sw);
    }

    private static void append(String line) {
        try {
            if (Gdx.files != null) Gdx.files.local("maple-log.txt").writeString(line + "\n", true);
        } catch (Throwable ignored) {
            // logging must never crash the game
        }
    }

    public static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
