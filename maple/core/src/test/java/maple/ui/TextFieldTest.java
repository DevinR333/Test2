package maple.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Quantity boxes start with their default selected: typing replaces it (typing 8 gives 8, not 18). */
public class TextFieldTest {
    @Test
    public void typingReplacesTheSelectedDefault() {
        Widgets.TextField f = new Widgets.TextField(0, 0, 100, 15);
        f.allowed = Character::isDigit;
        f.text = "1";
        f.selected = true;
        f.keyTyped('8');
        assertEquals("8", f.text);
        f.keyTyped('0');
        assertEquals("80", f.text);
    }

    @Test
    public void backspaceClearsTheSelection() {
        Widgets.TextField f = new Widgets.TextField(0, 0, 100, 15);
        f.text = "1";
        f.selected = true;
        f.keyTyped('\b');
        assertEquals("", f.text);
    }

    @Test
    public void withoutSelectionTypingAppends() {
        Widgets.TextField f = new Widgets.TextField(0, 0, 100, 15);
        f.text = "1";
        f.keyTyped('8');
        assertEquals("18", f.text);
    }
}
