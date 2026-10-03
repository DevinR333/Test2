package maple;

import com.badlogic.gdx.Gdx;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Writes problems to the console and to maple-log.txt in the app's local storage. */
public final class Log {
    private Log() {}

    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();
    private static boolean captured;

    /** Keeps the last lines printed by the game and the server so they can be shown on screen. */
    public static synchronized void captureConsole() {
        if (captured) return;
        captured = true;
        System.setOut(new java.io.PrintStream(new LineTee(System.out), true));
        System.setErr(new java.io.PrintStream(new LineTee(System.err), true));
    }

    static void remember(String line) {
        synchronized (RECENT) {
            RECENT.addLast(line);
            while (RECENT.size() > 60) RECENT.removeFirst();
        }
    }

    /** The last {@code n} console lines (oldest first). */
    public static java.util.List<String> recent(int n) {
        synchronized (RECENT) {
            java.util.List<String> all = new java.util.ArrayList<>(RECENT);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    private static final class LineTee extends java.io.OutputStream {
        private final java.io.PrintStream original;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

        LineTee(java.io.PrintStream original) { this.original = original; }

        @Override
        public synchronized void write(int b) {
            original.write(b);
            if (b == '\n') {
                String s = new String(line.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).replace("\r", "");
                remember(s);
                // server warnings, errors and stack traces also go to the log file (for bug reports)
                if (s.contains("WARN") || s.contains("ERROR") || s.contains("Exception") || s.startsWith("\tat ") || s.contains("Autoban")) append(s);
                line.reset();
            } else if (line.size() < 2000) {
                line.write(b);
            }
        }

        @Override
        public void flush() { original.flush(); }
    }

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
    private static String build;

    public static synchronized String build() {
        if (build == null) build = readBuild();
        return build;
    }

    private static String readBuild() {
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
