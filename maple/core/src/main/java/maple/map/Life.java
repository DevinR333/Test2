package maple.map;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Animation;
import maple.gfx.SpriteBank;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** NPCs and mobs placed on the map. Mobs wander around their spawn area (combat comes later). */
public final class Life {
    public final boolean isMob;
    public final int id;
    public final String name;
    final Map<String, Animation> anims = new HashMap<>();
    final PhysicsObject phys = new PhysicsObject();
    final int rx0, rx1;
    boolean facingRight;
    String stance = "stand";
    long stanceStart;
    int layer;
    int thinkTicks;
    boolean flying;
    double speed;
    double baseY;
    private static final Random RNG = new Random();

    Life(WzNode src, Wz wz, SpriteBank bank, FootholdTree fht) {
        String type = src.getString("type", "n");
        isMob = type.equals("m");
        id = src.getInt("id", 0);
        String idStr = String.format("%07d", id);
        WzNode img = wz.get((isMob ? "Mob/" : "Npc/") + idStr + ".img");
        WzNode link = img.get("info").get("link");
        if (link.exists()) {
            WzNode linked = wz.get((isMob ? "Mob/" : "Npc/") + String.format("%07d", link.asInt(0)) + ".img");
            if (linked.exists()) img = linked;
        }
        String[] wanted = isMob ? new String[]{"stand", "move", "fly"} : new String[]{"stand"};
        for (String a : wanted) {
            WzNode n = img.get(a);
            if (n.exists()) {
                Animation anim = Animation.of(n, bank);
                if (!anim.isEmpty()) anims.put(a, anim);
            }
        }
        if (anims.isEmpty()) {
            // Some NPCs only have oddly named animations: take the first one that works.
            for (WzNode n : img.children()) {
                if (n.name.equals("info")) continue;
                Animation anim = Animation.of(n, bank);
                if (!anim.isEmpty()) {
                    anims.put("stand", anim);
                    break;
                }
            }
        }
        if (!anims.containsKey("stand") && anims.containsKey("fly")) anims.put("stand", anims.get("fly"));
        if (!anims.containsKey("stand") && anims.containsKey("move")) anims.put("stand", anims.get("move"));

        WzNode strings = wz.get("String/" + (isMob ? "Mob.img/" : "Npc.img/") + id);
        name = strings.getString("name", "");

        flying = isMob && anims.containsKey("fly");
        double sp = isMob ? img.get("info").getInt(flying ? "flySpeed" : "speed", 0) : 0;
        speed = Math.max(0.02, 0.1 * (100 + sp) / 100.0);
        facingRight = src.getInt("f", 0) != 0;
        int x = src.getInt("x", 0);
        int cy = src.getInt("cy", src.getInt("y", 0));
        rx0 = src.getInt("rx0", x - 100);
        rx1 = src.getInt("rx1", x + 100);
        phys.setPosition(x, flying ? src.getInt("y", cy) : cy - 5);
        baseY = phys.y;
        phys.mode = flying ? PhysicsObject.Mode.FLYING : PhysicsObject.Mode.NORMAL;
        int fh = src.getInt("fh", 0);
        layer = fht.get(fh).layer;
        if (!isMob) phys.mode = PhysicsObject.Mode.FIXED;
        thinkTicks = RNG.nextInt(200);
    }

    public boolean hidden;

    void update(FootholdTree fht, long now) {
        if (!isMob) return;
        if (--thinkTicks <= 0) {
            thinkTicks = 150 + RNG.nextInt(300);
            boolean canMove = anims.containsKey("move") || flying;
            if (canMove && RNG.nextInt(3) != 0) {
                setStance(flying ? "fly" : "move", now);
                facingRight = RNG.nextBoolean();
            } else {
                setStance(flying ? "fly" : "stand", now);
            }
        }
        boolean moving = stance.equals("move") || (flying && thinkTicks % 2 == 0 && stance.equals("fly"));
        if (moving || (flying && stance.equals("fly"))) {
            if (phys.x <= rx0) facingRight = true;
            else if (phys.x >= rx1) facingRight = false;
            phys.hforce = facingRight ? speed : -speed;
            if (!flying) phys.flags |= PhysicsObject.TURN_AT_EDGES;
        }
        if (flying) {
            // gentle bobbing around the spawn height
            phys.vforce = (baseY + Math.sin(now / 600.0) * 10 - phys.y) * 0.002;
        }
        double before = phys.x;
        fht.move(phys);
        if (!flying && stance.equals("move") && Math.abs(phys.x - before) < 0.0001 && phys.onGround) {
            facingRight = !facingRight; // hit a wall or edge
        }
        if (phys.onGround) layer = fht.get(phys.fhid).layer;
    }

    private void setStance(String s, long now) {
        if (!anims.containsKey(s)) s = "stand";
        if (!s.equals(stance)) {
            stance = s;
            stanceStart = now;
        }
    }

    void draw(Batch batch, float alpha, long now) {
        if (hidden) return;
        Animation a = anims.get(stance);
        if (a == null) a = anims.get("stand");
        if (a == null) return;
        a.draw(batch, (float) Math.round(phys.drawX(alpha)), (float) Math.round(phys.drawY(alpha)), facingRight, now - stanceStart, 1f);
    }

    public float x() { return (float) phys.x; }
    public float y() { return (float) phys.y; }
}
