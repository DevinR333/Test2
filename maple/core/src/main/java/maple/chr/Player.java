package maple.chr;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.map.Field;
import maple.map.FootholdTree;
import maple.map.Ladder;
import maple.map.PhysicsObject;
import maple.input.Pad;

/** The player character: MapleStory-style walking, jumping, dropping down, climbing and proning. */
public final class Player {
    public enum State { STAND, WALK, FALL, PRONE, LADDER, ROPE }

    public final PhysicsObject phys = new PhysicsObject();
    public State state = State.FALL;
    public boolean facingRight;
    public String name = "Player";
    private Avatar avatar;
    private int frame;
    private int frameTime;
    private String stance = "stand1";
    private Ladder ladder;
    private int ladderCooldown;

    // Stats that shape movement (100 = normal, like the v83 client).
    public int speed = 100, jump = 100;

    // Combat state
    /** Body action being played (attack/skill stance), or null. */
    public String action;
    private int actionLeft, actionFrame, actionFrameTime;
    private float actionSpeed = 1;
    public int alertMs, invincibleMs;
    public boolean dead;
    private int knockTicks;

    // Chairs: a portable chair item (Item.wz/Install/0301) or a map seat.
    public int chairItem;
    public int seat = -1;
    public maple.gfx.Animation chairAnim;
    private long chairTime;

    public boolean sitting() { return chairItem != 0 || seat >= 0; }

    public void sit(int item, int seatId, maple.gfx.Animation anim) {
        chairItem = item;
        seat = seatId;
        chairAnim = anim;
        chairTime = 0;
        action = null;
        phys.walkDir = 0;
        phys.hspeed = 0;
        state = State.STAND;
    }

    public void standUp() {
        chairItem = 0;
        seat = -1;
        chairAnim = null;
    }

    public void setAvatar(Avatar a) { avatar = a; }
    public Avatar avatar() { return avatar; }

    double climbSpeed() { return maple.map.Physics.walkSpeed * speed / 100.0; }

    /** Plays a body action (attack, skill) for its WZ duration divided by speed. */
    public void startAction(String stance, float speedFactor) {
        if (avatar == null || !avatar.has(stance)) return;
        action = stance;
        actionSpeed = Math.max(0.3f, speedFactor);
        actionLeft = (int) (avatar.duration(stance) / actionSpeed);
        actionFrame = 0;
        actionFrameTime = 0;
        alertMs = 5000;
    }

    public boolean inAction() { return action != null; }

    /** Current frame of the running action (for afterimages/hit timing). */
    public int actionFrame() { return actionFrame; }

    /** Hit by a monster: knock back away from it unless climbing. */
    public void knockback(boolean fromLeft) {
        alertMs = 5000;
        invincibleMs = 2000;
        if (state == State.LADDER || state == State.ROPE || dead) return;
        phys.hspeed = fromLeft ? 1.5 : -1.5;
        phys.vspeed = -3.5 * 0.5;
        phys.onGround = false;
        state = State.FALL;
        knockTicks = 30;
    }

    public void die() {
        dead = true;
        action = null;
        phys.walkDir = 0;
    }

    public void revive() {
        dead = false;
        invincibleMs = 2000;
    }

    public void spawn(double x, double y) {
        phys.setPosition(x, y);
        action = null;
        phys.mode = PhysicsObject.Mode.NORMAL;
        state = State.FALL;
        ladder = null;
        standUp();
    }

    /** One 8 ms step. */
    public void update(Field field, Pad in) {
        FootholdTree fht = field.footholds;
        if (ladderCooldown > 0) ladderCooldown--;
        phys.walkDir = 0;
        phys.speedMul = speed / 100.0;
        phys.jumpMul = jump / 100.0;
        if (alertMs > 0) alertMs -= 8;
        if (invincibleMs > 0) invincibleMs -= 8;
        if (knockTicks > 0) knockTicks--;
        if (action != null) {
            actionLeft -= 8;
            if (actionLeft <= 0) action = null;
        }
        if (sitting()) chairTime += 8;
        boolean locked = dead || knockTicks > 0 || sitting() || (action != null && state != State.FALL);
        if (locked) {
            if (state != State.LADDER && state != State.ROPE) {
                fht.move(phys);
                if (phys.onGround && state == State.FALL) state = State.STAND;
                else if (!phys.onGround) state = State.FALL;
            }
            animate(8);
            return;
        }
        switch (state) {
            case STAND: case WALK: case PRONE:
                groundControl(field, in);
                break;
            case FALL:
                airControl(field, in);
                break;
            case LADDER: case ROPE:
                climb(field, in);
                break;
        }
        fht.move(phys);
        if (state != State.LADDER && state != State.ROPE) {
            if (phys.onGround) {
                if (state == State.FALL) state = State.STAND;
            } else {
                state = State.FALL;
            }
        }
        if (state == State.LADDER || state == State.ROPE) animate(phys.vspeed != 0 ? 8 : 0);
        else animate(8);
    }

    private void groundControl(Field field, Pad in) {
        boolean left = in.left && !in.right, right = in.right && !in.left;
        if (in.down && !left && !right) {
            // Grab a ladder below us, else lie down.
            if (in.downPressed || state != State.PRONE) {
                Ladder l = field.ladderAt(phys.x, phys.y, false);
                if (l != null && ladderCooldown == 0) {
                    grab(l, phys.y + 5);
                    return;
                }
            }
            state = State.PRONE;
            if (in.jump && phys.enableJumpDown) {
                // drop through the platform
                phys.y = phys.groundBelow;
                phys.onGround = false;
                phys.fhid = 0;
                phys.vspeed = 0.5;
                state = State.FALL;
            }
            return;
        }
        if (in.up) {
            Ladder l = field.ladderAt(phys.x, phys.y, true);
            if (l != null && ladderCooldown == 0 && !in.jump) {
                grab(l, phys.y - 5);
                return;
            }
        }
        if (left || right) {
            facingRight = right;
            phys.walkDir = right ? 1 : -1;
            state = State.WALK;
        } else {
            state = State.STAND;
        }
        if (in.jump) {
            phys.jumpRequest = true;
            state = State.FALL;
        }
    }

