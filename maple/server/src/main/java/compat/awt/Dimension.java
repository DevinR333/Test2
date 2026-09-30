package compat.awt;

/** Drop-in for java.awt.Dimension. */
public class Dimension implements java.io.Serializable {
    public int width, height;
    public Dimension() {}
    public Dimension(int width, int height) { this.width = width; this.height = height; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
}
