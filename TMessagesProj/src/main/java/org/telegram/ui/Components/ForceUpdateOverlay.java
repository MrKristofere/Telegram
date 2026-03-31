package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BetaUpdate;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;

@SuppressLint("ViewConstructor")
public class ForceUpdateOverlay extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int STATE_DOWNLOAD = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_INSTALL = 2;

    private final ButtonWithCounterView actionButton;
    private int state = STATE_DOWNLOAD;

    public ForceUpdateOverlay(Context context, View parentView, BetaUpdate update) {
        super(context);
        setClickable(true);
        setFocusable(true);

        Bitmap blurBitmap = AndroidUtilities.makeBlurBitmap(parentView, 12f, 10);
        if (blurBitmap != null) {
            ImageView backgroundImageView = new ImageView(context);
            backgroundImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(backgroundImageView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

            BitmapDrawable bitmapDrawable = new BitmapDrawable(context.getResources(), blurBitmap);
            bitmapDrawable.setColorFilter(new PorterDuffColorFilter(0xf0000000, PorterDuff.Mode.DST_OVER));
            backgroundImageView.setImageDrawable(bitmapDrawable);
        }

        View scrim = new View(context);
        scrim.setBackgroundColor(blurBitmap != null ? 0x80000000 : 0xF0000000);
        addView(scrim, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setClipToPadding(false);
        scrollView.addView(contentLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(scrollView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER, 0, 0, 0, 0));

        TextView titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(LocaleController.getString(R.string.AppUpdateRequired));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        contentLayout.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 24, 0, 24, 0));

        TextView subtitleView = new TextView(context);
        subtitleView.setTextColor(0x96FFFFFF);
        subtitleView.setText(LocaleController.getString(R.string.AppUpdateRequiredSubtitle));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        contentLayout.addView(subtitleView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 24, 8, 24, 0));

        TextView versionView = new TextView(context);
        versionView.setTextColor(0xB0FFFFFF);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        versionView.setGravity(Gravity.CENTER_HORIZONTAL);
        versionView.setText(LocaleController.formatString(R.string.AppBetaUpdateVersion, update.version, update.versionCode));
        contentLayout.addView(versionView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 24, 4, 24, 0));

        String changelog = update.changelog;
        if (TextUtils.isEmpty(changelog)) {
            changelog = LocaleController.getString(R.string.AppUpdateChangelogEmpty);
        }
        TextView changelogView = new TextView(context);
        changelogView.setTextColor(0xCCFFFFFF);
        changelogView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        changelogView.setGravity(Gravity.START);
        changelogView.setLineSpacing(AndroidUtilities.dp(3), 1.0f);
        changelogView.setText(Emoji.replaceEmoji(changelog, changelogView.getPaint().getFontMetricsInt(), false));
        NotificationCenter.listenEmojiLoading(changelogView);
        contentLayout.addView(changelogView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.START, 24, 24, 24, 0));

        actionButton = new ButtonWithCounterView(context, null);
        actionButton.setOnClickListener(v -> onActionButtonClick());
        contentLayout.addView(actionButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48,
                Gravity.CENTER_HORIZONTAL, 24, 32, 24, 0));

        updateState(false);
    }

    private void updateState(boolean animated) {
        int previousState = state;
        File downloadedFile = ApplicationLoader.applicationLoaderInstance.getDownloadedUpdateFile();
        if (downloadedFile != null) {
            state = STATE_INSTALL;
            actionButton.setText(LocaleController.getString(R.string.AppUpdateNow), animated);
            actionButton.setEnabled(true);
        } else if (ApplicationLoader.applicationLoaderInstance.isDownloadingUpdate()) {
            state = STATE_DOWNLOADING;
            int progress = (int) (ApplicationLoader.applicationLoaderInstance.getDownloadingUpdateProgress() * 100);
            actionButton.setText(LocaleController.formatString(R.string.AppUpdateDownloading, progress), animated);
            actionButton.setEnabled(false);
        } else {
            state = STATE_DOWNLOAD;
            actionButton.setText(LocaleController.getString(R.string.AppUpdateDownloadNow), animated);
            actionButton.setEnabled(true);
            if (previousState == STATE_DOWNLOADING) {
                Toast.makeText(getContext(), LocaleController.getString(R.string.AppUpdateDownloadFailed), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void onActionButtonClick() {
        if (state == STATE_DOWNLOAD) {
            ApplicationLoader.applicationLoaderInstance.downloadUpdate();
            updateState(true);
        } else if (state == STATE_INSTALL) {
            File file = ApplicationLoader.applicationLoaderInstance.getDownloadedUpdateFile();
            if (file == null) return;
            Activity activity = AndroidUtilities.findActivity(getContext());
            if (activity == null) return;
            if (!ApplicationLoader.applicationLoaderInstance.checkApkInstallPermissions(getContext())) {
                return;
            }
            AndroidUtilities.openForView(file, "vpGram.apk", "application/vnd.android.package-archive", activity, null, false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateLoading);
        updateState(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateLoading);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appUpdateAvailable || id == NotificationCenter.appUpdateLoading) {
            updateState(true);
        }
    }
}
