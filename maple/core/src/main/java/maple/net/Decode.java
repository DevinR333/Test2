package maple.net;

import maple.net.model.CharEntry;
import maple.net.model.CharLook;
import maple.net.model.CharStats;
import maple.net.model.Item;
import maple.net.model.PlayerData;

/** Reads the v83 structures the server writes (mirrors Cosmic's PacketCreator exactly). */
public final class Decode {
    private Decode() {}

    public static void charStats(PacketReader r, CharStats s) {
        s.id = r.readInt();
        s.name = r.readFixedString(13);
        s.gender = r.readByte();
        s.skin = r.readByte();
        s.face = r.readInt();
        s.hair = r.readInt();
        for (int i = 0; i < 3; i++) s.petIds[i] = r.readLong();
        s.level = r.readUByte();
        s.job = r.readShort();
        s.str = r.readShort();
        s.dex = r.readShort();
        s.intel = r.readShort();
        s.luk = r.readShort();
        s.hp = r.readShort();
        s.maxHp = r.readShort();
        s.mp = r.readShort();
        s.maxMp = r.readShort();
        s.ap = r.readShort();
        if (isEvan(s.job)) {
            int n = r.readUByte();
            for (int i = 0; i < n; i++) { r.readByte(); r.readByte(); }
        } else {
            s.sp = r.readShort();
        }
        s.exp = r.readInt();
        s.fame = r.readShort();
        s.gachaExp = r.readInt();
        s.mapId = r.readInt();
        s.spawnPoint = r.readUByte();
        r.readInt();
    }

    private static boolean isEvan(int job) {
        return job == 2001 || (job >= 2200 && job <= 2218);
    }

    public static void charLook(PacketReader r, CharLook l) {
        l.gender = r.readByte();
        l.skin = r.readByte();
        l.face = r.readInt();
        l.mega = !r.readBool();
        l.hair = r.readInt();
        int slot;
        while ((slot = r.readUByte()) != 0xFF) l.equips.put(slot, r.readInt());
        while ((slot = r.readUByte()) != 0xFF) l.masked.put(slot, r.readInt());
        l.cashWeapon = r.readInt();
        for (int i = 0; i < 3; i++) l.pets[i] = r.readInt();
    }

    public static CharEntry charEntry(PacketReader r) {
        CharEntry e = new CharEntry();
        charStats(r, e.stats);
        charLook(r, e.look);
        r.readByte(); // not "view all"
        e.rankEnabled = r.readBool();
        if (e.rankEnabled) {
            e.rank = r.readInt();
            e.rankMove = r.readInt();
            e.jobRank = r.readInt();
            e.jobRankMove = r.readInt();
        }
        return e;
    }

    public static boolean isRechargeable(int itemId) {
        int t = itemId / 10000;
        return t == 207 || t == 233;
    }

    /** addItemInfo without the leading position. */
    public static Item item(PacketReader r, int position) {
        Item it = new Item();
        it.position = position;
        it.type = r.readByte();
        it.itemId = r.readInt();
        it.cash = r.readBool();
        if (it.cash) it.cashId = r.readLong();
        it.expiration = r.readLong();
        if (it.type == 3) {
            it.petName = r.readFixedString(13);
            it.petLevel = r.readUByte();
            it.petCloseness = r.readShort();
            it.petFullness = r.readUByte();
            r.readLong();
            r.readShort();
            r.readShort();
            r.readInt();
            r.readShort();
            return it;
        }
        if (it.type != 1) {
            it.quantity = r.readShort();
            it.owner = r.readString();
            it.flag = r.readShort();
            if (isRechargeable(it.itemId)) r.skip(8);
            return it;
        }
        it.upgradeSlots = r.readUByte();
        it.level = r.readUByte();
        it.str = r.readShort();
        it.dex = r.readShort();
        it.intel = r.readShort();
        it.luk = r.readShort();
        it.hp = r.readShort();
        it.mp = r.readShort();
        it.watk = r.readShort();
        it.matk = r.readShort();
        it.wdef = r.readShort();
        it.mdef = r.readShort();
        it.acc = r.readShort();
        it.avoid = r.readShort();
        it.hands = r.readShort();
        it.speed = r.readShort();
        it.jump = r.readShort();
        it.owner = r.readString();
        it.flag = r.readShort();
        if (it.cash) {
            r.skip(10);
        } else {
            r.readByte();
            it.itemLevel = r.readUByte();
            it.itemExp = r.readInt();
            it.vicious = r.readInt();
            r.readLong();
        }
        r.readLong();
        r.readInt();
        return it;
    }

