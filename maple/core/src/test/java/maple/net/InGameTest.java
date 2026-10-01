package maple.net;

import offline.OfflineServer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import provider.DataProviderFactory;
import provider.wz.XMLWZFile;
import net.opcodes.RecvOpcode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** In-game protocol against the real server (needs MAPLE_XML_WZ): portals, and later combat, NPCs... */
public class InGameTest {
    static GameClient c;
    static int[] warp;

    @BeforeClass
    public static void enter() throws Exception {
        String xml = System.getenv("MAPLE_XML_WZ");
        Assume.assumeTrue("no XML export", xml != null && !xml.isEmpty() && new File(xml).isDirectory());
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        File save = Files.createTempDirectory("maple-ingame").toFile();
        OfflineServer.start(null, save, null);
        while (!OfflineServer.isOnline()) {
            if (OfflineServer.failure() != null) throw new AssertionError(OfflineServer.failure());
            Thread.sleep(100);
        }
        c = new GameClient();
        c.warpHandler = w -> warp = w;
        c.start();
        waitFor(() -> c.state == GameClient.State.CHARACTER_SELECT);
        c.createCharacter("Mapler", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
        waitFor(() -> !c.characters.isEmpty());
        c.selectCharacter(c.characters.get(0).stats.id);
        waitFor(() -> c.state == GameClient.State.IN_GAME);
        assertEquals(10000, c.player.stats.mapId);
        c.mapLoaded();
    }

    @AfterClass
    public static void leave() {
        if (c != null) c.close();
        OfflineServer.stop();
    }

    static void move(int x, int y) {
        PacketWriter w = new PacketWriter(RecvOpcode.MOVE_PLAYER.getValue());
        w.writeBytes(new byte[9]);
        w.writeByte(1).writeByte(0).writeShort(x).writeShort(y).writeShort(0).writeShort(0).writeShort(0).writeByte(4).writeShort(100);
        c.send(w);
    }

    @Test(timeout = 120000)
    public void pressUpOnPortalChangesMap() throws Exception {
        move(1077, 480);
        Thread.sleep(300);
        warp = null;
        c.send(new PacketWriter(RecvOpcode.CHANGE_MAP.getValue())
                .writeByte(0).writeInt(-1).writeString("out00").writeByte(0).writeByte(0).writeByte(0));
        waitFor(() -> warp != null);
        assertEquals(20000, warp[0]);
    }

    interface Cond { boolean ok(); }

    static void waitFor(Cond cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (!cond.ok()) {
            c.update();
            if (c.state == GameClient.State.FAILED) fail(c.error);
            if (System.currentTimeMillis() > end) fail("timed out (state " + c.state + ")");
            Thread.sleep(20);
        }
    }
}
