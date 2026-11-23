package com.safeyoutube.brave;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String YOUTUBE_URL = "https://m.youtube.com";
    private static final String[] BRAVE_PACKAGES = {
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly"
    };
    private static final int REQ_NOTIF_PERMISSION = 101;

    private TextView tvStatus;
    private AudioManager audioManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnOpenYouTube = findViewById(R.id.btnOpenYouTube);
        Button btnStopLimiter = findViewById(R.id.btnStopLimiter);

        updateStatusText();

        btnOpenYouTube.setOnClickListener(v -> launchYouTubeInBrave());
        btnStopLimiter.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, VolumeLimiterService.class);
            stopIntent.setAction(VolumeLimiterService.ACTION_STOP);
            startService(stopIntent);
            Toast.makeText(this, "تم إيقاف قفل الصوت", Toast.LENGTH_SHORT).show();
            finish();
        });

        requestNotificationPermissionIfNeeded();
        launchYouTubeInBrave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusText();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF_PERMISSION
                );
            }
        }
    }

    private void updateStatusText() {
        if (audioManager == null) return;
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int capVol = VolumeLimiterService.getMaxAllowedVolume(audioManager);
        String bravePkg = findInstalledBravePackage();
        if (bravePkg != null) {
            tvStatus.setText("قفل الصوت مفعّل عند 25% كحد أقصى (" + capVol + " من " + maxVol + ")");
        } else {
            tvStatus.setText("متصفح Brave غير مثبّت على الجهاز.\nاضغط الزر بالأسفل لتثبيته من Google Play.");
        }
    }

    private void startVolumeLimiter() {
        if (audioManager != null) {
            int capVol = VolumeLimiterService.getMaxAllowedVolume(audioManager);
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > capVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, capVol, 0);
            }
        }
        Intent serviceIntent = new Intent(this, VolumeLimiterService.class);
        serviceIntent.setAction(VolumeLimiterService.ACTION_START);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void launchYouTubeInBrave() {
        startVolumeLimiter();

        String bravePackage = findInstalledBravePackage();
        if (bravePackage == null) {
            Toast.makeText(this, "يرجى تثبيت متصفح Brave أولاً", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.brave.browser")));
            } catch (Exception ignored) {
            }
            return;
        }

        Uri ytUri = Uri.parse(YOUTUBE_URL);
        try {
            CustomTabColorSchemeParams darkParams = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(Color.parseColor("#0F0F0F"))
                    .setNavigationBarColor(Color.parseColor("#0F0F0F"))
                    .build();

            CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(darkParams)
                    .setShowTitle(false)
                    .setUrlBarHidingEnabled(true)
                    .build();

            customTabsIntent.intent.setPackage(bravePackage);
            customTabsIntent.launchUrl(this, ytUri);
        } catch (Exception e) {
            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, ytUri);
            fallbackIntent.setPackage(bravePackage);
            startActivity(fallbackIntent);
        }
    }

    private String findInstalledBravePackage() {
        PackageManager pm = getPackageManager();
        for (String pkg : BRAVE_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }
}
