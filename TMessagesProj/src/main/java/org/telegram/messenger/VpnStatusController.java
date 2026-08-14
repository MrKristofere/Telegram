package org.telegram.messenger;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.UiThread;

import java.util.ArrayList;
import java.util.List;

import vpn.sdk.VpnSDK;
import vpn.tunnel.VpnTunnelState;

public class VpnStatusController {

    public enum Status {
        OFF,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public interface Listener {
        @UiThread
        void onVpnStatusChanged(Status status);
    }

    private static final List<Listener> listeners = new ArrayList<>();
    private static volatile Status status = Status.OFF;
    private static volatile boolean deliberateDisconnect;
    private static boolean subscribed;

    public static void init() {
        if (subscribed) {
            return;
        }
        subscribed = true;
        VpnSDK.addStateListener(state -> {
            // Реагируем на состояние туннеля, когда выбран режим AWG — даже если
            // разрешение временно потеряно (сторонний VPN занял слот).
            if (VpnConnectionMode.getEffective() != VpnConnectionMode.AWG) {
                return;
            }
            switch (state) {
                case UP:
                    deliberateDisconnect = false;
                    setStatus(Status.CONNECTED);
                    break;
                case CONNECTING:
                    deliberateDisconnect = false;
                    setStatus(Status.CONNECTING);
                    break;
                default:
                    boolean deliberate = deliberateDisconnect;
                    deliberateDisconnect = false;
                    if (deliberate) {
                        setStatus(Status.OFF);
                    } else if (VpnSDK.isProxyRunning()) {
                        setStatus(Status.CONNECTED);
                    } else if (isOtherVpnActive()) {
                        setStatus(Status.OFF);
                    } else {
                        setStatus(Status.ERROR);
                    }
                    break;
            }
        });
        refresh();
    }

    public static boolean isVpnActive() {
        return status == Status.CONNECTED || status == Status.CONNECTING;
    }

    static boolean isOtherVpnActive() {
        if (VpnSDK.getTunnelState() != VpnTunnelState.DOWN) {
            return false;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static void markDeliberateDisconnect() {
        deliberateDisconnect = true;
    }

    public static Status getStatus() {
        return status;
    }

    public static synchronized void setStatus(Status newStatus) {
        if (status == newStatus) {
            return;
        }
        status = newStatus;
        AndroidUtilities.runOnUIThread(() -> {
            List<Listener> snapshot = new ArrayList<>(listeners);
            for (int i = 0; i < snapshot.size(); i++) {
                snapshot.get(i).onVpnStatusChanged(newStatus);
            }
        });
    }

    public static void refresh() {
        if (VpnConnectionMode.getEffective() == VpnConnectionMode.AWG) {
            // Статус AWG отражает ФАКТ (состояние туннеля / xray-носителя), а не
            // мастер-тумблер: автоподъём AWG от мастера не зависит, значит и статус
            // не должен — иначе refresh() и слушатель UP дают разные ответы.
            VpnTunnelState ts = VpnSDK.getTunnelState();
            if (ts == VpnTunnelState.UP || VpnSDK.isProxyRunning()) {
                setStatus(Status.CONNECTED);
            } else if (ts == VpnTunnelState.CONNECTING) {
                setStatus(Status.CONNECTING);
            } else {
                // Туннель DOWN и ничего не поднимается (по дизайну не поднято, или
                // вытеснение сторонним VPN) — OFF (серый), а не вечный спиннер.
                setStatus(Status.OFF);
            }
        } else if (!VpnConnectionMode.isEnabled()) {
            // vpRay: мастер-тумблер = вкл/выкл.
            setStatus(Status.OFF);
        } else {
            setStatus(VpnSDK.isProxyRunning() ? Status.CONNECTED : Status.CONNECTING);
        }
    }

    @UiThread
    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @UiThread
    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
