package maple.game;

import com.badlogic.gdx.graphics.g2d.Batch;
import maple.gfx.Animation;
import maple.gfx.SpriteBank;
import maple.map.Field;
import maple.map.PhysicsObject;
import maple.wz.Wz;
import maple.wz.WzNode;

import java.util.HashMap;
import java.util.Map;

/**
 * A summoned pet (Item.wz/Pet/<id>.img): it walks after its owner on the footholds, jumps to keep
 * up, and catches up at once when left far behind (another platform, a portal).
 */
public final class Pet {
    public final int itemId, slot;
    public final long uniqueId;
    public final String name;
    public final PhysicsObject phys = new PhysicsObject();
    private final Map<String, Animation> anims = new HashMap<>();
    private String stance = "stand0";
    private long stanceStart;
    private boolean facingRight;
    private int stuckTicks;

    public Pet(Wz wz, SpriteBank bank, int slot, int itemId, String name, long uniqueId, int x, int y) {
        this.slot = slot;
        this.itemId = itemId;
        this.name = name;
        this.uniqueId = uniqueId;
        WzNode img = wz.get("Item/Pet/" + itemId + ".img");
        for (String s : new String[]{"stand0", "stand1", "move", "jump", "hang", "fly", "rest0", "prone"}) {
            WzNode n = img.get(s);
            if (!n.exists()) continue;
            Animation a = Animation.of(n, bank);
            if (!a.isEmpty()) anims.put(s, a);
        }
        phys.setPosition(x, y);
    }

    private void stance(String s, long timeMs) {
        if (!anims.containsKey(s)) s = anims.containsKey("stand0") ? "stand0" : s;
        if (!s.equals(stance)) {
            stance = s;
            stanceStart = timeMs;
        }
    }

    /** One 8 ms tick: follow the owner standing at (ox, oy), facing ownerRight. */
    public void update(Field field, double ox, double oy, boolean ownerRight, long timeMs) {
        double targetX = ox + (ownerRight ? -40 : 40) * (1 + slot * 0.6);
        double dx = targetX - phys.x, dy = oy - phys.y;
        // too far (other platform, teleport, map change): appear next to the owner
        if (Math.abs(dx) > 450 || Math.abs(dy) > 300 || stuckTicks > 400) {
            phys.setPosition(targetX, oy - 5);
            stuckTicks = 0;
        }
        phys.walkDir = Math.abs(dx) > 20 ? (dx > 0 ? 1 : -1) : 0;
        phys.speedMul = Math.abs(dx) > 150 ? 1.6 : 1.0;
        if (phys.walkDir != 0) facingRight = phys.walkDir > 0;
        else facingRight = ownerRight;
        // jump to reach a higher owner, or when the last step went nowhere (a wall)
        boolean blocked = phys.walkDir != 0 && Math.abs(phys.x - phys.lastX) < 0.01;
        if (phys.onGround && (dy < -40 || blocked)) phys.jumpRequest = true;
        stuckTicks = blocked ? stuckTicks + 1 : 0;
        field.footholds.move(phys); // also keeps the previous position for drawing
        if (!phys.onGround) stance("jump", timeMs);
        else if (phys.walkDir != 0) stance("move", timeMs);
        else stance("stand0", timeMs);
    }

    public void draw(Batch batch, float alpha, long timeMs) {
        Animation a = anims.get(stance);
        if (a == null) return;
        a.draw(batch, (float) Math.round(phys.drawX(alpha)), (float) Math.round(phys.drawY(alpha)), facingRight, timeMs - stanceStart, 1f);
    }
}
