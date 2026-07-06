package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Tracks whether the user is subscribed to the vpGram public Telegram channel and
 * decides whether the "Join our channel" banner should be shown at the top of the
 * chat list. Android counterpart of the iOS TrackedChannelBannerManager.
 *
 * State is persisted per-account in MessagesController main settings:
 *  - status: unknown / unsubscribed / subscribed
 *  - lastCheck: timestamp (seconds) of the last membership evaluation
 *  - forceRecheck: bypass the throttle on the next evaluation
 *  - dismissTs: timestamp (seconds) when the banner was last dismissed (2-day cooldown)
 */
public class TrackedChannelBannerController {

    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_UNSUBSCRIBED = 1;
    public static final int STATUS_SUBSCRIBED = 2;

    private static final long MEMBERSHIP_CHECK_INTERVAL = 12 * 60 * 60; // seconds
    private static final long DISMISS_COOLDOWN = 2 * 24 * 60 * 60;       // seconds

    private static final String KEY_STATUS = "vpgram_channel_status";
    private static final String KEY_LAST_CHECK = "vpgram_channel_last_check";
    private static final String KEY_FORCE_RECHECK = "vpgram_channel_force_recheck";
    private static final String KEY_DISMISS_TS = "vpgram_channel_dismiss_ts";

    private static final TrackedChannelBannerController[] Instance = new TrackedChannelBannerController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static TrackedChannelBannerController getInstance(int num) {
        TrackedChannelBannerController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new TrackedChannelBannerController(num);
                }
            }
        }
        return localInstance;
    }

    /**
     * Called when the app returns to the foreground: force a re-check on the next chat list
     * update to catch the user having joined or left the channel while backgrounded.
     */
    public static void onAppForeground() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                getInstance(account).markForceRecheck();
            }
        }
    }

    private final int currentAccount;
    private volatile boolean evaluating;

    private TrackedChannelBannerController(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    private SharedPreferences prefs() {
        return MessagesController.getInstance(currentAccount).getMainSettings();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * Whether the banner should currently be visible: a channel is configured, membership
     * has been reliably evaluated as "not subscribed" and the dismiss cooldown has elapsed.
     */
    public boolean shouldShowBanner() {
        if (TextUtils.isEmpty(VpGramRemoteConfig.getTgChannelUrl())) {
            return false;
        }
        final SharedPreferences prefs = prefs();
        // Only show once we've confirmed the user is not subscribed. STATUS_UNKNOWN (not yet
        // checked) keeps the banner hidden to avoid flashing it at subscribers on cold start.
        if (prefs.getInt(KEY_STATUS, STATUS_UNKNOWN) != STATUS_UNSUBSCRIBED) {
            return false;
        }
        final long dismissTs = prefs.getLong(KEY_DISMISS_TS, 0);
        if (dismissTs != 0 && dismissTs + DISMISS_COOLDOWN > now()) {
            return false;
        }
        return true;
    }

    /**
     * Kicks off an asynchronous membership evaluation, honoring the 12h throttle (unless a
     * force-recheck was requested or membership is still unknown). When the status changes
     * it posts {@link NotificationCenter#trackedChannelBannerChanged} so the UI can refresh.
     */
    public void refresh() {
        refresh(false);
    }

    /**
     * Forces a membership re-check regardless of the throttle (still guarded against a
     * concurrent in-flight check). Call this when returning to the chat list, so a user who
     * just joined the channel elsewhere has the banner hidden immediately on return.
     */
    public void forceRefresh() {
        refresh(true);
    }

    private void refresh(boolean force) {
        final long channelId = VpGramRemoteConfig.getTgChannelId();
        final String channelUsername = VpGramRemoteConfig.getTgChannelUsername();
        if (channelId == 0 && channelUsername == null) {
            return;
        }
        if (evaluating) {
            return;
        }

        final SharedPreferences prefs = prefs();
        final int status = prefs.getInt(KEY_STATUS, STATUS_UNKNOWN);
        final boolean forceRecheck = force || prefs.getBoolean(KEY_FORCE_RECHECK, false);
        final long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);

        // Throttle repeated network checks once we already have a determined status; a still
        // unknown status is always (re)evaluated so the banner state resolves as soon as possible.
        if (!forceRecheck && status != STATUS_UNKNOWN
                && lastCheck != 0 && lastCheck + MEMBERSHIP_CHECK_INTERVAL > now()) {
            return;
        }

        evaluating = true;
        if (prefs.getBoolean(KEY_FORCE_RECHECK, false)) {
            prefs.edit().putBoolean(KEY_FORCE_RECHECK, false).apply();
        }
        resolveThenQuery(channelId, channelUsername);
    }

    /** Banner tapped: open the channel and force a re-check (the user may now subscribe). */
    public void onBannerTapped() {
        prefs().edit().putBoolean(KEY_FORCE_RECHECK, true).apply();
    }

    /** Banner closed: start the 2-day cooldown. */
    public void onBannerDismissed() {
        prefs().edit().putLong(KEY_DISMISS_TS, now()).apply();
    }

    private void markForceRecheck() {
        prefs().edit().putBoolean(KEY_FORCE_RECHECK, true).apply();
    }

    /**
     * Ensures we have the channel chat (with an access_hash) and then queries membership.
     * If the chat isn't cached (typical for a user who never opened the channel) it is first
     * resolved by its public username.
     */
    private void resolveThenQuery(long channelId, String channelUsername) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final TLRPC.Chat cached = channelId != 0 ? controller.getChat(channelId) : null;
        if (cached != null && cached.access_hash != 0) {
            queryParticipant(cached);
            return;
        }
        if (channelUsername == null) {
            evaluating = false;
            return;
        }

        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = channelUsername;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    TLRPC.Chat resolved = null;
                    if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                        final TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                        controller.putUsers(res.users, false);
                        controller.putChats(res.chats, false);
                        // We resolved BY username, so trust the peer the username points to
                        // rather than the tg_channel_id from config (which may not match).
                        if (res.peer != null && res.peer.channel_id != 0) {
                            resolved = controller.getChat(res.peer.channel_id);
                        }
                        if (resolved == null) {
                            for (int i = 0, n = res.chats.size(); i < n; i++) {
                                final TLRPC.Chat c = res.chats.get(i);
                                final String uname = ChatObject.getPublicUsername(c);
                                if (ChatObject.isChannel(c) && uname != null && uname.equalsIgnoreCase(channelUsername)) {
                                    resolved = c;
                                    break;
                                }
                            }
                        }
                    }
                    if (resolved != null && resolved.access_hash != 0) {
                        queryParticipant(resolved);
                    } else {
                        // Couldn't resolve — leave status unchanged, retry on the next trigger.
                        evaluating = false;
                    }
                }));
    }

    private void queryParticipant(TLRPC.Chat chat) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final TLRPC.TL_channels_getParticipant req = new TLRPC.TL_channels_getParticipant();
        req.channel = controller.getInputChannel(chat.id);
        req.participant = controller.getInputPeer(selfId);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.TL_channels_channelParticipant) {
                        commit(STATUS_SUBSCRIBED);
                    } else if (error != null && error.text != null && error.text.contains("USER_NOT_PARTICIPANT")) {
                        commit(STATUS_UNSUBSCRIBED);
                    }
                    // Any other error (network/timeout/etc.): leave the status untouched so a
                    // transient failure never wrongly shows the banner to a subscriber.
                    evaluating = false;
                }));
    }

    private void commit(int nextStatus) {
        final SharedPreferences prefs = prefs();
        final int previousStatus = prefs.getInt(KEY_STATUS, STATUS_UNKNOWN);
        // If the user just left the channel, drop the dismiss cooldown so the banner can
        // reappear immediately. Mirrors iOS shouldClearDismissTimestamp.
        final boolean clearDismiss = previousStatus == STATUS_SUBSCRIBED && nextStatus == STATUS_UNSUBSCRIBED;

        final SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_STATUS, nextStatus);
        editor.putLong(KEY_LAST_CHECK, now());
        if (clearDismiss) {
            editor.remove(KEY_DISMISS_TS);
        }
        editor.apply();

        if (nextStatus != previousStatus || clearDismiss) {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.trackedChannelBannerChanged);
        }
    }
}
