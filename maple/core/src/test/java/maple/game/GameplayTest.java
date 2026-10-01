package maple.game;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.graphics.GL20;
import maple.chr.Avatar;
import maple.chr.Player;
import maple.map.Field;
import maple.net.GameClient;
import maple.net.PacketReader;
import maple.net.model.Item;
import maple.input.Pad;
import maple.wz.FolderSource;
import maple.wz.Wz;
import offline.OfflineServer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import provider.DataProviderFactory;
import provider.wz.XMLWZFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Plays the game against the real server without graphics (needs MAPLE_XML_WZ for the server and
 * MAPLE_BIN_WZ for the client): fights monsters, gains EXP, picks up drops and talks to an NPC.
 */
public class GameplayTest {
    static GameClient c;
    static World world;
    static Wz wz;
    static Player player;
    static final List<String> chat = new ArrayList<>();
    static final List<NpcTalk> talks = new ArrayList<>();
    static int[] warp;
    static HeadlessApplication app;
    static final List<String> serverErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeClass
    public static void setUp() throws Exception {
        String xml = System.getenv("MAPLE_XML_WZ"), bin = System.getenv("MAPLE_BIN_WZ");
        Assume.assumeTrue("needs MAPLE_XML_WZ and MAPLE_BIN_WZ", xml != null && !xml.isEmpty() && bin != null && !bin.isEmpty());
        app = new HeadlessApplication(new ApplicationAdapter() {});
        GL20 gl = (GL20) java.lang.reflect.Proxy.newProxyInstance(GameplayTest.class.getClassLoader(), new Class<?>[]{GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        Gdx.gl = Gdx.gl20 = gl;
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        OfflineServer.start(null, Files.createTempDirectory("maple-play").toFile(), null);
        while (!OfflineServer.isOnline()) {
            if (OfflineServer.failure() != null) throw new AssertionError(OfflineServer.failure());
            Thread.sleep(100);
        }
        wz = new Wz(new FolderSource(new File(bin)));
        c = new GameClient();
        c.warpHandler = w -> {
            warp = w;
            if (world != null) world.beginField();
        };
        System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
            final java.io.PrintStream orig = System.err;
            final StringBuilder line = new StringBuilder();
            @Override public void write(int b) {
                orig.write(b);
                if (b == '\n') {
                    String l = line.toString();
                    if (l.contains("Error saving") || l.contains("JdbcSQL")) serverErrors.add(l);
                    line.setLength(0);
                } else line.append((char) b);
            }
        }, true));
        c.start();
        pump(() -> c.state == GameClient.State.CHARACTER_SELECT);
        c.createCharacter("Fighter", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
        pump(() -> !c.characters.isEmpty());
        c.selectCharacter(c.characters.get(0).stats.id);
        pump(() -> c.state == GameClient.State.IN_GAME);
        player = new Player();
        world = new World(wz, c, player);
        world.events = new GameEvents() {
            public void chat(String text, int color) { chat.add(text); }
            public void status(String text, int color) { chat.add(text); }
            public void npcTalk(NpcTalk talk) { talks.add(talk); }
            public void shop(Shop shop) {}
            public void shopResult(int code) {}
            public void refresh() {}
            public void popup(String text) { chat.add("popup: " + text); }
            public void storage(Storage storage) {}
            public void died() {}
            public void keymap(int[] types, int[] actions) {}
        };
        c.inGameHandler = world::handle;
        int[] eq = {1040002, 1060002, 1072001, 1302000};
        player.setAvatar(new Avatar(wz, 0, 20000, 30030, eq));
        world.recomputeStats();
        world.beginField();
        enterMap(c.player.stats.mapId);
        // Send the character to a hunting ground with Blue Snails and two NPCs.
        client.Character chr = net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Fighter");
        warp = null;
        chr.changeMap(100010000);
        pump(() -> warp != null);
        enterMap(warp[0]);
    }

