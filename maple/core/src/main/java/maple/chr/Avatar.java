package maple.chr;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A character look assembled from Character.wz: body, head, face, hair and equips.
 * Each part's "map" points (navel, neck, brow, hand...) are joined up to place it, and Base.wz/zmap
 * decides which part is drawn over which.
 */
public final class Avatar {
    /** Every standard v83 body action (Character.wz/0000200x.img). */
    public static final String[] STANCES = {"stand1", "stand2", "walk1", "walk2", "jump", "ladder", "rope", "prone", "alert",
            "sit", "fly", "heal", "dead", "proneStab", "shoot1", "shoot2", "shootF", "shot",
            "swingO1", "swingO2", "swingO3", "swingOF", "swingT1", "swingT2", "swingT3", "swingTF",
            "swingP1", "swingP2", "swingPF", "stabO1", "stabO2", "stabOF", "stabT1", "stabT2", "stabTF"};

    // Weapon behaviour (Character.wz/Weapon/xxx.img/info)
    public int weaponId, attackType, attackSpeed = 6;
    public String afterImage = "", weaponSound = "";
    public boolean twoHanded;
    private String standStance = "stand1", walkStance = "walk1";
    private final Map<String, String> smap = new HashMap<>();

    public static final class Part {
        final Sprite sprite;
        final int x, y; // top-left relative to the feet
        final int z;

