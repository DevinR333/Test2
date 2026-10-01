package maple.ui.windows;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.utils.Align;
import maple.game.Names;
import maple.game.Social;
import maple.game.World;
import maple.gfx.Sprite;
import maple.ui.Button;
import maple.ui.Dialogs;
import maple.ui.JobNames;
import maple.ui.TabStrip;
import maple.ui.Tooltip;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The user list (UIWindow.img/UserList, 312x389; 009196f2): Buddy / Party / Guild / Guild Alliance /
 * Blacklist tabs on one window, each with its background (backgrnd3, backgrnd, backgrnd2, backgrnd2,
 * backgrnd) and the original buttons at their recovered places.
 */
public final class UserListWindow extends Window {
    private static final String B = "UIWindow.img/UserList/";
    private static final String[] BACKGROUNDS = {"backgrnd3", "backgrnd", "backgrnd2", "backgrnd2", "backgrnd"};
    private final World world;
    private final TabStrip tabs;
    private int tab;
    private final List<Button> buttons = new ArrayList<>();
    /** Selected row: a character id (buddy / party / guild member), or -1. */
    private int selected = -1;
    private String selectedName = "";
    private boolean onlineOnly;
    private final IntConsumer chatTo;
    private final java.util.function.BiConsumer<Integer, String> chatTarget;

    /** chatTarget(target, whisperName) points the chat bar at whisper / party / buddy / guild chat. */
    public UserListWindow(Ui ui, World world, int startTab, java.util.function.BiConsumer<Integer, String> chatTarget) {
        super(ui, "UserList", B + "backgrnd3");
        this.world = world;
        this.chatTarget = chatTarget;
        this.chatTo = t -> chatTarget.accept(t, null);
        if (w == 0) {
            w = 312;
            h = 389;
        }
        tabs = add(new TabStrip(ui.assets, B + "Tab", 5, 270));
        tabs.x = 3;
        tabs.onSelect = i -> {
            tab = i;
            selected = -1;
            build();
        };
        tab = startTab;
        tabs.selected = startTab;
        build();
    }

    @Override
    public void draw(UiDraw g) {
        background = ui.assets.sprite(B + BACKGROUNDS[tab]);
        super.draw(g);
    }

    private Button button(String path, float bx, float by, Runnable r) {
        Button b = add(new Button(ui.assets, B + path, bx, by, r));
        buttons.add(b);
        return b;
    }

    private void build() {
        for (Button b : buttons) remove(b);
        buttons.clear();
        if (close != null) remove(close);
        switch (tab) {
            case 0: buildBuddy(); break;
            case 1: buildParty(); break;
            case 2: case 3: buildGuild(tab == 3); break;
            default: buildBlacklist(); break;
        }
        close = add(new Button(ui.assets, "Basic.img/BtClose", 295, 6, this::close));
    }

    private void prompt(String text, java.util.function.Consumer<String> then) {
        ui.open(new TextPrompt(ui, text, "", 13, then));
    }

    private void notice(String text) {
        ui.open(new Dialogs.Notice(ui, text, false, null, null));
    }

    // ------------------------------------------------------------------ buddy

    private void buildBuddy() {
        button("Friend/BtAddFriend", 5, 81, () -> prompt("Please enter the name of your friend.", n -> world.buddyAdd(n, "Default Group")));
        button("Friend/BtAddGroup", 72, 81, () -> notice("Groups are created by typing a group name when you add a friend."));
        button("Friend/" + (onlineOnly ? "BtShowAll" : "BtShowOnline"), 232, 81, () -> {
            onlineOnly = !onlineOnly;
            build();
        });
        button("Friend/BtGroupWhisper", 8, 341, () -> chatTo.accept(3));
        button("Friend/BtMod", 8, 362, () -> notice("Select a friend and delete it to move it to another group."));
        button("Friend/BtDelete", 49, 362, () -> {
            if (selected < 0) return;
            int id = selected;
            ui.open(new Dialogs.Notice(ui, "Delete " + selectedName + " from your friends?", true, () -> world.buddyDelete(id), null));
        });
        button("Friend/BtChat", 99, 341, () -> chatTo.accept(3));
        button("Friend/BtParty", 99, 362, () -> {
            if (selected >= 0) world.partyInvite(selectedName);
        });
        button("Friend/" + (blacklist().contains(selectedName) ? "BtUnBlock" : "BtBlock"), 182, 362, () -> {
            if (selected < 0) return;
            toggleBlock(selectedName);
            build();
        });
        button("Friend/BtWhisper", 254, 341, () -> {
            if (selected >= 0) chatTarget.accept(1, selectedName);
        });
        button("Friend/BtMessage", 254, 362, () -> {
            if (selected >= 0) world.messengerInvite(selectedName);
        });
    }

