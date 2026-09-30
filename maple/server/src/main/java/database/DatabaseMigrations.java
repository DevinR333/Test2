package database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates the tables and fills the game data tables (shops, drops, maker...) the first time the
 * save database is opened. Replaces Liquibase with a tiny runner over the same SQL files.
 */
public class DatabaseMigrations {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrations.class);
    private static final String[] CHANGELOGS = {"db/changelog-tables.xml", "db/changelog-data.xml"};
    private static final Pattern CHANGESET = Pattern.compile("<changeSet\\s+id=\"([^\"]+)\"[\\s\\S]*?</changeSet>");
    private static final Pattern SQLFILE = Pattern.compile("<sqlFile\\s+path=\"([^\"]+)\"");

    public static void runDatabaseMigrations() {
        try (Connection c = DatabaseConnection.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS offline_migrations (id VARCHAR(64) PRIMARY KEY)");
            }
            for (String changelog : CHANGELOGS) {
                String xml = resource(changelog);
                Matcher m = CHANGESET.matcher(xml);
                while (m.find()) {
                    String id = changelog + "#" + m.group(1);
                    if (applied(c, id)) {
                        continue;
                    }
                    Matcher f = SQLFILE.matcher(m.group());
                    List<String> files = new ArrayList<>();
                    while (f.find()) {
                        files.add(f.group(1));
                    }
                    for (String file : files) {
                        runSqlFile(c, file);
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO offline_migrations (id) VALUES (?)")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to run database migrations", e);
        }
    }

    private static boolean applied(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM offline_migrations WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void runSqlFile(Connection c, String path) throws IOException, SQLException {
        String sql = resource(path);
        long t0 = System.currentTimeMillis();
        int n = 0;
        try (Statement st = c.createStatement()) {
            for (String stmt : splitStatements(sql)) {
                try {
                    st.execute(h2Compatible(stmt));
                    n++;
                } catch (SQLException e) {
                    throw new SQLException("In " + path + ": " + e.getMessage() + "\nStatement: "
                            + (stmt.length() > 300 ? stmt.substring(0, 300) + "..." : stmt), e);
                }
            }
        }
        log.info("Database setup: {} ({} statements, {} ms)", path, n, System.currentTimeMillis() - t0);
    }

    private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_NAME = Pattern.compile("\\b(KEY|INDEX|CONSTRAINT)\\s+`?(\\w+)`?(?=\\s*(\\(|FOREIGN|PRIMARY|UNIQUE|CHECK))", Pattern.CASE_INSENSITIVE);

    /** MySQL index names are per table, H2's are per schema: prefix them with the table name. */
    private static final Pattern CHARSET = Pattern.compile("\\s+(DEFAULT\\s+)?(CHARACTER\\s+SET|CHARSET)\\s*=?\\s*\\w+|\\s+COLLATE\\s*=?\\s*\\w+", Pattern.CASE_INSENSITIVE);

    private static final Pattern USING = Pattern.compile("\\s+USING\\s+(BTREE|HASH)", Pattern.CASE_INSENSITIVE);

    /** MySQL allows "double quoted" strings; H2 reads those as names. Turn them into 'single quoted' strings. */
    static String doubleQuotedStrings(String sql) {
        if (sql.indexOf('"') < 0) return sql;
        StringBuilder out = new StringBuilder(sql.length());
        boolean single = false, dbl = false, back = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\\' && (single || dbl) && i + 1 < sql.length()) {
                char n = sql.charAt(i + 1);
                if (dbl && n == '"') out.append('"');
                else if (dbl && n == '\'') out.append("''");
                else out.append(c).append(n);
                i++;
                continue;
            }
            if (!single && !back && c == '"') {
                dbl = !dbl;
                out.append('\'');
                continue;
            }
            if (dbl && c == '\'') {
                out.append("''");
                continue;
            }
            if (!dbl && !back && c == '\'') single = !single;
            else if (!dbl && !single && c == '`') back = !back;
            out.append(c);
        }
        return out.toString();
    }

    static String h2Compatible(String stmt) {
        if (stmt.regionMatches(true, 0, "INSERT", 0, 6)) return doubleQuotedStrings(stmt);
        stmt = CHARSET.matcher(stmt).replaceAll("");
        stmt = USING.matcher(stmt).replaceAll("");
        // H2 has no unsigned integers: widen them so the full MySQL range fits.
        stmt = stmt.replaceAll("(?i)\\bTINYINT(\\s*\\(\\d+\\))?\\s+UNSIGNED\\b", "SMALLINT")
                .replaceAll("(?i)\\bSMALLINT(\\s*\\(\\d+\\))?\\s+UNSIGNED\\b", "INT")
                .replaceAll("(?i)\\bMEDIUMINT(\\s*\\(\\d+\\))?\\s+UNSIGNED\\b", "INT")
                .replaceAll("(?i)\\bINT(\\s*\\(\\d+\\))?\\s+UNSIGNED\\b", "BIGINT")
                .replaceAll("(?i)\\bBIGINT(\\s*\\(\\d+\\))?\\s+UNSIGNED\\b", "BIGINT");
        Matcher t = CREATE_TABLE.matcher(stmt);
        if (!t.find()) return stmt;
        String table = t.group(1).toLowerCase();
        Matcher m = INDEX_NAME.matcher(stmt);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String kw = m.group(1);
            String name = m.group(2);
            if (kw.equalsIgnoreCase("KEY") && name.equalsIgnoreCase("PRIMARY")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(kw + " " + table + "_" + name));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Splits on semicolons outside quotes and comments. */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean single = false, dbl = false, back = false, lineComment = false, blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) {
                if (ch == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (!single && !dbl && !back) {
                if (ch == '-' && next == '-') { lineComment = true; continue; }
                if (ch == '#') { lineComment = true; continue; }
                if (ch == '/' && next == '*') { blockComment = true; i++; continue; }
                if (ch == ';') {
                    String s = cur.toString().trim();
                    if (!s.isEmpty()) out.add(s);
                    cur.setLength(0);
                    continue;
                }
            }
            if (ch == '\\' && (single || dbl)) {
                cur.append(ch).append(next);
                i++;
                continue;
            }
            if (ch == '\'' && !dbl && !back) single = !single;
            else if (ch == '"' && !single && !back) dbl = !dbl;
            else if (ch == '`' && !single && !dbl) back = !back;
            cur.append(ch);
        }
        String s = cur.toString().trim();
        if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static String resource(String path) throws IOException {
        InputStream in = DatabaseMigrations.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("missing resource " + path);
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
