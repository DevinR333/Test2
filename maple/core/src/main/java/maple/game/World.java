package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.chr.Avatar;
import maple.chr.Player;
import maple.gfx.Animation;
import maple.gfx.SpriteBank;
import maple.map.Field;
import maple.net.Decode;
import maple.net.GameClient;
import maple.net.PacketReader;
import maple.net.PacketWriter;
import maple.net.model.Item;
import maple.net.model.PlayerData;
import maple.ui.UiSounds;
import maple.wz.Wz;
import maple.wz.WzNode;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Everything on the current map that the server drives: monsters, NPCs, drops, reactors, damage
 * numbers and effects; plus the player's interactions with them (attacking, being hit, picking up,
 * talking). Packet formats mirror Cosmic's PacketCreator; gameplay follows HeavenClient (AGPL).
 */
public final class World {
    public final Wz wz;
    public final GameClient client;
    public GameEvents events;
    public Field field;
    public final Player player;
    public final PlayerStats stats = new PlayerStats();
    public SpriteBank bank = new SpriteBank();
    public final Map<Integer, Mob> mobs = new LinkedHashMap<>();
    public final Map<Integer, Npc> npcs = new LinkedHashMap<>();
    public final Map<Integer, Drop> drops = new LinkedHashMap<>();
    public final Map<Integer, Reactor> reactors = new LinkedHashMap<>();
    public DamageNumbers numbers;
    private final List<Effect> effects = new ArrayList<>();
    private final List<PendingHit> pendingHits = new ArrayList<>();
    public long timeMs;
    /** Summoned pets (up to 3), redrawn from petSpecs on every map (each map has its own sprite bank). */
    public final Pet[] pets = new Pet[3];
    private final Object[][] petSpecs = new Object[3][]; // {itemId, name, uniqueId}
    private int attackCooldown;
    private short mobMoveId = 1;
    private long nextHeal;
    private static final Random RNG = new Random();
    /** Key bindings from the server (90 keys): type and action. */
    public final int[] keyTypes = new int[90], keyActions = new int[90];
    public Shop shop;
    public NpcTalk talk;
    public int lastNpcId;
    /** Buffs: skill/item id -> expiry time (ms, game clock). */
    public final Map<Integer, Long> buffs = new LinkedHashMap<>();
    public final Map<Integer, Long> cooldownsUntil = new LinkedHashMap<>();
    private boolean enableActions = true;

    /** A one-shot animation in the world (level up, skill use/hit). */
    private static final class Effect {
        final Animation anim;
        final boolean followPlayer;
        final boolean flip;
        double x, y;
        final long start;
        final Mob mob;

        Effect(Animation anim, double x, double y, long start, boolean followPlayer, boolean flip, Mob mob) {
            this.anim = anim;
            this.x = x;
            this.y = y;
            this.start = start;
            this.followPlayer = followPlayer;
            this.flip = flip;
            this.mob = mob;
        }
    }

    /** Damage shown/applied after the attack's delay. */
    private static final class PendingHit {
        final int oid;
        final int damage;
        final boolean critical, fromLeft;
        final long at;
        final double rowOffset;
        final Animation hitEffect;

        PendingHit(int oid, int damage, boolean critical, boolean fromLeft, long at, double rowOffset, Animation hitEffect) {
            this.oid = oid;
            this.damage = damage;
            this.critical = critical;
            this.fromLeft = fromLeft;
            this.at = at;
            this.rowOffset = rowOffset;
            this.hitEffect = hitEffect;
        }
    }

    public World(Wz wz, GameClient client, Player player) {
        this.wz = wz;
        this.client = client;
        this.player = player;
        ItemInfo.init(wz);
        SkillInfo.init(wz);
        numbers = new DamageNumbers(wz, bank);
    }

    public PlayerData data() {
        return client.player;
    }

    /**
     * The server moved us to another map (SET_FIELD). Called as soon as that packet arrives, before
     * the map loads: the spawn packets that follow it belong to the new map.
     */
    public void beginField() {
        field = null;
        mobs.clear();
        npcs.clear();
        drops.clear();
        reactors.clear();
        java.util.Arrays.fill(pets, null); // re-made on the new map
        effects.clear();
        pendingHits.clear();
        bank.dispose();
        bank = new SpriteBank();
        bank.bake();
        numbers = new DamageNumbers(wz, bank);
        talk = null;
        shop = null;
    }

    /** The new map finished loading. */
    public void enterField(Field f) {
        field = f;
    }

    public void recomputeStats() {
        if (data() == null) return;
        stats.compute(data());
        player.speed = stats.speed;
        player.jump = stats.jump;
    }

    // ------------------------------------------------------------------ packets

    /** Handles one in-game packet. Returns true if it was understood. */
    public boolean handle(PacketReader r) {
        int op = r.opcode;
        try {
            if (op == SendOpcode.SPAWN_MONSTER.getValue()) spawnMob(r, false);
            else if (op == SendOpcode.SPAWN_MONSTER_CONTROL.getValue()) spawnMob(r, true);
            else if (op == SendOpcode.KILL_MONSTER.getValue()) killMob(r);
            else if (op == SendOpcode.MOVE_MONSTER.getValue()) moveMob(r);
            else if (op == SendOpcode.MOVE_MONSTER_RESPONSE.getValue()) { /* nothing to do in single player */ }
            else if (op == SendOpcode.SHOW_MONSTER_HP.getValue()) mobHp(r);
            else if (op == SendOpcode.DAMAGE_MONSTER.getValue()) damageMobPacket(r);
            else if (op == SendOpcode.SPAWN_PET.getValue()) petPacket(r);
            else if (op == SendOpcode.SPAWN_NPC.getValue()) spawnNpc(r, false);
            else if (op == SendOpcode.SPAWN_NPC_REQUEST_CONTROLLER.getValue()) spawnNpc(r, true);
            else if (op == SendOpcode.REMOVE_NPC.getValue()) npcs.remove(r.readInt());
            else if (op == SendOpcode.NPC_ACTION.getValue()) { /* ambient NPC animation */ }
            else if (op == SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue()) spawnDrop(r);
            else if (op == SendOpcode.REMOVE_ITEM_FROM_MAP.getValue()) removeDrop(r);
            else if (op == SendOpcode.REACTOR_SPAWN.getValue()) spawnReactor(r);
            else if (op == SendOpcode.REACTOR_HIT.getValue()) hitReactor(r);
            else if (op == SendOpcode.REACTOR_DESTROY.getValue()) destroyReactor(r);
            else if (op == SendOpcode.STAT_CHANGED.getValue()) statChanged(r);
            else if (op == SendOpcode.INVENTORY_OPERATION.getValue()) inventoryOperation(r);
            else if (op == SendOpcode.INVENTORY_GROW.getValue()) {
                int type = r.readUByte();
                if (type < 6) data().slotLimits[type] = r.readUByte();
                events.refresh();
            } else if (op == SendOpcode.SHOW_STATUS_INFO.getValue()) statusInfo(r);
            else if (op == SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue()) itemGainInChat(r);
            else if (op == SendOpcode.UPDATE_SKILLS.getValue()) updateSkills(r);
            else if (op == SendOpcode.COOLDOWN.getValue()) {
                int id = r.readInt();
                int secs = r.readShort();
                if (secs <= 0) cooldownsUntil.remove(id);
                else cooldownsUntil.put(id, timeMs + secs * 1000L);
            } else if (op == SendOpcode.GIVE_BUFF.getValue()) giveBuff(r);
            else if (op == SendOpcode.CANCEL_BUFF.getValue()) { /* expiry handled by the timers */ events.refresh(); }
            else if (op == SendOpcode.CHATTEXT.getValue()) chatText(r);
            else if (op == SendOpcode.SERVERMESSAGE.getValue()) serverMessage(r);
            else if (op == SendOpcode.NPC_TALK.getValue()) npcTalkPacket(r);
            else if (op == SendOpcode.OPEN_NPC_SHOP.getValue()) openShop(r);
            else if (op == SendOpcode.CONFIRM_SHOP_TRANSACTION.getValue()) events.shopResult(r.readUByte());
            else if (op == SendOpcode.STORAGE.getValue()) storagePacket(r);
            else if (op == SendOpcode.KEYMAP.getValue()) keymap(r);
            else if (op == SendOpcode.BUDDYLIST.getValue()) buddyPacket(r);
            else if (op == SendOpcode.QUERY_CASH_RESULT.getValue()) {
                cash.nxCredit = r.readInt();
                cash.maplePoints = r.readInt();
                cash.nxPrepaid = r.readInt();
                events.refresh();
            } else if (op == SendOpcode.CASHSHOP_OPERATION.getValue()) cashPacket(r);
            else if (op == SendOpcode.WHISPER.getValue()) {
                int flag = r.readUByte();
                if (flag == 0x0A) { // result
                    String target = r.readString();
                    if (r.readByte() == 0) events.chat("Unable to find '" + target + "'", 0xFFFF0000);
                } else if (flag == 0x12) { // received
                    String from = r.readString();
                    r.readByte();
                    r.readByte();
                    events.chat(from + "<< " + r.readString(), 0xFF00FF00);
                } else if (flag == 0x09 || flag == 0x48) {
                    events.chat("'" + r.readString() + "' is not online.", 0xFFFF0000);
                }
            } else if (op == SendOpcode.MULTICHAT.getValue()) {
                int type = r.readUByte();
                String from = r.readString(), text = r.readString();
                int color = type == 1 ? 0xFFFF9EC7 : type == 2 ? 0xFFC2FBFB : 0xFFFFB64E;
                events.chat(from + ": " + text, color);
            }
            else if (op == SendOpcode.PARTY_OPERATION.getValue()) partyPacket(r);
            else if (op == SendOpcode.GUILD_OPERATION.getValue()) guildPacket(r);
            else if (op == SendOpcode.MESSENGER.getValue()) messengerPacket(r);
            else if (op == SendOpcode.MONSTER_BOOK_SET_CARD.getValue()) {
                boolean full = r.readByte() == 0;
                int card = r.readInt(), level = r.readInt();
                if (!full) data().monsterCards.put(card, level);
                else events.status("You cannot collect more of this card.", 0xFFFFFFFF);
                events.refresh();
            } else if (op == SendOpcode.MONSTER_BOOK_SET_COVER.getValue()) {
                data().monsterBookCover = r.readInt();
                events.refresh();
            } else if (op == SendOpcode.CANCEL_CHAIR.getValue()) {
                if (r.readByte() == 0) player.standUp();
                else {
                    int id = r.readShort();
                    int[] st = field != null && id >= 0 && id < field.seats.size() ? field.seats.get(id) : null;
                    if (st != null) {
                        player.phys.setPosition(st[0], st[1]);
                        player.sit(0, id, null);
                    }
                }
            } else if (op == SendOpcode.MACRO_SYS_DATA_INIT.getValue()) {
                int n = r.readUByte();
                for (int i = 0; i < 5; i++) macros[i] = null;
                for (int i = 0; i < n && i < 5; i++) {
                    SkillMacro m = new SkillMacro();
                    m.name = r.readString();
                    m.shout = r.readByte() != 0;
                    for (int k = 0; k < 3; k++) m.skills[k] = r.readInt();
                    macros[i] = m;
                }
                events.refresh();
            }
            else if (op == SendOpcode.QUEST_CLEAR.getValue()) {
                int q = r.readUShort();
                showPlayerEffect("Effect/BasicEff.img/QuestClear");
                UiSounds.game("QuestClear");
                events.refresh();
            } else if (op == SendOpcode.UPDATE_QUEST_INFO.getValue()) updateQuestInfo(r);
            else if (op == SendOpcode.SHOW_FOREIGN_EFFECT.getValue()) { /* other players only */ }
            else if (op == SendOpcode.DAMAGE_PLAYER.getValue()) { /* other players only */ }
            else if (op == SendOpcode.FIELD_EFFECT.getValue()) fieldEffect(r);
            else if (op == SendOpcode.BLOW_WEATHER.getValue()) { /* weather items */ }
            else if (op == SendOpcode.PLAYER_HINT.getValue()) {
                events.status(r.readString(), 0xFFFFFFFF);
            } else if (op == SendOpcode.SCRIPT_PROGRESS_MESSAGE.getValue()) {
                events.status(r.readString(), 0xFFFFFF00);
            } else if (op == SendOpcode.SET_WEEK_EVENT_MESSAGE.getValue()) {
                r.readByte();
                events.chat(r.readString(), 0xFFFFFF00);
            } else if (op == SendOpcode.LOCK_UI.getValue() || op == SendOpcode.DISABLE_UI.getValue()) {
                lockedUi = r.readByte() != 0;
            } else if (op == SendOpcode.SPAWN_PLAYER.getValue() || op == SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()
                    || op == SendOpcode.MOVE_PLAYER.getValue() || op == SendOpcode.CLOSE_RANGE_ATTACK.getValue()) {
                // other players: none offline
            } else return false;
        } catch (RuntimeException e) {
            maple.Log.error("packet 0x" + Integer.toHexString(op), e);
        }
        return true;
    }