    private List<Object[]> buddyRows() {
        List<Object[]> rows = new ArrayList<>(); // {header?, group or Buddy[] pair}
        java.util.Map<String, List<Social.Buddy>> groups = new java.util.LinkedHashMap<>();
        for (Social.Buddy b : world.social.buddies) groups.computeIfAbsent(b.group.isEmpty() ? "Default Group" : b.group, k -> new ArrayList<>()).add(b);
        for (java.util.Map.Entry<String, List<Social.Buddy>> e : groups.entrySet()) {
            int online = 0;
            for (Social.Buddy b : e.getValue()) if (b.channel >= 0) online++;
            rows.add(new Object[]{true, e.getKey() + "  (" + online + "/" + e.getValue().size() + ")"});
            List<Social.Buddy> shown = new ArrayList<>();
            for (Social.Buddy b : e.getValue()) if (!onlineOnly || b.channel >= 0) shown.add(b);
            for (int i = 0; i < shown.size(); i += 2) {
                rows.add(new Object[]{false, new Social.Buddy[]{shown.get(i), i + 1 < shown.size() ? shown.get(i + 1) : null}});
            }
        }
        return rows;
    }

    private void drawBuddy(UiDraw g) {
        g.text(world.social.buddies.size() + " / " + world.social.buddyCapacity, 214, 60, 81, Align.right, false, 12, false, 0xFFFFFFFF);
        float y = 113;
        for (Object[] row : buddyRows()) {
            if (y > 113 + 188 - 18) break;
            if ((Boolean) row[0]) {
                g.text((String) row[1], 13, y + 2, 270, Align.left, false, 12, true, 0xFF000000);
            } else {
                Social.Buddy[] pair = (Social.Buddy[]) row[1];
                for (int k = 0; k < 2; k++) {
                    Social.Buddy b = pair[k];
                    if (b == null) continue;
                    float x = 8 + k * 128;
                    if (b.id == selected) g.fill(x, y, 126, 18, 0xFF396093);
                    Sprite icon = ui.assets.sprite(B + "Friend/icon" + (b.channel >= 0 ? 0 : 1));
                    if (icon != null) g.image(icon, x + 4, y + 4);
                    g.text(b.name, x + 16, y + 2, 12, false, b.id == selected ? 0xFFFFFFFF : 0xFF000000);
                }
            }
            y += 18;
        }
        if (world.social.buddies.isEmpty()) g.text("No friends registered.", 13, 117, 12, false, 0xFF808080);
        if (selected >= 0) {
            String where = "Offline";
            for (Social.Buddy b : world.social.buddies) if (b.id == selected && b.channel >= 0) where = "Channel " + (b.channel + 1);
            g.text(selectedName + " - " + where, 10, 313, 12, false, 0xFF000000);
        }
    }

    private void pressBuddy(float lx, float ly) {
        float y = 113;
        for (Object[] row : buddyRows()) {
            if (ly >= y && ly < y + 18 && !(Boolean) row[0]) {
                Social.Buddy[] pair = (Social.Buddy[]) row[1];
                int k = lx < 8 + 128 ? 0 : 1;
                Social.Buddy b = pair[k];
                if (b != null) {
                    selected = b.id;
                    selectedName = b.name;
                    build();
                }
                return;
            }
            y += 18;
        }
    }

    // ------------------------------------------------------------------ party

    private boolean leader() {
        return world.social.partyId != 0 && world.data() != null && world.social.partyLeader == world.data().stats.id;
    }

    private void buildParty() {
        boolean in = world.social.partyId != 0;
        Button create = button("Party/BtCreate", 13, 355, world::partyCreate);
        create.disabled = in;
        button("Party/BtSearch", 72, 345, () -> notice("There is no one else in this world to search for."));
        Button invite = button("Party/BtInvite", 72, 365, () -> prompt("Enter the name of the character to invite.", world::partyInvite));
        invite.disabled = !leader();
        button("Party/BtHP", 131, 345, () -> {}).disabled = world.social.party.size() <= 1; // no other members' HP offline
        Button boss = button("Party/BtChangeBoss", 131, 365, () -> {
            if (selected >= 0) world.partyLeader(selected);
        });
        boss.disabled = !leader();
        Button kick = button("Party/BtKick", 190, 344, () -> {
            if (selected >= 0) world.partyExpel(selected);
        });
        kick.disabled = !leader();
        Button leave = button("Party/BtWithdraw", 190, 364, () -> ui.open(new Dialogs.Notice(ui,
                leader() ? "Do you want to disband the party?" : "Do you want to leave the party?", true, world::partyLeave, null)));
        leave.disabled = !in;
        button("Party/BtWhisper", 249, 345, () -> {
            if (selected >= 0) chatTarget.accept(1, selectedName);
        });
        button("Party/BtChat", 249, 365, () -> chatTo.accept(2));
    }

