package maple.wz;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** .wz files in a folder on disk (PC), memory-mapped. File names are matched case-insensitively. */
public final class FolderSource implements Wz.Source {
    private final File dir;

    public FolderSource(File dir) {
        this.dir = dir;
    }

    public File dir() { return dir; }

    @Override
    public ByteBuffer open(String fileName) throws IOException {
        File f = new File(dir, fileName);
        if (!f.isFile()) {
            File[] all = dir.listFiles();
            f = null;
            if (all != null) for (File c : all) if (c.getName().equalsIgnoreCase(fileName)) f = c;
            if (f == null) return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }

    @Override
    public String describe() {
        return dir.getAbsolutePath();
    }
}
