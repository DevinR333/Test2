package maple.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

import maple.MapleGame;
import maple.SaveTransfer;

public class AndroidLauncher extends AndroidApplication {
    private static final int EXPORT = 41, IMPORT = 42;
    private File exportSrc;
    private Consumer<String> exportDone;
    private Consumer<InputStream> importPicked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
        cfg.useImmersiveMode = true;
        cfg.useAccelerometer = false;
        cfg.useCompass = false;
        cfg.useGyroscope = false;
        cfg.numSamples = 0;
        SaveTransfer.platform = new FilePicker();
        File save = new File(getFilesDir(), "save");
        initialize(new MapleGame(new AssetWzSource(getAssets()), save, new AssetScriptLoader(getAssets())), cfg);
    }

    /** Save export/import through the system file picker (any folder, Drive, a USB stick...). */
    private final class FilePicker implements SaveTransfer.Platform {
        @Override
        public void exportFile(File src, String suggestedName, Consumer<String> done) {
            runOnUiThread(() -> {
                exportSrc = src;
                exportDone = done;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE, suggestedName);
                try {
                    startActivityForResult(i, EXPORT);
                } catch (RuntimeException e) {
                    done.accept("No file picker is available on this device.");
                }
            });
        }

        @Override
        public void pickFile(Consumer<InputStream> picked) {
            runOnUiThread(() -> {
                importPicked = picked;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, IMPORT);
                } catch (RuntimeException e) {
                    picked.accept(null);
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == EXPORT && exportDone != null) {
            final File src = exportSrc;
            final Consumer<String> done = exportDone;
            exportSrc = null;
            exportDone = null;
            if (uri == null) {
                src.delete();
                done.accept("Export cancelled.");
                return;
            }
            new Thread(() -> {
                try (InputStream in = new FileInputStream(src); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    done.accept("Save exported.");
                } catch (Exception e) {
                    done.accept("The save could not be exported: " + e.getMessage());
                } finally {
                    src.delete();
                }
            }, "export").start();
        } else if (requestCode == IMPORT && importPicked != null) {
            final Consumer<InputStream> picked = importPicked;
            importPicked = null;
            new Thread(() -> {
                try {
                    picked.accept(uri == null ? null : getContentResolver().openInputStream(uri));
                } catch (Exception e) {
                    picked.accept(null);
                }
            }, "import").start();
        }
    }
}
