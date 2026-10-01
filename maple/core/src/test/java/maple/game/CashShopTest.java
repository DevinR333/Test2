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
public class CashShopTest {
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
        GL20 gl = (GL20) java.lang.reflect.Proxy.newProxyInstance(CashShopTest.class.getClassLoader(), new Class<?>[]{GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        Gdx.gl = Gdx.gl20 = gl;
        DataProviderFactory.override = f -> new XMLWZFile(Path.of(xml, f.getBaseName() + ".wz"));
        OfflineServer.start(null, Files.createTempDirectory("maple-cash").toFile(), null);
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
        c.createCharacter("Shopper", 1, 20000, 30030, 0, 0, 1040002, 1060002, 1072001, 1302000, 0);
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
        client.Character chr = net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Shopper");
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
        return net.server.Server.getInstance().getWorld(0).getPlayerStorage().getCharacterByName("Shopper");
    }

    static void stepUntil(Cond cond, int maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (!cond.ok() && System.currentTimeMillis() < end) step();
    }

    @Test(timeout = 240000)
    public void buyTakeOutPutBackAndLeave() throws Exception {
        client.Character chr = server();
        offline.OfflineOptions.freeCashShop = false; // first the original paid shop
        boolean[] opened = {false};
        c.cashShopHandler = () -> opened[0] = true;
        c.enterCashShop();
        stepUntil(() -> opened[0] && c.inCashShop, 10000);
        assertTrue("in the cash shop", c.inCashShop);
        chr.getCashShop().gainCash(1, 20000);
        world.cashCheck();
        stepUntil(() -> world.cash.nxCredit == 20000, 5000);
        assertEquals("NX balance", 20000, world.cash.nxCredit);

        // the first on-sale equip in Commodity.img
        int sn = 0, itemId = 0;
        for (maple.wz.WzNode n : wz.get("Etc/Commodity.img").children()) {
            int id = n.getInt("ItemId", 0);
            if (n.getInt("OnSale", 0) == 1 && id / 1000000 == 1 && n.getInt("Price", 99999) <= 20000
                    && n.getInt("Gender", 2) == 2 && server.CashShop.CashItemFactory.getItem(n.getInt("SN", 0)) != null) {
                sn = n.getInt("SN", 0);
                itemId = id;
                break;
            }
        }
        assertTrue("found an offer", sn != 0);
        int before = world.cash.locker.size();
        world.cashBuy(sn, itemId, 1);
        stepUntil(() -> world.cash.locker.size() > before && world.cash.nxCredit < 20000, 10000);
        assertEquals("bought into the locker", before + 1, world.cash.locker.size());
        assertTrue("NX spent", world.cash.nxCredit < 20000);
        int afterPaid = world.cash.nxCredit;
        long cashId = world.cash.locker.get(world.cash.locker.size() - 1).cashId;
        int bought = itemId;

        world.cashTakeOut(cashId);
        stepUntil(() -> c.player.inventory(1).values().stream().anyMatch(it -> it.itemId == bought), 5000);
        Item taken = null;
        for (Item it : c.player.inventory(1).values()) if (it.itemId == bought) taken = it;
        assertNotNull("taken out into the equip inventory", taken);
        assertTrue("left the locker", world.cash.locker.stream().noneMatch(e -> e.cashId == cashId));

        world.cashPutBack(taken);
        stepUntil(() -> world.cash.locker.stream().anyMatch(e -> e.cashId == cashId), 5000);
        assertTrue("back in the locker", world.cash.locker.stream().anyMatch(e -> e.cashId == cashId));
        assertTrue(c.player.inventory(1).values().stream().noneMatch(it -> it.itemId == bought));

        // the offline option: everything free
        offline.OfflineOptions.freeCashShop = true;
        world.cashCheck();
        stepUntil(() -> world.cash.nxCredit == server.CashShop.FREE_BALANCE, 5000);
        int lockerBefore = world.cash.locker.size();
        world.cashBuy(sn, itemId, 1);
        stepUntil(() -> world.cash.locker.size() > lockerBefore, 10000);
        assertEquals("free purchase arrived", lockerBefore + 1, world.cash.locker.size());
        offline.OfflineOptions.freeCashShop = false;
        assertEquals("no NX was taken", afterPaid, chr.getCashShop().getCash(1));
        offline.OfflineOptions.freeCashShop = true;

        warp = null;
        c.leaveCashShop();
        pump(() -> warp != null);
        assertFalse("back in the game", c.inCashShop);
        enterMap(warp[0]);
        assertNotNull(world.field);
    }

    static int price(int sn) {
        return server.CashShop.CashItemFactory.getItem(sn).getPrice();
    }

    static void sendMove() {
        maple.net.PacketWriter w = new maple.net.PacketWriter(net.opcodes.RecvOpcode.MOVE_PLAYER.getValue());
        w.writeBytes(new byte[9]);
        w.writeByte(1).writeByte(0).writeShort((int) player.phys.x).writeShort((int) player.phys.y)
                .writeShort(0).writeShort(0).writeShort(player.phys.fhid).writeByte(4).writeShort(100);
        c.send(w);
    }
}
