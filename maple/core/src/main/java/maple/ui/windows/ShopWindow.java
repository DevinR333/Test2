package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.game.ItemInfo;
import maple.game.Shop;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.Button;
import maple.ui.Dialogs;
import maple.ui.ItemTooltip;
import maple.ui.Scrollbar;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.UiSounds;
import maple.ui.Window;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The NPC shop (UIWindow.img/Shop, 463x339; 007532ce..00753db7): NPC at (56, 76), you at (285, 76), mesos
 * right-aligned at (361, 64, 86), Exit (142, 15), Buy (142, 35), Sell (372, 35), Tab3 strips at y 95
 * (buy All/Must from x 5, sell Equip..Cash from x 235), five 40px rows from y 146 with Shop/select
 * highlights and VScr3 scrollbars at (210 / 441, 127, 194).
 */
public final class ShopWindow extends Window {
    private static final int ROWS = 5, ROW_Y = 146, ROW_STEP = 40;
    private final World world;
    private final Shop shop;
    private final Scrollbar buyScroll, sellScroll;
    private int buyTab, sellTab;
    private int buySel = -1, sellSel = -1; // shop index / inventory slot
    private final List<Integer> buyRows = new ArrayList<>();
    private final List<Item> sellRows = new ArrayList<>();
    private Sprite npc;
    private final Button buyBtn, sellBtn;
    private final List<Button> recharge = new ArrayList<>();

    public ShopWindow(Ui ui, World world, Shop shop) {
        super(ui, "Shop", "UIWindow.img/Shop/backgrnd");
        this.world = world;
        this.shop = shop;
        draggable = true;
        add(new Button(ui.assets, "UIWindow.img/PersonalShop/BtExit", 142, 15, this::leave));
        buyBtn = add(new Button(ui.assets, "UIWindow.img/Shop/BtBuy", 142, 35, this::buy));
        sellBtn = add(new Button(ui.assets, "UIWindow.img/Shop/BtSell", 372, 35, this::sell));
        buyScroll = add(new Scrollbar(ui.assets, "VScr3", 210, 127, 194));
        sellScroll = add(new Scrollbar(ui.assets, "VScr3", 441, 127, 194));
        sellScroll.onChange = p -> refresh();
        WzNode src = ui.assets.wz.get("Npc/" + String.format("%07d", shop.npcId) + ".img");
        String link = src.get("info").getString("link", "");
        if (!link.isEmpty()) src = ui.assets.wz.get("Npc/" + link + ".img");
        WzNode f = src.get("stand").get("0");
        npc = f.exists() ? ui.assets.sprite(f) : null;
        refresh();
    }

    private boolean recommended(Shop.Entry e) {
        ItemInfo info = ItemInfo.get(e.itemId);
        if (ItemInfo.inventoryType(e.itemId) != 1 || e.price <= 0 || world.data() == null) return false;
        int level = world.data().stats.level, job = world.data().stats.job;
        if (Math.abs(info.reqLevel - level) >= 6) return false;
        int family = (job % 1000) / 100;
        int jobs = info.reqJob;
        if (jobs != 0 && !(family != 0 && (jobs & (1 << (family - 1))) != 0)) return false;
        int gender = (e.itemId / 1000) % 10;
        return gender >= 2 || gender == world.data().stats.gender;
    }

    @Override
    public void refresh() {
        buyRows.clear();
        for (int i = 0; i < shop.items.size(); i++) {
            Shop.Entry e = shop.items.get(i);
            if ((e.price > 0 || e.pitch > 0 || e.recharge || e.itemId == offline.OfflineItems.LEVEL_POTION) && (buyTab == 0 || recommended(e))) buyRows.add(i);
        }
        sellRows.clear();
        if (world.data() != null) sellRows.addAll(world.data().inventory(sellTab + 1).values());
        if (!buyRows.contains(buySel)) buySel = -1;
        boolean found = false;
        for (Item it : sellRows) if (it.position == sellSel) found = true;
        if (!found) sellSel = -1;
        buyScroll.setRange(Math.max(1, buyRows.size() - ROWS + 1), buyScroll.position);
        sellScroll.setRange(Math.max(1, sellRows.size() - ROWS + 1), sellScroll.position);
        buyBtn.disabled = buySel < 0;
        sellBtn.disabled = sellSel < 0;
        for (Button b : recharge) remove(b);
        recharge.clear();
        for (int r = 0; r < ROWS; r++) {
            int i = sellScroll.position + r;
            if (i >= sellRows.size()) break;
            Item it = sellRows.get(i);
            if (ItemInfo.get(it.itemId).isRechargeable()) {
                int slot = it.position;
                recharge.add(add(new Button(ui.assets, "UIWindow.img/Shop/BtRecharge", 404, ROW_Y + r * ROW_STEP - 19, () -> world.shopRecharge(slot))));
            }
        }
    }

    private void leave() {
        world.shopLeave();
        close();
    }

