package org.telegram.messenger;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import vpn.sdk.VpnSDK;
import vpn.tunnel.VpnTunnelState;

public class VpnConnectionHelper {

    private static final String TAG = "VpnConnectionHelper";
    public static final int REQUEST_CODE_VPN_PERMISSION = 100;
    public static final int REQUEST_CODE_VPN_NOTIFICATION = 101;
    public static volatile boolean vpnConsentInFlight;

    private final BaseFragment fragment;
    private boolean pendingVpnPermission;
    private boolean waitingForActivityResult;
    private Runnable pendingAction;
    private Runnable pendingFailure;
    private VpnSDK.StateListener tunnelUpListener;

    public interface VpnProgressCallback {
        void onVpnProgressChanged(boolean showProgress);
    }

    private VpnProgressCallback progressCallback;

    public VpnConnectionHelper(BaseFragment fragment) {
        this.fragment = fragment;
    }

    public void setProgressCallback(VpnProgressCallback callback) {
        this.progressCallback = callback;
    }

    public void startVpnAndThen(Runnable onConnected) {
        startVpnAndThen(onConnected, null);
    }

    /**
     * Как {@link #startVpnAndThen(Runnable)}, но с колбэком неудачи:
     * {@code onFailed} вызывается, если туннель так и не поднялся — отказ в
     * VPN-разрешении или уведомлениях, ограниченное устройство, обрыв во
     * время подключения или таймаут. При уничтожении фрагмента
     * ({@link #release()}) не вызывается.
     */
    public void startVpnAndThen(Runnable onConnected, Runnable onFailed) {
        VpnTunnelState currentState = VpnSDK.getTunnelState();

        if (currentState == VpnTunnelState.UP) {
            if (onConnected != null) {
                onConnected.run();
            }
            return;
        }

        this.pendingAction = onConnected;
        this.pendingFailure = onFailed;

        if (currentState == VpnTunnelState.CONNECTING) {
            showProgress(true);
            listenForTunnelUp();
            return;
        }

        requestVpnPermission();
    }

    public void startVpnToggle() {
        VpnTunnelState currentState = VpnSDK.getTunnelState();
        if (currentState == VpnTunnelState.CONNECTING) {
            return;
        }
        if (currentState == VpnTunnelState.UP) {
            VpnStatusController.markDeliberateDisconnect();
            VpnSDK.toggleConnection();
            return;
        }
        this.pendingAction = null;
        this.pendingFailure = null;
        requestVpnPermission();
    }

    private void requestVpnPermission() {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;

        try {
            Intent vpnIntent = VpnService.prepare(activity);
            if (vpnIntent == null) {
                checkNotificationPermission(activity);
            } else {
                pendingVpnPermission = true;
                waitingForActivityResult = true;
                vpnConsentInFlight = true;
                activity.startActivityForResult(vpnIntent, REQUEST_CODE_VPN_PERMISSION);
            }
        } catch (Exception e) {
            // Устройства Сбера и другие, где VPN ограничен
            Toast.makeText(activity,
                    getString(R.string.VpnConnectionToastDeviceRestricted),
                    Toast.LENGTH_SHORT).show();
            clearPendingAction();
        }
    }

