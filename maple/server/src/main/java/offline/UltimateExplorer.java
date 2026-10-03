package offline;

import maple.wz.WzNode;
import maple.wz.WzPatches;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Ultimate Explorers (GMS v.96 AfterShock), backported onto the pre-Big Bang game. A level-120
 * Cygnus Knight finishes Empress Cygnus's "Empress's Grace" (10 Peridots from Harps and Blood Harps
 * in Leafre; rewards Empress's Shout and Empress's Prayer), then Cygnus lets it make one Ultimate
 * Explorer: a new character at level 50 with its 2nd job, the Empress's Fine Set and a weapon for its
 * job, the Successor medal named after the knight and Empress's Might. At level 70 Cygnus trades the
 * Fine Set for the Empress's Brilliant Set. Big Bang's Cygnus 4th job is left out.
 *
 * Everything is added to the data as it is read (the game and the server share it). The weapons are
 * the level-60 and level-80 weapons the Empress's sets were made from (same names, art and attack);
 * the armour's own art is not in the v83 files, so it borrows the Noblesse hat and robe, and silver
 * (Fine) or gold (Brilliant) Sylvia gloves and war boots.
 */
public final class UltimateExplorer {
    public static final int CYGNUS = 1101000;
    public static final int PERIDOT = 4032579;
    public static final int HARP = 8140001, BLOOD_HARP = 8140002;
    /** Peridots drop very rarely (per million), only while Empress's Grace is in progress. */
    public static final int PERIDOT_CHANCE = 4000;
    public static final int QUEST_GRACE = 20890;
    /** Records: on the knight, the name of the explorer it made; on the explorer, its knight's name. */
    public static final int QUEST_MADE = 20891, QUEST_SUCCESSOR = 20892, QUEST_BRILLIANT = 20893;
    public static final int SHOUT = 10001090, PRAYER = 10001091, MIGHT = 1090;
    public static final int MEDAL = 1142166;
    /** NPC get-text that opens the creation screen instead of a text box (answer: see {@link #parseChoice}). */
    public static final String CREATOR = "#UltimateExplorerCreator#";
    public static final int START_MAP = 130000000; // Ereve, where Cygnus makes them
    public static final int START_LEVEL = 50, BRILLIANT_LEVEL = 70;

    /** The 2nd jobs an Ultimate Explorer may start as, and the weapon each gets (Fine, Brilliant). */
    public static final int[][] JOBS = {
            {110, 1402073, 1402074}, {120, 1402073, 1402074}, {130, 1432062, 1432063},
            {210, 1372059, 1372060}, {220, 1372059, 1372060}, {230, 1372059, 1372060},
            {310, 1452086, 1452087}, {320, 1462076, 1462077},
            {410, 1472101, 1472102}, {420, 1332100, 1332101},
            {510, 1482047, 1482048}, {520, 1492049, 1492050},
    };

    public static final int FINE_HAT = 1003074, FINE_ROBE = 1052304, FINE_GLOVES = 1082263, FINE_SHOES = 1072476;
    public static final int BRILLIANT_HAT = 1003075, BRILLIANT_ROBE = 1052305, BRILLIANT_GLOVES = 1082264, BRILLIANT_SHOES = 1072477;

    // stat order: STR DEX INT LUK HP MP PAD MAD PDD MDD ACC EVA Speed Jump
    private static int[] all(int s, int hp, int mp, int def) {
        return new int[]{s, s, s, s, hp, mp, 0, 0, def, def, 0, 0, 0, 0};
    }

    private static int[] attack(int pad, int mad) {
        return new int[]{0, 0, 0, 0, 0, 0, pad, mad, 0, 0, 0, 0, 0, 0};
    }

    private static final String[] STAT_KEYS = {"incSTR", "incDEX", "incINT", "incLUK", "incMHP", "incMMP", "incPAD",
            "incMAD", "incPDD", "incMDD", "incACC", "incEVA", "incSpeed", "incJump"};

    /** One new equip: id, the item whose image it copies, folder, name, level, stats (null: keep the base's). */
    private static final class Equip {
        final int id, base, level, tuc;
        final String folder, name;
        final int[] stats;

        Equip(int id, int base, String folder, String name, int level, int tuc, int[] stats) {
            this.id = id;
            this.base = base;
            this.folder = folder;
            this.name = name;
            this.level = level;
            this.tuc = tuc;
            this.stats = stats;
        }
    }

