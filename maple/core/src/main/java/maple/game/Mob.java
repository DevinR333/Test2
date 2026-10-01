package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.map.FootholdTree;
import maple.map.Physics;
import maple.map.PhysicsObject;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A monster the server spawned. Ported from HeavenClient's Mob (AGPL): the controlling client (us, in
 * single player) runs its wandering, knockback and death, and reports each move to the server.
 */
public final class Mob {
    public enum Stance { MOVE, STAND, JUMP, HIT, DIE, FLY }

    public final int oid, id;
    public final String name;
    public final int level, watk, matk, wdef, mdef, acc, avoid, knockback, maxHp, exp;
    public final boolean touchDamage, undead, noFlip, boss, canMove, canJump, canFly;
    public final PhysicsObject phys = new PhysicsObject();
    private final Map<Stance, Animation> anims = new HashMap<>();
    private final String soundBase;
    public Stance stance = Stance.STAND;
    private long stanceStart;
    /** true = facing right. */
    public boolean flip;
    public boolean controlled, aggro;
    public int hpPercent = -1;
    private long showHpUntil;
    public boolean dying, dead, fading;
    private float opacity = 1;
    private boolean fadeIn;
    private int counter;
    private int flyDir; // 0 straight, 1 up, 2 down
    public int rx0 = Integer.MIN_VALUE, rx1 = Integer.MAX_VALUE;
    private final double groundSpeed, flySpeed;
    private static final Random RNG = new Random();
    /** Ms since the last touch damage this mob dealt. */
    public long lastTouch;

    public Mob(Wz wz, SpriteBank bank, int oid, int id, int x, int y, int stanceByte, int fh, boolean newSpawn, boolean controlled, boolean aggro) {
        this.oid = oid;
        this.id = id;
        String strId = String.format("%07d", id);
        WzNode src = wz.get("Mob/" + strId + ".img");
        WzNode info = src.get("info");
        level = info.getInt("level", 1);
        watk = info.getInt("PADamage", 1);
        matk = info.getInt("MADamage", 1);
        wdef = info.getInt("PDDamage", 0);
        mdef = info.getInt("MDDamage", 0);
        acc = info.getInt("acc", 0);
        avoid = info.getInt("eva", 0);
        knockback = info.getInt("pushed", 1);
        maxHp = info.getInt("maxHP", 1);
        exp = info.getInt("exp", 0);
        touchDamage = info.getInt("bodyAttack", 0) != 0;
        undead = info.getInt("undead", 0) != 0;
        noFlip = info.getInt("noFlip", 0) != 0;
        boss = info.getInt("boss", 0) != 0;
        WzNode look = src;
        WzNode link = info.get("link");
        if (link.exists()) {
            WzNode l = wz.get("Mob/" + String.format("%07d", link.asInt(0)) + ".img");
            if (l.exists()) look = l;
        }
        canFly = look.get("fly").exists();
        canJump = look.get("jump").exists();
        canMove = look.get("move").exists() || canFly;
        if (canFly) {
            put(Stance.STAND, look.get("fly"), bank);
            put(Stance.MOVE, look.get("fly"), bank);
        } else {
            put(Stance.STAND, look.get("stand"), bank);
            put(Stance.MOVE, look.get("move"), bank);
        }
        put(Stance.JUMP, look.get("jump"), bank);
        put(Stance.HIT, look.get("hit1"), bank);
        put(Stance.DIE, look.get("die1"), bank);
        if (!anims.containsKey(Stance.STAND)) {
            for (WzNode n : look.children()) {
                if (n.name.equals("info")) continue;
                Animation a = Animation.of(n, bank);
                if (!a.isEmpty()) {
                    anims.put(Stance.STAND, a);
                    break;
                }
            }
        }
        name = wz.get("String/Mob.img/" + id).getString("name", "");
        soundBase = "Mob.img/" + strId + "/";
        int speed = info.getInt("speed", 0), fly = info.getInt("flySpeed", 0);
        groundSpeed = Math.max(0, 100 + speed) / 100.0 * 60.0 / Math.max(1e-6, Physics.walkSpeed / 0.008);
        flySpeed = Math.max(0, 100 + fly) * 0.0005;
        phys.setPosition(x, y);
        phys.fhid = fh;
        phys.flags |= PhysicsObject.TURN_AT_EDGES;
        if (canFly) phys.mode = PhysicsObject.Mode.FLYING;
        this.controlled = controlled;
        this.aggro = aggro;
        setStanceByte(stanceByte);
        if (newSpawn) {
            fadeIn = true;
            opacity = 0;
        }
    }

