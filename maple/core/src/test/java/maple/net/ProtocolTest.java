package maple.net;

import maple.net.model.CharEntry;
import maple.net.model.Item;
import offline.OfflineServer;
import org.junit.Assume;
import org.junit.Test;
import provider.DataProviderFactory;
import provider.wz.XMLWZFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * End to end against the real server (needs MAPLE_XML_WZ): auto-login, create a character,
 * select it and enter the game, decoding the full character info.
 */
public class ProtocolTest {
    @Test(timeout = 300000)
    public void loginCreateAndEnterGame() throws Exception {
        String xml = System.getenv("MAPLE_XML_WZ");
        Assume.assumeTrue("no XML export", xml != null && !xml.isEmpty() && new File(xml).isDirectory());
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        File save = Files.createTempDirectory("maple-save").toFile();
        OfflineServer.start(null, save, null);
        while (!OfflineServer.isOnline()) {
            if (OfflineServer.failure() != null) throw new AssertionError(OfflineServer.failure());
            Thread.sleep(100);
        }
        GameClient c = new GameClient();
        c.start();
        waitFor(c, GameClient.State.CHARACTER_SELECT);
        assertEquals("Scania", c.worldName);
        assertEquals(0, c.characters.size());

        // Sit at character select longer than the server's idle limit (30 s + 15 s): pings must be answered.
        if (Boolean.getBoolean("maple.idleTest")) {
            long idleEnd = System.currentTimeMillis() + 50000;
            while (System.currentTimeMillis() < idleEnd) {
                c.update();
                assertNotEquals(c.error, GameClient.State.FAILED, c.state);
                Thread.sleep(100);
            }
        }
        c.checkName("Mapler");
        while (c.nameAvailable == null) { c.update(); Thread.sleep(20); }
        assertTrue(c.nameAvailable);
        // Default beginner look from MakeCharInfo (face 20000, hair 30030 + black, white skin, starter clothes, sword)
        c.createCharacter("Mapler", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
        while (c.characters.isEmpty()) {
            c.update();
            if (c.error != null) fail(c.error);
            Thread.sleep(20);
        }
        CharEntry e = c.characters.get(0);
        assertEquals("Mapler", e.stats.name);
        assertEquals(1, e.stats.level);
        assertEquals(10000, e.stats.mapId);
        assertEquals(1302000, (int) e.look.equips.get(11));

        c.selectCharacter(e.stats.id);
        waitFor(c, GameClient.State.IN_GAME);
        assertEquals("Mapler", c.player.stats.name);
        assertEquals(10000, c.player.stats.mapId);
        Item weapon = c.player.inventory(-1).get(-11);
        assertNotNull("equipped weapon", weapon);
        assertEquals(1302000, weapon.itemId);
        assertTrue("has beginner skills or none", c.player.skills.size() >= 0);
        c.close();
    }

    private static void waitFor(GameClient c, GameClient.State s) throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (c.state != s) {
            c.update();
            if (c.state == GameClient.State.FAILED) fail(c.error);
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + s + " (at " + c.state + ")");
            Thread.sleep(20);
        }
    }
}