    private void airControl(Field field, Pad in) {
        if (in.left && !in.right) {
            facingRight = false;
            phys.walkDir = -1;
        } else if (in.right && !in.left) {
            facingRight = true;
            phys.walkDir = 1;
        }
        if (in.up && ladderCooldown == 0) {
            Ladder l = field.ladderAt(phys.x, phys.y, true);
            if (l != null) grab(l, phys.y);
        }
    }

    private void grab(Ladder l, double y) {
        ladder = l;
        state = l.isLadder ? State.LADDER : State.ROPE;
        phys.mode = PhysicsObject.Mode.FIXED;
        phys.hspeed = phys.vspeed = phys.hforce = phys.vforce = 0;
        phys.x = l.x;
        phys.y = Math.max(l.y1, Math.min(l.y2, y));
        phys.onGround = false;
    }

    private void release(State next) {
        ladder = null;
        phys.mode = PhysicsObject.Mode.NORMAL;
        phys.fhid = 0;
        state = next;
        ladderCooldown = 25;
    }

    private void climb(Field field, Pad in) {
        Ladder l = ladder;
        if (l == null) { release(State.FALL); return; }
        if (in.jump && (in.left || in.right)) {
            release(State.FALL);
            facingRight = in.right;
            phys.hspeed = (in.right ? 1 : -1) * maple.map.Physics.walkSpeed;
            phys.vspeed = -maple.map.Physics.jumpSpeed * 0.6;
            return;
        }
        double v = 0;
        if (in.up && !in.down) v = -climbSpeed();
        else if (in.down && !in.up) v = climbSpeed();
        phys.vspeed = 0;
        phys.hspeed = 0;
        double ny = phys.y + v;
        if (ny < l.y1 - 3) {
            // Climbed out of the top: stand on the platform there.
            FootholdTree fht = field.footholds;
            int fh = fht.fhBelow(l.x, l.y1 - 40);
            release(State.FALL);
            if (fh != 0) {
                double g = fht.get(fh).groundBelow(l.x);
                if (g <= l.y1 + 10) {
                    phys.y = g;
                    phys.lastY = g;
                    phys.fhid = fh;
                    phys.onGround = true;
                    state = State.STAND;
                }
            }
            return;
        }
        if (ny > l.y2) {
            release(State.FALL);
            return;
        }
        phys.vspeed = v;
    }

    private void animate(int ms) {
        if (action != null && avatar != null) {
            actionFrameTime += ms;
            int d = (int) (avatar.delay(action, actionFrame) / actionSpeed);
            if (actionFrameTime >= d) {
                actionFrameTime -= d;
                if (actionFrame + 1 < avatar.frameCount(action)) actionFrame++;
            }
            return;
        }
        String next = stanceFor();
        if (!next.equals(stance)) {
            stance = next;
            frame = 0;
            frameTime = 0;
        }
        if (avatar == null) return;
        frameTime += ms;
        int d = avatar.delay(stance, frame);
        if (frameTime >= d) {
            frameTime -= d;
            int n = avatar.frameCount(stance);
            frame = n == 0 ? 0 : (frame + 1) % n;
        }
    }

    private String stanceFor() {
        if (dead) return "dead";
        if (sitting()) return avatar != null && avatar.has("sit") ? "sit" : "stand1";
        switch (state) {
            case WALK: return avatar != null ? avatar.walkStance() : "walk1";
            case FALL: return "jump";
            case PRONE: return "prone";
            case LADDER: return "ladder";
            case ROPE: return "rope";
            default:
                if (alertMs > 0 && avatar != null && avatar.has("alert")) return "alert";
                return avatar != null ? avatar.standStance() : "stand1";
        }
    }

    public void draw(Batch batch, float alpha) {
        if (avatar == null) return;
        float x = (float) Math.round(phys.drawX(alpha));
        float y = (float) Math.round(phys.drawY(alpha));
        boolean climbing = state == State.LADDER || state == State.ROPE;
        if (chairAnim != null && !chairAnim.isEmpty()) chairAnim.draw(batch, x, y, facingRight, chairTime, 1f);
        if (invincibleMs > 0 && !dead && (invincibleMs / 100) % 2 == 1) batch.setColor(0.5f, 0.5f, 0.5f, 0.5f);
        if (action != null) avatar.draw(batch, action, actionFrame, x, y, facingRight);
        else avatar.draw(batch, stance, frame, x, y, !climbing && facingRight);
        batch.setColor(1, 1, 1, 1);
    }

    public int layer() { return phys.fhlayer; }

    /** v83 movement stance byte: walk 2, stand 4, jump 6, prone 10, ladder 14, rope 16; +1 when facing left. */
    public int stanceByte() {
        int s;
        if (sitting()) return 20 + (facingRight ? 0 : 1);
        switch (state) {
            case WALK: s = 2; break;
            case FALL: s = 6; break;
            case PRONE: s = 10; break;
            case LADDER: s = 14; break;
            case ROPE: s = 16; break;
            default: s = 4; break;
        }
        return s + (facingRight ? 0 : 1);
    }
}