    private void put(Stance s, WzNode n, SpriteBank bank) {
        if (!n.exists()) return;
        Animation a = Animation.of(n, bank);
        if (!a.isEmpty()) anims.put(s, a);
    }

    private Animation anim(Stance s) {
        Animation a = anims.get(s);
        if (a == null) a = anims.get(Stance.STAND);
        if (a == null) a = anims.get(Stance.MOVE);
        return a;
    }

    /** v83 move stance byte: even = facing right. 2 move, 4 stand, 6 jump, 8 hit, 10 die... */
    public void setStanceByte(int b) {
        flip = b % 2 == 0;
        if (!flip) b -= 1;
        if (b < 2) b = 2;
        Stance s;
        switch (b) {
            case 2: s = Stance.MOVE; break;
            case 4: s = Stance.STAND; break;
            case 6: s = Stance.JUMP; break;
            case 8: s = Stance.HIT; break;
            case 10: s = Stance.DIE; break;
            default: s = Stance.MOVE; break;
        }
        setStance(s);
    }

    public int stanceByte() {
        int b;
        switch (stance) {
            case STAND: b = 4; break;
            case JUMP: b = 6; break;
            case HIT: b = 8; break;
            case DIE: b = 10; break;
            default: b = 2; break;
        }
        return b + (flip ? 0 : 1);
    }

    void setStance(Stance s) {
        if (stance != s) {
            stance = s;
            stanceStart = now;
        }
    }

    private long now;

    /** One 8 ms step. Returns true when it wants to report its movement to the server. */
    public boolean update(FootholdTree fht, long timeMs) {
        now = timeMs;
        Animation a = anim(stance);
        boolean aniEnd = a == null || (timeMs - stanceStart) >= a.durationMs();
        if (dying && stance == Stance.DIE && aniEnd) dead = true;
        if (fading) {
            opacity -= 0.025f;
            if (opacity < 0.025f) {
                opacity = 0;
                fading = false;
                dead = true;
            }
        } else if (fadeIn) {
            opacity += 0.025f;
            if (opacity > 0.975f) {
                opacity = 1;
                fadeIn = false;
            }
        }
        if (dead) return false;
        boolean report = false;
        if (!dying) {
            if (!canFly && (phys.flags & PhysicsObject.TURN_AT_EDGES) == 0) {
                flip = !flip;
                phys.flags |= PhysicsObject.TURN_AT_EDGES;
                if (stance == Stance.HIT) setStance(Stance.STAND);
            }
            phys.walkDir = 0;
            phys.speedMul = groundSpeed;
            switch (stance) {
                case MOVE:
                    if (canFly) {
                        phys.hforce = flip ? flySpeed : -flySpeed;
                        if (flyDir == 1) phys.vforce = -flySpeed;
                        else if (flyDir == 2) phys.vforce = flySpeed;
                    } else {
                        phys.walkDir = flip ? 1 : -1;
                    }
                    break;
                case HIT:
                    if (canMove && !canFly) {
                        phys.walkDir = flip ? -1 : 1;
                        phys.speedMul = groundSpeed * 0.6;
                    } else if (canFly) {
                        phys.hforce = flip ? -0.1 : 0.1;
                    }
                    break;
                case JUMP:
                    if (phys.onGround && counter < 2) phys.jumpRequest = true;
                    break;
                default:
                    break;
            }
            // keep inside the spawn's patrol range (rx0..rx1) like the original controller
            if (stance == Stance.MOVE && !canFly && rx0 < rx1) {
                if (phys.x <= rx0 && !flip) flip = true;
                else if (phys.x >= rx1 && flip) flip = false;
            }
            fht.move(phys);
            if (controlled) {
                counter++;
                boolean next;
                switch (stance) {
                    case HIT: next = counter > 200; break;
                    case JUMP: next = phys.onGround && counter > 10; break;
                    default: next = aniEnd && counter > 200; break;
                }
                if (next) {
                    nextMove();
                    counter = 0;
                    report = true;
                }
            }
        } else {
            phys.lastX = phys.x;
            phys.lastY = phys.y;
        }
        return report;
    }

