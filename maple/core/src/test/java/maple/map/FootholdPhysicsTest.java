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

    /** Every map in the game: drops land on the first platform, slopes hold you walking and standing. */
    @Test
    public void allTerrainEverywhere() {
        Wz wz = wz();
        int maps = 0, drops = 0, slopes = 0;
        StringBuilder problems = new StringBuilder();
        for (int folder = 0; folder <= 9; folder++) {
            for (maple.wz.WzNode img : wz.get("Map/Map/Map" + folder).children()) {
                maple.wz.WzNode fhNode = img.get("foothold");
                if (!fhNode.exists() || fhNode.childCount() == 0) continue;
                FootholdTree t = new FootholdTree(fhNode);
                if (t.all().size() < 2) continue;
                maps++;
                for (int x = t.wallLeft + 5; x < t.wallRight - 5; x += 41) {
                    double startY = t.borderTop + 10;
                    int expect = t.fhBelow(x, startY);
                    if (expect == 0) continue;
                    PhysicsObject p = new PhysicsObject();
                    p.setPosition(x, startY);
                    p.vspeed = Physics.fallSpeed;
                    for (int i = 0; i < 6000 && !p.onGround; i++) t.move(p);
                    drops++;
                    if (!p.onGround || Math.abs(t.get(expect).groundBelow(x) - p.y) > 1.0) {
                        problems.append(img.name).append(" drop x=").append(x).append('\n');
                    }
                }
                for (Foothold fh : t.all()) {
                    if (fh.isWall() || fh.isFloor() || fh.r() - fh.l() < 20) continue;
                    double ty = Math.abs(fh.slope()) / Math.sqrt(1 + fh.slope() * fh.slope());
                    if (ty > 0.9) continue;
                    PhysicsObject p = new PhysicsObject();
                    double mx = (fh.l() + fh.r()) / 2.0;
                    p.setPosition(mx, fh.groundBelow(mx) - 3);
                    for (int i = 0; i < 20 && !p.onGround; i++) t.move(p);
                    if (!p.onGround || p.fhid != fh.id) continue;
                    double x0 = p.x;
                    for (int i = 0; i < 200; i++) t.move(p); // standing
                    if (!p.onGround || Math.abs(p.x - x0) > 0.001) problems.append(img.name).append(" slid on fh ").append(fh.id).append('\n');
                    for (int dir : new int[]{-1, 1}) {
                        for (int i = 0; i < 400; i++) {
                            p.walkDir = dir;
                            t.move(p);
                            if (!p.onGround) {
                                problems.append(img.name).append(" fell walking fh ").append(fh.id).append('\n');
                                break;
                            }
                            if (p.x <= fh.l() + 2 || p.x >= fh.r() - 2) break;
                        }
                        p.walkDir = 0;
                        p.setPosition(mx, fh.groundBelow(mx) - 3);
                        for (int i = 0; i < 20 && !p.onGround; i++) t.move(p);
                    }
                    slopes++;
                }
            }
        }
        System.out.println("allTerrain: " + maps + " maps, " + drops + " drops, " + slopes + " slopes");
        assertTrue("maps checked: " + maps, maps > 500);
        assertEquals("problems:\n" + (problems.length() > 3000 ? problems.substring(0, 3000) : problems), 0, problems.length());
    }

    /** Random walking and jumping on every 10th map of the game (steps, stairs, slopes, platforms). */
    @Test
    public void randomPlayAcrossTheGame() {
        Wz wz = wz();
        java.util.Random rng = new java.util.Random(7);
        int maps = 0, n = 0;
        StringBuilder problems = new StringBuilder();
        for (int folder = 0; folder <= 9; folder++) {
            for (maple.wz.WzNode img : wz.get("Map/Map/Map" + folder).children()) {
                if (n++ % 10 != 0) continue;
                maple.wz.WzNode fhNode = img.get("foothold");
                if (!fhNode.exists() || fhNode.childCount() == 0) continue;
                FootholdTree t = new FootholdTree(fhNode);
                if (t.all().size() < 2) continue;
                maps++;
                PhysicsObject p = new PhysicsObject();
                p.setPosition(t.wallLeft + rng.nextDouble() * (t.wallRight - t.wallLeft), t.borderTop + 10);
                int dir = 0;
                for (int i = 0; i < 8000; i++) {
                    if (i % 50 == 0) dir = rng.nextInt(3) - 1;
                    p.walkDir = dir;
                    if (p.onGround && rng.nextInt(80) == 0) p.jumpRequest = true;
                    double under = p.onGround ? Double.NaN : groundUnder(t, p);
                    t.move(p);
                    if (p.y >= t.borderBottom - 1) {
                        // falling out is only right when nothing at all was below
                        if (!Double.isInfinite(under)) problems.append(img.name).append(" fell out at x=").append((int) p.x).append('\n');
                        p.setPosition(t.wallLeft + rng.nextDouble() * (t.wallRight - t.wallLeft), t.borderTop + 10);
                        continue;
                    }
                    if (p.onGround) {
                        Foothold fh = t.get(p.fhid);
                        if (fh.isWall() || Math.abs(fh.groundBelow(p.x) - p.y) > 0.01) problems.append(img.name).append(" off the line\n");
                    } else if (p.vspeed > 0 && !Double.isNaN(under) && p.y > under + 0.01 && groundUnder(t, p) < p.y) {
                        problems.append(img.name).append(" passed through at x=").append((int) p.x).append('\n');
                    }
                }
            }
        }
        System.out.println("randomPlay: " + maps + " maps");
        assertTrue(maps > 200);
        assertEquals("problems:\n" + (problems.length() > 3000 ? problems.substring(0, 3000) : problems), 0, problems.length());
    }
}
