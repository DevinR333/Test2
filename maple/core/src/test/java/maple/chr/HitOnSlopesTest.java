package maple.chr;

import maple.input.Pad;
import maple.map.Field;
import maple.map.Foothold;
import maple.map.FootholdTree;
import maple.wz.FolderSource;
import maple.wz.Wz;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/** The real character code: monsters hitting you on slopes (standing, walking, in the air) never drop you through. */
public class HitOnSlopesTest {
    @Test
    public void hitsOnPerionSlopesNeverDropYouThrough() {
        String bin = System.getenv("MAPLE_BIN_WZ");
        Assume.assumeTrue("needs MAPLE_BIN_WZ", bin != null && !bin.isEmpty());
        new com.badlogic.gdx.backends.headless.HeadlessApplication(new com.badlogic.gdx.ApplicationAdapter() {});
        com.badlogic.gdx.Gdx.gl = com.badlogic.gdx.Gdx.gl20 = (com.badlogic.gdx.graphics.GL20) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.badlogic.gdx.graphics.GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        Wz wz = new Wz(new FolderSource(new File(bin)));
        maple.map.Physics.load(wz.get("Map/Physics.img"));
        StringBuilder problems = new StringBuilder();
        int cases = 0;
        java.util.Random rng = new java.util.Random(1);
        for (int map : new int[]{101040000, 102000000, 102010000, 102020000, 102030000, 102040000, 102050000, 101030000, 101030100}) {
            Field f = new Field(wz, map);
            FootholdTree t = f.footholds;
            for (Foothold fh : t.all()) {
                if (fh.isWall() || fh.r() - fh.l() < 20) continue;
                double mx = (fh.l() + fh.r()) / 2.0;
                if (mx < t.wallLeft + 40 || mx > t.wallRight - 40) continue;
                for (int scenario = 0; scenario < 3; scenario++) {
                    Player p = new Player();
                    p.spawn(mx, fh.groundBelow(mx) - 3);
                    Pad pad = new Pad();
                    for (int i = 0; i < 30; i++) p.update(f, pad);
                    if (!p.phys.onGround || p.phys.fhid != fh.id) continue;
                    cases++;
                    for (int i = 0; i < 1500; i++) {
                        pad.left = scenario == 1 && (i / 120) % 2 == 0;
                        pad.right = scenario == 1 && (i / 120) % 2 == 1;
                        // hit every 0.25-0.5 s from a random side (scenario 2: also while in the air)
                        if (i % (30 + rng.nextInt(30)) == 0 && (scenario == 2 || p.phys.onGround)) p.knockback(rng.nextBoolean());
                        double x0 = p.phys.x, y0 = p.phys.y;
                        boolean wasGround = p.phys.onGround;
                        p.update(f, pad);
                        if (p.state == Player.State.LADDER || p.state == Player.State.ROPE) break;
                        Foothold crossed = maple.map.FootholdPhysicsTest.crossedFloor(t, x0, y0, p.phys.x, p.phys.y);
                        // walking from one joined segment onto the next is not going through
                        if (crossed != null && !(wasGround && p.phys.onGround)
                                && !(p.phys.onGround && p.phys.y <= crossed.groundBelow(p.phys.x) + 0.01)) {
                            problems.append(map).append(" fh ").append(fh.id).append(" scenario ").append(scenario)
                                    .append(": through fh ").append(crossed.id).append(String.format(" (%d,%d)-(%d,%d)", crossed.x1, crossed.y1, crossed.x2, crossed.y2))
                                    .append(String.format(" step (%.2f,%.3f)->(%.2f,%.3f) wasGround=%b now on=%b fh=%d state=%s hs=%.2f vs=%.2f",
                                            x0, y0, p.phys.x, p.phys.y, wasGround, p.phys.onGround, p.phys.fhid, p.state, p.phys.hspeed, p.phys.vspeed)).append('\n');
                            break;
                        }
                        if (p.phys.onGround && Math.abs(t.get(p.phys.fhid).groundBelow(p.phys.x) - p.phys.y) > 0.01) {
                            problems.append(map).append(" fh ").append(fh.id).append(": off the floor line\n");
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("hits on slopes: " + cases + " cases");
        assertTrue(cases > 100);
        assertEquals("problems:\n" + (problems.length() > 2000 ? problems.substring(0, 2000) : problems), 0, problems.length());
    }

    static double groundUnder(FootholdTree t, double x, double y) {
        int id = t.fhBelow(x, y);
        return id == 0 ? Double.POSITIVE_INFINITY : t.get(id).groundBelow(x);
    }
}