    /** SET_FIELD sent on login: [int channel][1][1][short 0][3 ints] + character info + [long time]. */
    public static PlayerData characterInfo(PacketReader r, int channel) {
        PlayerData d = new PlayerData();
        d.channel = channel;
        r.readLong(); // -1
        r.readByte();
        charStats(r, d.stats);
        d.buddyCapacity = r.readUByte();
        if (r.readBool()) d.linkedName = r.readString();
        d.meso = r.readInt();
        // inventory
        for (int i = 1; i <= 5; i++) d.slotLimits[i] = r.readUByte();
        r.readLong();
        int pos;
        while ((pos = r.readShort()) != 0) d.inventory(-1).put(-pos, item(r, -pos));           // equipped
        while ((pos = r.readShort()) != 0) d.inventory(-1).put(-(pos + 100), item(r, -(pos + 100))); // cash equipped
        while ((pos = r.readShort()) != 0) d.inventory(1).put(pos, item(r, pos));
        r.skip(2);
        for (int type = 2; type <= 5; type++) {
            while ((pos = r.readUByte()) != 0) d.inventory(type).put(pos, item(r, pos));
        }
        // skills
        int n = r.readShort();
        for (int i = 0; i < n; i++) {
            int id = r.readInt();
            int level = r.readInt();
            r.readLong();
            int master = 0;
            if (isFourthJob(id)) master = r.readInt();
            d.skills.put(id, new int[]{level, master});
        }
        n = r.readShort();
        for (int i = 0; i < n; i++) d.cooldowns.put(r.readInt(), (int) r.readShort());
        // quests
        n = r.readShort();
        for (int i = 0; i < n; i++) d.startedQuests.put(r.readUShort(), r.readString());
        n = r.readShort();
        for (int i = 0; i < n; i++) d.completedQuests.put(r.readUShort(), r.readLong());
        // minigame
        r.readShort();
        // rings
        n = r.readShort();
        for (int i = 0; i < n; i++) { r.readInt(); r.readFixedString(13); d.ringIds.add(r.readInt()); r.readInt(); r.readInt(); r.readInt(); }
        n = r.readShort();
        for (int i = 0; i < n; i++) { r.readInt(); r.readFixedString(13); d.ringIds.add(r.readInt()); r.readInt(); r.readInt(); r.readInt(); r.readInt(); }
        n = r.readShort();
        for (int i = 0; i < n; i++) { r.readInt(); r.readInt(); r.readInt(); r.readShort(); r.readInt(); r.readInt(); r.readFixedString(13); r.readFixedString(13); }
        // teleport rocks
        for (int i = 0; i < 5; i++) d.teleportMaps[i] = r.readInt();
        for (int i = 0; i < 10; i++) d.vipTeleportMaps[i] = r.readInt();
        // monster book
        d.monsterBookCover = r.readInt();
        r.readByte();
        n = r.readShort();
        for (int i = 0; i < n; i++) d.monsterCards.put(2380000 + r.readUShort() % 10000, r.readUByte());
        // new year cards
        n = r.readShort();
        for (int i = 0; i < n; i++) {
            r.readInt(); r.readInt(); r.readString(); r.readBool(); r.readLong();
            r.readInt(); r.readString(); r.readBool(); r.readBool(); r.readLong(); r.readString();
        }
        // area info
        n = r.readShort();
        for (int i = 0; i < n; i++) d.areaInfo.put((int) r.readShort(), r.readString());
        r.readShort();
        r.readLong();
        return d;
    }

    /** Same rule as the server's Skill.isFourthJob(): those skills carry a master level. */
    static boolean isFourthJob(int skillId) {
        int job = skillId / 10000;
        if (job == 2212) return false;
        if (skillId == 22170001 || skillId == 22171003 || skillId == 22171004 || skillId == 22181002 || skillId == 22181003) return true;
        return job % 10 == 2;
    }
}