    private void memberRows(UiDraw g, List<Social.Member> members, float top, float height, float[] cols, boolean guild) {
        float y = top;
        for (Social.Member m : members) {
            if (y + 18 > top + height) break;
            if (m.id == selected) g.fill(6, y, 281, 18, 0xFF396093);
            int color = m.id == selected ? 0xFFFFFFFF : m.online ? 0xFF000000 : 0xFF808080;
            String name = m.name + (!guild && m.id == world.social.partyLeader ? " *" : "");
            g.text(name, 6 + cols[0], y + 2, 12, false, color);
            g.text(JobNames.name(m.job), 6 + cols[1], y + 2, 12, false, color);
            g.text(Integer.toString(m.level), 6 + cols[2], y + 2, 12, false, color);
            if (guild) {
                String rank = m.rank >= 1 && m.rank <= 5 && world.social.rankTitles[m.rank - 1] != null ? world.social.rankTitles[m.rank - 1] : "";
                g.text(rank, 6 + cols[3], y + 2, 12, false, color);
            }
            y += 18;
        }
    }

    private void drawParty(UiDraw g) {
        if (world.social.partyId == 0) {
            g.text("You are not in a party.", 6, 140, 281, Align.center, false, 12, false, 0xFF808080);
            return;
        }
        float y = 53;
        Sprite head = ui.assets.sprite(B + "Party/party0"), cols = ui.assets.sprite(B + "Party/party5");
        if (head != null) {
            g.image(head, 6, y);
            y += head.h;
        }
        if (cols != null) {
            g.image(cols, 7, y);
            y += cols.h;
        }
        Sprite row = ui.assets.sprite(B + "Party/party1");
        for (int i = 0; i < world.social.party.size() && row != null; i++) g.image(row, 7, y + i * 18);
        memberRows(g, world.social.party, y, 278 - (y - 53), new float[]{5, 120, 210}, false);
        Sprite foot = ui.assets.sprite(B + "Party/party2");
        if (foot != null) g.image(foot, 7, y + world.social.party.size() * 18);
        partyTop = y;
    }

    private float partyTop = 107;

    private void pressMembers(List<Social.Member> members, float top, float ly) {
        int i = (int) ((ly - top) / 18);
        if (ly >= top && i >= 0 && i < members.size()) {
            selected = members.get(i).id;
            selectedName = members.get(i).name;
            build();
        }
    }

    // ------------------------------------------------------------------ guild / alliance

    private void buildGuild(boolean alliance) {
        String b = alliance ? "GuildUnion/" : "Guild/GuildInfo/";
        boolean in = !alliance && world.social.guildId != 0;
        boolean master = in && rankOf(world.data().stats.id) <= 2;
        button(b + "BtInvite", 9, 316, () -> prompt("Enter the name of the character to invite.", n -> notice("There is no one by that name online."))).disabled = !master;
        button(b + "BtWithdraw", 80, 316, () -> ui.open(new Dialogs.Notice(ui, "Do you want to leave the guild?", true, world::guildLeave, null))).disabled = !in;
        button(b + "BtWhere", 159, 316, () -> {
            if (selected >= 0) notice(selectedName + " - " + (memberOnline(selected) ? "online" : "offline"));
        });
        button(b + "BtWhisper", 238, 316, () -> {
            if (selected >= 0) chatTarget.accept(1, selectedName);
        });
        button(b + "BtUp", 9, 336, () -> {}).disabled = true;
        button(b + "BtDown", 80, 336, () -> {}).disabled = true;
        button(b + "BtKick", 159, 336, () -> {}).disabled = true;
        button(b + "BtChat", 238, 336, () -> chatTo.accept(alliance ? 5 : 4));
        button(b + "Btnotice", alliance ? 288 : 287, 80, () -> prompt("Enter the guild notice.", world::guildNotice)).disabled = !master;
        button(b + "BtPartyInvite", 159, 363, () -> {
            if (selected >= 0) world.partyInvite(selectedName);
        });
        button(b + "BtInfo", 238, 363, () -> notice(in ? world.social.guildName + "\nGP: " + world.social.guildPoints
                + "\nMembers: " + world.social.guild.size() + " / " + world.social.guildCapacity : "You are not in a guild."));
        if (!alliance) button(b + "BtGuildBBS", 80, 363, () -> notice("The guild board has no posts.")).disabled = !in;
        if (in) button(b + "BtChange", 9, 363, () -> {}).disabled = true;
    }

    private int rankOf(int id) {
        for (Social.Member m : world.social.guild) if (m.id == id) return m.rank;
        return 5;
    }

