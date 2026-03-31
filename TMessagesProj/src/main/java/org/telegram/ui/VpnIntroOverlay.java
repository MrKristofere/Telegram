package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("ViewConstructor")
public class VpnIntroOverlay extends FrameLayout {

    public static class Item {
        public final int drawableRes;
        public final String header;
        public final String subHeader;

        public Item(int drawableRes, String header, String subHeader) {
            this.drawableRes = drawableRes;
            this.header = header;
            this.subHeader = subHeader;
        }
    }

    public VpnIntroOverlay(Context context, View parentView, String title, String subtitle, List<Item> items, String dismissText) {
        super(context);
        setClickable(true);

        ImageView backgroundImageView = new ImageView(context);
        backgroundImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(backgroundImageView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        BitmapDrawable bitmap = new BitmapDrawable(getContext().getResources(), AndroidUtilities.makeBlurBitmap(parentView, 12f, 10));
        bitmap.setColorFilter(new PorterDuffColorFilter(0xf0000000, PorterDuff.Mode.DST_OVER));
        backgroundImageView.setImageDrawable(bitmap);

        View scrim = new View(context);
        scrim.setBackgroundColor(0x80000000);
        addView(scrim, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(0, AndroidUtilities.dp(48), 0, AndroidUtilities.dp(48));
        linearLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView header = new TextView(context);
        header.setTextColor(Color.WHITE);
        header.setTypeface(AndroidUtilities.bold());
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        linearLayout.addView(header, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView subHeader = new TextView(context);
        subHeader.setTextColor(0x96FFFFFF);
        subHeader.setText(subtitle);
        subHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subHeader.setGravity(Gravity.CENTER_HORIZONTAL);
        linearLayout.addView(subHeader, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 68, 8, 68, 36));

        List<ItemView> itemViews = new ArrayList<>(items.size());
        int itemWidth = parentView.getMeasuredWidth() - AndroidUtilities.dp(100);
        for (Item item : items) {
            ItemView itemView = new ItemView(context, item.drawableRes, item.header, item.subHeader);
            itemViews.add(itemView);
            int w = itemView.getRequiredWidth();
            if (w > itemWidth) {
                itemWidth = w;
            }
        }
        if (itemWidth + AndroidUtilities.dp(8) > parentView.getMeasuredWidth()) {
            itemWidth = parentView.getMeasuredWidth() - AndroidUtilities.dp(8);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(itemWidth, AndroidUtilities.dp(64));
        lp.setMargins(0, AndroidUtilities.dp(5), 0, AndroidUtilities.dp(5));
        for (ItemView itemView : itemViews) {
            itemView.setHighlighted(true);
            linearLayout.addView(itemView, lp);
        }

        TextView bottomText = new TextView(context);
        bottomText.setTextColor(Color.WHITE);
        bottomText.setTypeface(AndroidUtilities.bold());
        bottomText.setText(dismissText);
        bottomText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        linearLayout.addView(bottomText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 73, 0, 0));

        addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    public void dismiss() {
        animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
        }).start();
    }

    @SuppressLint("ViewConstructor")
    static class ItemView extends View {

        private final String header;
        private final String subHeader;
        private final Drawable icon;
        private final Paint backgroundPaint;
        private final TextPaint headerTextPaint;
        private final TextPaint subHeaderTextPaint;
        private final RectF rectF;
        private final Rect textBounds = new Rect();
        private boolean highlighted;

        public ItemView(Context context, int drawableRes, String header, String subHeader) {
            super(context);
            this.header = header;
            this.subHeader = subHeader;

            Drawable d = ContextCompat.getDrawable(context, drawableRes);
            icon = d != null ? d.mutate() : null;
            if (icon != null) {
                icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            }

            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setColor(0x16D8D8D8);

            headerTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            headerTextPaint.setColor(Color.WHITE);
            headerTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics()));
            headerTextPaint.setTypeface(AndroidUtilities.bold());

            subHeaderTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            subHeaderTextPaint.setColor(0x96FFFFFF);
            subHeaderTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics()));

            rectF = new RectF();
        }

        public int getRequiredWidth() {
            headerTextPaint.getTextBounds(header, 0, header.length(), textBounds);
            int headerWidth = textBounds.width();
            subHeaderTextPaint.getTextBounds(subHeader, 0, subHeader.length(), textBounds);
            int subHeaderWidth = textBounds.width();
            return AndroidUtilities.dp(88) + AndroidUtilities.dp(8) + Math.max(headerWidth, subHeaderWidth);
        }

        public void setHighlighted(boolean highlighted) {
            this.highlighted = highlighted;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            int iconCx = AndroidUtilities.dp(40);
            int iconCy = getMeasuredHeight() / 2;
            int size = AndroidUtilities.dp(24);
            int left = iconCx - size / 2;
            int top = iconCy - size / 2;
            if (icon != null) {
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(canvas);
            }

            if (highlighted) {
                rectF.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                backgroundPaint.setAlpha(30);
                canvas.drawRoundRect(rectF, AndroidUtilities.dpf2(12f), AndroidUtilities.dpf2(12f), backgroundPaint);
            }

            canvas.drawText(header, AndroidUtilities.dpf2(80), getMeasuredHeight() / 2f - AndroidUtilities.dpf2(4), headerTextPaint);
            canvas.drawText(subHeader, AndroidUtilities.dpf2(80), getMeasuredHeight() / 2f + AndroidUtilities.dpf2(18), subHeaderTextPaint);
        }
    }
}
