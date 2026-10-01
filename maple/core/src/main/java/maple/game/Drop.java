package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.map.FootholdTree;
import maple.map.PhysicsObject;

/**
 * An item or meso drop on the map. Spawn arc, spin, floating bob and the pickup flight follow
 * HeavenClient's Drop (AGPL).
 */
public final class Drop {
    public enum State { DROPPED, FLOATING, PICKED_UP }

    public final int oid, itemId, owner;
    public final boolean meso;
    public final int pickupType;
    public final boolean playerDrop;
    public final PhysicsObject phys = new PhysicsObject();
    private final Animation icon;
    public State state;
    private float angle, lastAngle, opacity = 1;
    private double baseY, moved;
    private final int destX, destY;
    private PhysicsObject looter;
    public boolean gone;
    /** When the player asked to pick it up (avoid spamming the server). */
    public long pickupRequested;

    public Drop(int oid, int itemId, boolean meso, int owner, int startX, int startY, int destX, int destY,
                int pickupType, int mode, boolean playerDrop, Animation icon) {
        this.oid = oid;
        this.itemId = itemId;
        this.meso = meso;
        this.owner = owner;
        this.pickupType = pickupType;
        this.playerDrop = playerDrop;
        this.icon = icon;
        this.destX = destX;
        this.destY = destY;
        phys.setPosition(startX, startY - 4);
        switch (mode) {
            case 0:
            case 1:
                state = State.DROPPED;
                baseY = destY - 4;
                phys.vspeed = -5.0;
                phys.hspeed = (destX - startX) / 48.0;
                break;
            case 2:
                state = State.FLOATING;
                phys.setPosition(destX, destY - 4);
                baseY = destY - 4;
                phys.mode = PhysicsObject.Mode.FIXED;
                break;
            default:
                state = State.PICKED_UP;
                phys.vspeed = -5.0;
                break;
        }
    }

    public void update(FootholdTree fht) {
        lastAngle = angle;
        if (state == State.FLOATING) {
            phys.lastX = phys.x;
            phys.lastY = phys.y;
        } else {
            fht.move(phys);
        }
        if (state == State.DROPPED) {
            if (phys.onGround || phys.y >= destY - 4 && phys.vspeed > 0) {
                phys.hspeed = 0;
                phys.mode = PhysicsObject.Mode.FIXED;
                state = State.FLOATING;
                angle = lastAngle = 0;
                phys.setPosition(destX, destY - 4);
                baseY = destY - 4;
            } else {
                angle += 0.2f;
            }
        }
        if (state == State.FLOATING) {
            phys.lastY = phys.y;
            phys.y = baseY + 5.0 + (Math.cos(moved) - 1.0) * 2.5;
            moved = moved < 360 ? moved + 0.025 : 0;
        } else if (state == State.PICKED_UP) {
            if (looter != null) {
                double hdelta = looter.x - phys.x;
                phys.hspeed = looter.hspeed / 2.0 + (hdelta - 16.0) / 48.0;
            }
            opacity -= 1f / 48f;
            if (opacity <= 1f / 48f) gone = true;
        }
    }

    /** Server removed it: 0 expire (fade), 1 vanish, 2+ picked up by someone. */
    public void expire(int type, PhysicsObject by) {
        switch (type) {
            case 0:
                state = State.PICKED_UP;
                break;
            case 1:
                gone = true;
                break;
            default:
                angle = 0;
                state = State.PICKED_UP;
                looter = by;
                phys.vspeed = -4.5;
                phys.mode = PhysicsObject.Mode.NORMAL;
                phys.onGround = false;
                break;
        }
    }

    public void draw(Batch batch, float alpha, long timeMs) {
        if (gone || icon == null || icon.isEmpty()) return;
        float x = (float) Math.round(phys.drawX(alpha)), y = (float) Math.round(phys.drawY(alpha));
        float a = Math.max(0, Math.min(1, opacity));
        int f = icon.frameAt(timeMs);
        Sprite s = icon.frames[f];
        if (s == null || s.region == null) return;
        batch.setColor(a, a, a, a);
        float rot = (lastAngle + (angle - lastAngle) * alpha) * 57.29578f;
        if (rot == 0) {
            s.draw(batch, x, y, false);
        } else {
            // spin around the icon's centre while flying out
            TextureRegion r = s.region;
            batch.draw(r, x - s.ox, y - s.oy, s.w / 2f, s.h / 2f, s.w, s.h, 1, 1, rot);
        }
        batch.setColor(1, 1, 1, 1);
    }

    /** True if the player standing at (px, py) can pick it up. */
    public boolean inReach(double px, double py) {
        if (state != State.FLOATING) return false;
        return Math.abs(phys.x - px) <= 30 && py >= phys.y - 40 && py <= phys.y + 40;
    }
}
