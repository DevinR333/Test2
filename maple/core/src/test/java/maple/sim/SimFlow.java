package maple.sim;

import maple.net.GameClient;
import offline.OfflineServer;
import provider.DataProviderFactory;
import provider.wz.XMLWZFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Boot the server, auto-login, create a character and enter the game (run inside AndroidSimLoader). */
public final class SimFlow implements Runnable {
    @Override
    public void run() {
        try {
            String bin = System.getenv("MAPLE_BIN_WZ");
            File save = Files.createTempDirectory("maple-sim").toFile();
            if (bin != null && !bin.isEmpty()) {
                // Same path as the phone: the server reads binary .wz files through the WZ reader.
                OfflineServer.start(new maple.wz.Wz(new maple.wz.FolderSource(new File(bin))), save, null);
            } else {
                String xml = System.getenv("MAPLE_XML_WZ");
                DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
                OfflineServer.start(null, save, null);
            }
            long end = System.currentTimeMillis() + 120000;
            while (!OfflineServer.isOnline()) {
                if (OfflineServer.failure() != null) throw new RuntimeException("server failed", OfflineServer.failure());
                if (System.currentTimeMillis() > end) throw new RuntimeException("server boot timed out");
                Thread.sleep(100);
            }
            GameClient c = new GameClient();
            c.start();
            await(c, GameClient.State.CHARACTER_SELECT);
            c.createCharacter("Mapler", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
            end = System.currentTimeMillis() + 30000;
            while (c.characters.isEmpty()) {
                c.update();
                if (c.error != null) throw new RuntimeException(c.error);
                if (System.currentTimeMillis() > end) throw new RuntimeException("create timed out");
                Thread.sleep(20);
            }
            c.selectCharacter(c.characters.get(0).stats.id);
            await(c, GameClient.State.IN_GAME);
            if (c.player.stats.mapId != 10000) throw new RuntimeException("wrong map " + c.player.stats.mapId);
            // walk around a bit and change map through a portal script-free path is covered elsewhere
            Thread.sleep(1500);
            c.update();
            c.close();
            OfflineServer.stop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void await(GameClient c, GameClient.State s) throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (c.state != s) {
            c.update();
            if (c.state == GameClient.State.FAILED) throw new RuntimeException(c.error);
            if (System.currentTimeMillis() > end) throw new RuntimeException("timed out waiting for " + s + " at " + c.state);
            Thread.sleep(20);
        }
    }
}
