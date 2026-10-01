package maple;

import tools.DatabaseConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Save export / import. Export writes a consistent backup of the save database (every character,
 * item and quest) to a zip the player chooses where to keep. Import checks a chosen zip and stages it;
 * it replaces the save the next time the app starts, before the game server opens the database.
 */
public final class SaveTransfer {
    /** Where files go and come from (Android: the system file picker; desktop: the home folder). */
    public interface Platform {
        /** Offer src to the player to keep under the suggested name; report the outcome. */
        void exportFile(File src, String suggestedName, Consumer<String> done);
        /** Let the player choose a save zip; deliver a readable copy (or null if cancelled). */
        void pickFile(Consumer<InputStream> picked);
    }

    public static volatile Platform platform = new DesktopPlatform();
    private static final String PENDING = "import-pending.zip";

    private SaveTransfer() {}

    public static void exportSave(File saveDir, Consumer<String> done) {
        new Thread(() -> {
            try {
                offline.OfflineServer.saveNow();
                File tmp = new File(saveDir, "export-tmp.zip");
                if (tmp.exists() && !tmp.delete()) throw new IOException("could not replace " + tmp);
                DatabaseConnection.backupTo(tmp);
                String name = "maple-save-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".zip";
                platform.exportFile(tmp, name, done);
            } catch (Exception e) {
                Log.error("export save", e);
                done.accept("The save could not be exported: " + Log.brief(e));
            }
        }, "export").start();
    }

    public static void importSave(File saveDir, Consumer<String> done) {
        platform.pickFile(in -> {
            if (in == null) {
                done.accept("Import cancelled.");
                return;
            }
            try {
                File staged = new File(saveDir, PENDING);
                saveDir.mkdirs();
                try (InputStream src = in; OutputStream out = new FileOutputStream(staged)) {
                    copy(src, out);
                }
                if (!containsDatabase(staged)) {
                    staged.delete();
                    done.accept("That file is not a MapleStory offline save.");
                    return;
                }
                done.accept("The save was imported. Close and reopen the app to load it.");
            } catch (IOException e) {
                Log.error("import save", e);
                done.accept("The save could not be imported: " + Log.brief(e));
            }
        });
    }

    private static boolean containsDatabase(File zip) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().endsWith(DatabaseConnection.DB_NAME + ".mv.db")) return true;
            }
        }
        return false;
    }

    /** Before the server starts: replace the save with a staged import (keeping the old one aside). */
    public static void applyPendingImport(File saveDir) {
        File staged = new File(saveDir, PENDING);
        if (!staged.exists()) return;
        File db = new File(saveDir, DatabaseConnection.DB_NAME + ".mv.db");
        try {
            if (db.exists()) {
                File old = new File(saveDir, DatabaseConnection.DB_NAME + ".before-import.mv.db");
                if (old.exists()) old.delete();
                if (!db.renameTo(old)) throw new IOException("could not move the old save aside");
            }
            try (ZipInputStream z = new ZipInputStream(new FileInputStream(staged))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String name = new File(e.getName()).getName();
                    if (!name.endsWith(".mv.db")) continue;
                    try (OutputStream out = new FileOutputStream(new File(saveDir, DatabaseConnection.DB_NAME + ".mv.db"))) {
                        copy(z, out);
                    }
                }
            }
            Log.info("Imported a save from " + staged);
        } catch (IOException e) {
            Log.error("apply import", e);
        } finally {
            staged.delete();
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Desktop: exports to the home folder, imports the newest maple-save-*.zip there. */
    static final class DesktopPlatform implements Platform {
        @Override
        public void exportFile(File src, String name, Consumer<String> done) {
            File dst = new File(System.getProperty("user.home"), name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
                done.accept("Save exported to " + dst.getAbsolutePath());
            } catch (IOException e) {
                done.accept("The save could not be exported: " + Log.brief(e));
            } finally {
                src.delete();
            }
        }

        @Override
        public void pickFile(Consumer<InputStream> picked) {
            File home = new File(System.getProperty("user.home"));
            File[] files = home.listFiles((d, n) -> n.startsWith("maple-save-") && n.endsWith(".zip"));
            File newest = null;
            if (files != null) for (File f : files) if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            try {
                picked.accept(newest == null ? null : new FileInputStream(newest));
            } catch (IOException e) {
                picked.accept(null);
            }
        }
    }
}
