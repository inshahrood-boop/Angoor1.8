package ir.angoor.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.media.RingtoneManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

public final class ReminderScheduler {
    static final String CHANNEL_ID = "angoor_reminders_v2";
    static final String EXTRA_ID = "reminder_id";
    static final String EXTRA_DESCRIPTION = "reminder_description";
    static final String EXTRA_TITLE = "reminder_title";
    private static final String PREFS = "angoor_native_reminders";
    private static final String KEY_ITEMS = "items";

    private ReminderScheduler() { }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 800});
        channel.setSound(sound, audio);
        channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    public static boolean scheduleFromJson(Context context, String payload) {
        try {
            JSONObject input = new JSONObject(payload == null ? "{}" : payload);
            String id = input.optString("id", "").trim();
            if (id.isEmpty()) return false;
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("trigger", input.optLong("triggerAt", input.optLong("trigger", 0L)));
            item.put("title", input.optString("title", context.getString(R.string.alarm_title)));
            item.put("description", input.optString("description", "یادآوری"));
            item.put("repeat", input.optString("repeat", "none"));
            JSONArray days = input.optJSONArray("days");
            item.put("days", days == null ? new JSONArray() : days);
            if (item.optLong("trigger", 0L) <= 0L) return false;
            saveItem(context, item);
            scheduleAlarm(context, item);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean syncFromJson(Context context, String payload) {
        try {
            JSONArray list = new JSONArray(payload == null ? "[]" : payload);
            cancelAllAlarms(context);
            prefs(context).edit().putString(KEY_ITEMS, "{}").commit();
            boolean ok = true;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null) ok &= scheduleFromJson(context, item.toString());
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    static void scheduleAlarm(Context context, JSONObject item) {
        try {
            String id = item.getString("id");
            long trigger = Math.max(item.getLong("trigger"), System.currentTimeMillis() + 1000L);
            Intent intent = new Intent(context, ReminderReceiver.class)
                    .setAction(context.getPackageName() + ".REMINDER." + id)
                    .putExtra(EXTRA_ID, id);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    requestCode(id),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, trigger, pending);
            }
        } catch (Exception ignored) { }
    }

    public static void cancel(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        cancelAlarmOnly(context, id);
        removeItem(context, id);
    }

    private static void cancelAlarmOnly(Context context, String id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(context.getPackageName() + ".REMINDER." + id);
        PendingIntent pending = PendingIntent.getBroadcast(
                context,
                requestCode(id),
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (manager != null && pending != null) {
            manager.cancel(pending);
            pending.cancel();
        }
    }

    private static void cancelAllAlarms(Context context) {
        JSONObject all = getAllItems(context);
        Iterator<String> keys = all.keys();
        while (keys.hasNext()) cancelAlarmOnly(context, keys.next());
    }

    static JSONObject getItem(Context context, String id) {
        return id == null ? null : getAllItems(context).optJSONObject(id);
    }

    static void afterFired(Context context, JSONObject item) {
        try {
            String repeat = item.optString("repeat", "none");
            if ("none".equals(repeat)) {
                removeItem(context, item.getString("id"));
                return;
            }
            long next = nextTrigger(item.getLong("trigger"), repeat, item.optJSONArray("days"));
            int guard = 0;
            while (next > 0 && next <= System.currentTimeMillis() && guard++ < 400) {
                next = nextTrigger(next, repeat, item.optJSONArray("days"));
            }
            if (next <= 0) {
                removeItem(context, item.getString("id"));
                return;
            }
            item.put("trigger", next);
            saveItem(context, item);
            scheduleAlarm(context, item);
        } catch (Exception ignored) { }
    }

    public static void restoreAll(Context context) {
        createNotificationChannel(context);
        JSONObject all = getAllItems(context);
        Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            JSONObject item = all.optJSONObject(keys.next());
            if (item == null) continue;
            try {
                long trigger = item.optLong("trigger", 0L);
                String repeat = item.optString("repeat", "none");
                if (trigger <= System.currentTimeMillis()) {
                    if ("none".equals(repeat)) {
                        item.put("trigger", System.currentTimeMillis() + 4000L);
                        saveItem(context, item);
                    } else {
                        int guard = 0;
                        while (trigger <= System.currentTimeMillis() && guard++ < 400) {
                            trigger = nextTrigger(trigger, repeat, item.optJSONArray("days"));
                            if (trigger <= 0L) break;
                        }
                        if (trigger <= 0L) continue;
                        item.put("trigger", trigger);
                        saveItem(context, item);
                    }
                }
                scheduleAlarm(context, item);
            } catch (Exception ignored) { }
        }
    }

    private static long nextTrigger(long current, String repeat, JSONArray days) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(current);
        switch (repeat) {
            case "daily":
                cal.add(Calendar.DAY_OF_YEAR, 1);
                return cal.getTimeInMillis();
            case "weekly":
                cal.add(Calendar.WEEK_OF_YEAR, 1);
                return cal.getTimeInMillis();
            case "monthly":
                cal.add(Calendar.MONTH, 1);
                return cal.getTimeInMillis();
            case "custom":
                if (days == null || days.length() == 0) return -1L;
                for (int i = 1; i <= 14; i++) {
                    Calendar next = (Calendar) cal.clone();
                    next.add(Calendar.DAY_OF_YEAR, i);
                    int jsDay = next.get(Calendar.DAY_OF_WEEK) - 1;
                    for (int j = 0; j < days.length(); j++) {
                        if (days.optInt(j, -1) == jsDay) return next.getTimeInMillis();
                    }
                }
                return -1L;
            default:
                return -1L;
        }
    }

    private static synchronized void saveItem(Context context, JSONObject item) {
        try {
            JSONObject all = getAllItems(context);
            all.put(item.getString("id"), item);
            prefs(context).edit().putString(KEY_ITEMS, all.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static synchronized void removeItem(Context context, String id) {
        JSONObject all = getAllItems(context);
        all.remove(id);
        prefs(context).edit().putString(KEY_ITEMS, all.toString()).apply();
    }

    private static JSONObject getAllItems(Context context) {
        String raw = prefs(context).getString(KEY_ITEMS, "{}");
        try {
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int requestCode(String id) {
        return id.hashCode() & 0x7fffffff;
    }
}