    private static final List<Equip> EQUIPS = new ArrayList<>();

    static {
        // Empress's Fine Set (Lv. 60)
        EQUIPS.add(new Equip(FINE_HAT, 1002869, "Cap", "Empress's Fine Hat", 60, 7, all(7, 0, 0, 50)));
        EQUIPS.add(new Equip(FINE_ROBE, 1052177, "Longcoat", "Empress's Fine Robe", 60, 10, all(3, 20, 20, 60)));
        EQUIPS.add(new Equip(FINE_GLOVES, 1082043, "Glove", "Empress's Fine Gloves", 60, 5, all(2, 15, 0, 24)));
        EQUIPS.add(new Equip(FINE_SHOES, 1072051, "Shoes", "Empress's Fine Shoes", 60, 5, all(2, 0, 0, 35)));
        EQUIPS.add(new Equip(1402073, 1402011, "Weapon", "Empress's Fine Sparta", 60, 7, attack(80, 0)));
        EQUIPS.add(new Equip(1432062, 1432006, "Weapon", "Empress's Fine Holy Spear", 60, 7, attack(82, 0)));
        EQUIPS.add(new Equip(1372059, 1372014, "Weapon", "Empress's Fine Evil Tale", 60, 7, attack(48, 78)));
        EQUIPS.add(new Equip(1452086, 1452004, "Weapon", "Empress's Fine Asianic Bow", 60, 7, attack(75, 0)));
        EQUIPS.add(new Equip(1462076, 1462008, "Weapon", "Empress's Fine Golden Crow", 60, 7, attack(78, 0)));
        int[] claw = attack(30, 0);
        claw[3] = 5;
        EQUIPS.add(new Equip(1472101, 1472025, "Weapon", "Empress's Fine Dark Gigantic", 60, 7, claw));
        EQUIPS.add(new Equip(1332100, 1332015, "Weapon", "Empress's Fine Deadly Fin", 60, 7, attack(72, 0)));
        EQUIPS.add(new Equip(1482047, 1482017, "Weapon", "Empress's Fine Seraphims", 60, 7, attack(58, 0)));
        EQUIPS.add(new Equip(1492049, 1492008, "Weapon", "Empress's Fine Burning Hell", 60, 7, attack(58, 0)));
        // Empress's Brilliant Set (Lv. 80)
        EQUIPS.add(new Equip(BRILLIANT_HAT, 1002869, "Cap", "Empress's Brilliant Hat", 80, 7, all(10, 0, 0, 50)));
        EQUIPS.add(new Equip(BRILLIANT_ROBE, 1052177, "Longcoat", "Empress's Brilliant Robe", 80, 10, all(5, 0, 0, 100)));
        EQUIPS.add(new Equip(BRILLIANT_GLOVES, 1082044, "Glove", "Empress's Brilliant Gloves", 80, 5, all(3, 0, 0, 31)));
        EQUIPS.add(new Equip(BRILLIANT_SHOES, 1072053, "Shoes", "Empress's Brilliant Shoes", 80, 5, all(3, 0, 0, 46)));
        EQUIPS.add(new Equip(1402074, 1402015, "Weapon", "Empress's Brilliant Heaven's Gate", 80, 7, attack(90, 0)));
        EQUIPS.add(new Equip(1432063, 1432010, "Weapon", "Empress's Brilliant Omega Spear", 80, 7, attack(92, 0)));
        EQUIPS.add(new Equip(1372060, 1372016, "Weapon", "Empress's Brilliant Phoenix Wand", 80, 7, attack(60, 98)));
        EQUIPS.add(new Equip(1452087, 1452012, "Weapon", "Empress's Brilliant Marine Arund", 80, 7, attack(85, 0)));
        EQUIPS.add(new Equip(1462077, 1462010, "Weapon", "Empress's Brilliant Marine Raven", 80, 7, attack(88, 0)));
        int[] mamba = attack(38, 0);
        mamba[1] = 5;
        mamba[8] = 4;
        EQUIPS.add(new Equip(1472102, 1472031, "Weapon", "Empress's Brilliant Black Mamba", 80, 7, mamba));
        EQUIPS.add(new Equip(1332101, 1332023, "Weapon", "Empress's Brilliant Dragon Tail", 80, 7, attack(85, 0)));
        EQUIPS.add(new Equip(1482048, 1482031, "Weapon", "Empress's Brilliant Steel Renault", 80, 7, attack(66, 0)));
        EQUIPS.add(new Equip(1492050, 1492010, "Weapon", "Empress's Brilliant Infinity's Wrath", 80, 7, attack(66, 0)));
        // the knight's medal: HP/MP +200, ATT/M.ATT +2, Speed/Jump +10
        EQUIPS.add(new Equip(MEDAL, 1142069, "Accessory", "Successor Medal", 0, 0,
                new int[]{0, 0, 0, 0, 200, 200, 2, 2, 0, 0, 0, 0, 10, 10}));
    }

