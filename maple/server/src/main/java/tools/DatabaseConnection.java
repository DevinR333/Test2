package tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The save database: an embedded H2 file on the device (MySQL-compatible mode), so the game
 * needs no database server. Everything a character owns lives in this one file.
 * Connections come straight from H2's driver (its pooled/XA data sources need classes Android lacks);
 * opening an embedded connection to an already-open database is cheap.
 */
public class DatabaseConnection {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);
    private static volatile boolean ready;
    /** Keeps the database open between the server's short-lived connections. */
    private static Connection keepAlive;
    /** Folder for the save file. The app sets this before starting the server. */
    public static volatile File saveDir = new File("save");

    public static final String DB_NAME = "maple";

    public static File databaseFile() {
        return new File(saveDir, DB_NAME + ".mv.db");
    }

    public static String jdbcUrl() {
        return "jdbc:h2:file:" + new File(saveDir, DB_NAME).getAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,KEY,YEAR,MONTH,DAY,HOUR,MINUTE,SECOND,USER,ROW,LEVEL"
                + ";DB_CLOSE_DELAY=-1";
    }

    private static Connection open() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "sa");
        p.setProperty("password", "");
        return new org.h2.Driver().connect(jdbcUrl(), p);
    }

    public static Connection getConnection() throws SQLException {
        if (!ready) {
            throw new IllegalStateException("Unable to get connection - connection pool is uninitialized");
        }
        return open();
    }

    public static synchronized boolean initializeConnectionPool() {
        if (ready) {
            return true;
        }
        try {
            saveDir.mkdirs();
            keepAlive = open();
            ready = true;
            log.info("Save database ready at {}", databaseFile().getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.error("Failed to open the save database", e);
            return false;
        }
    }

    /** Closes the database cleanly (used before exporting or importing a save). */
    public static synchronized void shutdown() {
        if (!ready) return;
        ready = false;
        try {
            keepAlive.createStatement().execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // already closed
        }
        try {
            keepAlive.close();
        } catch (SQLException ignored) {
            // already closed
        }
        keepAlive = null;
    }
}
