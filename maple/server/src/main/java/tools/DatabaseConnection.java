package tools;

import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The save database: an embedded H2 file on the device (MySQL-compatible mode), so the game
 * needs no database server. Everything a character owns lives in this one file.
 */
public class DatabaseConnection {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);
    private static JdbcConnectionPool pool;
    /** Folder for the save file. The app sets this before starting the server. */
    public static volatile File saveDir = new File("save");

    public static final String DB_NAME = "maple";

    public static File databaseFile() {
        return new File(saveDir, DB_NAME + ".mv.db");
    }

    public static String jdbcUrl() {
        return "jdbc:h2:file:" + new File(saveDir, DB_NAME).getAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,KEY,YEAR,MONTH,DAY,HOUR,MINUTE,SECOND,USER,ROW,LEVEL"
                + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1";
    }

    public static Connection getConnection() throws SQLException {
        if (pool == null) {
            throw new IllegalStateException("Unable to get connection - connection pool is uninitialized");
        }
        return pool.getConnection();
    }

    public static synchronized boolean initializeConnectionPool() {
        if (pool != null) {
            return true;
        }
        try {
            saveDir.mkdirs();
            pool = JdbcConnectionPool.create(jdbcUrl(), "sa", "");
            pool.setMaxConnections(16);
            try (Connection c = pool.getConnection()) {
                log.info("Save database ready at {}", databaseFile().getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to open the save database", e);
            return false;
        }
    }

    /** Closes the database cleanly (used before exporting or importing a save). */
    public static synchronized void shutdown() {
        if (pool != null) {
            try (Connection c = pool.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            } catch (SQLException ignored) {
                // already closed
            }
            pool.dispose();
            pool = null;
        }
    }
}
