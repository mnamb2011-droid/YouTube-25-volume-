package com.safeyoutube.brave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class VolumeLimiterService extends Service {

    public static final String ACTION_START = "com.safeyoutube.brave.ACTION_START";
    public static final String ACTION_STOP = "com.safeyoutube.brave.ACTION_STOP";
    public static final int MAX_VOLUME_PERCENT = 25;

    private static final String CHANNEL_ID = "volume_limiter_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long POLL_INTERVAL_MS = 400L;

    private AudioManager audioManager;
    private Handler handler;
    private ContentObserver volumeObserver;
    private BroadcastReceiver volumeReceiver;
    private boolean isRunning = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            enforceVolumeCap();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public static int getMaxAllowedVolume(AudioManager am) {
        int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return Math.max(1, (maxVol * MAX_VOLUME_PERCENT) / 100);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopLimiter();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            startForegroundCompat();
            registerVolumeWatchers();
            enforceVolumeCap();
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        } else {
            enforceVolumeCap();
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Intent stopIntent = new Intent(this, VolumeLimiterService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int capVol = getMaxAllowedVolume(audioManager);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("حماية صوت YouTube مفعّلة")
                .setContentText("الحد الأقصى للصوت: 25% (" + capVol + " من " + maxVol + ")")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openPendingIntent)
                .addAction(0, "إيقاف قفل الصوت", stopPendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void registerVolumeWatchers() {
        volumeObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                enforceVolumeCap();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
        );

        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                enforceVolumeCap();
            }
        };
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        ContextCompat.registerReceiver(this, volumeReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void enforceVolumeCap() {
        if (audioManager == null) return;
        int capVol = getMaxAllowedVolume(audioManager);
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (currentVol > capVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, capVol, 0);
        }
    }

    private void stopLimiter() {
        isRunning = false;
        cleanupWatchers();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanupWatchers() {
        if (handler != null) {
            handler.removeCallbacks(pollRunnable);
        }
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
            }
            volumeObserver = null;
        }
        if (volumeReceiver != null) {
            try {
                unregisterReceiver(volumeReceiver);
            } catch (Exception ignored) {
            }
            volumeReceiver = null;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        cleanupWatchers();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "حماية مستوى الصوت",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("تقييد مستوى صوت الوسائط عند 25% كحد أقصى أثناء تشغيل يوتيوب");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
