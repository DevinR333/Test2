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

    /** Build stamp (commit + time), or "unknown". */
    public static String build() {
        try (java.io.InputStream in = Log.class.getClassLoader().getResourceAsStream("maple-build.txt")) {
            if (in == null) return "unknown";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** The error plus its first stack lines (and causes), for the on-screen error report. */
    public static String details(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (t != null && depth < 3) {
            if (depth > 0) sb.append("Caused by: ");
            sb.append(brief(t)).append('\n');
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) sb.append("   at ").append(st[i]).append('\n');
            t = t.getCause() == t ? null : t.getCause();
            depth++;
        }
        return sb.toString();
    }

    public static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
