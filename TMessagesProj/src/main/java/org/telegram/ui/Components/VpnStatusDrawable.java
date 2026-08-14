package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.core.content.res.ResourcesCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.VpnStatusController;
import org.telegram.ui.ActionBar.Theme;

public class VpnStatusDrawable extends Drawable {

    private static final int SIZE_DP = 36;

    private static final int COLOR_CONNECTED = 0xFF34C759;
    private static final int COLOR_ERROR = 0xFFFF3B30;

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable shield;
    private final CircularProgressDrawable progress;
    private final Theme.ResourcesProvider resourcesProvider;

    private VpnStatusController.Status status = VpnStatusController.Status.OFF;

    public VpnStatusDrawable(Context context, Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        shield = ResourcesCompat.getDrawable(context.getResources(), R.drawable.vpn_shield_key, null).mutate();
        progress = new CircularProgressDrawable(AndroidUtilities.dp(16), AndroidUtilities.dp(2), 0xFFFFFFFF);
        progress.setCallback(new Callback() {
            @Override
            public void invalidateDrawable(Drawable who) {
                invalidateSelf();
            }

            @Override
            public void scheduleDrawable(Drawable who, Runnable what, long when) {
                scheduleSelf(what, when);
            }

            @Override
            public void unscheduleDrawable(Drawable who, Runnable what) {
                unscheduleSelf(what);
            }
        });
        applyColors();
    }

    public void setStatus(VpnStatusController.Status newStatus) {
        if (status == newStatus) {
            return;
        }
        status = newStatus;
        if (status == VpnStatusController.Status.CONNECTING) {
            progress.reset();
        }
        applyColors();
        invalidateSelf();
    }

    public void updateColors() {
        applyColors();
        invalidateSelf();
    }

    private void applyColors() {
        int circleColor;
        int iconColor;
        switch (status) {
            case CONNECTED:
                circleColor = COLOR_CONNECTED;
                iconColor = 0xFFFFFFFF;
                break;
            case ERROR:
                circleColor = COLOR_ERROR;
                iconColor = 0xFFFFFFFF;
                break;
            default:
                circleColor = Theme.multAlpha(
                        Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider), 0.15f);
                iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
                break;
        }
        circlePaint.setColor(circleColor);
        shield.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        progress.setColor(iconColor);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        canvas.drawCircle(cx, cy, Math.min(bounds.width(), bounds.height()) / 2f, circlePaint);

        if (status == VpnStatusController.Status.CONNECTING) {
            progress.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
            progress.draw(canvas);
        } else {
            int w = AndroidUtilities.dp(20);
            int h = AndroidUtilities.dp(24);
            shield.setBounds((int) (cx - w / 2f), (int) (cy - h / 2f), (int) (cx + w / 2f), (int) (cy + h / 2f));
            shield.draw(canvas);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(SIZE_DP);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(SIZE_DP);
    }

    @Override
    public void setAlpha(int alpha) {
        circlePaint.setAlpha(alpha);
        shield.setAlpha(alpha);
        progress.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Цвета задаёт состояние, внешний фильтр игнорируем.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
