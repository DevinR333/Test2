package maple;

import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.ApplicationAdapter;
import maple.map.Field;
import maple.map.FootholdTree;
import maple.map.PhysicsObject;
import maple.wz.FolderSource;
import maple.wz.Wz;
import maple.wz.WzFile;
import maple.wz.WzNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class WzReaderTest {
    static File dir = new File("build/synthetic-wz");
    static Wz wz;

    @BeforeClass
    public static void setup() throws Exception {
        SyntheticData.write(dir);
        wz = new Wz(new FolderSource(dir));
    }

    @Test
    public void readsHeaderVersionAndKey() {
        WzFile f = wz.file("Map");
        assertEquals(83, f.version);
        assertEquals("GMS", f.keyName());
        assertTrue(f.root.get("Map").exists());
    }

    @Test
    public void readsProperties() {
        WzNode info = wz.get("Map/Map/Map1/100000000.img/info");
        assertEquals("Bgm00/Nothing", info.getString("bgm", ""));
        assertEquals(-900, info.getInt("VRLeft", 0));
        WzNode canvas = wz.get("Map/Tile/grass.img/bsc/0");
        assertTrue(canvas.isCanvas());
        assertEquals(90, canvas.width());
        assertEquals(2, canvas.getInt("z", 0));
        byte[] px = canvas.rgba();
        assertEquals(90 * 60 * 4, px.length);
        // inner pixel colour 0xFF8B5A2B -> RGBA
        int i = (30 * 90 + 45) * 4;
        assertEquals(0x8B, px[i] & 0xFF);
        assertEquals(0x5A, px[i + 1] & 0xFF);
        assertEquals(0x2B, px[i + 2] & 0xFF);
        assertEquals(0xFF, px[i + 3] & 0xFF);
    }

    @Test
    public void followsLinks() {
        WzNode head = wz.get("Character/00012000.img/walk1/2/head");
        assertTrue(head.isCanvas());
        assertEquals(-18, head.get("map").get("brow").vy());
        assertEquals("Test Town", wz.get("String/Map.img/victoria/100000000").getString("mapName", ""));
    }

    @Test
    public void physicsLandsWalksAndClimbsSlope() {
        FootholdTree fht = new FootholdTree(wz.get("Map/Map/Map1/100000000.img/foothold"));
        PhysicsObject p = new PhysicsObject();
        p.setPosition(-200, 100);
        for (int i = 0; i < 200; i++) fht.move(p);
        assertTrue(p.onGround);
        assertEquals(200, p.y, 0.001);
        // walk right up the slope onto the y=150 floor
        for (int i = 0; i < 2000; i++) {
            p.hforce = 0.16;
            fht.move(p);
        }
        assertTrue("x=" + p.x, p.x > 600);
        assertEquals(150, p.y, 1);
        // the wall at x=900 stops us
        assertTrue(p.x <= 900);
        // jump
        p.vforce = -4.5;
        fht.move(p);
        fht.move(p);
        assertFalse(p.onGround);
        for (int i = 0; i < 300; i++) fht.move(p);
        assertTrue(p.onGround);
    }

    @Test
    public void loadsFieldHeadless() {
        final Field[] out = new Field[1];
        final Throwable[] err = new Throwable[1];
        HeadlessApplication app = new HeadlessApplication(new ApplicationAdapter() {
            @Override
            public void create() {
                // No GPU in tests: a GL that ignores every call.
                com.badlogic.gdx.graphics.GL20 gl = (com.badlogic.gdx.graphics.GL20) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{com.badlogic.gdx.graphics.GL20.class},
                        (proxy, m, a) -> m.getReturnType() == int.class ? 1 : m.getReturnType() == boolean.class ? false : null);
                com.badlogic.gdx.Gdx.gl = com.badlogic.gdx.Gdx.gl20 = gl;
                try {
                    out[0] = new Field(wz, 100000000);
                } catch (Throwable t) {
                    err[0] = t;
                }
            }
        });
        long end = System.currentTimeMillis() + 10000;
        while (out[0] == null && err[0] == null && System.currentTimeMillis() < end) Thread.yield();
        app.exit();
        if (err[0] != null) throw new AssertionError(err[0]);
        Field f = out[0];
        assertNotNull(f);
        assertEquals("Test Town", f.mapName);
        assertEquals(3, f.portals.size());
        assertEquals(1, f.ladders.size());
        assertEquals(2, f.life.size());
        assertTrue(f.tiles > 40);
        assertEquals(3, f.objects);
    }
}
