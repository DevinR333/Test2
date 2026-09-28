package com.fable2.recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen Xbox 360 controller: A/B/X/Y, LB/RB, LT/RT, d-pad, Start/Back,
 * both analog sticks and L3/R3. Multi-touch; each finger owns the control it
 * first touched (sticks keep tracking while the finger drags outside them).
 * The full pad state is pushed to the native TouchGamepadDriver on change.
 */
public class TouchControlsView extends View {

    // XINPUT_GAMEPAD_* button bits.
    static final int DPAD_UP = 0x0001;
    static final int DPAD_DOWN = 0x0002;
    static final int DPAD_LEFT = 0x0004;
    static final int DPAD_RIGHT = 0x0008;
    static final int START = 0x0010;
    static final int BACK = 0x0020;
    static final int LEFT_THUMB = 0x0040;
    static final int RIGHT_THUMB = 0x0080;
    static final int LEFT_SHOULDER = 0x0100;
    static final int RIGHT_SHOULDER = 0x0200;
    static final int A = 0x1000;
    static final int B = 0x2000;
    static final int X = 0x4000;
    static final int Y = 0x8000;

    static native void nativeSetState(int buttons, int leftTrigger, int rightTrigger,
                                      int lx, int ly, int rx, int ry);

    /** Something drawn on screen that a finger can hold. */
    private abstract static class Control {
        int pointerId = -1;
        abstract boolean hit(float x, float y);
        abstract void draw(Canvas canvas);
        void onDown(float x, float y) {}
        void onMove(float x, float y) {}
        void onUp() {}
    }

    private class RoundButton extends Control {
        final String label;
        final int bit;          // button bit, or 0 for a trigger
        final char trigger;     // 'L' / 'R' for LT / RT
        final int color;
        float cx, cy, r;
        boolean held;

        RoundButton(String label, int bit, char trigger, int color) {
            this.label = label;
            this.bit = bit;
            this.trigger = trigger;
            this.color = color;
        }

        void place(float cx, float cy, float r) {
            this.cx = cx;
            this.cy = cy;
            this.r = r;
        }

        @Override
        boolean hit(float x, float y) {
            float dx = x - cx, dy = y - cy;
            float reach = r * 1.25f;
            return dx * dx + dy * dy <= reach * reach;
        }

        @Override
        void onDown(float x, float y) {
            held = true;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }

        @Override
        void onUp() {
            held = false;
        }

        @Override
        void draw(Canvas canvas) {
            fill.setColor(held ? color : withAlpha(color, 110));
            canvas.drawCircle(cx, cy, r, fill);
            stroke.setColor(withAlpha(Color.WHITE, held ? 230 : 140));
            canvas.drawCircle(cx, cy, r, stroke);
            drawLabel(canvas, label, cx, cy, r * 0.8f);
        }
    }

    private class PillButton extends RoundButton {
        float w, h;

        PillButton(String label, int bit, char trigger) {
            super(label, bit, trigger, Color.rgb(90, 90, 100));
        }

        void placeRect(float cx, float cy, float w, float h) {
            place(cx, cy, h / 2);
            this.w = w;
            this.h = h;
        }

        @Override
        boolean hit(float x, float y) {
            float slop = h * 0.25f;
            return Math.abs(x - cx) <= w / 2 + slop && Math.abs(y - cy) <= h / 2 + slop;
        }

        @Override
        void draw(Canvas canvas) {
            RectF rect = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            fill.setColor(held ? withAlpha(Color.WHITE, 170) : withAlpha(color, 120));
            canvas.drawRoundRect(rect, h / 2, h / 2, fill);
            stroke.setColor(withAlpha(Color.WHITE, held ? 230 : 140));
            canvas.drawRoundRect(rect, h / 2, h / 2, stroke);
            drawLabel(canvas, label, cx, cy, h * 0.55f);
        }
    }

    private class Stick extends Control {
        final String label;
        float cx, cy, radius;   // base
        float kx, ky;           // knob offset from base, px
        float valueX, valueY;   // -1..1, +Y = up

        Stick(String label) {
            this.label = label;
        }

        void place(float cx, float cy, float radius) {
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
        }

        @Override
        boolean hit(float x, float y) {
            float dx = x - cx, dy = y - cy;
            float reach = radius * 1.6f;
            return dx * dx + dy * dy <= reach * reach;
        }

        @Override
        void onDown(float x, float y) {
            onMove(x, y);
        }

