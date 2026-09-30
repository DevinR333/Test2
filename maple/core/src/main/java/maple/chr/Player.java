package maple.chr;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.map.Field;
import maple.map.FootholdTree;
import maple.map.Ladder;
import maple.map.PhysicsObject;
import maple.ui.Controls;

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

    public void setAvatar(Avatar a) { avatar = a; }
    public Avatar avatar() { return avatar; }

    double walkForce() { return 0.05 + 0.11 * speed / 100.0; }
    double jumpForce() { return 1.0 + 3.5 * jump / 100.0; }
    double climbSpeed() { return 0.8 * speed / 100.0; }

    public void spawn(double x, double y) {
        phys.setPosition(x, y);
        phys.mode = PhysicsObject.Mode.NORMAL;
        state = State.FALL;
        ladder = null;
    }

    /** One 8 ms step. */
    public void update(Field field, Controls in) {
        FootholdTree fht = field.footholds;
        if (ladderCooldown > 0) ladderCooldown--;
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

    private void groundControl(Field field, Controls in) {
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
            phys.hforce += right ? walkForce() : -walkForce();
            state = State.WALK;
        } else {
            state = State.STAND;
        }
        if (in.jump) {
            phys.vforce = -jumpForce();
            state = State.FALL;
        }
    }

    private void airControl(Field field, Controls in) {
        double h = phys.hspeed;
        if (in.left && !in.right) {
            facingRight = false;
            if (h > 0) phys.hspeed -= 0.025;
            else if (h > -0.35) phys.hspeed -= 0.01;
        } else if (in.right && !in.left) {
            facingRight = true;
            if (h < 0) phys.hspeed += 0.025;
            else if (h < 0.35) phys.hspeed += 0.01;
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

    private void climb(Field field, Controls in) {
        Ladder l = ladder;
        if (l == null) { release(State.FALL); return; }
        if (in.jump && (in.left || in.right)) {
            release(State.FALL);
            facingRight = in.right;
            phys.hspeed = in.right ? 1.2 : -1.2;
            phys.vspeed = -2.2;
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
        switch (state) {
            case WALK: return "walk1";
            case FALL: return "jump";
            case PRONE: return "prone";
            case LADDER: return "ladder";
            case ROPE: return "rope";
            default: return "stand1";
        }
    }

    public void draw(Batch batch, float alpha) {
        if (avatar == null) return;
        float x = (float) Math.round(phys.drawX(alpha));
        float y = (float) Math.round(phys.drawY(alpha));
        boolean climbing = state == State.LADDER || state == State.ROPE;
        avatar.draw(batch, stance, frame, x, y, !climbing && facingRight);
    }

    public int layer() { return phys.fhlayer; }
}
