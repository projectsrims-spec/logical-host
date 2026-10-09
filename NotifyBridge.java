package com.logicalhost.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class NotifyBridge {

    static final String CHANNEL_ID = "signals";   // trade signals from the EA
    static final String STATUS_ID = "status";     // bot on/off and execution
    // true = also show a notification while the app is on screen
    private static final boolean NOTIFY_WHEN_VISIBLE = false;

    private static final int ID_ON = 2001;
    private static final int ID_OFF = 2002;
    private static final int ID_EXEC = 2003;

    // Status changes are only announced after they hold for a moment, so a page reload
    // (which reports "off" then "on") does not send a false "Bot is OFF / ON" alert.
    private static final long SETTLE_MS = 4000;
    private static final long EXEC_SETTLE_MS = 1500;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int nextId = 1000;
    private String lastKey = "";
    private long lastAt = 0;

    // Status tracking. reportedOn == null means the page has not reported yet.
    private static Boolean reportedOn = null;
    private static boolean reportedExec = false;
    private Runnable pendingOn;
    private Runnable pendingExec;

    public NotifyBridge(Context c) {
        ctx = c.getApplicationContext();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        NotificationChannel signals = new NotificationChannel(
                CHANNEL_ID, "Signals", NotificationManager.IMPORTANCE_HIGH);
        signals.setDescription("Trade signals and scanner alerts");
        signals.enableVibration(true);
        signals.setVibrationPattern(new long[]{0, 200, 100, 200});
        signals.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(signals);

        NotificationChannel status = new NotificationChannel(
                STATUS_ID, "Bot status", NotificationManager.IMPORTANCE_DEFAULT);
        status.setDescription("Bot turned on or off, and trade execution");
        nm.createNotificationChannel(status);
    }

    @JavascriptInterface
    public boolean isNative() {
        return true;
    }

    @JavascriptInterface
    public void show(String title, String body) {
        if (title == null) title = "Logical Host";
        if (body == null) body = "";
        if (MainActivity.visible && !NOTIFY_WHEN_VISIBLE) return;

        synchronized (this) {
            String key = title + "|" + body;
            long now = System.currentTimeMillis();
            if (key.equals(lastKey) && now - lastAt < 3000) return; // same alert twice
            lastKey = key;
            lastAt = now;
        }
        KeepAliveService.postEvent(title + " \u2014 " + body);
        post(CHANNEL_ID, nextId++, title, body);
    }

    /**
     * Called by bridge.js whenever the web bubble appears, disappears or changes.
     * on = the page is showing its bot bubble, i.e. the bot is running.
     * executing = the bot is placing a trade right now.
     */
    @JavascriptInterface
    public void botState(final boolean on, final boolean executing, final boolean eaOff) {
        main.post(() -> {
            KeepAliveService.setState(on, executing, eaOff);
            track(on, executing);
        });
    }

    private void track(boolean on, boolean exec) {
        if (reportedOn == null) {               // first report after start: remember, stay quiet
            reportedOn = on;
            reportedExec = exec;
            return;
        }

        if (pendingOn != null) main.removeCallbacks(pendingOn);
        pendingOn = null;
        if (on != reportedOn) {
            final boolean target = on;
            pendingOn = () -> {
                reportedOn = target;
                if (target) {
                    KeepAliveService.postEvent("Bot is ON");
                    post(STATUS_ID, ID_ON, "Bot is ON", "Logical Host is running the bot.");
                } else {
                    KeepAliveService.postEvent("Bot is OFF");
                    post(STATUS_ID, ID_OFF, "Bot is OFF", "Logical Host has stopped the bot.");
                }
            };
            main.postDelayed(pendingOn, SETTLE_MS);
        }

        if (pendingExec != null) main.removeCallbacks(pendingExec);
        pendingExec = null;
        if (!exec) {
            reportedExec = false;                // trade finished: re-arm for the next one
        } else if (!reportedExec) {
            pendingExec = () -> {
                reportedExec = true;
                KeepAliveService.postEvent("Executing trade");
                post(STATUS_ID, ID_EXEC, "Executing trade", "The bot is placing a trade.");
            };
            main.postDelayed(pendingExec, EXEC_SETTLE_MS);
        }
    }

    /** Builds and posts one notification on the given channel. Tap opens the web console. */
    private void post(String channel, int id, String title, String body) {
        if (MainActivity.visible && !NOTIFY_WHEN_VISIBLE) return;
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(MainActivity.EXTRA_OPEN_CONSOLE, true);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
            if (nm.areNotificationsEnabled()) {
                nm.notify(id, b.build());
            }
        } catch (SecurityException ignored) {
        }
    }
}
