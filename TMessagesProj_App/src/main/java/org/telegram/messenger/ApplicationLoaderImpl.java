package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.ViewGroup;

import androidx.core.content.FileProvider;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ForceUpdateOverlay;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LaunchActivity;

import java.io.File;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public boolean isCustomUpdate() {
        return true;
    }

    @Override
    protected boolean isBeta() {
        return true;
    }

    @Override
    public BetaUpdate getUpdate() {
        return AppUpdaterController.getInstance().getUpdate();
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        AppUpdaterController.getInstance().checkForUpdate(force, whenDone);
    }

    @Override
    public void downloadUpdate() {
        AppUpdaterController.getInstance().downloadUpdate();
    }

    @Override
    public void cancelDownloadingUpdate() {
        AppUpdaterController.getInstance().cancelDownloadingUpdate();
    }

    @Override
    public boolean isDownloadingUpdate() {
        return AppUpdaterController.getInstance().isDownloading();
    }

    @Override
    public float getDownloadingUpdateProgress() {
        return AppUpdaterController.getInstance().getDownloadingProgress();
    }

    @Override
    public File getDownloadedUpdateFile() {
        return AppUpdaterController.getInstance().getDownloadedFile();
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenuContainer);
    }

    private ForceUpdateOverlay forceUpdateOverlay;

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        try {
            if (update.isRequired) {
                showForceUpdateOverlay(update);
            } else {
                removeForceUpdateOverlay();
                (new UpdateAppAlertDialog(context, update, account)).show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    private void removeForceUpdateOverlay() {
        if (forceUpdateOverlay != null && forceUpdateOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) forceUpdateOverlay.getParent()).removeView(forceUpdateOverlay);
        }
        forceUpdateOverlay = null;
    }

    private void showForceUpdateOverlay(BetaUpdate update) {
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.drawerLayoutContainer == null) return;
        if (forceUpdateOverlay != null && forceUpdateOverlay.getParent() == activity.drawerLayoutContainer) return;
        if (activity.drawerLayoutContainer.getWidth() == 0 || activity.drawerLayoutContainer.getHeight() == 0) {
            activity.drawerLayoutContainer.post(() -> showForceUpdateOverlay(update));
            return;
        }
        forceUpdateOverlay = new ForceUpdateOverlay(activity, activity.drawerLayoutContainer, update);
        activity.drawerLayoutContainer.addView(forceUpdateOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    @Override
    public boolean checkApkInstallPermissions(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(context, null).show();
            return false;
        }
        return true;
    }

    @Override
    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        boolean exists = false;
        try {
            final String fileName = FileLoader.getAttachFileName(document);
            final File f = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true);
            if (exists = f.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                if (Build.VERSION.SDK_INT >= 24) {
                    intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f), "application/vnd.android.package-archive");
                } else {
                    intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                }
                try {
                    activity.startActivityForResult(intent, 500);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return exists;
    }
}
