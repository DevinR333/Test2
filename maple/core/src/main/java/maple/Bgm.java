package maple;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import maple.wz.Wz;
import maple.wz.WzNode;

/** Background music from Sound.wz. The MP3 is cached to local storage once, then streamed. */
final class Bgm {
    private Music music;
    private String current = "";
    float volume = 0.6f;

    void play(Wz wz, String path) {
        if (path == null || path.isEmpty() || path.equals(current)) return;
        stop();
        current = path;
        if (Gdx.audio == null) return;
        try {
            int slash = path.indexOf('/');
            if (slash < 0) return;
            WzNode snd = wz.get("Sound/" + path.substring(0, slash) + ".img/" + path.substring(slash + 1));
            if (snd.type != WzNode.Type.SOUND) return;
            WzNode.SoundFile sf = snd.soundFile();
            FileHandle f = Gdx.files.local("bgm/" + path.replace('/', '_').replace(' ', '_') + "." + sf.extension);
            if (!f.exists() || f.length() == 0) f.writeBytes(sf.bytes, false);
            music = Gdx.audio.newMusic(f);
            music.setLooping(true);
            music.setVolume(volume);
            music.play();
        } catch (Throwable t) {
            Log.error("music " + path, t);
            music = null;
        }
    }

    void applyVolume() {
        if (music != null) music.setVolume(volume);
    }

    void pause() { if (music != null) music.pause(); }
    void resume() { if (music != null) music.play(); }

    void stop() {
        if (music != null) {
            music.stop();
            music.dispose();
            music = null;
        }
        current = "";
    }
}