    /** Scripts can freeze the controls (cutscenes, tutorial). */
    public boolean lockedUi;

    private void spawnMob(PacketReader r, boolean control) {
        int mode = 1;
        if (control) {
            mode = r.readUByte();
            int oid = r.readInt();
            if (mode == 0 || r.available() < 5) {
                Mob m = mobs.get(oid);
                if (m != null) m.controlled = false;
                return;
            }
            spawnMobBody(r, oid, mode);
            return;
        }
        int oid = r.readInt();
        spawnMobBody(r, oid, 0);
    }

    private void spawnMobBody(PacketReader r, int oid, int controlMode) {
        r.readUByte(); // 5 = no controller, 1 = controlled
        int id = r.readInt();
        if (controlMode > 0) skipMobStatus(r);
        else r.skip(16);
        int x = r.readShort(), y = r.readShort();
        int stance = r.readUByte();
        r.readShort(); // origin fh
        int fh = r.readShort();
        int effect = r.readByte();
        boolean newSpawn = false;
        if (effect == -2 || effect == -1) {
            newSpawn = effect == -2;
        } else if (effect == -3) {
            r.readInt(); // parent
        } else if (effect > 0) {
            r.readByte();
            r.readShort();
            if (effect == 15) r.readByte();
            newSpawn = r.readByte() == -2;
        }
        Mob existing = mobs.get(oid);
        if (existing != null && existing.alive()) {
            existing.controlled = controlMode > 0;
            existing.aggro = controlMode == 2;
            return;
        }
        Mob m = new Mob(wz, bank, oid, id, x, y, stance, fh, newSpawn, controlMode > 0, controlMode == 2);
        mobs.put(oid, m);
    }

    /** Mob temporary status block: 4 int masks, then 8 bytes per status, then reflect counters. */
    private static void skipMobStatus(PacketReader r) {
        int[] masks = new int[4];
        for (int i = 0; i < 4; i++) masks[i] = r.readInt();
        int count = Integer.bitCount(masks[0]) + Integer.bitCount(masks[2]);
        r.skip(count * 8);
        boolean weaponReflect = (masks[0] & 0x20000000) != 0, magicReflect = (masks[0] & 0x40000000) != 0;
        if (weaponReflect) r.skip(4);
        if (magicReflect) r.skip(4);
        if (weaponReflect || magicReflect) r.skip(4);
    }

    private void killMob(PacketReader r) {
        int oid = r.readInt();
        int anim = r.readUByte();
        Mob m = mobs.get(oid);
        if (m != null) m.kill(anim);
    }

    private void moveMob(PacketReader r) {
        int oid = r.readInt();
        Mob m = mobs.get(oid);
        if (m == null || m.controlled) return;
        r.skip(1 + 1 + 1 + 1 + 1 + 2);
        int sx = r.readShort(), sy = r.readShort();
        int n = r.readUByte();
        for (int i = 0; i < n; i++) {
            int cmd = r.readUByte();
            if (cmd == 0 || cmd == 5 || cmd == 17) {
                int x = r.readShort(), y = r.readShort();
                r.skip(4);
                int fh = r.readShort();
                int st = r.readUByte();
                r.readShort();
                m.phys.x = x;
                m.phys.y = y;
                m.phys.fhid = fh;
                m.setStanceByte(st);
            } else {
                break;
            }
        }
    }

    private void mobHp(PacketReader r) {
        Mob m = mobs.get(r.readInt());
        int pct = r.readUByte();
        if (m != null) m.showHp(pct, timeMs);
    }

    private void damageMobPacket(PacketReader r) {
        Mob m = mobs.get(r.readInt());
        r.readByte();
        int dmg = r.readInt();
        if (m != null) numbers.add(DamageNumbers.Type.NORMAL, dmg, m.headX(), m.headY());
    }

    private void spawnNpc(PacketReader r, boolean controller) {
        if (controller) r.readByte();
        int oid = r.readInt();
        int id = r.readInt();
        int x = r.readShort(), cy = r.readShort();
        boolean faceRight = r.readBool();
        int fh = r.readShort();
        r.readShort();
        r.readShort();
        if (npcs.containsKey(oid)) return;
        npcs.put(oid, new Npc(wz, bank, oid, id, x, cy, faceRight, fh));
    }

    private void spawnDrop(PacketReader r) {
        int mode = r.readUByte();
        int oid = r.readInt();
        boolean meso = r.readBool();
        int itemId = r.readInt();
        int owner = r.readInt();
        int pickupType = r.readUByte();
        int dx = r.readShort(), dy = r.readShort();
        r.readInt(); // dropper oid
        int sx = dx, sy = dy;
        if (mode != 2) {
            sx = r.readShort();
            sy = r.readShort();
            r.readShort(); // delay
        }
        if (!meso) r.readLong();
        boolean playerDrop = r.available() > 0 && r.readByte() == 0;
        Animation icon;
        if (meso) {
            int k = itemId < 50 ? 0 : itemId < 100 ? 1 : itemId < 1000 ? 2 : 3;
            icon = Animation.of(wz.get("Item/Special/0900.img/0900000" + k + "/iconRaw"), bank);
        } else {
            icon = Animation.of(ItemInfo.get(itemId).iconRaw(), bank);
        }
        Drop d = new Drop(oid, itemId, meso, owner, sx, sy, dx, dy, pickupType, mode, playerDrop, icon);
        drops.put(oid, d);
        if (mode != 2) UiSounds.game("DropItem");
    }

    private void removeDrop(PacketReader r) {
        int anim = r.readUByte();
        int oid = r.readInt();
        Drop d = drops.get(oid);
        if (d == null) return;
        if (anim >= 2) {
            int chr = r.readInt();
            d.expire(2, chr == data().stats.id ? player.phys : null);
            if (chr == data().stats.id) UiSounds.game("PickUpItem");
        } else {
            d.expire(anim, null);
        }
    }

    private void spawnReactor(PacketReader r) {
        int oid = r.readInt();
        int id = r.readInt();
        int state = r.readByte();
        int x = r.readShort(), y = r.readShort();
        reactors.put(oid, new Reactor(wz, bank, oid, id, state, x, y));
    }

    private void hitReactor(PacketReader r) {
        Reactor rc = reactors.get(r.readInt());
        int state = r.readByte();
        if (rc != null) rc.hit(state, timeMs);
    }

    private void destroyReactor(PacketReader r) {
        Reactor rc = reactors.get(r.readInt());
        int state = r.readByte();
        if (rc != null) rc.destroy(state, timeMs);
    }

    private void statChanged(PacketReader r) {
        enableActions = r.readBool();
        int mask = r.readInt();
        PlayerData d = data();
        int oldLevel = d.stats.level, oldJob = d.stats.job;
        boolean look = false;
        // Fields come in ascending mask order (Cosmic sorts them).
        if ((mask & 0x1) != 0) { d.stats.skin = r.readUByte(); look = true; }
        if ((mask & 0x2) != 0) { d.stats.face = r.readInt(); look = true; }
        if ((mask & 0x4) != 0) { d.stats.hair = r.readInt(); look = true; }
        if ((mask & 0x10) != 0) d.stats.level = r.readUByte();
        if ((mask & 0x20) != 0) d.stats.job = r.readShort();
        if ((mask & 0x40) != 0) d.stats.str = r.readShort();
        if ((mask & 0x80) != 0) d.stats.dex = r.readShort();
        if ((mask & 0x100) != 0) d.stats.intel = r.readShort();
        if ((mask & 0x200) != 0) d.stats.luk = r.readShort();
        if ((mask & 0x400) != 0) d.stats.hp = r.readShort();
        if ((mask & 0x800) != 0) d.stats.maxHp = r.readShort();
        if ((mask & 0x1000) != 0) d.stats.mp = r.readShort();
        if ((mask & 0x2000) != 0) d.stats.maxMp = r.readShort();
        if ((mask & 0x4000) != 0) d.stats.ap = r.readShort();
        if ((mask & 0x8000) != 0) {
            if (isEvan(d.stats.job)) {
                int n = r.readUByte();
                for (int i = 0; i < n; i++) { r.readByte(); d.stats.sp = r.readUByte(); }
            } else {
                d.stats.sp = r.readShort();
            }
        }
        if ((mask & 0x10000) != 0) d.stats.exp = r.readInt();
        if ((mask & 0x20000) != 0) d.stats.fame = r.readShort();
        if ((mask & 0x40000) != 0) d.meso = r.readInt();
        if ((mask & 0x180008) != 0 && r.available() >= 4) r.readInt(); // pet
        if ((mask & 0x200000) != 0 && r.available() >= 4) d.stats.gachaExp = r.readInt();
        if (d.stats.level > oldLevel && oldLevel > 0) {
            showPlayerEffect("Effect/BasicEff.img/LevelUp");
            UiSounds.game("LevelUp");
        }
        if (d.stats.job != oldJob && oldJob >= 0 && (mask & 0x20) != 0) {
            showPlayerEffect("Effect/BasicEff.img/JobChanged");
            UiSounds.game("JobChanged");
        }
        if ((mask & 0x400) != 0 && d.stats.hp <= 0 && !player.dead) {
            player.die();
            UiSounds.game("Tombstone");
            events.died();
        } else if (player.dead && d.stats.hp > 0) {
            player.revive();
        }
        if (look && lookChanged != null) lookChanged.run();
        recomputeStats();
        events.refresh();
    }

    /** Called when skin/face/hair/equipment changed (rebuild the avatar). */
    public Runnable lookChanged;

    private static boolean isEvan(int job) {
        return job == 2001 || (job >= 2200 && job <= 2218);
    }

    private void inventoryOperation(PacketReader r) {
        r.readBool();
        int n = r.readUByte();
        PlayerData d = data();
        boolean equipChanged = false;
        for (int i = 0; i < n; i++) {
            int mode = r.readUByte();
            int type = r.readUByte();
            int pos = r.readShort();
            switch (mode) {
                case 0: {
                    Item it = Decode.item(r, pos);
                    inv(type, pos).put(pos, it);
                    if (pos < 0) equipChanged = true;
                    break;
                }
                case 1: {
                    int qty = r.readShort();
                    Item it = inv(type, pos).get(pos);
                    if (it != null) it.quantity = qty;
                    break;
                }
                case 2: {
                    int to = r.readShort();
                    Item it = inv(type, pos).remove(pos);
                    Item other = inv(type, to).remove(to);
                    if (it != null) {
                        it.position = to;
                        inv(type, to).put(to, it);
                    }
                    if (other != null) {
                        other.position = pos;
                        inv(type, pos).put(pos, other);
                    }
                    if (pos < 0 || to < 0) equipChanged = true;
                    break;
                }
                case 3: {
                    inv(type, pos).remove(pos);
                    if (pos < 0) equipChanged = true;
                    break;
                }
                default:
                    break;
            }
        }
        if (equipChanged && lookChanged != null) lookChanged.run();
        recomputeStats();
        events.refresh();
    }