        @Override
        void onMove(float x, float y) {
            float dx = x - cx, dy = y - cy;
            float len = (float) Math.hypot(dx, dy);
            if (len > radius) {
                dx = dx / len * radius;
                dy = dy / len * radius;
            }
            kx = dx;
            ky = dy;
            valueX = dx / radius;
            valueY = -dy / radius;
        }

        @Override
        void onUp() {
            kx = ky = 0;
            valueX = valueY = 0;
        }

        @Override
        void draw(Canvas canvas) {
            fill.setColor(withAlpha(Color.rgb(60, 60, 70), 110));
            canvas.drawCircle(cx, cy, radius, fill);
            stroke.setColor(withAlpha(Color.WHITE, 120));
            canvas.drawCircle(cx, cy, radius, stroke);
            boolean active = pointerId != -1;
            fill.setColor(withAlpha(Color.rgb(200, 200, 210), active ? 210 : 140));
            canvas.drawCircle(cx + kx, cy + ky, radius * 0.45f, fill);
            drawLabel(canvas, label, cx + kx, cy + ky, radius * 0.3f);
        }

        int axis(float v) {
            float dead = 0.08f;
            if (Math.abs(v) < dead) {
                return 0;
            }
            return Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Stick leftStick = new Stick("L");
    private final Stick rightStick = new Stick("R");
    private final RoundButton btnA = new RoundButton("A", A, '\0', Color.rgb(96, 180, 60));
    private final RoundButton btnB = new RoundButton("B", B, '\0', Color.rgb(220, 60, 50));
    private final RoundButton btnX = new RoundButton("X", X, '\0', Color.rgb(50, 110, 220));
    private final RoundButton btnY = new RoundButton("Y", Y, '\0', Color.rgb(235, 190, 40));
    private final RoundButton dUp = new RoundButton("▲", DPAD_UP, '\0', Color.rgb(90, 90, 100));
    private final RoundButton dDown = new RoundButton("▼", DPAD_DOWN, '\0', Color.rgb(90, 90, 100));
    private final RoundButton dLeft = new RoundButton("◀", DPAD_LEFT, '\0', Color.rgb(90, 90, 100));
    private final RoundButton dRight = new RoundButton("▶", DPAD_RIGHT, '\0', Color.rgb(90, 90, 100));
    private final RoundButton l3 = new RoundButton("L3", LEFT_THUMB, '\0', Color.rgb(90, 90, 100));
    private final RoundButton r3 = new RoundButton("R3", RIGHT_THUMB, '\0', Color.rgb(90, 90, 100));
    private final PillButton lt = new PillButton("LT", 0, 'L');
    private final PillButton rt = new PillButton("RT", 0, 'R');
    private final PillButton lb = new PillButton("LB", LEFT_SHOULDER, '\0');
    private final PillButton rb = new PillButton("RB", RIGHT_SHOULDER, '\0');
    private final PillButton back = new PillButton("BACK", BACK, '\0');
    private final PillButton start = new PillButton("START", START, '\0');
    private final PillButton toggle = new PillButton("◎", 0, '\0');

    private final List<Control> controls = new ArrayList<>();
    private boolean controlsVisible = true;
    private int lastButtons, lastLt, lastRt, lastLx, lastLy, lastRx, lastRy;

    public TouchControlsView(Context context) {
        super(context);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);

        // Hit-test order: buttons before sticks, so a finger near both goes to
        // the smaller target (sticks have a generous grab radius).
        controls.add(btnA);
        controls.add(btnB);
        controls.add(btnX);
        controls.add(btnY);
        controls.add(dUp);
        controls.add(dDown);
        controls.add(dLeft);
        controls.add(dRight);
        controls.add(l3);
        controls.add(r3);
        controls.add(lt);
        controls.add(rt);
        controls.add(lb);
        controls.add(rb);
        controls.add(back);
        controls.add(start);
        controls.add(leftStick);
        controls.add(rightStick);
    }

    /** Shows or hides the pad (the small toggle button always stays). */
    public void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        releaseAll();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Layout unit: 1/360 of the screen's short side, so the pad scales
        // with the display instead of with its pixel density.
        float s = Math.min(w, h) / 360f;

        // Left side: stick bottom-left, d-pad to its right, L3 above the stick.
        leftStick.place(95 * s, h - 105 * s, 58 * s);
        l3.place(185 * s, h - 190 * s, 20 * s);
        float dx = 250 * s, dy = h - 95 * s, dr = 20 * s, dgap = 40 * s;
        dUp.place(dx, dy - dgap, dr);
        dDown.place(dx, dy + dgap, dr);
        dLeft.place(dx - dgap, dy, dr);
        dRight.place(dx + dgap, dy, dr);