        Part(Sprite sprite, int x, int y, int z) {
            this.sprite = sprite;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class Frame {
        final List<Part> parts = new ArrayList<>();
        int delay;
    }

    private final Map<String, Frame[]> stances = new HashMap<>();
    private final Map<String, Integer> zOrder = new HashMap<>();
    public final SpriteBank bank = new SpriteBank();
    public String problems = "";

    public Avatar(Wz wz, int skin, int face, int hair, int[] equips) {
        loadZmap(wz);
        for (WzNode n : wz.get("Base/smap.img").children()) smap.put(n.name, n.asString(""));
        WzNode body = wz.get(String.format("Character/%08d.img", 2000 + skin));
        WzNode head = wz.get(String.format("Character/%08d.img", 12000 + skin));
        WzNode faceImg = wz.get(String.format("Character/Face/%08d.img", face));
        WzNode hairImg = wz.get(String.format("Character/Hair/%08d.img", hair));
        List<WzNode> equipImgs = new ArrayList<>();
        // Slots claimed by equips (info/vslot, two letters each): a cap claiming H1 hides "hairOverHead".
        Map<String, WzNode> occupied = new HashMap<>();
        for (int e : equips) {
            WzNode n = wz.get("Character/" + equipFolder(e) + "/" + String.format("%08d.img", e));
            if (!n.exists()) continue;
            equipImgs.add(n);
            String vslot = n.get("info").getString("vslot", "");
            for (int i = 0; i + 2 <= vslot.length(); i += 2) occupied.put(vslot.substring(i, i + 2), n);
            if (equipFolder(e).equals("Weapon")) {
                WzNode info = n.get("info");
                weaponId = e;
                attackType = info.getInt("attack", 1);
                attackSpeed = info.getInt("attackSpeed", 6);
                afterImage = info.getString("afterImage", "");
                weaponSound = info.getString("sfx", "");
                int prefix = e / 10000;
                twoHanded = prefix == 138 || (prefix >= 140 && prefix <= 144) || prefix == 146;
                standStance = info.getInt("stand", twoHanded ? 2 : 1) == 2 ? "stand2" : "stand1";
                walkStance = info.getInt("walk", twoHanded ? 2 : 1) == 2 ? "walk2" : "walk1";
            }
        }
        this.occupied = occupied;
        if (!body.exists()) problems += "body missing; ";
        if (!head.exists()) problems += "head missing; ";

        WzNode faceCanvas = faceImg.get("default").get("face");
        for (String stance : STANCES) {
            WzNode bs = body.get(stance);
            if (!bs.exists()) continue;
            List<Frame> frames = new ArrayList<>();
            for (int i = 0; ; i++) {
                WzNode bf = bs.get(i);
                if (!bf.exists()) break;
                Frame fr = new Frame();
                fr.delay = Math.abs(bf.getInt("delay", 200));
                if (fr.delay == 0) fr.delay = 200;

                List<WzNode> parts = new ArrayList<>();
                // A body frame may borrow another action's frame ("action" + "frame").
                WzNode src = bf;
                if (!hasCanvas(bf) && bf.get("action").exists()) {
                    src = body.get(bf.getString("action", "")).get(bf.getInt("frame", 0));
                    if (!bf.get("delay").exists()) fr.delay = Math.abs(src.getInt("delay", 200));
                }
                addCanvases(src, parts);
                addCanvases(frameOf(head.get(stance), i), parts);
                boolean back = stance.equals("ladder") || stance.equals("rope");
                boolean showFace = bf.get("face").exists() ? bf.getInt("face", 1) != 0 : !back;
                if (showFace && faceCanvas.isCanvas()) parts.add(faceCanvas);
                addVisible(frameOf(hairImg.get(stance), i), parts, hairImg);
                for (WzNode eq : equipImgs) addVisible(frameOf(eq.get(stance), i), parts, eq);
                assemble(fr, parts);
                frames.add(fr);
            }
            if (!frames.isEmpty()) stances.put(stance, frames.toArray(new Frame[0]));
        }
        if (stances.isEmpty()) problems += "no stances; ";
        bank.bake();
    }

    private static WzNode frameOf(WzNode stance, int i) {
        if (!stance.exists()) return WzNode.MISSING;
        WzNode f = stance.get(i);
        if (f.exists()) return f;
        return stance.get(0);
    }

    private static String equipFolder(int id) {
        switch (id / 10000) {
            case 100: return "Cap";
            case 101: case 102: case 103: return "Accessory";
            case 104: return "Coat";
            case 105: return "Longcoat";
            case 106: return "Pants";
            case 107: return "Shoes";
            case 108: return "Glove";
            case 109: return "Shield";
            case 110: return "Cape";
            case 111: return "Ring";
            default: return id / 10000 >= 130 && id / 10000 < 170 ? "Weapon" : "Accessory";
        }
    }

    private Map<String, WzNode> occupied = new HashMap<>();

    private static boolean hasCanvas(WzNode frame) {
        for (WzNode c : frame.children()) if (c.resolve().isCanvas()) return true;
        return false;
    }

    /** Adds the frame's canvases except those in a slot another item claims (smap + vslot). */
    private void addVisible(WzNode frame, List<WzNode> out, WzNode owner) {
        if (!frame.exists()) return;
        for (WzNode c : frame.children()) {
            WzNode r = c.resolve();
            if (!r.isCanvas()) continue;
            String slot = smap.get(r.getString("z", ""));
            if (slot != null && slot.length() >= 2) {
                WzNode by = occupied.get(slot.substring(0, 2));
                if (by != null && by != owner) continue;
            }
            out.add(r);
        }
    }

    public String standStance() { return has(standStance) ? standStance : "stand1"; }
    public String walkStance() { return has(walkStance) ? walkStance : "walk1"; }

    private static final java.util.Random RNG = new java.util.Random();

    /** A random attack action for the weapon (HeavenClient's v83 table); prone gives proneStab. */
    public String attackStance(boolean prone, boolean degenerate) {
        if (prone) return "proneStab";
        String[][] normal = {
                {}, {"stabO1", "stabO2", "swingO1", "swingO2", "swingO3"}, {"stabT1", "swingP1"}, {"shoot1"}, {"shoot2"},
                {"stabO1", "stabO2", "swingT1", "swingT2", "swingT3"}, {"swingO1", "swingO2"}, {"swingO1", "swingO2"}, {}, {"shot"}};
        String[][] degen = {
                {}, {}, {}, {"swingT1", "swingT3"}, {"swingT1", "stabT1"}, {}, {}, {"swingT1", "stabT1"}, {}, {"swingP1", "stabT2"}};
        int t = Math.max(0, Math.min(9, attackType));
        String[] opts = degenerate && degen[t].length > 0 ? degen[t] : normal[t];
        if (opts.length == 0) opts = new String[]{"swingO1", "swingO2", "swingO3"};
        for (int tries = 0; tries < 4; tries++) {
            String s = opts[RNG.nextInt(opts.length)];
            if (has(s)) return s;
        }
        for (String s : opts) if (has(s)) return s;
        return has("swingO1") ? "swingO1" : standStance();
    }

    /** Total duration of an action in ms. */
    public int duration(String stance) {
        Frame[] f = frames(stance);
        int t = 0;
        if (f != null) for (Frame fr : f) t += fr.delay;
        return Math.max(1, t);
    }

    private static void addCanvases(WzNode frame, List<WzNode> out) {
        if (!frame.exists()) return;
        for (WzNode c : frame.children()) {
            WzNode r = c.resolve();
            if (r.isCanvas()) out.add(r);
        }
    }

    private void loadZmap(Wz wz) {
        WzNode zmap = wz.get("Base/zmap.img");
        List<String> names = new ArrayList<>();
        for (WzNode n : zmap.children()) names.add(n.name);
        // zmap lists layers front-to-back; draw order is the reverse. Detect it in case a client differs.
        int body = names.indexOf("body"), backHair = names.indexOf("backHair");
        if (body >= 0 && backHair >= 0 && backHair < body) Collections.reverse(names);
        for (int i = 0; i < names.size(); i++) zOrder.put(names.get(i), names.size() - i);
        if (names.isEmpty()) {
            String[] fallback = {"backHair", "body", "arm", "mailChest", "pants", "shoes", "mail", "mailArm", "head", "face", "hair", "hairOverHead", "weapon", "hand"};
            for (int i = 0; i < fallback.length; i++) zOrder.put(fallback[i], i + 1);
        }
    }

    private int z(WzNode canvas) {
        String z = canvas.getString("z", "");
        Integer v = zOrder.get(z);
        if (v != null) return v;
        Integer b = zOrder.get("body");
        return b == null ? 0 : b;
    }

    /** Places each part by joining its map points to points already placed (starting from the body at the feet). */
    private void assemble(Frame fr, List<WzNode> parts) {
        Map<String, int[]> anchors = new HashMap<>();
        List<WzNode> todo = new ArrayList<>(parts);
        // body first
        WzNode bodyPart = null;
        for (WzNode p : todo) if (p.name.equals("body")) { bodyPart = p; break; }
        if (bodyPart != null) {
            todo.remove(bodyPart);
            place(fr, bodyPart, 0, 0, anchors);
        }
        boolean progress = true;
        while (!todo.isEmpty() && progress) {
            progress = false;
            for (int i = 0; i < todo.size(); i++) {
                WzNode p = todo.get(i);
                WzNode map = p.get("map");
                int[] at = null;
                for (WzNode m : map.children()) {
                    if (!m.isVector()) continue;
                    int[] a = anchors.get(m.name);
                    if (a != null) {
                        at = new int[]{a[0] - m.vx(), a[1] - m.vy()};
                        break;
                    }
                }
                if (at == null && anchors.isEmpty()) at = new int[]{0, 0};
                if (at != null) {
                    place(fr, p, at[0], at[1], anchors);
                    todo.remove(i);
                    i--;
                    progress = true;
                }
            }
        }
        for (WzNode p : todo) place(fr, p, 0, 0, anchors);
        Collections.sort(fr.parts, (a, b) -> Integer.compare(a.z, b.z));
    }

    private void place(Frame fr, WzNode canvas, int px, int py, Map<String, int[]> anchors) {
        for (WzNode m : canvas.get("map").children()) {
            if (m.isVector() && !anchors.containsKey(m.name)) anchors.put(m.name, new int[]{px + m.vx(), py + m.vy()});
        }
        Sprite s = bank.get(canvas);
        if (s == null) return;
        fr.parts.add(new Part(s, px - s.ox, py - s.oy, z(canvas)));
    }

    public boolean has(String stance) { return stances.containsKey(stance); }

    public Frame[] frames(String stance) {
        Frame[] f = stances.get(stance);
        if (f == null) f = stances.get("stand1");
        if (f == null && !stances.isEmpty()) f = stances.values().iterator().next();
        return f;
    }

    public int delay(String stance, int frame) {
        Frame[] f = frames(stance);
        if (f == null || f.length == 0) return 200;
        return f[frame % f.length].delay;
    }

    public int frameCount(String stance) {
        Frame[] f = frames(stance);
        return f == null ? 0 : f.length;
    }

    /** Draws at the feet position (x, y). Sprites face left; flip mirrors to the right. */
    public void draw(Batch batch, String stance, int frame, float x, float y, boolean flip) {
        Frame[] f = frames(stance);
        if (f == null || f.length == 0) return;
        Frame fr = f[frame % f.length];
        for (Part p : fr.parts) {
            if (p.sprite.region == null) continue;
            if (flip) batch.draw(p.sprite.region, x - p.x, y + p.y, -p.sprite.w, p.sprite.h);
            else batch.draw(p.sprite.region, x + p.x, y + p.y, p.sprite.w, p.sprite.h);
        }
    }

    public void dispose() { bank.dispose(); }
}