    private java.util.TreeMap<Integer, Item> inv(int type, int pos) {
        return data().inventory(pos < 0 ? -1 : type);
    }

    private void statusInfo(PacketReader r) {
        int mode = r.readUByte();
        switch (mode) {
            case 0: {
                int sub = r.readByte();
                if (sub == 1) { // meso
                    r.readByte();
                    int gain = r.readInt();
                    events.status("You have gained mesos (+" + gain + ")", 0xFFFFFFFF);
                } else if (sub == 0) {
                    int item = r.readInt();
                    int qty = r.readInt();
                    String name = ItemInfo.get(item).name;
                    events.status(qty >= 0 ? "You have gained an item (" + name + (qty > 1 ? " x" + qty : "") + ")"
                            : "You have lost an item (" + name + (qty < -1 ? " x" + (-qty) : "") + ")", 0xFFFFFFFF);
                } else if (sub == -1 || sub == 0xFF) {
                    events.status("You can't get anymore items.", 0xFFFFFFFF);
                } else if (sub == -2 || sub == 0xFE) {
                    events.status("This item is unavailable for the pick-up.", 0xFFFFFFFF);
                }
                break;
            }
            case 1: { // quest record
                int q = r.readUShort();
                int st = r.readUByte();
                PlayerData d = data();
                if (st == 0) {
                    d.startedQuests.remove(q);
                    d.completedQuests.remove(q);
                } else if (st == 1) {
                    d.startedQuests.put(q, r.available() >= 2 ? r.readString() : "");
                } else if (st == 2) {
                    d.startedQuests.remove(q);
                    d.completedQuests.put(q, r.available() >= 8 ? r.readLong() : 0L);
                }
                events.refresh();
                break;
            }
            case 3: { // exp
                boolean white = r.readBool();
                int gain = r.readInt();
                boolean inChat = r.readBool();
                events.status("You have gained experience (+" + gain + ")", white ? 0xFFFFFFFF : 0xFFFFFF00);
                break;
            }
            case 4: {
                int fame = r.readInt();
                events.status("You have gained fame. (+" + fame + ")", 0xFFFFFFFF);
                break;
            }
            case 5: {
                int gain = r.readInt();
                events.chat("You have gained mesos (+" + gain + ")", 0xFFFFFFFF);
                break;
            }
            case 9:
                events.chat(r.readString(), 0xFFFFFF00);
                break;
            case 10: {
                int area = r.readShort();
                data().areaInfo.put(area, r.readString());
                break;
            }
            default:
                break;
        }
    }

    private void itemGainInChat(PacketReader r) {
        int effect = r.readUByte();
        if (effect == 3) {
            r.readByte();
            int item = r.readInt();
            int qty = r.readInt();
            events.chat((qty >= 0 ? "You have gained an item (" : "You have lost an item (") + ItemInfo.get(item).name
                    + (Math.abs(qty) > 1 ? " x" + Math.abs(qty) : "") + ")", 0xFFFFFFFF);
        } else if (effect == 0) {
            showPlayerEffect("Effect/BasicEff.img/LevelUp");
        } else if (effect == 8) {
            showPlayerEffect("Effect/BasicEff.img/JobChanged");
        } else if (effect == 9) {
            showPlayerEffect("Effect/BasicEff.img/QuestClear");
        } else if (effect == 0x12 || effect == 0x17) {
            String path = r.readString();
            showPlayerEffect("Effect/" + path.replaceFirst("^Effect/", ""));
        }
    }

    private void updateSkills(PacketReader r) {
        r.readByte();
        int n = r.readShort();
        for (int i = 0; i < n; i++) {
            int id = r.readInt();
            int level = r.readInt();
            int master = r.readInt();
            r.readLong();
            if (level <= 0) data().skills.remove(id);
            else data().skills.put(id, new int[]{level, master});
        }
        recomputeStats();
        events.refresh();
    }

    private void giveBuff(PacketReader r) {
        long first = r.readLong(), second = r.readLong();
        int count = Long.bitCount(first) + Long.bitCount(second);
        int source = 0, length = 0;
        for (int i = 0; i < count && r.available() >= 10; i++) {
            r.readShort();
            source = r.readInt();
            length = r.readInt();
        }
        if (source != 0) buffs.put(source, timeMs + Math.max(0, length));
        events.refresh();
    }

    private void chatText(PacketReader r) {
        int from = r.readInt();
        boolean gm = r.readBool();
        String text = r.readString();
        String name = from == data().stats.id ? data().stats.name : "?";
        events.chat(name + " : " + text, gm ? 0xFFFFFFFF : 0xFFFFFFFF);
        chatBalloon = text;
        chatBalloonUntil = timeMs + 4000;
    }

    public String chatBalloon;
    public long chatBalloonUntil;

    private void serverMessage(PacketReader r) {
        int type = r.readUByte();
        boolean server = false;
        if (type == 4) server = r.readByte() == 1;
        String msg = r.readString();
        switch (type) {
            case 0: events.chat("[Notice] " + msg, 0xFF8EC5FF); break;
            case 1: events.popup(msg); break;
            case 2: events.chat(msg, 0xFFFFC4E4); break;
            case 3: events.chat(msg, 0xFFFFC4E4); break;
            case 4: events.status(msg, 0xFFFFFF00); break;
            case 5: events.chat(msg, 0xFFFF9A9A); break;
            case 6: events.chat(msg, 0xFF8EC5FF); break;
            default: events.chat(msg, 0xFFFFFFFF); break;
        }
    }

    private void npcTalkPacket(PacketReader r) {
        r.readByte();
        NpcTalk t = new NpcTalk();
        t.npcId = r.readInt();
        t.type = r.readUByte();
        t.speaker = r.readUByte();
        t.text = r.readString();
        switch (t.type) {
            case 0:
                if (r.available() >= 2) {
                    t.prev = r.readByte() != 0;
                    t.next = r.readByte() != 0;
                }
                break;
            case 2:
                t.defText = r.readString();
                r.readInt();
                break;
            case 3:
                t.def = r.readInt();
                t.min = r.readInt();
                t.max = r.readInt();
                break;
            case 7: {
                int n = r.readUByte();
                t.styles = new int[n];
                for (int i = 0; i < n; i++) t.styles[i] = r.readInt();
                break;
            }
            default:
                break;
        }
        talk = t;
        events.npcTalk(t);
    }

    private void openShop(PacketReader r) {
        Shop s = new Shop();
        s.npcId = r.readInt();
        int n = r.readShort();
        for (int i = 0; i < n; i++) {
            Shop.Entry e = new Shop.Entry();
            e.itemId = r.readInt();
            e.price = r.readInt();
            e.pitch = r.readInt();
            r.readInt();
            r.readInt();
            if (ItemInfo.get(e.itemId).isRechargeable()) {
                r.readShort();
                r.readInt();
                e.unitPrice = r.readShort(); // short bits of a double; display uses price
                e.slotMax = r.readShort();
                e.recharge = true;
            } else {
                e.quantity = r.readShort();
                e.buyable = r.readShort();
            }
            s.items.add(e);
        }
        shop = s;
        events.shop(s);
    }

    private void storagePacket(PacketReader r) {
        int mode = r.readUByte();
        Storage st = new Storage();
        if (mode == 0x16) {
            st.npcId = r.readInt();
            st.slots = r.readUByte();
            r.readShort();
            r.readShort();
            r.readInt();
            st.meso = r.readInt();
            r.readShort();
            int n = r.readUByte();
            for (int i = 0; i < n; i++) st.items.add(Decode.item(r, 0));
            storage = st;
            events.storage(st);
        } else if (mode == 0x09 || mode == 0x0D || mode == 0x0F) {
            if (storage == null) return;
            storage.slots = r.readUByte();
            if (mode == 0x0F) {
                r.readByte();
                r.skip(10);
            } else {
                r.readShort();
                r.readShort();
                r.readInt();
            }
            int n = r.readUByte();
            storage.items.clear();
            for (int i = 0; i < n; i++) storage.items.add(Decode.item(r, 0));
            events.storage(storage);
        } else if (mode == 0x13) {
            if (storage == null) return;
            storage.slots = r.readUByte();
            r.readShort();
            r.readShort();
            r.readInt();
            storage.meso = r.readInt();
            events.storage(storage);
        } else {
            events.popup(mode == 0x0A ? "Your inventory is full." : mode == 0x0B ? "You don't have enough mesos." : mode == 0x11 ? "The storage is full." : "That can't be done.");
        }
    }

    public Storage storage;

    private void keymap(PacketReader r) {
        r.readByte();
        for (int i = 0; i < 90; i++) {
            keyTypes[i] = r.readUByte();
            keyActions[i] = r.readInt();
        }
        events.keymap(keyTypes, keyActions);
    }

    private void updateQuestInfo(PacketReader r) {
        int mode = r.readUByte();
        if (mode == 8) {
            r.readUShort();
            r.readInt();
        } else if (mode == 0x0A) {
            events.popup("You don't have enough space in your inventory or don't meet the requirements.");
        } else if (mode == 0x0B) {
            events.popup("You don't have enough mesos.");
        } else if (mode == 0x0D) {
            events.popup("Unable to retrieve it due to the equipment currently being worn by the character.");
        } else if (mode == 0x0E) {
            events.popup("You may not possess more than one of this item.");
        }
        events.refresh();
    }

    private void fieldEffect(PacketReader r) {
        int mode = r.readUByte();
        if (mode == 3) {
            String path = r.readString();
            WzNode n = wz.get("Map/Effect.img/" + path);
            if (n.exists()) screenEffect = new Effect(Animation.of(n, bank), 0, 0, timeMs, false, false, null);
        } else if (mode == 4) {
            UiSounds.playPath(r.readString().replace("/", ".img/").replaceFirst("\\.img/", ".img/"));
        } else if (mode == 6) {
            String bgm = r.readString();
            if (bgmChange != null) bgmChange.accept(bgm);
        }
    }

    /** Map effect shown in the middle of the screen (Map.wz/Effect.img). */
    public Effect screenEffect;
    public java.util.function.Consumer<String> bgmChange;

    // ------------------------------------------------------------------ effects

    public void showPlayerEffect(String path) {
        Animation a = Animation.of(wz.get(path), bank);
        if (!a.isEmpty()) effects.add(new Effect(a, 0, 0, timeMs, true, false, null));
    }

    // ------------------------------------------------------------------ update

