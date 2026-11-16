package fr.freebox.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Pavé directionnel circulaire entièrement dessiné au Canvas.
 *
 * <p>Le bouton OK occupe le centre géométrique exact de la vue et son texte
 * est centré optiquement ; les quatre chevrons sont répartis symétriquement
 * à égale distance du centre. Un appui long répète la direction (420 ms puis
 * toutes les 110 ms) et glisser le doigt d'une direction à l'autre change la
 * touche envoyée, comme sur une vraie télécommande.</p>
 */
final class DpadView extends View {

    interface KeySink {
        void sendKey(int keyCode);
    }

    private static final int NONE = 0;
    private static final int UP = 1;
    private static final int RIGHT = 2;
    private static final int DOWN = 3;
    private static final int LEFT = 4;
    private static final int OK = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final RectF bounds = new RectF();
    private final KeySink sink;

    private int active = NONE;
    private Runnable repeatRunnable;
    private int repeatCode;
    private float centerX;
    private float centerY;
    private float radius;

    DpadView(Context context, KeySink sink) {
        super(context);
        this.sink = sink;
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(0xFFFFFFFF);
    }

    // ── dessin ────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        centerX = getWidth() / 2f;
        centerY = getHeight() / 2f;
        radius = Math.min(getWidth(), getHeight()) / 2f;
        if (radius <= 0) {
            return;
        }

        // Socle : disque avec un léger dégradé pour donner du relief.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(centerX, centerY - radius * 0.35f, radius * 1.25f,
                new int[]{0xFF232B3C, 0xFF171C28, 0xFF10141E},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setShader(null);

        // Liseré extérieur (la moitié intérieure pour rester net).
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF2C3547);
        canvas.drawCircle(centerX, centerY, radius - dp(0.5f), paint);

        // Halo très discret autour du OK.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(centerX, centerY, radius * 0.42f,
                new int[]{0x33FF2B46, 0x00FF2B46}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, radius * 0.42f, paint);
        paint.setShader(null);

        float offset = radius * 0.60f;
        drawDirection(canvas, centerX, centerY - offset, 0, UP);
        drawDirection(canvas, centerX + offset, centerY, 90, RIGHT);
        drawDirection(canvas, centerX, centerY + offset, 180, DOWN);
        drawDirection(canvas, centerX - offset, centerY, 270, LEFT);

        drawOk(canvas);
    }

    /** Chevron pointant vers le haut, tourné de {@code rotation} degrés. */
    private void drawDirection(Canvas canvas, float x, float y, float rotation, int direction) {
        float arm = radius * 0.075f;
        if (active == direction) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x2EFF2B46);
            canvas.drawCircle(x, y, radius * 0.16f, paint);
        }
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);
        chevron.reset();
        chevron.moveTo(-arm, arm * 0.62f);
        chevron.lineTo(0, -arm * 0.62f);
        chevron.lineTo(arm, arm * 0.62f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(radius * 0.042f);
        paint.setColor(active == direction ? 0xFFFF5C6C : 0xFFDDE3EF);
        canvas.drawPath(chevron, paint);
        canvas.restore();
    }

    private void drawOk(Canvas canvas) {
        float okRadius = radius * 0.30f;

        // Ombre portée douce.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawCircle(centerX + dp(1), centerY + dp(2.5f), okRadius, paint);

        // Pastille principale dans la couleur d'accent.
        paint.setShader(new LinearGradient(centerX, centerY - okRadius, centerX,
                centerY + okRadius, 0xFFFF4258, 0xFFD9112F, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, okRadius, paint);
        paint.setShader(null);

        // Reflet supérieur et liseré.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x40FFFFFF);
        canvas.drawCircle(centerX, centerY, okRadius - dp(0.5f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1FFFFFFF);
        bounds.set(centerX - okRadius * 0.62f, centerY - okRadius * 0.78f,
                centerX + okRadius * 0.62f, centerY - okRadius * 0.06f);
        canvas.drawOval(bounds, paint);

        // Texte parfaitement centré verticalement (mesures de la police).
        label.setTextSize(okRadius * 0.78f);
        Paint.FontMetrics metrics = label.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText("OK", centerX, baseline, label);

        if (active == OK) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0x66FFFFFF);
            canvas.drawCircle(centerX, centerY, okRadius + dp(1), paint);
        }
    }

    // ── interaction ───────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                setActive(hitTest(event.getX(), event.getY()), true);
                return active != NONE;
            case MotionEvent.ACTION_MOVE: {
                int zone = hitTest(event.getX(), event.getY());
                if (zone != active) {
                    setActive(zone, true);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setActive(NONE, false);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void setActive(int zone, boolean fire) {
        if (active == zone) {
            return;
        }
        active = zone;
        stopRepeat();
        if (zone != NONE && fire) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sink.sendKey(codeFor(zone));
            if (zone != OK) {
                startRepeat(codeFor(zone));
            }
        }
        invalidate();
    }

    private void startRepeat(final int code) {
        repeatCode = code;
        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (repeatCode == code && active != NONE && active != OK) {
                    sink.sendKey(code);
                    postDelayed(this, 110);
                }
            }
        };
        postDelayed(repeatRunnable, 420);
    }

    private void stopRepeat() {
        if (repeatRunnable != null) {
            removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRepeat();
        super.onDetachedFromWindow();
    }

    private int hitTest(float x, float y) {
        if (radius <= 0) {
            return NONE;
        }
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > radius) {
            return NONE;
        }
        if (distance <= radius * 0.36f) {
            return OK;
        }
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? RIGHT : LEFT;
        }
        return dy > 0 ? DOWN : UP;
    }

    private static int codeFor(int zone) {
        switch (zone) {
            case UP:
                return KeyCodes.UP;
            case RIGHT:
                return KeyCodes.RIGHT;
            case DOWN:
                return KeyCodes.DOWN;
            case LEFT:
                return KeyCodes.LEFT;
            default:
                return KeyCodes.OK;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
