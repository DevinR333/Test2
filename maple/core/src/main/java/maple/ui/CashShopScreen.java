package maple.ui;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.game.CashShopState;
import maple.game.ItemInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.windows.TextPrompt;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Cash Shop (UI.wz/CashShop.img; the 800x600 stage of 00468f3e): your character previewed with what
 * you try on, the nine category tabs (CSTab) and their subcategories (Etc.wz/Category.img), two columns of
 * five commodities (Etc.wz/Commodity.img) with Buy / Gift / Wish List, page numbers, NX balances, the
 * Cash Inventory (locker) and your cash items, inventory expansion and Exit.
 */
public final class CashShopScreen extends Widget implements Ui.Refreshable {
    private static final String CS = "CashShop.img/";
    private static final String[] TABS = {"Main", "Event", "Equip", "Use", "Set-Up", "Etc.", "Pet", "Package", "Wish List"};
    private static final int[] TAB_CATEGORIES = {8, 1, 2, 3, 4, 5, 6, 7, 9};
    private static final int[][] TAB_HIT_X = {{57, 69}, {107, 119}, {157, 169}, {209, 220}, {260, 272}, {311, 323}, {362, 374}, {3, 3}, {451, 451}};
    private static final int PAGE = 10;

    private static final class Offer {
        int sn, itemId, count, price, period, priority, gender;
        int category, sub;
        boolean limited; // retired (seasonal, limited): offered while the option is on
    }

    private static List<Offer> offers;
    private static final List<int[]> subcategories = new ArrayList<>(); // category, sub
    private static final List<String> subNames = new ArrayList<>();

    private final Ui ui;
    private final World world;
    private final Runnable exit;
    private int tab = 1, sub = 0, page, selectedSn, bagType = 1, lockerPage, bagPage;
    private String search = "";
    private final Map<Integer, Integer> tryOn = new HashMap<>(); // equip slot -> item
    private Avatar preview;
    private boolean previewDirty = true;
    private long time;
    private final List<Button> listButtons = new ArrayList<>();

    public CashShopScreen(Ui ui, World world, Runnable exit) {
        this.ui = ui;
        this.world = world;
        this.exit = exit;
        x = (Ui.W - 800) / 2f;
        w = 800;
        h = 600;
        loadCatalog(ui.assets.wz);
        add(new Button(ui.assets, CS + "CSChar/BtBuyAvatar", 16, 237, this::buyPreview));
        add(new Button(ui.assets, CS + "CSChar/BtDefaultAvatar", 101, 237, () -> {
            tryOn.clear();
            previewDirty = true;
        }));
        add(new Button(ui.assets, CS + "CSChar/BtTakeoffAvatar", 187, 237, () -> {
            tryOn.clear();
            for (int slot = 1; slot <= 20; slot++) tryOn.put(slot, 0);
            previewDirty = true;
        }));
        Button charge = add(new Button(ui.assets, CS + "CSStatus/BtCharge", 502, 543, () -> {}));
        charge.disabled = true; // NX is earned in game (NX cards from monsters)
        add(new Button(ui.assets, CS + "CSStatus/BtCheck", 543, 543, world::cashCheck));
        Button coupon = add(new Button(ui.assets, CS + "CSStatus/BtCoupon", 584, 543, () -> {}));
        coupon.disabled = true;
        add(new Button(ui.assets, CS + "CSStatus/BtExit", 632, 545, exit));
        add(new Button(ui.assets, CS + "CSItemSearch/BtSearch", 690, 97, () -> ui.open(new TextPrompt(ui, "Enter the name of the item to search for.", search, 30, s -> {
            search = s;
            page = 0;
            buildList();
        }))));
        String[] ex = {"BtExEquip", "BtExConsume", "BtExInstall", "BtExEtc"};
        for (int t = 1; t <= 4; t++) {
            int type = t;
            add(new Button(ui.assets, CS + "CSInventory/" + ex[t - 1], 176, 426 + 28 + (t - 1) * 26, () -> expand(type)));
        }
        Button trunk = add(new Button(ui.assets, CS + "CSInventory/BtExTrunk", 176, 426 + 132, () -> {}));
        trunk.disabled = true;
        selectTab(1);
    }

