package maple.map;

import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All footholds of a map plus the MapleStory movement rules on them (ground following,
 * walls, falling, slopes). Follows the v83 client's behaviour as reimplemented by open-source clients.
 */
public final class FootholdTree {
    public static final double FLY_FRICTION = 0.05;

    private final Map<Integer, Foothold> footholds = new HashMap<>();
    private final List<Foothold> list = new ArrayList<>();
    /** Floors by x column (bucketed) for fast "what's below me" lookups. */
    private final Map<Integer, List<Foothold>> byColumn = new HashMap<>();
    private static final int COLUMN = 32;
    public int wallLeft, wallRight, borderTop, borderBottom;
    private static final Foothold NONE = new Foothold(0, 0, 0, 0, 0, 0, 0, 0);

    public FootholdTree(WzNode src) {
        int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE, top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (WzNode layer : src.children()) {
            int layerId = parse(layer.name, 0);
            for (WzNode group : layer.children()) {
                for (WzNode f : group.children()) {
                    int id = parse(f.name, -1);
                    if (id <= 0) continue;
                    Foothold fh = new Foothold(id, layerId, f.getInt("prev", 0), f.getInt("next", 0),
                            f.getInt("x1", 0), f.getInt("y1", 0), f.getInt("x2", 0), f.getInt("y2", 0));
                    footholds.put(id, fh);
                    list.add(fh);
                    left = Math.min(left, fh.l());
                    right = Math.max(right, fh.r());
                    top = Math.min(top, fh.t());
                    bottom = Math.max(bottom, fh.b());
                    if (!fh.isWall()) {
                        for (int c = Math.floorDiv(fh.l(), COLUMN); c <= Math.floorDiv(fh.r(), COLUMN); c++) {
                            List<Foothold> col = byColumn.get(c);
                            if (col == null) byColumn.put(c, col = new ArrayList<>());
                            col.add(fh);
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            left = -400; right = 400; top = -300; bottom = 300;
        }
        wallLeft = left + 25;
        wallRight = right - 25;
        borderTop = top - 300;
        borderBottom = bottom + 100;
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    public List<Foothold> all() { return list; }

    public Foothold get(int id) {
        Foothold f = footholds.get(id);
        return f == null ? NONE : f;
    }

    /** The highest floor at x that is at or below y. 0 if there is none. */
    public int fhBelow(double fx, double fy) {
        int ret = 0;
        double best = borderBottom;
        List<Foothold> col = byColumn.get(Math.floorDiv((int) Math.floor(fx), COLUMN));
        if (col == null) return 0;
        for (Foothold fh : col) {
            if (fx < fh.l() || fx > fh.r()) continue;
            double yc = fh.groundBelow(fx);
            if (best >= yc && yc >= fy) {
                best = yc;
                ret = fh.id;
            }
        }
        return ret;
    }

    public double wall(int curId, boolean left, double fy) {
        int y = (int) fy;
        int top = y - 50, bottom = y - 1;
        Foothold cur = get(curId);
        if (left) {
            Foothold prev = get(cur.prev);
            if (prev.isBlocking(top, bottom)) return cur.l();
            Foothold pp = get(prev.prev);
            if (pp.isBlocking(top, bottom)) return prev.l();
            return wallLeft;
        } else {
            Foothold next = get(cur.next);
            if (next.isBlocking(top, bottom)) return cur.r();
            Foothold nn = get(next.next);
            if (nn.isBlocking(top, bottom)) return next.r();
            return wallRight;
        }
    }

    public double edge(int curId, boolean left) {
        Foothold fh = get(curId);
        if (left) {
            if (fh.prev == 0) return fh.l();
            Foothold prev = get(fh.prev);
            if (prev.prev == 0 || prev.isWall()) return prev.isWall() ? fh.l() : prev.l();
            return wallLeft;
        } else {
            if (fh.next == 0) return fh.r();
            Foothold next = get(fh.next);
            if (next.next == 0 || next.isWall()) return next.isWall() ? fh.r() : next.r();
            return wallRight;
        }
    }

    // ---- physics ----

    /** One 8 ms step for an object. */
    public void move(PhysicsObject p) {
        p.lastX = p.x;
        p.lastY = p.y;
        switch (p.mode) {
            case NORMAL:
                moveNormal(p);
                limitMovement(p);
                break;
            case FLYING:
                moveFlying(p);
                limitMovement(p);
                break;
            case FIXED:
                break;
        }
        updateFoothold(p);
        p.x += p.hspeed;
        p.y += p.vspeed;
    }

    /** Ground and air movement with the client's Physics.img constants (walk force/drag, float drag, gravity). */
    private void moveNormal(PhysicsObject p) {
        int d = p.walkDir;
        double max = Physics.walkSpeed * p.speedMul;
        if (p.onGround) {
            if (d != 0) {
                double acc = Physics.walkForce;
                if (p.hspeed * d < 0) acc += Physics.walkDrag;
                p.hspeed += d * acc;
                if (p.hspeed * d > max) p.hspeed = d * max;
            } else {
                p.hspeed = toward(p.hspeed, 0, Physics.walkDrag);
            }
            if (p.jumpRequest) {
                p.vspeed = -Physics.jumpSpeed * p.jumpMul;
            }
        } else if ((p.flags & PhysicsObject.NO_GRAVITY) == 0) {
            p.vspeed = Math.min(p.vspeed + Physics.gravity, Physics.fallSpeed);
            double airMax = max * 0.5;
            if (d == 0) {
                p.hspeed = toward(p.hspeed, 0, Physics.floatDrag2);
            } else if (p.hspeed * d < 0) {
                p.hspeed += d * Physics.floatDrag1;
                if (p.hspeed * d > airMax) p.hspeed = d * airMax;
            } else if (Math.abs(p.hspeed) < airMax) {
                p.hspeed = d * Math.min(airMax, Math.abs(p.hspeed) + Physics.floatDrag2);
            }
        }
        p.jumpRequest = false;
        p.hforce = 0;
        p.vforce = 0;
    }

    private static double toward(double v, double target, double step) {
        if (v > target) return Math.max(target, v - step);
        if (v < target) return Math.min(target, v + step);
        return v;
    }

    private void moveFlying(PhysicsObject p) {
        p.hacc = p.hforce;
        p.vacc = p.vforce;
        p.hforce = 0;
        p.vforce = 0;
        p.hacc -= FLY_FRICTION * p.hspeed;
        p.vacc -= FLY_FRICTION * p.vspeed;
        p.hspeed += p.hacc;
        p.vspeed += p.vacc;
        if (Math.abs(p.hspeed) < 0.001) p.hspeed = 0;
        if (Math.abs(p.vspeed) < 0.001) p.vspeed = 0;
    }

    private void limitMovement(PhysicsObject p) {
        if (p.hmobile()) {
            double cx = p.x, nx = p.nextX();
            boolean left = p.hspeed < 0;
            double wall = wall(p.fhid, left, p.nextY());
            boolean collision = left ? cx >= wall && nx <= wall : cx <= wall && nx >= wall;
            if (!collision && (p.flags & PhysicsObject.TURN_AT_EDGES) != 0) {
                wall = edge(p.fhid, left);
                collision = left ? cx >= wall && nx <= wall : cx <= wall && nx >= wall;
            }
            if (collision) {
                p.limitX(wall);
                p.flags &= ~PhysicsObject.TURN_AT_EDGES;
            }
        }
        if (p.vmobile() && p.mode == PhysicsObject.Mode.NORMAL) {
            double cy = p.y, ny = p.nextY();
            Foothold fh = get(p.fhid);
            double g1 = fh.groundBelow(p.x), g2 = fh.groundBelow(p.nextX());
            boolean collision = p.fhid != 0 && cy <= g1 && ny >= g2;
            if (collision) {
                p.limitY(g2);
                limitMovement(p);
            } else if (ny < borderTop) {
                p.limitY(borderTop);
            } else if (ny > borderBottom) {
                p.limitY(borderBottom);
            }
        }
    }

    private void updateFoothold(PhysicsObject p) {
        if (p.mode == PhysicsObject.Mode.FIXED && p.fhid > 0) return;
        Foothold cur = get(p.fhid);
        boolean checkSlope = false;
        double x = p.x, y = p.y;
        if (p.onGround) {
            if (Math.floor(x) > cur.r()) p.fhid = cur.next;
            else if (Math.ceil(x) < cur.l()) p.fhid = cur.prev;
            if (p.fhid != 0 && get(p.fhid).isWall()) p.fhid = 0;
            if (p.fhid == 0) p.fhid = fhBelow(x, y);
            else checkSlope = true;
        } else {
            p.fhid = fhBelow(x, y);
            if (p.fhid == 0) return;
        }
        Foothold next = get(p.fhid);
        p.fhslope = next.slope();
        double ground = next.groundBelow(x);
        if (p.vspeed == 0 && checkSlope) {
            double vdelta = Math.abs(p.fhslope);
            if (p.fhslope < 0) vdelta *= (ground - y);
            else if (p.fhslope > 0) vdelta *= (y - ground);
            if (cur.slope() != 0 || next.slope() != 0) {
                if (p.hspeed > 0 && vdelta <= p.hspeed) p.y = ground;
                else if (p.hspeed < 0 && vdelta >= p.hspeed) p.y = ground;
            }
        }
        p.onGround = p.mode == PhysicsObject.Mode.NORMAL && Math.abs(p.y - ground) < 0.001;
        if (p.onGround) p.y = ground;
        if (p.onGround || (p.flags & PhysicsObject.CHECK_BELOW) != 0) {
            int below = fhBelow(x, next.groundBelow(x) + 1.0);
            if (below > 0) {
                double nextGround = get(below).groundBelow(x);
                p.enableJumpDown = (nextGround - ground) < 600.0;
                p.groundBelow = ground + 1.0;
            } else {
                p.enableJumpDown = false;
            }
            p.flags &= ~PhysicsObject.CHECK_BELOW;
        }
        if (p.fhlayer == 0 || p.onGround) p.fhlayer = next.layer;
        if (p.fhid == 0) {
            p.fhid = cur.id;
            p.limitX(cur.x1);
        }
    }
}