    private static final int[] FINE_ARMOUR = {FINE_HAT, FINE_ROBE, FINE_GLOVES, FINE_SHOES};
    private static final int[] BRILLIANT_ARMOUR = {BRILLIANT_HAT, BRILLIANT_ROBE, BRILLIANT_GLOVES, BRILLIANT_SHOES};

    public static boolean isFine(int id) {
        for (int a : FINE_ARMOUR) if (a == id) return true;
        for (int[] j : JOBS) if (j[1] == id) return true;
        return false;
    }

    public static boolean isBrilliant(int id) {
        for (int a : BRILLIANT_ARMOUR) if (a == id) return true;
        for (int[] j : JOBS) if (j[2] == id) return true;
        return false;
    }

    /** Fine or Brilliant set piece, or the medal. */
    public static boolean isEmpressItem(int id) {
        return isFine(id) || isBrilliant(id) || id == MEDAL;
    }

    public static int[] fineSet(int job2) {
        int w = weapon(job2, 1);
        return new int[]{FINE_HAT, FINE_ROBE, FINE_GLOVES, FINE_SHOES, w};
    }

    public static int[] brilliantSet(int job2) {
        int w = weapon(job2, 2);
        return new int[]{BRILLIANT_HAT, BRILLIANT_ROBE, BRILLIANT_GLOVES, BRILLIANT_SHOES, w};
    }

    /** The set weapon for this job's branch (tier 1 Fine, 2 Brilliant). */
    public static int weapon(int job, int tier) {
        int branch = job % 1000 / 10 * 10;
        for (int[] j : JOBS) if (j[0] == branch) return j[tier];
        return 0;
    }

    /**
     * Set effects for what is worn, in the stat order above. Fine (5 pieces: hat, robe, gloves, shoes,
     * weapon): all stats +6, HP/MP +100, ATT/M.ATT +5, DEF +150, Speed +20. Brilliant: 4 pieces
     * HP/MP +150, DEF +50; 5 pieces also all stats +8, ATT/M.ATT +7, DEF +150, Speed +20.
     */
    public static int[] setBonus(Collection<Integer> worn) {
        int fine = 0, brilliant = 0;
        for (int id : worn) {
            if (isFine(id)) fine++;
            else if (isBrilliant(id)) brilliant++;
        }
        int[] b = new int[14];
        if (fine >= 5) add(b, new int[]{6, 6, 6, 6, 100, 100, 5, 5, 150, 150, 0, 0, 20, 0});
        if (brilliant >= 4) add(b, new int[]{0, 0, 0, 0, 150, 150, 0, 0, 50, 50, 0, 0, 0, 0});
        if (brilliant >= 5) add(b, new int[]{8, 8, 8, 8, 0, 0, 7, 7, 150, 150, 0, 0, 20, 0});
        return b;
    }

    private static void add(int[] to, int[] v) {
        for (int i = 0; i < to.length; i++) to[i] += v[i];
    }

    /** Empress's Blessing: +1 ATT, M.ATT, accuracy and avoid per 5 levels of the best Cygnus Knight (max 24). */
    public static int blessing(int bestKnightLevel) {
        return OfflineOptions.empressBlessing ? Math.min(24, Math.max(0, bestKnightLevel) / 5) : 0;
    }

    public static boolean isKnight(int job) {
        return job >= 1000 && job < 2000;
    }

    /** Level-120 Cygnus Knight (3rd job; 4th is Big Bang's). */
    public static boolean knightJobs(int job) {
        return job >= 1111 && job <= 1512 && job % 100 / 10 == 1 && job % 10 >= 1;
    }

