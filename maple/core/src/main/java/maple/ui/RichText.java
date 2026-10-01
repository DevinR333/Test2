package maple.ui;

import maple.gfx.Sprite;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MapleStory script text (CUtilDlgEx 009a2de0): #b #r #g #d #k colours, #e/#n bold, #L<n># ... #l
 * choices with the dot0/dot1 marker, #p #o #m #t #z #q #h #c #a names and counts, #i #v #s item/skill
 * icons, #f/#F WZ images and ## for a literal '#'. Laid out by word into fixed-height lines.
 */
public final class RichText {
    public static final int BLACK = 0xFF000000, BLUE = 0xFF0000FF, RED = 0xFFFF0000, GREEN = 0xFF00FF00, PURPLE = 0xFF8000FF;
    private static final Pattern TOKEN = Pattern.compile(
            "#L\\d+#|#l|#h(?:0| )?#|#@\\d+:#|#[ptomciavuyzsqB]\\d+:?#|#[bgrdken]|#[fFW][^#\\r\\n]+#|##|#(?=\\n|$)");
    public static final float LINE = 18;

    /** Resolves names and pictures for the tokens. */
    public interface Resolver {
        /** Text for #p #o #m #t #z #q #h #c #a #@ #u #y (code, id). */
        String text(char code, int id);
        /** Picture for #i #v (items), #s (skills), #f #F #W (paths). */
        Sprite image(char code, String arg);
    }

    private static final class Piece {
        String text;
        Sprite img;
        int color;
        boolean bold;
        int choice = -1;
        boolean marker;
        float x, y, w, h;
        int line;
    }

    private final List<Piece> pieces = new ArrayList<>();
    private final List<float[]> choiceRects = new ArrayList<>(); // x, y, w, h, choice
    public float height;
    public int choiceCount;
    public final List<Integer> choices = new ArrayList<>();

    private RichText() {}

