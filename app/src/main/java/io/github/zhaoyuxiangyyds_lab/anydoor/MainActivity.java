package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public class MainActivity extends Activity {
    private static final String TAG = "AnyDoor";
    private WebView web;
    private JsBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        beginInitialization();
    }

    private void beginInitialization() {
        android.widget.TextView loading = new android.widget.TextView(this);
        loading.setText("正在加载设置；升级时会迁移收藏，请在提示时授予 Root…");
        loading.setPadding(32, 80, 32, 32);
        setContentView(loading);
        new Thread(() -> {
            try {
                LegacyPrefsMigration.migrate(this);
                Config.ensureDefaults(this);
                if (!SpoofService.isRunning()) Config.commit(Config.config(this).edit().putBoolean(Keys.STARTED, false));
                Config.mirror();
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) initializeUi(); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new android.app.AlertDialog.Builder(this).setTitle("设置迁移未完成")
                            .setMessage(e.getMessage()).setPositiveButton("重试", (d, w) -> beginInitialization())
                            .setNegativeButton("退出", (d, w) -> finish()).setCancelable(false).show();
                });
            }
        }, "anydoor-init").start();
    }

    private void initializeUi() {
        applyWindowStyle();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(100);
        WebView.setWebContentsDebuggingEnabled(true);
        web.setBackgroundColor(isNight() ? 0xFF15161C : 0xFFF4F5F8);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        bridge = new JsBridge(this, web);
        web.addJavascriptInterface(bridge, "Native");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "js: " + m.message() + " @" + m.lineNumber());
                bridge.log("JS " + m.messageLevel() + ": " + m.message() + " (" + m.lineNumber() + ")");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/web/index.html");
        requestPerms();
    }

    private void applyWindowStyle() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        boolean night = isNight();
        w.setStatusBarColor(night ? 0xFF15161C : 0xFFF4F5F8);
        w.setNavigationBarColor(night ? 0xFF15161C : 0xFFF4F5F8);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        if (!night) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(f);
    }

    boolean isNight() {
        String t = Config.app(this).getString(Keys.THEME, "auto");
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    void onThemeChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyWindowStyle();
                web.setBackgroundColor(isNight() ? 0xFF15161C : 0xFFF4F5F8);
            }
        });
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
    }

    @Override
    public void onBackPressed() {
        if (web == null) { finishBack(); return; }
        web.evaluateJavascript("(window.handleBack && window.handleBack()) ? 1 : 0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"1".equals(v)) finishBack();
            }
        });
    }

    private void finishBack() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
