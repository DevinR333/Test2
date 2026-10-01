package maple.map;

/** A ladder or rope: a vertical line from y1 (top) to y2 (bottom). */
public final class Ladder {
    public final boolean isLadder;
    public final boolean exitTop;
    public final int x, y1, y2;
    /** The map layer its picture is on: a climbing character is drawn on this layer, in front of it. */
    public final int page;

    Ladder(boolean isLadder, boolean exitTop, int x, int y1, int y2, int page) {
        this.page = Math.max(0, Math.min(7, page));
        this.isLadder = isLadder;
        this.exitTop = exitTop;
        this.x = x;
        this.y1 = Math.min(y1, y2);
        this.y2 = Math.max(y1, y2);
    }

    /** Close enough to grab: pressing up from below, or down from the platform on top. */
    public boolean inRange(double px, double py, boolean upwards) {
        double depth = upwards ? py - 5 : py + 5;
        return px >= x - 12 && px <= x + 12 && depth >= y1 && depth <= y2 + 15;
    }
}
