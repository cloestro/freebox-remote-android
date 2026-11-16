package fr.freebox.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Icônes vectorielles dessinées au Canvas : nettes à toutes les densités,
 * aucun fichier image, et un tracé homogène d'un bouton à l'autre.
 *
 * <p>Chaque icône est dessinée dans une boîte de 100 × 100 puis mise à
 * l'échelle ({@link #content(float)}) au centre de la vue.</p>
 */
final class IconView extends View {

    static final int NONE = 0;
    static final int POWER = 1;
    static final int TV = 2;
    static final int MUTE = 3;
    static final int MORE = 4;
    static final int BACK = 5;
    static final int HOME = 6;
    static final int MENU = 7;
    static final int GEAR = 8;
    static final int KEYPAD = 9;
    static final int APPS = 10;
    static final int REWIND = 11;
    static final int PLAY_PAUSE = 12;
    static final int FORWARD = 13;
    static final int STOP = 14;
    static final int RECORD = 15;
    static final int GUIDE = 16;
    static final int INFO = 17;
    static final int SEARCH = 18;
    static final int PLUS = 19;
    static final int MINUS = 20;
    static final int REMOTE = 21;
    static final int LOGO = 22;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private int icon = NONE;
    private int tint = 0xFFF2F4F8;
    private float content = 0.5f;

    IconView(Context context) {
        super(context);
        setWillNotDraw(false);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        solid.setStyle(Paint.Style.FILL);
        applyTint();
    }

    IconView icon(int value) {
        icon = value;
        invalidate();
        return this;
    }

    IconView tint(int color) {
        tint = color;
        applyTint();
        invalidate();
        return this;
    }

    IconView content(float ratio) {
        content = ratio;
        invalidate();
        return this;
    }

