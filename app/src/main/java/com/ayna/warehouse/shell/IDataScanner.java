package com.ayna.warehouse.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

public class IDataScanner implements ScannerDevice {

    private static final String TAG = "IDataScanner";

    private static final String RES_ACTION = "android.intent.action.SCANRESULT";

    private static final String ACTION_ENABLE_SCANNER = "android.intent.action.BARCODESCAN";
    private static final String ACTION_OUTPUT = "android.intent.action.BARCODEOUTPUT";
    private static final String ACTION_LOCK_SCAN_KEY = "android.intent.action.BARCODELOCKSCANKEY";
    private static final String ACTION_CHARSET = "android.intent.actionCHARSET";
    private static final String ACTION_POWER = "android.intent.action.POWER";
    private static final String ACTION_BEEP = "android.intent.action.BEEP";
    private static final String ACTION_FAILURE_BEEP = "android.intent.action.FAILUREBEEP";
    private static final String ACTION_VIBRATE = "android.intent.action.VIBRATE";
    private static final String ACTION_FAILURE_BROADCAST = "android.intent.action.FAILUREBROADCAST";
    private static final String ACTION_TIMEOUT = "android.intent.action.TIMEOUT";
    private static final String ACTION_INTERVAL = "android.intent.action.INTERVALTIME";
    private static final String ACTION_MAX_MULTIREAD = "android.intent.action.MAXMULTIREADCOUNT";

    private final Context context;
    private boolean receiverRegistered;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String data = intent.getStringExtra("value");
            if (data == null || data.isEmpty()) {
                Log.w(TAG, "Scan broadcast without barcode data");
                return;
            }
            String type = intent.getStringExtra("barcodeType");
            Log.i(TAG, "Scan result: " + data + (type != null ? " (" + type + ")" : ""));
            MainActivity.deliverDecode(data, type, System.currentTimeMillis());
        }
    };

    public IDataScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized void open() {
        applySettings();
        registerReceiver();
        Log.i(TAG, "Scanner opened, waiting for scans");
    }

    @Override
    public synchronized boolean isAlive() {
        return receiverRegistered;
    }

    @Override
    public synchronized void recover() {
        Log.i(TAG, "Recovering scanner session");
        applySettings();
        registerReceiver();
    }

    private void applySettings() {
        sendBool(ACTION_ENABLE_SCANNER, true);
        sendBool(ACTION_BEEP, true);
        sendBool(ACTION_FAILURE_BEEP, false);
        sendBool(ACTION_VIBRATE, true);
        sendBool(ACTION_POWER, true);
        sendBool(ACTION_FAILURE_BROADCAST, true);
        sendInt(ACTION_CHARSET, 4);
        sendInt(ACTION_TIMEOUT, 10);
        sendInt(ACTION_INTERVAL, 1000);
        sendInt(ACTION_MAX_MULTIREAD, 5);
        sendInt(ACTION_OUTPUT, 1);
        sendVoid(ACTION_LOCK_SCAN_KEY);
    }

    private void sendVoid(String action) {
        try {
            context.sendBroadcast(new Intent(action));
        } catch (Exception e) {
            Log.w(TAG, "Broadcast failed: " + action, e);
        }
    }

    private void sendBool(String action, boolean value) {
        try {
            Intent intent = new Intent(action);
            intent.putExtra(action, value);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Broadcast failed: " + action, e);
        }
    }

    private void sendInt(String action, int value) {
        try {
            Intent intent = new Intent(action);
            intent.putExtra(action, value);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Broadcast failed: " + action, e);
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(RES_ACTION);
        context.registerReceiver(scanReceiver, filter);
        receiverRegistered = true;
    }

    @Override
    public synchronized void close() {
        try {
            if (receiverRegistered) {
                context.unregisterReceiver(scanReceiver);
                receiverRegistered = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered");
        }
        sendBool(ACTION_ENABLE_SCANNER, false);
    }
}