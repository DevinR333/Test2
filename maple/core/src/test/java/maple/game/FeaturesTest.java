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
public class FeaturesTest {
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
        GL20 gl = (GL20) java.lang.reflect.Proxy.newProxyInstance(FeaturesTest.class.getClassLoader(), new Class<?>[]{GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        Gdx.gl = Gdx.gl20 = gl;
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        OfflineServer.start(null, Files.createTempDirectory("maple-feat").toFile(), null);
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
        c.createCharacter("Sitter", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
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
        client.Character chr = net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Sitter");
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
        return net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Sitter");
    }

    static void stepUntil(Cond cond, int maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (!cond.ok() && System.currentTimeMillis() < end) step();
    }

    @Test(timeout = 240000)
    public void chairsMacrosAndStyles() throws Exception {
        assertEquals(100000000, world.field.id);
        client.Character chr = server();

        // --- portable chair
        chr.getAbstractPlayerInteraction().gainItem(3010000, (short) 1);
        stepUntil(() -> c.player.inventory(3).values().stream().anyMatch(it -> it.itemId == 3010000), 5000);
        stepUntil(() -> player.phys.onGround, 3000);
        assertTrue("sat down", world.useChair(3010000));
        stepUntil(() -> chr.getChair() == 3010000, 5000);
        assertEquals("server sees the chair", 3010000, chr.getChair());
        assertEquals("sit stance byte", 20 + (player.facingRight ? 0 : 1), player.stanceByte());
        world.standUp();
        stepUntil(() -> chr.getChair() < 0, 5000);
        assertTrue("stood up", chr.getChair() < 0 && !player.sitting());

        // --- map seat (Henesys has benches)
        assertFalse("map has seats", world.field.seats.isEmpty());
        int[] seat = world.field.seats.get(0);
        player.phys.x = seat[0];
        player.phys.y = seat[1];
        sendMove();
        stepUntil(() -> false, 300);
        player.phys.x = seat[0];
        player.phys.y = seat[1];
        player.phys.onGround = true;
        assertTrue("found the seat", world.sitOnSeat());
        stepUntil(() -> player.seat == 0, 5000);
        assertEquals("server confirmed the seat", 0, player.seat);
        assertEquals(0, chr.getChair());
        world.standUp();
        stepUntil(() -> chr.getChair() < 0, 5000);

        // --- skill macro
        World.SkillMacro m = new World.SkillMacro();
        m.name = "Snails";
        m.skills[0] = 1000;
        world.macros[0] = m;
        world.saveMacros();
        stepUntil(() -> chr.getMacros()[0] != null, 5000);
        assertNotNull("macro saved", chr.getMacros()[0]);
        assertEquals("Snails", chr.getMacros()[0].getName());
        assertEquals(1000, chr.getMacros()[0].getSkill1());

        // --- style picker: Natalie's haircut with a coupon
        chr.getAbstractPlayerInteraction().gainItem(5150001, (short) 1);
        warp = null;
        chr.changeMap(100000104);
        pump(() -> warp != null);
        enterMap(warp[0]);
        Npc natalie = null;
        for (Npc n : world.npcs.values()) if (n.id == 1012103) natalie = n;
        assertNotNull("Natalie is here", natalie);
        talks.clear();
        world.talkTo(natalie);
        stepUntil(() -> !talks.isEmpty(), 10000);
        assertEquals("menu", 4, talks.get(talks.size() - 1).type);
        world.answer(1, 1, null);
        stepUntil(() -> talks.size() >= 2, 10000);
        NpcTalk style = talks.get(talks.size() - 1);
        assertEquals("style picker", 7, style.type);
        assertTrue("styles offered", style.styles.length > 0);
        int want = style.styles[style.styles.length - 1];
        int before = chr.getHair();
        world.answer(1, style.styles.length - 1, null);
        stepUntil(() -> chr.getHair() != before, 10000);
        assertEquals("hair changed to the picked style", want, chr.getHair());
    }

    static void sendMove() {
        maple.net.PacketWriter w = new maple.net.PacketWriter(net.opcodes.RecvOpcode.MOVE_PLAYER.getValue());
        w.writeBytes(new byte[9]);
        w.writeByte(1).writeByte(0).writeShort((int) player.phys.x).writeShort((int) player.phys.y)
                .writeShort(0).writeShort(0).writeShort(player.phys.fhid).writeByte(4).writeShort(100);
        c.send(w);
    }
}
