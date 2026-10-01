package maple.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Short sound effects from Sound.wz (UI.img button clicks, Game.img pickups, level up, mob hits...).
 * Each is written to local storage once, then loaded as a libGDX Sound.
 */
public final class UiSounds {
    private static Wz wz;
    private static final Map<String, Sound> loaded = new HashMap<>();
    private static final Map<String, Boolean> missing = new HashMap<>();
    public static float volume = 0.7f;

    private UiSounds() {}

    public static void init(Wz w) {
        wz = w;
    }

    /** A UI.img sound, e.g. "BtMouseClick". */
    public static void play(String uiSound) {
        playPath("UI.img/" + uiSound);
    }

    /** A Game.img sound, e.g. "PickUpItem". */
    public static void game(String name) {
        playPath("Game.img/" + name);
    }

    /** Any Sound.wz path, e.g. "Mob.img/0100100/Damage". */
    public static synchronized void playPath(String path) {
        if (wz == null || Gdx.audio == null || volume <= 0) return;
        if (missing.containsKey(path)) return;
        Sound s = loaded.get(path);
        if (s == null) {
            try {
                WzNode n = wz.get("Sound/" + path);
                if (n.type != WzNode.Type.SOUND) {
                    missing.put(path, true);
                    return;
                }
                WzNode.SoundFile sf = n.soundFile();
                FileHandle f = Gdx.files.local("sfx/" + path.replace('/', '_').replace(' ', '_') + "." + sf.extension);
                if (!f.exists() || f.length() != sf.bytes.length) f.writeBytes(sf.bytes, false);
                s = Gdx.audio.newSound(f);
                loaded.put(path, s);
            } catch (Throwable t) {
                missing.put(path, true);
                return;
            }
        }
        try {
            s.play(volume);
        } catch (Throwable ignored) {
            // a sound must never stop the game
        }
    }

    public static synchronized void dispose() {
        for (Sound s : loaded.values()) s.dispose();
        loaded.clear();
    }
}