    /** "name|job|face|hair|color|skin|gender" from the creation screen, or null. */
    public static int[] parseChoice(String s, StringBuilder name) {
        if (s == null) return null;
        String[] p = s.split("\\|");
        if (p.length != 7) return null;
        name.append(p[0]);
        int[] v = new int[6];
        try {
            for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(p[i + 1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }

    private static boolean installed;

    private UltimateExplorer() {}

    static synchronized void install() {
        if (installed) return;
        installed = true;
        for (Equip e : EQUIPS) {
            String dir = "Character/" + e.folder + "/";
            String path = dir + String.format("%08d.img", e.id);
            WzPatches.copyImage(path, dir + String.format("%08d.img", e.base));
            WzPatches.register(path, img -> {
                WzNode info = img.get("info");
                if (!info.exists()) return;
                for (String k : STAT_KEYS) info.remove(k);
                for (String k : new String[]{"reqLevel", "tradeBlock", "cash", "price", "notSale", "quest", "only"}) info.remove(k);
                for (int i = 0; i < STAT_KEYS.length; i++) if (e.stats[i] != 0) info.addInt(STAT_KEYS[i], e.stats[i]);
                info.addInt("reqLevel", e.level).addInt("tradeBlock", 1).addInt("price", 1);
                if (e.tuc > 0) info.addInt("tuc", e.tuc);
                if (e.id == MEDAL) {
                    for (String k : new String[]{"reqSTR", "reqDEX", "reqINT", "reqLUK", "reqJob", "tuc"}) info.remove(k);
                    info.addInt("only", 1);
                }
                // set gear asks only for its level (and the weapon for its job)
                for (String k : new String[]{"reqSTR", "reqDEX", "reqINT", "reqLUK"}) info.remove(k);
                if (!e.folder.equals("Weapon")) info.remove("reqJob");
            });
        }
        WzPatches.register("String/Eqp.img", img -> {
            WzNode eqp = img.get("Eqp");
            for (Equip e : EQUIPS) {
                WzNode folder = eqp.get(e.folder);
                if (!folder.exists()) continue;
                String desc = e.id == MEDAL
                        ? "A medal Empress Cygnus gives the Ultimate Explorer a Cygnus Knight has brought to her. It carries that knight's name: you are their successor."
                        : isFine(e.id)
                        ? "Empress Cygnus's gift to the Ultimate Explorers. Wear all five pieces of the Empress's Fine Set for its set effect."
                        : "Empress Cygnus's gift to Ultimate Explorers who reach Lv. 70. Wear four or five pieces of the Empress's Brilliant Set for its set effects.";
                folder.addProp(Integer.toString(e.id)).addString("name", e.name).addString("desc", desc);
            }
        });
        // Peridot (Etc): the green gem the Harps of Leafre keep
        WzPatches.register("Item/Etc/0403.img", img -> {
            String id = "0" + PERIDOT;
            if (img.child(id) != null) return;
            WzNode root = img;
            while (root.parent != null) root = root.parent;
            WzNode look = root.path("Etc/0402.img/04021003/info"); // Emerald
            WzNode info = img.addProp(id).addProp("info");
            info.addExisting(look.child("icon")).addExisting(look.child("iconRaw"));
            info.addInt("slotMax", 100).addInt("quest", 1).addInt("price", 1);
        });
        WzPatches.register("String/Etc.img", img -> {
            img.get("Etc").addProp(Integer.toString(PERIDOT)).addString("name", "Peridot")
                    .addString("desc", "A clear green gem that Harps and Blood Harps of Leafre hide very rarely. Empress Cygnus needs these.");
        });
        installSkills();
        installQuest();
    }

    private static void installSkills() {
        WzPatches.register("Skill/1000.img", img -> {
            WzNode skills = img.get("skill");
            WzNode echo = skills.get(Integer.toString(10001005));
            WzNode blessing = skills.get(Integer.toString(10000012));
            // Empress's Shout: passive, max HP and MP +20%
            WzNode shout = skills.addProp(Integer.toString(SHOUT));
            copyIcons(blessing.exists() ? blessing : echo, shout);
            shout.addProp("level").addProp("1").addInt("x", 20);
            // Empress's Prayer: ATT and M.ATT +4% for 2 hours, once a day (Echo of Hero's buff)
            WzNode prayer = skills.addProp(Integer.toString(PRAYER));
            copyIcons(echo, prayer);
            prayer.addExisting(echo.child("effect"));
            prayer.addProp("level").addProp("1").addInt("mpCon", 0).addInt("x", 4).addInt("time", 7200).addInt("cooltime", 86400);
        });
        WzPatches.register("Skill/000.img", img -> {
            WzNode skills = img.get("skill");
            WzNode might = skills.addProp(String.format("%07d", MIGHT));
            copyIcons(skills.get("0000012"), might);
            might.addProp("level").addProp("1");
        });
        WzPatches.register("String/Skill.img", img -> {
            img.addProp(Integer.toString(SHOUT)).addString("name", "Empress's Shout")
                    .addString("desc", "[Master Level : 1]\\nThe Empress's voice strengthens you. Permanently increases Max HP and Max MP.")
                    .addString("h1", "Max HP +20%, Max MP +20%");
            img.addProp(Integer.toString(PRAYER)).addString("name", "Empress's Prayer")
                    .addString("desc", "[Master Level : 1]\\nThe Empress's prayer for her knight. Increases Weapon and Magic Attack.\\n#cCan be used once every 24 hours.#")
                    .addString("h1", "Weapon Attack +4%, Magic Attack +4% for 2 hours");
            img.addProp(String.format("%07d", MIGHT)).addString("name", "Empress's Might")
                    .addString("desc", "[Master Level : 1]\\nThe might of Empress Cygnus, given to her Ultimate Explorers.")
                    .addString("h1", "You can equip items up to 10 levels above your level, as long as you meet their other requirements.");
        });
    }

    private static void copyIcons(WzNode from, WzNode to) {
        for (String k : new String[]{"icon", "iconMouseOver", "iconDisabled"}) to.addExisting(from.child(k));
    }

    private static void installQuest() {
        String q = Integer.toString(QUEST_GRACE);
        WzPatches.register("Quest/QuestInfo.img", img -> {
            img.addProp(q).addString("name", "Empress's Grace")
                    .addString("0", "#p1101000# wants to grant me the Empress's grace. She needs 10 #t" + PERIDOT + "#s that #o" + HARP + "#s and #o" + BLOOD_HARP + "#s of Leafre hide, very rarely.")
                    .addString("1", "Hunt #o" + HARP + "#s and #o" + BLOOD_HARP + "#s in Leafre for 10 #t" + PERIDOT + "#s and bring them to #p1101000#.\\n\\n#i" + PERIDOT + "# #t" + PERIDOT + "#: #c" + PERIDOT + "# / 10")
                    .addString("2", "I brought #p1101000# 10 #t" + PERIDOT + "#s and received the Empress's grace: #bEmpress's Shout#k and #bEmpress's Prayer#k. Now I can give my strength to an #bUltimate Explorer#k through her.")
                    .addInt("area", 15);
        });
        WzPatches.register("Quest/Check.img", img -> {
            WzNode quest = img.addProp(q);
            WzNode start = quest.addProp("0");
            start.addInt("npc", CYGNUS).addInt("lvmin", 120);
            WzNode jobs = start.addProp("job");
            int n = 0;
            for (int c = 1; c <= 5; c++) for (int j = 1; j <= 2; j++) jobs.addInt(Integer.toString(n++), 1000 + c * 100 + 10 + j);
            WzNode end = quest.addProp("1");
            end.addInt("npc", CYGNUS);
            end.addProp("item").addProp("0").addInt("id", PERIDOT).addInt("count", 10);
        });
        WzPatches.register("Quest/Act.img", img -> {
            WzNode quest = img.addProp(q);
            quest.addProp("0");
            WzNode end = quest.addProp("1");
            end.addProp("item").addProp("0").addInt("id", PERIDOT).addInt("count", -10);
            WzNode skills = end.addProp("skill");
            int[] ids = {SHOUT, PRAYER};
            for (int i = 0; i < ids.length; i++) {
                WzNode s = skills.addProp(Integer.toString(i));
                s.addInt("id", ids[i]).addInt("skillLevel", 1).addInt("masterLevel", 1);
                WzNode jobs = s.addProp("job");
                int n = 0;
                for (int c = 1; c <= 5; c++) for (int j = 1; j <= 2; j++) jobs.addInt(Integer.toString(n++), 1000 + c * 100 + 10 + j);
            }
        });
        WzPatches.register("Quest/Say.img", img -> {
            WzNode quest = img.addProp(q);
            WzNode start = quest.addProp("0");
            start.addString("0", "You have grown into one of the finest knights of Ereve, #h0#. I have watched you protect Maple World, and I want to give you something in return: the Empress's grace.");
            start.addString("1", "But my power alone is not enough. I need #b10 #t" + PERIDOT + "#s#k, the green gems that the #r#o" + HARP + "#s#k and #r#o" + BLOOD_HARP + "#s#k of Leafre keep. They hide them well, so you will have to hunt many of them. Will you bring them to me?");
            start.addProp("yes").addString("0", "Thank you. The Harps live in the forests of #bLeafre#k. Be patient: a #t" + PERIDOT + "# is rarely found.");
            start.addProp("no").addString("0", "I understand. Come back when you are ready.");
            WzNode end = quest.addProp("1");
            end.addString("0", "You found all 10 #t" + PERIDOT + "#s... Thank you, #h0#. Now, receive the Empress's grace.");
            end.addString("1", "#bEmpress's Shout#k will strengthen your body for good, and #bEmpress's Prayer#k will strengthen your attacks for a while each day.\\r\\n\\r\\nThere is one more thing. A knight who has come this far can pass their strength on to an #bUltimate Explorer#k. When you are ready, talk to me again.");
            end.addProp("stop").addProp("item").addString("0", "You don't have all 10 #t" + PERIDOT + "#s yet. The #o" + HARP + "#s and #o" + BLOOD_HARP + "#s of Leafre hide them.");
        });
    }

    // ------------------------------------------------------------------ server logic

    /** Best level among the account's other Cygnus Knights in this world (Empress's Blessing). */
    public static int bestKnightLevel(int accountId, int world, int exceptCharacter) {
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement(
                     "SELECT MAX(level) FROM characters WHERE accountid = ? AND world = ? AND id <> ? AND job >= 1000 AND job < 2000")) {
            ps.setInt(1, accountId);
            ps.setInt(2, world);
            ps.setInt(3, exceptCharacter);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (java.sql.SQLException e) {
            return 0;
        }
    }

    /** What the knight may do with Cygnus now: 0 nothing, 1 make an Ultimate Explorer, 2 already made one. */
    public static int knightState(client.Character knight) {
        if (!isKnight(knight.getJob().getId()) || knight.getLevel() < 120) return 0;
        if (knight.getQuest(QUEST_GRACE).getStatus() != client.QuestStatus.Status.COMPLETED) return 0;
        String made = record(knight, QUEST_MADE);
        return made == null || made.isEmpty() ? 1 : 2;
    }

    static String record(client.Character chr, int quest) {
        String v = chr.getQuest(quest).getProgress(0);
        return v == null ? "" : v;
    }

    public static boolean upgraded(client.Character chr) {
        return !record(chr, QUEST_BRILLIANT).isEmpty();
    }

    public static boolean isUltimate(client.Character chr) {
        return chr.getSkillLevel(MIGHT) > 0;
    }

    /**
     * Makes the knight's Ultimate Explorer: level 50 in its 2nd job, stats as a level-50 explorer of
     * that class would have them (main stat, and the secondary stat its gear needs), its unspent SP,
     * Empress's Might, the Fine Set and weapon worn and the Successor medal with the knight's name.
     * 0 made, -1 name taken or invalid, -2 bad choice, -3 no free character slot, -4 not allowed.
     */
    public static synchronized int create(client.Client c, client.Character knight, String name, int job2, int face, int hair, int skin, int gender) {
        if (knightState(knight) != 1) return -4;
        if (weapon(job2, 1) == 0 || job2 % 10 != 0 || job2 >= 1000) return -2;
        if (face / 10000 != 2 || hair / 10000 != 3 || skin < 0 || skin > 9 || gender < 0 || gender > 1) return -2;
        if (!name.matches("[A-Za-z0-9]{4,12}") || !client.Character.canCreateChar(name)) return -1;
        if (c.getAvailableCharacterWorldSlots() <= 0 && c.getAvailableCharacterSlots() <= 0) return -3;

        client.Job job = client.Job.getById(job2);
        if (job == null) return -2;
        int cls = job2 / 100;
        client.creator.CharacterFactoryRecipe recipe = new client.creator.CharacterFactoryRecipe(job, START_LEVEL, START_MAP, 0, 0, 0, 0);
        // 25 points at creation + 5 per level from 2 to 50, spent like Auto-Assign would
        int total = 25 + 5 * (START_LEVEL - 1);
        int str = 4, dex = 4, intel = 4, luk = 4;
        switch (cls) {
            case 2: luk = START_LEVEL; intel = total - luk - 8; break;
            case 3: str = START_LEVEL / 2 + 4; dex = total - str - 8; break;
            case 4: dex = START_LEVEL; luk = total - dex - 8; break;
            case 5:
                if (job2 == 520) { str = START_LEVEL / 2 + 4; dex = total - str - 8; }
                else { dex = START_LEVEL / 2 + 4; str = total - dex - 8; }
                break;
            default: dex = START_LEVEL; str = total - dex - 8; break;
        }
        recipe.setStr(str);
        recipe.setDex(dex);
        recipe.setInt(intel);
        recipe.setLuk(luk);
        recipe.setRemainingAp(0);
        recipe.setRemainingSp(1 + 3 * 20 + 1 + 3 * 20); // 1st and 2nd job advancements, levels 11-50
        // HP / MP a level-50 character of the class has (average gains: beginner 1-10, job advancements, 11-50)
        int[] hpMp;
        switch (cls) {
            case 1: hpMp = new int[]{1766, 304}; break;
            case 2: hpMp = new int[]{656, 1840}; break;
            case 5: hpMp = new int[]{1626, 1116}; break;
            default: hpMp = new int[]{1506, 916}; break;
        }
        recipe.setMaxHp(hpMp[0]);
        recipe.setMaxMp(hpMp[1]);
        recipe.addStartingSkillLevel(client.SkillFactory.getSkill(MIGHT), 1);

        client.Character chr = client.Character.getDefault(c);
        chr.setWorld(c.getWorld());
        chr.setSkinColor(client.SkinColor.getById(skin));
        chr.setGender(gender);
        chr.setName(name);
        chr.setHair(hair);
        chr.setFace(face);
        chr.setLevel(START_LEVEL);
        chr.setJob(job);
        chr.setMapId(START_MAP);
        server.ItemInformationProvider ii = server.ItemInformationProvider.getInstance();
        client.inventory.Inventory equipped = chr.getInventory(client.inventory.InventoryType.EQUIPPED);
        int[] set = fineSet(job2);
        short[] slots = {-1, -5, -8, -7, -11};
        for (int i = 0; i < set.length; i++) {
            client.inventory.Item it = ii.getEquipById(set[i]);
            it.setPosition(slots[i]);
            equipped.addItemFromDB(it);
        }
        client.inventory.Item medal = ii.getEquipById(MEDAL);
        medal.setOwner(knight.getName());
        medal.setPosition((short) -49);
        equipped.addItemFromDB(medal);
        if (!chr.insertNewChar(recipe)) return -2;
        net.server.Server.getInstance().createCharacterEntry(chr);
        knight.setQuestProgress(QUEST_MADE, 0, name);
        return 0;
    }

    /** At level 70 an Ultimate Explorer trades its Fine Set for the Brilliant Set (once): 0 done, -1 not yet, -2 bag full, -3 already. */
    public static int upgrade(client.Character chr) {
        if (!isUltimate(chr)) return -1;
        String done = record(chr, QUEST_BRILLIANT);
        if (done != null && !done.isEmpty()) return -3;
        if (chr.getLevel() < BRILLIANT_LEVEL) return -1;
        int[] set = brilliantSet(chr.getJob().getId());
        if (set[4] == 0) set[4] = brilliantSet(chr.getJob().getId() / 10 * 10)[4];
        client.inventory.Inventory bag = chr.getInventory(client.inventory.InventoryType.EQUIP);
        if (bag.getNumFreeSlot() < set.length) return -2;
        for (int id : set) {
            if (id == 0) continue;
            client.inventory.manipulator.InventoryManipulator.addById(chr.getClient(), id, (short) 1, "", -1);
        }
        chr.setQuestProgress(QUEST_BRILLIANT, 0, "1");
        return 0;
    }
}