    public static RichText layout(UiDraw g, String raw, float width, Resolver r, int baseColor) {
        RichText t = new RichText();
        String s = raw.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n").replace("\r\n", "\n").replace("\r", "\n");
        List<Piece> atoms = new ArrayList<>();
        int color = baseColor;
        boolean bold = false;
        int choice = -1;
        Matcher m = TOKEN.matcher(s);
        int pos = 0;
        while (m.find()) {
            addText(atoms, s.substring(pos, m.start()), color, bold, choice);
            String tok = m.group();
            pos = m.end();
            char c = tok.length() > 1 ? tok.charAt(1) : '#';
            if (tok.startsWith("#L")) {
                choice = Integer.parseInt(tok.substring(2, tok.length() - 1));
                t.choices.add(choice);
                Piece p = new Piece();
                p.marker = true;
                p.choice = choice;
                p.w = 18;
                p.h = 12;
                atoms.add(p);
            } else if (tok.equals("#l")) {
                choice = -1;
            } else if (tok.equals("##")) {
                addText(atoms, "#", color, bold, choice);
            } else if (tok.equals("#")) {
                // stray terminator
            } else if (tok.length() == 2 && "bgrdk".indexOf(c) >= 0) {
                color = c == 'b' ? BLUE : c == 'r' ? RED : c == 'g' ? GREEN : c == 'd' ? PURPLE : baseColor;
            } else if (tok.equals("#e")) {
                bold = true;
            } else if (tok.equals("#n")) {
                bold = false;
            } else if (c == 'i' || c == 'v' || c == 's' || c == 'f' || c == 'F' || c == 'W') {
                String arg = tok.substring(2, tok.length() - 1);
                if (arg.endsWith(":")) arg = arg.substring(0, arg.length() - 1);
                Sprite img = r.image(c, arg);
                if (img != null) {
                    Piece p = new Piece();
                    p.img = img;
                    p.w = img.w;
                    p.h = img.h;
                    p.choice = choice;
                    atoms.add(p);
                }
            } else if (c == 'B') {
                // progress bar: not used by v83 scripts' visible text
            } else if (c == 'h') {
                addText(atoms, r.text('h', 0), color, bold, choice);
            } else {
                String digits = tok.replaceAll("[^0-9]", "");
                int id = digits.isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, Long.parseLong(digits));
                String v = r.text(c, id);
                addText(atoms, v == null ? tok : v, color, bold, choice);
            }
        }
        addText(atoms, s.substring(pos), color, bold, choice);
        t.place(g, atoms, width);
        return t;
    }

    private static void addText(List<Piece> atoms, String text, int color, boolean bold, int choice) {
        if (text == null || text.isEmpty()) return;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            int j = i;
            if (ch == '\n') {
                Piece p = new Piece();
                p.text = "\n";
                atoms.add(p);
                i++;
                continue;
            }
            if (ch == ' ') {
                while (j < text.length() && text.charAt(j) == ' ') j++;
            } else {
                while (j < text.length() && text.charAt(j) != ' ' && text.charAt(j) != '\n') j++;
            }
            Piece p = new Piece();
            p.text = text.substring(i, j);
            p.color = color;
            p.bold = bold;
            p.choice = choice;
            atoms.add(p);
            i = j;
        }
    }

    private void place(UiDraw g, List<Piece> atoms, float width) {
        float x = 0;
        int line = 0;
        List<Float> lineHeights = new ArrayList<>();
        lineHeights.add(LINE);
        for (Piece a : atoms) {
            if ("\n".equals(a.text)) {
                line++;
                lineHeights.add(LINE);
                x = 0;
                continue;
            }
            if (a.text != null) a.w = g.textWidth(a.text, 12, a.bold);
            boolean space = a.text != null && a.text.trim().isEmpty();
            if (x > 0 && x + a.w > width && !space) {
                line++;
                lineHeights.add(LINE);
                x = 0;
            }
            if (x == 0 && space && line > 0) continue; // no leading spaces on wrapped lines
            if (a.text != null && a.w > width) {
                // a word longer than the line: break it by characters
                String rest = a.text;
                while (!rest.isEmpty()) {
                    int n = rest.length();
                    while (n > 1 && g.textWidth(rest.substring(0, n), 12, a.bold) > width - x) n--;
                    Piece p = copy(a, rest.substring(0, n));
                    p.w = g.textWidth(p.text, 12, p.bold);
                    p.x = x;
                    p.line = line;
                    pieces.add(p);
                    rest = rest.substring(n);
                    if (!rest.isEmpty()) {
                        line++;
                        lineHeights.add(LINE);
                        x = 0;
                    } else x += p.w;
                }
                continue;
            }
            a.x = x;
            a.line = line;
            pieces.add(a);
            x += a.w;
            if (a.img != null) lineHeights.set(line, Math.max(lineHeights.get(line), a.h + 2));
        }
        float[] tops = new float[lineHeights.size()];
        float y = 0;
        for (int i = 0; i < tops.length; i++) {
            tops[i] = y;
            y += lineHeights.get(i);
        }
        height = y;
        for (Piece p : pieces) {
            float lh = lineHeights.get(p.line);
            if (p.img != null) p.y = tops[p.line] + (lh - p.h) / 2f;
            else p.y = tops[p.line] + (lh - LINE) / 2f + 3;
            if (p.text != null) p.h = 13;
            if (p.choice >= 0) choiceRects.add(new float[]{p.x, tops[p.line], p.w, lh, p.choice});
        }
        choiceCount = choices.size();
    }

    private static Piece copy(Piece a, String text) {
        Piece p = new Piece();
        p.text = text;
        p.color = a.color;
        p.bold = a.bold;
        p.choice = a.choice;
        return p;
    }

    /** Draws with the top-left at (x, y); only lines inside [clipTop, clipBottom) (local y) are drawn. */
    public void draw(UiDraw g, float x, float y, int hoverChoice, float clipTop, float clipBottom) {
        for (Piece p : pieces) {
            if (p.y + (p.img != null ? p.h : LINE - 4) <= clipTop || p.y >= clipBottom) continue;
            if (p.marker) {
                Sprite dot = g.assets.sprite("UIWindow.img/UtilDlgEx/" + (p.choice == hoverChoice ? "dot1" : "dot0"));
                if (dot != null) g.image(dot, x + p.x + 10, y + p.y + 2);
            } else if (p.img != null) {
                g.image(p.img, x + p.x, y + p.y);
            } else if (!p.text.trim().isEmpty()) {
                g.text(p.text, x + p.x, y + p.y, 12, p.bold, p.color);
            }
        }
    }

    /** The choice under a local point, or -1. */
    public int choiceAt(float lx, float ly) {
        for (float[] r : choiceRects) {
            if (lx >= r[0] && lx < r[0] + r[2] && ly >= r[1] && ly < r[1] + r[3]) return (int) r[4];
        }
        return -1;
    }
}