    private void applyTint() {
        line.setColor(tint);
        solid.setColor(tint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (icon == NONE) {
            return;
        }
        float box = Math.min(getWidth(), getHeight()) * content;
        if (box <= 0) {
            return;
        }
        float scale = box / 100f;
        canvas.save();
        canvas.translate((getWidth() - box) / 2f, (getHeight() - box) / 2f);
        canvas.scale(scale, scale);
        line.setStrokeWidth(icon == LOGO ? 15f : 8f);
        switch (icon) {
            case POWER:
                oval.set(22, 26, 78, 82);
                canvas.drawArc(oval, -60f, 300f, false, line);
                canvas.drawLine(50, 14, 50, 46, line);
                break;
            case TV:
                oval.set(13, 24, 87, 72);
                canvas.drawRoundRect(oval, 9, 9, line);
                canvas.drawLine(50, 72, 50, 82, line);
                canvas.drawLine(37, 84, 63, 84, line);
                break;
            case MUTE:
                path.reset();
                path.moveTo(20, 40);
                path.lineTo(34, 40);
                path.lineTo(48, 27);
                path.lineTo(48, 73);
                path.lineTo(34, 60);
                path.lineTo(20, 60);
                path.close();
                canvas.drawPath(path, solid);
                canvas.drawLine(60, 40, 78, 60, line);
                canvas.drawLine(78, 40, 60, 60, line);
                break;
            case MORE:
                canvas.drawCircle(27, 50, 6, solid);
                canvas.drawCircle(50, 50, 6, solid);
                canvas.drawCircle(73, 50, 6, solid);
                break;
            case BACK:
                path.reset();
                path.moveTo(56, 26);
                path.lineTo(30, 50);
                path.lineTo(56, 74);
                canvas.drawPath(path, line);
                canvas.drawLine(31, 50, 80, 50, line);
                break;
            case HOME:
                path.reset();
                path.moveTo(16, 52);
                path.lineTo(50, 22);
                path.lineTo(84, 52);
                canvas.drawPath(path, line);
                path.reset();
                path.moveTo(28, 48);
                path.lineTo(28, 80);
                path.lineTo(72, 80);
                path.lineTo(72, 48);
                canvas.drawPath(path, line);
                break;
            case MENU:
                canvas.drawLine(24, 33, 76, 33, line);
                canvas.drawLine(24, 50, 76, 50, line);
                canvas.drawLine(24, 67, 76, 67, line);
                break;
            case GEAR:
                canvas.drawLine(20, 30, 80, 30, line);
                canvas.drawLine(20, 50, 80, 50, line);
                canvas.drawLine(20, 70, 80, 70, line);
                canvas.drawCircle(66, 30, 7, solid);
                canvas.drawCircle(38, 50, 7, solid);
                canvas.drawCircle(58, 70, 7, solid);
                break;
            case KEYPAD:
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 3; column++) {
                        canvas.drawCircle(30 + column * 20, 30 + row * 20, 5, solid);
                    }
                }
                break;
            case APPS:
                oval.set(20, 20, 44, 44);
                canvas.drawRoundRect(oval, 7, 7, solid);
                oval.set(56, 20, 80, 44);
                canvas.drawRoundRect(oval, 7, 7, solid);
                oval.set(20, 56, 44, 80);
                canvas.drawRoundRect(oval, 7, 7, solid);
                oval.set(56, 56, 80, 80);
                canvas.drawRoundRect(oval, 7, 7, solid);
                break;
            case REWIND:
                path.reset();
                path.moveTo(48, 26);
                path.lineTo(24, 50);
                path.lineTo(48, 74);
                path.close();
                canvas.drawPath(path, solid);
                path.reset();
                path.moveTo(78, 26);
                path.lineTo(54, 50);
                path.lineTo(78, 74);
                path.close();
                canvas.drawPath(path, solid);
                break;
            case FORWARD:
                path.reset();
                path.moveTo(22, 26);
                path.lineTo(46, 50);
                path.lineTo(22, 74);
                path.close();
                canvas.drawPath(path, solid);
                path.reset();
                path.moveTo(52, 26);
                path.lineTo(76, 50);
                path.lineTo(52, 74);
                path.close();
                canvas.drawPath(path, solid);
                break;
            case PLAY_PAUSE:
                path.reset();
                path.moveTo(24, 24);
                path.lineTo(58, 50);
                path.lineTo(24, 76);
                path.close();
                canvas.drawPath(path, solid);
                oval.set(66, 24, 80, 76);
                canvas.drawRoundRect(oval, 5, 5, solid);
                break;
            case STOP:
                oval.set(28, 28, 72, 72);
                canvas.drawRoundRect(oval, 9, 9, solid);
                break;
            case RECORD:
                canvas.drawCircle(50, 50, 25, solid);
                break;
            case GUIDE:
                oval.set(17, 22, 83, 78);
                canvas.drawRoundRect(oval, 9, 9, line);
                canvas.drawLine(17, 40, 83, 40, line);
                canvas.drawLine(50, 40, 50, 78, line);
                break;
            case INFO:
                canvas.drawCircle(50, 50, 32, line);
                canvas.drawCircle(50, 33, 4.5f, solid);
                canvas.drawLine(50, 45, 50, 68, line);
                break;
            case SEARCH:
                canvas.drawCircle(43, 43, 25, line);
                canvas.drawLine(62, 62, 80, 80, line);
                break;
            case PLUS:
                canvas.drawLine(50, 26, 50, 74, line);
                canvas.drawLine(26, 50, 74, 50, line);
                break;
            case MINUS:
                canvas.drawLine(26, 50, 74, 50, line);
                break;
            case REMOTE:
                oval.set(30, 12, 70, 88);
                canvas.drawRoundRect(oval, 13, 13, line);
                canvas.drawCircle(50, 32, 9, line);
                for (int row = 0; row < 3; row++) {
                    canvas.drawCircle(43, 54 + row * 11, 3, solid);
                    canvas.drawCircle(57, 54 + row * 11, 3, solid);
                }
                break;
            case LOGO:
                path.reset();
                path.moveTo(68, 17);
                path.cubicTo(51, 17, 43, 28, 43, 44);
                path.lineTo(43, 84);
                path.moveTo(25, 51);
                path.lineTo(63, 51);
                canvas.drawPath(path, line);
                break;
            default:
                break;
        }
        canvas.restore();
    }
}
