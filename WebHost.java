package com.logicalhost.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;

/**
 * One single WebView for the whole app. The screen attaches to it when open.
 * When the screen closes it is detached (not destroyed), so the page and the bot
 * running inside it keep working.
 */
public final class WebHost {

    static final String HOME_URL = "https://logical-host-ea.base44.app/android";
    private static final String APP_DOMAIN = "base44.app";

    interface ChooserHandler {
        boolean show(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p);
    }

    static Activity activity;       // set while the screen is showing the WebView
    static ChooserHandler chooser;  // file picker, set by MainActivity

    private static WebView web;
    private static MutableContextWrapper ctx;
    private static Context appContext;
    private static String bridgeJs = "";

    private WebHost() {}

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static WebView get(Context c) {
        if (web != null) return web;

        appContext = c.getApplicationContext();
        bridgeJs = readAsset(appContext, "bridge.js");
        ctx = new MutableContextWrapper(appContext);

        web = new WebView(ctx);
        web.setBackgroundColor(Color.parseColor("#05070c"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // login and keys live in localStorage
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new NotifyBridge(appContext), "AndroidNotify");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    web, bridgeJs, Collections.singleton("https://*." + APP_DOMAIN));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                String scheme = u.getScheme();
                if ("blob".equals(scheme) || "data".equals(scheme) || "about".equals(scheme)) {
                    return false;
                }
                String host = u.getHost();
                if (host != null && (host.equals(APP_DOMAIN) || host.endsWith("." + APP_DOMAIN))) {
                    return false;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(i);
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                v.evaluateJavascript(bridgeJs, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(bridgeJs, null);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    String html = "<html><body style='background:#05070c;color:#fff;"
                            + "font-family:sans-serif;text-align:center;padding-top:30vh'>"
                            + "<h2>No internet</h2><p>Check your connection.</p>"
                            + "<p><a style='color:#4da3ff' href='" + HOME_URL + "'>Try again</a></p>"
                            + "</body></html>";
                    v.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Activity a = activity;
                destroy();
                if (a != null) {
                    a.recreate();
                } else {
                    get(appContext);
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams p) {
                ChooserHandler h = chooser;
                if (h == null) {
                    cb.onReceiveValue(null);
                    return true;
                }
                return h.show(cb, p);
            }
        });

        web.loadUrl(HOME_URL);
        return web;
    }

    /** Show the WebView inside the screen. */
    static void attach(Activity a, ViewGroup container) {
        WebView w = get(a);
        ViewParent p = w.getParent();
        if (p != container) {
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(w);
            container.addView(w, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ctx.setBaseContext(a);
        activity = a;
        w.requestFocus();
        refresh();
    }

    /** Wake the page up and make Android draw it again (fixes a blank screen). */
    static void refresh() {
        final WebView w = web;
        if (w == null) return;
        w.onResume();
        w.resumeTimers();
        w.setVisibility(View.INVISIBLE);
        w.postDelayed(() -> {
            if (web != w) return;
            w.setVisibility(View.VISIBLE);
            w.requestLayout();
            w.invalidate();
            w.evaluateJavascript(
                    "try{window.dispatchEvent(new Event('resize'))}catch(e){}", null);
        }, 80);
        w.postDelayed(() -> {
            if (web != w) return;
            w.requestLayout();
            w.invalidate();
        }, 500);
    }

    /**
     * Open the web bot console (same as tapping the page's own bubble).
     * Retries until the page has drawn its bubble; clicks it only once, so it can
     * never open and then close again. If the bot is off there is no bubble, so
     * nothing happens after the retries.
     */
    static void openConsole() {
        final WebView w = web;
        if (w == null) return;
        w.postDelayed(() -> tryOpenConsole(w, 0), 500);
    }

    private static void tryOpenConsole(final WebView w, final int attempt) {
        if (web != w) return;
        final String js = "(function(){try{"
                + "if(document.querySelector('.lcx-sheet'))return 1;"
                + "var b=document.querySelector('.float-bubble');"
                + "if(b){b.click();return 2;}"
                + "}catch(e){}return 0;})()";
        w.evaluateJavascript(js, v -> {
            if (web != w) return;
            if ("0".equals(v)) {
                if (attempt < 8) {
                    w.postDelayed(() -> tryOpenConsole(w, attempt + 1), 600);
                } else {
                    // still nothing after ~5s: if the page is blank, load it again
                    w.evaluateJavascript(
                            "(function(){return document.body?document.body.innerText.length:-1})()",
                            len -> {
                                if (web == w && ("0".equals(len) || "-1".equals(len))
                                        && w.getProgress() == 100) {
                                    w.reload();
                                }
                            });
                }
            }
        });
    }

    /**
     * When the app comes back to the front, make sure the web app is on its Home screen.
     * If the page is blank (the WebView lost its drawing after the app was in the background)
     * or the page is on another route, load the Home URL again. A page that is already on
     * Home and drawn is left alone, so the bot and its console state are not disturbed.
     */
    static void ensureHome(long delayMs) {
        final WebView w = web;
        if (w == null) return;
        w.postDelayed(() -> {
            if (web != w || w.getProgress() < 100) return; // still loading: leave it
            w.evaluateJavascript(
                    "(function(){try{var p=location.pathname;"
                            + "var home=(p==='/android'||p==='/android/');"
                            + "var drawn=!!document.body&&document.body.innerText.length>0;"
                            + "return (home&&drawn)?1:0;}catch(e){return 0;}})()",
                    v -> {
                        if (web == w && "0".equals(v)) w.loadUrl(HOME_URL);
                    });
        }, delayMs);
    }

    /** Screen destroyed: take the WebView out of it. The page keeps running. */
    static void detach(Context c) {
        if (web == null) return;
        ViewParent p = web.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(web);
        activity = null;
        ctx.setBaseContext(c.getApplicationContext());
        web.onResume();
        web.resumeTimers();
    }

    static boolean canGoBack() {
        return web != null && web.canGoBack();
    }

    static void goBack() {
        if (web != null) web.goBack();
    }

    static void destroy() {
        if (web == null) return;
        ViewParent p = web.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(web);
        web.destroy();
        web = null;
        ctx = null;
        activity = null;
    }

    private static String readAsset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
