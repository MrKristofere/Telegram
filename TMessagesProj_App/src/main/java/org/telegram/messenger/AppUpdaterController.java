package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.ui.LaunchActivity;
import org.telegram.ui.web.HttpGetFileTask;

import vpn.sdk.AppUpdateResult;
import vpn.sdk.VpnSDK;

import java.io.File;

public class AppUpdaterController {

    private static final long CHECK_INTERVAL_PAUSED = 1000 * 60 * 60 * 24; // 1 day
    private static final long CHECK_INTERVAL = 1000 * 60 * 60 * 24; // 1 day
    private static final long CHECK_INTERVAL_DEBUG = 1000 * 60 * 4; // 4 minutes

    private boolean firstCheck = true;
    private boolean checkingForUpdate;
    private final Runnable scheduledUpdateCheck = () -> checkForUpdate(false, null);

    private static AppUpdaterController instance;
    public static AppUpdaterController getInstance() {
        if (instance == null) {
            instance = new AppUpdaterController();
        }
        return instance;
    }

    private String version;
    private int versionCode;
    private String changelog;
    private String fileUrl;
    private String path;
    private boolean isRequired;
    private long lastCheck;

    public AppUpdaterController() {
        load();
    }

    private SharedPreferences getSharedPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("beta_update", Activity.MODE_PRIVATE);
    }

    private void load() {
        final SharedPreferences prefs = getSharedPreferences();

        version = prefs.getString("version", null);
        versionCode = prefs.getInt("versionCode", 0);
        changelog = prefs.getString("changelog", null);
        path = prefs.getString("path", null);
        isRequired = prefs.getBoolean("isRequired", false);
        lastCheck = prefs.getLong("lastCheck", 0L);

        if (getCurrentVersionCode() >= versionCode) {
            version = null;
            versionCode = 0;
            path = null;
            changelog = null;
            isRequired = false;
            lastCheck = 0;
            save();
        } else if (!TextUtils.isEmpty(path) && !new File(path).exists()) {
            path = null;
            save();
        }
    }

    private void save() {
        final SharedPreferences.Editor e = getSharedPreferences().edit();
        if (TextUtils.isEmpty(version)) {
            e.remove("version");
        } else {
            e.putString("version", version);
        }
        if (TextUtils.isEmpty(changelog)) {
            e.remove("changelog");
        } else {
            e.putString("changelog", changelog);
        }
        if (versionCode == 0) {
            e.remove("versionCode");
        } else {
            e.putInt("versionCode", versionCode);
        }
        if (TextUtils.isEmpty(path)) {
            e.remove("path");
        } else {
            e.putString("path", path);
        }
        e.putBoolean("isRequired", isRequired);
        if (lastCheck == 0) {
            e.remove("lastCheck");
        } else {
            e.putLong("lastCheck", lastCheck);
        }
        e.apply();
    }

    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (checkingForUpdate) return;

        if (firstCheck) {
            force = true;
        }
        if (!force && System.currentTimeMillis() - lastCheck < (ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : (BuildVars.DEBUG_PRIVATE_VERSION ? CHECK_INTERVAL_DEBUG : CHECK_INTERVAL))) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }

        checkingForUpdate = true;
        firstCheck = false;

        VpnSDK.fetchAppUpdate(updateInfo -> AndroidUtilities.runOnUIThread(() -> {
            checkingForUpdate = false;
            if (updateInfo == null) {
                FileLog.w("Failed to fetch update info from all sources");
                if (!TextUtils.isEmpty(path)) {
                    try {
                        new File(path).delete();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                version = null;
                versionCode = 0;
                fileUrl = null;
                changelog = null;
                path = null;
                isRequired = false;
                save();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                AndroidUtilities.cancelRunOnUIThread(this.scheduledUpdateCheck);
                AndroidUtilities.runOnUIThread(this.scheduledUpdateCheck, CHECK_INTERVAL);
                if (whenDone != null) {
                    whenDone.run();
                }
                return;
            }

            processUpdateInfo(updateInfo, whenDone);
        }));
    }

    private void processUpdateInfo(AppUpdateResult info, Runnable whenDone) {
        try {
            final String newVersion = info.getVersion();
            final int newVersionCode = info.getVersionCode();
            final String newFileUrl = info.getFileUrl();
            final String newChangelog = info.getChangelog();
            final boolean newIsRequired = info.isRequired();

            final int oldVersionCode = this.versionCode;

            if (
                (version == null || SharedConfig.versionBiggerOrEqual(newVersion, version) && newVersionCode > versionCode) &&
                SharedConfig.versionBiggerOrEqual(newVersion, getCurrentVersion()) && newVersionCode > getCurrentVersionCode()
            ) { // received newer version
                if (!TextUtils.isEmpty(path)) {
                    try {
                        new File(path).delete();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                path = null;
                version = newVersion;
                versionCode = newVersionCode;
                this.fileUrl = newFileUrl;
                this.changelog = newChangelog;
                this.isRequired = newIsRequired;
            } else if (
                version != null && versionCode != 0 && SharedConfig.versionBiggerOrEqual(version, newVersion) && versionCode == newVersionCode
            ) { // received the same version
                this.fileUrl = newFileUrl;
                this.changelog = newChangelog;
                this.isRequired = newIsRequired;
            } else { // received different version
                if (!TextUtils.isEmpty(path)) {
                    try {
                        new File(path).delete();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                path = null;
                if (SharedConfig.versionBiggerOrEqual(getCurrentVersion(), newVersion) && getCurrentVersionCode() < newVersionCode) {
                    // remote version code is still newer than installed
                    version = newVersion;
                    versionCode = newVersionCode;
                    this.fileUrl = newFileUrl;
                    this.changelog = newChangelog;
                    this.isRequired = newIsRequired;
                } else {
                    version = null;
                    versionCode = 0;
                    this.fileUrl = null;
                    this.changelog = null;
                    this.isRequired = false;
                }
            }

            this.lastCheck = System.currentTimeMillis();
            save();

            if (this.versionCode != oldVersionCode) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            }

            AndroidUtilities.cancelRunOnUIThread(this.scheduledUpdateCheck);
            AndroidUtilities.runOnUIThread(this.scheduledUpdateCheck, BuildVars.DEBUG_PRIVATE_VERSION ? CHECK_INTERVAL_DEBUG : CHECK_INTERVAL);

            if (whenDone != null) {
                whenDone.run();
            } else if ((this.versionCode != oldVersionCode || this.isRequired) && !ApplicationLoader.mainInterfacePaused) {
                final Context context = LaunchActivity.instance != null ? LaunchActivity.instance : ApplicationLoader.applicationContext;
                final BetaUpdate pendingUpdate = getUpdate();
                if (context != null && pendingUpdate != null) {
                    ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(context, pendingUpdate, UserConfig.selectedAccount);
                }
            }
        } catch (Exception e) {
            FileLog.e("Failed to process update info", e);
        }
    }

    public BetaUpdate getUpdate() {
        if (version == null || versionCode == 0) {
            return null;
        }
        return new BetaUpdate(version, versionCode, changelog, isRequired);
    }

    private boolean downloading;
    private float downloadingProgress;
    private HttpGetFileTask downloadingTask;

    public void downloadUpdate() {
        downloadUpdate(false);
    }

    private void downloadUpdate(boolean triedGettingFileUrl) {
        if (downloading || !TextUtils.isEmpty(path)) return;

        downloading = true;
        downloadingProgress = 0.0f;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);

        if (TextUtils.isEmpty(fileUrl)) {
            if (!triedGettingFileUrl) {
                checkForUpdate(true, () -> downloadUpdate(true));
            } else {
                downloading = false;
            }
            return;
        }

        downloadingTask = new HttpGetFileTask(
            downloadedFile -> AndroidUtilities.runOnUIThread(() -> {
                if (downloadedFile != null) {
                    if (!TextUtils.isEmpty(path)) {
                        try {
                            new File(path).delete();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                    path = downloadedFile.getAbsolutePath();
                    save();
                    downloadingProgress = 1.0f;
                    downloading = false;
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                } else {
                    downloading = false;
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                }
            }),
            progress -> {
                downloadingProgress = progress;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
            }
        ).setOverrideExtension("apk");
        downloadingTask.execute(fileUrl);
    }

    public void cancelDownloadingUpdate() {
        if (!downloading) return;
        if (downloadingTask != null) {
            downloadingTask.cancel(false);
        }
        downloading = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    public File getDownloadedFile() {
        if (path == null) return null;
        final File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }

    private String getCurrentVersion() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    private int getCurrentVersionCode() {
        try {
            int code = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
            return code / 10;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }
}
