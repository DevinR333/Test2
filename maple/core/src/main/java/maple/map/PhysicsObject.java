package maple.map;

/** Position and velocity of anything that moves on footholds (player, mobs). Units: pixels per 8 ms tick. */
public class PhysicsObject {
    public static final int TURN_AT_EDGES = 1, NO_GRAVITY = 2, CHECK_BELOW = 4;

    public enum Mode { NORMAL, FIXED, FLYING }

    public Mode mode = Mode.NORMAL;
    public double x, y, lastX, lastY;
    public double hspeed, vspeed, hforce, vforce, hacc, vacc;
    /** Held direction: -1 left, 0 none, 1 right. */
    public int walkDir;
    /** Jump on the next tick if standing. */
    public boolean jumpRequest;
    /** Speed and jump stats as multipliers (1 = 100%). */
    public double speedMul = 1, jumpMul = 1;
    public int fhid;
    public double fhslope;
    public int fhlayer;
    public boolean onGround;
    public boolean enableJumpDown;
    public double groundBelow;
    public int flags;

    public double nextX() { return x + hspeed; }
    public double nextY() { return y + vspeed; }
    public boolean hmobile() { return hspeed != 0; }
    public boolean vmobile() { return vspeed != 0; }

    public void limitX(double nx) { x = nx; hspeed = 0; }
    public void limitY(double ny) { y = ny; vspeed = 0; }

    public void setPosition(double nx, double ny) {
        x = lastX = nx;
        y = lastY = ny;
        hspeed = vspeed = hforce = vforce = 0;
        walkDir = 0;
        jumpRequest = false;
        fhid = 0;
        onGround = false;
    }

    public double drawX(float alpha) { return lastX + (x - lastX) * alpha; }
    public double drawY(float alpha) { return lastY + (y - lastY) * alpha; }
}
