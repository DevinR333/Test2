package offline;

import org.junit.Test;
import tools.DatabaseConnection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/** Prepares every literal SQL statement in the server source against the embedded H2 schema. */
public class SqlCompatibilityTest {
    private static final Pattern PREPARE = Pattern.compile("prepareStatement\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*[,)]");

    /** Broken in Cosmic itself (also fail on MySQL) and unused offline: IP bans, website vote rewards. */
    private static final List<String> KNOWN_UPSTREAM = List.of("INSERT INTO ipbans VALUES (DEFAULT, ?)", "bit_votingrecords");

    @Test
    public void allLiteralQueriesPrepareOnH2() throws Exception {
        DatabaseConnection.saveDir = Files.createTempDirectory("maple-sql").toFile();
        DatabaseConnection.initializeConnectionPool();
        database.DatabaseMigrations.runDatabaseMigrations();
        List<String> failures = new ArrayList<>();
        int count = 0;
        try (Connection c = DatabaseConnection.getConnection(); Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Matcher m = PREPARE.matcher(src);
                while (m.find()) {
                    String sql = m.group(1).replace("\\\"", "\"");
                    if (KNOWN_UPSTREAM.stream().anyMatch(sql::contains)) continue;
                    count++;
                    try {
                        c.prepareStatement(sql).close();
                    } catch (SQLException e) {
                        String msg = e.getMessage();
                        failures.add(p.getFileName() + ": " + (msg.length() > 300 ? msg.substring(0, 300) : msg));
                    }
                }
            }
        }
        DatabaseConnection.shutdown();
        System.out.println("Checked " + count + " SQL statements");
        assertTrue(failures.size() + " statements fail on H2:\n" + String.join("\n", failures), failures.isEmpty());
    }
}