    private boolean memberOnline(int id) {
        for (Social.Member m : world.social.guild) if (m.id == id) return m.online;
        for (Social.Member m : world.social.party) if (m.id == id) return m.online;
        return false;
    }

    private void drawGuild(UiDraw g, boolean alliance) {
        if (alliance || world.social.guildId == 0) {
            String msg = alliance ? "Your guild is not in an alliance." : "You are not in a guild.\nGuilds are founded with Heracle in Orbis.";
            g.text(msg, 6, 140, 300, Align.center, true, 12, false, 0xFF808080);
            return;
        }
        g.text(world.social.guildName, 3, 57, 306, Align.center, false, 12, true, 0xFFFFFFFF);
        String notice = world.social.guildNotice;
        g.text(notice.isEmpty() ? "Members: " + world.social.guild.size() + " / " + world.social.guildCapacity : notice, 18, 82, 265, Align.left, false, 12, false, 0xFF000000);
        memberRows(g, world.social.guild, 113, 192, new float[]{5, 102, 170, 210}, true);
    }

    // ------------------------------------------------------------------ blacklist (kept on this device)

    private Preferences prefs() {
        return Gdx.app.getPreferences("maple-offline");
    }

    private List<String> blacklist() {
        List<String> l = new ArrayList<>();
        if (world.data() == null) return l;
        for (String s : prefs().getString("blacklist." + world.data().stats.id, "").split(",")) if (!s.isEmpty()) l.add(s);
        return l;
    }

    private void saveBlacklist(List<String> l) {
        prefs().putString("blacklist." + world.data().stats.id, String.join(",", l)).flush();
    }

    private void toggleBlock(String name) {
        List<String> l = blacklist();
        if (!l.remove(name)) l.add(name);
        saveBlacklist(l);
    }

    private void buildBlacklist() {
        button("BlackList/BtAdd", 60, 355, () -> prompt("Enter the name of the character to block.", n -> {
            List<String> l = blacklist();
            if (!l.contains(n)) l.add(n);
            saveBlacklist(l);
        }));
        button("BlackList/BtDelete", 180, 355, () -> {
            List<String> l = blacklist();
            l.remove(selectedName);
            saveBlacklist(l);
            selected = -1;
        });
    }

    private void drawBlacklist(UiDraw g) {
        Sprite head = ui.assets.sprite(B + "BlackList/blacklist0");
        if (head != null) g.image(head, 6, 53);
        float y = 53 + (head == null ? 27 : head.h);
        List<String> l = blacklist();
        for (int i = 0; i < l.size(); i++) {
            float ry = y + i * 18;
            if (ry > 53 + 278 - 18) break;
            if (l.get(i).equals(selectedName) && selected == -2) g.fill(6, ry, 281, 18, 0xFF396093);
            g.text(l.get(i), 11, ry + 2, 12, false, 0xFF000000);
        }
        if (l.isEmpty()) g.text("No one is blocked.", 11, y + 2, 12, false, 0xFF808080);
    }

    // ------------------------------------------------------------------ common

    @Override
    protected void drawContent(UiDraw g) {
        if (world.data() == null) return;
        switch (tab) {
            case 0: drawBuddy(g); break;
            case 1: drawParty(g); break;
            case 2: drawGuild(g, false); break;
            case 3: drawGuild(g, true); break;
            default: drawBlacklist(g); break;
        }
    }

    @Override
    protected boolean pressBody(float lx, float ly) {
        switch (tab) {
            case 0: pressBuddy(lx, ly); break;
            case 1: pressMembers(world.social.party, partyTop, ly); break;
            case 2: pressMembers(world.social.guild, 113, ly); break;
            case 4: {
                List<String> l = blacklist();
                int i = (int) ((ly - 80) / 18);
                if (ly >= 80 && i >= 0 && i < l.size()) {
                    selectedName = l.get(i);
                    selected = -2;
                }
                break;
            }
            default: break;
        }
        return false;
    }

    @Override
    public void onDoubleClick(float lx, float ly) {
        pressBody(lx, ly);
        if (selected >= 0 && !selectedName.isEmpty()) chatTarget.accept(1, selectedName);
    }

    @Override
    public Tooltip tooltip(float lx, float ly) {
        if (tab == 1) {
            int i = (int) ((ly - partyTop) / 18);
            if (ly >= partyTop && i >= 0 && i < world.social.party.size()) {
                Social.Member m = world.social.party.get(i);
                String where = Names.map(m.mapId);
                return Tooltip.text(m.name + (where.isEmpty() ? "" : "\n" + where));
            }
        }
        return null;
    }

    /** The party / guild changed under the open window: buttons follow it. */
    @Override
    public void refresh() {
        build();
    }

    @Override
    public boolean onKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    public void selectTab(int t) {
        tab = t;
        tabs.selected = t;
        selected = -1;
        build();
    }
}
