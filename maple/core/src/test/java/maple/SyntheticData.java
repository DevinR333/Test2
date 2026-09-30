package maple;

import maple.WzWriter.Canvas;
import maple.WzWriter.Dir;
import maple.WzWriter.Prop;
import maple.WzWriter.Uol;
import maple.WzWriter.Vec;

import java.io.File;
import java.io.IOException;

/**
 * Builds a tiny made-up game in WZ format (coloured boxes, no real game art) so the whole
 * pipeline can be tested without anyone's client files.
 */
public final class SyntheticData {
    public static final int MAP = 100000000, MAP2 = 100000001;

    public static void main(String[] args) throws IOException {
        write(new File(args.length > 0 ? args[0] : "build/synthetic-wz"));
    }

    public static void write(File dir) throws IOException {
        dir.mkdirs();
        WzWriter w = new WzWriter();
        w.write(map(), new File(dir, "Map.wz"));
        w.write(character(), new File(dir, "Character.wz"));
        w.write(base(), new File(dir, "Base.wz"));
        w.write(strings(), new File(dir, "String.wz"));
        w.write(npc(), new File(dir, "Npc.wz"));
        w.write(mob(), new File(dir, "Mob.wz"));
    }

    static Dir map() {
        Dir root = new Dir();
        Dir mapDir = root.dir("Map").dir("Map1");
        buildMap(mapDir.img("100000000.img"), MAP2, "right00");
        buildMap(mapDir.img("100000001.img"), MAP, "left00");
        Prop link = mapDir.img("100000002.img");
        link.sub("info").set("link", MAP);

        Prop back = root.dir("Back").img("test.img");
        back.sub("back").canvas("0", 256, 256, 0xFF87CEEB, 0, 0);
        back.sub("back").canvas("1", 200, 120, 0xFF4F8F4F, 100, 60);
        Prop ani = back.sub("ani").sub("0");
        ani.canvas("0", 60, 30, 0xFFFFFFFF, 30, 15).props.set("delay", 400);
        ani.canvas("1", 70, 34, 0xFFEEEEFF, 35, 17).props.set("delay", 400);

        Prop tile = root.dir("Tile").img("grass.img");
        tile.sub("bsc").canvas("0", 90, 60, 0xFF8B5A2B, 0, 0).props.set("z", 2);
        tile.sub("enH0").canvas("0", 90, 20, 0xFF3CB043, 0, 20).props.set("z", 3);

        Prop obj = root.dir("Obj").img("house.img");
        Prop tree = obj.sub("tree").sub("big");
        tree.sub("0").canvas("0", 80, 160, 0xFF2E7D32, 40, 160);
        Prop sign = obj.sub("sign").sub("anim").sub("0");
        sign.canvas("0", 40, 40, 0xFFFFC107, 20, 40).props.set("delay", 300);
        sign.canvas("1", 40, 40, 0xFFFF9800, 20, 40).props.set("delay", 300);

        Prop pv = root.dir("MapHelper").img("MapHelper.img");
        // MapHelper.img sits directly in Map.wz
        root.entries.remove("MapHelper");
        pv = root.img("MapHelper.img");
        Prop frames = pv.sub("portal").sub("game").sub("pv");
        for (int i = 0; i < 4; i++) frames.canvas(Integer.toString(i), 60, 110, 0xAA3399FF - i * 0x00101000, 30, 105).props.set("delay", 100);
        return root;
    }

