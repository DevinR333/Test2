package maple.map;

/** A portal: spawn point, visible door, invisible press-up spot or touch trigger. */
public final class Portal {
    public final String name;
    public final int type, x, y, targetMap;
    public final String targetName, script;

    Portal(String name, int type, int x, int y, int targetMap, String targetName, String script) {
        this.name = name;
        this.type = type;
        this.x = x;
        this.y = y;
        this.targetMap = targetMap;
        this.targetName = targetName;
        this.script = script;
    }

    public boolean isSpawn() { return type == 0; }
    public boolean isVisible() { return type == 2 || type == 7; }
    public boolean isTouch() { return type == 3 || type == 9; }

    public boolean hasTarget() {
        return targetMap != 999999999 && targetMap >= 0;
    }

    public boolean inRange(double px, double py) {
        return px >= x - 25 && px <= x + 25 && py >= y - 100 && py <= y + 25;
    }
}