    private Item sellItem() {
        for (Item it : sellRows) if (it.position == sellSel) return it;
        return null;
    }

    private void buy() {
        if (buySel < 0) return;
        Shop.Entry e = shop.items.get(buySel);
        ItemInfo info = ItemInfo.get(e.itemId);
        int meso = world.data() == null ? 0 : world.data().meso;
        if (e.price > meso) {
            ui.open(new Dialogs.Notice(ui, "You do not have enough mesos.", false, null, null));
            return;
        }
        if (ItemInfo.inventoryType(e.itemId) != 1 && !info.isRechargeable()) {
            int stack = Math.max(1, info.slotMax);
            int max = Math.max(1, e.price > 0 ? Math.min(stack, meso / e.price) : stack); // free items: a full stack
            QuantityPrompt q = new QuantityPrompt(ui, "How many are you willing to buy?", 1, max, n -> world.shopBuy(buySel, e.itemId, n));
            boolean mesoLimited = e.price > 0 && meso / e.price < stack;
            q.overMax = mesoLimited ? "You only have enough mesos for " + max + "." : "You can buy up to " + max + " at a time.";
            ui.open(q);
        } else {
            int idx = buySel;
            ui.open(new Dialogs.Notice(ui, "Are you sure you want to buy it?", true, () -> world.shopBuy(idx, e.itemId, 1), null));
        }
    }

    private void sell() {
        Item it = sellItem();
        if (it == null) return;
        int slot = it.position;
        ItemInfo info = ItemInfo.get(it.itemId);
        if (it.quantity > 1 && !info.isRechargeable()) {
            ui.open(new QuantityPrompt(ui, "How many are you willing to sell?", it.quantity, it.quantity, n -> world.shopSell(slot, it.itemId, n)));
        } else {
            ui.open(new Dialogs.Notice(ui, "Are you sure you want to sell it?", true, () -> world.shopSell(slot, it.itemId, Math.max(1, it.quantity)), null));
        }
    }

    // ---------------------------------------------------------------- drawing

    private void drawTabs(UiDraw g, int start, int count, int selected, boolean buy) {
        g.image(ui.assets.sprite("Basic.img/Tab3/left" + (selected == 0 ? 1 : 0)), start, 95);
        float x = start + 6;
        for (int i = 0; i < count; i++) {
            int width = buy ? 42 : i < 2 ? 33 : 32;
            boolean on = i == selected;
            Sprite fill = ui.assets.sprite("Basic.img/Tab3/fill" + (on ? 1 : 0));
            if (fill != null) g.stretched(fill, x, 95, width, fill.h);
            boolean last = i == count - 1;
            String edge = last ? "right" + (on ? 1 : 0) : "middle" + (on ? 1 : (selected == i + 1 ? 2 : 0));
            g.image(ui.assets.sprite("Basic.img/Tab3/" + edge), x + width, 95);
            Sprite label = ui.assets.sprite("UIWindow.img/Shop/" + (buy ? "TabBuy" : "TabSell") + "/" + (on ? "enabled" : "disabled") + "/" + i);
            if (label != null) g.image(label, x + (int) ((width - label.w) / 2), 95 + (int) ((21 - label.h) / 2));
            x += width + 12;
        }
    }

    private int tabAt(float lx, float ly, int start, int count, boolean buy) {
        if (ly < 95 || ly >= 116) return -1;
        float x = start + 6;
        for (int i = 0; i < count; i++) {
            int width = buy ? 42 : i < 2 ? 33 : 32;
            if (lx >= x && lx < x + width + 12) return i;
            x += width + 12;
        }
        return -1;
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (npc != null) g.anchored(npc, 56, 76);
        Avatar a = world.player.avatar();
        if (a != null) {
            g.artMode();
            a.draw(g.batch, a.standStance(), 0, g.tx + 285, g.ty + 76, false);
        }
        int meso = world.data() == null ? 0 : world.data().meso;
        g.text(String.format(Locale.US, "%,d", meso), 361, 64, 86, Align.right, false, 12, false, 0xFF000000);
        drawTabs(g, 5, 2, buyTab, true);
        drawTabs(g, 235, 5, sellTab, false);
        Sprite select = ui.assets.sprite("UIWindow.img/Shop/select");
        Sprite mesoIcon = ui.assets.sprite("UIWindow.img/Shop/meso");
        for (int r = 0; r < ROWS; r++) {
            int i = buyScroll.position + r;
            if (i >= buyRows.size()) break;
            Shop.Entry e = shop.items.get(buyRows.get(i));
            float anchor = ROW_Y + r * ROW_STEP;
            if (buyRows.get(i) == buySel && select != null) g.image(select, 43, anchor - 19);
            ItemInfo info = ItemInfo.get(e.itemId);
            Sprite icon = ui.assets.sprite(info.icon());
            if (icon != null) g.anchored(icon, 8, anchor + 15);
            g.text(clip(g, info.name, 152), 49, anchor - 18, 152, Align.left, false, 12, false, 0xFF000000);
            if (mesoIcon != null) g.image(mesoIcon, 49, anchor - 1);
            String price = e.recharge ? String.format(Locale.US, "%,d", e.price) : String.format(Locale.US, "%,d", e.price);
            g.text(price, 64, anchor, 137, Align.left, false, 12, false, 0xFF000000);
        }
        for (int r = 0; r < ROWS; r++) {
            int i = sellScroll.position + r;
            if (i >= sellRows.size()) break;
            Item it = sellRows.get(i);
            float anchor = ROW_Y + r * ROW_STEP;
            if (it.position == sellSel && select != null) g.image(select, 273, anchor - 19);
            ItemInfo info = ItemInfo.get(it.itemId);
            ItemWindow.drawItem(g, ui, it, 238 - 2, anchor - 19 - 0, sellTab >= 1 && sellTab <= 3);
            boolean rechargeable = info.isRechargeable();
            g.text(clip(g, info.name, rechargeable ? 120 : 152), 279, anchor - 18, 152, Align.left, false, 12, false, 0xFF000000);
            String value = String.format(Locale.US, "%,d meso", offline.OfflineItems.sellPrice(it.itemId, info.price));
            g.text(value, 279, anchor, 155, Align.left, false, 12, false, 0xFF000000);
        }
    }