    private static synchronized void loadCatalog(maple.wz.Wz wz) {
        if (offers != null) return;
        offers = new ArrayList<>();
        List<int[]> entries = new ArrayList<>();
        for (WzNode n : wz.get("Etc/Commodity.img").children()) {
            entries.add(new int[]{n.getInt("SN", 0), n.getInt("ItemId", 0), n.getInt("OnSale", 0)});
        }
        java.util.Set<Integer> limited = offline.OfflineItems.limitedOffers(entries);
        for (WzNode n : wz.get("Etc/Commodity.img").children()) {
            boolean retired = n.getInt("OnSale", 0) == 0;
            if (retired && !limited.contains(n.getInt("SN", 0))) continue;
            Offer o = new Offer();
            o.sn = n.getInt("SN", 0);
            o.itemId = n.getInt("ItemId", 0);
            o.count = n.getInt("Count", 1);
            o.price = n.getInt("Price", 0);
            o.period = n.getInt("Period", 0);
            o.priority = n.getInt("Priority", 0);
            o.gender = n.getInt("Gender", 2);
            o.category = o.sn / 10000000;
            o.sub = (o.sn / 100000) % 100;
            o.limited = retired;
            if (o.itemId != 0 && (!retired || ItemInfo.named(o.itemId))) offers.add(o);
        }
        offers.sort((a, b) -> a.priority != b.priority ? Integer.compare(b.priority, a.priority) : Integer.compare(a.sn, b.sn));
        for (WzNode n : wz.get("Etc/Category.img").children()) {
            subcategories.add(new int[]{n.getInt("Category", 0), n.getInt("CategorySub", 0)});
            subNames.add(n.getString("Name", ""));
        }
    }

    // ------------------------------------------------------------------ catalogue

