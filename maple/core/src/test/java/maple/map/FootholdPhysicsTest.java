package maple.map;

import maple.wz.FolderSource;
import maple.wz.Wz;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/** v83 ground and fall behaviour on real maps: no slipping off slopes, no falling through platforms. */
public class FootholdPhysicsTest {
    static final int[] MAPS = {100000000, 102000000, 101000000, 103000000, 104000000, 106000130};

    static Wz wz() {
        String bin = System.getenv("MAPLE_BIN_WZ");
        Assume.assumeTrue("needs MAPLE_BIN_WZ", bin != null && !bin.isEmpty());
        Wz wz = new Wz(new FolderSource(new File(bin)));
        Physics.load(wz.get("Map/Physics.img"));
        return wz;
    }

    static FootholdTree tree(Wz wz, int map) {
        return new FootholdTree(wz.get("Map/Map/Map" + map / 100000000 + "/" + map + ".img/foothold"));
    }

    @Test
    public void fallsLandOnTheFirstPlatformBelow() {
        Wz wz = wz();
        for (int map : MAPS) {
            FootholdTree t = tree(wz, map);
            int checked = 0;
            for (int x = t.wallLeft + 5; x < t.wallRight - 5; x += 7) {
                double startY = t.borderTop + 10;
                int expect = t.fhBelow(x, startY);
                if (expect == 0) continue;
                PhysicsObject p = new PhysicsObject();
                p.setPosition(x, startY);
                p.vspeed = Physics.fallSpeed; // already falling fast
                for (int i = 0; i < 5000 && !p.onGround; i++) t.move(p);
                assertTrue(map + " x=" + x + ": landed", p.onGround);
                assertEquals(map + " x=" + x + ": landed on the first platform below", t.get(expect).groundBelow(x), p.y, 1.0);
                checked++;
            }
            assertTrue(map + ": some drops", checked > 20);
        }
    }

    @Test
    public void walkingDoesNotSlipOffSlopes() {
        Wz wz = wz();
        int slopes = 0;
        for (int map : MAPS) {
            FootholdTree t = tree(wz, map);
            for (Foothold fh : t.all()) {
                if (fh.isWall() || fh.isFloor() || Math.abs(fh.slope()) > 0.9 || fh.r() - fh.l() < 20) continue;
                for (int dir : new int[]{-1, 1}) {
                    PhysicsObject p = new PhysicsObject();
                    double mx = (fh.l() + fh.r()) / 2.0;
                    p.setPosition(mx, fh.groundBelow(mx) - 3);
                    for (int i = 0; i < 20 && !p.onGround; i++) t.move(p);
                    if (!p.onGround || p.fhid != fh.id) continue; // another platform on top: not this case
                    // walk until the segment's end
                    for (int i = 0; i < 400; i++) {
                        p.walkDir = dir;
                        t.move(p);
                        Foothold cur = t.get(p.fhid);
                        if (!p.onGround) {
                            // only allowed past the end of the walked chain (a real edge)
                            fail(map + " fh " + fh.id + " dir " + dir + ": left the ground at x=" + (int) p.x + " y=" + (int) p.y + " on fh " + cur.id);
                        }
                        if (p.x <= fh.l() + 2 || p.x >= fh.r() - 2) break; // reached the end of this segment
                        assertEquals(map + " fh " + fh.id + ": on the line", fh.groundBelow(p.x), p.y, 0.01);
                    }
                    slopes++;
                }
            }
        }
        assertTrue("slopes walked: " + slopes, slopes > 20);
    }

    @Test
    public void standingStillOnASlopeStaysPut() {
        Wz wz = wz();
        int slopes = 0;
        for (int map : MAPS) {
            FootholdTree t = tree(wz, map);
            for (Foothold fh : t.all()) {
                if (fh.isWall() || fh.isFloor() || fh.r() - fh.l() < 20) continue;
                double ty = Math.abs(fh.slope()) / Math.sqrt(1 + fh.slope() * fh.slope());
                if (ty > 0.9) continue; // only very steep slopes slide in v83
                PhysicsObject p = new PhysicsObject();
                double mx = (fh.l() + fh.r()) / 2.0;
                p.setPosition(mx, fh.groundBelow(mx) - 3);
                for (int i = 0; i < 20 && !p.onGround; i++) t.move(p);
                if (!p.onGround || p.fhid != fh.id) continue;
                double x = p.x, y = p.y;
                for (int i = 0; i < 500; i++) t.move(p); // 4 seconds without input
                assertTrue(map + " fh " + fh.id + ": still standing", p.onGround);
                assertEquals(map + " fh " + fh.id + ": did not slide", x, p.x, 0.001);
                assertEquals(y, p.y, 0.001);
                assertEquals("no walking speed", 0, p.hspeed, 0.0);
                slopes++;
            }
        }
        assertTrue("slopes checked: " + slopes, slopes > 10);
    }

    /** Minutes of random walking and jumping around Perion and nearby maps: never through a platform. */
    @Test
    public void randomPlayInPerionNeverFallsThrough() {
        Wz wz = wz();
        java.util.Random rng = new java.util.Random(83);
        for (int map : new int[]{102000000, 102010000, 102020000, 102030000, 102040000, 100000000}) {
            FootholdTree t = tree(wz, map);
            PhysicsObject p = new PhysicsObject();
            p.setPosition((t.wallLeft + t.wallRight) / 2.0, t.borderTop + 10);
            int dir = 0, landings = 0;
            Foothold fallFrom = null;
            for (int i = 0; i < 60000; i++) {
                if (i % 60 == 0) dir = rng.nextInt(3) - 1;
                p.walkDir = dir;
                if (p.onGround && rng.nextInt(90) == 0) p.jumpRequest = true;
                boolean wasGround = p.onGround;
                double expectBelow = p.onGround ? Double.NaN : groundUnder(t, p);
                t.move(p);
                assertTrue(map + " step " + i + ": fell out of the map at x=" + (int) p.x, p.y < t.borderBottom - 1);
                if (p.onGround) {
                    Foothold fh = t.get(p.fhid);
                    assertFalse(map + ": standing on a wall", fh.isWall());
                    assertEquals(map + " step " + i + ": on the line", fh.groundBelow(p.x), p.y, 0.01);
                    if (!wasGround) landings++;
                } else if (p.vspeed > 0 && !Double.isNaN(expectBelow)) {
                    // still in the air: never below the platform that was under us
                    assertTrue(map + " step " + i + ": passed through a platform at x=" + (int) p.x + " y=" + (int) p.y
                            + " (ground was " + (int) expectBelow + ")", p.y <= expectBelow + 0.01 || groundUnder(t, p) >= p.y);
                }
            }
            assertTrue(map + ": jumped and landed", landings > 20);
        }
    }

    /** The ground right under an airborne object (the first floor at or below it), or +inf. */
    static double groundUnder(FootholdTree t, PhysicsObject p) {
        int id = t.fhBelow(p.x, p.y);
        return id == 0 ? Double.POSITIVE_INFINITY : t.get(id).groundBelow(p.x);
    }
}
