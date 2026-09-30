package compat.awt.geom;

/** Drop-in for the part of java.awt.geom.Line2D the server uses. */
public abstract class Line2D {
    public abstract double getX1();
    public abstract double getY1();
    public abstract double getX2();
    public abstract double getY2();

    public static class Float extends Line2D {
        public float x1, y1, x2, y2;
        public Float() {}
        public Float(float x1, float y1, float x2, float y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }
        @Override public double getX1() { return x1; }
        @Override public double getY1() { return y1; }
        @Override public double getX2() { return x2; }
        @Override public double getY2() { return y2; }
    }
}