    static void buildMap(Prop m, int other, String otherPortal) {
        Prop info = m.sub("info");
        info.set("bgm", "Bgm00/Nothing");
        info.set("VRLeft", -900).set("VRRight", 900).set("VRTop", -700).set("VRBottom", 300);
        info.set("returnMap", MAP);

        Prop backs = m.sub("back");
        Prop b0 = backs.sub(0);
        b0.set("bS", "test").set("no", 0).set("type", 3).set("rx", 0).set("ry", 0).set("x", 0).set("y", 0);
        Prop b1 = backs.sub(1);
        b1.set("bS", "test").set("no", 1).set("type", 1).set("rx", -50).set("ry", -50).set("x", 0).set("y", 150).set("cx", 260);
        Prop b2 = backs.sub(2);
        b2.set("bS", "test").set("no", 0).set("ani", 1).set("type", 4).set("rx", 30).set("x", 0).set("y", -150).set("cx", 400);

        // Layer 0: ground tiles and objects
        Prop l0 = m.sub(0);
        l0.sub("info").set("tS", "grass");
        Prop tiles = l0.sub("tile");
        int t = 0;
        for (int x = -900; x < 900; x += 90) {
            tiles.sub(t++).set("x", x).set("y", 200).set("u", "bsc").set("no", 0).set("zM", 0);
            tiles.sub(t++).set("x", x).set("y", 200).set("u", "enH0").set("no", 0).set("zM", 0);
            tiles.sub(t++).set("x", x).set("y", 260).set("u", "bsc").set("no", 0).set("zM", 0);
        }
        // a raised platform
        for (int x = 0; x < 360; x += 90) {
            tiles.sub(t++).set("x", x).set("y", 20).set("u", "enH0").set("no", 0).set("zM", 0);
        }
        Prop objs = l0.sub("obj");
        objs.sub(0).set("oS", "house").set("l0", "tree").set("l1", "big").set("l2", "0").set("x", -500).set("y", 200).set("z", 1);
        objs.sub(1).set("oS", "house").set("l0", "sign").set("l1", "anim").set("l2", "0").set("x", -300).set("y", 200).set("z", 2);
        objs.sub(2).set("oS", "house").set("l0", "tree").set("l1", "big").set("l2", "0").set("x", 600).set("y", 200).set("z", 1).set("f", 1);

        // Footholds: ground from -900 to 900 at y=200 with walls, a slope, and a platform at y=20.
        Prop fh = m.sub("foothold").sub(0).sub(1);
        fh.sub(1).set("x1", -900).set("y1", 0).set("x2", -900).set("y2", 200).set("prev", 0).set("next", 2);
        fh.sub(2).set("x1", -900).set("y1", 200).set("x2", 400).set("y2", 200).set("prev", 1).set("next", 3);
        fh.sub(3).set("x1", 400).set("y1", 200).set("x2", 600).set("y2", 150).set("prev", 2).set("next", 4);
        fh.sub(4).set("x1", 600).set("y1", 150).set("x2", 900).set("y2", 150).set("prev", 3).set("next", 5);
        fh.sub(5).set("x1", 900).set("y1", 150).set("x2", 900).set("y2", -50).set("prev", 4).set("next", 0);
        Prop fh2 = m.sub("foothold").sub(0).sub(2);
        fh2.sub(10).set("x1", 0).set("y1", 20).set("x2", 360).set("y2", 20).set("prev", 0).set("next", 0);

        Prop lr = m.sub("ladderRope");
        lr.sub(1).set("l", 1).set("uf", 1).set("x", 60).set("y1", 25).set("y2", 190).set("page", 0);

        Prop portals = m.sub("portal");
        portals.sub(0).set("pn", "sp").set("pt", 0).set("x", -200).set("y", 200).set("tm", 999999999).set("tn", "");
        portals.sub(1).set("pn", "right00").set("pt", 2).set("x", 820).set("y", 150).set("tm", other).set("tn", otherPortal);
        portals.sub(2).set("pn", "left00").set("pt", 2).set("x", -820).set("y", 200).set("tm", other).set("tn", "right00");

        Prop life = m.sub("life");
        life.sub(0).set("type", "n").set("id", "9000000").set("x", -400).set("cy", 200).set("y", 200).set("fh", 2).set("rx0", -450).set("rx1", -350).set("f", 0);
        life.sub(1).set("type", "m").set("id", "100100").set("x", 200).set("cy", 200).set("y", 200).set("fh", 2).set("rx0", 0).set("rx1", 380);
    }

