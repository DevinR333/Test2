package maple.map;

/** One platform segment. Floors and slopes carry things; vertical segments are walls. */
public final class Foothold {
    public final int id, layer, prev, next;
    public final int x1, y1, x2, y2;

    public Foothold(int id, int layer, int prev, int next, int x1, int y1, int x2, int y2) {
        this.id = id;
        this.layer = layer;
        this.prev = prev;
        this.next = next;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public int l() { return Math.min(x1, x2); }
    public int r() { return Math.max(x1, x2); }
    public int t() { return Math.min(y1, y2); }
    public int b() { return Math.max(y1, y2); }
    public boolean isWall() { return x1 == x2; }
    public boolean isFloor() { return y1 == y2; }

    public double slope() {
        return isWall() ? 0 : (double) (y2 - y1) / (x2 - x1);
    }

    public double groundBelow(double x) {
        return isFloor() ? y1 : slope() * (x - x1) + y1;
    }

    /** A wall that overlaps the vertical range [top, bottom]. */
    public boolean isBlocking(int top, int bottom) {
        return isWall() && t() <= bottom && b() >= top;
    }
}
