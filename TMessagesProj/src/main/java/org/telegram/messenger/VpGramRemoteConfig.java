package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import java.util.List;

/**
 * Thin wrapper over Firebase Remote Config that exposes the vpGram-specific values.
 * Mirrors the iOS FirebaseRemoteConfig wrapper (keys tg_channel_url / tg_channel_id).
 *
 * All access is guarded: on flavors without google-services (e.g. Huawei) or before
 * Firebase has initialized, getInstance() may throw — callers simply fall back to defaults.
 */
public final class VpGramRemoteConfig {

    private static final String KEY_TG_CHANNEL_URL = "tg_channel_url";
    private static final String KEY_TG_CHANNEL_ID = "tg_channel_id";
    private static final String KEY_ACTIVE_VPN_PROTOCOL = "active_vpn_protocol";

    // The channel id/url change rarely; fetch at most hourly. fetch() is called on every
    // app foregrounding, so a 0 interval would hammer Firebase and risk FETCH_THROTTLED.
    private static final long MINIMUM_FETCH_INTERVAL_SECONDS = 60 * 60;

    private VpGramRemoteConfig() {
    }

    /**
     * Kicks off a background fetch+activate. Safe to call multiple times.
     * Mirrors the iOS FirebaseRemoteConfig wrapper.
     */
    public static void fetch() {
        try {
            final FirebaseRemoteConfig remoteConfig = FirebaseRemoteConfig.getInstance();
            final FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(MINIMUM_FETCH_INTERVAL_SECONDS)
                    .build();
            remoteConfig.setConfigSettingsAsync(settings);
            remoteConfig.fetchAndActivate();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Remote-controlled connection mode ("active_vpn_protocol") for users who
     * selected «Авто» in connection settings: "awg" or "vpray". Empty/unknown
     * values mean "no remote preference" — {@link VpnConnectionMode} falls
     * back to vpRay.
     */
    public static String getActiveVpnProtocol() {
        try {
            return FirebaseRemoteConfig.getInstance().getString(KEY_ACTIVE_VPN_PROTOCOL);
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    public static String getTgChannelUrl() {
        try {
            final String value = FirebaseRemoteConfig.getInstance().getString(KEY_TG_CHANNEL_URL);
            return TextUtils.isEmpty(value) ? null : value;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Channel id as configured remotely, normalized to the raw internal id used by
     * Telegram-Android (a dialog for a channel has id {@code -channel_id}). 0 means "not set".
     *
     * Accepts both forms so the remote value can't be mis-set:
     *  - a positive raw channel id (e.g. {@code 1234567890}) — used as-is;
     *  - a bot-API style id (always negative, e.g. {@code -1001234567890}) — converted.
     */
    public static long getTgChannelId() {
        try {
            return normalizeChannelId(FirebaseRemoteConfig.getInstance().getLong(KEY_TG_CHANNEL_ID));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Bot-API channel id is {@code -1000000000000 - channel_id}. */
    private static final long BOT_API_CHANNEL_OFFSET = 1000000000000L;
    /** iOS/TelegramCore peer-id namespace for cloud channels (stored in the high 32 bits). */
    private static final long IOS_CHANNEL_NAMESPACE = 2L;

    static long normalizeChannelId(long value) {
        if (value < 0) {
            // bot-API style id (e.g. -1001234567890) → raw internal channel id
            return -BOT_API_CHANNEL_OFFSET - value;
        }
        // iOS-style namespaced peer id: high 32 bits carry the peer namespace (2 = channel),
        // low 32 bits are the raw channel id. The shared config uses this iOS format.
        if ((value >>> 32) == IOS_CHANNEL_NAMESPACE) {
            return value & 0xFFFFFFFFL;
        }
        return value; // already a raw internal channel id
    }

    /**
     * Extracts the public username from the configured channel url (e.g.
     * "https://t.me/vpgtg" -> "vpgtg"). Returns null if the url has no resolvable
     * username (private invite links of the form t.me/+hash or t.me/joinchat/...).
     */
    public static String getTgChannelUsername() {
        return usernameFromUrl(getTgChannelUrl());
    }

    static String usernameFromUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            String value = url.trim();
            if (value.startsWith("@")) {
                value = value.substring(1);
            }
            if (!value.contains("/") && !value.contains(":")) {
                // already a bare username
                return value.isEmpty() ? null : value;
            }
            if (!value.contains("://")) {
                value = "https://" + value;
            }
            final Uri uri = Uri.parse(value);
            final List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return null;
            }
            final String first = segments.get(0);
            if (TextUtils.isEmpty(first)) {
                return null;
            }
            // private invite links / non-username paths -> no resolvable username
            if (first.startsWith("+") || "joinchat".equalsIgnoreCase(first)) {
                return null;
            }
            return first;
        } catch (Throwable e) {
            return null;
        }
    }
}
