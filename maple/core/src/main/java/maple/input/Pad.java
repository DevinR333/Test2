package maple.input;

/** What the character reads each physics tick: held directions and jump, plus press edges. */
public final class Pad {
    public boolean left, right, up, down, jump;
    public boolean upPressed, downPressed, jumpPressed;

    /** Edge flags fire on the first physics tick of a frame only. */
    public void consumeEdges() {
        upPressed = downPressed = jumpPressed = false;
    }
}
