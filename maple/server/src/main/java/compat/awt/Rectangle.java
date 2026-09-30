package compat.awt;

/** Drop-in for java.awt.Rectangle (not available on Android). */
public class Rectangle implements java.io.Serializable, Cloneable {
    public int x, y, width, height;

    public Rectangle() {}
    public Rectangle(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    public Rectangle(int width, int height) { this(0, 0, width, height); }
    public Rectangle(Point p, Dimension d) { this(p.x, p.y, d.width, d.height); }
    public Rectangle(Rectangle r) { this(r.x, r.y, r.width, r.height); }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public double getMinX() { return x; }
    public double getMinY() { return y; }
    public double getMaxX() { return (double) x + width; }
    public double getMaxY() { return (double) y + height; }
    public double getCenterX() { return x + width / 2.0; }
    public double getCenterY() { return y + height / 2.0; }
    public Point getLocation() { return new Point(x, y); }
    public void setLocation(int x, int y) { this.x = x; this.y = y; }
    public void setBounds(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    public void setBounds(Rectangle r) { setBounds(r.x, r.y, r.width, r.height); }
    public void setSize(int width, int height) { this.width = width; this.height = height; }
    public void translate(int dx, int dy) { x += dx; y += dy; }
    public boolean isEmpty() { return width <= 0 || height <= 0; }
    public Rectangle getBounds() { return new Rectangle(this); }

    public boolean contains(Point p) { return contains(p.x, p.y); }
    public boolean contains(int X, int Y) {
        int w = width, h = height;
        if ((w | h) < 0) return false;
        if (X < x || Y < y) return false;
        w += x;
        h += y;
        return (w < x || w > X) && (h < y || h > Y);
    }
    public boolean contains(double X, double Y) { return contains((int) Math.floor(X), (int) Math.floor(Y)) && X < x + width && Y < y + height; }
    public boolean contains(Rectangle r) {
        return r.width > 0 && r.height > 0 && width > 0 && height > 0
                && r.x >= x && r.y >= y && r.x + r.width <= x + width && r.y + r.height <= y + height;
    }
    public boolean intersects(Rectangle r) {
        int tw = width, th = height, rw = r.width, rh = r.height;
        if (rw <= 0 || rh <= 0 || tw <= 0 || th <= 0) return false;
        int tx = x, ty = y, rx = r.x, ry = r.y;
        rw += rx; rh += ry; tw += tx; th += ty;
        return (rw < rx || rw > tx) && (rh < ry || rh > ty) && (tw < tx || tw > rx) && (th < ty || th > ry);
    }
    public Rectangle intersection(Rectangle r) {
        int x1 = Math.max(x, r.x), y1 = Math.max(y, r.y);
        int x2 = Math.min(x + width, r.x + r.width), y2 = Math.min(y + height, r.y + r.height);
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }
    public Rectangle union(Rectangle r) {
        int x1 = Math.min(x, r.x), y1 = Math.min(y, r.y);
        int x2 = Math.max(x + width, r.x + r.width), y2 = Math.max(y + height, r.y + r.height);
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Rectangle)) return false;
        Rectangle r = (Rectangle) o;
        return x == r.x && y == r.y && width == r.width && height == r.height;
    }
    @Override public int hashCode() { return ((x * 31 + y) * 31 + width) * 31 + height; }
    @Override public Object clone() { return new Rectangle(this); }
    @Override public String toString() { return getClass().getName() + "[x=" + x + ",y=" + y + ",width=" + width + ",height=" + height + "]"; }
}
