package maple.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Touch + keyboard input. Left thumb: a D-pad. Right thumb: JUMP (and UP for portals).
 * Keyboard: arrows to move, Alt/Space/C to jump, Up for portals and ladders.
 */
public final class Controls {
    public boolean left, right, up, down, jump;
    public boolean upPressed, downPressed, jumpPressed;
    private boolean prevUp, prevDown, prevJump;

    // Layout in UI units (screen pixels / uiScale), recomputed each frame.
    float padX, padY, padR, jumpX, jumpY, jumpR, upX, upY, upR;
    private float uiScale = 1;
    public boolean showTouch = true;

    /** Buttons along the top-right that the game reacts to. */
    public final Button mapsButton = new Button("MAPS");
    public final Button debugButton = new Button("DEBUG");

    public static final class Button {
        public final String label;
        float x, y, w, h;
        public boolean clicked;
        boolean held;

        Button(String label) { this.label = label; }

        boolean hit(float px, float py) { return px >= x && px <= x + w && py >= y && py <= y + h; }
    }

    public void layout(float w, float h, float scale) {
        uiScale = scale;
        float uw = w / scale, uh = h / scale;
        padR = 95;
        padX = 40 + padR;
        padY = uh - 40 - padR;
        jumpR = 62;
        jumpX = uw - 40 - jumpR;
        jumpY = uh - 45 - jumpR;
        upR = 42;
        upX = jumpX - jumpR - 40 - upR;
        upY = uh - 40 - upR;
        float bw = 90, bh = 40;
        mapsButton.x = uw - bw - 12;
        mapsButton.y = 12;
        mapsButton.w = bw;
        mapsButton.h = bh;
        debugButton.x = uw - 2 * bw - 22;
        debugButton.y = 12;
        debugButton.w = bw;
        debugButton.h = bh;
    }

    public void poll() {
        boolean l = false, r = false, u = false, d = false, j = false;
        Input in = Gdx.input;
        if (in.isKeyPressed(Input.Keys.LEFT) || in.isKeyPressed(Input.Keys.A)) l = true;
        if (in.isKeyPressed(Input.Keys.RIGHT) || in.isKeyPressed(Input.Keys.D)) r = true;
        if (in.isKeyPressed(Input.Keys.UP) || in.isKeyPressed(Input.Keys.W)) u = true;
        if (in.isKeyPressed(Input.Keys.DOWN) || in.isKeyPressed(Input.Keys.S)) d = true;
        if (in.isKeyPressed(Input.Keys.ALT_LEFT) || in.isKeyPressed(Input.Keys.ALT_RIGHT)
                || in.isKeyPressed(Input.Keys.SPACE) || in.isKeyPressed(Input.Keys.C)) j = true;

        boolean mapsHeld = false, debugHeld = false;
        for (int i = 0; i < 10; i++) {
            if (!in.isTouched(i)) continue;
            float x = in.getX(i) / uiScale, y = in.getY(i) / uiScale;
            if (mapsButton.hit(x, y)) { mapsHeld = true; continue; }
            if (debugButton.hit(x, y)) { debugHeld = true; continue; }
            float dx = x - padX, dy = y - padY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < padR * 1.6f && x < jumpX - jumpR - 60 && x < upX - upR) {
                if (dist > padR * 0.22f) {
                    double ang = Math.toDegrees(Math.atan2(dy, dx)); // 0 = right, 90 = down
                    if (ang > -67.5 && ang < 67.5) r = true;
                    if (ang > 112.5 || ang < -112.5) l = true;
                    if (ang > 22.5 && ang < 157.5) d = true;
                    if (ang < -22.5 && ang > -157.5) u = true;
                }
                continue;
            }
            float jx = x - jumpX, jy = y - jumpY;
            if (jx * jx + jy * jy < jumpR * jumpR * 1.7f) { j = true; continue; }
            float ux = x - upX, uy = y - upY;
            if (ux * ux + uy * uy < upR * upR * 1.7f) { u = true; continue; }
        }
        mapsButton.clicked = mapsHeld && !mapsButton.held;
        mapsButton.held = mapsHeld;
        debugButton.clicked = debugHeld && !debugButton.held;
        debugButton.held = debugHeld;
        if (in.isKeyJustPressed(Input.Keys.M)) mapsButton.clicked = true;
        if (in.isKeyJustPressed(Input.Keys.F3)) debugButton.clicked = true;

        left = l; right = r; up = u; down = d; jump = j;
        upPressed = u && !prevUp;
        downPressed = d && !prevDown;
        jumpPressed = j && !prevJump;
        prevUp = u; prevDown = d; prevJump = j;
    }

    /** Consume edge flags after the first physics tick of a frame so they fire once. */
    public void consumeEdges() {
        upPressed = downPressed = jumpPressed = false;
    }

    public void draw(ShapeRenderer sr, SpriteBatch batch, BitmapFont font) {
        if (!showTouch) {
            drawButtons(sr, batch, font);
            return;
        }
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(0, 0, 0, 0.25f);
        sr.circle(padX, padY, padR, 48);
        sr.setColor(1, 1, 1, 0.22f);
        float a = padR * 0.62f, s = padR * 0.26f;
        arrow(sr, padX + a, padY, s, 0, left && !right ? 0.22f : right ? 0.55f : 0.22f);
        arrow(sr, padX - a, padY, s, 180, left ? 0.55f : 0.22f);
        arrow(sr, padX, padY - a, s, 270, up ? 0.55f : 0.22f);
        arrow(sr, padX, padY + a, s, 90, down ? 0.55f : 0.22f);
        sr.setColor(1, 1, 1, jump ? 0.45f : 0.22f);
        sr.circle(jumpX, jumpY, jumpR, 48);
        sr.setColor(1, 1, 1, up ? 0.45f : 0.18f);
        sr.circle(upX, upY, upR, 40);
        sr.end();
        batch.begin();
        font.setColor(1, 1, 1, 0.85f);
        center(batch, font, "JUMP", jumpX, jumpY);
        center(batch, font, "UP", upX, upY);
        batch.end();
        drawButtons(sr, batch, font);
    }

    private void drawButtons(ShapeRenderer sr, SpriteBatch batch, BitmapFont font) {
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (Button b : new Button[]{mapsButton, debugButton}) {
            sr.setColor(0, 0, 0, b.held ? 0.6f : 0.4f);
            sr.rect(b.x, b.y, b.w, b.h);
        }
        sr.end();
        batch.begin();
        font.setColor(Color.WHITE);
        for (Button b : new Button[]{mapsButton, debugButton}) center(batch, font, b.label, b.x + b.w / 2, b.y + b.h / 2);
        batch.end();
    }

    private static void arrow(ShapeRenderer sr, float cx, float cy, float s, float deg, float alpha) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), n = (float) Math.sin(r);
        float tipX = cx + c * s, tipY = cy + n * s;
        float bx = cx - c * s * 0.6f, by = cy - n * s * 0.6f;
        float px = -n * s * 0.8f, py = c * s * 0.8f;
        sr.setColor(1, 1, 1, alpha);
        sr.triangle(tipX, tipY, bx + px, by + py, bx - px, by - py);
    }

    private static final GlyphLayout LAYOUT = new GlyphLayout();

    static void center(SpriteBatch batch, BitmapFont font, String text, float x, float y) {
        LAYOUT.setText(font, text);
        font.draw(batch, text, x - LAYOUT.width / 2, y - LAYOUT.height / 2);
    }
}
