package ir.angoor.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.RingtoneManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public final class AlarmActivity extends Activity {
    private static WeakReference<AlarmActivity> active = new WeakReference<>(null);
    private MediaPlayer player;
    private Vibrator vibrator;
    private int notificationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        active = new WeakReference<>(this);
        configureLockScreen();
        render(getIntent());
        startAlarm();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        stopAlarm();
        render(intent);
        startAlarm();
    }

    private void configureLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(75, 49, 94));
        getWindow().setNavigationBarColor(Color.rgb(75, 49, 94));
    }

    private void render(Intent intent) {
        String id = intent.getStringExtra(ReminderScheduler.EXTRA_ID);
        String title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE);
        String description = intent.getStringExtra(ReminderScheduler.EXTRA_DESCRIPTION);
        if (title == null || title.trim().isEmpty()) title = getString(R.string.alarm_title);
        if (description == null || description.trim().isEmpty()) description = "یادآوری";
        notificationId = ReminderScheduler.requestCode(id == null ? description : id);

        int padding = dp(26);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(75, 49, 94));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        iconParams.bottomMargin = dp(26);
        root.addView(icon, iconParams);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(28);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView bodyView = new TextView(this);
        bodyView.setText(description);
        bodyView.setTextColor(Color.argb(230, 255, 255, 255));
        bodyView.setTextSize(19);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(18);
        bodyParams.bottomMargin = dp(36);
        root.addView(bodyView, bodyParams);

        Button confirm = new Button(this);
        confirm.setText(R.string.alarm_confirm);
        confirm.setTextSize(17);
        confirm.setTextColor(Color.rgb(38, 31, 20));
        confirm.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        confirm.setAllCaps(false);
        confirm.setBackgroundResource(R.drawable.alarm_button_background);
        confirm.setOnClickListener(v -> dismissAlarm());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.leftMargin = dp(12);
        buttonParams.rightMargin = dp(12);
        root.addView(confirm, buttonParams);

        setContentView(root);
    }

    private void startAlarm() {
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            player.setDataSource(this, sound);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) { }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = new long[]{0, 700, 300, 700, 300, 1000};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) { }
    }

    private void dismissAlarm() {
        stopAlarm();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(notificationId);
        finishAndRemoveTask();
    }

    private void stopAlarm() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) { }
            vibrator = null;
        }
    }

    static void stopActiveAlarm() {
        AlarmActivity activity = active.get();
        if (activity != null) activity.runOnUiThread(activity::dismissAlarm);
    }

    @Override
    public void onBackPressed() {
        dismissAlarm();
    }

    @Override
    protected void onDestroy() {
        stopAlarm();
        if (active.get() == this) active.clear();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