    /** One 8 ms step (after the player moved). */
    public void update() {
        timeMs += 8;
        if (field == null) return;
        if (attackCooldown > 0) attackCooldown -= 8;
        for (Iterator<Mob> it = mobs.values().iterator(); it.hasNext(); ) {
            Mob m = it.next();
            if (m.update(field.footholds, timeMs)) reportMobMove(m);
            if (m.dead) it.remove();
        }
        for (Npc n : npcs.values()) n.update(timeMs);
        for (int i = 0; i < 3; i++) {
            if (petSpecs[i] == null) continue;
            if (pets[i] == null) {
                pets[i] = new Pet(wz, bank, i, (Integer) petSpecs[i][0], (String) petSpecs[i][1], (Long) petSpecs[i][2],
                        (int) player.phys.x, (int) player.phys.y - 5);
            }
            pets[i].update(field, player.phys.x, player.phys.y, player.facingRight, timeMs);
        }
        for (Iterator<Drop> it = drops.values().iterator(); it.hasNext(); ) {
            Drop d = it.next();
            d.update(field.footholds);
            if (d.gone) it.remove();
        }
        for (Iterator<Reactor> it = reactors.values().iterator(); it.hasNext(); ) {
            Reactor rc = it.next();
            rc.update(timeMs);
            if (rc.gone) it.remove();
        }
        numbers.update();
        for (Iterator<PendingHit> it = pendingHits.iterator(); it.hasNext(); ) {
            PendingHit h = it.next();
            if (timeMs < h.at) continue;
            it.remove();
            Mob m = mobs.get(h.oid);
            if (m == null) continue;
            numbers.add(h.critical ? DamageNumbers.Type.CRITICAL : DamageNumbers.Type.NORMAL, h.damage, m.headX(), m.headY() - h.rowOffset);
            if (h.damage > 0) {
                m.applyDamage(h.damage, h.fromLeft);
                if (h.hitEffect != null) effects.add(new Effect(h.hitEffect, m.headX(), m.headY() + 10, timeMs, false, !h.fromLeft, m));
            }
        }
        for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
            Effect e = it.next();
            if (timeMs - e.start >= e.anim.durationMs()) it.remove();
        }
        if (screenEffect != null && timeMs - screenEffect.start >= screenEffect.anim.durationMs()) screenEffect = null;
        touchDamage();
        naturalHealing();
        stepMacro();
    }

    private void reportMobMove(Mob m) {
        PacketWriter w = new PacketWriter(RecvOpcode.MOVE_LIFE.getValue());
        w.writeInt(m.oid);
        w.writeShort(mobMoveId++);
        w.writeByte(0); // nibbles
        w.writeByte(-1); // activity: plain movement
        w.writeByte(0).writeByte(0);
        w.writeShort(0);
        w.writeBytes(new byte[8]);
        w.writeByte(0);
        w.writeInt(0);
        w.writeShort((int) Math.round(m.phys.x)).writeShort((int) Math.round(m.phys.y));
        w.writeByte(1);
        w.writeByte(0);
        w.writeShort((int) Math.round(m.phys.x)).writeShort((int) Math.round(m.phys.y));
        w.writeShort((int) Math.round(m.phys.hspeed * 125)).writeShort((int) Math.round(m.phys.vspeed * 125));
        w.writeShort(m.phys.fhid);
        w.writeByte(m.stanceByte());
        w.writeShort(500);
        client.send(w);
    }

    /** Monsters hurt the player by touch (TAKE_DAMAGE from -1). */
    private void touchDamage() {
        if (player.dead || player.invincibleMs > 0 || data() == null) return;
        float px = (float) player.phys.x, py = (float) player.phys.y;
        float l = px - 15, r = px + 15, t = py - 55, b = py;
        for (Mob m : mobs.values()) {
            if (!m.alive() || !m.touchDamage) continue;
            if (!m.overlaps(l, t, r, b)) continue;
            int raw = m.touchAttack();
            int dmg = damageTaken(raw);
            boolean fromLeft = m.phys.x < px;
            PacketWriter w = new PacketWriter(RecvOpcode.TAKE_DAMAGE.getValue());
            w.writeInt((int) timeMs);
            w.writeByte(-1);
            w.writeByte(0);
            w.writeInt(dmg);
            w.writeInt(m.id);
            w.writeInt(m.oid);
            w.writeByte(fromLeft ? 0 : 1);
            client.send(w);
            numbers.add(DamageNumbers.Type.TO_PLAYER, dmg, px, py - 60);
            player.knockback(fromLeft);
            if (dmg > 0) {
                faceHit = timeMs;
            }
            break;
        }
    }

    public long faceHit;

    /** Damage the player takes from a raw monster attack (HeavenClient CharStats::calculate_damage). */
    private int damageTaken(int raw) {
        int def = stats.wdef;
        if (def <= 0) return Math.max(1, raw);
        int d = raw / 2 + raw / def;
        return Math.max(1, d);
    }

    /** The client's 10-second HP/MP recovery while alive. */
    private void naturalHealing() {
        if (player.dead || data() == null) return;
        if (nextHeal == 0) nextHeal = timeMs + 10000;
        if (timeMs < nextHeal) return;
        nextHeal = timeMs + 10000;
        PlayerData d = data();
        int hp = d.stats.hp < stats.maxHp ? 10 : 0;
        int mp = d.stats.mp < stats.maxMp ? 3 : 0;
        if (hp == 0 && mp == 0) return;
        PacketWriter w = new PacketWriter(RecvOpcode.HEAL_OVER_TIME.getValue());
        w.writeBytes(new byte[8]);
        w.writeShort(hp);
        w.writeShort(mp);
        w.writeByte(0);
        client.send(w);
    }

    // ------------------------------------------------------------------ actions

    public boolean canAct() {
        return !player.dead && !lockedUi && data() != null;
    }

    /** Regular attack (Ctrl). Returns true if an attack started. */
    public boolean attack() {
        if (!canAct() || attackCooldown > 0 || player.inAction()) return false;
        if (player.state == Player.State.LADDER || player.state == Player.State.ROPE) return false;
        Avatar av = player.avatar();
        if (av == null) return false;
        boolean prone = player.state == Player.State.PRONE;
        int cat = stats.weaponCat;
        boolean ranged = cat == 45 || cat == 46 || cat == 47 || cat == 49;
        boolean degenerate = ranged && !hasProjectile(cat);
        String stance = av.attackStance(prone, degenerate);
        float speed = 1.7f - Math.max(2, Math.min(9, av.attackSpeed)) / 10f;
        player.startAction(stance, speed);
        attackCooldown = (int) (av.duration(stance) / speed) + 50;
        UiSounds.playPath("Weapon.img/" + (av.weaponSound.isEmpty() ? "swordL" : av.weaponSound) + "/Attack");
        boolean stab = stance.startsWith("stab") || stance.equals("proneStab");
        stats.computeDamage(data().stats.job, stab);
        int min = stats.minDamage, max = stats.maxDamage;
        if (degenerate) {
            min = Math.max(1, min / 10);
            max = Math.max(1, max / 10);
        }
        float[] range = attackRange(av, stance, ranged && !degenerate);
        boolean toLeft = !player.facingRight;
        int levelDelta;
        List<Mob> targets = closest(range, 1);
        List<int[]> lines = new ArrayList<>();
        int hitDelay = attackHitDelay(av, stance, speed);
        for (Mob m : targets) {
            levelDelta = Math.max(0, m.level - data().stats.level);
            int dmg = rollDamage(m, levelDelta, min, max, false);
            lines.add(new int[]{m.oid, dmg});
            pendingHits.add(new PendingHit(m.oid, Math.max(0, dmg), false, toLeft, timeMs + hitDelay, 0, null));
        }
        sendAttack(ranged && !degenerate ? RecvOpcode.RANGED_ATTACK : RecvOpcode.CLOSE_RANGE_ATTACK, 0, 1, lines, toLeft, stance, speed);
        hitReactors(range);
        return true;
    }

    /**
     * Casts a skill bound to a key: attack skills send CLOSE_RANGE / RANGED / MAGIC_ATTACK with their own
     * damage %, target count and hit count; buffs and other actives send SPECIAL_MOVE. Passive skills do nothing.
     */
    public boolean useSkill(int skillId) {
        if (!canAct() || player.inAction() || attackCooldown > 0) return false;
        int[] learned = data().skills.get(skillId);
        int level = learned == null ? 0 : learned[0];
        SkillInfo s = SkillInfo.get(skillId);
        if (s == null || level <= 0 || s.passive()) return false;
        Long cd = cooldownsUntil.get(skillId);
        if (cd != null && cd > timeMs) {
            events.status("You cannot use this skill yet.", 0xFFFFFFFF);
            return false;
        }
        WzNode lv = s.level(level);
        int mpCon = lv.getInt("mpCon", 0), hpCon = lv.getInt("hpCon", 0);
        if (mpCon > data().stats.mp) {
            events.status("Not enough MP.", 0xFFFFFFFF);
            return false;
        }
        if (hpCon > 0 && hpCon >= data().stats.hp) return false;
        Avatar av = player.avatar();
        if (av == null) return false;
        boolean attackSkill = s.attack() || skillId == 2301002;
        int book = skillId / 10000;
        boolean magic = attackSkill && ((book / 100) % 10 == 2 && book % 1000 >= 200 && book % 1000 < 300 || book / 100 == 12 || book / 100 == 22);
        String stance = s.action();
        if (stance.isEmpty() || av.frameCount(stance) == 0) stance = av.attackStance(player.state == Player.State.PRONE, false);
        float speed = 1.7f - Math.max(2, Math.min(9, av.attackSpeed)) / 10f;
        if (player.state == Player.State.LADDER || player.state == Player.State.ROPE) return false;
        player.startAction(stance, speed);
        attackCooldown = (int) (av.duration(stance) / speed) + 50;
        WzNode effect = s.node.get("effect");
        if (effect.exists()) {
            Animation a = Animation.of(effect, bank);
            if (!a.isEmpty()) effects.add(new Effect(a, 0, 0, timeMs, true, player.facingRight, null));
        }
        UiSounds.playPath("Skill.img/" + skillId + "/Use");
        if (!attackSkill) {
            PacketWriter w = new PacketWriter(RecvOpcode.SPECIAL_MOVE.getValue());
            w.writeInt(stamp());
            w.writeInt(skillId);
            w.writeByte(level);
            if (skillId % 10000000 == 1004) w.writeShort(0);
            client.send(w);
            return true;
        }
        int cat = stats.weaponCat;
        boolean rangedWeapon = cat == 45 || cat == 46 || cat == 47 || cat == 49;
        boolean ranged = !magic && rangedWeapon && (lv.get("bulletCount").exists() || s.node.get("ball").exists() || !lv.get("lt").exists() && !s.node.get("lt").exists());
        if (ranged && !hasProjectile(cat)) {
            events.status("You do not have enough arrows/stars/bullets.", 0xFFFFFFFF);
            return false;
        }
        int mobCount = Math.max(1, lv.getInt("mobCount", 1));
        int hits = Math.max(1, lv.getInt("attackCount", 1)) * Math.max(1, lv.getInt("bulletCount", 1));
        float[] range;
        WzNode lt = lv.get("lt").exists() ? lv.get("lt") : s.node.get("lt");
        WzNode rb = lv.get("rb").exists() ? lv.get("rb") : s.node.get("rb");
        if (lt.exists() && rb.exists()) {
            float l = lt.vx(), t = lt.vy(), r = rb.vx(), b = rb.vy();
            if (player.facingRight) {
                float nl = -r, nr = -l;
                l = nl;
                r = nr;
            }
            range = new float[]{(float) player.phys.x + l, (float) player.phys.y + t, (float) player.phys.x + r, (float) player.phys.y + b};
        } else {
            range = attackRange(av, stance, ranged);
        }
        int min, max;
        if (magic) {
            double matk = stats.matk + stats.intel;
            double spell = lv.getInt("mad", lv.getInt("damage", 100));
            double hi = ((matk * matk / 1000 + matk) / 30 + stats.intel / 200.0) * spell / 10.0;
            double lo = ((matk * matk / 1000 + matk * stats.mastery * 0.9) / 30 + stats.intel / 200.0) * spell / 10.0;
            max = (int) Math.max(1, hi);
            min = (int) Math.max(1, Math.min(hi, lo));
        } else {
            boolean stab = stance.startsWith("stab") || stance.equals("proneStab");
            stats.computeDamage(data().stats.job, stab);
            double pct = lv.getInt("damage", 100) / 100.0;
            min = (int) Math.max(1, stats.minDamage * pct);
            max = (int) Math.max(1, stats.maxDamage * pct);
        }
        boolean toLeft = !player.facingRight;
        List<Mob> targets = closest(range, mobCount);
        List<int[]> lines = new ArrayList<>();
        int hitDelay = attackHitDelay(av, stance, speed);
        WzNode hitNode = s.node.get("hit").get("0");
        Animation hitAnim = hitNode.exists() ? Animation.of(hitNode, bank) : null;
        if (hitAnim != null && hitAnim.isEmpty()) hitAnim = null;
        for (Mob m : targets) {
            int levelDelta = Math.max(0, m.level - data().stats.level);
            for (int h = 0; h < hits; h++) {
                int dmg = skillId == 2301002 ? 0 : rollDamage(m, levelDelta, min, max, magic);
                lines.add(new int[]{m.oid, dmg});
                pendingHits.add(new PendingHit(m.oid, Math.max(0, dmg), false, toLeft, timeMs + hitDelay + h * 60L, h * 30, h == 0 ? hitAnim : null));
            }
        }
        RecvOpcode op = magic ? RecvOpcode.MAGIC_ATTACK : ranged ? RecvOpcode.RANGED_ATTACK : RecvOpcode.CLOSE_RANGE_ATTACK;
        sendAttack(op, skillId, hits, lines, toLeft, stance, speed);
        if (!magic) hitReactors(range);
        return true;
    }

    private boolean hasProjectile(int cat) {
        for (Item it : data().inventory(2).values()) {
            int t = it.itemId / 10000;
            if (cat == 45 && it.itemId / 1000 == 2060 && it.quantity > 0) return true;
            if (cat == 46 && it.itemId / 1000 == 2061 && it.quantity > 0) return true;
            if (cat == 47 && t == 207 && it.quantity > 0) return true;
            if (cat == 49 && t == 233 && it.quantity > 0) return true;
        }
        return false;
    }

    /** World rectangle {left, top, right, bottom} for an attack. */
    private float[] attackRange(Avatar av, String stance, boolean ranged) {
        double ox = player.phys.x, oy = player.phys.y;
        float l, t, r, b;
        if (ranged) {
            l = -400; r = -50; t = -60; b = 10; // projectile reach (facing left)
        } else {
            WzNode n = wz.get("Character/Afterimage/" + av.afterImage + ".img/" + Math.max(0, weaponLevel() / 10) + "/" + stance);
            if (!n.get("lt").exists()) n = wz.get("Character/Afterimage/" + av.afterImage + ".img/0/" + stance);
            if (n.get("lt").exists()) {
                l = n.get("lt").vx();
                t = n.get("lt").vy();
                r = n.get("rb").vx();
                b = n.get("rb").vy();
            } else {
                l = -70; t = -50; r = -10; b = 0;
            }
        }
        if (player.facingRight) {
            float nl = -r, nr = -l;
            l = nl;
            r = nr;
        }
        return new float[]{(float) ox + l, (float) oy + t, (float) ox + r, (float) oy + b};
    }

    private int weaponLevel() {
        Item w = data().inventory(-1).get(-11);
        return w == null ? 0 : ItemInfo.get(w.itemId).reqLevel;
    }

    /** Ms from the start of the attack until the hit lands: the afterimage's first frame. */
    private int attackHitDelay(Avatar av, String stance, float speed) {
        int frames = av.frameCount(stance);
        int t = 0;
        for (int i = 0; i < Math.max(1, frames / 2); i++) t += av.delay(stance, i);
        return (int) (t / speed);
    }

    private List<Mob> closest(float[] range, int count) {
        List<Mob> in = new ArrayList<>();
        for (Mob m : mobs.values()) if (m.alive() && m.overlaps(range[0], range[1], range[2], range[3])) in.add(m);
        double ox = player.phys.x, oy = player.phys.y;
        in.sort((a, b) -> Double.compare(Math.hypot(a.phys.x - ox, a.phys.y - oy), Math.hypot(b.phys.x - ox, b.phys.y - oy)));
        return in.size() > count ? in.subList(0, count) : in;
    }

    /** One damage line against a mob, or 0 for a miss (HeavenClient Mob::next_damage). */
    private int rollDamage(Mob m, int levelDelta, int min, int max, boolean magic) {
        float hit = m.hitChance(levelDelta, stats.acc);
        if (RNG.nextFloat() >= hit) return 0;
        double lo = m.minDamage(levelDelta, min, magic), hi = m.maxDamage(levelDelta, max, magic);
        if (hi < lo) hi = lo;
        double dmg = lo + RNG.nextDouble() * (hi - lo);
        return (int) Math.max(1, Math.min(999999, dmg));
    }

    private void sendAttack(RecvOpcode op, int skill, int hits, List<int[]> lines, boolean toLeft, String stance, float speed) {
        PacketWriter w = new PacketWriter(op.getValue());
        w.writeByte(0);
        int mobCount = lines.size() / Math.max(1, hits);
        w.writeByte((mobCount << 4) | hits);
        w.writeInt(skill);
        w.writeBytes(new byte[8]);
        w.writeByte(0); // display
        w.writeByte(toLeft ? 0x80 : 0); // direction
        w.writeByte(stanceCode(stance));
        w.writeByte(0);
        w.writeByte(Math.max(0, Math.min(9, (int) Math.round((1.7f - speed) * 10))));
        if (op == RecvOpcode.RANGED_ATTACK) {
            w.writeByte(0);
            w.writeByte(toLeft ? 1 : 0);
            w.writeBytes(new byte[7]);
        } else {
            w.writeInt(0);
        }
        Map<Integer, List<Integer>> byMob = new LinkedHashMap<>();
        for (int[] l : lines) byMob.computeIfAbsent(l[0], k -> new ArrayList<>()).add(l[1]);
        for (Map.Entry<Integer, List<Integer>> e : byMob.entrySet()) {
            Mob m = mobs.get(e.getKey());
            w.writeInt(e.getKey());
            w.writeInt(0);
            int mx = m == null ? 0 : (int) m.phys.x, my = m == null ? 0 : (int) m.phys.y;
            w.writeShort(mx).writeShort(my);
            w.writeShort(mx).writeShort(my);
            w.writeShort(0);
            for (int d : e.getValue()) w.writeInt(d);
            w.writeInt(0);
        }
        client.send(w);
    }

    /** The client's action table index for the attack stance (sent so other players see it). */
    private static int stanceCode(String stance) {
        String[] order = {"walk1", "walk2", "stand1", "stand2", "alert", "swingO1", "swingO2", "swingO3", "swingOF",
                "swingT1", "swingT2", "swingT3", "swingTF", "swingP1", "swingP2", "swingPF", "stabO1", "stabO2", "stabOF",
                "stabT1", "stabT2", "stabTF", "shoot1", "shoot2", "shootF", "proneStab", "prone", "heal", "fly", "jump",
                "ladder", "rope", "dead", "sit"};
        for (int i = 0; i < order.length; i++) if (order[i].equals(stance)) return i;
        return 5;
    }

    private void hitReactors(float[] range) {
        for (Reactor rc : reactors.values()) {
            if (rc.destroyed || !rc.hittable()) continue;
            float[] b = rc.bounds();
            if (b[0] < range[2] && b[2] > range[0] && b[1] < range[3] && b[3] > range[1]) {
                PacketWriter w = new PacketWriter(RecvOpcode.DAMAGE_REACTOR.getValue());
                w.writeInt(rc.oid);
                w.writeShort((int) player.phys.x).writeShort((int) player.phys.y);
                w.writeShort(player.facingRight ? 0 : 1);
                w.writeInt(0);
                w.writeInt(0);
                client.send(w);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ chairs

    /** Portable chair (Setup items 301xxxx): USE_CHAIR, then sit with the chair's effect animation. */
    public boolean useChair(int itemId) {
        if (!canAct() || player.sitting() || player.inAction() || !player.phys.onGround) return false;
        if (player.state == Player.State.LADDER || player.state == Player.State.ROPE) return false;
        boolean have = false;
        for (Item it : data().inventory(3).values()) if (it.itemId == itemId) have = true;
        if (!have) return false;
        PacketWriter w = new PacketWriter(RecvOpcode.USE_CHAIR.getValue());
        w.writeInt(itemId);
        client.send(w);
        WzNode eff = wz.get("Item/Install/" + String.format("%04d", itemId / 10000) + ".img/" + String.format("%08d", itemId) + "/effect");
        Animation a = eff.exists() ? Animation.of(eff, bank) : null;
        player.sit(itemId, -1, a);
        return true;
    }

    /** Sit key: the nearest map seat in reach (CANCEL_CHAIR with the seat id; the server confirms). */
    public boolean sitOnSeat() {
        if (!canAct() || player.sitting() || field == null || !player.phys.onGround) return false;
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < field.seats.size(); i++) {
            int[] st = field.seats.get(i);
            if (st == null) continue;
            double dx = Math.abs(st[0] - player.phys.x), dy = Math.abs(st[1] - player.phys.y);
            if (dx > 40 || dy > 40) continue;
            if (dx + dy < bestD) {
                bestD = dx + dy;
                best = i;
            }
        }
        if (best < 0) return false;
        PacketWriter w = new PacketWriter(RecvOpcode.CANCEL_CHAIR.getValue());
        w.writeShort(best);
        client.send(w);
        return true;
    }

    /** Getting up (any movement key while sitting). */
    public void standUp() {
        if (!player.sitting()) return;
        player.standUp();
        PacketWriter w = new PacketWriter(RecvOpcode.CANCEL_CHAIR.getValue());
        w.writeShort(-1);
        client.send(w);
    }

    // ------------------------------------------------------------------ skill macros

    /** One of the five skill macros (UIWindow.img/SkillMacro): a name, a shout flag and three skills. */
    public static final class SkillMacro {
        public String name = "";
        public boolean shout;
        public final int[] skills = new int[3];
    }

    public final SkillMacro[] macros = new SkillMacro[5];
    private int macroIndex = -1, macroStep;

    /** Saves all five macros (SKILL_MACRO). */
    public void saveMacros() {
        PacketWriter w = new PacketWriter(RecvOpcode.SKILL_MACRO.getValue());
        int n = 0;
        for (SkillMacro m : macros) if (m != null) n++;
        // the server stores them by position, so empty ones in between are sent blank
        int last = -1;
        for (int i = 0; i < 5; i++) if (macros[i] != null) last = i;
        w.writeByte(last + 1);
        for (int i = 0; i <= last; i++) {
            SkillMacro m = macros[i] != null ? macros[i] : new SkillMacro();
            w.writeString(m.name);
            w.writeByte(m.shout ? 1 : 0);
            for (int k = 0; k < 3; k++) w.writeInt(m.skills[k]);
        }
        client.send(w);
    }

    /** Runs a macro: its skills one after another as each finishes; the name is shouted if set. */
    public void runMacro(int index) {
        if (index < 0 || index >= 5 || macros[index] == null || macroIndex >= 0) return;
        SkillMacro m = macros[index];
        if (m.shout && !m.name.isEmpty()) chat(m.name);
        macroIndex = index;
        macroStep = 0;
    }

    private void stepMacro() {
        if (macroIndex < 0) return;
        if (player.inAction() || attackCooldown > 0) return;
        SkillMacro m = macros[macroIndex];
        while (m != null && macroStep < 3 && m.skills[macroStep] == 0) macroStep++;
        if (m == null || macroStep >= 3) {
            macroIndex = -1;
            return;
        }
        int id = m.skills[macroStep++];
        useSkill(id);
    }

    // ------------------------------------------------------------------ friends, party, guild, messenger

    public final Social social = new Social();
    /** Set when an NPC (Heracle) asks for a new guild's name. */
    public boolean guildNamePrompt;

    private static String fixed(PacketReader r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int c = r.readUByte();
            if (c != 0 && sb.length() == i) sb.append((char) c);
        }
        return sb.toString();
    }

    private void buddyPacket(PacketReader r) {
        int mode = r.readUByte();
        switch (mode) {
            case 7:
            case 0x0A:
            case 0x12: {
                int n = r.readUByte();
                social.buddies.clear();
                for (int i = 0; i < n; i++) {
                    Social.Buddy b = new Social.Buddy();
                    b.id = r.readInt();
                    b.name = fixed(r, 13);
                    r.readByte();
                    b.channel = r.readInt();
                    b.group = fixed(r, 13);
                    r.readInt();
                    social.buddies.add(b);
                }
                break;
            }
            case 0x14: { // a buddy changed channel
                int id = r.readInt();
                r.readByte();
                int ch = r.readInt();
                for (Social.Buddy b : social.buddies) if (b.id == id) b.channel = ch;
                break;
            }
            case 0x15:
                social.buddyCapacity = r.readUByte();
                break;
            case 0x0B: events.popup("Your buddy list is full."); break;
            case 0x0C: events.popup("The other character's buddy list is full."); break;
            case 0x0D: events.popup("That character is already registered as your buddy."); break;
            case 0x0E: events.popup("You cannot add a GM to your buddy list."); break;
            case 0x0F: events.popup("That character is not registered."); break;
            default: break;
        }
        events.refresh();
    }

    /** Chat to a target: 0 all, 1 whisper, 2 party, 3 buddies, 4 guild, 5 alliance. */
    public void chatTo(int target, String whisperTo, String text) {
        switch (target) {
            case 1: {
                if (whisperTo == null || whisperTo.isEmpty()) return;
                PacketWriter w = new PacketWriter(RecvOpcode.WHISPER.getValue());
                w.writeByte(6);
                w.writeString(whisperTo);
                w.writeString(text);
                client.send(w);
                events.chat(whisperTo + ">> " + text, 0xFF00FF00);
                return;
            }
            case 2: case 3: case 4: case 5: {
                int type = target == 2 ? 1 : target == 3 ? 0 : target == 4 ? 2 : 3;
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                if (type == 1) for (Social.Member m : social.party) if (m.id != data().stats.id) ids.add(m.id);
                if (type == 0) for (Social.Buddy b : social.buddies) ids.add(b.id);
                if (type == 2) for (Social.Member m : social.guild) if (m.id != data().stats.id) ids.add(m.id);
                if (type == 1 && social.partyId == 0) { events.chat("You are not in a party.", 0xFFFF0000); return; }
                if (type == 2 && social.guildId == 0) { events.chat("You are not in a guild.", 0xFFFF0000); return; }
                PacketWriter w = new PacketWriter(RecvOpcode.MULTI_CHAT.getValue());
                w.writeByte(type);
                w.writeByte(ids.size());
                for (int id : ids) w.writeInt(id);
                w.writeString(text);
                client.send(w);
                int color = type == 1 ? 0xFFFF9EC7 : type == 2 ? 0xFFC2FBFB : 0xFFFFB64E;
                events.chat(data().stats.name + ": " + text, color);
                return;
            }
            default:
                chat(text);
        }
    }

    public void buddyAdd(String name, String group) {
        PacketWriter w = new PacketWriter(RecvOpcode.BUDDYLIST_MODIFY.getValue());
        w.writeByte(1);
        w.writeString(name);
        w.writeString(group == null || group.isEmpty() ? "Default Group" : group);
        client.send(w);
    }

    public void buddyDelete(int id) {
        PacketWriter w = new PacketWriter(RecvOpcode.BUDDYLIST_MODIFY.getValue());
        w.writeByte(3);
        w.writeInt(id);
        client.send(w);
    }

    private void readPartyStatus(PacketReader r) {
        int[] ids = new int[6];
        for (int i = 0; i < 6; i++) ids[i] = r.readInt();
        String[] names = new String[6];
        for (int i = 0; i < 6; i++) names[i] = fixed(r, 13);
        int[] jobs = new int[6], levels = new int[6], channels = new int[6], maps = new int[6];
        for (int i = 0; i < 6; i++) jobs[i] = r.readInt();
        for (int i = 0; i < 6; i++) levels[i] = r.readInt();
        for (int i = 0; i < 6; i++) channels[i] = r.readInt();
        social.partyLeader = r.readInt();
        for (int i = 0; i < 6; i++) maps[i] = r.readInt();
        for (int i = 0; i < 6; i++) r.skip(16); // mystic doors
        social.party.clear();
        for (int i = 0; i < 6; i++) {
            if (ids[i] == 0) continue;
            Social.Member m = new Social.Member();
            m.id = ids[i];
            m.name = names[i];
            m.job = jobs[i];
            m.level = levels[i];
            m.channel = channels[i];
            m.online = channels[i] >= 0;
            m.mapId = maps[i];
            social.party.add(m);
        }
    }

    private void selfInParty() {
        social.party.clear();
        Social.Member m = new Social.Member();
        m.id = data().stats.id;
        m.name = data().stats.name;
        m.job = data().stats.job;
        m.level = data().stats.level;
        m.channel = 0;
        m.online = true;
        m.mapId = field == null ? 0 : field.id;
        social.party.add(m);
        social.partyLeader = m.id;
    }

    private void partyPacket(PacketReader r) {
        int mode = r.readUByte();
        switch (mode) {
            case 8:
                social.partyId = r.readInt();
                selfInParty();
                events.status("You have created a new party.", 0xFFFFFFFF);
                break;
            case 7:
                social.partyId = r.readInt();
                readPartyStatus(r);
                break;
            case 0x0F:
                social.partyId = r.readInt();
                events.status(r.readString() + " has joined the party.", 0xFFFFFFFF);
                readPartyStatus(r);
                break;
            case 0x0C: {
                r.readInt();
                int target = r.readInt();
                boolean disband = r.readByte() == 0;
                if (disband || target == data().stats.id) {
                    social.partyId = 0;
                    social.party.clear();
                    events.status(disband ? "The party has been disbanded." : "You have left the party.", 0xFFFFFFFF);
                } else {
                    boolean expel = r.readByte() == 1;
                    String name = r.readString();
                    events.status(name + (expel ? " has been expelled from the party." : " has left the party."), 0xFFFFFFFF);
                    readPartyStatus(r);
                }
                break;
            }
            case 0x1B:
                social.partyLeader = r.readInt();
                break;
            case 4: { // invitation
                int pid = r.readInt();
                String from = r.readString();
                events.partyInvite(pid, from);
                break;
            }
            case 10: events.popup("A beginner can't create a party."); break;
            case 13: events.popup("You have yet to join a party."); break;
            case 16: events.popup("You have already joined a party."); break;
            case 17: events.popup("The party you're trying to join is already in full capacity."); break;
            case 19: events.popup("Unable to find the requested character in this channel."); break;
            case 21: case 22: case 23: {
                String n = r.available() > 1 ? r.readString() : "";
                events.popup(mode == 21 ? n + " is blocking party invitations." : mode == 22 ? n + " is taking care of another invitation." : n + " has denied the request to the party.");
                break;
            }
            default:
                if (mode == 1 || mode == 5 || mode == 6 || mode == 11 || mode == 14) events.popup("Your request for a party didn't work due to an unexpected error.");
                break;
        }
        events.refresh();
    }

    public void partyCreate() { partyOp(1, null, 0); }
    public void partyLeave() { partyOp(2, null, 0); }
    public void partyInvite(String name) { partyOp(4, name, 0); }
    public void partyExpel(int id) { partyOp(5, null, id); }
    public void partyLeader(int id) { partyOp(6, null, id); }

    /** Accepts (or declines) a party invitation (DENY_PARTY_REQUEST / join). */
    public void partyJoin(int partyId) { partyOp(3, null, partyId); }

    private void partyOp(int op, String name, int arg) {
        PacketWriter w = new PacketWriter(RecvOpcode.PARTY_OPERATION.getValue());
        w.writeByte(op);
        if (name != null) w.writeString(name);
        else if (op != 1 && op != 2) w.writeInt(arg);
        client.send(w);
    }

    private void guildPacket(PacketReader r) {
        int mode = r.readUByte();
        switch (mode) {
            case 0x01:
                guildNamePrompt = true;
                events.guildNamePrompt();
                break;
            case 0x1A: {
                if (r.readByte() == 0) {
                    social.guildId = 0;
                    social.guild.clear();
                    break;
                }
                social.guildId = r.readInt();
                social.guildName = r.readString();
                for (int i = 0; i < 5; i++) social.rankTitles[i] = r.readString();
                int n = r.readUByte();
                int[] ids = new int[n];
                for (int i = 0; i < n; i++) ids[i] = r.readInt();
                social.guild.clear();
                for (int i = 0; i < n; i++) {
                    Social.Member m = new Social.Member();
                    m.id = ids[i];
                    m.name = fixed(r, 13);
                    m.job = r.readInt();
                    m.level = r.readInt();
                    m.rank = r.readInt();
                    m.online = r.readInt() != 0;
                    r.readInt();
                    r.readInt();
                    social.guild.add(m);
                }
                social.guildCapacity = r.readInt();
                r.readShort();
                r.readByte();
                r.readShort();
                r.readByte();
                social.guildNotice = r.readString();
                social.guildPoints = r.readInt();
                break;
            }
            default:
                break;
        }
        events.refresh();
    }

    public void guildCreate(String name) {
        guildNamePrompt = false;
        PacketWriter w = new PacketWriter(RecvOpcode.GUILD_OPERATION.getValue());
        w.writeByte(0x02);
        w.writeString(name);
        client.send(w);
    }

    public void guildLeave() {
        PacketWriter w = new PacketWriter(RecvOpcode.GUILD_OPERATION.getValue());
        w.writeByte(0x07);
        w.writeInt(data().stats.id);
        w.writeString(data().stats.name);
        client.send(w);
    }

    public void guildNotice(String text) {
        PacketWriter w = new PacketWriter(RecvOpcode.GUILD_OPERATION.getValue());
        w.writeByte(0x10);
        w.writeString(text);
        client.send(w);
    }

    private void messengerPacket(PacketReader r) {
        int mode = r.readUByte();
        switch (mode) {
            case 0x00:
            case 0x07: {
                int pos = r.readUByte();
                maple.net.model.CharLook look = new maple.net.model.CharLook();
                Decode.charLook(r, look);
                String name = r.readString();
                if (pos < 3) {
                    social.seatLooks[pos] = look;
                    social.seatNames[pos] = name;
                }
                if (mode == 0 && pos != social.messengerSeat) social.messengerLog.add(name + " has joined.");
                break;
            }
            case 0x01:
                social.messengerSeat = r.readUByte();
                social.messengerOpen = true;
                break;
            case 0x02: {
                int pos = r.readUByte();
                if (pos < 3) {
                    if (social.seatNames[pos] != null) social.messengerLog.add(social.seatNames[pos] + " has left.");
                    social.seatNames[pos] = null;
                    social.seatLooks[pos] = null;
                }
                break;
            }
            case 0x06:
                social.messengerLog.add(r.readString());
                break;
            default:
                if (r.available() > 2) social.messengerLog.add(r.readString());
                break;
        }
        events.refresh();
    }

    public void messengerOpen() {
        PacketWriter w = new PacketWriter(RecvOpcode.MESSENGER.getValue());
        w.writeByte(0x00);
        w.writeInt(0);
        client.send(w);
        // a new messenger gets no answer: the client seats itself in the first place
        social.messengerOpen = true;
        social.messengerSeat = 0;
        social.seatNames[0] = data().stats.name;
        maple.net.model.CharLook look = new maple.net.model.CharLook();
        look.gender = data().stats.gender;
        look.skin = data().stats.skin;
        look.face = data().stats.face;
        look.hair = data().stats.hair;
        for (Item it : data().inventory(-1).values()) {
            int slot = -it.position;
            if (slot > 100) look.equips.put(slot - 100, it.itemId);
            else if (!look.equips.containsKey(slot)) look.equips.put(slot, it.itemId);
        }
        social.seatLooks[0] = look;
    }

    public void messengerLeave() {
        if (!social.messengerOpen) return;
        PacketWriter w = new PacketWriter(RecvOpcode.MESSENGER.getValue());
        w.writeByte(0x02);
        client.send(w);
        social.messengerOpen = false;
        social.messengerSeat = -1;
        java.util.Arrays.fill(social.seatNames, null);
        java.util.Arrays.fill(social.seatLooks, null);
    }

    public void messengerInvite(String name) {
        PacketWriter w = new PacketWriter(RecvOpcode.MESSENGER.getValue());
        w.writeByte(0x03);
        w.writeString(name);
        client.send(w);
    }

    public void messengerSay(String text) {
        PacketWriter w = new PacketWriter(RecvOpcode.MESSENGER.getValue());
        w.writeByte(0x06);
        w.writeString(data().stats.name + " : " + text);
        client.send(w);
        social.messengerLog.add(data().stats.name + " : " + text);
    }

    // ------------------------------------------------------------------ cash shop

    public final CashShopState cash = new CashShopState();

    private CashShopState.Entry cashEntry(PacketReader r, boolean gift) {
        CashShopState.Entry e = new CashShopState.Entry();
        e.cashId = r.readLong();
        if (!gift) {
            r.readInt();
            r.readInt();
        }
        e.itemId = r.readInt();
        if (!gift) {
            e.sn = r.readInt();
            e.quantity = r.readShort();
        } else e.quantity = 1;
        e.giftFrom = fixed(r, 13);
        if (gift) {
            fixed(r, 73);
            return e;
        }
        e.expiration = r.readLong();
        r.readLong();
        return e;
    }

    private void cashPacket(PacketReader r) {
        int mode = r.readUByte();
        switch (mode) {
            case 0x4B: {
                cash.locker.clear();
                int n = r.readShort();
                for (int i = 0; i < n; i++) cash.locker.add(cashEntry(r, false));
                break;
            }
            case 0x4D: {
                cash.gifts.clear();
                int n = r.readShort();
                for (int i = 0; i < n; i++) cash.gifts.add(cashEntry(r, true));
                break;
            }
            case 0x4F:
            case 0x55:
                for (int i = 0; i < 10; i++) cash.wishlist[i] = r.readInt();
                break;
            case 0x57:
                cash.locker.add(cashEntry(r, false));
                cash.message = "The item has been moved to your Cash Inventory.";
                break;
            case 0x89: {
                int n = r.readUByte();
                for (int i = 0; i < n; i++) cash.locker.add(cashEntry(r, false));
                cash.message = "The package has been moved to your Cash Inventory.";
                break;
            }
            case 0x68: { // taken out into the inventory
                int pos = r.readShort();
                Item it = Decode.item(r, pos);
                int type = ItemInfo.inventoryType(it.itemId);
                data().inventory(type).put(pos, it);
                cash.locker.removeIf(e -> e.cashId == it.cashId);
                break;
            }
            case 0x6A: { // put back into the locker
                CashShopState.Entry e = cashEntry(r, false);
                cash.locker.add(e);
                for (int t = 1; t <= 5; t++) data().inventory(t).values().removeIf(it -> it.cashId == e.cashId);
                break;
            }
            case 0x60: {
                int type = r.readUByte();
                int slots = r.readShort();
                if (type >= 1 && type <= 5) data().slotLimits[type] = slots;
                cash.message = "Your inventory has been expanded.";
                break;
            }
            case 0x5C: {
                int code = r.readUByte();
                cash.message = cashError(code);
                break;
            }
            default:
                break;
        }
        events.refresh();
    }

    private static String cashError(int code) {
        switch (code) {
            case 0xA3: return "Request timed out. Please try again.";
            case 0xA5: return "You don't have enough cash.";
            case 0xA8: return "You cannot send a gift to your own account.";
            case 0xAA: return "Gender restriction.";
            case 0xAC: return "You have exceeded the number of cash items you can have.";
            case 0xBB: return "Your inventory is full.";
            case 0xBF: return "This item is not available for purchase at this time.";
            case 0xC0: return "This item is out of stock.";
            default: return "Due to an unknown error, the request failed.";
        }
    }

    /** Buys a commodity (an item, or a package for 9xxxxxx ids) with NX Credit (1), Maple Points (2) or NX Prepaid (4). */
    public void cashBuy(int sn, int itemId, int currency) {
        PacketWriter w = new PacketWriter(RecvOpcode.CASHSHOP_OPERATION.getValue());
        w.writeByte(itemId / 1000000 == 9 ? 0x1E : 0x03);
        w.writeByte(0);
        w.writeInt(currency);
        w.writeInt(sn);
        client.send(w);
    }

    public void cashTakeOut(long cashId) {
        PacketWriter w = new PacketWriter(RecvOpcode.CASHSHOP_OPERATION.getValue());
        w.writeByte(0x0D);
        w.writeInt((int) cashId);
        client.send(w);
    }

    public void cashPutBack(Item it) {
        PacketWriter w = new PacketWriter(RecvOpcode.CASHSHOP_OPERATION.getValue());
        w.writeByte(0x0E);
        w.writeInt((int) it.cashId);
        w.writeInt(0);
        w.writeByte(ItemInfo.inventoryType(it.itemId));
        client.send(w);
    }

    /** Adds 4 slots to an inventory (type 1..4) for 4,000 NX. */
    public void cashExpand(int type, int currency) {
        PacketWriter w = new PacketWriter(RecvOpcode.CASHSHOP_OPERATION.getValue());
        w.writeByte(0x06);
        w.writeByte(0);
        w.writeInt(currency);
        w.writeByte(0);
        w.writeByte(type);
        client.send(w);
    }

    /** Saves the wish list (up to 10 SNs). */
    public void cashWishlist(int[] sns) {
        PacketWriter w = new PacketWriter(RecvOpcode.CASHSHOP_OPERATION.getValue());
        w.writeByte(0x05);
        for (int i = 0; i < 10; i++) w.writeInt(i < sns.length ? sns[i] : 0);
        client.send(w);
    }

    public void cashCheck() {
        client.send(new PacketWriter(RecvOpcode.CHECK_CASH.getValue()));
    }

    /** Registers a card as the Monster Book cover (0 releases it). */
    public void setBookCover(int cardId) {
        PacketWriter w = new PacketWriter(RecvOpcode.MONSTER_BOOK_COVER.getValue());
        w.writeInt(cardId);
        client.send(w);
    }

    /** Pick up the nearest drop in reach (Z). */
    public boolean pickup() {
        if (!canAct()) return false;
        for (Drop d : drops.values()) {
            if (!d.inReach(player.phys.x, player.phys.y)) continue;
            if (timeMs - d.pickupRequested < 500) continue;
            d.pickupRequested = timeMs;
            PacketWriter w = new PacketWriter(RecvOpcode.ITEM_PICKUP.getValue());
            w.writeInt((int) timeMs);
            w.writeByte(0);
            w.writeShort((int) player.phys.x).writeShort((int) player.phys.y);
            w.writeInt(d.oid);
            client.send(w);
            return true;
        }
        return false;
    }

    /** The NPC at world coords, or null. */
    public Npc npcAt(float wx, float wy) {
        for (Npc n : npcs.values()) if (n.contains(wx, wy)) return n;
        return null;
    }

    /**
     * Clicking an NPC. One with quests for the character first offers them (like the original client):
     * quests to complete, in progress, and to start, then its own conversation.
     */
    public final QuestBook quests = new QuestBook(this);

    public void talkTo(Npc n) {
        if (!canAct() || n == null) return;
        List<Integer> start = quests.startable(n.id), finish = quests.finishing(n.id);
        if (start.isEmpty() && finish.isEmpty()) {
            serverTalk(n);
            return;
        }
        StringBuilder sb = new StringBuilder();
        List<Runnable> choices = new ArrayList<>();
        List<Integer> ready = new ArrayList<>(), busy = new ArrayList<>();
        for (int q : finish) (quests.ready(q) ? ready : busy).add(q);
        if (!ready.isEmpty()) {
            sb.append("#r#eQuests you can complete#n#k");
            for (int q : ready) {
                sb.append("\r\n#L").append(choices.size()).append("##b").append(quests.name(q)).append("#k#l");
                choices.add(() -> finishQuest(n, q));
            }
            sb.append("\r\n");
        }
        if (!busy.isEmpty()) {
            sb.append("#e#dQuests in progress#n#k");
            for (int q : busy) {
                sb.append("\r\n#L").append(choices.size()).append("##b").append(quests.name(q)).append("#k#l");
                choices.add(() -> finishQuest(n, q));
            }
            sb.append("\r\n");
        }
        if (!start.isEmpty()) {
            sb.append("#e#gQuests available#n#k");
            for (int q : start) {
                sb.append("\r\n#L").append(choices.size()).append("##b").append(quests.name(q)).append("#k#l");
                choices.add(() -> startQuest(n, q));
            }
            sb.append("\r\n");
        }
        sb.append("\r\n#L").append(choices.size()).append("#Talk to ").append(n.name).append("#l");
        choices.add(() -> serverTalk(n));
        NpcTalk menu = localTalk(n.id, 4, sb.toString(), false, false);
        menu.local = (action, selection, text) -> {
            if (action == 1 && selection >= 0 && selection < choices.size()) choices.get(selection).run();
        };
        showLocal(menu);
    }

    private NpcTalk localTalk(int npcId, int type, String text, boolean prev, boolean next) {
        NpcTalk t = new NpcTalk();
        t.type = type;
        t.npcId = npcId;
        t.speaker = 0;
        t.text = text;
        t.prev = prev;
        t.next = next;
        return t;
    }

    private void showLocal(NpcTalk t) {
        talk = t;
        events.npcTalk(t);
    }

    /** Quest.wz pages one after another (Prev/Next); the last one asks (accept/decline) or just ends. */
    private void questPages(int npcId, List<String> pages, int i, boolean ask, Runnable done, Runnable declined) {
        if (pages.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        boolean last = i == pages.size() - 1;
        NpcTalk t = localTalk(npcId, last && ask ? 0x0C : 0, pages.get(i), i > 0, !last);
        t.local = (action, selection, text) -> {
            if (last && ask) {
                if (action == 1) {
                    if (done != null) done.run();
                } else if (action == 0 && declined != null) {
                    declined.run();
                }
                return;
            }
            if (action == 1) {
                if (last) {
                    if (done != null) done.run();
                } else {
                    questPages(npcId, pages, i + 1, ask, done, declined);
                }
            } else if (action == 0 && i > 0) {
                questPages(npcId, pages, i - 1, ask, done, declined);
            }
        };
        showLocal(t);
    }

    private void startQuest(Npc n, int q) {
        if (quests.scriptedStart(q)) {
            questAction(4, q, n.id, 0); // the server runs the quest's start script
            return;
        }
        boolean ask = quests.asks(q);
        questPages(n.id, quests.say(q, 0, ""), 0, ask, () -> {
            questAction(1, q, n.id, 0);
            List<String> yes = quests.say(q, 0, "yes");
            if (!yes.isEmpty()) questPages(n.id, yes, 0, false, null, null);
        }, () -> {
            List<String> no = quests.say(q, 0, "no");
            if (!no.isEmpty()) questPages(n.id, no, 0, false, null, null);
        });
    }

    private void finishQuest(Npc n, int q) {
        if (!quests.ready(q)) {
            List<String> stop = quests.say(q, 1, "stop");
            if (stop.isEmpty()) stop = java.util.Collections.singletonList("You haven't finished #b" + quests.name(q) + "#k yet.");
            questPages(n.id, stop, 0, false, null, null);
            return;
        }
        if (quests.scriptedEnd(q)) {
            questAction(5, q, n.id, 0); // the server runs the quest's end script
            return;
        }
        List<String> pages = quests.say(q, 1, "");
        Runnable complete = () -> questAction(2, q, n.id, -1);
        if (pages.isEmpty()) complete.run();
        else questPages(n.id, pages, 0, false, complete, null);
    }

    /** The NPC's own conversation (its server script, shop, storage...). */
    private void serverTalk(Npc n) {
        lastNpcId = n.id;
        PacketWriter w = new PacketWriter(RecvOpcode.NPC_TALK.getValue());
        w.writeInt(n.oid);
        w.writeShort((int) player.phys.x).writeShort((int) player.phys.y);
        client.send(w);
    }

    /** Answer the current NPC page. action: 0 end/no/back, 1 next/yes/ok; selection for menus/numbers. */
    public void answer(int action, int selection, String text) {
        NpcTalk t = talk;
        if (t == null) return;
        if (t.local != null) {
            talk = null;
            t.local.answer(action, selection, text);
            return;
        }
        PacketWriter w = new PacketWriter(RecvOpcode.NPC_TALK_MORE.getValue());
        w.writeByte(t.type);
        w.writeByte(action);
        if (t.type == 2) {
            if (action != 0) w.writeString(text == null ? "" : text);
        } else if (t.type == 3 || t.type == 4 || t.type == 5 || t.type == 7 || t.type == 8) {
            if (action != 0) w.writeInt(selection);
        }
        client.send(w);
        talk = null;
    }

    // ------------------------------------------------------------------ requests (client -> server)

    private int stamp() {
        return (int) (System.currentTimeMillis() & 0x7FFFFFFF);
    }

    /** ITEM_MOVE: move/swap within an inventory, equip (dst < 0), unequip (src < 0) or drop (dst 0). */
    public void moveItem(int type, int src, int dst, int qty) {
        PacketWriter w = new PacketWriter(RecvOpcode.ITEM_MOVE.getValue());
        w.writeInt(stamp());
        w.writeByte(type);
        w.writeShort(src);
        w.writeShort(dst);
        w.writeShort(qty);
        client.send(w);
        if (dst == 0) UiSounds.game("DropItem");
    }

    /** Equip the item in equip-inventory slot src. */
    public void equip(int src) {
        Item it = data().inventory(1).get(src);
        if (it == null) return;
        int slot = ItemInfo.equipSlot(it.itemId);
        if (slot == 0) return;
        if (it.cash && slot > -100) slot -= 100;
        moveItem(1, src, slot, 1);
    }

    /** Unequip to the first free slot of the equip inventory. */
    public void unequip(int src) {
        int free = freeSlot(1);
        if (free <= 0) {
            events.popup("Your equipment inventory is full.");
            return;
        }
        moveItem(1, src, free, 1);
    }

    public int freeSlot(int type) {
        int limit = data().slotLimits[type] > 0 ? data().slotLimits[type] : 24;
        java.util.TreeMap<Integer, Item> inv = data().inventory(type);
        for (int i = 1; i <= limit; i++) if (!inv.containsKey(i)) return i;
        return -1;
    }

    /** Double-click / use: potions, scrolls of return, etc. (USE_ITEM). */
    public void useItem(int slot) {
        Item it = data().inventory(2).get(slot);
        if (it == null || !canAct()) return;
        PacketWriter w = new PacketWriter(RecvOpcode.USE_ITEM.getValue());
        w.writeInt(stamp());
        w.writeShort(slot);
        w.writeInt(it.itemId);
        client.send(w);
        UiSounds.game("UseShopItem");
    }

    /** Uses the first item with this id (key bindings, quick slots). */
    public void useItemId(int itemId) {
        if (itemId / 10000 == 301) {
            if (player.chairItem == itemId) standUp();
            else useChair(itemId);
            return;
        }
        for (Item it : data().inventory(ItemInfo.inventoryType(itemId)).values()) {
            if (it.itemId == itemId) {
                if (ItemInfo.inventoryType(itemId) == 2) useItem(it.position);
                return;
            }
        }
    }

    /** AP: stat is the server's Stat mask value (STR 0x40, DEX 0x80, INT 0x100, LUK 0x200, MaxHP 0x800, MaxMP 0x2000). */
    public void distributeAp(int stat) {
        PacketWriter w = new PacketWriter(RecvOpcode.DISTRIBUTE_AP.getValue());
        w.writeInt(stamp());
        w.writeInt(stat);
        client.send(w);
    }

    public void distributeSp(int skillId) {
        PacketWriter w = new PacketWriter(RecvOpcode.DISTRIBUTE_SP.getValue());
        w.writeInt(stamp());
        w.writeInt(skillId);
        client.send(w);
    }

    public void dropMeso(int amount) {
        PacketWriter w = new PacketWriter(RecvOpcode.MESO_DROP.getValue());
        w.writeInt(stamp());
        w.writeInt(amount);
        client.send(w);
    }

    /** Gather (merge stacks and fill gaps) or Sort an inventory tab. */
    public void sortInventory(int type, boolean sort) {
        PacketWriter w = new PacketWriter((sort ? RecvOpcode.ITEM_SORT2 : RecvOpcode.ITEM_SORT).getValue());
        w.writeInt(stamp());
        w.writeByte(type);
        client.send(w);
    }

    public void chat(String text) {
        PacketWriter w = new PacketWriter(RecvOpcode.GENERAL_CHAT.getValue());
        w.writeString(text);
        w.writeByte(0);
        client.send(w);
    }

    /** Saves key bindings (CHANGE_KEYMAP mode 0). */
    public void changeKeys(int[] slots) {
        PacketWriter w = new PacketWriter(RecvOpcode.CHANGE_KEYMAP.getValue());
        w.writeInt(0);
        w.writeInt(slots.length);
        for (int k : slots) {
            w.writeInt(k);
            w.writeByte(keyTypes[k]);
            w.writeInt(keyActions[k]);
        }
        client.send(w);
    }

    public void shopBuy(int index, int itemId, int qty) {
        PacketWriter w = new PacketWriter(RecvOpcode.NPC_SHOP.getValue());
        w.writeByte(0).writeShort(index).writeInt(itemId).writeShort(qty);
        client.send(w);
    }

    public void shopSell(int slot, int itemId, int qty) {
        PacketWriter w = new PacketWriter(RecvOpcode.NPC_SHOP.getValue());
        w.writeByte(1).writeShort(slot).writeInt(itemId).writeShort(qty);
        client.send(w);
    }

    public void shopRecharge(int slot) {
        PacketWriter w = new PacketWriter(RecvOpcode.NPC_SHOP.getValue());
        w.writeByte(2).writeShort(slot);
        client.send(w);
    }

    public void shopLeave() {
        PacketWriter w = new PacketWriter(RecvOpcode.NPC_SHOP.getValue());
        w.writeByte(3);
        client.send(w);
        shop = null;
    }

    /** Storage: 4 take out (type, slot), 5 store (slot, itemId, qty), 6 arrange?, 7 meso, 8 close. */
    public void storage(int mode, int a, int b, int c) {
        PacketWriter w = new PacketWriter(RecvOpcode.STORAGE.getValue());
        w.writeByte(mode);
        switch (mode) {
            case 4: w.writeByte(a); w.writeByte(b); break;
            case 5: w.writeShort(a); w.writeInt(b); w.writeShort(c); break;
            case 7: w.writeInt(a); break;
            default: break;
        }
        client.send(w);
        if (mode == 8) storage = null;
    }

    /** SPAWN_PET (showPet): int owner, byte slot, then 1 + 0 + item, name, unique id, x, y, stance, fh; or 0 + hunger. */
    private void petPacket(PacketReader r) {
        int owner = r.readInt();
        if (data() != null && owner != data().stats.id) return;
        int slot = r.readUByte();
        if (slot < 0 || slot > 2) return;
        if (r.readUByte() == 1) {
            r.readUByte();
            int itemId = r.readInt();
            String name = r.readString();
            long uid = r.readLong();
            petSpecs[slot] = new Object[]{itemId, name, uid};
            pets[slot] = null; // made at the owner's side on the next tick
        } else {
            petSpecs[slot] = null;
            pets[slot] = null;
        }
        events.refresh();
    }

    /** SPAWN_PET request: summon or put away the pet in a Cash inventory slot. */
    public void spawnPet(int slot) {
        PacketWriter w = new PacketWriter(RecvOpcode.SPAWN_PET.getValue());
        w.writeInt(stamp());
        w.writeByte(slot);
        w.writeByte(0);
        w.writeByte(0); // not the lead pet
        client.send(w);
    }

    /** QUEST_ACTION: 1 start, 2 complete, 3 forfeit, 4 scripted start, 5 scripted end. */
    public void questAction(int action, int questId, int npcId, int selection) {
        PacketWriter w = new PacketWriter(RecvOpcode.QUEST_ACTION.getValue());
        w.writeByte(action);
        w.writeShort(questId);
        if (action != 3) {
            w.writeInt(npcId);
            w.writeShort((int) player.phys.x).writeShort((int) player.phys.y);
            if (action == 2 && selection >= 0) w.writeShort(selection);
        }
        client.send(w);
    }

    public void faceExpression(int emote) {
        PacketWriter w = new PacketWriter(RecvOpcode.FACE_EXPRESSION.getValue());
        w.writeInt(emote);
        client.send(w);
    }

    /** Respawn after dying (CHANGE_MAP with the dead flag). */
    public void revive() {
        PacketWriter w = new PacketWriter(RecvOpcode.CHANGE_MAP.getValue());
        w.writeByte(1);
        w.writeInt(0);
        w.writeString("");
        w.writeByte(0);
        w.writeByte(0);
        w.writeByte(0);
        client.send(w);
    }

    // ------------------------------------------------------------------ drawing

    /** Draws world objects on a layer (called between the map's layers). */
    public void drawLayer(Batch batch, int layer, float alpha) {
        if (field == null) return;
        for (Reactor rc : reactors.values()) if (layer == 7) rc.draw(batch, timeMs);
        for (Npc n : npcs.values()) if (field.footholds.get(n.fh).layer == layer) n.draw(batch, timeMs);
        if (layer == Math.max(0, Math.min(7, player.layer()))) for (Pet p : pets) if (p != null) p.draw(batch, alpha, timeMs);
        for (Mob m : mobs.values()) if (m.phys.fhlayer == layer || (layer == 7 && m.canFly)) m.draw(batch, alpha, timeMs);
    }

    /** Drops, effects and damage numbers above everything else in the world. */
    public void drawTop(Batch batch, float alpha) {
        for (Drop d : drops.values()) d.draw(batch, alpha, timeMs);
        for (Effect e : effects) {
            double x = e.x, y = e.y;
            if (e.followPlayer) {
                x = player.phys.drawX(alpha);
                y = player.phys.drawY(alpha);
            }
            e.anim.draw(batch, (float) x, (float) y, e.flip, timeMs - e.start, 1f);
        }
        numbers.draw(batch, alpha);
    }

    public Effect screenEffect() {
        return screenEffect;
    }

    public void drawScreenEffect(Batch batch, float cx, float cy) {
        Effect e = screenEffect;
        if (e != null) e.anim.draw(batch, cx, cy, false, timeMs - e.start, 1f);
    }

    public void dispose() {
        bank.dispose();
    }
}
