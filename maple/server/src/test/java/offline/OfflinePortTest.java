package offline;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import scripting.jsr.RhinoEngine;
import tools.DatabaseConnection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class OfflinePortTest {

    @Test
    public void databaseSetsUpOnEmbeddedH2() throws Exception {
        File dir = Files.createTempDirectory("maple-db").toFile();
        DatabaseConnection.saveDir = dir;
        assertTrue(DatabaseConnection.initializeConnectionPool());
        database.DatabaseMigrations.runDatabaseMigrations();
        try (Connection c = DatabaseConnection.getConnection()) {
            ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM shopitems");
            rs.next();
            assertTrue("shop items loaded", rs.getInt(1) > 1000);
            rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM drop_data");
            rs.next();
            assertTrue("drops loaded", rs.getInt(1) > 10000);
        }
        // second run is a no-op
        database.DatabaseMigrations.runDatabaseMigrations();
        DatabaseConnection.shutdown();
    }

    @Test
    public void everyScriptParsesWithRhino() throws Exception {
        List<String> failures = new ArrayList<>();
        int count = 0;
        try (Stream<Path> files = Files.walk(Path.of("scripts"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".js"))::iterator) {
                count++;
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Context cx = Context.enter();
                try {
                    cx.setOptimizationLevel(-1);
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    cx.compileString(src, p.toString(), 1, null);
                } catch (EvaluatorException e) {
                    failures.add(p + ": " + e.getMessage());
                } finally {
                    Context.exit();
                }
            }
        }
        assertTrue(count > 1500);
        assertTrue(failures.size() + " scripts fail to parse:\n" + String.join("\n", failures.subList(0, Math.min(40, failures.size()))), failures.isEmpty());
    }

    @Test
    public void javaInteropWorks() throws Exception {
        RhinoEngine e = new RhinoEngine();
        e.eval("var Point = Java.type('java.awt.Point'); var p = new Point(3, 4);"
                + "var Arr = Java.type('compat.awt.Point[]'); var a = Java.to([p, new Point(1,1)], Arr);"
                + "function start(mode, type, sel) { return p.x + p.y + a.length + mode; }");
        Object r = e.invokeFunction("start", (byte) 1, (byte) 0, 0);
        assertEquals(10, ((Number) r).intValue());
    }
}
