package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/** Floating joystick drawn over other apps. Single custom view, no XML. */
public class JoystickOverlay {
    public interface Listener {
        void onMove(float x, float y);            // -1..1 east / north, (0,0) = released

        void onSpeedChanged(double metersPerSecond);

        void onClose();
    }

    private static final double[] SPEEDS = {1.5, 4, 8, 20, 40};
    private static final String[] SPEED_NAMES = {"步行", "跑步", "骑行", "开车", "飞车"};

    private final Context ctx;
    private final Listener listener;
    private final WindowManager wm;
    private JoyView view;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private int speedIdx;

    public JoystickOverlay(Context ctx, Listener l) {
        this.ctx = ctx;
        this.listener = l;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShown() {
        return shown;
    }

    public void show(double speed) {
        if (shown) return;
        speedIdx = 0;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - speed) < 0.01) speedIdx = i;
        float d = ctx.getResources().getDisplayMetrics().density;
        view = new JoyView(ctx, d);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams((int) (200 * d), (int) (250 * d), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (20 * d);
        lp.y = ctx.getResources().getDisplayMetrics().heightPixels / 2;
        try {
            wm.addView(view, lp);
            shown = true;
        } catch (Throwable t) {
            shown = false;
        }
    }

    public void hide() {
        if (!shown) return;
        shown = false;
        try {
            wm.removeView(view);
        } catch (Throwable ignored) {
        }
        view = null;
    }

    private class JoyView extends View {
        final float d;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        float kx, ky;              // knob offset in px
        int mode;                  // 0 none, 1 knob, 2 drag window
        float downX, downY;
        int winX, winY;

        JoyView(Context c, float density) {
            super(c);
            d = density;
            bg.setColor(0xCC15161C);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(0x66FFFFFF);
            knob.setColor(0xFFFF4D8D);
            knob.setShadowLayer(8 * d, 0, 2 * d, 0x80000000);
            text.setColor(Color.WHITE);
            text.setTextSize(12 * d);
            text.setTextAlign(Paint.Align.CENTER);
            chip.setColor(0x33FFFFFF);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        float cx() {
            return getWidth() / 2f;
        }

        float cy() {
            return 44 * d + 88 * d;
        }

        float radius() {
            return 78 * d;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(new RectF(0, 0, w, h), 22 * d, 22 * d, bg);
            // header: speed chip, handle, close
            RectF sc = new RectF(10 * d, 9 * d, 82 * d, 35 * d);
            c.drawRoundRect(sc, 13 * d, 13 * d, chip);
            c.drawText(SPEED_NAMES[speedIdx] + " " + fmtSpeed(SPEEDS[speedIdx]), sc.centerX(), sc.centerY() + 4 * d, text);
            Paint handle = new Paint(ring);
            handle.setStrokeWidth(3 * d);
            handle.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(w / 2 - 12 * d, 22 * d, w / 2 + 12 * d, 22 * d, handle);
            RectF xc = new RectF(w - 36 * d, 9 * d, w - 10 * d, 35 * d);
            c.drawRoundRect(xc, 13 * d, 13 * d, chip);
            Paint xp = new Paint(handle);
            xp.setStrokeWidth(2 * d);
            c.drawLine(xc.centerX() - 5 * d, xc.centerY() - 5 * d, xc.centerX() + 5 * d, xc.centerY() + 5 * d, xp);
            c.drawLine(xc.centerX() - 5 * d, xc.centerY() + 5 * d, xc.centerX() + 5 * d, xc.centerY() - 5 * d, xp);
            // joystick
            float cx = cx(), cy = cy(), r = radius();
            c.drawCircle(cx, cy, r, ring);
            Paint inner = new Paint(ring);
            inner.setColor(0x22FFFFFF);
            c.drawCircle(cx, cy, r * 0.5f, inner);
            text.setColor(0x99FFFFFF);
            c.drawText("北", cx, cy - r + 16 * d, text);
            c.drawText("南", cx, cy + r - 8 * d, text);
            c.drawText("西", cx - r + 12 * d, cy + 4 * d, text);
            c.drawText("东", cx + r - 12 * d, cy + 4 * d, text);
            text.setColor(Color.WHITE);
            c.drawCircle(cx + kx, cy + ky, 28 * d, knob);
        }

        String fmtSpeed(double v) {
            return String.format(Locale.US, "%.0fkm/h", v * 3.6);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (y < 44 * d) {
                        if (x < 90 * d) {
                            speedIdx = (speedIdx + 1) % SPEEDS.length;
                            listener.onSpeedChanged(SPEEDS[speedIdx]);
                            invalidate();
                            mode = 0;
                        } else if (x > getWidth() - 44 * d) {
                            listener.onClose();
                            mode = 0;
                        } else {
                            mode = 2;
                            downX = e.getRawX();
                            downY = e.getRawY();
                            winX = lp.x;
                            winY = lp.y;
                        }
                    } else {
                        mode = 1;
                        moveKnob(x, y);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode == 1) moveKnob(x, y);
                    else if (mode == 2) {
                        lp.x = winX + (int) (e.getRawX() - downX);
                        lp.y = winY + (int) (e.getRawY() - downY);
                        try {
                            wm.updateViewLayout(this, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode == 1) {
                        kx = ky = 0;
                        listener.onMove(0, 0);
                        invalidate();
                    }
                    mode = 0;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        void moveKnob(float x, float y) {
            float dx = x - cx(), dy = y - cy(), r = radius() - 20 * d;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > r) {
                dx = dx / len * r;
                dy = dy / len * r;
            }
            kx = dx;
            ky = dy;
            listener.onMove(dx / r, -dy / r);
            invalidate();
        }
    }
}