    static void enterMap(int id) throws Exception {
        Field f = new Field(wz, id);
        world.enterField(f);
        world.field = f;
        player.spawn(f.spawnPortal(null).x, f.spawnPortal(null).y - 10);
        c.mapLoaded();
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) step();
    }

    static final Pad idle = new Pad();

    static void step() throws InterruptedException {
        c.update();
        player.update(world.field, idle);
        world.update();
        Thread.sleep(1);
    }

    @AfterClass
    public static void tearDown() {
        if (c != null) c.close();
        OfflineServer.stop();
        if (app != null) app.exit();
    }

    interface Cond { boolean ok(); }

    static void pump(Cond cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (!cond.ok()) {
            c.update();
            if (c.state == GameClient.State.FAILED) fail(c.error);
            if (System.currentTimeMillis() > end) fail("timed out (state " + c.state + ")");
            Thread.sleep(10);
        }
    }

    @Test(timeout = 240000)
    public void huntPickUpAndTalk() throws Exception {
        assertEquals(100010000, world.field.id);
        assertFalse("monsters spawned", world.mobs.isEmpty());
        assertTrue("we control them", world.mobs.values().stream().anyMatch(m -> m.controlled));
        assertFalse("NPCs spawned", world.npcs.isEmpty());

        int expBefore = c.player.stats.exp;
        int levelBefore = c.player.stats.level;
        long end = System.currentTimeMillis() + 120000;
        int kills = 0;
        while (System.currentTimeMillis() < end && (c.player.stats.exp == expBefore && c.player.stats.level == levelBefore)) {
            Mob target = null;
            for (Mob m : world.mobs.values()) if (m.alive()) { target = m; break; }
            if (target == null) { step(); continue; }
            // stand right next to it, facing it
            player.phys.x = target.phys.x + 30;
            player.phys.y = target.phys.y;
            player.facingRight = false;
            sendMove();
            world.attack();
            for (int i = 0; i < 80; i++) step();
            if (!target.alive()) kills++;
        }
        assertTrue("EXP gained (" + chat + ")", c.player.stats.exp > expBefore || c.player.stats.level > levelBefore);

        System.out.println("after kill: drops=" + world.drops.size() + " mobs=" + world.mobs.size() + " chat=" + chat);
        // keep fighting until something drops
        long more = System.currentTimeMillis() + 60000;
        while (world.drops.isEmpty() && System.currentTimeMillis() < more) {
            Mob target = null;
            for (Mob m : world.mobs.values()) if (m.alive()) { target = m; break; }
            if (target == null) { step(); continue; }
            player.phys.x = target.phys.x + 30;
            player.phys.y = target.phys.y;
            player.facingRight = false;
            sendMove();
            world.attack();
            for (int i = 0; i < 80; i++) step();
        }
        assertFalse("something dropped", world.drops.isEmpty());
        // pick up whatever dropped
        int meso = c.player.meso;
        int items = c.player.inventory(2).size() + c.player.inventory(4).size();
        long dropEnd = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < dropEnd && !world.drops.isEmpty()) {
            Drop d = world.drops.values().iterator().next();
            for (int i = 0; i < 200 && d.state != Drop.State.FLOATING; i++) step();
            player.phys.x = d.phys.x;
            player.phys.y = d.phys.y + 4;
            sendMove();
            for (int i = 0; i < 20; i++) step();
            world.pickup();
            for (int i = 0; i < 100; i++) step();
            if (c.player.meso != meso || c.player.inventory(2).size() + c.player.inventory(4).size() != items) break;
        }

        assertTrue("picked something up (" + chat + ")", c.player.meso != meso || c.player.inventory(2).size() + c.player.inventory(4).size() != items);
        // talk to an NPC
        Npc npc = world.npcs.values().iterator().next();
        player.phys.x = npc.x;
        player.phys.y = npc.y;
        sendMove();
        for (int i = 0; i < 20; i++) step();
        world.talkTo(npc);
        long talkEnd = System.currentTimeMillis() + 10000;
        while (talks.isEmpty() && System.currentTimeMillis() < talkEnd) step();
        assertFalse("NPC answered (" + npc.id + ")", talks.isEmpty());
        assertFalse(talks.get(0).text.isEmpty());
        assertTrue("server errors: " + serverErrors, serverErrors.isEmpty());
        System.out.println("kills=" + kills + " exp=" + c.player.stats.exp + " meso " + meso + "->" + c.player.meso
                + " npc says: " + talks.get(0).text + " chat=" + chat);
    }

    static void sendMove() {
        maple.net.PacketWriter w = new maple.net.PacketWriter(net.opcodes.RecvOpcode.MOVE_PLAYER.getValue());
        w.writeBytes(new byte[9]);
        w.writeByte(1).writeByte(0).writeShort((int) player.phys.x).writeShort((int) player.phys.y)
                .writeShort(0).writeShort(0).writeShort(player.phys.fhid).writeByte(4).writeShort(100);
        c.send(w);
    }
}
