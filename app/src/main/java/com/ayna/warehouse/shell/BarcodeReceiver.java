package com.ayna.warehouse.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.nio.charset.StandardCharsets;

public class BarcodeReceiver extends BroadcastReceiver {

    private static final String TAG = "BarcodeReceiver";

    private static final String[] STRING_KEYS = {
            "barcode_string", "barcode", "barcodeData", "scanResult", "text", "value"
    };

    private static final String[] FORMAT_KEYS = {
            "barcode_format", "barcodeType", "format", "formatName"
    };

    private static final String[] BYTE_KEYS = {
            "barcode", "barcodeData", "data"
    };

    private static final String ACTION_DECODE_DATA = "android.intent.action.ACTION_DECODE_DATA";
    private static final String ACTION_SCANRESULT = "android.intent.action.SCANRESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_DECODE_DATA.equals(action) && !ACTION_SCANRESULT.equals(action)) {
            return;
        }

        String data = firstStringExtra(intent, STRING_KEYS);
        if (data == null) {
            data = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (data == null) {
            data = firstByteExtra(intent, BYTE_KEYS);
        }
        if (data == null) {
            Uri uri = intent.getData();
            if (uri != null) {
                data = uri.toString();
            }
        }
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "Decode broadcast received without extractable data");
            return;
        }

        String format = firstStringExtra(intent, FORMAT_KEYS);
        Log.i(TAG, "Decode data received: " + data + (format != null ? " (" + format + ")" : ""));
        MainActivity.deliverDecode(data, format, System.currentTimeMillis());
    }

    private static String firstStringExtra(Intent intent, String... keys) {
        for (String key : keys) {
            String value = intent.getStringExtra(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String firstByteExtra(Intent intent, String... keys) {
        for (String key : keys) {
            byte[] value = intent.getByteArrayExtra(key);
            if (value != null && value.length > 0) {
                return new String(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}