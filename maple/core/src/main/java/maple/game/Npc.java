package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Animation;
import maple.gfx.Sprite;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** An NPC the server spawned: its animations (stand, speak, move...), name and function labels. */
public final class Npc {
    public final int oid, id;
    public final String name, func;
    public final boolean hideName, mouseOnly, scripted;
    public final int x, y, fh;
    public final boolean flip;
    public boolean visible = true;
    private final Map<String, Animation> anims = new LinkedHashMap<>();
    private final List<String> states = new ArrayList<>();
    /** Speech-bubble lines per state (String.wz/Npc.img/id/<speak key>). */
    public final Map<String, List<String>> lines = new LinkedHashMap<>();
    private String stance = "stand";
    private long stanceStart;
    private static final Random RNG = new Random();

    public Npc(Wz wz, SpriteBank bank, int oid, int id, int x, int cy, boolean faceRight, int fh) {
        this.oid = oid;
        this.id = id;
        this.x = x;
        this.y = cy;
        this.fh = fh;
        this.flip = faceRight;
        String strId = String.format("%07d", id);
        WzNode src = wz.get("Npc/" + strId + ".img");
        WzNode strings = wz.get("String/Npc.img/" + id);
        WzNode info = src.get("info");
        String link = info.getString("link", "");
        if (!link.isEmpty()) {
            WzNode l = wz.get("Npc/" + link + ".img");
            if (l.exists()) src = l;
        }
        hideName = info.getInt("hideName", 0) != 0;
        mouseOnly = info.getInt("talkMouseOnly", 0) != 0;
        scripted = info.get("script").exists() || info.getInt("shop", 0) != 0;
        for (WzNode n : src.children()) {
            if (n.name.equals("info")) continue;
            Animation a = Animation.of(n, bank);
            if (a.isEmpty()) continue;
            anims.put(n.name, a);
            states.add(n.name);
            List<String> said = new ArrayList<>();
            for (WzNode s : n.get("speak").children()) {
                String line = strings.getString(s.asString(""), "");
                if (!line.isEmpty()) said.add(line);
            }
            if (!said.isEmpty()) lines.put(n.name, said);
        }
        if (!anims.containsKey(stance) && !states.isEmpty()) stance = states.get(0);
        name = strings.getString("name", "");
        String f = strings.getString("func", "");
        // Korean-only function text is left out, like the client
        boolean korean = false;
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if ((c >= 0x1100 && c <= 0x11FF) || (c >= 0xAC00 && c <= 0xD7AF)) korean = true;
        }
        func = korean ? "" : f;
    }

    public void update(long timeMs) {
        Animation a = anims.get(stance);
        if (a == null) return;
        if (timeMs - stanceStart >= a.durationMs() && !states.isEmpty()) {
            String next = states.get(RNG.nextInt(states.size()));
            stanceStart = timeMs;
            stance = next;
        }
    }

    public void draw(Batch batch, long timeMs) {
        if (!visible) return;
        Animation a = anims.get(stance);
        if (a != null) a.draw(batch, x, y, flip, timeMs - stanceStart, 1f);
    }

    /** Clickable area around the NPC's current frame. */
    public boolean contains(float wx, float wy) {
        if (!visible) return false;
        Animation a = anims.get(stance);
        Sprite s = a == null ? null : a.first();
        int w = s == null ? 40 : s.w, h = s == null ? 70 : s.h;
        return wx >= x - w / 2f && wx <= x + w / 2f && wy >= y - h && wy <= y;
    }

    public int height() {
        Animation a = anims.get("stand");
        if (a == null) a = anims.get(stance);
        Sprite s = a == null ? null : a.first();
        return s == null ? 70 : s.oy;
    }
}
