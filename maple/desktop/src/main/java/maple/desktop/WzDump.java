package maple.desktop;

import maple.wz.FolderSource;
import maple.wz.Wz;
import maple.wz.WzFile;
import maple.wz.WzNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Writes the structure of the WZ parts the UI and physics are built from: names, numbers,
 * positions and image sizes (no pixels, no sounds). Used to match screens exactly to your client.
 */
public final class WzDump {
    private static PrintWriter out;
    private static int lines;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "C:\\msclassicv83\\MapleStory");
        File file = new File(args.length > 1 ? args[1] : "wz-structure.txt");
        Wz wz = new Wz(new FolderSource(dir));
        out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        out.println("WZ structure dump of " + dir.getAbsolutePath());

        for (String f : Wz.FILES) {
            try {
                WzFile wf = wz.file(f);
                out.println();
                out.println("### " + f + ".wz  version " + wf.version + " key " + wf.keyName());
                listTop(wf.root, 0, f.equals("UI") || f.equals("Base") || f.equals("Etc") || f.equals("Sound") ? 2 : 1);
            } catch (RuntimeException e) {
                out.println("### " + f + ".wz  ERROR " + e.getMessage());
            }
        }

        section(wz, "Map/Physics.img", 99);
        section(wz, "Base/zmap.img", 99);
        section(wz, "Base/smap.img", 99);
        for (WzNode img : wz.get("UI").children()) {
            String n = img.name;
            if (n.startsWith("Login") || n.startsWith("MapLogin") || n.startsWith("StatusBar") || n.equals("Basic.img")
                    || n.equals("Logo.img") || n.equals("ChatBalloon.img") || n.equals("NameTag.img")) {
                section(wz, "UI/" + n, 99);
            }
        }
        section(wz, "UI/UIWindow.img", 4);
        section(wz, "UI/UIWindow2.img", 4);
        section(wz, "Etc/MakeCharInfo.img", 99);
        section(wz, "Map/Obj/login.img", 5);
        section(wz, "Map/Back/login.img", 4);
        section(wz, "Sound/UI.img", 1);
        section(wz, "Sound/Game.img", 1);
        section(wz, "Sound/BgmUI.img", 1);
        out.println();
        out.println("END (" + lines + " lines)");
        out.close();
        System.out.println("Wrote " + file.getAbsolutePath() + " (" + lines + " lines)");
    }

    private static void listTop(WzNode n, int depth, int maxDepth) {
        for (WzNode c : n.children()) {
            if (c.type == WzNode.Type.DIR) {
                line(depth, c.name + "/  (" + c.childCount() + " entries)");
                if (depth + 1 < maxDepth) listTop(c, depth + 1, maxDepth);
            } else if (depth < maxDepth && maxDepth > 1) {
                line(depth, c.name);
            }
        }
    }

    private static void section(Wz wz, String path, int maxDepth) {
        WzNode n = wz.get(path);
        out.println();
        out.println("=== " + path + (n.exists() ? "" : "  (not found)"));
        if (!n.exists()) return;
        try {
            tree(n, 0, maxDepth);
        } catch (RuntimeException e) {
            out.println("  ERROR " + e);
        }
    }

    private static void tree(WzNode n, int depth, int maxDepth) {
        for (WzNode c : n.children()) {
            line(depth, describe(c));
            if (depth + 1 < maxDepth && c.type != WzNode.Type.UOL && c.childCount() > 0) tree(c, depth + 1, maxDepth);
            else if (c.childCount() > 0 && c.type != WzNode.Type.CANVAS) line(depth + 1, "... " + c.childCount() + " children");
        }
    }

    private static String describe(WzNode c) {
        switch (c.type) {
            case CANVAS: {
                WzNode o = c.get("origin");
                return c.name + "  [img " + c.width() + "x" + c.height() + (o.isVector() ? " origin " + o.vx() + "," + o.vy() : "") + "]";
            }
            case SOUND: return c.name + "  [sound]";
            default: return c.toString();
        }
    }

    private static void line(int depth, String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        out.println(sb.append(s));
        lines++;
    }
}
