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

    static Npc npc(int id) {
        for (Npc n : world.npcs.values()) if (n.id == id) return n;
        return null;
    }

    /** Taps through a client-side quest conversation: picks menu entry `choice`, then Next/Accept/OK. */
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
