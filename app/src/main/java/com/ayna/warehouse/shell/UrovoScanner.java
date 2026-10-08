package com.ayna.warehouse.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import android.device.ScanManager;
import android.device.scanner.configuration.PropertyID;
import android.device.scanner.configuration.Symbology;

import java.nio.charset.StandardCharsets;

public class UrovoScanner implements ScannerDevice {

    private static final String TAG = "UrovoScanner";

    private static final int OUTPUT_MODE_INTENT = 0;
    private static final int OUTPUT_MODE_FOCUS = 1;

    private final Context context;
    private ScanManager scanManager;
    private boolean receiverRegistered;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String data = intent.getStringExtra(ScanManager.BARCODE_STRING_TAG);
            if (data == null) {
                byte[] bytes = intent.getByteArrayExtra(ScanManager.DECODE_DATA_TAG);
                int length = intent.getIntExtra(ScanManager.BARCODE_LENGTH_TAG, 0);
                if (bytes != null && length > 0) {
                    data = new String(bytes, 0, Math.min(length, bytes.length), StandardCharsets.UTF_8);
                }
            }
            if (data == null || data.isEmpty()) {
                Log.w(TAG, "Scan broadcast without barcode data");
                return;
            }
            String type = intent.getStringExtra(ScanManager.BARCODE_TYPE_TAG);
            Log.i(TAG, "Scan result: " + data + (type != null ? " (" + type + ")" : ""));
            MainActivity.deliverDecode(data, type, System.currentTimeMillis());
        }
    };

    public UrovoScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void open() {
        try {
            if (scanManager != null) {
                close();
            }
            scanManager = new ScanManager();
            if (!scanManager.openScanner()) {
                Log.w(TAG, "openScanner failed");
                return;
            }
            applySettings();
            registerReceiver();
            Log.i(TAG, "Scanner opened, waiting for scans");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialise scanner", e);
        }
    }

    public synchronized boolean isAlive() {
        try {
            return scanManager != null && scanManager.getScannerState();
        } catch (Exception e) {
            Log.w(TAG, "getScannerState failed", e);
            return false;
        }
    }

    public synchronized void recover() {
        Log.i(TAG, "Recovering scanner session");
        try {
            if (scanManager != null) {
                try {
                    scanManager.stopDecode();
                    scanManager.closeScanner();
                } catch (Exception e) {
                    Log.w(TAG, "Teardown during recover failed", e);
                }
                scanManager = null;
            }
            scanManager = new ScanManager();
            if (!scanManager.openScanner()) {
                Log.w(TAG, "Recover: openScanner failed");
                return;
            }
            applySettings();
            registerReceiver();
            Log.i(TAG, "Scanner recovered");
        } catch (Exception e) {
            Log.e(TAG, "Recover failed", e);
        }
    }

    private void applySettings() {
        try {
            scanManager.enableAllSymbologies(true);
            if (!scanManager.isSymbologyEnabled(Symbology.QRCODE)) {
                scanManager.enableSymbology(Symbology.QRCODE, true);
            }
            int currentMode = scanManager.getOutputMode();
            if (currentMode != OUTPUT_MODE_INTENT) {
                scanManager.switchOutputMode(OUTPUT_MODE_INTENT);
            }
            int[] wedge = {PropertyID.WEDGE_KEYBOARD_ENABLE};
            int[] wedgeValues = scanManager.getParameterInts(wedge);
            if (wedgeValues != null && wedgeValues.length > 0 && wedgeValues[0] != 0) {
                Log.i(TAG, "Disabling keyboard wedge so scans are broadcast");
                int[] disable = {0};
                scanManager.setParameterInts(wedge, disable);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply scanner settings", e);
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        String[] params = scanManager.getParameterString(new int[]{
                PropertyID.WEDGE_INTENT_ACTION_NAME,
                PropertyID.WEDGE_INTENT_DATA_STRING_TAG
        });
        if (params != null && params[0] != null && !params[0].isEmpty()) {
            filter.addAction(params[0]);
        } else {
            filter.addAction(ScanManager.ACTION_DECODE);
        }
        context.registerReceiver(scanReceiver, filter);
        receiverRegistered = true;
    }

    public synchronized void close() {
        try {
            if (receiverRegistered) {
                context.unregisterReceiver(scanReceiver);
                receiverRegistered = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered");
        }
        try {
            if (scanManager != null) {
                scanManager.stopDecode();
                scanManager.closeScanner();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to close scanner", e);
        }
        scanManager = null;
    }
}
