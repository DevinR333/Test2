package compat.awt;

/** Drop-in for java.awt.Point (not available on Android). */
public class Point implements java.io.Serializable, Cloneable {
    public int x;
    public int y;

    public Point() {}
    public Point(Point p) { this(p.x, p.y); }
    public Point(int x, int y) { this.x = x; this.y = y; }

    public double getX() { return x; }
    public double getY() { return y; }
    public Point getLocation() { return new Point(x, y); }
    public void setLocation(Point p) { setLocation(p.x, p.y); }
    public void setLocation(int x, int y) { this.x = x; this.y = y; }
    public void setLocation(double x, double y) { this.x = (int) Math.floor(x + 0.5); this.y = (int) Math.floor(y + 0.5); }
    public void move(int x, int y) { this.x = x; this.y = y; }
    public void translate(int dx, int dy) { this.x += dx; this.y += dy; }

    public double distanceSq(double px, double py) { px -= x; py -= y; return px * px + py * py; }
    public double distanceSq(Point p) { return distanceSq(p.x, p.y); }
    public double distance(double px, double py) { return Math.sqrt(distanceSq(px, py)); }
    public double distance(Point p) { return Math.sqrt(distanceSq(p)); }

    public static double distanceSq(double x1, double y1, double x2, double y2) {
        x1 -= x2; y1 -= y2; return x1 * x1 + y1 * y1;
    }
    public static double distance(double x1, double y1, double x2, double y2) { return Math.sqrt(distanceSq(x1, y1, x2, y2)); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return p.x == x && p.y == y;
    }
    @Override public int hashCode() {
        long bits = Double.doubleToLongBits(getX());
        bits ^= Double.doubleToLongBits(getY()) * 31;
        return (((int) bits) ^ ((int) (bits >> 32)));
    }
    @Override public Object clone() { return new Point(x, y); }
    @Override public String toString() { return getClass().getName() + "[x=" + x + ",y=" + y + "]"; }
}
