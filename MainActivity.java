package com.safeyoutube.brave;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
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
    private Button btnOverlayPermission;
    private Button btnAccessibilityPermission;
    private Switch switchAuto360;
    private AudioManager audioManager;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences(VolumeLimiterService.PREFS_NAME, MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission);
        btnAccessibilityPermission = findViewById(R.id.btnAccessibilityPermission);
        switchAuto360 = findViewById(R.id.switchAuto360);
        Button btnOpenYouTube = findViewById(R.id.btnOpenYouTube);
        Button btnOpenDownloader = findViewById(R.id.btnOpenDownloader);
        Button btnStopAll = findViewById(R.id.btnStopAll);

        switchAuto360.setChecked(prefs.getBoolean(VolumeLimiterService.KEY_AUTO_360, true));
        switchAuto360.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(VolumeLimiterService.KEY_AUTO_360, isChecked).apply()
        );

        btnOverlayPermission.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        });

        btnAccessibilityPermission.setOnClickListener(v -> {
            Toast.makeText(this, "فعّل خيار (YouTube 360p) من قائمة التطبيقات المثبّتة", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnOpenYouTube.setOnClickListener(v -> launchYouTubeInBrave());

        btnOpenDownloader.setOnClickListener(v ->
                startActivity(new Intent(this, DownloadActivity.class))
        );

        btnStopAll.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, VolumeLimiterService.class);
            stopIntent.setAction(VolumeLimiterService.ACTION_STOP);
            startService(stopIntent);
            Toast.makeText(this, "تم إيقاف قفل الصوت والغطاء العلوي", Toast.LENGTH_SHORT).show();
            finish();
        });

        requestNotificationPermissionIfNeeded();
        updateUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUiState();
    }

    private void updateUiState() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean accOk = isAccessibilityEnabled();
        String bravePkg = findInstalledBravePackage();

        btnOverlayPermission.setText(overlayOk
                ? "✓ 1. إخفاء الشريط العلوي مفعّل"
                : "1. تفعيل إخفاء الشريط العلوي (Overlay)");

        btnAccessibilityPermission.setText(accOk
                ? "✓ 2. خدمة جودة 360p مفعّلة"
                : "2. تفعيل خدمة جودة 360p (إمكانية الوصول)");

        if (audioManager != null) {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int capVol = VolumeLimiterService.getMaxAllowedVolume(audioManager);
            if (bravePkg != null) {
                tvStatus.setText("قفل الصوت عند " + VolumeLimiterService.MAX_VOLUME_PERCENT + "% (" + capVol + " من " + maxVol + ")" +
                        (overlayOk ? " • الشريط العلوي مخفي" : " • فعّل الغطاء لإخفاء الشريط"));
            } else {
                tvStatus.setText("متصفح Brave غير مثبّت على الجهاز.");
            }
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return enabled != null && enabled.contains(getPackageName());
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

    private void startVolumeAndOverlayService() {
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
        startVolumeAndOverlayService();

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
