package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import org.telegram.ui.LaunchActivity;
import java.util.Locale;
import vpn.sdk.VpnSDK;
import vpn.tunnel.VpnStatistics;
import vpn.tunnel.VpnTunnelState;

public class VpnConnectionService extends Service {

    private static final String TAG = "VpnConnectionService";
    private static final int NOTIFICATION_ID = 9001;
    private static final long TIMEOUT_MS = 5_000L;
    private static final String ACTION_STOP_VPN = "ACTION_STOP_VPN";
    private static final String NOTIFICATION_CHANNEL_ID = "vpn_connection_status";

    public static volatile boolean disconnectedBySwipe = false;

    private boolean isStarted = false;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private Handler handler;
    private VpnSDK.StateListener stateListener;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, VpnConnectionService.class);
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VpnConnectionService", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, VpnConnectionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());

        // Защита от рестарта сервиса системой, когда VpnSDK ещё не инициализирован
        try {
            VpnSDK.getTunnelState();
        } catch (IllegalStateException e) {
            Log.w(TAG, "VpnSDK not initialized, stopping service");
            stopSelf();
            return;
        }

        stateListener = state -> {
            if (isStarted && state == VpnTunnelState.DOWN) {
                stopSelf();
            }
        };
        VpnSDK.addStateListener(stateListener);

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.VpnConnectionNotificationChannelName),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            notificationManager.createNotificationChannel(channel);
        }

        PendingIntent openAppPendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, LaunchActivity.class),
                PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, VpnConnectionService.class);
        stopIntent.setAction(ACTION_STOP_VPN);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentTitle(getString(R.string.VpnConnectionNotificationTitle))
                .setContentText(getString(R.string.VpnConnectionNotificationContent))
                .setSmallIcon(R.drawable.ic_notification_vepegram)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSilent(true)
                .setContentIntent(openAppPendingIntent)
                .addAction(0, getString(R.string.VpnConnectionNotificationDisconnectAction), stopPendingIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {
            VpnSDK.toggleConnection();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForegroundNotification();
            isStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }

        scheduleStatsUpdate();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App swiped away, disconnecting VPN");
        disconnectedBySwipe = true;
        VpnSDK.toggleConnection();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isStarted = false;
        if (stateListener != null) {
            VpnSDK.removeStateListener(stateListener);
            stateListener = null;
        }
        handler.removeCallbacksAndMessages(null);
    }

    private void startForegroundNotification() {
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notificationBuilder.build(), type);
    }

    private void scheduleStatsUpdate() {
        handler.postDelayed(() -> {
            if (!isStarted) return;
            try {
                VpnStatistics stats = VpnSDK.getStatistics();
                float receivedMb = stats.getTotalRxBytes() / (1024f * 1024f);
                float transmittedMb = stats.getTotalTxBytes() / (1024f * 1024f);
                String receivedText = String.format(Locale.getDefault(), "%.2f МБ", receivedMb);
                String transmittedText = String.format(Locale.getDefault(), "%.2f МБ", transmittedMb);
                notificationManager.notify(
                        NOTIFICATION_ID,
                        notificationBuilder
                                .setContentText(getString(
                                        R.string.VpnConnectionNotificationContentWithTraffic,
                                        receivedText, transmittedText))
                                .build()
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to update stats notification", e);
            }
            scheduleStatsUpdate();
        }, TIMEOUT_MS);
    }
}