    private List<Integer> subsOf(int category) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < subcategories.size(); i++) if (subcategories.get(i)[0] == category) l.add(i);
        return l;
    }

    private List<Offer> visibleOffers() {
        List<Offer> out = new ArrayList<>();
        int category = TAB_CATEGORIES[tab];
        String q = search.toLowerCase(Locale.ROOT);
        java.util.Set<Integer> wish = new java.util.HashSet<>();
        for (int sn : world.cash.wishlist) if (sn != 0) wish.add(sn);
        for (Offer o : offers) {
            if (o.limited && !offline.OfflineOptions.limitedCash) continue;
            if (!q.isEmpty()) {
                if (ItemInfo.get(o.itemId).name.toLowerCase(Locale.ROOT).contains(q)) out.add(o);
                continue;
            }
            if (tab == 0) continue;
            if (tab == 8) {
                if (wish.contains(o.sn)) out.add(o);
                continue;
            }
            if (o.category != category) continue;
            if (sub >= 0 && o.sub != sub) continue;
            out.add(o);
        }
        return out;
    }

    private int pages() {
        return Math.max(1, (visibleOffers().size() + PAGE - 1) / PAGE);
    }

    private void selectTab(int t) {
        tab = t;
        List<Integer> subs = subsOf(TAB_CATEGORIES[t]);
        sub = subs.isEmpty() ? -1 : subcategories.get(subs.get(0))[1];
        search = "";
        page = 0;
        selectedSn = 0;
        buildList();
    }

    private boolean wrongGender(Offer o) {
        int g = world.data() == null ? 0 : world.data().stats.gender;
        return o.gender != 2 && o.gender != -1 && o.gender != g;
    }

    private void buildList() {
        for (Button b : listButtons) remove(b);
        listButtons.clear();
        List<Offer> list = visibleOffers();
        page = Math.min(page, Math.max(0, pages() - 1));
        for (int i = 0; i < PAGE && page * PAGE + i < list.size(); i++) {
            Offer o = list.get(page * PAGE + i);
            float bx = 275 + (i % 2) * 206, by = 95 + 2 + (i / 2) * 81;
            Button buy = add(new Button(ui.assets, CS + "CSList/BtBuy", bx + 80, by + 53, () -> buy(o)));
            buy.disabled = wrongGender(o);
            Button gift = add(new Button(ui.assets, CS + "CSList/BtGift", bx + 119, by + 53,
                    () -> ui.open(new Dialogs.Notice(ui, "You cannot send a gift to your own account.", false, null, null))));
            boolean wished = false;
            for (int sn : world.cash.wishlist) if (sn == o.sn) wished = true;
            boolean inWish = wished;
            Button wish = add(new Button(ui.assets, CS + "CSList/" + (tab == 8 ? "BtRemove" : "BtReserve"), bx + 158, by + 53, () -> toggleWish(o, inWish)));
            wish.disabled = tab != 8 && (o.category == 8 || wrongGender(o));
            listButtons.add(buy);
            listButtons.add(gift);
            listButtons.add(wish);
        }
    }

    private void toggleWish(Offer o, boolean remove) {
        List<Integer> l = new ArrayList<>();
        for (int sn : world.cash.wishlist) if (sn != 0 && sn != o.sn) l.add(sn);
        if (!remove) {
            if (l.size() >= 10) {
                ui.open(new Dialogs.Notice(ui, "Your Wish List is full.", false, null, null));
                return;
            }
            l.add(o.sn);
        }
        int[] arr = new int[l.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = l.get(i);
        world.cashWishlist(arr);
    }

    /** NX Credit first, then NX Prepaid, then Maple Points. */
    private static boolean free() {
        return offline.OfflineOptions.freeCashShop;
    }

    private int currencyFor(int price) {
        if (free()) return 1;
        if (world.cash.nxCredit >= price) return 1;
        if (world.cash.nxPrepaid >= price) return 4;
        if (world.cash.maplePoints >= price) return 2;
        return 0;
    }

    private void buy(Offer o) {
        int currency = currencyFor(o.price);
        if (currency == 0) {
            ui.open(new Dialogs.Notice(ui, "You don't have enough cash.", false, null, null));
            return;
        }
        String name = ItemInfo.get(o.itemId).name;
        String period = o.period > 0 ? " (" + o.period + " days)" : "";
        String cost = free() ? "free" : "for " + String.format(Locale.US, "%,d", o.price) + " NX";
        ui.open(new Dialogs.Notice(ui, "Would you like to buy " + name + period + " " + cost + "?", true,
                () -> world.cashBuy(o.sn, o.itemId, currency), null));
    }

    private void buyPreview() {
        for (int id : tryOn.values()) {
            if (id == 0) continue;
            for (Offer o : offers) {
                if (o.itemId == id) {
                    buy(o);
                    return;
                }
            }
        }
    }

    private void expand(int type) {
        int currency = currencyFor(4000);
        if (currency == 0) {
            ui.open(new Dialogs.Notice(ui, "You don't have enough cash.", false, null, null));
            return;
        }
        String[] names = {"Equip", "Use", "Set-Up", "Etc."};
        ui.open(new Dialogs.Notice(ui, "Expand your " + names[type - 1] + " inventory by 4 slots" + (free() ? "?" : " for 4,000 NX?"), true,
                () -> world.cashExpand(type, currency), null));
    }

    // ------------------------------------------------------------------ preview

    private Avatar preview() {
        if (!previewDirty && preview != null) return preview;
        previewDirty = false;
        if (preview != null) preview.dispose();
        preview = null;
        if (world.data() == null) return null;
        Map<Integer, Integer> slots = new HashMap<>();
        for (Item it : world.data().inventory(-1).values()) {
            int s = -it.position;
            if (s > 100) slots.put(s - 100, it.itemId);
            else slots.putIfAbsent(s, it.itemId);
        }
        for (Map.Entry<Integer, Integer> e : tryOn.entrySet()) {
            if (e.getValue() == 0) slots.remove(e.getKey());
            else slots.put(e.getKey(), e.getValue());
        }
        int[] eq = new int[slots.size()];
        int i = 0;
        for (int id : slots.values()) eq[i++] = id;
        try {
            preview = new Avatar(ui.assets.wz, world.data().stats.skin, world.data().stats.face, world.data().stats.hair, eq);
        } catch (RuntimeException ex) {
            preview = null;
        }
        return preview;
    }

    private void tryOn(Offer o) {
        if (o.itemId / 1000000 != 1) return;
        int slot = -ItemInfo.equipSlot(o.itemId);
        if (slot == 0) return;
        tryOn.put(slot, o.itemId);
        previewDirty = true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void update(long ms) {
        super.update(ms);
        time += ms;
        x = (Ui.W - 800) / 2f;
        if (world.cash.message != null) {
            String m = world.cash.message;
            world.cash.message = null;
            ui.open(new Dialogs.Notice(ui, m, false, null, null));
            buildList();
        }
    }

    @Override
    public void draw(UiDraw g) {
        int job = world.data() == null ? 0 : world.data().stats.job;
        String bg = job / 1000 == 1 ? "backgrnd1" : job == 2000 || job / 100 == 21 ? "backgrnd2" : "backgrnd";
        g.image(ui.assets.sprite(CS + "Base/" + bg), 0, 0);
        Sprite pv = ui.assets.sprite(CS + "Base/Preview/0");
        if (pv != null) g.image(pv, 24, 40);
        Avatar a = preview();
        if (a != null) {
            String st = a.standStance();
            g.artMode();
            a.draw(g.batch, st, (int) ((time / 500) % Math.max(1, a.frameCount(st))), g.tx + 130, g.ty + 175, false);
        }
        if (world.data() != null) g.text(world.data().stats.name, 103, 273, 128, Align.left, false, 12, false, 0xFF111111);
        // category tabs and subcategories
        g.image(ui.assets.sprite(CS + "CSTab/Tab/" + (tab + 1)), 272, 17);
        List<Integer> subs = subsOf(TAB_CATEGORIES[tab]);
        if (!subs.isEmpty() && search.isEmpty()) {
            float sw = (float) Math.floor(506f / subs.size());
            for (int i = 0; i < subs.size(); i++) {
                int[] cs = subcategories.get(subs.get(i));
                g.text(subNames.get(subs.get(i)), 274 + i * sw, 77, sw, Align.center, false, 12, false, cs[1] == sub ? 0xFFFFFF00 : 0xFFFFFFFF);
            }
        }
        drawList(g);
        // balances
        int[] bal = {world.cash.nxCredit, world.cash.nxPrepaid, world.cash.maplePoints};
        for (int i = 0; i < 3; i++) {
            String v = free() ? "Free" : String.format(Locale.US, "%,d", bal[i]);
            g.text(v, 359, 544 + 14 * i, 122, Align.right, false, 12, false, 0xFF111111);
        }
        drawLocker(g);
        drawBag(g);
        drawChildren(g);
    }

    private void drawList(UiDraw g) {
        List<Offer> list = visibleOffers();
        if (list.isEmpty()) {
            String path = tab == 0 && search.isEmpty() ? CS + "PicturePlate/HowTo" : CS + "PicturePlate/NoItem";
            Sprite p = ui.assets.sprite(path);
            if (p != null) g.image(p, 275 + 3, 95 + (path.endsWith("HowTo") ? 2 : 60));
            else g.text(tab == 0 ? "Choose a category above." : "There are no items.", 275, 200, 412, Align.center, false, 12, false, 0xFFFFFFFF);
        }
        for (int i = 0; i < PAGE && page * PAGE + i < list.size(); i++) {
            Offer o = list.get(page * PAGE + i);
            float bx = 275 + (i % 2) * 206, by = 95 + 2 + (i / 2) * 81;
            g.image(ui.assets.sprite(CS + "CSList/Base"), bx, by);
            if (o.sn == selectedSn) g.image(ui.assets.sprite(CS + "CSList/ItemIcon"), bx, by);
            ItemInfo info = ItemInfo.get(o.itemId);
            Sprite icon = ui.assets.sprite(info.iconRaw());
            if (icon == null) icon = ui.assets.sprite(info.icon());
            if (icon != null) g.image(icon, bx + 21, by + 19);
            String name = info.name.isEmpty() ? Integer.toString(o.itemId) : info.name;
            while (name.length() > 1 && g.textWidth(name, 12, false) > 121) name = name.substring(0, name.length() - 1);
            g.text(name, bx + 76, by + 5, 12, false, 0xFF111111);
            String price = free() && o.category != 8 ? "Free" : String.format(Locale.US, "%,d %s", o.price, o.category == 8 ? "Mesos" : "NX");
            g.text(price, bx + 79, by + 25, 12, false, 0xFFFFFFFF);
            g.text(o.count + " item(s) / " + (o.period > 0 ? o.period : 90) + " days", bx + 79, by + 39, 12, false, 0xFFFFFFFF);
        }
        // page numbers
        int pages = pages(), start = (page / 10) * 10;
        float px = 275 + 5;
        g.text("<", px, 95 + 407, 24, Align.center, false, 12, false, 0xFFFFFFFF);
        for (int p = start; p < Math.min(pages, start + 10); p++) {
            px += 25;
            g.text(Integer.toString(p + 1), px, 95 + 407, 24, Align.center, false, 12, false, p == page ? 0xFFFFFF00 : 0xFFFFFFFF);
        }
        g.text(">", px + 25, 95 + 407, 24, Align.center, false, 12, false, 0xFFFFFFFF);
    }

    private float[] lockerCell(int index) {
        int slot = index % 12;
        return new float[]{-1 + 22 + (slot % 6) * 35, 318 + 33 + (slot / 6) * 35};
    }

    private void drawLocker(UiDraw g) {
        List<CashShopState.Entry> l = world.cash.locker;
        for (int i = lockerPage * 12; i < Math.min(l.size(), lockerPage * 12 + 12); i++) {
            float[] c = lockerCell(i);
            CashShopState.Entry e = l.get(i);
            Sprite icon = ui.assets.sprite(ItemInfo.get(e.itemId).icon());
            if (icon != null) g.anchored(icon, c[0], c[1] + 32);
            if (e.quantity > 1) g.text(Integer.toString(e.quantity), c[0], c[1] + 20, 9, false, 0xFFFFFFFF);
        }
        g.text((lockerPage + 1) + "/" + Math.max(1, (l.size() + 11) / 12), 200, 318 + 8, 50, Align.right, false, 11, false, 0xFFFFFFFF);
    }

    private List<Item> bagItems() {
        List<Item> out = new ArrayList<>();
        if (world.data() == null) return out;
        for (Item it : world.data().inventory(bagType).values()) if (it.cash || ItemInfo.get(it.itemId).cash) out.add(it);
        return out;
    }

    private void drawBag(UiDraw g) {
        for (int i = 0; i < 5; i++) {
            Sprite t = ui.assets.sprite(CS + "Base/Tab2/" + (bagType == i + 1 ? "Enable" : "Disable") + "/" + i);
            if (t != null) g.image(t, 18 + i * 30, 426 + 33);
        }
        List<Item> items = bagItems();
        for (int i = bagPage * 12; i < Math.min(items.size(), bagPage * 12 + 12); i++) {
            int k = i - bagPage * 12;
            float cx = 22 + (k % 4) * 35, cy = 426 + 55 + (k / 4) * 35;
            Sprite icon = ui.assets.sprite(ItemInfo.get(items.get(i).itemId).icon());
            if (icon != null) g.anchored(icon, cx, cy + 32);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean interactive() { return true; }

    private Offer offerAt(float lx, float ly) {
        List<Offer> list = visibleOffers();
        for (int i = 0; i < PAGE && page * PAGE + i < list.size(); i++) {
            float bx = 275 + (i % 2) * 206, by = 95 + 2 + (i / 2) * 81;
            if (lx >= bx + 5 && lx < bx + 69 && ly >= by + 5 && ly < by + 69) return list.get(page * PAGE + i);
        }
        return null;
    }

    private int lockerAt(float lx, float ly) {
        List<CashShopState.Entry> l = world.cash.locker;
        for (int i = lockerPage * 12; i < Math.min(l.size(), lockerPage * 12 + 12); i++) {
            float[] c = lockerCell(i);
            if (lx >= c[0] && lx < c[0] + 32 && ly >= c[1] && ly < c[1] + 32) return i;
        }
        return -1;
    }

    private Item bagAt(float lx, float ly) {
        List<Item> items = bagItems();
        for (int i = bagPage * 12; i < Math.min(items.size(), bagPage * 12 + 12); i++) {
            int k = i - bagPage * 12;
            float cx = 22 + (k % 4) * 35, cy = 426 + 55 + (k / 4) * 35;
            if (lx >= cx && lx < cx + 32 && ly >= cy && ly < cy + 32) return items.get(i);
        }
        return null;
    }

    @Override
    public Widget hit(float lx, float ly) {
        Widget h = super.hit(lx, ly);
        if (h != null) return h;
        return lx >= 0 && ly >= 0 && lx < 800 && ly < 600 ? this : null;
    }

    @Override
    public boolean onPress(float lx, float ly) {
        // category tabs
        if (ly >= 39 && ly < 70) {
            for (int i = 0; i < TABS.length; i++) {
                if (i == tab) continue;
                int category = TAB_CATEGORIES[i];
                float tx = 272 + TAB_HIT_X[category - 1][category > TAB_CATEGORIES[tab] ? 1 : 0];
                if (lx >= tx && lx < tx + 51) {
                    UiSounds.play("Tab");
                    selectTab(i);
                    return false;
                }
            }
        }
        // subcategories
        List<Integer> subs = subsOf(TAB_CATEGORIES[tab]);
        if (ly >= 75 && ly < 93 && lx >= 274 && !subs.isEmpty()) {
            float sw = (float) Math.floor(506f / subs.size());
            int i = (int) ((lx - 274) / sw);
            if (i >= 0 && i < subs.size()) {
                sub = subcategories.get(subs.get(i))[1];
                page = 0;
                search = "";
                buildList();
            }
            return false;
        }
        // pages
        if (ly >= 95 + 405 && ly < 95 + 425 && lx >= 280 && lx < 280 + 25 * 12) {
            int idx = (int) ((lx - 280) / 25);
            int pages = pages(), start = (page / 10) * 10, count = Math.min(pages, start + 10) - start;
            if (idx == 0) page = Math.max(0, start - 1);
            else if (idx <= count) page = start + idx - 1;
            else if (idx == count + 1) page = Math.min(pages - 1, start + 10);
            buildList();
            return false;
        }
        // bag tabs
        if (ly >= 426 + 28 && ly < 426 + 50 && lx >= 18 && lx < 18 + 150) {
            bagType = (int) ((lx - 18) / 30) + 1;
            bagPage = 0;
            return false;
        }
        Offer o = offerAt(lx, ly);
        if (o != null) selectedSn = o.sn;
        return false;
    }

    @Override
    public void onDoubleClick(float lx, float ly) {
        Offer o = offerAt(lx, ly);
        if (o != null) {
            tryOn(o);
            return;
        }
        int li = lockerAt(lx, ly);
        if (li >= 0) {
            world.cashTakeOut(world.cash.locker.get(li).cashId);
            return;
        }
        Item it = bagAt(lx, ly);
        if (it != null) world.cashPutBack(it);
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        if (lx >= 275 && ly >= 95) {
            page = Math.max(0, Math.min(pages() - 1, page + Integer.signum(amount)));
            buildList();
        } else if (ly >= 318 && ly < 426) {
            lockerPage = Math.max(0, Math.min(Math.max(0, (world.cash.locker.size() - 1) / 12), lockerPage + Integer.signum(amount)));
        } else if (ly >= 426) {
            bagPage = Math.max(0, Math.min(Math.max(0, (bagItems().size() - 1) / 12), bagPage + Integer.signum(amount)));
        }
        return true;
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        Offer o = offerAt(lx, ly);
        if (o != null) return new ItemTooltip(ui.assets, o.itemId, null, world.data() == null ? null : world.data().stats, false, null);
        int li = lockerAt(lx, ly);
        if (li >= 0) return new ItemTooltip(ui.assets, world.cash.locker.get(li).itemId, null, world.data().stats, false,
                "Double-click to move it to your inventory.");
        Item it = bagAt(lx, ly);
        if (it != null) return new ItemTooltip(ui.assets, it.itemId, it, world.data().stats, false, "Double-click to put it in the Cash Inventory.");
        return null;
    }

    /** Escape leaves the Cash Shop. */
    public boolean keyDown(int key) {
        if (key == Input.Keys.ESCAPE) {
            exit.run();
            return true;
        }
        return false;
    }

    public void dispose() {
        if (preview != null) preview.dispose();
    }

    /** The commodity list changed (wish list): rebuild its buttons. */
    @Override
    public void refresh() {
        buildList();
    }
}
