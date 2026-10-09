package com.logicalhost.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the app alive in the background and draws the native bot bubble over other apps.
 *
 * The bubble is shown only when the bot is on, the app is not on screen, and the
 * "display over other apps" permission is granted. Tapping it toggles a small bot
 * console card on top of the current app (it does not bring Logical Host to the front).
 */
public class KeepAliveService extends Service {

    static final String ACTION_STOP = "com.logicalhost.app.STOP";
    private static final String CHANNEL = "running";
    private static final int NOTIF_ID = 1;
    private static final int MAX_EVENTS = 20;

    static KeepAliveService instance;

    // State pushed by the web page (bridge.js -> NotifyBridge.botState). Main thread only.
    static boolean botOn = false;
    static boolean executing = false;
    static boolean eaOff = false;

    // Recent activity lines shown in the console card (newest last).
    private static final ArrayList<String> events = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Remembered bubble position (px) so it comes back where you left it.
    private static int savedX = Integer.MIN_VALUE;
    private static int savedY = Integer.MIN_VALUE;

    private PowerManager.WakeLock lock;
    private WindowManager wm;

    // Bubble
    private FrameLayout bubble;
    private GradientDrawable ring;
    private GradientDrawable dotShape;
    private WindowManager.LayoutParams bubbleLp;

    // Console card
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView panelStatus;
    private TextView panelEa;
    private TextView panelLog;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        botOn = false;       // never trust old state after a (re)start
        executing = false;
        eaOff = false;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Bot running", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "logicalhost:bot");
        lock.setReferenceCounted(false);
        lock.acquire();

        WebHost.get(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            botOn = false;
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            Activity a = WebHost.activity;
            if (a != null) a.finish();
            WebHost.destroy();
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        // Tapping the notification opens the app on the web bot console.
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CONSOLE, true);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, KeepAliveService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Logical Host is online")
                .setContentText("Bot is running. Tap to open.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .build();
    }

    // ---------------- state pushed from the web page ----------------

    /** Main thread. Called by NotifyBridge.botState(). */
    static void setState(boolean on, boolean exec, boolean off) {
        botOn = on;
        executing = on && exec;
        eaOff = on && off;
        sync();
    }

    /** Any thread. Adds a line to the console's recent activity. */
    static void postEvent(final String line) {
        MAIN.post(() -> addEvent(line));
    }

    private static void addEvent(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        events.add(time + "  " + line);
        while (events.size() > MAX_EVENTS) events.remove(0);
        KeepAliveService s = instance;
        if (s != null) s.refreshPanel();
    }

    /** Call from the main thread whenever botOn / app-visible / permission may have changed. */
    static void sync() {
        KeepAliveService s = instance;
        if (s != null) s.syncOverlay();
    }

    // ---------------- overlay: bubble + console card ----------------

    private void syncOverlay() {
        boolean want = botOn && !MainActivity.visible && Settings.canDrawOverlays(this);
        if (!want) {
            removeOverlay();
            return;
        }
        if (bubble == null) addBubble();
        else paintBubble();
        refreshPanel();
    }

    private void removeOverlay() {
        closePanel();
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        bubble = null;
        ring = null;
        dotShape = null;
    }

    private static int dp(float d, float v) {
        return (int) (v * d + 0.5f);
    }

    private void addBubble() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float d = dm.density;
        final int size = dp(d, 60);
        final int margin = dp(d, 8);

        ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ring.setGradientRadius(size * 0.7f);
        ring.setGradientCenter(0.35f, 0.30f);
        ring.setColors(new int[]{0xFF2A3D99, 0xFF060914});

        FrameLayout root = new FrameLayout(this);
        root.setBackground(ring);
        root.setElevation(dp(d, 8));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(d, 9);
        logo.setPadding(pad, pad, pad, pad);
        root.addView(logo, new FrameLayout.LayoutParams(size, size));

