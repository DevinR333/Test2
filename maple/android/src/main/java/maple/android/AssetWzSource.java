package maple.android;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import maple.wz.Wz;

/** Reads the .wz files packed (uncompressed) inside the APK by memory-mapping them in place. */
final class AssetWzSource implements Wz.Source {
    private final AssetManager assets;

    AssetWzSource(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public ByteBuffer open(String fileName) throws IOException {
        AssetFileDescriptor fd;
        try {
            fd = assets.openFd("wz/" + fileName);
        } catch (FileNotFoundException e) {
            String[] names = assets.list("wz");
            if (names != null) for (String n : names) {
                if (n.equals(fileName)) throw new IOException(fileName + " is stored compressed in the APK (rebuild it)", e);
            }
            return null;
        }
        try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel ch = in.getChannel();
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getLength());
        } finally {
            fd.close();
        }
    }

    @Override
    public String describe() {
        return "APK assets/wz";
    }
}
