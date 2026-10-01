package maple.ui.hud;

import com.badlogic.gdx.Input;
import maple.ui.Button;
import maple.ui.Ui;
import maple.ui.UiDraw;
import maple.ui.Widget;
import maple.ui.Widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The chat area of the status bar (openms' recovery of 008d536c / 008dfb36): the ComboBox2 chat target
 * at (1,537), the edit at (85,542) 440 wide, BtMax/BtMin at (536,541), and the log above the bar,
 * which expands with a 50% black backing.
 */
public final class ChatBar extends Widget {
    private static final int MAX_LINES = 200;
    private final Ui ui;
    private final List<String> lines = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private boolean expanded;
    private int expandedHeight = 70;
    private int scroll; // lines scrolled up from the newest
    public final Widgets.TextField input;
    private final Button max, min;
    private final Consumer<String> send;
    public boolean typing;
    /** Chat target: 0 all, 1 whisper, 2 party, 3 buddy, 4 guild, 5 alliance (the ComboBox2 list). */
    public int target;
    public String whisperTo = "";
    private static final String[] TARGETS = {"To All", "Whisper", "To Party", "To Buddy", "To Guild", "To Alliance"};
    private boolean listOpen;

    public ChatBar(Ui ui, Consumer<String> send) {
        this.ui = ui;
        this.send = send;
        w = 800;
        h = 600;
        input = add(new Widgets.TextField(85, 542, 440, 12));
        input.maxLength = 70;
        input.hint = "Chat";
        input.onEnter = this::submit;
        max = add(new Button(ui.assets, "Basic.img/BtMax", 536, 519 + 22, () -> {
            expanded = true;
            updateButtons();
        }));
        min = add(new Button(ui.assets, "Basic.img/BtMin", 536, 519 + 22, () -> {
            expanded = false;
            updateButtons();
        }));
        updateButtons();
    }

    private void updateButtons() {
        max.visible = !expanded;
        min.visible = expanded;
        scroll = 0;
    }

    private void submit(String text) {
        text = text.trim();
        input.text = "";
        typing = false;
        Widgets.Focus.set(null);
        if (!text.isEmpty()) send.accept(text);
    }

    /** Picks the chat target (and opens the edit), e.g. from the Friends window or a chat key. */
    public void setTarget(int t, String whisper) {
        target = Math.max(0, Math.min(TARGETS.length - 1, t));
        if (whisper != null) whisperTo = whisper;
        beginTyping();
    }

    /** Enter opens the chat edit; pressing it again sends. */
    public void beginTyping() {
        typing = true;
        Widgets.Focus.set(input);
        input.onPress(0, 0); // phones: open the keyboard dialog
    }

    public boolean keyDown(int key) {
        if (!typing) return false;
        if (key == Input.Keys.ESCAPE) {
            typing = false;
            input.text = "";
            Widgets.Focus.set(null);
            return true;
        }
        return true; // typing: the game ignores keys
    }

    /** Keeps the log when the HUD is rebuilt (screen shape change). */
    public void copyLog(ChatBar other) {
        lines.addAll(other.lines);
        colors.addAll(other.colors);
        expanded = other.expanded;
        updateButtons();
    }

    public void toggleExpanded() {
        expanded = !expanded;
        updateButtons();
    }

    public void add(String text, int color) {
        for (String part : text.split("\n")) {
            lines.add(part);
            colors.add(color);
        }
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
            colors.remove(0);
        }
        scroll = 0;
    }

    @Override
    public Widget hit(float lx, float ly) {
        Widget h = super.hit(lx, ly);
        if (h != null) return h;
        if (expanded && lx >= 4 && lx < 570 && ly >= logTop() && ly < 532) return this;
        if (lx >= 85 && lx < 525 && ly >= 538 && ly < 558) return input;
        if (lx >= 1 && lx < 81 && ly >= 537 && ly < 557) return this;
        if (listOpen && lx >= 1 && lx < 81 && ly >= 537 - TARGETS.length * 16 && ly < 537) return this;
        return null;
    }

    private float logTop() {
        return 532 - expandedHeight;
    }

    @Override
    public boolean interactive() { return true; }

    @Override
    public boolean onScroll(float lx, float ly, int amount) {
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - 1), scroll - amount));
        return true;
    }

    private float dragStartY;
    private int dragStartScroll;

    @Override
    public boolean onPress(float lx, float ly) {
        if (listOpen) {
            listOpen = false;
            int i = (int) ((ly - (537 - TARGETS.length * 16)) / 16);
            if (lx >= 1 && lx < 81 && i >= 0 && i < TARGETS.length) {
                target = i;
                if (target == 1 && whisperTo.isEmpty()) whisperTo = "";
                beginTyping();
            }
            return false;
        }
        if (lx >= 1 && lx < 81 && ly >= 537 && ly < 557) {
            listOpen = true;
            return false;
        }
        dragStartY = ly;
        dragStartScroll = scroll;
        return true;
    }

    @Override
    public void onDrag(float lx, float ly) {
        int d = (int) ((ly - dragStartY) / 13);
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - 1), dragStartScroll + d));
    }

    @Override
    public void draw(UiDraw g) {
        // chat target (To All)
        g.image("Basic.img/ComboBox2/normal/0", 1, 515 + 22);
        String label = target == 1 && !whisperTo.isEmpty() ? whisperTo : TARGETS[target];
        g.text(label, 7, 515 + 22 + 3, 11, false, 0xFFFFFFFF);
        if (listOpen) {
            for (int i = 0; i < TARGETS.length; i++) {
                float ry = 537 - (TARGETS.length - i) * 16;
                g.fill(1, ry, 80, 16, i == target ? 0xF0405A78 : 0xE0202838);
                g.text(TARGETS[i], 7, ry + 2, 11, false, 0xFFFFFFFF);
            }
        }
        if (typing) g.fill(85, 541, 440, 14, 0xFFFFFFFF);
        int rows;
        float top;
        if (expanded) {
            top = logTop();
            g.fill(4, top, 566, expandedHeight, 0x80000000);
            rows = expandedHeight / 13;
        } else {
            top = 486 + 22;
            rows = 2;
        }
        int end = lines.size() - scroll;
        int start = Math.max(0, end - rows);
        float y = top + (rows - (end - start)) * 13;
        for (int i = start; i < end; i++) {
            if (!expanded) g.outlined(lines.get(i), 6, y, 12, false, colors.get(i), 0xA0000000);
            else g.text(lines.get(i), 6, y, 12, false, colors.get(i));
            y += 13;
        }
        drawChildren(g);
    }
}
