package ir.angoor.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONObject;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(ReminderScheduler.EXTRA_ID);
        if (id == null || id.isEmpty()) return;
        JSONObject item = ReminderScheduler.getItem(context, id);
        if (item == null) return;

        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Angoor:ReminderReceiver");
                wakeLock.acquire(15_000L);
            }

            String title = item.optString("title", context.getString(R.string.alarm_title));
            String description = item.optString("description", "یادآوری");
            int notificationId = ReminderScheduler.requestCode(id);
            ReminderScheduler.createNotificationChannel(context);

            Intent alarmIntent = new Intent(context, AlarmActivity.class)
                    .putExtra(ReminderScheduler.EXTRA_ID, id)
                    .putExtra(ReminderScheduler.EXTRA_TITLE, title)
                    .putExtra(ReminderScheduler.EXTRA_DESCRIPTION, description)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent fullScreenPending = PendingIntent.getActivity(
                    context,
                    notificationId,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Intent openIntent = new Intent(context, MainActivity.class)
                    .putExtra(ReminderScheduler.EXTRA_ID, id)
                    .putExtra(ReminderScheduler.EXTRA_DESCRIPTION, description)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentPending = PendingIntent.getActivity(
                    context,
                    notificationId + 1,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Intent confirmIntent = new Intent(context, NotificationActionReceiver.class)
                    .putExtra("notification_id", notificationId)
                    .putExtra(ReminderScheduler.EXTRA_ID, id);
            PendingIntent confirmPending = PendingIntent.getBroadcast(
                    context,
                    notificationId + 2,
                    confirmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, ReminderScheduler.CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(description)
                    .setStyle(new Notification.BigTextStyle().bigText(description))
                    .setContentIntent(contentPending)
                    .setDeleteIntent(confirmPending)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setFullScreenIntent(fullScreenPending, true)
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.checkbox_on_background,
                            context.getString(R.string.notification_confirm),
                            confirmPending
                    ).build());

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean allowed = Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (manager != null && allowed) manager.notify(notificationId, builder.build());

            ReminderScheduler.afterFired(context, item);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
    }
}
