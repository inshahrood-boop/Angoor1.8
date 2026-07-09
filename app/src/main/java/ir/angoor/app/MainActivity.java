package ir.angoor.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.Executor;

public final class MainActivity extends FragmentActivity {
    private static final int REQUEST_RECORD_AUDIO = 4101;
    private static final int REQUEST_NOTIFICATIONS = 4102;
    private static final int REQUEST_FILE_CHOOSER = 4103;
    private static final int REQUEST_WRITE_STORAGE = 4104;
    private static final long TRANSACTION_SPEECH_WINDOW_MS = 10_000L;
    private static final long REVIEW_SPEECH_WINDOW_MS = 20_000L;
    private static final long SPEECH_RETRY_DELAY_MS = 180L;
    private static final String DOWNLOAD_CHANNEL_ID = "angoor_downloads_v1";

    private FrameLayout rootLayout;
    private WebView webView;
    private AndroidBridge androidBridge;
    private SpeechRecognizer speechRecognizer;
    private String pendingSpeechMode = "transaction";
    private boolean speechPendingAfterPermission;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private long speechDeadlineElapsed;
    private int speechSession;
    private boolean speechResultDelivered;
    private boolean speechStopRequested;
    private boolean speechStartNotified;
    private Runnable speechDeadlineRunnable;
    private boolean pageReady;
    private boolean exactAlarmPromptShown;
    private boolean fullScreenPromptShown;
    private boolean alarmPermissionFlowPending;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingReminderId;
    private String pendingLegacySavePayload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        ReminderScheduler.createNotificationChannel(this);
        createDownloadNotificationChannel();
        configureWebView();
        handleReminderIntent(getIntent());
        requestNotificationPermissionIfNeeded();
    }

    private void configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        applySystemTheme("light");
    }

    void applySystemTheme(String theme) {
        boolean dark = "dark".equalsIgnoreCase(theme);
        int barColor = dark ? Color.rgb(11, 15, 20) : Color.WHITE;
        Window window = getWindow();
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(barColor);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!dark);
            controller.setAppearanceLightNavigationBars(!dark);
        }
        if (rootLayout != null) rootLayout.setBackgroundColor(barColor);
        if (webView != null) webView.setBackgroundColor(barColor);
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.WHITE);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        rootLayout.addView(webView, webParams);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (view, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) webView.getLayoutParams();
            if (params.leftMargin != safeInsets.left || params.topMargin != safeInsets.top ||
                    params.rightMargin != safeInsets.right || params.bottomMargin != safeInsets.bottom) {
                params.setMargins(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom);
                webView.setLayoutParams(params);
            }
            return windowInsets;
        });
        setContentView(rootLayout);
        ViewCompat.requestApplyInsets(rootLayout);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setSaveFormData(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        androidBridge = new AndroidBridge(this);
        webView.addJavascriptInterface(androidBridge, "AngoorAndroid");
        webView.addJavascriptInterface(androidBridge, "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverPendingReminder();
                callJs("try{if(typeof syncNativeReminders==='function')syncNativeReminders();if(typeof refreshVoiceAvailability==='function')refreshVoiceAvailability();}catch(e){}");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isInternalUri(uri)) return false;
                openExternalUri(uri);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isInternalUri(uri)) return false;
                openExternalUri(uri);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audioRequested = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            audioRequested = true;
                            break;
                        }
                    }
                    if (audioRequested && hasAudioPermission()) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallbackValue, FileChooserParams fileChooserParams) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = filePathCallbackValue;
                try {
                    Intent chooser = fileChooserParams.createIntent();
                    chooser.addCategory(Intent.CATEGORY_OPENABLE);
                    chooser.setType("application/json");
                    startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    showNativeToast("انتخاب فایل امکان‌پذیر نیست");
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return super.onConsoleMessage(consoleMessage);
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean isInternalUri(Uri uri) {
        if (uri == null || uri.getScheme() == null) return true;
        String scheme = uri.getScheme();
        return "file".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme);
    }

    private void openExternalUri(Uri uri) {
        if (uri == null) return;
        try {
            if ("intent".equalsIgnoreCase(uri.getScheme())) {
                Intent parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(parsed);
                } catch (ActivityNotFoundException e) {
                    String fallback = parsed.getStringExtra("browser_fallback_url");
                    if (fallback != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                }
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            String host = uri.getHost();
            if (host != null && host.contains("instagram.com")) {
                Intent appIntent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.instagram.android");
                try {
                    startActivity(appIntent);
                    return;
                } catch (ActivityNotFoundException ignored) { }
            }
            startActivity(intent);
        } catch (Exception ignored) {
            showNativeToast("برنامه‌ای برای بازکردن این لینک پیدا نشد");
        }
    }

    boolean isBiometricAvailable() {
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK;
        return BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    void authenticateBiometric(String reason) {
        runOnUiThread(() -> {
            if (!isBiometricAvailable()) {
                callJs("window.onAngoorBiometricResult&&window.onAngoorBiometricResult(false,'اثر انگشت در این گوشی در دسترس نیست');");
                return;
            }
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    callJs("window.onAngoorBiometricResult&&window.onAngoorBiometricResult(true,'');");
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    callJs("window.onAngoorBiometricResult&&window.onAngoorBiometricResult(false," + JSONObject.quote(errString.toString()) + ");");
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    callJs("window.onAngoorBiometricResult&&window.onAngoorBiometricResult(false,'اثر انگشت تأیید نشد');");
                }
            });
            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("ورود به انگور")
                    .setSubtitle(reason == null || reason.trim().isEmpty() ? "اثر انگشت را تأیید کنید" : reason)
                    .setNegativeButtonText("استفاده از رمز ۴ رقمی")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build();
            prompt.authenticate(promptInfo);
        });
    }

    boolean isSpeechRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(this);
    }

    void startSpeechRecognition(String mode) {
        runOnUiThread(() -> {
            pendingSpeechMode = "review".equals(mode) ? "review" : "transaction";
            speechSession++;
            speechResultDelivered = false;
            speechStopRequested = false;
            speechStartNotified = false;
            speechDeadlineElapsed = SystemClock.elapsedRealtime() +
                    ("review".equals(pendingSpeechMode) ? REVIEW_SPEECH_WINDOW_MS : TRANSACTION_SPEECH_WINDOW_MS);
            clearSpeechTimers();
            scheduleSpeechDeadline(speechSession);
            if (!hasAudioPermission()) {
                speechPendingAfterPermission = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                return;
            }
            beginSpeechRecognition(speechSession);
        });
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void beginSpeechRecognition(int session) {
        if (session != speechSession || speechResultDelivered || speechStopRequested) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishSpeechWithError(session, "service-not-allowed");
            return;
        }
        if (SystemClock.elapsedRealtime() >= speechDeadlineElapsed) {
            finishSpeechWithError(session, "no-speech");
            return;
        }
        destroySpeechRecognizer(false);
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (session != speechSession || speechResultDelivered) return;
                if (!speechStartNotified) {
                    speechStartNotified = true;
                    callJs("window.onAngoorSpeechStarted&&window.onAngoorSpeechStarted(" + JSONObject.quote(pendingSpeechMode) + ");");
                }
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                if (session != speechSession || speechResultDelivered || speechStopRequested) return;
                destroySpeechRecognizer(false);
                if (isRetryableSpeechError(error) && SystemClock.elapsedRealtime() < speechDeadlineElapsed) {
                    speechHandler.postDelayed(() -> beginSpeechRecognition(session), SPEECH_RETRY_DELAY_MS);
                } else if (isRetryableSpeechError(error)) {
                    finishSpeechWithError(session, "no-speech");
                } else {
                    finishSpeechWithError(session, mapSpeechError(error));
                }
            }
            @Override public void onResults(Bundle results) {
                if (session != speechSession || speechResultDelivered || speechStopRequested) return;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches == null || matches.isEmpty()) {
                    destroySpeechRecognizer(false);
                    if (SystemClock.elapsedRealtime() < speechDeadlineElapsed) {
                        speechHandler.postDelayed(() -> beginSpeechRecognition(session), SPEECH_RETRY_DELAY_MS);
                    } else {
                        finishSpeechWithError(session, "no-speech");
                    }
                    return;
                }
                speechResultDelivered = true;
                clearSpeechTimers();
                JSONArray alternatives = new JSONArray();
                for (String match : matches) alternatives.put(match);
                callJs("window.onAngoorSpeechResult&&window.onAngoorSpeechResult(" + JSONObject.quote(pendingSpeechMode) + "," + alternatives + ");");
                destroySpeechRecognizer(true);
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);
        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            destroySpeechRecognizer(false);
            if (SystemClock.elapsedRealtime() < speechDeadlineElapsed) {
                speechHandler.postDelayed(() -> beginSpeechRecognition(session), SPEECH_RETRY_DELAY_MS);
            } else {
                finishSpeechWithError(session, "service-not-allowed");
            }
        }
    }

    private boolean isRetryableSpeechError(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT;
    }

    private void scheduleSpeechDeadline(int session) {
        clearSpeechTimers();
        long delay = Math.max(1L, speechDeadlineElapsed - SystemClock.elapsedRealtime());
        speechDeadlineRunnable = () -> {
            if (session != speechSession || speechResultDelivered || speechStopRequested) return;
            finishSpeechWithError(session, "no-speech");
        };
        speechHandler.postDelayed(speechDeadlineRunnable, delay);
    }

    private void clearSpeechTimers() {
        if (speechDeadlineRunnable != null) {
            speechHandler.removeCallbacks(speechDeadlineRunnable);
            speechDeadlineRunnable = null;
        }
    }

    private void finishSpeechWithError(int session, String code) {
        if (session != speechSession || speechResultDelivered || speechStopRequested) return;
        speechResultDelivered = true;
        clearSpeechTimers();
        destroySpeechRecognizer(false);
        callSpeechError(code);
        callJs("window.onAngoorSpeechEnded&&window.onAngoorSpeechEnded(" + JSONObject.quote(pendingSpeechMode) + ");");
    }

    void stopSpeechRecognition() {
        runOnUiThread(() -> {
            speechStopRequested = true;
            speechResultDelivered = true;
            speechSession++;
            clearSpeechTimers();
            if (speechRecognizer != null) {
                try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            }
            destroySpeechRecognizer(true);
        });
    }

    private void callSpeechError(String code) {
        callJs("window.onAngoorSpeechError&&window.onAngoorSpeechError(" + JSONObject.quote(pendingSpeechMode) + "," + JSONObject.quote(code) + ");");
    }

    private String mapSpeechError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio-capture";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "not-allowed";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no-speech";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT: return "aborted";
            default: return "service-not-allowed";
        }
    }

    private void destroySpeechRecognizer(boolean notifyEnded) {
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        if (notifyEnded) {
            callJs("window.onAngoorSpeechEnded&&window.onAngoorSpeechEnded(" + JSONObject.quote(pendingSpeechMode) + ");");
        }
    }

    void requestExactAlarmAccess() {
        alarmPermissionFlowPending = true;
        runOnUiThread(this::continueAlarmPermissionFlow);
    }

    private void continueAlarmPermissionFlow() {
        if (!alarmPermissionFlowPending || isFinishing()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !ReminderScheduler.canScheduleExact(this) &&
                !exactAlarmPromptShown) {
            exactAlarmPromptShown = true;
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Exception e) {
                showNativeToast("از تنظیمات گوشی، اجازه آلارم دقیق را برای انگور فعال کنید");
            }
        }

        if (Build.VERSION.SDK_INT >= 34 && !fullScreenPromptShown) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && !manager.canUseFullScreenIntent()) {
                fullScreenPromptShown = true;
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception e) {
                    showNativeToast("از تنظیمات گوشی، نمایش تمام‌صفحه آلارم را برای انگور فعال کنید");
                }
            }
        }

        alarmPermissionFlowPending = false;
    }

    void callJs(String script) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    void showNativeToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    boolean hasLegacyStoragePermission() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void requestLegacyStoragePermission(String payloadJson) {
        runOnUiThread(() -> {
            pendingLegacySavePayload = payloadJson;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasLegacyStoragePermission()) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
            } else if (androidBridge != null) {
                String pending = pendingLegacySavePayload;
                pendingLegacySavePayload = null;
                androidBridge.saveFile(pending);
            }
        });
    }

    private void createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(getString(R.string.download_channel_description));
        manager.createNotificationChannel(channel);
    }

    void onFileSaved(Uri uri, String filename, String mimeType, boolean openImmediately) {
        runOnUiThread(() -> {
            if (uri == null) {
                showNativeToast("ذخیره فایل انجام نشد");
                return;
            }
            Intent openIntent = buildOpenFileIntent(uri, mimeType);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this,
                    Math.abs((filename + System.currentTimeMillis()).hashCode()),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.download_complete_title))
                    .setContentText(filename)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent);
            try {
                NotificationManagerCompat.from(this).notify(Math.abs(filename.hashCode()), builder.build());
            } catch (SecurityException ignored) { }

            showNativeToast("فایل در پوشه Downloads ذخیره شد");
            if (openImmediately) {
                try {
                    startActivity(openIntent);
                } catch (ActivityNotFoundException e) {
                    showNativeToast("فایل ذخیره شد؛ برنامه‌ای برای بازکردن PDF پیدا نشد");
                } catch (Exception e) {
                    showNativeToast("فایل ذخیره شد اما بازکردن خودکار انجام نشد");
                }
            }
        });
    }

    private Intent buildOpenFileIntent(Uri uri, String mimeType) {
        String type = mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType;
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .setClipData(ClipData.newRawUri("Angoor file", uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void handleReminderIntent(Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra(ReminderScheduler.EXTRA_ID);
        if (id != null && !id.isEmpty()) {
            pendingReminderId = id;
            deliverPendingReminder();
        }
    }

    private void deliverPendingReminder() {
        if (!pageReady || pendingReminderId == null || webView == null) return;
        String id = pendingReminderId;
        pendingReminderId = null;
        callJs("window.onAngoorReminderFired&&window.onAngoorReminderFired(" + JSONObject.quote(id) + ");");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleReminderIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReminderScheduler.restoreAll(this);
        if (alarmPermissionFlowPending && webView != null) {
            webView.postDelayed(this::continueAlarmPermissionFlow, 250L);
        }
        if (pageReady) callJs("try{if(typeof syncNativeReminders==='function')syncNativeReminders();}catch(e){}");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && speechPendingAfterPermission) beginSpeechRecognition(speechSession);
            else {
                clearSpeechTimers();
                speechResultDelivered = true;
                callSpeechError("not-allowed");
                callJs("window.onAngoorSpeechEnded&&window.onAngoorSpeechEnded(" + JSONObject.quote(pendingSpeechMode) + ");");
            }
            speechPendingAfterPermission = false;
        } else if (requestCode == REQUEST_WRITE_STORAGE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String pending = pendingLegacySavePayload;
            pendingLegacySavePayload = null;
            if (granted && pending != null && androidBridge != null) androidBridge.saveFile(pending);
            else showNativeToast("برای ذخیره فایل در Downloads اجازه حافظه لازم است");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        String script = "(function(){try{" +
                "var bg=document.getElementById('modalBg');" +
                "if(bg&&(bg.classList.contains('show')||bg.classList.contains('active'))){if(typeof closeModal==='function')closeModal();return true;}" +
                "var active=document.querySelector('.screen.active');" +
                "if(active&&active.id!=='screen-home'){if(typeof goto==='function')goto('home');return true;}" +
                "}catch(e){}return false;})()";
        webView.evaluateJavascript(script, value -> {
            if (!"true".equals(value)) super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        speechStopRequested = true;
        speechSession++;
        clearSpeechTimers();
        speechHandler.removeCallbacksAndMessages(null);
        destroySpeechRecognizer(false);
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AngoorAndroid");
            webView.removeJavascriptInterface("Android");
            webView.stopLoading();
            webView.clearHistory();
            webView.destroy();
            webView = null;
        }
        rootLayout = null;
        androidBridge = null;
        pendingLegacySavePayload = null;
        super.onDestroy();
    }
}
