package maple.game;

import maple.net.model.CharStats;
import maple.net.model.Item;
import maple.net.model.PlayerData;

/**
 * Totals the client derives from base stats + equipment (+ buffs): attack, defence, accuracy, avoid,
 * speed, jump and the damage range. Weapon multipliers and main/secondary stats match the server's
 * own calculation (Cosmic Character.calculateMaxBaseDamage), so damage never trips its checks.
 */
public final class PlayerStats {
    public int str, dex, intel, luk, maxHp, maxMp;
    public int watk, matk, wdef, mdef, acc, avoid, hands, speed = 100, jump = 100;
    public int weaponId, weaponCat; // weapon id, (id / 10000) % 100
    public int maxDamage, minDamage;
    public float mastery = 0.1f, critical;
    /** Buff deltas (from GIVE_BUFF), applied on top of equipment. */
    public int buffWatk, buffMatk, buffWdef, buffMdef, buffAcc, buffAvoid, buffSpeed, buffJump;
    /** Best level of the account's other Cygnus Knights (Empress's Blessing). */
    public int knightLevel;

    public void compute(PlayerData d) {
        CharStats s = d.stats;
        str = s.str;
        dex = s.dex;
        intel = s.intel;
        luk = s.luk;
        maxHp = s.maxHp;
        maxMp = s.maxMp;
        watk = matk = wdef = mdef = acc = avoid = hands = 0;
        speed = 100;
        jump = 100;
        weaponId = 0;
        for (Item it : d.inventory(-1).values()) {
            if (it.position <= -100 && it.position != -111) continue; // cash covers have no stats
            str += it.str;
            dex += it.dex;
            intel += it.intel;
            luk += it.luk;
            maxHp += it.hp;
            maxMp += it.mp;
            watk += it.watk;
            matk += it.matk;
            wdef += it.wdef;
            mdef += it.mdef;
            acc += it.acc;
            avoid += it.avoid;
            hands += it.hands;
            speed += it.speed;
            jump += it.jump;
            if (it.position == -11) weaponId = it.itemId;
        }
        java.util.List<Integer> worn = new java.util.ArrayList<>();
        for (Item it : d.inventory(-1).values()) worn.add(it.itemId);
        int[] set = offline.UltimateExplorer.setBonus(worn); // Empress's set effects
        str += set[0];
        dex += set[1];
        intel += set[2];
        luk += set[3];
        maxHp += set[4];
        maxMp += set[5];
        watk += set[6];
        matk += set[7];
        wdef += set[8];
        mdef += set[9];
        acc += set[10];
        avoid += set[11];
        speed += set[12];
        jump += set[13];
        if (d.skills.containsKey(offline.UltimateExplorer.SHOUT)) { // Empress's Shout
            maxHp += maxHp / 5;
            maxMp += maxMp / 5;
        }
        int blessing = offline.UltimateExplorer.blessing(knightLevel); // Empress's Blessing
        watk += blessing;
        matk += blessing;
        acc += blessing;
        avoid += blessing;
        watk += buffWatk;
        matk += buffMatk;
        wdef += buffWdef;
        mdef += buffMdef;
        acc += buffAcc + (int) (dex * 0.8f + luk * 0.5f);
        avoid += buffAvoid + (int) (dex * 0.25f + luk * 0.5f);
        speed = Math.max(100, Math.min(140, speed + buffSpeed));
        jump = Math.max(100, Math.min(123, jump + buffJump));
        matk += intel; // the stat window shows magic incl. INT
        weaponCat = weaponId == 0 ? 0 : (weaponId / 10000) % 100;
        int job = s.job;
        mastery = 0.1f + skillMastery(d, weaponCat);
        critical = 0;
        computeDamage(job, false);
    }

    /** Weapon mastery from passive skills (Skill.wz "mastery" is a percent). */
    private static float skillMastery(PlayerData d, int cat) {
        int best = 0;
        for (java.util.Map.Entry<Integer, int[]> e : d.skills.entrySet()) {
            int id = e.getKey(), lv = e.getValue()[0];
            if (lv <= 0) continue;
            SkillInfo si = SkillInfo.get(id);
            if (si == null || cat == 0 || !si.masteryFor(cat)) continue;
            best = Math.max(best, si.level(lv).getInt("mastery", 0));
        }
        return best / 100f;
    }

    public float multiplier(boolean stab) {
        switch (weaponCat) {
            case 30: return 4.0f;               // 1h sword
            case 31: case 32: return stab ? 3.2f : 4.4f; // 1h axe / blunt
            case 33: return isThief() ? 3.6f : 4.0f;      // dagger
            case 37: case 38: return 3.6f;      // wand / staff
            case 40: return 4.6f;               // 2h sword
            case 41: case 42: return stab ? 3.4f : 4.8f; // 2h axe / blunt
            case 43: return stab ? 5.0f : 3.0f; // spear
            case 44: return stab ? 3.0f : 5.0f; // polearm
            case 45: return 3.4f;               // bow
            case 46: return 3.6f;               // crossbow
            case 47: return 3.6f;               // claw
            case 48: return 4.8f;               // knuckle
            case 49: return 3.6f;               // gun
            default: return 0f;
        }
    }

    private int job;

    private boolean isThief() {
        return job / 100 == 4 || job / 100 == 14;
    }

    /** Min/max weapon damage for the current weapon (stab or swing multiplier). */
    public void computeDamage(int job, boolean stab) {
        this.job = job;
        int primary, secondary;
        if (weaponCat == 45 || weaponCat == 46 || weaponCat == 49) {
            primary = dex;
            secondary = str;
        } else if (weaponCat == 47 || (weaponCat == 33 && isThief())) {
            primary = luk;
            secondary = dex + str;
        } else {
            primary = str;
            secondary = dex;
        }
        float mult = multiplier(stab);
        if (weaponId == 0) {
            maxDamage = minDamage = 1; // bare hands
            return;
        }
        int attack = watk;
        maxDamage = (int) Math.ceil((mult * primary + secondary) / 100.0 * attack);
        minDamage = (int) Math.ceil((mult * primary * 0.9 * mastery + secondary) / 100.0 * attack);
        minDamage = Math.min(minDamage, maxDamage);
    }
}
