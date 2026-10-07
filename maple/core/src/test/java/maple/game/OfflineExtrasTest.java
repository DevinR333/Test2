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
                    if (l.contains("Error saving") || l.contains("JdbcSQL") || l.contains("Exception") || l.contains("ERROR")) serverErrors.add(l);
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

    /** Cash Shop packages: buying one fills the locker with its items, and each comes out into the bag. */
    @Test(timeout = 1800000)
    public void cashPackages() throws Exception {
        boolean[] opened = {false};
        c.cashShopHandler = () -> opened[0] = true;
        c.enterCashShop();
        stepUntil(() -> opened[0] && c.inCashShop, 10000);
        world.cashCheck();
        stepUntil(() -> world.cash.nxCredit > 0, 5000);
        java.util.List<int[]> packs = new java.util.ArrayList<>();
        for (maple.wz.WzNode n : wz.get("Etc/Commodity.img").children()) {
            int sn = n.getInt("SN", 0), id = n.getInt("ItemId", 0);
            server.CashShop.CashItem ci = server.CashShop.CashItemFactory.getItem(sn);
            if (id / 10000 == 910 && ci != null && ci.isOnSale()) packs.add(new int[]{sn, id});
        }
        System.out.println("packages offered: " + packs.size());
        assertTrue(packs.size() > 20);
        int equips = 0;
        for (int[] p : packs) {
                        java.util.List<?> contents = server.CashShop.CashItemFactory.getPackage(p[1]);
            assertFalse("package " + p[1] + " has contents", contents.isEmpty());
            ItemInfo pi = ItemInfo.get(p[1]);
            assertTrue("package " + p[1] + " named: " + pi.name, !pi.name.isEmpty() && !pi.name.equals("None") && !QuestBook.korean(pi.name));
            assertTrue("package " + p[1] + " has an icon", pi.icon().exists());
            assertEquals("package " + p[1] + " lists its items", contents.size(), ItemInfo.packageContents(p[1]).size());
            for (int[] in : ItemInfo.packageContents(p[1])) assertTrue("content " + in[0] + " named", ItemInfo.named(in[0]));
            int before = world.cash.locker.size();
            world.cashBuy(p[0], p[1], 1);
            stepUntil(() -> world.cash.locker.size() >= before + contents.size(), 10000);
            assertEquals("package " + p[1] + " bought", before + contents.size(), world.cash.locker.size());
            java.util.List<CashShopState.Entry> got = new java.util.ArrayList<>(world.cash.locker.subList(before, world.cash.locker.size()));
            for (CashShopState.Entry e : got) {
                assertNotEquals("no package item itself in the locker", 910, e.itemId / 10000);
                int type = ItemInfo.inventoryType(e.itemId);
                if (c.player.inventory(type).size() >= c.player.slotLimits[type]) { // a full bag says so
                    world.cash.message = null;
                    world.cashTakeOut(e.cashId);
                    stepUntil(() -> world.cash.message != null, 5000);
                    assertEquals("Your inventory is full.", world.cash.message);
                    int slots = c.player.slotLimits[type];
                    world.cashExpand(type, 1); // make room and carry on
                    stepUntil(() -> c.player.slotLimits[type] > slots, 5000);
                    assertTrue("bag expanded", c.player.slotLimits[type] > slots);
                }
                long n0 = c.player.inventory(type).values().stream().filter(it -> it.itemId == e.itemId).count();
                world.cashTakeOut(e.cashId);
                stepUntil(() -> c.player.inventory(type).values().stream().filter(it -> it.itemId == e.itemId).count() > n0, 5000);
                assertTrue("took out " + e.itemId + " from package " + p[1],
                        c.player.inventory(type).values().stream().filter(it -> it.itemId == e.itemId).count() > n0);
                assertFalse("left the locker", world.cash.locker.stream().anyMatch(x -> x.cashId == e.cashId));
                Item out = null;
                for (Item it : c.player.inventory(type).values()) if (it.cashId == e.cashId) out = it;
                assertNotNull("same item in the bag", out);
                world.cashPutBack(out); // and back, keeping the bag from filling up
                stepUntil(() -> world.cash.locker.stream().anyMatch(x -> x.cashId == e.cashId), 5000);
                assertTrue("put back " + e.itemId, world.cash.locker.stream().anyMatch(x -> x.cashId == e.cashId));
                if (type == 1) equips++;
            }
        }
        assertTrue("equips came out: " + equips, equips > 300);
        warp = null;
        c.leaveCashShop();
        pump(() -> warp != null);
        enterMap(warp[0]);
    }

    /** A cash weapon cover goes on at -111 over the real weapon, which stays, and is drawn with art for that weapon type. */
    @Test(timeout = 120000)
    public void weaponCover() throws Exception {
        client.Character chr = server();
        client.inventory.Inventory worn = chr.getInventory(client.inventory.InventoryType.EQUIPPED);
        if (worn.getItem((short) -11) == null) {
            if (firstOf(1, 1302000) == null) give(1302000, 1);
            world.equip(firstOf(1, 1302000).position);
            stepUntil(() -> worn.getItem((short) -11) != null, 5000);
        }
        int weapon = worn.getItem((short) -11).getItemId();
        give(1702119, 1);
        assertEquals("a cover is a weapon-slot item", -11, ItemInfo.equipSlot(1702119));
        assertFalse(ItemInfo.get(1702119).name.isEmpty());
        world.equip(firstOf(1, 1702119).position);
        stepUntil(() -> worn.getItem((short) -111) != null, 5000);
        assertEquals("cover worn at -111", 1702119, worn.getItem((short) -111).getItemId());
        assertEquals("weapon still worn", weapon, worn.getItem((short) -11).getItemId());
        maple.chr.Avatar a = new maple.chr.Avatar(wz, 0, 20000, 30000, new int[]{1040002, weapon, 1702119});
        assertEquals("cover art drawn", 1702119, a.coverDrawn);
        assertEquals("", a.problems);
        assertNotNull(a.frames("stand1"));
        a.dispose();
    }

    /**
     * Ultimate Explorers: the data is there for game and server, a level-120 knight takes Empress's
     * Grace from Cygnus and turns in 10 Peridots for Shout and Prayer, the set effects count on both
     * sides, Cygnus makes the Ultimate Explorer once (level 50, 2nd job, the Fine Set worn, the medal
     * with the knight's name, Empress's Might), and Might and the Brilliant Set work.
     */
    @Test(timeout = 300000)
    public void ultimateExplorer() throws Exception {
        final int Q = offline.UltimateExplorer.QUEST_GRACE, CYG = offline.UltimateExplorer.CYGNUS;
        server.ItemInformationProvider ii = server.ItemInformationProvider.getInstance();
        // data, on both sides
        assertEquals("Peridot", ItemInfo.get(offline.UltimateExplorer.PERIDOT).name);
        assertTrue(ItemInfo.get(offline.UltimateExplorer.PERIDOT).icon().exists());
        for (int[] j : offline.UltimateExplorer.JOBS) {
            for (int id : new int[]{j[1], j[2]}) {
                assertTrue(id + " named", ItemInfo.get(id).name.startsWith("Empress's "));
                assertTrue(id + " has art", ItemInfo.get(id).icon().exists());
                assertNotNull(id + " on the server", ii.getEquipById(id));
                assertEquals(id + " level", ItemInfo.get(id).id / 1 > 0 ? (offline.UltimateExplorer.isFine(id) ? 60 : 80) : 0, ItemInfo.get(id).reqLevel);
            }
        }
        for (int id : new int[]{offline.UltimateExplorer.FINE_HAT, offline.UltimateExplorer.FINE_ROBE, offline.UltimateExplorer.FINE_GLOVES,
                offline.UltimateExplorer.FINE_SHOES, offline.UltimateExplorer.BRILLIANT_HAT, offline.UltimateExplorer.BRILLIANT_ROBE,
                offline.UltimateExplorer.BRILLIANT_GLOVES, offline.UltimateExplorer.BRILLIANT_SHOES, offline.UltimateExplorer.MEDAL}) {
            assertFalse(id + " named", ItemInfo.get(id).name.isEmpty());
            assertTrue(id + " art", ItemInfo.get(id).icon().exists());
            assertNotNull("server " + id, ii.getEquipById(id));
        }
        assertEquals(7, ii.getEquipById(offline.UltimateExplorer.FINE_HAT) instanceof client.inventory.Equip
                ? ((client.inventory.Equip) ii.getEquipById(offline.UltimateExplorer.FINE_HAT)).getStr() : -1);
        assertNotNull(client.SkillFactory.getSkill(offline.UltimateExplorer.SHOUT));
        assertNotNull(client.SkillFactory.getSkill(offline.UltimateExplorer.PRAYER));
        assertNotNull(client.SkillFactory.getSkill(offline.UltimateExplorer.MIGHT));
        assertEquals(4, client.SkillFactory.getSkill(offline.UltimateExplorer.PRAYER).getEffect(1).getX());
        assertTrue("Peridots drop from Harps during the quest", server.life.MonsterInformationProvider.getInstance()
                .retrieveDrop(offline.UltimateExplorer.HARP).stream().anyMatch(e -> e.itemId == offline.UltimateExplorer.PERIDOT && e.questid == Q));

        // a level-120 Dawn Warrior in Ereve
        client.Character chr = server();
        chr.changeJob(client.Job.DAWNWARRIOR3);
        while (chr.getLevel() < 120) chr.levelUp(false);
        stepUntil(() -> c.player.stats.level == 120 && c.player.stats.job == 1111, 5000);
        warp = null;
        chr.changeMap(130000000);
        pump(() -> warp != null);
        enterMap(warp[0]);
        stepUntil(() -> npc(CYG) != null, 5000);
        assertNotNull("Cygnus is here", npc(CYG));
        assertTrue("Empress's Grace offered", world.quests.startable(CYG).contains(Q));
        player.spawn(npc(CYG).x, npc(CYG).y - 10);
        for (int i = 0; i < 50; i++) step();
        sendMove();
        for (int i = 0; i < 50; i++) step();
        talkThrough(npc(CYG), "Empress's Grace");
        stepUntil(() -> world.quests.state(Q) == 1, 5000);
        assertEquals("started", 1, world.quests.state(Q));
        assertFalse("not ready without Peridots", world.quests.ready(Q));
        give(offline.UltimateExplorer.PERIDOT, 10);
        Thread.sleep(600);
        assertTrue("ready with 10", world.quests.ready(Q));
        int hpBefore = chr.getCurrentMaxHp();
        talkThrough(npc(CYG), "Empress's Grace");
        stepUntil(() -> world.quests.state(Q) == 2, 5000);
        assertEquals("completed", 2, world.quests.state(Q));
        stepUntil(() -> c.player.skills.containsKey(offline.UltimateExplorer.SHOUT), 5000);
        assertTrue("Empress's Shout learned", c.player.skills.containsKey(offline.UltimateExplorer.SHOUT));
        assertTrue("Empress's Prayer learned", c.player.skills.containsKey(offline.UltimateExplorer.PRAYER));
        assertNull("Peridots taken", firstOf(4, offline.UltimateExplorer.PERIDOT));
        assertEquals("Shout: max HP +20%", hpBefore + hpBefore / 5, chr.getCurrentMaxHp(), 2);
        assertEquals("client agrees", chr.getCurrentMaxHp(), world.stats.maxHp, 2);

        // set effect: the whole Fine Set worn
        for (int id : offline.UltimateExplorer.fineSet(1111)) {
            give(id, 1);
            Thread.sleep(350); // the server ignores item moves under 300 ms apart
            world.equip(firstOf(1, id).position);
            stepUntil(() -> chr.getInventory(client.inventory.InventoryType.EQUIPPED).findById(id) != null, 5000);
            assertNotNull("wearing " + id + " bag " + c.player.inventory(1).size() + " chat " + chat, chr.getInventory(client.inventory.InventoryType.EQUIPPED).findById(id));
        }
        int itemStr = 0;
        for (client.inventory.Item it : chr.getInventory(client.inventory.InventoryType.EQUIPPED)) itemStr += ((client.inventory.Equip) it).getStr();
        java.util.List<Integer> wornIds = new java.util.ArrayList<>();
        for (client.inventory.Item it : chr.getInventory(client.inventory.InventoryType.EQUIPPED)) wornIds.add(it.getItemId());
        assertEquals("5-piece set: STR +6 on the server " + wornIds + " " + java.util.Arrays.toString(offline.UltimateExplorer.setBonus(wornIds)), chr.getStr() + itemStr + 6, chr.getTotalStr());
        for (int i = 0; i < 20; i++) step();
        assertEquals("and in the game's stat window", chr.getTotalStr(), world.stats.str);

        // Cygnus makes the Ultimate Explorer through the creation screen
        assertEquals(1, offline.UltimateExplorer.knightState(chr));
        world.talkTo(npc(CYG)); // no quests left with her: straight to her own words
        NpcTalk menu = world.talk;
        if (menu != null && menu.local != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("#L(\\d+)#Talk to").matcher(menu.text);
            assertTrue(menu.text, m.find());
            world.answer(1, Integer.parseInt(m.group(1)), null);
        }
        stepUntil(() -> world.talk != null && world.talk.type == 1, 5000);
        assertEquals("asks first", 1, world.talk.type);
        world.answer(1, 0, null);
        stepUntil(() -> world.talk != null && world.talk.type == 2, 5000);
        assertTrue("opens the creation screen", world.talk.text.startsWith(offline.UltimateExplorer.CREATOR));
        world.answer(1, 0, "Shopper|110|20000|30000|0|0|0"); // a taken name
        stepUntil(() -> world.talk != null && world.talk.type == 2 && world.talk.text.length() > offline.UltimateExplorer.CREATOR.length(), 5000);
        assertTrue("name taken: asked again", world.talk.text.contains("name"));
        world.answer(1, 0, "Successor1|110|20000|30000|0|0|0");
        stepUntil(() -> world.talk != null && world.talk.type == 0, 5000);
        assertTrue(world.talk.text, world.talk.text.contains("Successor1"));
        world.answer(0, 0, null);
        assertEquals("one per knight", 2, offline.UltimateExplorer.knightState(chr));
        assertEquals(-4, offline.UltimateExplorer.create(chr.getClient(), chr, "Successor2", 110, 20000, 30000, 0, 0));
        int[] made = ultimate("Successor1");
        assertNotNull("saved", made);
        assertEquals("level 50", 50, made[0]);
        assertEquals("Fighter", 110, made[1]);
        java.util.Set<Integer> worn = new java.util.HashSet<>();
        String medalOwner = null;
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement("SELECT i.itemid, i.position, i.owner FROM inventoryitems i WHERE i.characterid = ? AND i.position < 0")) {
            ps.setInt(1, made[2]);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    worn.add(rs.getInt(1));
                    if (rs.getInt(1) == offline.UltimateExplorer.MEDAL) medalOwner = rs.getString(3);
                }
            }
        }
        for (int id : offline.UltimateExplorer.fineSet(110)) assertTrue("wears " + id + ": " + worn, worn.contains(id));
        assertTrue("medal worn", worn.contains(offline.UltimateExplorer.MEDAL));
        assertEquals("medal carries the knight's name", "Shopper", medalOwner);
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement("SELECT skilllevel FROM skills WHERE characterid = ? AND skillid = ?")) {
            ps.setInt(1, made[2]);
            ps.setInt(2, offline.UltimateExplorer.MIGHT);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue("Empress's Might", rs.next() && rs.getInt(1) == 1);
            }
        }

        // Might: items up to 10 levels above (tried on this character for the moment), and the Brilliant Set at 70+
        client.inventory.Equip hat = (client.inventory.Equip) ii.getEquipById(offline.UltimateExplorer.FINE_HAT); // level 60
        chr.setLevel(55);
        boolean without = ii.canWearEquipment(chr, hat, -1);
        chr.changeSkillLevel(client.SkillFactory.getSkill(offline.UltimateExplorer.MIGHT), (byte) 1, 1, -1);
        assertTrue("Empress's Might", offline.UltimateExplorer.isUltimate(chr));
        assertFalse("a level-60 hat is too high at 55", without);
        assertTrue("Might: 10 levels higher", ii.canWearEquipment(chr, hat, -1));
        chr.setLevel(120);
        assertEquals(0, offline.UltimateExplorer.upgrade(chr));
        for (int id : offline.UltimateExplorer.brilliantSet(1111)) {
            int bid = id;
            stepUntil(() -> firstOf(1, bid) != null, 5000);
            assertNotNull("Brilliant " + id, firstOf(1, id));
        }
        assertEquals("only once", -3, offline.UltimateExplorer.upgrade(chr));
        chr.changeSkillLevel(client.SkillFactory.getSkill(offline.UltimateExplorer.MIGHT), (byte) 0, 0, -1);
    }

    /** {level, job, id} of a saved character, or null. */
    static int[] ultimate(String name) throws Exception {
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement("SELECT level, job, id FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)} : null;
            }
        }
    }

    /** Uses a Cash item through its dialogue: picks menu entry `pick` (or types `text`) at each step. */
    static void useCash(int itemId, Object... answers) throws InterruptedException {
        Item it = firstOf(5, itemId);
        assertNotNull("have " + itemId, it);
        Thread.sleep(3900); // the server allows one cash item every 3 seconds (its clock moves in 777 ms steps)
        world.useCashItem(it.position);
        for (Object a : answers) {
            NpcTalk t = world.talk;
            assertNotNull(itemId + ": a prompt", t);
            if (a instanceof String) world.answer(1, 0, (String) a);
            else world.answer(1, (Integer) a, null);
        }
        for (int i = 0; i < 100; i++) step();
    }

    /** Item Tag, Item Guard, Incubator, Scissors of Karma, Vicious' Hammer, Vega's Spell, Note, Name Change cancel. */
    @Test(timeout = 180000)
    public void cashItemsWithTargets() throws Exception {
        client.Character chr = server();
        client.inventory.Inventory equipped = chr.getInventory(client.inventory.InventoryType.EQUIPPED);
        client.inventory.Inventory bag = chr.getInventory(client.inventory.InventoryType.EQUIP);
        // Item Tag: your name on a worn item
        if (equipped.getItem((short) -11) == null) {
            give(1302000, 1);
            Thread.sleep(350);
            world.equip(firstOf(1, 1302000).position);
            stepUntil(() -> equipped.getItem((short) -11) != null, 5000);
        }
        give(5060000, 1);
        int before = (int) data().inventory(-1).values().stream().filter(i -> i.position > -100 && i.owner.isEmpty()).count();
        useCash(5060000, 0);
        stepUntil(() -> equipped.list().stream().anyMatch(i -> chr.getName().equals(i.getOwner())), 5000);
        assertTrue("Item Tag: a worn item carries the name", equipped.list().stream().anyMatch(i -> chr.getName().equals(i.getOwner())));
        assertNull("used up", firstOf(5, 5060000));
        // Item Guard: a permanent equip gets locked
        give(1302000, 1);
        give(5060001, 1);
        useCash(5060001, 0);
        stepUntil(() -> bag.list().stream().anyMatch(i -> (i.getFlag() & 0x01) != 0), 5000);
        StringBuilder dbg = new StringBuilder();
        for (client.inventory.Item i : bag.list()) dbg.append(i.getItemId()).append('@').append(i.getPosition()).append(" exp ").append(i.getExpiration()).append(" flag ").append(i.getFlag()).append("; ");
        for (Item i : data().inventory(1).values()) dbg.append("client ").append(i.itemId).append('@').append(i.position).append(" exp ").append(i.expiration).append("; ");
        assertTrue("Item Guard: locked " + dbg + " cash left " + firstOf(5, 5060001), bag.list().stream().anyMatch(i -> (i.getFlag() & 0x01) != 0));
        // Incubator: a Pigmy Egg hatches into something
        give(4170000, 1);
        give(5060002, 1);
        chat.clear();
        useCash(5060002, 0);
        stepUntil(() -> firstOf(4, 4170000) == null, 5000);
        assertNull("egg used", firstOf(4, 4170000));
        assertTrue("hatched: " + chat, chat.stream().anyMatch(m -> m.startsWith("The egg hatched")));
        // Scissors of Karma: an item that allows it becomes tradeable once
        give(1002357, 1);
        give(5520000, 1);
        assertTrue(ItemInfo.get(1002357).info.getInt("tradeAvailable", 0) > 0);
        int karmaMenu = 0;
        java.util.List<Item> karmaable = new java.util.ArrayList<>();
        for (int t = 1; t <= 4; t++) for (Item i : data().inventory(t).values()) if (ItemInfo.get(i.itemId).info.getInt("tradeAvailable", 0) > 0) karmaable.add(i);
        for (int k = 0; k < karmaable.size(); k++) if (karmaable.get(k).itemId == 1002357) karmaMenu = k;
        useCash(5520000, karmaMenu);
        stepUntil(() -> (bag.findById(1002357).getFlag() & 0x10) != 0, 5000);
        assertTrue("Karma: tradeable once", (bag.findById(1002357).getFlag() & 0x10) != 0);
        // Vicious' Hammer: one more slot
        client.inventory.Equip sword = (client.inventory.Equip) bag.findById(1302000);
        int slots = sword.getUpgradeSlots();
        give(5570000, 1);
        java.util.List<Item> hammerable = new java.util.ArrayList<>();
        for (Item i : data().inventory(1).values()) if (i.vicious < 2 && !ItemInfo.get(i.itemId).cash && ItemInfo.get(i.itemId).tuc > 0) hammerable.add(i);
        int hm = 0;
        for (int k = 0; k < hammerable.size(); k++) if (hammerable.get(k).position == sword.getPosition()) hm = k;
        chat.clear();
        useCash(5570000, hm);
        stepUntil(() -> sword.getVicious() == 1, 5000);
        assertEquals("hammered once", 1, sword.getVicious());
        assertEquals("one more slot", slots + 1, sword.getUpgradeSlots());
        // Vega's Spell (10%) with a 10% hat scroll on a hat
        give(1002001, 1);
        give(2040002, 1);
        give(5610000, 1);
        assertTrue("hat has slots", ((client.inventory.Equip) bag.findById(1002001)).getUpgradeSlots() > 0);
        chat.clear();
        useCash(5610000, 0, 0);
        stepUntil(() -> firstOf(2, 2040002) == null && chat.stream().anyMatch(m -> m.contains("Vega's Spell")), 8000);
        assertNull("scroll used", firstOf(2, 2040002));
        assertTrue("result shown: " + chat, chat.stream().anyMatch(m -> m.contains("Vega's Spell")));
        // a Note to a character
        give(5090000, 1);
        useCash(5090000, chr.getName(), "hello");
        stepUntil(() -> firstOf(5, 5090000) == null, 5000);
        assertNull("note sent", firstOf(5, 5090000));
    }

    static maple.net.model.PlayerData data() {
        return c.player;
    }

    /** Maple Life makes a Lv. 30 character, the slot coupon adds a slot, the Wheel of Destiny revives on the spot. */
    @Test(timeout = 120000)
    public void mapleLifeSlotAndWheel() throws Exception {
        client.Character chr = server();
        while (chr.getLevel() < 30) chr.levelUp(false);
        stepUntil(() -> c.player.stats.level >= 30, 5000);
        // Extra Character Slot Coupon
        give(5430000, 1);
        chat.clear();
        useCash(5430000, 0);
        stepUntil(() -> firstOf(5, 5430000) == null, 5000);
        assertNull("coupon used", firstOf(5, 5430000));
        assertTrue(chat.toString(), chat.stream().anyMatch(m -> m.contains("character slots have been increased")));
        // Maple Life (A-Type): a Lv. 30 Magician
        give(5431000, 1);
        chat.clear();
        Thread.sleep(3900);
        world.mapleLife(firstOf(5, 5431000).position, 5431000, "LifeMage1", 20000, 30000, 0, 0, 0, 1);
        stepUntil(() -> chat.stream().anyMatch(m -> m.contains("new character has been created")), 8000);
        assertTrue(chat.toString(), chat.stream().anyMatch(m -> m.contains("new character has been created")));
        int[] made = ultimate("LifeMage1");
        assertNotNull(made);
        assertEquals(30, made[0]);
        assertEquals(200, made[1]);
        assertNull("used up", firstOf(5, 5431000));
        // Wheel of Destiny: die, revive where you fell
        give(5510000, 2);
        int map = chr.getMapId();
        chr.updateHp(0);
        stepUntil(() -> !chr.isAlive(), 5000);
        warp = null;
        world.revive(true);
        stepUntil(() -> chr.isAlive(), 5000);
        assertTrue("alive again", chr.isAlive());
        assertEquals("same map", map, chr.getMapId());
        stepUntil(() -> firstOf(5, 5510000) != null && firstOf(5, 5510000).quantity == 1, 5000);
        assertEquals("one wheel used", 1, firstOf(5, 5510000).quantity);
        if (warp != null) enterMap(warp[0]);
    }

    /** A quiz quest (Rain's Maple Quiz 1): the choices can be picked; a wrong one is answered, the right one completes it. */
    @Test(timeout = 120000)
    public void quizQuestChoices() throws Exception {
        final int Q = 1009, RAIN = 12101;
        client.Character chr = server();
        warp = null;
        chr.changeMap(1000000);
        pump(() -> warp != null);
        enterMap(warp[0]);
        stepUntil(() -> npc(RAIN) != null, 5000);
        server.quest.Quest.getInstance(Q).forceStart(chr, RAIN);
        stepUntil(() -> world.quests.state(Q) == 1, 5000);
        assertEquals(1, world.quests.state(Q));
        player.spawn(npc(RAIN).x, npc(RAIN).y - 10);
        for (int i = 0; i < 50; i++) step();
        sendMove();
        for (int i = 0; i < 50; i++) step();
        // wrong answer: K
        openQuest(npc(RAIN), world.quests.name(Q));
        stepUntil(() -> world.talk != null && world.talk.type == 4, 3000);
        assertNotNull("the question", world.talk);
        assertEquals("a menu: the choices can be picked", 4, world.talk.type);
        assertTrue(world.talk.text, world.talk.text.contains("#L1#"));
        world.answer(1, 1, null);
        assertNotNull("Rain answers the wrong choice", world.talk);
        assertTrue(world.talk.text, world.talk.text.startsWith("K is for the Skill Window"));
        world.answer(1, 0, null);
        for (int i = 0; i < 50; i++) step();
        assertEquals("still in progress", 1, world.quests.state(Q));
        // right answer: I
        openQuest(npc(RAIN), world.quests.name(Q));
        stepUntil(() -> world.talk != null && world.talk.type == 4, 3000);
        world.answer(1, 0, null);
        assertNotNull(world.talk);
        assertTrue(world.talk.text, world.talk.text.startsWith("That's right"));
        assertTrue("an OK to finish", !world.talk.next);
        world.answer(1, 0, null);
        stepUntil(() -> world.quests.state(Q) == 2, 5000);
        assertEquals("completed", 2, world.quests.state(Q));
    }

    static Npc npc(int id) {
        for (Npc n : world.npcs.values()) if (n.id == id) return n;
        return null;
    }

    /** Taps through a client-side quest conversation: picks menu entry `choice`, then Next/Accept/OK. */
    /** Picks the quest in the NPC's quest menu and stops there. */
    static void openQuest(Npc n, String questName) {
        world.talkTo(n);
        NpcTalk menu = world.talk;
        assertNotNull("quest menu shown", menu);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#L(\\d+)##b" + java.util.regex.Pattern.quote(questName)).matcher(menu.text);
        assertTrue("quest listed: " + menu.text, m.find());
        world.answer(1, Integer.parseInt(m.group(1)), null);
    }

    static void talkThrough(Npc n, String questName) throws InterruptedException {
        world.talkTo(n);
        NpcTalk menu = world.talk;
        assertNotNull("quest menu shown", menu);
        assertNotNull("answered on the client", menu.local);
        assertEquals(4, menu.type);
        // the menu entry for the quest: "#L<n>##b<name>"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#L(\\d+)##b" + java.util.regex.Pattern.quote(questName)).matcher(menu.text);
        assertTrue("quest listed: " + menu.text, m.find());
        world.answer(1, Integer.parseInt(m.group(1)), null);
        for (int i = 0; i < 30 && world.talk != null && world.talk.local != null; i++) world.answer(1, 0, null);
    }

    @Test(timeout = 120000)
    public void questBulbsStartAndComplete() throws Exception {
        int q = 4437, starter = 9250052, rooney = 1022101;
        stepUntil(() -> npc(starter) != null && npc(rooney) != null, 5000);
        assertTrue("quest offered", world.quests.startable(starter).contains(q));
        assertEquals("bulb over the NPC", 0, world.quests.marker(starter));
        // stand next to the NPC (the server only accepts quests from NPCs within about a screen)
        player.spawn(npc(starter).x, npc(starter).y - 10);
        for (int i = 0; i < 50; i++) step();
        sendMove();
        for (int i = 0; i < 50; i++) step();
        talkThrough(npc(starter), world.quests.name(q));
        stepUntil(() -> world.quests.state(q) == 1, 5000);
        assertEquals("the server started it", 1, world.quests.state(q));
        Thread.sleep(600); // markers refresh twice a second
        assertTrue("marker over Rooney", world.quests.marker(rooney) >= 1);
        assertTrue(world.quests.finishing(rooney).contains(q));
        if (world.quests.ready(q)) {
            player.spawn(npc(rooney).x, npc(rooney).y - 10);
            for (int i = 0; i < 50; i++) step();
            sendMove();
            for (int i = 0; i < 50; i++) step();
            talkThrough(npc(rooney), world.quests.name(q));
            stepUntil(() -> world.quests.state(q) == 2, 5000);
            assertEquals("the server completed it", 2, world.quests.state(q));
        }
    }

    @Test(timeout = 120000)
    public void cashItemsSellAtShops() throws Exception {
        client.Character chr = server();
        // a cash hat on sale in the Cash Shop
        int hat = 0;
        for (maple.wz.WzNode n : wz.get("Etc/Commodity.img").children()) {
            int id = n.getInt("ItemId", 0);
            if (n.getInt("OnSale", 0) == 1 && id / 10000 == 100 && n.getInt("Price", 0) > 0
                    && server.ItemInformationProvider.getInstance().isCash(id)) {
                hat = id;
                break;
            }
        }
        assertTrue("found a cash hat", hat != 0);
        int price = offline.OfflineItems.sellPrice(hat, 0);
        assertTrue("cash items have a selling price (" + price + ")", price >= 1000);
        client.inventory.manipulator.InventoryManipulator.addById(chr.getClient(), hat, (short) 1, "", -1);
        int id = hat;
        stepUntil(() -> c.player.inventory(1).values().stream().anyMatch(it -> it.itemId == id), 5000);
        Item item = null;
        for (Item it : c.player.inventory(1).values()) if (it.itemId == hat) item = it;
        assertNotNull("got the hat", item);
        server.ShopFactory.getInstance().getShopForNPC(1012004).sendShop(chr.getClient());
        stepUntil(() -> chr.getShop() != null, 5000);
        int meso = chr.getMeso();
        world.shopSell(item.position, hat, 1);
        stepUntil(() -> c.player.inventory(1).values().stream().noneMatch(it -> it.itemId == id), 5000);
        assertEquals("paid for the cash item", meso + price, chr.getMeso());
        world.shopLeave();
    }

    @Test(timeout = 180000)
    public void petSummonAndFollow() throws Exception {
        client.Character chr = server();
        // buy a pet in the Cash Shop and take it out into the Cash inventory
        boolean[] opened = {false};
        c.cashShopHandler = () -> opened[0] = true;
        c.enterCashShop();
        stepUntil(() -> opened[0] && c.inCashShop, 10000);
        int sn = 0, petId = 0;
        for (maple.wz.WzNode n : wz.get("Etc/Commodity.img").children()) {
            int id = n.getInt("ItemId", 0);
            if (n.getInt("OnSale", 0) == 1 && id / 10000 == 500 && server.CashShop.CashItemFactory.getItem(n.getInt("SN", 0)) != null) {
                sn = n.getInt("SN", 0);
                petId = id;
                break;
            }
        }
        assertTrue("a pet on sale", sn != 0);
        int before = world.cash.locker.size();
        world.cashBuy(sn, petId, 1);
        stepUntil(() -> world.cash.locker.size() > before, 10000);
        long cashId = world.cash.locker.get(world.cash.locker.size() - 1).cashId;
        world.cashTakeOut(cashId);
        int pid = petId;
        stepUntil(() -> c.player.inventory(5).values().stream().anyMatch(it -> it.itemId == pid), 5000);
        warp = null;
        c.leaveCashShop();
        pump(() -> warp != null);
        enterMap(warp[0]);

        Item pet = null;
        for (Item it : c.player.inventory(5).values()) if (it.itemId == petId) pet = it;
        assertNotNull("pet in the Cash inventory", pet);
        world.spawnPet(pet.position);
        stepUntil(() -> world.pets[0] != null, 5000);
        assertNotNull("pet summoned", world.pets[0]);
        assertNotNull("server agrees", chr.getPet(0));
        // it stays with its owner
        for (int i = 0; i < 300; i++) step();
        assertTrue("pet near its owner", Math.abs(world.pets[0].phys.x - player.phys.x) < 200);
        // it loots mesos and items it touches (no Meso Magnet / Item Pouch needed with the offline option)
        int mesoBefore = chr.getMeso();
        compat.awt.Point at = new compat.awt.Point((int) world.pets[0].phys.x, (int) world.pets[0].phys.y - 10);
        chr.getMap().spawnMesoDrop(77, at, chr, chr, false, (byte) 2, (short) 0);
        stepUntil(() -> chr.getMeso() == mesoBefore + 77, 8000);
        assertEquals("pet looted the mesos", mesoBefore + 77, chr.getMeso());
        int potions = chr.getInventory(client.inventory.InventoryType.USE).countById(2000000);
        chr.getMap().spawnItemDrop(chr, chr, new client.inventory.Item(2000000, (short) 0, (short) 3),
                new compat.awt.Point((int) world.pets[0].phys.x, (int) world.pets[0].phys.y - 10), (byte) 2, false);
        stepUntil(() -> chr.getInventory(client.inventory.InventoryType.USE).countById(2000000) == potions + 3, 8000);
        assertEquals("pet looted the item", potions + 3, chr.getInventory(client.inventory.InventoryType.USE).countById(2000000));
        // and is put away again
        world.spawnPet(pet.position);
        stepUntil(() -> world.pets[0] == null, 5000);
        assertNull("pet put away", world.pets[0]);
    }

    @Test(timeout = 120000)
    public void jobSwitchToken() throws Exception {
        client.Character chr = server();
        // a level 30 Fighter with skills learned, stats spent and a warrior-only weapon on
        while (chr.getLevel() < 30) chr.levelUp(false);
        chr.changeJob(client.Job.WARRIOR);
        chr.changeJob(client.Job.FIGHTER);
        client.Skill power = client.SkillFactory.getSkill(1000000); // Improved HP Recovery (warrior)
        chr.changeSkillLevel(power, (byte) 5, 16, -1);
        int str = chr.getStr(), dex = chr.getDex(), in = chr.getInt(), luk = chr.getLuk(), ap = chr.getRemainingAp();
        int sp = chr.getRemainingSp() + 5;
        client.inventory.manipulator.InventoryManipulator.addById(chr.getClient(), 1302133, (short) 1, "", -1); // warrior-only sword (reqJob 1)
        stepUntil(() -> c.player.inventory(1).values().stream().anyMatch(it -> it.itemId == 1302133), 5000);
        for (Item it : c.player.inventory(1).values()) if (it.itemId == 1302133) world.equip(it.position);
        stepUntil(() -> chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11) != null
                && chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11).getItemId() == 1302133, 5000);
        client.inventory.manipulator.InventoryManipulator.addById(chr.getClient(), offline.OfflineItems.JOB_TOKEN, (short) 1, "", -1);
        stepUntil(() -> c.player.inventory(2).values().stream().anyMatch(it -> it.itemId == offline.OfflineItems.JOB_TOKEN), 5000);
        assertEquals("named", "Job Switch Token", ItemInfo.get(offline.OfflineItems.JOB_TOKEN).name);

        talks.clear();
        world.talk = null;
        world.useItemId(offline.OfflineItems.JOB_TOKEN);
        stepUntil(() -> world.talk != null, 5000);
        NpcTalk menu = world.talk;
        assertNotNull("job menu", menu);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#L(\\d+)#Cleric#l").matcher(menu.text);
        assertTrue("Cleric offered: " + menu.text, m.find());
        assertFalse("not its own job", menu.text.contains("#Fighter#"));
        world.answer(1, Integer.parseInt(m.group(1)), null);
        stepUntil(() -> chr.getJob().getId() == 230 && c.player.stats.job == 230, 5000);
        assertEquals("now a Cleric", 230, chr.getJob().getId());
        assertEquals("the client knows", 230, c.player.stats.job);
        assertEquals("level kept", 30, chr.getLevel());
        assertEquals("STR back to 4", 4, chr.getStr());
        assertEquals("all AP back", ap + str + dex + in + luk - 16, chr.getRemainingAp());
        assertEquals("all SP back", sp, chr.getRemainingSp());
        assertEquals("skill unlearned", 0, chr.getSkillLevel(power));
        stepUntil(() -> chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11) == null, 5000);
        assertNull("warrior sword taken off", chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11));
        assertTrue("sword in the bag", chr.getInventory(client.inventory.InventoryType.EQUIP).countById(1302133) > 0);
        stepUntil(() -> !chr.haveItem(offline.OfflineItems.JOB_TOKEN), 5000);
        assertFalse("token used up", chr.haveItem(offline.OfflineItems.JOB_TOKEN));
        // the token is in the Cash Shop, free
        assertEquals(offline.OfflineItems.JOB_TOKEN, server.CashShop.CashItemFactory.getItem(30099902).getItemId());
        assertEquals(0, server.CashShop.CashItemFactory.getItem(30099902).getPrice());
        for (int i = 0; i < 30 && world.talk != null; i++) world.answer(1, 0, null);
    }

    static Item firstOf(int inv, int itemId) {
        for (Item it : c.player.inventory(inv).values()) if (it.itemId == itemId) return it;
        return null;
    }

    static void give(int itemId, int count) throws InterruptedException {
        client.inventory.manipulator.InventoryManipulator.addById(server().getClient(), itemId, (short) count, "", -1);
        stepUntil(() -> firstOf(ItemInfo.inventoryType(itemId), itemId) != null, 5000);
        assertNotNull("got " + itemId, firstOf(ItemInfo.inventoryType(itemId), itemId));
    }

    /** Picks the menu entry whose text contains `label` in the client-side menu now shown. */
    static void pick(String label) {
        NpcTalk t = world.talk;
        assertNotNull("menu shown for " + label, t);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#L(\\d+)#([^#]*)#l").matcher(t.text);
        while (m.find()) {
            if (m.group(2).contains(label)) {
                world.answer(1, Integer.parseInt(m.group(1)), null);
                return;
            }
        }
        fail("no '" + label + "' in: " + t.text);
    }

    @Test(timeout = 180000)
    public void itemsOfEveryKindWork() throws Exception {
        client.Character chr = server();
        // VIP Teleport Rock: save this map, go elsewhere, come back with the rock
        give(5041000, 2);
        int home = chr.getMapId();
        world.useCashItem(firstOf(5, 5041000).position);
        pick("Save this map");
        stepUntil(() -> java.util.Arrays.stream(c.player.vipTeleportMaps).anyMatch(m -> m == home), 5000);
        assertTrue("map saved", java.util.Arrays.stream(c.player.vipTeleportMaps).anyMatch(m -> m == home));
        warp = null;
        chr.changeMap(100010000);
        pump(() -> warp != null);
        enterMap(warp[0]);
        Thread.sleep(3100); // the server allows one cash item every 3 seconds
        warp = null;
        world.useCashItem(firstOf(5, 5041000).position);
        pick("Go to");
        pump(() -> warp != null);
        assertEquals("teleported back", home, warp[0]);
        enterMap(warp[0]);

        // a scroll dragged onto a sword uses one of its upgrade slots
        give(1302000, 1);
        give(2043001, 1);
        Item sword = firstOf(1, 1302000);
        world.equip(sword.position); // v83: scrolls go on worn items (bag items need Legendary Spirit)
        stepUntil(() -> chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11) != null
                && chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11).getItemId() == 1302000, 5000);
        client.inventory.Equip eq = (client.inventory.Equip) chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11);
        int slots = eq.getUpgradeSlots(), level = eq.getLevel();
        world.scroll(firstOf(2, 2043001).position, -11);
        stepUntil(() -> firstOf(2, 2043001) == null, 5000);
        assertNull("scroll used", firstOf(2, 2043001));
        client.inventory.Equip after = (client.inventory.Equip) chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11);
        assertTrue("upgrade slot used", after == null || after.getUpgradeSlots() == slots - 1 || after.getLevel() == level + 1);

        // a megaphone asks for the message and shows it
        Thread.sleep(3100);
        while (chr.getLevel() < 10) chr.levelUp(false); // v83: megaphones from level 10
        give(5071000, 1);
        chat.clear();
        world.useCashItem(firstOf(5, 5071000).position);
        assertEquals("asks for the message", 2, world.talk.type);
        world.answer(1, 0, "hello maple");
        stepUntil(() -> chat.stream().anyMatch(s -> s.contains("hello maple")), 5000);
        assertTrue("megaphone shown: " + chat, chat.stream().anyMatch(s -> s.contains("hello maple")));
    }

    /** Nonstop fighting on Perion Street Corner: monsters keep dying, the server keeps up, nothing breaks. */
    @Test(timeout = 300000)
    public void combatStress() throws Exception {
        client.Character chr = server();
        while (chr.getLevel() < 41) chr.levelUp(false);
        // a fighter who can actually hurt Stumps: Warrior, STR, a sword
        if (chr.getJob().getId() == 0) chr.changeJob(client.Job.WARRIOR);
        chr.gainAp(150, false);
        stepUntil(() -> c.player.stats.ap >= 150, 5000);
        world.autoAssign(0x40, chr.getRemainingAp(), 0x80, 0);
        stepUntil(() -> chr.getRemainingAp() == 0, 5000);
        if (firstOf(1, 1302000) == null && chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11) == null) give(1302000, 1);
        if (firstOf(1, 1302000) != null) {
            world.equip(firstOf(1, 1302000).position);
            stepUntil(() -> chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11) != null, 5000);
        }
        warp = null;
        chr.changeMap(101040000);
        pump(() -> warp != null);
        enterMap(warp[0]);
        serverErrors.clear();
        int expStart = c.player.stats.exp + c.player.stats.level * 1000000;
        long end = System.currentTimeMillis() + 90000, worstStep = 0, worstKill = 0;
        int attacks = 0, kills = 0;
        while (System.currentTimeMillis() < end) {
            Mob target = null;
            for (Mob m : world.mobs.values()) if (m.alive()) { target = m; break; }
            if (target == null) { step(); continue; }
            player.phys.x = target.phys.x + 30;
            player.phys.y = target.phys.y;
            player.facingRight = false;
            sendMove();
            long t0 = System.currentTimeMillis();
            Mob tgt = target;
            for (int a = 0; a < 15 && tgt.alive(); a++) {
                world.attack();
                attacks++;
                for (int i = 0; i < 40; i++) {
                    long s0 = System.nanoTime();
                    step();
                    worstStep = Math.max(worstStep, (System.nanoTime() - s0) / 1000000);
                }
            }
            if (!tgt.alive()) {
                kills++;
                worstKill = Math.max(worstKill, System.currentTimeMillis() - t0);
            }
        }
        System.out.println("combatStress: attacks=" + attacks + " kills=" + kills + " worstStepMs=" + worstStep + " worstKillMs=" + worstKill
                + " exp gained=" + (c.player.stats.exp + c.player.stats.level * 1000000 - expStart) + " serverErrors=" + serverErrors.size());
        for (String e : serverErrors.subList(0, Math.min(10, serverErrors.size()))) System.out.println("  ERR " + e);
        assertTrue("monsters die (" + kills + " kills, " + attacks + " attacks)", kills > 20);
        assertTrue("the game keeps up (worst step " + worstStep + " ms)", worstStep < 500);
        assertTrue("no server errors: " + serverErrors, serverErrors.isEmpty());
    }

    @Test(timeout = 120000)
    public void autoAssignAp() throws Exception {
        client.Character chr = server();
        chr.gainAp(20, false);
        stepUntil(() -> c.player.stats.ap >= 20, 5000);
        assertTrue("client sees the AP (" + c.player.stats.ap + ", server " + chr.getRemainingAp() + ")", c.player.stats.ap >= 20);
        world.talk = null;
        int ap = chr.getRemainingAp(), luk = chr.getLuk();
        world.autoAssignMenu();
        pick("All into LUK");
        stepUntil(() -> chr.getRemainingAp() == 0, 5000);
        assertEquals("all AP into LUK", luk + ap, chr.getLuk());

        // smart: a warrior gets STR, with DEX raised toward its level
        while (chr.getLevel() < 30) chr.levelUp(false);
        chr.changeJob(client.Job.WARRIOR);
        chr.gainAp(30, false);
        stepUntil(() -> c.player.stats.ap >= 30 && c.player.stats.job == 100, 5000);
        int ap2 = chr.getRemainingAp(), str = chr.getStr(), dex = chr.getDex();
        world.autoAssignMenu();
        pick("Auto-assign for my class");
        stepUntil(() -> chr.getRemainingAp() == 0, 5000);
        assertEquals("all AP used", 0, chr.getRemainingAp());
        assertEquals("split between STR and DEX", str + dex + ap2, chr.getStr() + chr.getDex());
        assertTrue("mostly STR", chr.getStr() - str >= chr.getDex() - dex || dex >= 30);
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
