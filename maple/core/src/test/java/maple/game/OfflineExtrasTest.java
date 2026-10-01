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
public class OfflineExtrasTest {
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
        String bin = System.getenv("MAPLE_BIN_WZ");
        Assume.assumeTrue("needs MAPLE_BIN_WZ", bin != null && !bin.isEmpty());
        app = new HeadlessApplication(new ApplicationAdapter() {});
        GL20 gl = (GL20) java.lang.reflect.Proxy.newProxyInstance(OfflineExtrasTest.class.getClassLoader(), new Class<?>[]{GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        Gdx.gl = Gdx.gl20 = gl;
        // the server reads the same binary .wz as the game, as on the phone, so the offline items apply
        wz = new Wz(new FolderSource(new File(bin)));
        OfflineServer.start(wz, Files.createTempDirectory("maple-extras").toFile(), null);
        while (!OfflineServer.isOnline()) {
            if (OfflineServer.failure() != null) throw new AssertionError(OfflineServer.failure());
            Thread.sleep(100);
        }
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
    public void levelUpPotionFromAShop() throws Exception {
        client.Character chr = server();
        Shop[] got = {null};
        world.events = new GameEvents() {
            public void chat(String text, int color) { chat.add(text); }
            public void status(String text, int color) { chat.add(text); }
            public void npcTalk(NpcTalk talk) { talks.add(talk); }
            public void shop(Shop shop) { got[0] = shop; }
            public void shopResult(int code) {}
            public void refresh() {}
            public void popup(String text) { chat.add("popup: " + text); }
            public void storage(Storage storage) {}
            public void died() {}
            public void keymap(int[] types, int[] actions) {}
        };
        // Henesys potion shop (any shop has the potion)
        server.ShopFactory.getInstance().getShopForNPC(1012004).sendShop(chr.getClient());
        stepUntil(() -> got[0] != null, 5000);
        assertNotNull("shop opened", got[0]);
        int index = -1;
        for (int i = 0; i < got[0].items.size(); i++) if (got[0].items.get(i).itemId == offline.OfflineItems.LEVEL_POTION) index = i;
        assertTrue("the potion is in the shop", index >= 0);
        assertEquals("free", 0, got[0].items.get(index).price);
        assertEquals("named", "Level Up Potion", ItemInfo.get(offline.OfflineItems.LEVEL_POTION).name);
        int meso = chr.getMeso();
        world.shopBuy(index, offline.OfflineItems.LEVEL_POTION, 1);
        stepUntil(() -> c.player.inventory(2).values().stream().anyMatch(it -> it.itemId == offline.OfflineItems.LEVEL_POTION), 5000);
        assertEquals("no mesos taken", meso, chr.getMeso());
        world.shopLeave();
        int level = chr.getLevel();
        world.useItemId(offline.OfflineItems.LEVEL_POTION);
        stepUntil(() -> c.player.stats.level == level + 1, 5000);
        assertEquals("one level up", level + 1, chr.getLevel());
        assertEquals("client sees it", level + 1, c.player.stats.level);
        assertTrue("potion used up", c.player.inventory(2).values().stream().noneMatch(it -> it.itemId == offline.OfflineItems.LEVEL_POTION));
    }

    @Test(timeout = 240000)
    public void cashShopExtras() throws Exception {
        boolean[] opened = {false};
        c.cashShopHandler = () -> opened[0] = true;
        c.enterCashShop();
        stepUntil(() -> opened[0] && c.inCashShop, 10000);
        assertTrue("in the cash shop", c.inCashShop);
        world.cashCheck();
        stepUntil(() -> world.cash.nxCredit == server.CashShop.FREE_BALANCE, 5000);

        // Mark of the Beta: added to Equip > Hat, permanent
        server.CashShop.CashItem beta = server.CashShop.CashItemFactory.getItem(20099901);
        assertNotNull("beta bandana offered", beta);
        assertEquals(1002419, beta.getItemId());
        assertTrue(beta.isOnSale());
        int before = world.cash.locker.size();
        world.cashBuy(20099901, 1002419, 1);
        stepUntil(() -> world.cash.locker.size() > before, 10000);
        assertEquals("bought", before + 1, world.cash.locker.size());
        long cashId = world.cash.locker.get(world.cash.locker.size() - 1).cashId;
        world.cashTakeOut(cashId);
        stepUntil(() -> c.player.inventory(1).values().stream().anyMatch(it -> it.itemId == 1002419), 5000);
        Item bandana = null;
        for (Item it : c.player.inventory(1).values()) if (it.itemId == 1002419) bandana = it;
        assertNotNull("Mark of the Beta in the inventory", bandana);
        assertTrue("never expires (" + bandana.expiration + ")", bandana.expiration <= 0 || bandana.expiration > 94354848000000000L);

        // a retired (seasonal) entry is offered with the option on, refused with it off
        int limitedSn = 0, limitedItem = 0;
        for (maple.wz.WzNode n : wz.get("Etc/Commodity.img").children()) {
            int sn = n.getInt("SN", 0);
            server.CashShop.CashItem ci = server.CashShop.CashItemFactory.getItem(sn);
            if (n.getInt("OnSale", 0) == 0 && ci != null && ci.isOnSale() && n.getInt("ItemId", 0) / 1000000 == 1
                    && n.getInt("Gender", 2) == 2 && ItemInfo.named(n.getInt("ItemId", 0))) {
                limitedSn = sn;
                limitedItem = n.getInt("ItemId", 0);
                break;
            }
        }
        assertTrue("found a seasonal item", limitedSn != 0);
        offline.OfflineOptions.limitedCash = false;
        assertFalse(server.CashShop.CashItemFactory.getItem(limitedSn).isOnSale());
        offline.OfflineOptions.limitedCash = true;
        int before2 = world.cash.locker.size();
        world.cashBuy(limitedSn, limitedItem, 1);
        stepUntil(() -> world.cash.locker.size() > before2, 10000);
        assertEquals("seasonal item bought", before2 + 1, world.cash.locker.size());

        // the potion is in the Cash Shop too, free
        server.CashShop.CashItem potion = server.CashShop.CashItemFactory.getItem(30099901);
        assertNotNull(potion);
        assertEquals(offline.OfflineItems.LEVEL_POTION, potion.getItemId());
        assertEquals(0, potion.getPrice());

        warp = null;
        c.leaveCashShop();
        pump(() -> warp != null);
        enterMap(warp[0]);
    }

    @Test(timeout = 60000)
    public void ratesChangeWhilePlaying() {
        client.Character chr = server();
        assertEquals("default EXP", 3, chr.getExpRate());
        offline.OfflineOptions.expRate = 10;
        offline.OfflineOptions.mesoRate = 2;
        OfflineServer.applyRates();
        assertEquals(10, chr.getExpRate());
        assertEquals(2, chr.getMesoRate());
        offline.OfflineOptions.expRate = 3;
        offline.OfflineOptions.mesoRate = 5;
        OfflineServer.applyRates();
        assertEquals(3, chr.getExpRate());
        assertEquals(5, chr.getMesoRate());
    }

    @Test(timeout = 120000)
    public void holidaysAllYear() throws Exception {
        // turkeys roam The Forest of Wisdom, sharing the Slimes' drops
        server.maps.MapleMap forest = net.server.Server.getInstance().getWorld(0).getChannel(1).getMapFactory().getMap(100040100);
        long turkeys = forest.getAllMonsters().stream().filter(m -> m.getId() == 9400505).count();
        assertTrue("turkeys spawned (" + turkeys + ")", turkeys > 0);
        assertFalse("turkeys drop things", server.life.MonsterInformationProvider.getInstance().retrieveDrop(9400505).isEmpty());
        // a 2008 event quest's end date no longer closes it
        server.quest.Quest q = server.quest.Quest.getInstance(9952);
        java.lang.reflect.Field f = server.quest.Quest.class.getDeclaredField("startReqs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<server.quest.QuestRequirementType, server.quest.requirements.AbstractQuestRequirement> reqs =
                (java.util.Map<server.quest.QuestRequirementType, server.quest.requirements.AbstractQuestRequirement>) f.get(q);
        server.quest.requirements.AbstractQuestRequirement end = reqs.get(server.quest.QuestRequirementType.END_DATE);
        assertNotNull("quest 9952 has an end date", end);
        assertTrue("still open", end.check(server(), 9010010));
        offline.OfflineOptions.holidays = false;
        assertFalse("closed in the original game", end.check(server(), 9010010));
        offline.OfflineOptions.holidays = true;
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
