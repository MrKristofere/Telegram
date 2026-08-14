/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.json.JSONObject;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.Locale;

import android.net.VpnService;
import vpn.sdk.VpnSDK;
import vpn.tunnel.VpnTunnelState;

public class ApplicationLoader extends Application {

    public static ApplicationLoader applicationLoaderInstance;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;
    private static int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static IMapsProvider mapsProvider;
    private static ILocationServiceProvider locationServiceProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    public static ILocationServiceProvider getLocationServiceProvider() {
        if (locationServiceProvider == null) {
            locationServiceProvider = applicationLoaderInstance.onCreateLocationServiceProvider();
            locationServiceProvider.init(applicationContext);
        }
        return locationServiceProvider;
    }

    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new GoogleLocationProvider();
    }

    public static IMapsProvider getMapsProvider() {
        if (mapsProvider == null) {
            mapsProvider = applicationLoaderInstance.onCreateMapsProvider();
        }
        return mapsProvider;
    }

    protected IMapsProvider onCreateMapsProvider() {
        return new GoogleMapsProvider();
    }

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = applicationLoaderInstance.onCreatePushProvider();
        }
        return pushProvider;
    }

    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return PushListenerController.GooglePushListenerServiceProvider.INSTANCE;
    }

    public static String getApplicationId() {
        return applicationLoaderInstance.onGetApplicationId();
    }

    protected String onGetApplicationId() {
        return null;
    }

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return applicationLoaderInstance.isStandalone();
    }

    public static boolean isBetaBuild() {
        return applicationLoaderInstance.isBeta();
    }

    public static boolean isAndroidTestEnvironment() {
        return applicationLoaderInstance.isAndroidTestEnv();
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return false;
    }

    protected boolean isBeta() {
        return false;
    }

    protected boolean isAndroidTestEnv() {
        return false;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/org.telegram.messenger/files");
    }

    public static File getFilesDirFixed(String child) {
        try {
            File path = getFilesDirFixed();
            File dir = new File(path, child);
            dir.mkdirs();

            return dir;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance(); //TODO improve
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        SharedPrefsHelper.init(applicationContext);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        // Enforce xray proxy on all accounts after ConnectionsManager instances are initialized.
        // ConnectionsManager.init() reads SharedPreferences on creation, but we call this
        // explicitly to guarantee the native layer has the correct proxy settings.
        if (VpnSDK.isProxyRunning()) {
            org.telegram.tgnet.ConnectionsManager.setProxySettings(
                    true,
                    VpnSDK.getProxySocksHost(),
                    VpnSDK.getProxySocksPort(),
                    "", "", ""
            );
            FileLog.d("xray proxy enforced on ConnectionsManager: " +
                    VpnSDK.getProxySocksHost() + ":" + VpnSDK.getProxySocksPort());
        } else {
            FileLog.e("xray proxy not running at postInit — no proxy set. error: " + VpnSDK.getProxyLastError());
        }

        ApplicationLoader app = (ApplicationLoader) ApplicationLoader.applicationContext;
        app.initPushServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
        BillingController.getInstance().startConnection();
    }

    public ApplicationLoader() {
        super();
    }

    private static boolean isXrayProcess() {
        String processName = null;
        if (Build.VERSION.SDK_INT >= 28) {
            processName = Application.getProcessName();
        } else {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"))) {
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = reader.read()) > 0) {
                    sb.append((char) c);
                }
                processName = sb.toString();
            } catch (Exception e) {
                // FileLog is off-limits here: it would initialize file logging
                // in the :xray process. Failing open means the full Telegram
                // stack (and a second Go runtime) comes up in :xray — log loudly.
                android.util.Log.e("ApplicationLoader", "failed to read process name from /proc/self/cmdline", e);
            }
        }
        return processName != null && processName.endsWith(":xray");
    }

    @Override
    public void onCreate() {
        applicationLoaderInstance = this;
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        super.onCreate();

        // Application.onCreate() runs in EVERY process of the app. The ":xray"
        // process exists solely to host libXray (see vpn.proxy.XrayProxyService)
        // and must stay lightweight: no native Telegram libs, no accounts, no
        // push, no VpnSDK — and crucially no AmneziaWG Go runtime next to
        // libXray's, which is the exact conflict the process split avoids.
        if (isXrayProcess()) {
            return;
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                final String abi;
                switch (info.versionCode % 10) {
                    case 1:
                    case 2:
                        abi = "store bundled " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        break;
                    default:
                    case 9:
                        if (ApplicationLoader.isStandaloneBuild()) {
                            abi = "direct " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        } else {
                            abi = "universal " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        }
                        break;
                }
                FileLog.d("buildVersion = " + String.format(Locale.US, "v%s (%d[%d]) %s", info.versionName, info.versionCode / 10, info.versionCode % 10, abi));
            } catch (Exception e) {
                FileLog.e(e);
            }
            FileLog.d("device = manufacturer=" + Build.MANUFACTURER + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", product=" + Build.PRODUCT);
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder());
        }
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                    VpnSDK.updateConfig();
                    refreshXrayConfigIfActivated();
                    VpGramRemoteConfig.fetch();
                    TrackedChannelBannerController.onAppForeground();
                    onForegroundResolveAwg();
                    VpnStatusController.refresh();
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                super.onActivityStopped(activity);
                if (isBackground()) {
                    disconnectTunnelOnMinimizeIfNeeded();
                }
            }
        };
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        // Initialize VPN SDK and fetch remote config
        VpnSDK.setup(applicationContext);
        VpnSDK.updateConfig();
        VpnStatusController.init();

        // Fetch vpGram Firebase Remote Config (tracked channel banner id/url)
        VpGramRemoteConfig.fetch();

        // Xray exists only for authenticated users: login traffic must go
        // through the AWG tunnel (Telegram doesn't deliver the auth code to
        // sessions from VLESS exit IPs), so before the first successful auth
        // the proxy is never started and no /auth/register is issued —
        // switchToPostAuthConnectionMode() does both once login completes.
        //
        // UserConfig.getActivatedAccountsCount() is unusable here: loadConfig()
        // runs later, in postInitApplication(), so the in-memory check always
        // sees zero accounts during Application.onCreate(). Read the userconfig
        // prefs directly instead.
        if (hasAuthorizedAccountOnDisk()) {
            if (VpnConnectionMode.getEffective() == VpnConnectionMode.AWG) {
                if (runAwgMode()) {
                    persistXrayProxyToMainConfig(false);
                    FileLog.d("cold start: AWG mode, resolving by cold-start toggle");
                    bringUpAwgIfWanted(VpnConnectionMode.isToggleOnClose());
                } else {
                    if (VpnConnectionMode.isToggleOnClose()) {
                        pendingAwgReclaim = true;
                    }
                    startXrayCarrierOnColdStart();
                }
            } else if (!VpnConnectionMode.isEnabled()) {
                persistXrayProxyToMainConfig(false);
                FileLog.d("vpn master switch off; nothing is brought up");
            } else {
                startXrayCarrierOnColdStart();
            }

            // Refresh xray config in background for already-authenticated users.
            refreshXrayConfigIfActivated();
        } else {
            persistXrayProxyToMainConfig(false);
            FileLog.d("no authorized accounts; xray stays down until login completes");
        }


        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);

        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();
    }

    /** Refresh no more often than once per 5 minutes per process. */
    private static final long XRAY_REFRESH_MIN_INTERVAL_MS = 5L * 60L * 1000L;
    private static volatile long lastXrayRefreshAtMs = 0L;

    /**
     * True while the login flow routes MTProto directly through the AWG tunnel
     * (xray off). Set by {@link #disableXrayProxyForLogin()}, cleared by
     * {@link #switchToPostAuthConnectionMode()}. While set, background xray
     * refreshes must not re-enable the proxy or touch the tunnel.
     */
    private static volatile boolean loginInProgress;

    /**
     * True when the post-auth switch to xray failed and the AWG tunnel was
     * deliberately left up. Lets the next successful xray refresh finish the
     * deferred switch (tunnel down) without disconnecting a tunnel the user
     * brought up manually.
     */
    private static volatile boolean pendingPostAuthTunnelDown;

    /**
     * Pushes the current xray SOCKS5 address into the native ConnectionsManager
     * so all accounts switch over at runtime, AND persists it to mainconfig so
     * the next cold start picks it up before ConnectionsManager.init() runs.
     * Safe to call repeatedly with the same value — the native side reapplies.
     */
    public static void applyXrayProxyToConnectionsManager() {
        persistXrayProxyToMainConfig(true);
        ConnectionsManager.setProxySettings(
                true,
                VpnSDK.getProxySocksHost(),
                VpnSDK.getProxySocksPort(),
                "", "", ""
        );
        VpnStatusController.setStatus(VpnStatusController.Status.CONNECTED);
    }

    /**
     * Turns the xray SOCKS5 proxy off for the login flow: the auth code is
     * not delivered to sessions coming from VLESS exit IPs, so MTProto has to
     * go directly through the AWG tunnel while the user signs in.
     * {@link #switchToPostAuthConnectionMode()} re-enables it after auth.
     */
    public static void disableXrayProxyForLogin() {
        loginInProgress = true;
        persistXrayProxyToMainConfig(false);
        ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
    }

    /**
     * Applies the post-auth connection mode after a successful login. For now
     * the default (and only) mode is xray: route MTProto/VoIP through the
     * local SOCKS5 proxy and bring the AWG tunnel down. When the AWG / vpRay /
     * Auto mode setting lands ("Настройки соединения"), this is the single
     * place that should consult it.
     *
     * On a first login there is no cached xray config yet — /auth/register is
     * issued right here, through the still-up AWG tunnel, so the backend is
     * reachable even when it's blocked on the direct network. Until (and
     * unless) xray comes up, the fresh account keeps working through AWG.
     */
    public static void switchToPostAuthConnectionMode() {
        loginInProgress = false;
        applyEffectiveConnectionMode();
    }

    /**
     * Applies {@link VpnConnectionMode#getEffective()} at runtime. Called
     * after a successful login and when the user changes the mode in
     * connection settings. For AWG the VPN permission must already be granted
     * (the settings screen requests it via VpnConnectionHelper and calls this
     * only once the tunnel is up; after login it's granted since the tunnel
     * is up) — otherwise {@link #runAwgMode()} falls back to the xray path.
     * <p>
     * ИНВАРИАНТ (Option A): {@link VpnConnectionMode#isEnabled()} здесь означает
     * ТЕКУЩЕЕ вкл/выкл-намерение пользователя — вызывающий обязан выставить его
     * перед вызовом: смена режима/включение → {@code setEnabled(true)}, ручное
     * выключение/откат → {@code setEnabled(false)}. Автоподъём по lifecycle-
     * тумблерам ({@link #bringUpAwgIfWanted}) сюда НЕ ходит и на isEnabled не
     * смотрит. Не вызывайте этот метод, полагаясь на «старое» значение isEnabled.
     */
    public static void applyEffectiveConnectionMode() {
        if (loginInProgress) {
            // The login flow owns the connection state right now.
            FileLog.d("apply mode skipped: login in progress");
            return;
        }
        removeAwgActivateWatcher();
        if (!VpnConnectionMode.isEnabled()) {
            pendingPostAuthTunnelDown = false;
            persistXrayProxyToMainConfig(false);
            ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            VpnSDK.stopProxy();
            if (VpnSDK.getTunnelState() != VpnTunnelState.DOWN) {
                VpnStatusController.markDeliberateDisconnect();
                disconnectTunnelIfUp();
            }
            VpnStatusController.setStatus(VpnStatusController.Status.OFF);
            FileLog.d("vpn master switch off: xray stopped, tunnel down");
            return;
        }
        if (runAwgMode()) {
            // AWG: tunnel carries everything, xray off. After login the
            // tunnel is already up; after a settings change it may be down.
            pendingPostAuthTunnelDown = false;
            persistXrayProxyToMainConfig(false);
            ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            connectTunnelIfPermitted();
            VpnStatusController.refresh();
            FileLog.d("connection mode AWG applied: xray off, tunnel up");
            return;
        }
        VpnStatusController.setStatus(VpnStatusController.Status.CONNECTING);
        VpnSDK.startProxyAsync(started -> {
            if (loginInProgress) {
                // A new login began while xray was coming up — leave the auth
                // traffic on the AWG tunnel; the next switch call will apply.
                FileLog.d("post-auth: login restarted during xray start; deferring");
                return;
            }
            if (!VpnConnectionMode.isEnabled()) {
                FileLog.d("vpRay apply cancelled: master switch off");
                return;
            }
            if (runAwgMode()) {
                // The user switched to AWG while xray was coming up.
                FileLog.d("vpRay apply cancelled: mode changed to AWG");
                return;
            }
            if (started) { // already running or started from cache
                pendingPostAuthTunnelDown = false;
                applyXrayProxyToConnectionsManager();
                disconnectTunnelIfUp();
                FileLog.d("post-auth: switched to xray, AWG tunnel down");
                return;
            }
            FileLog.d("post-auth: xray didn't start from cache (no config, start failed or :xray unreachable: "
                    + VpnSDK.getProxyLastError() + "); registering through AWG tunnel");
            VpnSDK.registerOrAuth(3, success -> {
                if (loginInProgress) {
                    FileLog.d("post-auth: login restarted during register; deferring");
                    return;
                }
                if (!VpnConnectionMode.isEnabled()) {
                    FileLog.d("vpRay apply cancelled: master switch off");
                    return;
                }
                if (runAwgMode()) {
                    FileLog.d("vpRay apply cancelled: mode changed to AWG");
                    return;
                }
                if (success) {
                    pendingPostAuthTunnelDown = false;
                    applyXrayProxyToConnectionsManager();
                    disconnectTunnelIfUp();
                    FileLog.d("post-auth: xray registered and applied, AWG tunnel down");
                } else {
                    // Keep the AWG tunnel up so the fresh account isn't left on a
                    // direct (possibly blocked) connection. The next foreground
                    // refresh / cold start will retry xray and finish the switch.
                    pendingPostAuthTunnelDown = true;
                    VpnStatusController.setStatus(VpnSDK.getTunnelState() == VpnTunnelState.UP
                            ? VpnStatusController.Status.CONNECTED
                            : VpnStatusController.Status.ERROR);
                    FileLog.e("post-auth: xray register failed (" + VpnSDK.getProxyLastError()
                            + "); keeping AWG tunnel up");
                }
            });
        });
    }

    /**
     * Background /auth/register for an authenticated user with no usable
     * cached config. On success applies the fresh proxy unless an add-account
     * login started while the register was in flight — putting xray back
     * under auth traffic would route the code request to a VLESS exit IP.
     */
    private static void registerXrayInBackground() {
        VpnSDK.registerOrAuth(3, success -> {
            if (success) {
                if (loginInProgress) {
                    FileLog.d("xray initial register succeeded during login; deferring apply");
                    return;
                }
                if (!VpnConnectionMode.isEnabled()) {
                    FileLog.d("xray initial register: config cached, apply skipped (master switch off)");
                    return;
                }
                if (runAwgMode()) {
                    FileLog.d("xray initial register: config cached, apply skipped (mode AWG)");
                    return;
                }
                applyXrayProxyToConnectionsManager();
                FileLog.d("xray initial register succeeded; ConnectionsManager updated");
            } else {
                if (!loginInProgress && !runAwgMode() && VpnConnectionMode.isEnabled()) {
                    VpnStatusController.setStatus(VpnStatusController.Status.ERROR);
                }
                FileLog.e("xray initial register failed: " + VpnSDK.getProxyLastError()
                        + " — will retry on next foreground transition");
            }
        });
    }

    private static void disconnectTunnelIfUp() {
        if (VpnSDK.getTunnelState() != VpnTunnelState.DOWN) {
            VpnSDK.disconnect();
        }
    }

    private static void disconnectTunnelOnMinimizeIfNeeded() {
        // Единое правило: жизненный цикл AWG зависит только от тумблера события,
        // не от мастер-тумблера (симметрично автоподъёму, см. awgWantedAt).
        if (!VpnConnectionMode.isToggleOnMinimize()) {
            return;
        }
        if (VpnConnectionMode.getEffective() != VpnConnectionMode.AWG) {
            return;
        }
        if (VpnSDK.getTunnelState() == VpnTunnelState.UP) {
            VpnStatusController.markDeliberateDisconnect();
            VpnSDK.disconnect();
            FileLog.d("minimize: AWG tunnel brought down");
        }
    }

    /**
     * Нужно ли автоматически поднимать AWG на данном событии жизненного цикла.
     * Зависит ТОЛЬКО от тумблера события {@code lifecycleToggle} («при закрытии»
     * для холодного старта, «при сворачивании» для возврата из фона) — мастер-
     * тумблер «Использовать туннель» на автоподъём не влияет (он лишь ручное
     * вкл/выкл). Возвращает true только в режиме AWG.
     */
    private static boolean awgWantedAt(boolean lifecycleToggle) {
        return VpnConnectionMode.getEffective() == VpnConnectionMode.AWG && lifecycleToggle;
    }

    /**
     * Приводит AWG в нужное состояние на данном событии (возврат/старт):
     * слот свободен — поднимаем молча; занял сторонний VPN — просим согласие
     * (диалог покажет foreground-экран). Ничего не делаем, если поднимать не нужно
     * или пользователь уже отказался от диалога при активном стороннем VPN.
     */
    private static void bringUpAwgIfWanted(boolean lifecycleToggle) {
        if (pendingAwgReclaim || awgReclaimInProgress || VpnConnectionHelper.vpnConsentInFlight) {
            return;
        }
        if (!awgWantedAt(lifecycleToggle) || VpnSDK.getTunnelState() != VpnTunnelState.DOWN) {
            return;
        }
        try {
            if (VpnService.prepare(applicationContext) == null) {
                activateAwgTunnel();
                FileLog.d("AWG tunnel brought up (slot free)");
                return;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!awgReclaimDeclined) {
            pendingAwgReclaim = true;
            FileLog.d("AWG reclaim requested (needs VPN consent dialog)");
        }
    }

    private static void startXrayCarrierOnColdStart() {
        if (VpnSDK.hasCachedXrayConfig()) {
            persistXrayProxyToMainConfig(true);
            VpnSDK.startProxyAsync(success -> {
                if (success) {
                    if (VpnConnectionMode.isEnabled() && !runAwgMode()) {
                        VpnStatusController.setStatus(VpnStatusController.Status.CONNECTED);
                    }
                    FileLog.d("xray proxy started from cache on " + VpnSDK.getProxySocksHost() + ":" + VpnSDK.getProxySocksPort());
                } else {
                    FileLog.e("xray start from cache failed: " + VpnSDK.getProxyLastError());
                    if (!loginInProgress) {
                        persistXrayProxyToMainConfig(false);
                        ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                    }
                    registerXrayInBackground();
                }
            });
        } else {
            persistXrayProxyToMainConfig(false);
            FileLog.d("no cached xray config; registering in background");
            registerXrayInBackground();
        }
    }

    private static VpnSDK.StateListener awgActivateListener;
    private static Runnable awgActivateTimeout;
    private static final long AWG_ACTIVATE_TIMEOUT_MS = 30_000L;

    private static void activateAwgTunnel() {
        if (VpnSDK.getTunnelState() == VpnTunnelState.UP) {
            dropXrayCarrier();
            VpnStatusController.refresh();
            return;
        }
        removeAwgActivateWatcher();
        awgActivateListener = state -> {
            if (VpnConnectionMode.getEffective() != VpnConnectionMode.AWG) {
                // Режим сменился (vpRay / AUTO→vpRay через remote config) — поднятый
                // прокси теперь нужен, глушить его нельзя. Снимаем watcher.
                removeAwgActivateWatcher();
                return;
            }
            if (state == VpnTunnelState.UP) {
                removeAwgActivateWatcher();
                dropXrayCarrier();
                VpnStatusController.refresh();
            }
        };
        VpnSDK.addStateListener(awgActivateListener);
        awgActivateTimeout = ApplicationLoader::removeAwgActivateWatcher;
        AndroidUtilities.runOnUIThread(awgActivateTimeout, AWG_ACTIVATE_TIMEOUT_MS);
        connectTunnelIfPermitted();
        VpnStatusController.refresh();
    }

    private static void removeAwgActivateWatcher() {
        if (awgActivateListener != null) {
            VpnSDK.removeStateListener(awgActivateListener);
            awgActivateListener = null;
        }
        if (awgActivateTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(awgActivateTimeout);
            awgActivateTimeout = null;
        }
    }

    private static void dropXrayCarrier() {
        // Только если xray реально был поднят — иначе stopProxy() зря биндит :xray,
        // а сброс proxy-настроек и так не нужен.
        if (!VpnSDK.isProxyRunning()) {
            return;
        }
        persistXrayProxyToMainConfig(false);
        ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
        VpnSDK.stopProxy();
    }

    /** Возврат из фона: тумблер события — «при сворачивании». */
    private static void onForegroundResolveAwg() {
        if (!VpnStatusController.isOtherVpnActive()) {
            awgReclaimDeclined = false;
        }
        bringUpAwgIfWanted(VpnConnectionMode.isToggleOnMinimize());
    }

    public static volatile boolean awgReclaimDeclined;
    public static volatile boolean pendingAwgReclaim;
    public static volatile boolean awgReclaimInProgress;

    public static boolean consumePendingAwgReclaim() {
        if (pendingAwgReclaim) {
            pendingAwgReclaim = false;
            return true;
        }
        return false;
    }

    /**
     * True when the effective mode is AWG and the tunnel can actually come up
     * — i.e. the VPN permission is granted. With the permission missing or
     * revoked (another VPN app took the slot, user turned it off in system
     * settings) nothing in the background can bring the tunnel up, so callers
     * must fall back to xray instead of leaving the user on a direct
     * connection. The connection-settings screen requests the permission via
     * VpnConnectionHelper before applying AWG, so there this is transient.
     */
    static boolean runAwgMode() {
        if (VpnConnectionMode.getEffective() != VpnConnectionMode.AWG) {
            return false;
        }
        try {
            if (VpnService.prepare(applicationContext) == null) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        FileLog.d("mode AWG but VPN permission not granted; falling back to xray");
        return false;
    }

    /**
     * Brings the AWG tunnel up when the VPN permission is already granted
     * (VpnService.prepare() == null). Without the permission this is a no-op —
     * only a foreground screen can request it (VpnConnectionHelper does that
     * on the login and connection-settings screens).
     */
    private static void connectTunnelIfPermitted() {
        try {
            if (VpnSDK.getTunnelState() == VpnTunnelState.DOWN
                    && VpnService.prepare(applicationContext) == null) {
                VpnConnectionService.start(applicationContext);
                VpnSDK.toggleConnection();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Поднять AWG-туннель после того, как пользователь дал согласие на VPN в
     * системном диалоге (RESULT_OK). Вызывается из LaunchActivity — единого
     * владельца reclaim-флоу.
     */
    public static void connectAwgAfterConsent() {
        activateAwgTunnel();
    }

    /**
     * Checks authorization by reading the userconfig prefs straight from disk
     * ("user" is written on successful auth and wiped by clearConfig() on
     * logout). Unlike {@link UserConfig#getActivatedAccountsCount()}, works
     * before postInitApplication() has run loadConfig() — i.e. inside
     * {@link #onCreate()}.
     */
    private static boolean hasAuthorizedAccountOnDisk() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            String name = a == 0 ? "userconfing" : "userconfig" + a;
            SharedPreferences prefs = applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
            if (prefs.getString("user", null) != null) {
                return true;
            }
        }
        return false;
    }

    private static void persistXrayProxyToMainConfig(boolean enabled) {
        android.content.SharedPreferences.Editor proxyPrefs =
                applicationContext.getSharedPreferences("mainconfig", android.content.Context.MODE_PRIVATE).edit();
        if (enabled) {
            proxyPrefs.putString("proxy_ip", VpnSDK.getProxySocksHost());
            proxyPrefs.putInt("proxy_port", VpnSDK.getProxySocksPort());
            proxyPrefs.putString("proxy_user", "");
            proxyPrefs.putString("proxy_pass", "");
            proxyPrefs.putString("proxy_secret", "");
            proxyPrefs.putBoolean("proxy_enabled", true);
            proxyPrefs.putBoolean("proxy_enabled_calls", true);
        } else {
            proxyPrefs.putBoolean("proxy_enabled", false);
            proxyPrefs.putBoolean("proxy_enabled_calls", false);
        }
        proxyPrefs.commit(); // synchronous — must land before ConnectionsManager.init() reads
    }

    /**
     * Triggers a background /auth/register call when at least one Telegram
     * account is already authenticated. On success the local xray proxy is
     * restarted with the fresh config and ConnectionsManager is told to apply
     * the new proxy address to all accounts at runtime. On failure (after the
     * SDK's internal retries) the existing cached / fallback config keeps
     * serving traffic; we'll try again on the next foreground transition.
     *
     * Debounced to {@link #XRAY_REFRESH_MIN_INTERVAL_MS} to avoid hammering
     * the backend (and tearing down live MTProto sockets) when the user
     * rapidly toggles the app between foreground and background.
     */
    private static void refreshXrayConfigIfActivated() {
        // The on-disk fallback covers the call from onCreate(), where
        // UserConfig configs aren't loaded yet (see hasAuthorizedAccountOnDisk).
        if (UserConfig.getActivatedAccountsCount() <= 0 && !hasAuthorizedAccountOnDisk()) {
            return;
        }
        if (loginInProgress) {
            // Add-account login is routing auth traffic through the AWG tunnel;
            // re-enabling xray now would put the code request on a VLESS exit IP.
            FileLog.d("xray refresh skipped: login in progress");
            return;
        }
        if (!VpnConnectionMode.isEnabled()) {
            FileLog.d("xray refresh skipped: master switch off");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long since = now - lastXrayRefreshAtMs;
        if (since < XRAY_REFRESH_MIN_INTERVAL_MS) {
            FileLog.d("xray refresh skipped: " + since + "ms since last refresh");
            return;
        }
        lastXrayRefreshAtMs = now;
        VpnSDK.registerOrAuth(3, success -> {
            if (success) {
                if (loginInProgress) {
                    // Login started while the register was in flight.
                    lastXrayRefreshAtMs = 0L;
                    FileLog.d("xray refresh result ignored: login in progress");
                    return;
                }
                if (!VpnConnectionMode.isEnabled()) {
                    FileLog.d("xray refresh: config cached, apply skipped (master switch off)");
                    return;
                }
                if (runAwgMode()) {
                    // AWG mode: the fresh config stays cached for a future
                    // switch to vpRay, but the proxy must not be re-enabled.
                    FileLog.d("xray refresh: config cached, apply skipped (mode AWG)");
                    return;
                }
                applyXrayProxyToConnectionsManager();
                // Finish the deferred post-auth switch if it left the AWG
                // tunnel up; never touch a tunnel the user brought up manually.
                if (pendingPostAuthTunnelDown) {
                    pendingPostAuthTunnelDown = false;
                    disconnectTunnelIfUp();
                }
                FileLog.d("xray refreshed via /auth/register, ConnectionsManager updated");
            } else {
                // Allow the next foreground transition to retry sooner.
                lastXrayRefreshAtMs = 0L;
                FileLog.e("xray /auth/register failed after retries; keeping current config");
            }
        });
    }

    public static void startPushService() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", true);
        } else {
            enabled = MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
        }
        if (enabled) {
            try {
                applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
            } catch (Throwable ignore) {

            }
        } else {
            applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
            }
        }, 1000);
    }

    private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (networkCallback == null) {
                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                lastKnownNetworkType = -1;
                            }
                        };
                        connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    }
                }
            } catch (Throwable ignore) {

            }
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return true;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        boolean result = isNetworkOnlineRealtime();
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            boolean result2 = isNetworkOnlineFast();
            if (result != result2) {
                FileLog.d("network online mismatch");
            }
        }
        return result;
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    public static void appCenterLog(Throwable e) {
        applicationLoaderInstance.appCenterLogInternal(e);
    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        return false;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        return false;
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        return false;
    }

    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return null;
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public void addItemOptions(ItemOptions itemOptions) {

    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

    public boolean isCustomUpdate() {
        return false;
    }
    public void downloadUpdate() {}
    public void cancelDownloadingUpdate() {}
    public boolean isDownloadingUpdate() {
        return false;
    }
    public float getDownloadingUpdateProgress() {
        return 0.0f;
    }
    public void checkUpdate(boolean force, Runnable whenDone) {}
    public BetaUpdate getUpdate() {
        return null;
    }
    public File getDownloadedUpdateFile() {
        return null;
    }
}
