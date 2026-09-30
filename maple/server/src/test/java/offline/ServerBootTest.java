package offline;

import net.server.Server;
import org.junit.Assume;
import org.junit.Test;
import provider.DataProviderFactory;
import provider.wz.XMLWZFile;
import tools.DatabaseConnection;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * Boots the whole server against an XML export of the game data (set MAPLE_XML_WZ to its folder).
 * Skipped when no export is available.
 */
public class ServerBootTest {
    @Test(timeout = 600000)
    public void serverBoots() throws Exception {
        String xml = System.getenv("MAPLE_XML_WZ");
        Assume.assumeTrue("no XML export", xml != null && new File(xml).isDirectory());
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        File save = Files.createTempDirectory("maple-save").toFile();
        OfflineServer.start(null, save, null);
        long end = System.currentTimeMillis() + 540000;
        while (!OfflineServer.isOnline() && OfflineServer.failure() == null && System.currentTimeMillis() < end) Thread.sleep(200);
        if (OfflineServer.failure() != null) throw new AssertionError(OfflineServer.failure());
        assertTrue("server online", Server.getInstance().isOnline());
        assertTrue(DatabaseConnection.databaseFile().exists());
    }
}
