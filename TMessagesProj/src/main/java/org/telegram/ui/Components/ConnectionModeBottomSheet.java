package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.VpnConnectionHelper;
import org.telegram.messenger.VpnConnectionMode;
import org.telegram.messenger.VpnStatusController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;

import java.util.ArrayList;

public class ConnectionModeBottomSheet extends BottomSheet {

    private static final int CARD_BACKGROUND_LIGHT = 0xFFF2F2F7;

    private final BaseFragment fragment;
    private final VpnConnectionHelper vpnHelper;
    private final ArrayList<ModeCell> modeCells = new ArrayList<>();
    private final TextCheckCell enabledCell;
    private final LinearLayout protocolSection;
    private TextCheckCell toggleOnCloseCell;
    private TextCheckCell toggleOnMinimizeCell;
    private LinearLayout settingsSection;
    private HeaderCell settingsHeader;
    private Runnable pendingCancel;
    private boolean cleanedUp;
    private final VpnStatusController.Listener vpnStatusListener;

    public ConnectionModeBottomSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        this.vpnHelper = new VpnConnectionHelper(fragment);

        Context context = fragment.getParentActivity();
        setApplyBottomPadding(false);
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundGray));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.AppName));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setGravity(Gravity.CENTER);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 12));

        LinearLayout enabledSection = createCard(context);
        container.addView(enabledSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        enabledCell = new TextCheckCell(context, resourcesProvider);
        enabledCell.setBackground(Theme.getSelectorDrawable(getThemedColor(Theme.key_listSelector), false));
        enabledCell.setTextAndCheck(LocaleController.getString(R.string.ConnectionModeUseTunnel), VpnStatusController.isVpnActive(), false);
        enabledCell.setOnClickListener(v -> onEnabledClicked());
        enabledSection.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // Тумблер отражает реальное состояние VPN, а не намерение из prefs.
        vpnStatusListener = status -> {
            if (pendingCancel == null) {
                enabledCell.setChecked(VpnStatusController.isVpnActive());
            }
        };
        VpnStatusController.addListener(vpnStatusListener);

        HeaderCell header = new HeaderCell(context, resourcesProvider);
        header.setText(LocaleController.getString(R.string.ConnectionModeHeader));
        container.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        protocolSection = createCard(context);
        container.addView(protocolSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        addModeCell(context, VpnConnectionMode.VPRAY, R.string.ConnectionModeVpRay, R.string.ConnectionModeVpRaySubtitle, true);
        addModeCell(context, VpnConnectionMode.AWG, R.string.ConnectionModeAwg, R.string.ConnectionModeAwgSubtitle, true);
        addModeCell(context, VpnConnectionMode.AUTO, R.string.ConnectionModeAuto, R.string.ConnectionModeAutoSubtitle, false);

        settingsHeader = new HeaderCell(context, Theme.key_windowBackgroundWhiteBlueHeader, 20, 6, true, resourcesProvider);
        settingsHeader.setText(LocaleController.getString(R.string.ConnectionModeSettingsHeader));
        settingsHeader.getTextView2().setTextSize(15);
        settingsHeader.getTextView2().setTypeface(AndroidUtilities.bold());
        settingsHeader.getTextView2().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        settingsHeader.getTextView2().setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
        settingsHeader.setText2(LocaleController.getString(R.string.ConnectionModeSettingsCaption));
        FrameLayout.LayoutParams captionLp = (FrameLayout.LayoutParams) settingsHeader.getTextView2().getLayoutParams();
        captionLp.topMargin = AndroidUtilities.dp(6);
        container.addView(settingsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        settingsSection = createCard(context);
        container.addView(settingsSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        toggleOnCloseCell = addSettingToggle(context, settingsSection,
                R.string.ConnectionModeToggleOnClose, VpnConnectionMode.isToggleOnClose(), true,
                v -> {
                    boolean value = !VpnConnectionMode.isToggleOnClose();
                    VpnConnectionMode.setToggleOnClose(value);
                    toggleOnCloseCell.setChecked(value);
                    if (!value && VpnConnectionMode.isToggleOnMinimize()) {
                        VpnConnectionMode.setToggleOnMinimize(false);
                        toggleOnMinimizeCell.setChecked(false);
                    }
                });
        toggleOnMinimizeCell = addSettingToggle(context, settingsSection,
                R.string.ConnectionModeToggleOnMinimize, VpnConnectionMode.isToggleOnMinimize(), false,
                v -> {
                    boolean value = !VpnConnectionMode.isToggleOnMinimize();
                    VpnConnectionMode.setToggleOnMinimize(value);
                    toggleOnMinimizeCell.setChecked(value);
                    if (value && !VpnConnectionMode.isToggleOnClose()) {
                        VpnConnectionMode.setToggleOnClose(true);
                        toggleOnCloseCell.setChecked(true);
                    }
                });

        updateChecked(VpnConnectionMode.getSelected(), false);
        updateSettingsEnabled();
        container.setPadding(0, 0, 0, AndroidUtilities.dp(48));
        setCustomView(container);
    }

    private LinearLayout createCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int background = Theme.isCurrentThemeDark()
                ? getThemedColor(Theme.key_windowBackgroundGray)
                : CARD_BACKGROUND_LIGHT;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(AndroidUtilities.dp(24));
        shape.setColor(background);
        card.setBackground(shape);
        card.setClipToOutline(true);
        return card;
    }

    private TextCheckCell addSettingToggle(Context context, LinearLayout section, int titleRes,
                                           boolean checked, boolean divider, View.OnClickListener onClick) {
        TextCheckCell cell = new TextCheckCell(context, resourcesProvider);
        cell.setBackground(Theme.getSelectorDrawable(getThemedColor(Theme.key_listSelector), false));
        cell.setTextAndCheck(LocaleController.getString(titleRes), checked, divider);
        cell.setOnClickListener(onClick);
        section.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private void addModeCell(Context context, VpnConnectionMode mode, int titleRes, int subtitleRes, boolean divider) {
        ModeCell cell = new ModeCell(context, mode);
        cell.set(LocaleController.getString(titleRes), LocaleController.getString(subtitleRes), divider);
        cell.setBackground(Theme.getSelectorDrawable(getThemedColor(Theme.key_listSelector), false));
        cell.setOnClickListener(v -> onModeClicked(mode));
        protocolSection.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        modeCells.add(cell);
    }

    private void onEnabledClicked() {
        if (pendingCancel != null) {
            // Идёт запрос VPN-разрешения — не даём второму флоу перезаписать откат первого.
            return;
        }
        boolean enabled = !VpnStatusController.isVpnActive();
        VpnConnectionMode.setEnabled(enabled);
        enabledCell.setChecked(enabled);
        if (enabled && VpnConnectionMode.getEffective() == VpnConnectionMode.AWG) {
            Runnable revert = () -> revertEnabled(false);
            pendingCancel = revert;
            vpnHelper.startVpnAndThen(
                    () -> {
                        pendingCancel = null;
                        ApplicationLoader.applyEffectiveConnectionMode();
                    },
                    () -> {
                        pendingCancel = null;
                        revert.run();
                    });
        } else {
            pendingCancel = null;
            ApplicationLoader.applyEffectiveConnectionMode();
        }
    }

    private void revertEnabled(boolean enabled) {
        VpnConnectionMode.setEnabled(enabled);
        ApplicationLoader.applyEffectiveConnectionMode();
        // Тумблер приводим к фактическому состоянию VPN, а не к prefs.
        enabledCell.setChecked(VpnStatusController.isVpnActive());
    }

    private void onModeClicked(VpnConnectionMode mode) {
        if (pendingCancel != null) {
            return;
        }
        VpnConnectionMode previous = VpnConnectionMode.getSelected();
        boolean previousEnabled = VpnConnectionMode.isEnabled();
        if (mode == previous) {
            return;
        }
        VpnConnectionMode.setSelected(mode);
        updateChecked(mode, true);
        updateSettingsEnabled();
        VpnConnectionMode.setEnabled(true);
        enabledCell.setChecked(true);
        if (VpnConnectionMode.getEffective() == VpnConnectionMode.AWG) {
            VpnStatusController.setStatus(VpnStatusController.Status.CONNECTING);
            Runnable revert = () -> {
                if (VpnConnectionMode.getSelected() == mode) {
                    VpnConnectionMode.setSelected(previous);
                    VpnConnectionMode.setEnabled(previousEnabled); // восстанавливаем прежнее намерение
                    updateChecked(previous, true);
                    enabledCell.setChecked(VpnStatusController.isVpnActive());
                    ApplicationLoader.applyEffectiveConnectionMode();
                }
            };
            pendingCancel = revert;
            vpnHelper.startVpnAndThen(
                    () -> {
                        pendingCancel = null;
                        ApplicationLoader.applyEffectiveConnectionMode();
                    },
                    () -> {
                        pendingCancel = null;
                        revert.run();
                    });
        } else {
            pendingCancel = null;
            ApplicationLoader.applyEffectiveConnectionMode();
        }
    }

    private void updateChecked(VpnConnectionMode selected, boolean animated) {
        for (int i = 0; i < modeCells.size(); i++) {
            ModeCell cell = modeCells.get(i);
            cell.setChecked(cell.mode == selected, animated);
        }
    }

    /**
     * Настройки жизненного цикла относятся только к AWG/Авто (см. caption
     * «для AWG и Авто»). В режиме vpRay они неактуальны — дизейблим блок.
     */
    private void updateSettingsEnabled() {
        boolean enabled = VpnConnectionMode.getSelected() != VpnConnectionMode.VPRAY;
        settingsHeader.setAlpha(enabled ? 1f : 0.5f);
        settingsSection.setAlpha(enabled ? 1f : 0.5f);
        toggleOnCloseCell.setEnabled(enabled);
        toggleOnMinimizeCell.setEnabled(enabled);
    }

    @Override
    public void dismissInternal() {
        if (!cleanedUp) {
            cleanedUp = true;
            VpnStatusController.removeListener(vpnStatusListener);
            Runnable cancel = pendingCancel;
            pendingCancel = null;
            vpnHelper.release();
            if (cancel != null) {
                cancel.run();
            }
        }
        super.dismissInternal();
    }

    public void onResume() {
        vpnHelper.handleResume();
    }

    public boolean onActivityResult(int requestCode, int resultCode) {
        return vpnHelper.handleActivityResult(requestCode, resultCode);
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        vpnHelper.handlePermissionResult(requestCode, permissions, grantResults);
    }

    private class ModeCell extends FrameLayout {

        final VpnConnectionMode mode;
        private final ConnectionModeCheckView checkBox;
        private final TextView titleView;
        private final TextView subtitleView;
        private boolean needDivider;
        private boolean checked;

        ModeCell(Context context, VpnConnectionMode mode) {
            super(context);
            this.mode = mode;
            setWillNotDraw(false);

            checkBox = new ConnectionModeCheckView(context);
            addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setLines(1);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                    LocaleController.isRTL ? 16 : 52, 0, LocaleController.isRTL ? 52 : 16, 0));
        }

        void set(String title, String subtitle, boolean divider) {
            titleView.setText(title);
            subtitleView.setText(subtitle);
            needDivider = divider;
        }

        void setChecked(boolean checked, boolean animated) {
            this.checked = checked;
            checkBox.setChecked(checked, animated);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(checked);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(60), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(AndroidUtilities.dp(LocaleController.isRTL ? 0 : 52), getMeasuredHeight() - 1,
                        getMeasuredWidth() - AndroidUtilities.dp(LocaleController.isRTL ? 52 : 0), getMeasuredHeight() - 1,
                        Theme.dividerPaint);
            }
        }
    }
}
