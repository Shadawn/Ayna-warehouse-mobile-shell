package com.ayna.warehouse.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class UpdateManager {

    private static final String TAG = "UpdateManager";
    private static final String PREFS_NAME = "warehouse_shell_prefs";
    private static final String KEY_DECLINED_VERSION = "declined_update_version";
    private static final String KEY_DECLINED_AT = "declined_update_at";
    private static final long DECLINED_COOLDOWN_MS = 24L * 60 * 60 * 1000;

    private UpdateManager() {
    }

    public static void checkAndUpdate(final Activity activity, final String serverUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateInfo info = fetchUpdateInfo(serverUrl);
                    if (info == null) {
                        return;
                    }
                    PackageInfo packageInfo = activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0);
                    final long currentVersion = PackageInfoCompat.getLongVersionCode(packageInfo);
                    if (info.versionCode <= currentVersion) {
                        return;
                    }
                    SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (info.versionCode == prefs.getInt(KEY_DECLINED_VERSION, -1)
                            && System.currentTimeMillis() - prefs.getLong(KEY_DECLINED_AT, 0) < DECLINED_COOLDOWN_MS) {
                        return;
                    }
                    final File apk = downloadApk(activity, info);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            promptInstall(activity, info, apk);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Update check failed", e);
                }
            }
        }).start();
    }

    private static UpdateInfo fetchUpdateInfo(String serverUrl) throws Exception {
        URL base = new URL(serverUrl);
        URL checkUrl = new URL(base.getProtocol(), base.getHost(),
                base.getPort() > 0 ? base.getPort() : base.getDefaultPort(),
                "/mobile/update.json");
        HttpURLConnection conn = (HttpURLConnection) checkUrl.openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Update endpoint returned " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();

            JSONObject json = new JSONObject(sb.toString());
            UpdateInfo info = new UpdateInfo();
            info.versionCode = json.optLong("versionCode", -1);
            info.versionName = json.optString("versionName", "");
            info.apkUrl = json.optString("apkUrl", "");
            info.sha256 = json.optString("sha256", "");
            if (info.versionCode <= 0 || info.apkUrl.isEmpty()) {
                Log.w(TAG, "Update manifest is missing versionCode or apkUrl");
                return null;
            }
            if (!info.apkUrl.startsWith("http://") && !info.apkUrl.startsWith("https://")) {
                info.apkUrl = new URL(base, info.apkUrl).toString();
            }
            return info;
        } finally {
            conn.disconnect();
        }
    }

    private static File downloadApk(Context context, UpdateInfo info) throws IOException {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create updates dir");
        }
        for (File old : dir.listFiles()) {
            if (!old.getName().equals("update-" + info.versionCode + ".apk")) {
                old.delete();
            }
        }
        File apk = new File(dir, "update-" + info.versionCode + ".apk");
        if (apk.exists() && (info.sha256.isEmpty() || verifySha256(apk, info.sha256))) {
            return apk;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(info.apkUrl).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("APK download returned " + code);
            }
            File tmp = new File(dir, "update-" + info.versionCode + ".apk.tmp");
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            if (!info.sha256.isEmpty() && !verifySha256(tmp, info.sha256)) {
                tmp.delete();
                throw new IOException("SHA-256 mismatch");
            }
            if (!tmp.renameTo(apk)) {
                apk.delete();
                if (!tmp.renameTo(apk)) {
                    throw new IOException("Cannot move downloaded APK");
                }
            }
            return apk;
        } finally {
            conn.disconnect();
        }
    }

    private static boolean verifySha256(File file, String expected) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        FileInputStream in = new FileInputStream(file);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            digest.update(buf, 0, n);
        }
        in.close();
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return expected.equalsIgnoreCase(hex.toString());
    }

    private static void promptInstall(final Activity activity, final UpdateInfo info, final File apk) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(activity.getString(R.string.update_message, info.versionName))
                .setPositiveButton(R.string.update_install, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestInstall(activity, apk);
                    }
                })
                .setNegativeButton(R.string.update_later, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        rememberDeclined(activity, info);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        rememberDeclined(activity, info);
                    }
                })
                .show();
    }

    private static void rememberDeclined(Activity activity, UpdateInfo info) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DECLINED_VERSION, (int) info.versionCode)
                .putLong(KEY_DECLINED_AT, System.currentTimeMillis())
                .apply();
    }

    private static void requestInstall(final Activity activity, final File apk) {
        PackageManager pm = activity.getPackageManager();
        boolean canInstall = pm.canRequestPackageInstalls();
        Log.i(TAG, "canRequestPackageInstalls=" + canInstall);
        if (!canInstall) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setPositiveButton(R.string.update_permission_open_settings,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:" + activity.getPackageName()));
                                    activity.startActivity(intent);
                                }
                            })
                    .setNegativeButton(R.string.update_later, null)
                    .show();
            return;
        }
        Uri apkUri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Log.i(TAG, "APK uri=" + apkUri + " size=" + apk.length());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(activity.getPackageManager()) == null) {
            Log.e(TAG, "No activity found to handle APK install");
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_no_installer)
                    .setPositiveButton(R.string.change_url_ok, null)
                    .show();
            return;
        }
        try {
            activity.startActivity(intent);
            Log.i(TAG, "Package installer launched");
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch package installer", e);
        }
    }

    private static class UpdateInfo {
        long versionCode;
        String versionName;
        String apkUrl;
        String sha256;
    }
}