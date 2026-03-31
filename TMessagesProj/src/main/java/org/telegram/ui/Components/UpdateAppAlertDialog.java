package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BetaUpdate;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;

public class UpdateAppAlertDialog extends BottomSheet {

    private Drawable shadowDrawable;
    private NestedScrollView scrollView;

    private AnimatorSet shadowAnimation;

    private View shadow;

    private LinearLayout linearLayout;

    private int scrollOffsetY;

    private int[] location = new int[2];

    public UpdateAppAlertDialog(Context context, BetaUpdate update, int account) {
        super(context, false);

        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        shadowDrawable = context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));

        FrameLayout container = new FrameLayout(context) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                updateLayout();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.getY() < scrollOffsetY) {
                    dismiss();
                    return true;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                return !isDismissed() && super.onTouchEvent(e);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int top = (int) (scrollOffsetY - backgroundPaddingTop - getTranslationY());
                shadowDrawable.setBounds(0, top, getMeasuredWidth(), getMeasuredHeight());
                shadowDrawable.draw(canvas);
            }
        };
        container.setWillNotDraw(false);
        containerView = container;

        scrollView = new NestedScrollView(context) {

            private boolean ignoreLayout;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int contentHeight = linearLayout.getMeasuredHeight();
                int padding = (height / 5 * 2);
                int visiblePart = height - padding;
                if (contentHeight - visiblePart < AndroidUtilities.dp(90) || contentHeight < height / 2 + AndroidUtilities.dp(90)) {
                    padding = height - contentHeight;
                }
                if (padding < 0) {
                    padding = 0;
                }
                if (getPaddingTop() != padding) {
                    ignoreLayout = true;
                    setPadding(0, padding, 0, 0);
                    ignoreLayout = false;
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                updateLayout();
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setWillNotDraw(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        container.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 130));

        linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        TextView titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(LocaleController.getString(R.string.AppUpdateBeta));
        linearLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 16, 23, 0));

        TextView versionView = new TextView(getContext());
        versionView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setText(LocaleController.formatString(R.string.AppBetaUpdateVersion, update.version, update.versionCode));
        versionView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        linearLayout.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 0, 23, 5));

        if (!TextUtils.isEmpty(update.changelog)) {
            TextView changelogTextView = new TextView(getContext());
            changelogTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            changelogTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            changelogTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
            changelogTextView.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink));
            changelogTextView.setLineSpacing(AndroidUtilities.dp(3), 1.0f);
            changelogTextView.setText(Emoji.replaceEmoji(update.changelog, changelogTextView.getPaint().getFontMetricsInt(), false));
            NotificationCenter.listenEmojiLoading(changelogTextView);
            changelogTextView.setGravity(Gravity.LEFT | Gravity.TOP);
            linearLayout.addView(changelogTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 23, 15, 23, 0));
        }

        FrameLayout.LayoutParams frameLayoutParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight(), Gravity.BOTTOM | Gravity.LEFT);
        frameLayoutParams.bottomMargin = AndroidUtilities.dp(130);
        shadow = new View(context);
        shadow.setBackgroundColor(Theme.getColor(Theme.key_dialogShadowLine));
        shadow.setAlpha(0.0f);
        shadow.setTag(1);
        container.addView(shadow, frameLayoutParams);

        ButtonWithCounterView doneButton = new ButtonWithCounterView(context, null);
        final File file = ApplicationLoader.applicationLoaderInstance.getDownloadedUpdateFile();
        if (file != null) {
            doneButton.setText(LocaleController.formatString(R.string.AppUpdateNow), false);
            doneButton.setOnClickListener(v -> {
                Activity activity = AndroidUtilities.findActivity(getContext());
                if (activity == null) return;
                AndroidUtilities.openForView(file, "vpGram.apk", "application/vnd.android.package-archive", activity, null, false);
                dismiss();
            });
        } else {
            doneButton.setText(LocaleController.formatString(R.string.AppUpdateDownloadNow), false);
            doneButton.setOnClickListener(v -> {
                ApplicationLoader.applicationLoaderInstance.downloadUpdate();
                dismiss();
            });
        }
        container.addView(doneButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM, 20, 0, 20, 48 + 4 + 8));

        ButtonWithCounterView scheduleButton = new ButtonWithCounterView(context, false, null);
        scheduleButton.setText(LocaleController.getString(R.string.AppUpdateRemindMeLater), false);
        scheduleButton.setOnClickListener(v -> dismiss());
        container.addView(scheduleButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM, 20, 4, 20, 8));
    }

    private void runShadowAnimation(final int num, final boolean show) {
        if (show && shadow.getTag() != null || !show && shadow.getTag() == null) {
            shadow.setTag(show ? null : 1);
            if (show) {
                shadow.setVisibility(View.VISIBLE);
            }
            if (shadowAnimation != null) {
                shadowAnimation.cancel();
            }
            shadowAnimation = new AnimatorSet();
            shadowAnimation.playTogether(ObjectAnimator.ofFloat(shadow, View.ALPHA, show ? 1.0f : 0.0f));
            shadowAnimation.setDuration(150);
            shadowAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        if (!show) {
                            shadow.setVisibility(View.INVISIBLE);
                        }
                        shadowAnimation = null;
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        shadowAnimation = null;
                    }
                }
            });
            shadowAnimation.start();
        }
    }

    private void updateLayout() {
        View child = linearLayout.getChildAt(0);
        child.getLocationInWindow(location);
        int top = location[1] - AndroidUtilities.dp(24);
        int newOffset = Math.max(top, 0);
        if (location[1] + linearLayout.getMeasuredHeight() <= container.getMeasuredHeight() - AndroidUtilities.dp(113) + containerView.getTranslationY()) {
            runShadowAnimation(0, false);
        } else {
            runShadowAnimation(0, true);
        }
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset;
            scrollView.invalidate();
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }
}
