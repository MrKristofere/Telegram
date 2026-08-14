package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-selectable connection mode ("Настройки соединения"):
 *
 * <ul>
 * <li>{@link #AWG} — AmneziaWG tunnel up, xray SOCKS5 proxy off; all device
 *     traffic (not just Telegram) goes through the VPN.</li>
 * <li>{@link #VPRAY} — xray (VLESS) proxies MTProto and calls, tunnel down;
 *     only Telegram traffic is routed.</li>
 * <li>{@link #AUTO} — follow the remote config ({@code active_vpn_protocol}
 *     key in Firebase, see {@link VpGramRemoteConfig#getActiveVpnProtocol()});
 *     falls back to vpRay when the remote value is missing or unknown.</li>
 * </ul>
 *
 * The selection is stored in mainconfig; the login flow ignores it — auth
 * always goes through AWG (see LoginActivity), the mode is applied by
 * {@link ApplicationLoader#switchToPostAuthConnectionMode()} afterwards.
 */
public enum VpnConnectionMode {
    AWG("awg"),
    VPRAY("vpray"),
    AUTO("auto");

    private static final String PREF_KEY = "vpn_connection_mode";
    private static final String PREF_ENABLED_KEY = "vpn_connection_enabled";
    private static final String PREF_TOGGLE_ON_CLOSE_KEY = "vpn_toggle_on_close";
    private static final String PREF_TOGGLE_ON_MINIMIZE_KEY = "vpn_toggle_on_minimize";

    public final String key;

    VpnConnectionMode(String key) {
        this.key = key;
    }

    private static VpnConnectionMode fromKey(String key) {
        for (VpnConnectionMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }

    /** The mode picked by the user in connection settings. Default — Авто. */
    public static VpnConnectionMode getSelected() {
        VpnConnectionMode mode = fromKey(prefs().getString(PREF_KEY, AUTO.key));
        return mode != null ? mode : AUTO;
    }

    public static void setSelected(VpnConnectionMode mode) {
        prefs().edit().putString(PREF_KEY, mode.key).apply();
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED_KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(PREF_ENABLED_KEY, enabled).apply();
    }

    public static boolean isToggleOnClose() {
        return prefs().getBoolean(PREF_TOGGLE_ON_CLOSE_KEY, true);
    }

    public static void setToggleOnClose(boolean enabled) {
        prefs().edit().putBoolean(PREF_TOGGLE_ON_CLOSE_KEY, enabled).apply();
    }

    public static boolean isToggleOnMinimize() {
        return prefs().getBoolean(PREF_TOGGLE_ON_MINIMIZE_KEY, false);
    }

    public static void setToggleOnMinimize(boolean enabled) {
        prefs().edit().putBoolean(PREF_TOGGLE_ON_MINIMIZE_KEY, enabled).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
    }

    /**
     * The mode to actually run: resolves {@link #AUTO} through the remote
     * config, defaulting to vpRay. Never returns AUTO.
     */
    public static VpnConnectionMode getEffective() {
        VpnConnectionMode selected = getSelected();
        if (selected != AUTO) {
            return selected;
        }
        VpnConnectionMode remote = fromKey(VpGramRemoteConfig.getActiveVpnProtocol());
        return remote == AWG ? AWG : VPRAY;
    }
}
