package offline;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * The server also runs on Android (API 26+), whose java.* lacks some newer JDK methods. A call to one
 * compiles and passes every desktop test, then throws NoSuchMethodError on the phone (this is what left
 * login stuck on "Logging in..."). This scans the compiled classes for the ones known to be missing.
 */
public class AndroidApiTest {
    /** owner.name, or owner.name(desc prefix) when only some overloads are missing. */
    private static final List<String> MISSING_ON_ANDROID = Arrays.asList(
            "java/sql/Timestamp.valueOf(Ljava/time/", "java/sql/Timestamp.from", "java/sql/Timestamp.toLocalDateTime",
            "java/sql/Timestamp.toInstant", "java/sql/Date.valueOf(Ljava/time/", "java/sql/Date.toLocalDate",
            "java/util/HexFormat.", "java/nio/file/Path.of", "java/util/Optional.isEmpty", "java/util/Optional.orElseThrow()",
            "java/util/Set.of", "java/util/List.of", "java/util/Map.of", "java/util/Map.entry", "java/util/Map.copyOf",
            "java/util/List.copyOf", "java/util/Set.copyOf", "java/util/function/Predicate.not",
            "java/lang/String.isBlank", "java/lang/String.strip", "java/lang/String.repeat", "java/lang/String.lines",
            "java/lang/String.formatted", "java/util/stream/Stream.toList", "java/nio/file/Files.readString",
            "java/nio/file/Files.writeString", "java/io/InputStream.transferTo", "java/io/InputStream.readAllBytes");

    @Test
    public void serverUsesOnlyApisAndroidHas() throws IOException {
        List<String> found = new ArrayList<>();
        scan(new File("build/classes/java/main"), found);
        assertTrue("Methods missing on Android:\n" + String.join("\n", found), found.isEmpty());
    }

    /** The client (core) and the WZ reader run on the phone too. */
    @Test
    public void clientUsesOnlyApisAndroidHas() throws IOException {
        List<String> found = new ArrayList<>();
        for (String dir : new String[]{"../core/build/classes/java/main", "../wz/build/classes/java/main"}) {
            File d = new File(dir);
            assertTrue("compiled classes missing: " + d.getAbsolutePath(), d.isDirectory());
            scan(d, found);
        }
        assertTrue("Methods missing on Android:\n" + String.join("\n", found), found.isEmpty());
    }

    private static void scan(File f, List<String> found) throws IOException {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) scan(k, found);
        } else if (f.getName().endsWith(".class")) {
            for (String ref : methodRefs(f)) {
                for (String bad : MISSING_ON_ANDROID) {
                    if (ref.startsWith(bad.contains("(") || bad.endsWith(".") ? bad : bad + "(")) found.add(f.getName() + " -> " + ref);
                }
            }
        }
    }

    /** "owner.name(desc)" for every method a class file references. */
    static List<String> methodRefs(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int n = in.readUnsignedShort();
            Object[] cp = new Object[n];
            int[][] refs = new int[n][];
            for (int i = 1; i < n; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: cp[i] = in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: case 8: case 16: case 19: case 20: cp[i] = new int[]{in.readUnsignedShort()}; break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        refs[i] = new int[]{tag, in.readUnsignedShort(), in.readUnsignedShort()};
                        break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new IOException("bad constant pool tag " + tag + " in " + file);
                }
            }
            List<String> out = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                if (refs[i] == null || (refs[i][0] != 10 && refs[i][0] != 11)) continue;
                String owner = (String) cp[((int[]) cp[refs[i][1]])[0]];
                int[] nat = refs[i][2] < n ? refs[refs[i][2]] : null;
                if (nat == null) continue;
                out.add(owner + "." + cp[nat[1]] + cp[nat[2]]);
            }
            return out;
        }
    }
}
