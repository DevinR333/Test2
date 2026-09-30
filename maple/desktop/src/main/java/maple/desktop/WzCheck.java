package maple.desktop;

import maple.map.Field;
import maple.wz.FolderSource;
import maple.wz.Wz;
import maple.wz.WzFile;
import maple.wz.WzNode;

import java.io.File;

/**
 * Reads your .wz files the way the game does and prints a short report.
 * Paste the output to whoever is helping you if something doesn't load.
 */
public final class WzCheck {
    private static int problems;

    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0] : "C:\\msclassicv83\\MapleStory");
        System.out.println("=== WZ check: " + dir.getAbsolutePath() + " ===");
        if (!dir.isDirectory()) {
            System.out.println("FOLDER NOT FOUND. Pass the folder with your .wz files, e.g. -PwzDir=C:\\msclassicv83\\MapleStory");
            System.exit(1);
        }
        Wz wz = new Wz(new FolderSource(dir));
        for (String f : Wz.FILES) {
            try {
                WzFile file = wz.file(f);
                System.out.printf("  %-12s ok   version %d, %s, %d top-level entries%n", f + ".wz", file.version, file.keyName(), file.root.childCount());
            } catch (RuntimeException e) {
                problem(f + ".wz: " + e.getMessage());
            }
        }

        check(wz, "Map/Map/Map1/100000000.img/info/bgm");
        check(wz, "Base/zmap.img");
        check(wz, "Character/00002000.img/stand1/0/body");
        check(wz, "Character/00012000.img/stand1/0/head");
        check(wz, "Character/Face/00020000.img/default/face");
        check(wz, "Character/Hair/00030000.img/stand1/0/hair");
        check(wz, "Map/MapHelper.img/portal/game/pv/0");
        check(wz, "String/Map.img");

        int[] maps = {100000000, 101000000, 102000000, 103000000, 104000000, 200000000, 220000000};
        for (int id : maps) {
            try {
                long t0 = System.currentTimeMillis();
                FieldProbe p = FieldProbe.load(wz, id);
                System.out.printf("  map %d %-28s %s (%d ms)%n", id, "'" + p.name + "'", p.summary, System.currentTimeMillis() - t0);
            } catch (Throwable e) {
                problem("map " + id + ": " + e);
            }
        }

        // decode a few images of each pixel format we can find
        int decoded = 0;
        try {
            WzNode back = wz.get("Map/Back/grassySoil.img");
            decoded += decodeAll(back, 40);
            decoded += decodeAll(wz.get("Character/00002000.img/stand1"), 20);
            decoded += decodeAll(wz.get("Mob/0100100.img"), 20);
        } catch (Throwable e) {
            problem("image decode: " + e);
        }
        System.out.println("  decoded " + decoded + " images, formats seen: " + formats);

        WzNode bgm = wz.get("Sound/Bgm00.img/GoPicnic");
        if (bgm.type == WzNode.Type.SOUND) {
            byte[] d = bgm.soundData();
            System.out.printf("  music GoPicnic: %d bytes, starts %02X %02X %02X%n", d.length, d[0], d[1], d[2]);
        } else {
            problem("Sound/Bgm00.img/GoPicnic not found");
        }

        System.out.println(problems == 0 ? "=== ALL CHECKS PASSED ===" : "=== " + problems + " PROBLEM(S) ===");
    }

    private static final java.util.TreeSet<Integer> formats = new java.util.TreeSet<>();

    private static int decodeAll(WzNode n, int max) {
        int count = 0;
        if (n.isCanvas()) {
            try {
                n.rgba();
                formats.add(n.format());
                count++;
            } catch (RuntimeException e) {
                problem("decode " + n.fullPath() + " (format " + n.format() + "): " + e.getMessage());
            }
        }
        for (WzNode c : n.children()) {
            if (count >= max) break;
            count += decodeAll(c, max - count);
        }
        return count;
    }

    private static void check(Wz wz, String path) {
        WzNode n = wz.get(path);
        if (!n.exists()) problem("missing " + path);
        else System.out.println("  found " + path + (n.childCount() > 0 ? " (" + n.childCount() + " children)" : " = " + n));
    }

    private static void problem(String msg) {
        problems++;
        System.out.println("  PROBLEM: " + msg);
    }

    /** Loads a map's data without needing a GPU (textures are not created). */
    static final class FieldProbe {
        String name, summary;

        static FieldProbe load(Wz wz, int id) {
            WzNode src = wz.get(Field.imgPath(id));
            if (!src.exists()) throw new RuntimeException("not found");
            FieldProbe p = new FieldProbe();
            int tiles = 0, objs = 0, missing = 0;
            for (int l = 0; l < 8; l++) {
                WzNode layer = src.get(l);
                String ts = layer.get("info").getString("tS", "");
                for (WzNode t : layer.get("tile").children()) {
                    tiles++;
                    if (!wz.get("Map/Tile/" + ts + ".img/" + t.getString("u", "") + "/" + t.getInt("no", 0)).isCanvas()) missing++;
                }
                for (WzNode o : layer.get("obj").children()) {
                    objs++;
                    WzNode on = wz.get("Map/Obj/" + o.getString("oS", "") + ".img/" + o.getString("l0", "") + "/" + o.getString("l1", "") + "/" + o.getString("l2", ""));
                    if (!on.exists()) missing++;
                }
            }
            int fh = 0;
            for (WzNode a : src.get("foothold").children()) for (WzNode b : a.children()) fh += b.childCount();
            p.summary = tiles + " tiles, " + objs + " objs, " + src.get("back").childCount() + " backs, " + fh + " footholds, "
                    + src.get("portal").childCount() + " portals, " + src.get("life").childCount() + " life"
                    + (missing > 0 ? ", " + missing + " MISSING sprites" : "");
            if (missing > 0) problems++;
            p.name = "";
            for (WzNode r : wz.get("String/Map.img").children()) {
                WzNode m = r.get(Integer.toString(id));
                if (m.exists()) { p.name = m.getString("mapName", ""); break; }
            }
            return p;
        }
    }
}