    static Dir character() {
        Dir root = new Dir();
        Prop body = root.img("00002000.img");
        Prop head = root.img("00012000.img");
        String[] stances = {"stand1", "walk1", "jump", "ladder", "rope", "prone"};
        for (String s : stances) {
            int frames = s.equals("walk1") ? 4 : s.equals("stand1") ? 3 : 2;
            for (int i = 0; i < frames; i++) {
                Prop f = body.sub(s).sub(i);
                f.set("delay", 180);
                int bw = s.equals("prone") ? 44 : 22;
                int bh = s.equals("prone") ? 16 : 30;
                WzWriter.Canvas b = f.canvas("body", bw, bh, 0xFFFFD1A4, bw / 2, bh);
                b.props.set("z", "body");
                b.props.sub("map").set("navel", new Vec(0, -bh / 2)).set("neck", new Vec(0, -bh));
                Canvas arm = f.canvas("arm", 8, 16, 0xFFF5C08A, 4, 2);
                arm.props.set("z", "arm");
                arm.props.sub("map").set("navel", new Vec(-6 + i, -4)).set("hand", new Vec(0, 12));
                Prop hf = head.sub(s).sub(i);
                if (i == 0 && s.equals("stand1")) {
                    Canvas h = head.sub("front").canvas("head", 34, 32, 0xFFFFD9B0, 17, 30);
                    h.props.set("z", "head");
                    h.props.sub("map").set("neck", new Vec(0, 0)).set("brow", new Vec(-2, -18));
                }
                hf.set("head", new Uol("../../front/head"));
            }
        }
        Prop face = root.dir("Face").img("00020000.img");
        Canvas fc = face.sub("default").canvas("face", 14, 8, 0xFF222222, 7, -4);
        fc.props.set("z", "face");
        fc.props.sub("map").set("brow", new Vec(0, 0));
        Prop hair = root.dir("Hair").img("00030000.img");
        for (String s : stances) {
            Canvas hc = hair.sub(s).sub(0).canvas("hair", 38, 18, 0xFF5D4037, 19, 14);
            hc.props.set("z", "hair");
            hc.props.sub("map").set("brow", new Vec(0, 0));
        }
        Prop coat = root.dir("Coat").img("01040002.img");
        Prop pants = root.dir("Pants").img("01060002.img");
        for (String s : stances) {
            Canvas mc = coat.sub(s).sub(0).canvas("mail", 24, 16, 0xFFFFFFFF, 12, 10);
            mc.props.set("z", "mail");
            mc.props.sub("map").set("navel", new Vec(0, 0));
            Canvas pc = pants.sub(s).sub(0).canvas("pants", 24, 10, 0xFF1565C0, 12, 0);
            pc.props.set("z", "pants");
            pc.props.sub("map").set("navel", new Vec(0, 2));
        }
        Prop weapon = root.dir("Weapon").img("01302000.img");
        Canvas wc = weapon.sub("stand1").sub(0).canvas("weapon", 6, 40, 0xFFB0BEC5, 3, 30);
        wc.props.set("z", "weapon");
        wc.props.sub("map").set("hand", new Vec(0, 0));
        return root;
    }

    static Dir base() {
        Dir root = new Dir();
        Prop z = root.img("zmap.img");
        String[] front2back = {"weapon", "hairOverHead", "hair", "face", "head", "mailArm", "mail", "pants", "arm", "body", "backHair"};
        for (String s : front2back) z.set(s, "");
        return root;
    }

    static Dir strings() {
        Dir root = new Dir();
        Prop m = root.img("Map.img").sub("victoria");
        m.sub(Integer.toString(MAP)).set("streetName", "Victoria Road").set("mapName", "Test Town");
        m.sub(Integer.toString(MAP2)).set("streetName", "Victoria Road").set("mapName", "Test Field");
        root.img("Npc.img").sub("9000000").set("name", "Tester");
        root.img("Mob.img").sub("100100").set("name", "Snail");
        return root;
    }

    static Dir npc() {
        Dir root = new Dir();
        Prop n = root.img("9000000.img");
        n.sub("stand").canvas("0", 30, 56, 0xFF9C27B0, 15, 56).props.set("delay", 500);
        n.sub("stand").canvas("1", 30, 54, 0xFF8E24AA, 15, 54).props.set("delay", 500);
        return root;
    }

    static Dir mob() {
        Dir root = new Dir();
        Prop m = root.img("0100100.img");
        m.sub("info").set("speed", -60);
        m.sub("stand").canvas("0", 36, 26, 0xFFFF7043, 18, 26);
        m.sub("move").canvas("0", 36, 26, 0xFFFF7043, 18, 26).props.set("delay", 200);
        m.sub("move").canvas("1", 38, 24, 0xFFF4511E, 19, 24).props.set("delay", 200);
        return root;
    }
}
