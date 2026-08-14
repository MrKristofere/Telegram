package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.upstream.RawResourceDataSource;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

/**
 * Онбординг про режимы подключения, показывается один раз после авторизации.
 */
@SuppressLint("ViewConstructor")
public class VpnIntroOverlay extends FrameLayout {

    private static final int VIDEO_DESIGN_WIDTH = 345;
    private static final int VIDEO_DESIGN_HEIGHT = 230;
    private static final float VIDEO_MAX_HEIGHT_FRACTION = 0.32f;

    private final View parentView;
    private final ImageView backgroundImageView;
    private final Runnable updateBlurRunnable = () -> {
        if (isAttachedToWindow()) {
            updateBlurBackground();
        }
    };

    private TextureView videoView;
    private VideoPlayer videoPlayer;

    public VpnIntroOverlay(Context context, View parentView) {
        super(context);
        this.parentView = parentView;
        setClickable(true);

        backgroundImageView = new ImageView(context);
        backgroundImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(backgroundImageView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        updateBlurBackground();

        View scrim = new View(context);
        scrim.setBackgroundColor(0xB3000000);
        addView(scrim, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(24), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56),
                AndroidUtilities.dp(24), AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(24));

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(LocaleController.getString(R.string.VpnIntroTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        content.addView(createVideoBlock(context),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 4));

        LinearLayout cards = new LinearLayout(context);
        cards.setOrientation(LinearLayout.VERTICAL);
        addCard(cards, R.drawable.ic_toggle_on_intro, R.string.VpnIntroStatusHeader, R.string.VpnIntroStatusSubHeader, true);
        addCard(cards, R.drawable.ic_toggle_off_intro, R.string.VpnIntroTunnelHeader, R.string.VpnIntroTunnelSubHeader, false);
        addCard(cards, R.drawable.ic_thumb_up_intro, R.string.VpnIntroMultiHeader, R.string.VpnIntroMultiSubHeader, false);
        addCard(cards, R.drawable.ic_power_intro, R.string.VpnIntroAutoHeader, R.string.VpnIntroAutoSubHeader, false);
        content.addView(cards, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        View spacerTop = new View(context);
        content.addView(spacerTop, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));

        TextView tapToStart = new TextView(context);
        tapToStart.setTextColor(Color.WHITE);
        tapToStart.setTypeface(AndroidUtilities.bold());
        tapToStart.setText(LocaleController.getString(R.string.VpnIntroTapToStart));
        tapToStart.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tapToStart.setGravity(Gravity.CENTER);
        content.addView(tapToStart, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        View spacerBottom = new View(context);
        content.addView(spacerBottom, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));

        content.setClickable(true);
        content.setOnClickListener(v -> performClick());

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        scrollView.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        setContentDescription(LocaleController.getString(R.string.VpnIntroTitle)
                + ". " + LocaleController.getString(R.string.VpnIntroTapToStart));
    }

    private void updateBlurBackground() {
        Bitmap blurred = AndroidUtilities.makeBlurBitmap(parentView, 12f, 10);
        if (blurred == null) {
            return;
        }
        BitmapDrawable bitmap = new BitmapDrawable(getResources(), blurred);
        bitmap.setColorFilter(new PorterDuffColorFilter(0xf0000000, PorterDuff.Mode.DST_OVER));
        backgroundImageView.setImageDrawable(bitmap);
    }

    private View createVideoBlock(Context context) {
        FrameLayout container = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int height = width * VIDEO_DESIGN_HEIGHT / VIDEO_DESIGN_WIDTH;
                int maxHeight = (int) (AndroidUtilities.displaySize.y * VIDEO_MAX_HEIGHT_FRACTION);
                if (height > maxHeight) {
                    height = maxHeight;
                    width = height * VIDEO_DESIGN_WIDTH / VIDEO_DESIGN_HEIGHT;
                }
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        final float radius = AndroidUtilities.dp(16);
        container.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        container.setClipToOutline(true);
        container.setBackgroundColor(0x22000000);

        videoView = new TextureView(context);
        videoView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        videoView.setAlpha(0f);
        container.addView(videoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return container;
    }

    private void startVideo() {
        releasePlayer();
        try {
            VideoPlayer player = new VideoPlayer(false, true);
            videoPlayer = player;
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(VpnIntroOverlay.this::releasePlayer);
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                }

                @Override
                public void onRenderedFirstFrame() {
                    videoView.animate().alpha(1f).setDuration(150).start();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                }

                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                    return true;
                }
            });
            player.setTextureView(videoView);
            player.setLooping(true);
            player.preparePlayer(RawResourceDataSource.buildRawResourceUri(R.raw.onboarding_vpgram), "other");
            player.setMute(true);
            if (getWindowVisibility() == VISIBLE) {
                player.play();
            }
        } catch (Exception e) {
            FileLog.e(e);
            releasePlayer();
        }
    }

    private void releasePlayer() {
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
    }

    private void addCard(LinearLayout parent, int iconRes, int headerRes, int subHeaderRes, boolean highlighted) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        if (highlighted) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(AndroidUtilities.dp(12));
            bg.setColor(0x26FFFFFF);
            card.setBackground(bg);
        }

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(ContextCompat.getDrawable(getContext(), iconRes));
        icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        card.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(getContext());
        header.setTextColor(Color.WHITE);
        header.setTypeface(AndroidUtilities.bold());
        header.setText(LocaleController.getString(headerRes));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        texts.addView(header, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView subHeader = new TextView(getContext());
        subHeader.setTextColor(0x80FFFFFF);
        subHeader.setText(LocaleController.getString(subHeaderRes));
        subHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        texts.addView(subHeader, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        card.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        lp.topMargin = AndroidUtilities.dp(6);
        lp.bottomMargin = AndroidUtilities.dp(6);
        parent.addView(card, lp);
    }

    public void dismiss() {
        releasePlayer();
        animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
        }).start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startVideo();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (videoPlayer == null) {
            return;
        }
        if (visibility == VISIBLE) {
            videoPlayer.play();
        } else {
            videoPlayer.pause();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AndroidUtilities.cancelRunOnUIThread(updateBlurRunnable);
        AndroidUtilities.runOnUIThread(updateBlurRunnable, 150);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(updateBlurRunnable);
        releasePlayer();
    }
}
