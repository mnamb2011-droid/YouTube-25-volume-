package com.safeyoutube.brave;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class DownloadActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 201;
    private static final String PROCESS_ID = "yt_download_process";

    private EditText etUrl;
    private TextView tvVideoTitle;
    private TextView tvDownloadStatus;
    private Spinner spinnerFormat;
    private ProgressBar progressBar;
    private Button btnDownload;
    private Button btnCancel;
    private Button btnFetchFormats;
    private Button btnUpdateEngine;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<FormatOption> formatOptions = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;
    private volatile boolean engineReady = false;
    private volatile boolean busy = false;

    private static class FormatOption {
        final String label;
        final String formatSelector;
        final String mergeFormat;
        final boolean extractAudio;
        final String audioFormat;
        final String audioQuality;

        FormatOption(String label, String formatSelector, String mergeFormat,
                     boolean extractAudio, String audioFormat, String audioQuality) {
            this.label = label;
            this.formatSelector = formatSelector;
            this.mergeFormat = mergeFormat;
            this.extractAudio = extractAudio;
            this.audioFormat = audioFormat;
            this.audioQuality = audioQuality;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);

        etUrl = findViewById(R.id.etUrl);
        tvVideoTitle = findViewById(R.id.tvVideoTitle);
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus);
        spinnerFormat = findViewById(R.id.spinnerFormat);
        progressBar = findViewById(R.id.progressBar);
        btnDownload = findViewById(R.id.btnDownload);
        btnCancel = findViewById(R.id.btnCancel);
        btnFetchFormats = findViewById(R.id.btnFetchFormats);
        btnUpdateEngine = findViewById(R.id.btnUpdateEngine);
        Button btnPaste = findViewById(R.id.btnPaste);

        setupDefaultFormats();
        handleIncomingIntent(getIntent());

        btnPaste.setOnClickListener(v -> pasteFromClipboard());
        btnFetchFormats.setOnClickListener(v -> fetchAvailableFormats());
        btnDownload.setOnClickListener(v -> startDownload());
        btnCancel.setOnClickListener(v -> cancelDownload());
        btnUpdateEngine.setOnClickListener(v -> updateYtDlp());

        initEngineAsync();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("text/")) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(sharedText)) {
                etUrl.setText(extractFirstUrl(sharedText));
            }
        }
    }

    private String extractFirstUrl(String text) {
        Matcher m = Pattern.compile("(https?://\\S+)").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return text.trim();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                etUrl.setText(extractFirstUrl(clip.getItemAt(0).getText().toString()));
                return;
            }
        }
        Toast.makeText(this, "الحافظة فارغة", Toast.LENGTH_SHORT).show();
    }

    private void setupDefaultFormats() {
        formatOptions.clear();
        formatOptions.add(new FormatOption("فيديو MP4 — 360p (متوازن وسريع)",
                "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/best[height<=360][ext=mp4]/best[height<=360]",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو MP4 — 144p (أقل حجم)",
                "bestvideo[height<=144]+bestaudio/best[height<=144]",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو MP4 — 240p",
                "bestvideo[height<=240]+bestaudio/best[height<=240]",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو MP4 — 480p",
                "bestvideo[height<=480]+bestaudio/best[height<=480]",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو MP4 — 720p HD",
                "bestvideo[height<=720]+bestaudio/best[height<=720]",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو MP4 — 1080p Full HD",
                "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو MP4 — أعلى جودة متاحة (حتى 4K)",
                "bestvideo+bestaudio/best",
                "mp4", false, null, null));
        formatOptions.add(new FormatOption("فيديو WebM — أعلى جودة",
                "bestvideo[ext=webm]+bestaudio[ext=webm]/bestvideo+bestaudio/best",
                "webm", false, null, null));
        formatOptions.add(new FormatOption("صوت فقط — MP3 (جودة عالية 320k)",
                "bestaudio/best", null, true, "mp3", "0"));
        formatOptions.add(new FormatOption("صوت فقط — MP3 (حجم متوسط 128k)",
                "bestaudio/best", null, true, "mp3", "128K"));
        formatOptions.add(new FormatOption("صوت فقط — M4A (أصلي سريع)",
                "bestaudio[ext=m4a]/bestaudio/best", null, true, "m4a", "0"));
        formatOptions.add(new FormatOption("صوت فقط — OPUS (أصغر حجم)",
                "bestaudio/best", null, true, "opus", "0"));
        formatOptions.add(new FormatOption("صوت فقط — WAV (غير مضغوط)",
                "bestaudio/best", null, true, "wav", "0"));
        formatOptions.add(new FormatOption("صوت فقط — FLAC (بدون فقد جودة)",
                "bestaudio/best", null, true, "flac", "0"));
        refreshSpinner();
    }

    private void refreshSpinner() {
        List<String> labels = new ArrayList<>();
        for (FormatOption opt : formatOptions) {
            labels.add(opt.label);
        }
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFormat.setAdapter(spinnerAdapter);
    }

    private void initEngineAsync() {
        tvDownloadStatus.setText("جارٍ تهيئة محرك التحميل (yt-dlp + FFmpeg)...");
        executor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplicationContext());
                FFmpeg.getInstance().init(getApplicationContext());
                engineReady = true;
                runOnUiThread(() -> tvDownloadStatus.setText("المحرك جاهز! الصق رابط يوتيوب واختر الصيغة أو اضغط جلب كل الصيغ."));
            } catch (Exception e) {
                runOnUiThread(() -> tvDownloadStatus.setText("فشل تهيئة المحرك: " + e.getMessage()));
            }
        });
    }

    private void fetchAvailableFormats() {
        if (!engineReady) {
            Toast.makeText(this, "انتظر اكتمال تهيئة المحرك...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(this, "توجد عملية قيد التنفيذ حالياً", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            etUrl.setError("أدخل رابط الفيديو أولاً");
            return;
        }

        busy = true;
        setButtonsEnabled(false);
        tvDownloadStatus.setText("جارٍ قراءة جميع الصيغ المتاحة من يوتيوب...");
        progressBar.setIndeterminate(true);

        executor.execute(() -> {
            try {
                YoutubeDLRequest req = new YoutubeDLRequest(url);
                req.addOption("-J");
                req.addOption("--no-playlist");
                YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req);
                JSONObject json = new JSONObject(resp.getOut());
                String title = json.optString("title", "فيديو يوتيوب");
                JSONArray formats = json.optJSONArray("formats");

                List<FormatOption> fetched = new ArrayList<>();
                // Keep quick presets at the top, then append every exact stream from YouTube
                setupDefaultFormatsList(fetched);

                if (formats != null) {
                    for (int i = formats.length() - 1; i >= 0; i--) {
                        JSONObject f = formats.optJSONObject(i);
                        if (f == null) continue;
                        String id = f.optString("format_id", "");
                        String ext = f.optString("ext", "");
                        String vcodec = f.optString("vcodec", "none");
                        String acodec = f.optString("acodec", "none");
                        if (TextUtils.isEmpty(id) || "mhtml".equalsIgnoreCase(ext)) continue;
                        if ("none".equals(vcodec) && "none".equals(acodec)) continue;

                        long sizeBytes = f.optLong("filesize", 0);
                        if (sizeBytes <= 0) sizeBytes = f.optLong("filesize_approx", 0);
                        String sizeStr = sizeBytes > 0
                                ? String.format(Locale.US, " (%.1f MB)", sizeBytes / (1024.0 * 1024.0))
                                : "";

                        if (!"none".equals(vcodec)) {
                            int height = f.optInt("height", 0);
                            int fps = f.optInt("fps", 0);
                            String res = height > 0 ? (height + "p" + (fps > 30 ? fps : "")) : f.optString("format_note", "video");
                            boolean hasAudio = !"none".equals(acodec);
                            String selector = hasAudio ? id : (id + "+bestaudio/best");
                            String label = "[فيديو] " + res + " • " + ext.toUpperCase(Locale.US)
                                    + (hasAudio ? " (مدمج)" : " (+صوت)") + sizeStr + " [" + id + "]";
                            fetched.add(new FormatOption(label, selector, "mp4", false, null, null));
                        } else {
                            int abr = (int) Math.round(f.optDouble("abr", 0));
                            String bitrate = abr > 0 ? (abr + "kbps") : "audio";
                            String label = "[صوت خام] " + ext.toUpperCase(Locale.US) + " • " + bitrate
                                    + sizeStr + " [" + id + "]";
                            fetched.add(new FormatOption(label, id, null, false, null, null));
                        }
                    }
                }

                runOnUiThread(() -> {
                    busy = false;
                    progressBar.setIndeterminate(false);
                    setButtonsEnabled(true);
                    tvVideoTitle.setText(title);
                    tvVideoTitle.setVisibility(View.VISIBLE);
                    formatOptions.clear();
                    formatOptions.addAll(fetched);
                    refreshSpinner();
                    tvDownloadStatus.setText("تم جلب " + fetched.size() + " صيغة متاحة! اختر الصيغة من القائمة واضغط بدء التحميل.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    progressBar.setIndeterminate(false);
                    setButtonsEnabled(true);
                    tvDownloadStatus.setText("تعذّر جلب الصيغ: " + e.getMessage());
                });
            }
        });
    }

    private void setupDefaultFormatsList(List<FormatOption> target) {
        target.add(new FormatOption("★ فيديو MP4 — 360p (متوازن)",
                "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/best[height<=360]", "mp4", false, null, null));
        target.add(new FormatOption("★ فيديو MP4 — 720p HD",
                "bestvideo[height<=720]+bestaudio/best[height<=720]", "mp4", false, null, null));
        target.add(new FormatOption("★ فيديو MP4 — 1080p Full HD",
                "bestvideo[height<=1080]+bestaudio/best[height<=1080]", "mp4", false, null, null));
        target.add(new FormatOption("★ صوت فقط — MP3 (320k)",
                "bestaudio/best", null, true, "mp3", "0"));
        target.add(new FormatOption("★ صوت فقط — M4A (أصلي)",
                "bestaudio[ext=m4a]/bestaudio/best", null, true, "m4a", "0"));
        target.add(new FormatOption("★ صوت فقط — OPUS",
                "bestaudio/best", null, true, "opus", "0"));
    }

    private void startDownload() {
        if (!engineReady) {
            Toast.makeText(this, "انتظر اكتمال تهيئة المحرك...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(this, "التحميل قيد التنفيذ بالفعل", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }

        String url = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            etUrl.setError("أدخل رابط الفيديو أولاً");
            return;
        }

        int idx = spinnerFormat.getSelectedItemPosition();
        if (idx < 0 || idx >= formatOptions.size()) idx = 0;
        FormatOption chosen = formatOptions.get(idx);

        busy = true;
        setButtonsEnabled(false);
        btnCancel.setEnabled(true);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        tvDownloadStatus.setText("جارٍ بدء التحميل (" + chosen.label + ")...");

        executor.execute(() -> {
            File workDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "work_" + System.currentTimeMillis());
            workDir.mkdirs();
            try {
                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("--no-mtime");
                request.addOption("--no-playlist");
                request.addOption("--restrict-filenames");
                request.addOption("-f", chosen.formatSelector);

                if (chosen.extractAudio) {
                    request.addOption("-x");
                    request.addOption("--audio-format", chosen.audioFormat);
                    if (chosen.audioQuality != null) {
                        request.addOption("--audio-quality", chosen.audioQuality);
                    }
                } else if (chosen.mergeFormat != null) {
                    request.addOption("--merge-output-format", chosen.mergeFormat);
                }

                request.addOption("-o", workDir.getAbsolutePath() + "/%(title).80s.%(ext)s");

                Function3<Float, Long, String, Unit> callback = (progress, etaInSeconds, line) -> {
                    runOnUiThread(() -> {
                        if (progress >= 0) {
                            progressBar.setProgress(Math.min(100, Math.round(progress)));
                        }
                        if (!TextUtils.isEmpty(line)) {
                            tvDownloadStatus.setText(line);
                        }
                    });
                    return Unit.INSTANCE;
                };

                YoutubeDL.getInstance().execute(request, PROCESS_ID, callback);

                File[] downloadedFiles = workDir.listFiles();
                int savedCount = 0;
                String lastSavedName = "";
                if (downloadedFiles != null) {
                    for (File f : downloadedFiles) {
                        if (f.isFile() && f.length() > 0 && !f.getName().endsWith(".part")) {
                            publishToPublicDownloads(f);
                            savedCount++;
                            lastSavedName = f.getName();
                        }
                    }
                }
                cleanupDir(workDir);

                final int finalCount = savedCount;
                final String finalName = lastSavedName;
                runOnUiThread(() -> {
                    busy = false;
                    setButtonsEnabled(true);
                    btnCancel.setEnabled(false);
                    progressBar.setProgress(100);
                    if (finalCount > 0) {
                        tvDownloadStatus.setText("تم التحميل بنجاح!\nالملف: " + finalName + "\nالمسار: Download/YouTubeDownloader");
                        Toast.makeText(this, "تم حفظ الملف في Download/YouTubeDownloader", Toast.LENGTH_LONG).show();
                    } else {
                        tvDownloadStatus.setText("انتهت العملية ولكن لم يُعثر على ملف ناتج.");
                    }
                });
            } catch (Exception e) {
                cleanupDir(workDir);
                runOnUiThread(() -> {
                    busy = false;
                    setButtonsEnabled(true);
                    btnCancel.setEnabled(false);
                    tvDownloadStatus.setText("حدث خطأ أثناء التحميل:\n" + e.getMessage());
                });
            }
        });
    }

    private void publishToPublicDownloads(File sourceFile) throws Exception {
        String name = sourceFile.getName();
        String mime = guessMimeType(name);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YouTubeDownloader");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("تعذّر إنشاء الملف في مجلد التنزيلات");

            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("تعذّر فتح مسار الحفظ");
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        } else {
            File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YouTubeDownloader");
            if (!outDir.exists()) outDir.mkdirs();
            File dest = new File(outDir, name);
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    private String guessMimeType(String filename) {
        String lower = filename.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }

    private void cleanupDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    private void cancelDownload() {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
            tvDownloadStatus.setText("تم إيقاف التحميل.");
        } catch (Exception ignored) {
        }
    }

    private void updateYtDlp() {
        if (!engineReady || busy) return;
        busy = true;
        setButtonsEnabled(false);
        tvDownloadStatus.setText("جارٍ تحديث محرك yt-dlp لأحدث إصدار...");
        progressBar.setIndeterminate(true);

        executor.execute(() -> {
            try {
                YoutubeDL.UpdateStatus status = YoutubeDL.getInstance()
                        .updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE.INSTANCE);

                runOnUiThread(() -> {
                    busy = false;
                    progressBar.setIndeterminate(false);
                    setButtonsEnabled(true);
                    tvDownloadStatus.setText("نتيجة التحديث: " + (status != null ? status.name() : "تم"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    progressBar.setIndeterminate(false);
                    setButtonsEnabled(true);
                    tvDownloadStatus.setText("فشل التحديث: " + e.getMessage());
                });
            }
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        btnDownload.setEnabled(enabled);
        btnFetchFormats.setEnabled(enabled);
        btnUpdateEngine.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        cancelDownload();
        executor.shutdownNow();
        super.onDestroy();
    }
}