        // status dot, top right (green on / orange executing / grey EA off)
        View dot = new View(this);
        dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotShape);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(d, 14), dp(d, 14));
        dotLp.gravity = Gravity.TOP | Gravity.END;
        dotLp.topMargin = dp(d, 2);
        dotLp.rightMargin = dp(d, 2);
        root.addView(dot, dotLp);

        bubble = root;
        bubbleLp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;

        if (savedX == Integer.MIN_VALUE) {
            savedX = dm.widthPixels - size - margin;
            savedY = (int) (dm.heightPixels * 0.55f);
        }
        bubbleLp.x = clampX(savedX, size, dm.widthPixels);
        bubbleLp.y = clampY(savedY, size, dm.heightPixels);

        root.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                DisplayMetrics m = getResources().getDisplayMetrics();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - touchX;
                        float dy = e.getRawY() - touchY;
                        if (Math.abs(dx) > dp(m.density, 8) || Math.abs(dy) > dp(m.density, 8)) {
                            moved = true;
                        }
                        if (moved) {
                            bubbleLp.x = clampX(startX + (int) dx, size, m.widthPixels);
                            bubbleLp.y = clampY(startY + (int) dy, size, m.heightPixels);
                            updateBubble();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            // snap to the nearest side edge and remember the spot
                            boolean left = bubbleLp.x + size / 2 < m.widthPixels / 2;
                            bubbleLp.x = left ? margin : m.widthPixels - size - margin;
                            bubbleLp.y = clampY(bubbleLp.y, size, m.heightPixels);
                            savedX = bubbleLp.x;
                            savedY = bubbleLp.y;
                            updateBubble();
                        } else if (e.getAction() == MotionEvent.ACTION_UP) {
                            togglePanel();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(bubble, bubbleLp);
            paintBubble();
        } catch (Exception ex) {
            bubble = null;
            ring = null;
            dotShape = null;
        }
    }

    private static int clampX(int x, int size, int screenW) {
        return Math.max(0, Math.min(x, screenW - size));
    }

    private static int clampY(int y, int size, int screenH) {
        return Math.max(0, Math.min(y, screenH - size));
    }

    private void updateBubble() {
        if (bubble == null) return;
        try {
            wm.updateViewLayout(bubble, bubbleLp);
        } catch (Exception ignored) {
        }
        positionPanel();
    }

    /** Colours follow the web bubble: blue ring + green dot; orange while executing; grey when EA is off. */
    private void paintBubble() {
        if (bubble == null || ring == null) return;
        float d = getResources().getDisplayMetrics().density;
        int ringColor = 0xFF3D6BFF;
        int dotColor = 0xFF33D17A;
        if (executing) {
            ringColor = 0xFFFF8A3D;
            dotColor = 0xFFFF8A3D;
        } else if (eaOff) {
            ringColor = 0xFF5B7396;
            dotColor = 0xFF5B7396;
        }
        ring.setStroke(dp(d, 2), ringColor);
        dotShape.setColor(dotColor);
        dotShape.setStroke(dp(d, 2), 0xFF05070C);
        bubble.invalidate();
    }

    // ---- console card ----

    private void togglePanel() {
        if (panel == null) openPanel();
        else closePanel();
    }

    private void openPanel() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float d = dm.density;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(d, 14);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2080C18);
        bg.setCornerRadius(dp(d, 18));
        bg.setStroke(dp(d, 1), 0xFF7891FF);
        panel.setBackground(bg);

        // header: title + close
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("BOT CONSOLE");
        title.setTextColor(0xFF6F8BFF);
        title.setTextSize(11);
        title.setLetterSpacing(0.15f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextColor(0xFF8891A7);
        close.setTextSize(16);
        close.setPadding(dp(d, 8), 0, 0, 0);
        close.setOnClickListener(v -> closePanel());
        head.addView(close);
        panel.addView(head);

        panelStatus = new TextView(this);
        panelStatus.setTextColor(0xFFF3F5FB);
        panelStatus.setTextSize(16);
        panelStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        panelStatus.setPadding(0, dp(d, 8), 0, 0);
        panel.addView(panelStatus);

        panelEa = new TextView(this);
        panelEa.setTextColor(0xFF8891A7);
        panelEa.setTextSize(12);
        panel.addView(panelEa);

        TextView label = new TextView(this);
        label.setText("RECENT ACTIVITY");
        label.setTextColor(0xFF5A6178);
        label.setTextSize(10);
        label.setLetterSpacing(0.12f);
        label.setPadding(0, dp(d, 12), 0, dp(d, 4));
        panel.addView(label);

        panelLog = new TextView(this);
        panelLog.setTextColor(0xFFCFD6E6);
        panelLog.setTextSize(12);
        panel.addView(panelLog);

        TextView openBtn = new TextView(this);
        openBtn.setText("OPEN APP");
        openBtn.setTextColor(0xFFFFFFFF);
        openBtn.setTextSize(12);
        openBtn.setGravity(Gravity.CENTER);
        openBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable btn = new GradientDrawable();
        btn.setColor(0xFF4D6FFF);
        btn.setCornerRadius(dp(d, 12));
        openBtn.setBackground(btn);
        openBtn.setPadding(0, dp(d, 10), 0, dp(d, 10));
        openBtn.setOnClickListener(v -> openAppConsole());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(d, 12);
        panel.addView(openBtn, btnLp);

        panelLp = new WindowManager.LayoutParams(
                dp(d, 260), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(panel, panelLp);
        } catch (Exception e) {
            panel = null;
            return;
        }
        positionPanel();
        refreshPanel();
    }

    private void closePanel() {
        if (panel != null && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        panel = null;
        panelStatus = null;
        panelEa = null;
        panelLog = null;
    }

    /** Places the card beside the bubble, on the side with more room. */
    private void positionPanel() {
        if (panel == null || bubbleLp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float d = dm.density;
        int w = dp(d, 260);
        int size = dp(d, 60);
        int gap = dp(d, 8);
        boolean bubbleOnRight = bubbleLp.x + size / 2 > dm.widthPixels / 2;
        int x = bubbleOnRight ? bubbleLp.x - w - gap : bubbleLp.x + size + gap;
        x = Math.max(dp(d, 8), Math.min(x, dm.widthPixels - w - dp(d, 8)));
        int y = Math.max(dp(d, 8), Math.min(bubbleLp.y, dm.heightPixels - dp(d, 320)));
        panelLp.x = x;
        panelLp.y = y;
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Exception ignored) {
        }
    }

    /** Fills the card with the current bot state and the recent activity. */
    private void refreshPanel() {
        if (panel == null || panelStatus == null) return;
        if (!botOn) {
            panelStatus.setText("Bot is OFF");
            panelStatus.setTextColor(0xFF8891A7);
        } else if (executing) {
            panelStatus.setText("Executing trade");
            panelStatus.setTextColor(0xFFFF8A3D);
        } else {
            panelStatus.setText("Bot is ON \u2022 waiting");
            panelStatus.setTextColor(0xFF33D17A);
        }
        panelEa.setText(!botOn ? "" : (eaOff ? "Self-host EA: offline" : "Self-host EA: online"));

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, events.size() - 5);
        for (int i = events.size() - 1; i >= start; i--) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(events.get(i));
        }
        panelLog.setText(sb.length() == 0 ? "No activity yet." : sb.toString());
    }

    /** Opens the app on the web bot console (used by the card's OPEN APP button). */
    private void openAppConsole() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra(MainActivity.EXTRA_OPEN_CONSOLE, true);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
        closePanel();
    }

    @Override
    public void onDestroy() {
        botOn = false;
        removeOverlay();
        if (lock != null && lock.isHeld()) lock.release();
        if (!MainActivity.visible) WebHost.destroy();
        instance = null;
        super.onDestroy();
    }
}
