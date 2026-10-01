package maple;

import maple.WzWriter.Canvas;
import maple.WzWriter.Dir;
import maple.WzWriter.Prop;
import maple.WzWriter.Uol;
import maple.WzWriter.Vec;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test tool: rebuilds UI.wz (and other dumped images) from a wz-structure.txt dump of the real game
 * files. Every picture keeps its real size and origin and is filled with a translucent colour and a
 * border, so screenshots show exactly where each piece of the interface lands.
 *
 * Usage: DumpToWz dump.txt outDir [baseWzDir]
 * Copies (or links) the other .wz files from baseWzDir and writes UI.wz; images from other archives
 * in the dump (Map/Obj/login.img, Map/Back/login.img...) are merged into a fresh Map.wz built from
 * the XML export when MAPLE_XML_WZ is set.
 */
public final class DumpToWz {
    private static final Pattern CANVAS = Pattern.compile("^(\\S.*?)\\s+\\[img (\\d+)x(\\d+)(?: origin (-?\\d+),(-?\\d+))?\\]$");
    private static final Pattern PROP = Pattern.compile("^(\\S.*?) \\[(PROP|NULL|CONVEX|EXTENDED)\\]$");
    private static final Pattern UOL = Pattern.compile("^(\\S.*?) -> (.*)$");
    private static final Pattern VALUE = Pattern.compile("^([^=\\s]+)=(.*)$");
    private static final Pattern SOUND = Pattern.compile("^(\\S.*?)\\s+\\[sound.*\\]$");

    public static Map<String, Prop> parse(File dump) throws Exception {
        Map<String, Prop> images = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(dump), StandardCharsets.UTF_8))) {
            String line;
            Prop img = null;
            String imgPath = null;
            Deque<Object> stack = new ArrayDeque<>(); // Prop or Canvas, by depth
            Deque<Integer> depths = new ArrayDeque<>();
            while ((line = r.readLine()) != null) {
                if (line.startsWith("=== ")) {
                    String name = line.substring(4).trim();
                    if (name.contains("(not found)")) { img = null; continue; }
                    img = new Prop();
                    imgPath = name;
                    images.put(imgPath, img);
                    stack.clear();
                    depths.clear();
                    continue;
                }
                if (img == null || line.trim().isEmpty() || line.startsWith("END")) continue;
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                int depth = indent / 2;
                String t = line.trim();
                while (!depths.isEmpty() && depths.peek() >= depth) {
                    depths.pop();
                    stack.pop();
                }
                Object parentObj = stack.isEmpty() ? img : stack.peek();
                Prop parent = parentObj instanceof Canvas ? ((Canvas) parentObj).props : (Prop) parentObj;
                Matcher m;
                if ((m = CANVAS.matcher(t)).matches()) {
                    int w = Integer.parseInt(m.group(2)), h = Integer.parseInt(m.group(3));
                    int ox = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
                    int oy = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
                    String key = imgPath + "/" + m.group(1);
                    Canvas c = parent.canvas(m.group(1), Math.max(1, w), Math.max(1, h), color(key + w + h), ox, oy);
                    stack.push(c);
                    depths.push(depth);
                } else if ((m = PROP.matcher(t)).matches()) {
                    if (m.group(2).equals("NULL")) {
                        parent.set(m.group(1), null);
                    } else {
                        Prop p = parent.sub(m.group(1));
                        stack.push(p);
                        depths.push(depth);
                    }
                } else if ((m = UOL.matcher(t)).matches()) {
                    parent.set(m.group(1), new Uol(m.group(2).trim()));
                } else if ((m = SOUND.matcher(t)).matches()) {
                    parent.set(m.group(1), new WzWriter.Sound(new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0}));
                } else if ((m = VALUE.matcher(t)).matches()) {
                    String k = m.group(1), v = m.group(2).trim();
                    if (k.equals("origin") && parentObj instanceof Canvas) continue; // already set
                    parent.set(k, value(v));
                }
            }
        }
        return images;
    }

    private static Object value(String v) {
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) return v.substring(1, v.length() - 1);
        if (v.startsWith("(") && v.endsWith(")")) {
            String[] p = v.substring(1, v.length() - 1).split(",");
            return new Vec(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        }
        try {
            long l = Long.parseLong(v);
            return (int) l;
        } catch (NumberFormatException e) {
            // not an integer
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** A stable, clearly visible translucent colour per picture. */
    static int color(String key) {
        int h = key.hashCode() * 0x9E3779B1;
        int r = 80 + ((h >>> 16) & 0x7F), g = 80 + ((h >>> 8) & 0x7F), b = 80 + (h & 0x7F);
        return 0x90000000 | r << 16 | g << 8 | b;
    }

    public static void main(String[] args) throws Exception {
        File dump = new File(args[0]), out = new File(args[1]);
        File base = args.length > 2 ? new File(args[2]) : null;
        out.mkdirs();
        Map<String, Prop> images = parse(dump);
        Map<String, Dir> archives = new LinkedHashMap<>();
        for (Map.Entry<String, Prop> e : images.entrySet()) {
            String path = e.getKey(); // "UI/StatusBar.img", "Map/Obj/login.img"
            int slash = path.indexOf('/');
            String archive = path.substring(0, slash);
            Dir d = archives.computeIfAbsent(archive, k -> new Dir());
            String[] parts = path.substring(slash + 1).split("/");
            for (int i = 0; i < parts.length - 1; i++) d = d.dir(parts[i]);
            final Prop p = e.getValue();
            d.lazyImg(parts[parts.length - 1], () -> p);
        }
        // UI.wz entirely from the dump.
        new WzWriter().write(archives.get("UI"), new File(out, "UI.wz"));
        System.out.println("UI.wz written");
        // Map.wz: XML export plus the dumped login scenery (coloured).
        String xml = System.getenv("MAPLE_XML_WZ");
        if (xml != null && !xml.isEmpty() && archives.containsKey("Map")) {
            Dir root = new Dir();
            XmlToWz.fillDir(new File(xml, "Map.wz"), root);
            mergeInto(root, archives.get("Map"));
            new WzWriter().write(root, new File(out, "Map.wz"));
            System.out.println("Map.wz written");
        }
        if (base != null) {
            for (File f : base.listFiles()) {
                File target = new File(out, f.getName());
                if (!f.getName().endsWith(".wz") || target.exists()) continue;
                java.nio.file.Files.createSymbolicLink(target.toPath(), f.getAbsoluteFile().toPath());
            }
        }
    }

    private static void mergeInto(Dir target, Dir extra) {
        for (Map.Entry<String, Object> e : extra.entries.entrySet()) {
            if (e.getValue() instanceof Dir) mergeInto(target.dir(e.getKey()), (Dir) e.getValue());
            else target.entries.put(e.getKey(), e.getValue());
        }
    }
}
