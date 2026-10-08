package com.ayna.warehouse.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.util.Log;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.widget.Button;
import android.widget.EditText;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Properties;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "warehouse_shell_prefs";
    private static final String KEY_URL = "server_url";

    private static final long DEDUP_WINDOW_MS = 800;
    private static final long PENDING_WINDOW_MS = 15000;
    private static final long WATCHDOG_INTERVAL_MS = 10000;

    private static WeakReference<MainActivity> sInstance = new WeakReference<>(null);
    private static String sLastData;
    private static long sLastTimestamp;
    private static String sPendingJson;
    private static long sPendingAt;

    private WebView webView;
    private ScannerDevice scanner;
    private boolean pageLoaded;
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private boolean watchdogRunning;

    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            if (scanner != null && !scanner.isAlive()) {
                Log.w("MainActivity", "Watchdog: scanner session dead, recovering");
                scanner.recover();
            }
            if (watchdogRunning) {
                watchdog.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new WeakReference<>(this);

        String url = getSavedUrl();
        if (url == null) {
            url = getDefaultUrlFromConfig();
        }
        if (url == null) {
            showSetupScreen();
        } else {
            ensureCameraPermission(url);
        }
    }

    private void ensureCameraPermission(final String url) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openWebView(url);
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, 1001);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        String url = getSavedUrl();
        if (url == null) {
            url = getDefaultUrlFromConfig();
        }
        if (url == null) {
            showSetupScreen();
        } else {
            openWebView(url);
        }
    }

    private String getDefaultUrlFromConfig() {
        try {
            InputStream in = getAssets().open("config.properties");
            Properties props = new Properties();
            props.load(in);
            in.close();
            String url = props.getProperty("default_url");
            if (url == null || url.trim().isEmpty()) {
                return null;
            }
            return normalizeUrl(url);
        } catch (IOException e) {
            Log.w("MainActivity", "Could not read config.properties", e);
            return null;
        }
    }

    private String getSavedUrl() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_URL, null);
    }

    private void saveUrl(String url) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url)
                .apply();
    }

    private void showSetupScreen() {
        setContentView(R.layout.activity_setup);

        EditText urlInput = findViewById(R.id.url_input);
        Button saveButton = findViewById(R.id.save_button);

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = normalizeUrl(urlInput.getText().toString());
                if (url == null) {
                    urlInput.setError(getString(R.string.setup_hint));
                    return;
                }
                saveUrl(url);
                ensureCameraPermission(url);
            }
        });
    }

    private String normalizeUrl(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        return trimmed;
    }

    private void openWebView(String url) {
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                String js = "if(typeof window.onBarcodeDecode !== 'function'){"
                        + "window.onBarcodeDecode=function(d){"
                        + "if(d&&typeof d.data==='string'){"
                        + "window.lastBarcodeScan=d;"
                        + "window.dispatchEvent(new CustomEvent('barcode',{detail:d}));"
                        + "}true;};"
                        + "}true;";
                view.evaluateJavascript(js, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                if (sPendingJson != null && System.currentTimeMillis() - sPendingAt < PENDING_WINDOW_MS) {
                    evaluateDecode(sPendingJson);
                }
                sPendingJson = null;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }
        });
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                promptChangeUrl();
                return true;
            }
        });

        webView.loadUrl(url);

        scanner = createScanner();
        scanner.open();
        startWatchdog();

        UpdateManager.checkAndUpdate(this, url);
    }

    private ScannerDevice createScanner() {
        String manufacturer = Build.MANUFACTURER;
        if (manufacturer != null && manufacturer.toLowerCase().contains("idata")) {
            Log.i("MainActivity", "iData device detected: " + manufacturer);
            return new IDataScanner(this);
        }
        return new UrovoScanner(this);
    }

    private void startWatchdog() {
        if (!watchdogRunning) {
            watchdogRunning = true;
            watchdog.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
        }
    }

    private void stopWatchdog() {
        watchdogRunning = false;
        watchdog.removeCallbacks(watchdogTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanner != null && !scanner.isAlive()) {
            Log.w("MainActivity", "onResume: scanner session dead, recovering");
            scanner.recover();
        }
    }

    private void promptChangeUrl() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.setup_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.change_url_title)
                .setView(input)
                .setPositiveButton(R.string.change_url_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = normalizeUrl(input.getText().toString());
                        if (url != null) {
                            saveUrl(url);
                            webView.loadUrl(url);
                        }
                    }
                })
                .setNegativeButton(R.string.change_url_cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public static void deliverDecode(String data, String format, long timestamp) {
        if (data != null && data.equals(sLastData) && timestamp - sLastTimestamp < DEDUP_WINDOW_MS) {
            return;
        }
        sLastData = data;
        sLastTimestamp = timestamp;
        MainActivity activity = sInstance.get();
        if (activity != null) {
            activity.pushDecodeToWeb(data, format, timestamp);
        }
    }

    private void pushDecodeToWeb(String data, String format, long timestamp) {
        if (webView == null) {
            return;
        }
        try {
            JSONObject detail = new JSONObject();
            detail.put("data", data);
            detail.put("format", format != null ? format : JSONObject.NULL);
            detail.put("timestamp", timestamp);
            String json = detail.toString();

            if (!pageLoaded) {
                sPendingJson = json;
                sPendingAt = System.currentTimeMillis();
                return;
            }
            evaluateDecode(json);
        } catch (JSONException e) {
            Log.e("MainActivity", "Failed to build decode payload", e);
        }
    }

    private void evaluateDecode(String json) {
        WebView wv = webView;
        if (wv == null) {
            return;
        }
        String js = "var d=" + json
                + ";window.lastBarcodeScan=d;window.dispatchEvent(new CustomEvent('barcode',{detail:d}));"
                + "if(window.onBarcodeDecode){window.onBarcodeDecode(d)};"
                + "console.log('[warehouse-shell] barcode',d);true;";
        wv.evaluateJavascript(js, null);
    }

    @Override
    protected void onDestroy() {
        stopWatchdog();
        if (scanner != null) {
            scanner.close();
            scanner = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (sInstance.get() == this) {
            sInstance.clear();
        }
        super.onDestroy();
    }
}