    private static String clip(UiDraw g, String s, float w) {
        if (g.textWidth(s, 12, false) <= w) return s;
        String t = s;
        while (t.length() > 1 && g.textWidth(t + "..", 12, false) > w) t = t.substring(0, t.length() - 1);
        return t + "..";
    }

    private int rowAt(float lx, float ly, boolean buy) {
        float left = buy ? 6 : 236;
        for (int r = 0; r < ROWS; r++) {
            float top = ROW_Y + r * ROW_STEP - 19;
            if (lx >= left && lx < left + 198 && ly >= top && ly < top + 35) return r;
        }
        return -1;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        int t = tabAt(lx, ly, 5, 2, true);
        if (t >= 0 && t != buyTab) {
            buyTab = t;
            buyScroll.setRange(1, 0);
            UiSounds.play("BtMouseClick");
            refresh();
            return false;
        }
        t = tabAt(lx, ly, 235, 5, false);
        if (t >= 0 && t != sellTab) {
            sellTab = t;
            sellScroll.setRange(1, 0);
            UiSounds.play("BtMouseClick");
            refresh();
            return false;
        }
        int r = rowAt(lx, ly, true);
        if (r >= 0 && buyScroll.position + r < buyRows.size()) {
            buySel = buyRows.get(buyScroll.position + r);
            refresh();
            return false;
        }
        r = rowAt(lx, ly, false);
        if (r >= 0 && sellScroll.position + r < sellRows.size()) {
            sellSel = sellRows.get(sellScroll.position + r).position;
            refresh();
        }
        return false;
    }

    @Override
    public void onDoubleClick(float lx, float ly) {
        int r = rowAt(lx, ly, true);
        if (r >= 0 && buyScroll.position + r < buyRows.size()) {
            buySel = buyRows.get(buyScroll.position + r);
            buy();
            return;
        }
        r = rowAt(lx, ly, false);
        if (r >= 0 && sellScroll.position + r < sellRows.size()) {
            sellSel = sellRows.get(sellScroll.position + r).position;
            sell();
        }
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        int r = rowAt(lx, ly, true);
        if (r >= 0 && buyScroll.position + r < buyRows.size()) {
            Shop.Entry e = shop.items.get(buyRows.get(buyScroll.position + r));
            return new ItemTooltip(ui.assets, e.itemId, null, world.data() == null ? null : world.data().stats, false, null);
        }
        r = rowAt(lx, ly, false);
        if (r >= 0 && sellScroll.position + r < sellRows.size()) {
            Item it = sellRows.get(sellScroll.position + r);
            return new ItemTooltip(ui.assets, it.itemId, it, world.data() == null ? null : world.data().stats, false, null);
        }
        return null;
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        if (lx < 230) buyScroll.scroll(amount);
        else sellScroll.scroll(amount);
        return true;
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            leave();
            return true;
        }
        return false;
    }

    /** CONFIRM_SHOP_TRANSACTION result. */
    public void result(int code) {
        String msg = null;
        switch (code) {
            case 0:
            case 8:
                break;
            case 1:
                msg = "This item is out of stock.";
                break;
            case 2:
                msg = "You do not have enough mesos.";
                break;
            case 3:
                msg = "Please check if your inventory is full or not.";
                break;
            case 5:
                msg = "You do not have enough in stock.";
                break;
            default:
                msg = "Due to an error, the trade did not happen.";
                break;
        }
        if (msg != null) ui.open(new Dialogs.Notice(ui, msg, false, null, null));
        refresh();
    }
}
