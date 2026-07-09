package ir.angoor.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class AndroidBridge {
    private final MainActivity activity;

    AndroidBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void setSystemTheme(String theme) {
        activity.runOnUiThread(() -> activity.applySystemTheme(theme));
    }

    @JavascriptInterface
    public boolean isExactAlarmSupported() {
        return true;
    }

    @JavascriptInterface
    public boolean scheduleReminder(String payloadJson) {
        boolean result = ReminderScheduler.scheduleFromJson(activity, payloadJson);
        if (result) activity.requestExactAlarmAccess();
        return result;
    }

    @JavascriptInterface
    public boolean cancelReminder(String id) {
        ReminderScheduler.cancel(activity, id);
        return true;
    }

    @JavascriptInterface
    public boolean syncReminders(String payloadJson) {
        boolean result = ReminderScheduler.syncFromJson(activity, payloadJson);
        if (result && hasReminderItems(payloadJson)) activity.requestExactAlarmAccess();
        return result;
    }

    private boolean hasReminderItems(String payloadJson) {
        try {
            return new JSONArray(payloadJson == null ? "[]" : payloadJson).length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean isBiometricAvailable() {
        return activity.isBiometricAvailable();
    }

    @JavascriptInterface
    public String authenticateBiometric(String reason) {
        activity.authenticateBiometric(reason);
        return "pending";
    }

    @JavascriptInterface
    public boolean isSpeechRecognitionAvailable() {
        return activity.isSpeechRecognitionAvailable();
    }

    @JavascriptInterface
    public void startSpeechRecognition(String mode) {
        activity.startSpeechRecognition(mode == null ? "transaction" : mode);
    }

    @JavascriptInterface
    public void stopSpeechRecognition() {
        activity.stopSpeechRecognition();
    }

    @JavascriptInterface
    public boolean shareBackup(String payloadJson) {
        return shareFile(payloadJson);
    }

    @JavascriptInterface
    public boolean shareFile(String payloadJson) {
        try {
            JSONObject payload = new JSONObject(payloadJson == null ? "{}" : payloadJson);
            String filename = sanitizeFilename(payload.optString("filename", "Angoor-file"));
            String mimeType = payload.optString("mimeType", "application/octet-stream");
            byte[] bytes = payloadBytes(payload);
            new Thread(() -> {
                try {
                    File file = writeSharedFile(filename, bytes);
                    Uri uri = ShareFileProvider.uriFor(activity, file);
                    Intent intent = new Intent(Intent.ACTION_SEND)
                            .setType(mimeType)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, payload.optString("subject", "Angoor"))
                            .putExtra(Intent.EXTRA_TEXT, payload.optString("text", "فایل خروجی انگور"))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.runOnUiThread(() -> activity.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری فایل انگور")));
                } catch (Exception e) {
                    activity.showNativeToast("اشتراک‌گذاری فایل انجام نشد");
                }
            }, "AngoorShare").start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean saveFile(String payloadJson) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !activity.hasLegacyStoragePermission()) {
            activity.requestLegacyStoragePermission(payloadJson);
            return true;
        }
        try {
            JSONObject payload = new JSONObject(payloadJson == null ? "{}" : payloadJson);
            String filename = sanitizeFilename(payload.optString("filename", "Angoor-file"));
            String mimeType = payload.optString("mimeType", "application/octet-stream");
            byte[] bytes = payloadBytes(payload);
            new Thread(() -> {
                try {
                    Uri uri = saveToDownloads(filename, mimeType, bytes);
                    activity.onFileSaved(uri, filename, mimeType, "application/pdf".equalsIgnoreCase(mimeType));
                } catch (Exception e) {
                    activity.showNativeToast("ذخیره فایل انجام نشد");
                }
            }, "AngoorSave").start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] payloadBytes(JSONObject payload) throws Exception {
        String content = payload.optString("content", "");
        String encoding = payload.optString("encoding", "text");
        if ("base64".equalsIgnoreCase(encoding)) {
            return android.util.Base64.decode(content, android.util.Base64.DEFAULT);
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private File writeSharedFile(String filename, byte[] bytes) throws Exception {
        File dir = new File(activity.getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create share directory");
        File file = new File(dir, filename);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private Uri saveToDownloads(String filename, String mimeType, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = activity.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("Cannot open output");
                out.write(bytes);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) return null;
        File file = new File(dir, filename);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(activity, new String[]{file.getAbsolutePath()}, new String[]{mimeType}, null);
        // برای بازکردن امن فایل در اندرویدهای قدیمی، یک نسخه فقط‌خواندنی در کش اشتراک‌گذاری می‌سازیم.
        File shared = writeSharedFile(filename, bytes);
        return ShareFileProvider.uriFor(activity, shared);
    }

    private static String sanitizeFilename(String filename) {
        String value = filename == null ? "Angoor-file" : filename;
        value = value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return value.isEmpty() ? "Angoor-file" : value;
    }
}
