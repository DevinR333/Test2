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
public class SocialTest {
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
        GL20 gl = (GL20) java.lang.reflect.Proxy.newProxyInstance(SocialTest.class.getClassLoader(), new Class<?>[]{GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        Gdx.gl = Gdx.gl20 = gl;
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        OfflineServer.start(null, Files.createTempDirectory("maple-social").toFile(), null);
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
        c.createCharacter("Socialite", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
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
        client.Character chr = net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Socialite");
        warp = null;
        chr.changeMap(100000000);
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

    static client.Character server() {
        return net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Socialite");
    }

    static void stepUntil(Cond cond, int maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (!cond.ok() && System.currentTimeMillis() < end) step();
    }

    @Test(timeout = 240000)
    public void partyFriendsGuildMessengerAndCards() throws Exception {
        client.Character chr = server();
        // --- party (beginners can't create one, as in v83)
        for (int i = 0; i < 10; i++) chr.levelUp(false);
        chr.changeJob(client.Job.WARRIOR);
        stepUntil(() -> c.player.stats.job == 100, 5000);
        world.partyCreate();
        stepUntil(() -> world.social.partyId != 0, 5000);
        assertTrue("party created", world.social.partyId != 0);
        assertEquals(1, world.social.party.size());
        assertEquals("Socialite", world.social.party.get(0).name);
        assertNotNull("server party", chr.getParty());
        world.partyLeave();
        stepUntil(() -> world.social.partyId == 0, 5000);
        assertEquals("left the party", 0, world.social.partyId);

        // --- friends: unknown name is refused
        chat.clear();
        world.buddyAdd("NoSuchPerson", "Default Group");
        stepUntil(() -> chat.stream().anyMatch(l -> l.contains("does not exist")), 5000);
        assertTrue("unknown buddy refused: " + chat, chat.stream().anyMatch(l -> l.contains("does not exist")));

        // --- whisper to someone offline
        chat.clear();
        world.chatTo(1, "NoSuchPerson", "hello");
        stepUntil(() -> chat.stream().anyMatch(l -> l.contains("Unable to find")), 5000);
        assertTrue("whisper result: " + chat, chat.stream().anyMatch(l -> l.contains("Unable to find")));

        // --- messenger
        world.messengerOpen();
        stepUntil(() -> world.social.messengerOpen && world.social.seatNames[world.social.messengerSeat] != null, 5000);
        assertTrue("messenger open", world.social.messengerOpen);
        assertEquals("Socialite", world.social.seatNames[world.social.messengerSeat]);
        assertNotNull("own look in the seat", world.social.seatLooks[world.social.messengerSeat]);
        world.messengerLeave();

        // --- monster book card
        chr.getMonsterBook().addCard(chr.getClient(), 2380000);
        stepUntil(() -> c.player.monsterCards.containsKey(2380000), 5000);
        assertEquals("card collected", Integer.valueOf(1), c.player.monsterCards.get(2380000));
        world.setBookCover(2380000);
        stepUntil(() -> c.player.monsterBookCover == 2380000, 5000);
        assertEquals("cover set", 2380000, c.player.monsterBookCover);

        // --- guild, founded alone at the Guild Headquarters
        chr.gainMeso(2000000, false);
        warp = null;
        chr.changeMap(200000301);
        pump(() -> warp != null);
        enterMap(warp[0]);
        world.guildCreate("Lonely");
        stepUntil(() -> world.social.guildId != 0, 10000);
        assertTrue("guild founded: " + chat, world.social.guildId != 0);
        assertEquals("Lonely", world.social.guildName);
        assertTrue(chr.getGuildId() > 0);
    }

    static void sendMove() {
        maple.net.PacketWriter w = new maple.net.PacketWriter(net.opcodes.RecvOpcode.MOVE_PLAYER.getValue());
        w.writeBytes(new byte[9]);
        w.writeByte(1).writeByte(0).writeShort((int) player.phys.x).writeShort((int) player.phys.y)
                .writeShort(0).writeShort(0).writeShort(player.phys.fhid).writeByte(4).writeShort(100);
        c.send(w);
    }
}
