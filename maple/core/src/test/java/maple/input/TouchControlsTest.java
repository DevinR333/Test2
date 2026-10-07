package maple.input;

import com.badlogic.gdx.Preferences;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** The touch editor, driven with taps and drags as a finger would: move, resize, fade, rebind, delete, add, reset, save. */
public class TouchControlsTest {
    static Preferences prefs(Map<String, Object> store) {
        return (Preferences) java.lang.reflect.Proxy.newProxyInstance(TouchControlsTest.class.getClassLoader(), new Class<?>[]{Preferences.class},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "putString": store.put((String) a[0], a[1]); return proxy;
                        case "getString": return store.getOrDefault((String) a[0], a.length > 1 ? a[1] : "");
                        case "flush": return null;
                        default: return m.getReturnType() == boolean.class ? false : null;
                    }
                });
    }

    static void tap(TouchControls t, float[] p) {
        assertNotNull("target exists", p);
        t.touchDown(0, p[0], p[1]);
        t.touchUp(0, p[0], p[1]);
    }

    static TouchControls.Item withSlot(TouchControls t, int slot) {
        for (TouchControls.Item it : t.items) if (!it.stick && it.slot == slot) return it;
        return null;
    }

    static TouchControls.Item stick(TouchControls t) {
        for (TouchControls.Item it : t.items) if (it.stick) return it;
        return null;
    }

    static TouchControls fresh(Map<String, Object> store, float width) {
        TouchControls t = new TouchControls();
        t.load(prefs(store));
        t.layout(0, 0, width, 600);
        return t;
    }

    @Test
    public void editEverything() {
        Map<String, Object> store = new HashMap<>();
        TouchControls t = fresh(store, 1067); // 16:9
        int count = t.items.size();
        t.editing = true;

        // move: drag Ctrl to the left half of the screen
        TouchControls.Item ctrl = withSlot(t, 29);
        float[] c = t.center(ctrl);
        t.touchDown(0, c[0], c[1]);
        t.touchDragged(0, 300, 200);
        t.touchUp(0, 300, 200);
        float[] moved = t.center(ctrl);
        assertEquals(300, moved[0], 0.5);
        assertEquals(200, moved[1], 0.5);
        assertFalse("now anchored to the left side", ctrl.right);

        // resize and fade (Ctrl is still selected)
        assertSame(ctrl, t.selectedItem());
        float size = ctrl.size;
        tap(t, t.toolCenter("Bigger"));
        assertTrue("bigger", ctrl.size > size);
        tap(t, t.toolCenter("Smaller"));
        tap(t, t.toolCenter("Smaller"));
        assertTrue("smaller", ctrl.size < size);
        assertNull("no Fade button any more", t.toolCenter("Fade"));
        float[] half = t.sliderPoint(0.5f);
        t.touchDown(0, half[0], half[1]);
        t.touchUp(0, half[0], half[1]);
        assertEquals("slider: half", 0.5f, ctrl.alpha, 0.03f);
        float[] zero = t.sliderPoint(0f);
        t.touchDown(0, half[0], half[1]);
        t.touchDragged(0, zero[0] - 30, zero[1]);
        t.touchUp(0, zero[0] - 30, zero[1]);
        assertEquals("slider dragged to fully invisible", 0f, ctrl.alpha, 0.001f);

        // rebind to L-Click through the key picker
        tap(t, t.toolCenter("Key"));
        assertTrue(t.pickerOpen());
        tap(t, t.pickerCenter(TouchControls.LEFT_CLICK));
        assertEquals(TouchControls.LEFT_CLICK, ctrl.slot);

        // delete it
        tap(t, t.center(ctrl));
        assertSame(ctrl, t.selectedItem());
        tap(t, t.toolCenter("Delete"));
        assertEquals(count - 1, t.items.size());
        assertFalse(t.items.contains(ctrl));

        // add a new button bound to Wheel Down
        tap(t, new float[]{600, 570}); // empty space: deselect
        assertNull(t.selectedItem());
        tap(t, t.toolCenter("Add button"));
        assertTrue(t.pickerOpen());
        tap(t, t.pickerCenter(TouchControls.WHEEL_DOWN));
        assertEquals(count, t.items.size());
        assertEquals(TouchControls.WHEEL_DOWN, t.selectedItem().slot);

        // the stick can go and come back
        TouchControls.Item s = stick(t);
        tap(t, new float[]{600, 570});
        tap(t, t.center(s));
        tap(t, t.toolCenter("Delete"));
        assertNull("stick removed", stick(t));
        tap(t, new float[]{600, 570});
        tap(t, t.toolCenter("Add stick"));
        assertNotNull("stick back", stick(t));

        // Done saves; a reload gets the same layout
        tap(t, new float[]{600, 570});
        tap(t, t.toolCenter("Done"));
        assertFalse(t.editing);
        TouchControls again = fresh(store, 1067);
        assertEquals(t.items.size(), again.items.size());
        assertNotNull(withSlot(again, TouchControls.WHEEL_DOWN));
        assertNull("deleted button stays deleted", withSlot(again, TouchControls.LEFT_CLICK));

        // Reset brings the defaults back
        again.editing = true;
        tap(again, again.toolCenter("Reset"));
        assertNotNull(withSlot(again, 29));
        assertNull(withSlot(again, TouchControls.WHEEL_DOWN));
    }

    @Test
    public void sameShapeOn4by3AndWide() {
        Map<String, Object> store = new HashMap<>();
        TouchControls wide = fresh(store, 1067), narrow = fresh(store, 800);
        for (int i = 0; i < wide.items.size(); i++) {
            TouchControls.Item a = wide.items.get(i), b = narrow.items.get(i);
            assertEquals("same size", wide.radius(a), narrow.radius(b), 0.01);
            float da = a.right ? 1067 - wide.center(a)[0] : wide.center(a)[0];
            float db = b.right ? 800 - narrow.center(b)[0] : narrow.center(b)[0];
            assertEquals("same distance from its side", da, db, 0.01);
        }
        // nothing overlaps on the narrow screen
        for (TouchControls.Item a : narrow.items) {
            for (TouchControls.Item b : narrow.items) {
                if (a == b) continue;
                float[] pa = narrow.center(a), pb = narrow.center(b);
                double d = Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                assertTrue("controls overlap on 4:3", d >= narrow.radius(a) + narrow.radius(b) - 1);
            }
        }
    }

    @Test
    public void mouseButtonsFireTheirAction() {
        TouchControls t = fresh(new HashMap<>(), 800);
        t.editing = true;
        tap(t, t.toolCenter("Add button"));
        tap(t, t.pickerCenter(TouchControls.RIGHT_CLICK));
        TouchControls.Item it = t.selectedItem();
        tap(t, new float[]{400, 300});
        tap(t, t.toolCenter("Done"));
        int[] fired = {0};
        t.mouseListener = slot -> fired[0] = slot;
        float[] c = t.center(it);
        assertTrue("the button takes the touch", t.touchDown(0, c[0], c[1]));
        t.touchUp(0, c[0], c[1]);
        assertEquals(TouchControls.RIGHT_CLICK, fired[0]);
        assertFalse("a mouse button is not a key", t.held(TouchControls.RIGHT_CLICK));
    }

    @Test
    public void invisibleControlsStillWorkAndDiagonalsMoveYou() {
        Map<String, Object> store = new HashMap<>();
        TouchControls t = fresh(store, 1067);
        t.editing = true;
        // nothing selected: the slider sets every control, down to invisible
        float[] zero = t.sliderPoint(0f);
        t.touchDown(0, zero[0], zero[1]);
        t.touchUp(0, zero[0], zero[1]);
        for (TouchControls.Item it : t.items) assertEquals("all invisible", 0f, it.alpha, 0.001f);
        tap(t, t.toolCenter("Done"));
        assertFalse(t.editing);
        // an invisible button still presses its key
        TouchControls.Item jump = null;
        for (TouchControls.Item it : t.items) if (!it.stick && it.slot >= 0 && it.slot < 90) { jump = it; break; }
        assertNotNull(jump);
        float[] c = t.center(jump);
        assertTrue("touch taken", t.touchDown(1, c[0], c[1]));
        assertTrue("key held", t.held(jump.slot));
        t.touchUp(1, c[0], c[1]);
        // the stick pushed up and to the right (about 60 degrees up) still moves right
        TouchControls.Item s = stick(t);
        float[] sc = t.center(s);
        float r = t.radius(s);
        t.touchDown(2, sc[0], sc[1]);
        t.touchDragged(2, sc[0] + r * 0.45f, sc[1] - r * 0.8f);
        assertTrue("right while pushing up", t.right());
        assertTrue("and up", t.up());
        t.touchUp(2, sc[0], sc[1]);
        // and it survives a restart
        TouchControls again = fresh(store, 1067);
        for (TouchControls.Item it : again.items) assertEquals("saved", 0f, it.alpha, 0.001f);
    }
}
