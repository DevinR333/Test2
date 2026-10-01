package maple.ui.windows;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.chr.Avatar;
import maple.game.ItemInfo;
import maple.game.Storage;
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
 * The storage (UIWindow.img/Trunk, 007c70ef..): stored items on the left (five 40px rows from (6, 87),
 * each over Trunk/en), your inventory tab on the right (four rows from (236, 127)), Exit/Get/Sort at
 * x 154, Put at (384, 15), meso buttons at the bottom, stored/carried mesos at y 296.
 */
public final class StorageWindow extends Window {
    private final World world;
    private Storage storage;
    private final Scrollbar outScroll, inScroll;
    private int tab;
    private Item outSel, inSel;
    private final List<Item> stored = new ArrayList<>();
    private final List<Item> carried = new ArrayList<>();
    private final Sprite npc;

    public StorageWindow(Ui ui, World world, Storage storage) {
        super(ui, "Trunk", "UIWindow.img/Trunk/backgrnd");
        this.world = world;
        this.storage = storage;
        add(new Button(ui.assets, "UIWindow.img/Trunk/BtExit", 154, 15, this::leave));
        add(new Button(ui.assets, "UIWindow.img/Trunk/BtGet", 154, 35, this::get));
        add(new Button(ui.assets, "UIWindow.img/Trunk/BtPut", 384, 15, this::put));
        add(new Button(ui.assets, "UIWindow.img/Trunk/BtSort", 154, 55, () -> world.storage(6, 0, 0, 0)));
        add(new Button(ui.assets, "UIWindow.img/Trunk/BtOutCoin", 5, 294, this::withdrawMeso));
        add(new Button(ui.assets, "UIWindow.img/Trunk/BtInCoin", 236, 294, this::depositMeso));
        outScroll = add(new Scrollbar(ui.assets, "VScr3", 210, 88, 197));
        inScroll = add(new Scrollbar(ui.assets, "VScr3", 441, 128, 158));
        WzNode src = ui.assets.wz.get("Npc/" + String.format("%07d", storage.npcId) + ".img");
        String link = src.get("info").getString("link", "");
        if (!link.isEmpty()) src = ui.assets.wz.get("Npc/" + link + ".img");
        WzNode f = src.get("stand").get("0");
        npc = f.exists() ? ui.assets.sprite(f) : null;
        refresh();
    }

    public void update(Storage s) {
        storage = s;
        refresh();
    }

    @Override
    public void refresh() {
        stored.clear();
        stored.addAll(storage.items);
        stored.sort((a, b) -> ItemInfo.inventoryType(a.itemId) - ItemInfo.inventoryType(b.itemId));
        carried.clear();
        if (world.data() != null) carried.addAll(world.data().inventory(tab + 1).values());
        if (!stored.contains(outSel)) outSel = null;
        if (!carried.contains(inSel)) inSel = null;
        outScroll.setRange(Math.max(1, storage.slots - 5 + 1), outScroll.position);
        inScroll.setRange(Math.max(1, carried.size() - 4 + 1), inScroll.position);
    }

    private void leave() {
        world.storage(8, 0, 0, 0);
        close();
    }

    private void get() {
        if (outSel == null) return;
        int type = ItemInfo.inventoryType(outSel.itemId);
        int index = 0;
        for (Item it : storage.items) {
            if (it == outSel) break;
            if (ItemInfo.inventoryType(it.itemId) == type) index++;
        }
        world.storage(4, type, index, 0);
    }

    private void put() {
        if (inSel == null) return;
        Item it = inSel;
        if (it.quantity > 1 && !ItemInfo.get(it.itemId).isRechargeable()) {
            ui.open(new QuantityPrompt(ui, "How many will you store?", it.quantity, it.quantity, n -> world.storage(5, it.position, it.itemId, n)));
        } else {
            world.storage(5, it.position, it.itemId, Math.max(1, it.quantity));
        }
    }

    private void withdrawMeso() {
        if (storage.meso <= 0) return;
        ui.open(new QuantityPrompt(ui, "How many mesos would you like to withdraw?", storage.meso, 1, storage.meso, n -> world.storage(7, n, 0, 0)));
    }

    private void depositMeso() {
        int have = world.data() == null ? 0 : world.data().meso;
        if (have <= 0) return;
        ui.open(new QuantityPrompt(ui, "How many mesos would you like to store?", have, 1, have, n -> world.storage(7, -n, 0, 0)));
    }

