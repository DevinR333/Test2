package maple.ui;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Align;
import maple.gfx.Animation;
import maple.gfx.Sprite;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small reusable widgets: images, animations, text and a text field. */
public final class Widgets {
    private Widgets() {}

    /** A WZ canvas, drawn with its top-left at (x, y) (or its origin there when anchored). */
    public static class Image extends Widget {
        public Sprite sprite;
        public boolean anchored;

        public Image(Sprite s, float x, float y, boolean anchored) {
            this.sprite = s;
            this.x = x;
            this.y = y;
            this.anchored = anchored;
            if (s != null) {
                w = s.w;
                h = s.h;
            }
        }

        @Override
        public void draw(UiDraw g) {
            if (sprite == null) return;
            if (anchored) g.anchored(sprite, 0, 0);
            else g.image(sprite, 0, 0);
        }
    }

    /** A WZ animation with its origin at (x, y). once = holds its last frame. */
    public static class Anim extends Widget {
        public Animation anim;
        public boolean once;
        private long t;

        public Anim(Animation a, float x, float y, boolean once) {
            this.anim = a;
            this.x = x;
            this.y = y;
            this.once = once;
        }

        public void restart() { t = 0; }

        @Override
        public void update(long ms) {
            t += ms;
        }

        @Override
        public void draw(UiDraw g) {
            if (anim == null || anim.isEmpty()) return;
            long time = once ? Math.min(t, anim.durationMs() - 1) : t;
            g.anim(anim, 0, 0, time);
        }
    }

    /** Text from a supplier, re-read every frame. */
    public static class Label extends Widget {
        public Supplier<String> text;
        public int size = 12, color = 0xFF000000, align = Align.left;
        public boolean bold, wrap;

        public Label(float x, float y, float w, Supplier<String> text) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.text = text;
        }

        public Label style(int size, int color, boolean bold) {
            this.size = size;
            this.color = color;
            this.bold = bold;
            return this;
        }

        public Label align(int a) {
            this.align = a;
            return this;
        }

        @Override
        public void draw(UiDraw g) {
            String s = text.get();
            if (s == null || s.isEmpty()) return;
            if (w > 0) {
                if (!wrap) s = clip(g, s, w);
                g.text(s, 0, 0, w, align, wrap, size, bold, color);
            } else {
                g.text(s, 0, 0, size, bold, color);
            }
        }

        private String clip(UiDraw g, String s, float max) {
            if (g.textWidth(s, size, bold) <= max) return s;
            while (s.length() > 1 && g.textWidth(s + "..", size, bold) > max) s = s.substring(0, s.length() - 1);
            return s + "..";
        }
    }

    /** Filled rectangle. */
    public static class Rect extends Widget {
        public int argb;

        public Rect(float x, float y, float w, float h, int argb) {
            super(x, y, w, h);
            this.argb = argb;
        }

        @Override
        public void draw(UiDraw g) {
            g.fill(0, 0, w, h, argb);
        }
    }

    /**
     * A one-line text box. Typing on a keyboard edits it directly; tapping it on a phone opens the
     * system keyboard dialog.
     */
    public static class TextField extends Widget implements Focusable {
        public String text = "";
        public int maxLength = 12;
        public int size = 12, color = 0xFF000000;
        public boolean center;
        public String hint = "";
        public Consumer<String> onEnter;
        private boolean focused;
        private long blink;
        private boolean dialogOpen;
        public java.util.function.Predicate<Character> allowed = c -> c >= 32 && c < 127;
        /** The text starts selected (like the original edit boxes): the first key typed replaces it. */
        public boolean selected;

        public TextField(float x, float y, float w, float h) {
            super(x, y, w, h);
        }

        @Override
        public boolean interactive() { return true; }

        @Override
        public boolean onPress(float lx, float ly) {
            Focus.set(this);
            if (Gdx.app != null && Gdx.app.getType() != Application.ApplicationType.Desktop && !dialogOpen) {
                dialogOpen = true;
                Gdx.input.getTextInput(new Input.TextInputListener() {
                    @Override
                    public void input(String s) {
                        StringBuilder b = new StringBuilder();
                        for (char ch : s.toCharArray()) if (allowed.test(ch) && b.length() < maxLength) b.append(ch);
                        text = b.toString();
                        dialogOpen = false;
                        if (onEnter != null) onEnter.accept(text);
                    }

                    @Override
                    public void canceled() {
                        dialogOpen = false;
                    }
                }, hint.isEmpty() ? "Enter text" : hint, selected ? "" : text, selected ? text : "");
                selected = false;
            }
            return false;
        }

        @Override
        public void setFocused(boolean f) { focused = f; }

        @Override
        public boolean keyTyped(char c) {
            if (selected && c != '\r' && c != '\n') {
                selected = false;
                text = ""; // typing replaces the selected text (backspace just clears it)
                if (c == '\b') return true;
            }
            if (c == '\b') {
                if (!text.isEmpty()) text = text.substring(0, text.length() - 1);
                return true;
            }
            if (c == '\r' || c == '\n') {
                if (onEnter != null) onEnter.accept(text);
                return true;
            }
            if (allowed.test(c) && text.length() < maxLength) text += c;
            return true;
        }

        @Override
        public void update(long ms) {
            blink += ms;
        }

        @Override
        public void draw(UiDraw g) {
            float tw = g.textWidth(text, size, false);
            float tx = center ? (w - tw) / 2 : 1;
            if (selected && !text.isEmpty()) {
                g.fill(tx, 1, tw + 1, h - 2, 0xFF3060C0); // selected: white on blue
                g.text(text, tx, (h - size) / 2f - 1, size, false, 0xFFFFFFFF);
                return;
            }
            g.text(text, tx, (h - size) / 2f - 1, size, false, color);
            if (focused && (blink / 500) % 2 == 0) g.fill(tx + tw + 1, 1, 1, h - 2, color);
        }
    }

    /** Keyboard focus for text fields. */
    public interface Focusable {
        void setFocused(boolean f);
        boolean keyTyped(char c);
    }

    public static final class Focus {
        private static Focusable current;

        private Focus() {}

        public static void set(Focusable f) {
            if (current == f) return;
            if (current != null) current.setFocused(false);
            current = f;
            if (f != null) f.setFocused(true);
        }

        public static Focusable get() { return current; }

        public static boolean keyTyped(char c) {
            return current != null && current.keyTyped(c);
        }
    }
}