        // Right side: face buttons bottom-right, right stick to their left.
        float fx = w - 90 * s, fy = h - 110 * s, fr = 25 * s, fgap = 50 * s;
        btnA.place(fx, fy + fgap, fr);
        btnB.place(fx + fgap, fy, fr);
        btnX.place(fx - fgap, fy, fr);
        btnY.place(fx, fy - fgap, fr);
        rightStick.place(w - 245 * s, h - 80 * s, 50 * s);
        r3.place(w - 245 * s, h - 170 * s, 20 * s);

        // Shoulders: triggers along the top edge, bumpers just below.
        float pw = 84 * s, ph = 36 * s;
        lt.placeRect(20 * s + pw / 2, 26 * s, pw, ph);
        lb.placeRect(20 * s + pw / 2, 72 * s, pw, ph);
        rt.placeRect(w - 20 * s - pw / 2, 26 * s, pw, ph);
        rb.placeRect(w - 20 * s - pw / 2, 72 * s, pw, ph);

        // Back / Start top-centre, overlay toggle between them.
        back.placeRect(w / 2f - 70 * s, 26 * s, 76 * s, 30 * s);
        start.placeRect(w / 2f + 70 * s, 26 * s, 76 * s, 30 * s);
        toggle.placeRect(w / 2f, 26 * s, 34 * s, 30 * s);
        text.setTextSize(14 * s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        toggle.draw(canvas);
        if (!controlsVisible) {
            return;
        }
        for (Control c : controls) {
            c.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        boolean handled = false;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                if (toggle.hit(x, y)) {
                    setControlsVisible(!controlsVisible);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
                if (!controlsVisible) {
                    return false;
                }
                Control c = findFree(x, y);
                if (c != null) {
                    c.pointerId = id;
                    c.onDown(x, y);
                    handled = true;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Control c = findByPointer(event.getPointerId(i));
                    if (c != null) {
                        c.onMove(event.getX(i), event.getY(i));
                        handled = true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                Control c = findByPointer(event.getPointerId(index));
                if (c != null) {
                    c.pointerId = -1;
                    c.onUp();
                    handled = true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                handled = true;
                break;
            default:
                break;
        }

        if (handled) {
            publish();
            invalidate();
        }
        return handled || controlsVisible;
    }

    private Control findFree(float x, float y) {
        for (Control c : controls) {
            if (c.pointerId == -1 && c.hit(x, y)) {
                return c;
            }
        }
        return null;
    }

    private Control findByPointer(int id) {
        for (Control c : controls) {
            if (c.pointerId == id) {
                return c;
            }
        }
        return null;
    }

    private void releaseAll() {
        for (Control c : controls) {
            if (c.pointerId != -1) {
                c.pointerId = -1;
                c.onUp();
            }
        }
        publish();
    }

    private void publish() {
        int buttons = 0, ltv = 0, rtv = 0;
        for (Control c : controls) {
            if (c instanceof RoundButton) {
                RoundButton b = (RoundButton) c;
                if (!b.held) {
                    continue;
                }
                if (b.trigger == 'L') {
                    ltv = 255;
                } else if (b.trigger == 'R') {
                    rtv = 255;
                } else {
                    buttons |= b.bit;
                }
            }
        }
        int lx = leftStick.axis(leftStick.valueX), ly = leftStick.axis(leftStick.valueY);
        int rx = rightStick.axis(rightStick.valueX), ry = rightStick.axis(rightStick.valueY);
        if (buttons == lastButtons && ltv == lastLt && rtv == lastRt
                && lx == lastLx && ly == lastLy && rx == lastRx && ry == lastRy) {
            return;
        }
        lastButtons = buttons;
        lastLt = ltv;
        lastRt = rtv;
        lastLx = lx;
        lastLy = ly;
        lastRx = rx;
        lastRy = ry;
        try {
            nativeSetState(buttons, ltv, rtv, lx, ly, rx, ry);
        } catch (UnsatisfiedLinkError e) {
            // libmain.so not loaded yet (first frames of startup); state is
            // re-sent on the next touch change.
            lastButtons = -1;
        }
    }

    private void drawLabel(Canvas canvas, String label, float x, float y, float size) {
        text.setTextSize(size);
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, x, y - (fm.ascent + fm.descent) / 2, text);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