    private void drawTabs(UiDraw g) {
        g.image(ui.assets.sprite("Basic.img/Tab3/left" + (tab == 0 ? 1 : 0)), 235, 92);
        float x = 241;
        for (int i = 0; i < 5; i++) {
            int width = i < 2 ? 33 : 32;
            boolean on = i == tab;
            Sprite fill = ui.assets.sprite("Basic.img/Tab3/fill" + (on ? 1 : 0));
            if (fill != null) g.stretched(fill, x, 92, width, fill.h);
            String edge = i == 4 ? "right" + (on ? 1 : 0) : "middle" + (on ? 1 : (tab == i + 1 ? 2 : 0));
            g.image(ui.assets.sprite("Basic.img/Tab3/" + edge), x + width, 92);
            Sprite label = ui.assets.sprite("UIWindow.img/Trunk/Tab/" + (on ? "enabled" : "disabled") + "/" + i);
            if (label != null) g.image(label, x + (int) ((width - label.w) / 2), 92 + (int) ((21 - label.h) / 2));
            x += width + 12;
        }
    }

    private void row(UiDraw g, Item it, float x, float y, boolean selected, boolean withdraw) {
        if (withdraw) {
            Sprite en = ui.assets.sprite("UIWindow.img/Trunk/en");
            if (en != null) g.image(en, x, y);
        }
        if (it == null) return;
        if (selected) {
            Sprite sel = ui.assets.sprite("UIWindow.img/Trunk/select");
            if (sel != null) g.image(sel, x + 38, y);
        }
        ItemInfo info = ItemInfo.get(it.itemId);
        Sprite icon = ui.assets.sprite(info.icon());
        if (icon != null) g.anchored(icon, x + 1, y + 34);
        g.text(info.name, x + 43, y + 2, 152, Align.left, false, 12, false, 0xFF000000);
        int t = ItemInfo.inventoryType(it.itemId);
        if (t >= 2 && t <= 4) {
            float cx = x;
            for (char c : Integer.toString(it.quantity).toCharArray()) {
                Sprite d = ui.assets.sprite("Basic.img/ItemNo/" + c);
                if (d == null) continue;
                g.image(d, cx, y + 22);
                cx += d.w;
            }
        }
    }

    @Override
    protected void drawContent(UiDraw g) {
        if (npc != null) g.anchored(npc, 53, 76);
        Avatar a = world.player.avatar();
        if (a != null) {
            g.artMode();
            a.draw(g.batch, a.standStance(), 0, g.tx + 288, g.ty + 75, false);
        }
        drawTabs(g);
        for (int r = 0; r < 5; r++) {
            int i = outScroll.position + r;
            if (i >= storage.slots) break;
            Item it = i < stored.size() ? stored.get(i) : null;
            row(g, it, 6, 87 + r * 40, it != null && it == outSel, true);
        }
        for (int r = 0; r < 4; r++) {
            int i = inScroll.position + r;
            if (i >= carried.size()) break;
            Item it = carried.get(i);
            row(g, it, 236, 127 + r * 40, it == inSel, false);
        }
        g.text(String.format(Locale.US, "%,d", storage.meso), 66, 296, 124, Align.right, false, 12, false, 0xFF000000);
        int meso = world.data() == null ? 0 : world.data().meso;
        g.text(String.format(Locale.US, "%,d", meso), 290, 296, 128, Align.right, false, 12, false, 0xFF000000);
    }

    private Item itemAt(float lx, float ly, boolean withdraw) {
        float x0 = withdraw ? 6 : 236, y0 = withdraw ? 87 : 127;
        int rows = withdraw ? 5 : 4;
        if (lx < x0 || lx >= x0 + 198) return null;
        for (int r = 0; r < rows; r++) {
            float y = y0 + r * 40;
            if (ly >= y && ly < y + 35) {
                int i = (withdraw ? outScroll.position : inScroll.position) + r;
                List<Item> list = withdraw ? stored : carried;
                return i < list.size() ? list.get(i) : null;
            }
        }
        return null;
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        if (ly >= 92 && ly < 113 && lx >= 241) {
            float x = 241;
            for (int i = 0; i < 5; i++) {
                int width = i < 2 ? 33 : 32;
                if (lx >= x && lx < x + width + 12) {
                    if (tab != i) {
                        tab = i;
                        inSel = null;
                        inScroll.setRange(1, 0);
                        UiSounds.play("BtMouseClick");
                        refresh();
                    }
                    return false;
                }
                x += width + 12;
            }
        }
        Item it = itemAt(lx, ly, true);
        if (it != null) outSel = it;
        it = itemAt(lx, ly, false);
        if (it != null) inSel = it;
        return false;
    }

    @Override
    public void onDoubleClick(float lx, float ly) {
        Item it = itemAt(lx, ly, true);
        if (it != null) {
            outSel = it;
            get();
            return;
        }
        it = itemAt(lx, ly, false);
        if (it != null) {
            inSel = it;
            put();
        }
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        Item it = itemAt(lx, ly, true);
        if (it == null) it = itemAt(lx, ly, false);
        if (it == null) return null;
        return new ItemTooltip(ui.assets, it.itemId, it, world.data() == null ? null : world.data().stats, false, null);
    }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        if (lx < 230) outScroll.scroll(amount);
        else inScroll.scroll(amount);
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
}