    private void checkNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                startVpnConnection();
            } else {
                activity.requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_VPN_NOTIFICATION
                );
            }
        } else {
            startVpnConnection();
        }
    }

    private void startVpnConnection() {
        Activity activity = fragment.getParentActivity();
        if (activity != null) {
            VpnConnectionService.start(activity);
        }
        VpnTunnelState state = VpnSDK.getTunnelState();
        if (state == VpnTunnelState.UP) {
            // Туннель уже поднят (например, фоновым автоподъёмом) — не гасим его
            // повторным toggleConnection(), а сразу выполняем действие.
            Runnable action = pendingAction;
            pendingAction = null;
            pendingFailure = null;
            showProgress(false);
            if (action != null) {
                action.run();
            }
            return;
        }
        if (state == VpnTunnelState.DOWN) {
            VpnSDK.toggleConnection();
        }
        if (pendingAction != null) {
            showProgress(true);
            listenForTunnelUp();
        }
    }

    private static final long TUNNEL_UP_TIMEOUT_MS = 30_000;
    private Runnable tunnelUpTimeoutRunnable;

    private void listenForTunnelUp() {
        removeTunnelUpListener();

        tunnelUpListener = state -> {
            if (state == VpnTunnelState.UP) {
                Log.d(TAG, "Tunnel is UP, executing pending action");
                cancelTunnelUpTimeout();
                showProgress(false);
                removeTunnelUpListener();
                Runnable action = pendingAction;
                pendingAction = null;
                pendingFailure = null;
                if (action != null) {
                    action.run();
                }
            } else if (state == VpnTunnelState.DOWN) {
                Log.d(TAG, "Tunnel went DOWN while waiting");
                clearPendingAction();
            }
        };
        VpnSDK.addStateListener(tunnelUpListener);

        // Таймаут на случай если VPN застрянет в CONNECTING
        tunnelUpTimeoutRunnable = () -> {
            Log.w(TAG, "Tunnel UP timeout reached");
            tunnelUpTimeoutRunnable = null;
            clearPendingAction();
        };
        AndroidUtilities.runOnUIThread(tunnelUpTimeoutRunnable, TUNNEL_UP_TIMEOUT_MS);
    }

    private void cancelTunnelUpTimeout() {
        if (tunnelUpTimeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(tunnelUpTimeoutRunnable);
            tunnelUpTimeoutRunnable = null;
        }
    }

    private void removeTunnelUpListener() {
        if (tunnelUpListener != null) {
            VpnSDK.removeStateListener(tunnelUpListener);
            tunnelUpListener = null;
        }
    }

    public boolean handleActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CODE_VPN_PERMISSION) {
            return false;
        }
        if (!pendingVpnPermission) {
            return true;
        }

        // Результат получен через onActivityResult — handleResume уже не нужен
        pendingVpnPermission = false;
        waitingForActivityResult = false;
        vpnConsentInFlight = false;

        Activity activity = fragment.getParentActivity();
        if (activity == null) return true;

        Intent vpnCheck = VpnService.prepare(activity);
        if (vpnCheck == null) {
            // Пользователь дал разрешение → проверяем уведомления
            Log.d(TAG, "VPN permission granted via onActivityResult");
            checkNotificationPermission(activity);
        } else {
            // Пользователь отказал
            Log.d(TAG, "VPN permission denied via onActivityResult");
            Toast.makeText(activity,
                    getString(R.string.VpnConnectionToastEnableVpn),
                    Toast.LENGTH_SHORT).show();
            clearPendingAction();
        }
        return true;
    }

    public void handleResume() {
        if (!pendingVpnPermission) return;

        if (waitingForActivityResult) {
            waitingForActivityResult = false;
            return;
        }

        pendingVpnPermission = false;
        vpnConsentInFlight = false;

        Activity activity = fragment.getParentActivity();
        if (activity == null) return;

        Intent vpnCheck = VpnService.prepare(activity);
        if (vpnCheck == null) {
            // Пользователь дал разрешение → проверяем уведомления
            Log.d(TAG, "VPN permission granted via handleResume (fallback)");
            checkNotificationPermission(activity);
        } else {
            // Пользователь отказал
            Toast.makeText(activity,
                    getString(R.string.VpnConnectionToastEnableVpn),
                    Toast.LENGTH_SHORT).show();
            clearPendingAction();
        }
    }

    public boolean handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CODE_VPN_NOTIFICATION) {
            return false;
        }

        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                Log.d(TAG, "Notification permission result: " + grantResults[i]);
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    startVpnConnection();
                } else {
                    Activity activity = fragment.getParentActivity();
                    if (activity != null) {
                        fragment.showDialog(new AlertDialog.Builder(activity)
                                .setTitle(getString(R.string.VpnConnectionNotificationDialogTitle))
                                .setMessage(getString(R.string.VpnConnectionNotificationDialogDescription))
                                .setPositiveButton(getString(R.string.VpnConnectionNotificationDialogGoToSettings), (dialog, which) -> {
                                    try {
                                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                        intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                        activity.startActivity(intent);
                                    } catch (Exception e) {
                                        FileLog.e(e);
                                    }
                                })
                                .setNegativeButton(getString(R.string.VpnConnectionNotificationDialogClose), null)
                                .create());
                    }
                    clearPendingAction();
                }
                return true;
            }
        }
        return false;
    }

    public void release() {
        cancelTunnelUpTimeout();
        removeTunnelUpListener();
        pendingAction = null;
        pendingFailure = null;
        pendingVpnPermission = false;
        waitingForActivityResult = false;
        vpnConsentInFlight = false;
        progressCallback = null;
    }

    private void showProgress(boolean show) {
        if (progressCallback != null) {
            progressCallback.onVpnProgressChanged(show);
        }
    }

    private void clearPendingAction() {
        // Любой неуспешный путь (отказ, таймаут, ограниченное устройство из catch
        // requestVpnPermission, обрыв) — снимаем общий флаг, иначе автоподъём AWG
        // залипнет навсегда.
        vpnConsentInFlight = false;
        showProgress(false);
        cancelTunnelUpTimeout();
        removeTunnelUpListener();
        pendingAction = null;
        Runnable failure = pendingFailure;
        pendingFailure = null;
        if (failure != null) {
            failure.run();
        }
    }
}
