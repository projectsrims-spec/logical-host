package com.logicalhost.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    static final String EXTRA_OPEN_CONSOLE = "open_console";
    static volatile boolean visible = false;

    private boolean wantConsole = false;
    private FrameLayout container;
    private ValueCallback<Uri[]> fileCb;

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            r -> {
                if (fileCb != null) {
                    fileCb.onReceiveValue(WebChromeClient.FileChooserParams
                            .parseResult(r.getResultCode(), r.getData()));
                    fileCb = null;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#05070c"));
        getWindow().setNavigationBarColor(Color.parseColor("#05070c"));

        wantConsole = getIntent().getBooleanExtra(EXTRA_OPEN_CONSOLE, false);

        container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#05070c"));
        setContentView(container);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (WebHost.canGoBack()) {
                    WebHost.goBack();
                } else {
                    moveTaskToBack(true); // keep the bot running
                }
            }
        });

        startForegroundService(new Intent(this, KeepAliveService.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;

        WebHost.chooser = (cb, p) -> {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                picker.launch(p.createIntent());
            } catch (Exception e) {
                fileCb = null;
                cb.onReceiveValue(null);
            }
            return true;
        };
        WebHost.attach(this, container);
        KeepAliveService.sync(); // app on screen: hide the native bubble
        WebHost.ensureHome(900); // back to the web app's Home if the page went blank
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_CONSOLE, false)) wantConsole = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Runs after onStart AND after onNewIntent, so tapping the notification
        // works whether the app was closed or already open in the background.
        if (wantConsole) {
            wantConsole = false;
            WebHost.openConsole();
        }
        askNext();
        KeepAliveService.sync(); // overlay permission may have just been granted
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        KeepAliveService.sync(); // app left: native bubble appears if the bot is on
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) WebHost.refresh();
    }

    @Override
    protected void onDestroy() {
        WebHost.chooser = null;
        WebHost.detach(this);
        super.onDestroy();
    }

    // Ask one permission at a time, each only once.
    private void askNext() {
        SharedPreferences sp = getSharedPreferences("lh", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !sp.getBoolean("asked_notif", false)) {
            sp.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }

        if (!Settings.canDrawOverlays(this) && !sp.getBoolean("asked_overlay", false)) {
            sp.edit().putBoolean("asked_overlay", true).apply();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())
                && !sp.getBoolean("asked_batt", false)) {
            sp.edit().putBoolean("asked_batt", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
        }
    }
}
