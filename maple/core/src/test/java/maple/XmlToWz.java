package maple;

import maple.WzWriter.Canvas;
import maple.WzWriter.Dir;
import maple.WzWriter.Prop;
import maple.WzWriter.Uol;
import maple.WzWriter.Vec;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

/**
 * Test tool: converts an XML export of the game data into binary .wz files (same structure and values,
 * blank pictures), so the server and client can be tested on real data structures.
 */
public final class XmlToWz {
    public static void main(String[] args) throws Exception {
        File in = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        String[] files = args.length > 2 ? args[2].split(",") : new String[0];
        for (File wz : in.listFiles()) {
            if (!wz.getName().endsWith(".wz")) continue;
            String base = wz.getName().substring(0, wz.getName().length() - 3);
            if (files.length > 0 && !java.util.Arrays.asList(files).contains(base)) continue;
            long t0 = System.currentTimeMillis();
            Dir root = new Dir();
            fill(wz, root);
            new WzWriter().write(root, new File(out, wz.getName()));
            System.out.println(wz.getName() + " -> " + new File(out, wz.getName()).length() / 1024 + " KB in " + (System.currentTimeMillis() - t0) + " ms");
        }
    }

    private static void fill(File dir, Dir d) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File f : kids) {
            if (f.isDirectory()) fill(f, d.dir(f.getName()));
            else if (f.getName().endsWith(".img.xml")) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                d.lazyImg(name, () -> parse(f));
            }
        }
    }

    static Prop parse(File f) {
        try {
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getDocumentElement();
            Prop p = new Prop();
            children(root, p);
            return p;
        } catch (Exception e) {
            throw new RuntimeException(f.getPath(), e);
        }
    }

    private static void children(Element e, Prop p) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            String name = c.getAttribute("name");
            String v = c.getAttribute("value");
            switch (c.getTagName()) {
                case "imgdir": children(c, p.sub(name)); break;
                case "int": p.set(name, (int) Long.parseLong(v)); break;
                case "short": p.set(name, Short.parseShort(v)); break;
                case "long": p.set(name, (int) Long.parseLong(v)); break;
                case "float": p.set(name, Float.parseFloat(v.replace(',', '.'))); break;
                case "double": p.set(name, Double.parseDouble(v.replace(',', '.'))); break;
                case "string": p.set(name, v); break;
                case "uol": p.set(name, new Uol(v)); break;
                case "vector": p.set(name, new Vec(Integer.parseInt(c.getAttribute("x")), Integer.parseInt(c.getAttribute("y")))); break;
                case "null": p.set(name, null); break;
                case "canvas": {
                    int w = Integer.parseInt(c.getAttribute("width")), h = Integer.parseInt(c.getAttribute("height"));
                    Canvas cv = Canvas.blank(Math.max(1, w), Math.max(1, h));
                    children(c, cv.props);
                    p.set(name, cv);
                    break;
                }
                case "sound": p.set(name, new WzWriter.Sound(new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0})); break;
                default: {
                    Prop sub = p.sub(name); // convex / extended: keep children
                    children(c, sub);
                }
            }
        }
    }
}
