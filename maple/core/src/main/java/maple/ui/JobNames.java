package maple.ui;

import java.util.HashMap;
import java.util.Map;

/** Job names exactly as the v83 client prints them (string table recovered by openms, 004a77ef). */
public final class JobNames {
    private static final Map<Integer, String> N = new HashMap<>();

    static {
        N.put(0, "Beginner");
        N.put(100, "Swordman");
        N.put(110, "Fighter");
        N.put(111, "Crusader");
        N.put(112, "Hero");
        N.put(120, "Page");
        N.put(121, "White Knight");
        N.put(122, "Paladin");
        N.put(130, "Spearman");
        N.put(131, "Dragon Knight");
        N.put(132, "Dark Knight");
        N.put(200, "Magician");
        N.put(210, "Wizard (Fire,Poison)");
        N.put(211, "Mage(Fire, Poison)");
        N.put(212, "Arch Mage(Fire,Poison)");
        N.put(220, "Wizard(Ice,Lightning)");
        N.put(221, "Mage(Ice,Lightning)");
        N.put(222, "Arch Mage(Ice,Lightning)");
        N.put(230, "Cleric");
        N.put(231, "Priest");
        N.put(232, "Bishop");
        N.put(300, "Archer");
        N.put(310, "Hunter");
        N.put(311, "Ranger");
        N.put(312, "Bowmaster");
        N.put(320, "Crossbow man");
        N.put(321, "Sniper");
        N.put(322, "Marksman");
        N.put(400, "Rogue");
        N.put(410, "Assassin");
        N.put(411, "Hermit");
        N.put(412, "Night Lord");
        N.put(420, "Bandit");
        N.put(421, "Chief Bandit");
        N.put(422, "Shadower");
        N.put(500, "Pirate");
        N.put(510, "Brawler");
        N.put(511, "Marauder");
        N.put(512, "Buccaneer");
        N.put(520, "Gunslinger");
        N.put(521, "Outlaw");
        N.put(522, "Corsair");
        N.put(800, "매니저");
        N.put(900, "GM");
        N.put(910, "SuperGM");
        N.put(920, "MWLB");
        N.put(1000, "Noblesse");
        N.put(1100, "Dawn Warrior");
        N.put(1110, "Dawn Warrior");
        N.put(1111, "Dawn Warrior");
        N.put(1112, "Dawn Warrior");
        N.put(1200, "Blaze Wizard");
        N.put(1210, "Blaze Wizard");
        N.put(1211, "Blaze Wizard");
        N.put(1212, "Blaze Wizard");
        N.put(1300, "Wind Archer");
        N.put(1310, "Wind Archer");
        N.put(1311, "Wind Archer");
        N.put(1312, "Wind Archer");
        N.put(1400, "Night Walker");
        N.put(1410, "Night Walker");
        N.put(1411, "Night Walker");
        N.put(1412, "Night Walker");
        N.put(1500, "Thunder Breaker");
        N.put(1510, "Thunder Breaker");
        N.put(1511, "Thunder Breaker");
        N.put(1512, "Thunder Breaker");
        N.put(2000, "Legend");
        N.put(2100, "Aran");
        N.put(2110, "Aran");
        N.put(2111, "Aran");
        N.put(2112, "Aran");
        N.put(2001, "Evan0");
        N.put(2200, "Evan1");
        N.put(2210, "Evan2");
        N.put(2211, "Evan3");
        N.put(2212, "Evan4");
        N.put(2213, "Evan5");
        N.put(2214, "Evan6");
        N.put(2215, "Evan7");
        N.put(2216, "Evan8");
        N.put(2217, "Evan9");
        N.put(2218, "Evan10");
    }

    private JobNames() {}

    public static String name(int job) {
        String s = N.get(job);
        return s == null ? "" : s;
    }
}