    private void nextMove() {
        if (canMove) {
            switch (stance) {
                case HIT:
                case STAND:
                    setStance(Stance.MOVE);
                    flip = RNG.nextBoolean();
                    break;
                default:
                    if (canJump && phys.onGround && RNG.nextFloat() < 0.25f) {
                        setStance(Stance.JUMP);
                    } else {
                        switch (RNG.nextInt(3)) {
                            case 0: setStance(Stance.STAND); break;
                            case 1: setStance(Stance.MOVE); flip = false; break;
                            default: setStance(Stance.MOVE); flip = true; break;
                        }
                    }
                    break;
            }
            if (stance == Stance.MOVE && canFly) flyDir = RNG.nextInt(3);
        } else {
            setStance(Stance.STAND);
        }
    }

    public void draw(Batch batch, float alpha, long timeMs) {
        if (dead) return;
        Animation a = anim(stance);
        if (a == null) return;
        long t = timeMs - stanceStart;
        if (stance == Stance.DIE || stance == Stance.HIT) t = Math.min(t, a.durationMs() - 1);
        a.draw(batch, (float) Math.round(phys.drawX(alpha)), (float) Math.round(phys.drawY(alpha)), flip && !noFlip, t, opacity);
    }

    /** Head position (for damage numbers and the HP bar), from the current frame's "head" vector. */
    public float headX() {
        return (float) phys.x;
    }

    public float headY() {
        Animation a = anim(stance);
        if (a == null || a.isEmpty()) return (float) phys.y - 50;
        Sprite s = a.first();
        return (float) phys.y - Math.max(30, s.oy);
    }

    private Sprite frameSprite() {
        Animation a = anim(stance);
        return a == null ? null : a.first();
    }

    /** Body rectangle in world coords (left, top, right, bottom) from the current frame. */
    public float[] bounds() {
        Animation a = anim(stance);
        Sprite s = a == null ? null : a.first();
        if (s == null) return new float[]{(float) phys.x - 20, (float) phys.y - 40, (float) phys.x + 20, (float) phys.y};
        float l, r;
        if (flip && !noFlip) {
            l = (float) phys.x + s.ox - s.w;
            r = (float) phys.x + s.ox;
        } else {
            l = (float) phys.x - s.ox;
            r = l + s.w;
        }
        float t = (float) phys.y - s.oy;
        return new float[]{l, t, r, t + s.h};
    }

    public boolean overlaps(float l, float t, float r, float b) {
        float[] m = bounds();
        return m[0] < r && m[2] > l && m[1] < b && m[3] > t;
    }

    public boolean alive() {
        return !dying && !dead;
    }

    /** Damage animation/knockback after we hit it (HeavenClient Mob::apply_damage). */
    public void applyDamage(int damage, boolean fromLeft) {
        maple.ui.UiSounds.playPath(soundBase + "Damage");
        if (dying && stance != Stance.DIE) {
            applyDeath();
        } else if (controlled && alive() && damage >= knockback && canMove) {
            flip = fromLeft;
            counter = 170;
            setStance(Stance.HIT);
        }
    }

    public void kill(int animation) {
        switch (animation) {
            case 0: dead = true; break;
            case 1: dying = true; applyDeath(); break;
            default: fading = true; dying = true; break;
        }
    }

    private void applyDeath() {
        setStance(Stance.DIE);
        maple.ui.UiSounds.playPath(soundBase + "Die");
        dying = true;
    }

    public void showHp(int percent, long timeMs) {
        hpPercent = Math.max(0, Math.min(100, percent));
        showHpUntil = timeMs + 2000;
    }

    public boolean hpVisible(long timeMs) {
        return hpPercent >= 0 && timeMs < showHpUntil && !dying;
    }

    /** Hit chance against this mob (HeavenClient Mob::calculate_hitchance). */
    public float hitChance(int levelDelta, int accuracy) {
        float h = accuracy / ((1.84f + 0.07f * levelDelta) * avoid + 1.0f);
        return Math.max(0.01f, h);
    }

    public double minDamage(int levelDelta, double dmg, boolean magic) {
        double v = magic ? dmg - (1 + 0.01 * levelDelta) * mdef * 0.6 : dmg * (1 - 0.01 * levelDelta) - wdef * 0.6;
        return Math.max(1, v);
    }

    public double maxDamage(int levelDelta, double dmg, boolean magic) {
        double v = magic ? dmg - (1 + 0.01 * levelDelta) * mdef * 0.5 : dmg * (1 - 0.01 * levelDelta) - wdef * 0.5;
        return Math.max(1, v);
    }

    /** A touch attack's raw damage (80-100% of PADamage). */
    public int touchAttack() {
        int min = (int) (watk * 0.8f);
        return min + RNG.nextInt(Math.max(1, watk - min + 1));
    }
}
