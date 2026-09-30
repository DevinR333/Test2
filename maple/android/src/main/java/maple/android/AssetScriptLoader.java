package maple.android;

import android.content.res.AssetManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import scripting.AbstractScriptManager;

/** NPC/quest/portal/event scripts packed in the APK under assets/scripts. */
final class AssetScriptLoader implements AbstractScriptManager.ScriptLoader {
    private final AssetManager assets;

    AssetScriptLoader(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public Reader open(String path) throws IOException {
        try {
            return new InputStreamReader(assets.open("scripts/" + path), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String[] list(String dir) throws IOException {
        String[] names = assets.list("scripts/" + dir);
        return names == null ? new String[0] : names;
    }
}
