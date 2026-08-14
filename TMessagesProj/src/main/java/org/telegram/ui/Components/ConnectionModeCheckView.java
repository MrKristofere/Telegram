package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class ConnectionModeCheckView extends View {

    private static final int COLOR_ACCENT = 0xFF008BFF;
    private static final int COLOR_CHECK = 0xFFFFFFFF;
    private static final int COLOR_STROKE_LIGHT = 0x2E3C3C43; // rgba(60, 60, 67, 0.18)
    private static final int COLOR_STROKE_DARK = 0x4DEBEBF5; // rgba(235, 235, 245, 0.3)

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final int strokeAlpha;

    private boolean checked;
    private float progress;
    private ValueAnimator animator;

    public ConnectionModeCheckView(Context context) {
        super(context);
        fillPaint.setColor(COLOR_ACCENT);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(AndroidUtilities.dp(1.5f));
        strokePaint.setColor(Theme.isCurrentThemeDark() ? COLOR_STROKE_DARK : COLOR_STROKE_LIGHT);
        strokeAlpha = Color.alpha(strokePaint.getColor());
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeWidth(AndroidUtilities.dp(2f));
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(COLOR_CHECK);
    }

    public void setChecked(boolean value, boolean animated) {
        if (checked == value) {
            return;
        }
        checked = value;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!animated) {
            progress = value ? 1f : 0f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
        animator.setDuration(180);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - strokePaint.getStrokeWidth() / 2f;

        if (progress < 1f) {
            strokePaint.setAlpha((int) (strokeAlpha * (1f - progress)));
            canvas.drawCircle(cx, cy, radius, strokePaint);
        }
        if (progress > 0f) {
            fillPaint.setAlpha((int) (255 * progress));
            canvas.drawCircle(cx, cy, radius * progress, fillPaint);

            float checkProgress = Math.max(0f, (progress - 0.5f) * 2f);
            if (checkProgress > 0f) {
                float s = radius;
                checkPath.reset();
                checkPath.moveTo(cx - s * 0.42f, cy + s * 0.03f);
                checkPath.lineTo(cx - s * 0.12f, cy + s * 0.33f);
                checkPath.lineTo(cx + s * 0.44f, cy - s * 0.31f);
                checkPaint.setAlpha((int) (255 * checkProgress));
                canvas.drawPath(checkPath, checkPaint);
            }
        }
    }
}
