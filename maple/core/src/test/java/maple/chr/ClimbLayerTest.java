package maple.chr;

import maple.input.Pad;
import maple.map.Field;
import maple.map.Ladder;
import maple.wz.FolderSource;
import maple.wz.Wz;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/** A climbing character is drawn on the rope's or ladder's layer, in front of it (not behind). */
public class ClimbLayerTest {
    @Test
    public void climbingUsesTheLaddersLayer() {
        String bin = System.getenv("MAPLE_BIN_WZ");
        Assume.assumeTrue("needs MAPLE_BIN_WZ", bin != null && !bin.isEmpty());
        new com.badlogic.gdx.backends.headless.HeadlessApplication(new com.badlogic.gdx.ApplicationAdapter() {});
        com.badlogic.gdx.graphics.GL20 gl = (com.badlogic.gdx.graphics.GL20) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{com.badlogic.gdx.graphics.GL20.class},
                (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
        com.badlogic.gdx.Gdx.gl = com.badlogic.gdx.Gdx.gl20 = gl;
        Wz wz = new Wz(new FolderSource(new File(bin)));
        Field f = new Field(wz, 100000000); // Henesys: ladders on layer 2
        Ladder lad = null;
        for (Ladder l : f.ladders) if (l.page == 2) lad = l;
        assertNotNull("a layer-2 ladder", lad);
        Player p = new Player();
        p.spawn(lad.x, lad.y2 - 1);
        Pad up = new Pad();
        up.up = true;
        for (int i = 0; i < 60 && p.state != Player.State.LADDER && p.state != Player.State.ROPE; i++) p.update(f, up);
        assertTrue("climbing (" + p.state + ")", p.state == Player.State.LADDER || p.state == Player.State.ROPE);
        assertNotEquals("(the ground here is on another layer, so this checks something)", lad.page, p.phys.fhlayer);
        assertEquals("drawn on the ladder's layer", lad.page, p.layer());
    }
}
