package com.shinow.pda_scanner;

import android.content.IntentFilter;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
//import io.flutter.plugin.common.PluginRegistry;

public class PdaScannerPlugin implements FlutterPlugin, ActivityAware, EventChannel.StreamHandler {
    private static final String CHANNEL_NAME = "com.shinow.pda_scanner/plugin";
    private static final String TAG = "PdaScannerPlugin";

    private static final String XM_SCAN_ACTION = "com.android.server.scannerservice.broadcast";
    private static final String SHINIOW_SCAN_ACTION = "com.android.server.scannerservice.shinow";
    private static final String IDATA_SCAN_ACTION = "android.intent.action.SCANRESULT";
    private static final String YBX_SCAN_ACTION = "android.intent.ACTION_DECODE_DATA";
    private static final String PL_SCAN_ACTION = "scan.rcv.message";
    private static final String BARCODE_DATA_ACTION = "com.ehsy.warehouse.action.BARCODE_DATA";
    private static final String HONEYWELL_SCAN_ACTION = "com.honeywell.decode.intent.action.EDIT_DATA";
    private static final String SEUIC_SCAN_ACTION = "com.android.scanner.service_settings";
    private static final String NL_SCAN_ACTION = "nlscan.action.SCANNER_RESULT";
    private static final String YTO_ACTION = "com.yto.action.GET_SCANDATA";

    private final String pluginInstanceId = java.util.UUID.randomUUID().toString();

    @Nullable
    private EventChannel eventChannel;
    @Nullable
    private Context applicationContext;
    @Nullable
    private Activity activity;
    @Nullable
    private static EventChannel.EventSink eventSink;

    public PdaScannerPlugin() { // 如果你有构造函数
        Log.d(TAG, "PdaScannerPlugin constructor called, instance ID: " + pluginInstanceId);
    }

    private static final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive in plugin instance ID: " + PdaScannerPlugin.this.pluginInstanceId);
            if (eventSink == null) {
                Log.w(TAG, "eventSink is null, discarding scanned data.");
                return;
            }

            String actionName = intent.getAction();
            Log.d(TAG, "Received action:" + actionName);

            String scanResult = null;
            if (XM_SCAN_ACTION.equals(actionName) || SHINIOW_SCAN_ACTION.equals(actionName) || SEUIC_SCAN_ACTION.equals(actionName)) {
                scanResult = getSafeStringFromIntent(intent, "scannerdata");
            } else if (IDATA_SCAN_ACTION.equals(actionName)) {
                scanResult = getSafeStringFromIntent(intent, "value");
            } else if (YBX_SCAN_ACTION.equals(actionName)) {
                scanResult = getSafeStringFromIntent(intent, "barcode_string");
            } else if (PL_SCAN_ACTION.equals(actionName)) {
                byte[] barcode = intent.getByteArrayExtra("barocode");
                int barcodelen = intent.getIntExtra("length", 0);
                if (barcode != null && barcodelen > 0) {
                    scanResult = new String(barcode, 0, barcodelen);
                }
            } else if (HONEYWELL_SCAN_ACTION.equals(actionName) || BARCODE_DATA_ACTION.equals(actionName) || YTO_ACTION.equals(actionName)) {
                scanResult = getSafeStringFromIntent(intent, "data");
            } else if (NL_SCAN_ACTION.equals(actionName)) {
                scanResult = getSafeStringFromIntent(intent, "SCAN_BARCODE1");
            } else {
                Log.i(TAG, "Received scan intent with unhandled action: " + actionName);
                return;
            }

            if (scanResult != null) {
                Log.d(TAG, "Sending success event with data: " + scanResult);
                eventSink.success(scanResult);
            } else {
                Log.w(TAG, "Scan result is null for action: " + actionName);
            }
        }
    };

    // --- FlutterPlugin 实现 ---
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onAttachedToEngine");
        Log.d(TAG, "onAttachedToEngine, instance ID: " + pluginInstanceId);
        this.applicationContext = binding.getApplicationContext();
        BinaryMessenger messenger = binding.getBinaryMessenger();
        eventChannel = new EventChannel(messenger, CHANNEL_NAME);
        eventChannel.setStreamHandler(this); // 设置 StreamHandler
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onDetachedFromEngine");
        this.applicationContext = null;
        if (eventChannel != null) {
            eventChannel.setStreamHandler(null); // 清理 StreamHandler
            eventChannel = null;
        }
    }

    // --- ActivityAware 实现 ---
    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onAttachedToActivity");
        this.activity = binding.getActivity();
        registerReceiver();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges");
        unregisterReceiver();
        this.activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges");
        this.activity = binding.getActivity();
        registerReceiver();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
        unregisterReceiver();
        this.activity = null;
    }

    // --- EventChannel.StreamHandler 实现 ---
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        Log.d(TAG, "onListen called");
        this.eventSink = events; // 保存 EventSink 实例
        // 注意：注册 Receiver 的操作移到了 onAttachedToActivity
    }

    @Override
    public void onCancel(Object arguments) {
        Log.d(TAG, "onCancel called");
        this.eventSink = null; // 清理 EventSink 实例
        // 注意：注销 Receiver 的操作移到了 onDetachedFromActivity
    }

    private void registerReceiver() {
        if (this.activity == null) {
            Log.e(TAG, "Activity is null, cannot register receiver.");
            return;
        }
        if (applicationContext == null) {
            Log.e(TAG, "ApplicationContext is null, cannot register receiver.");
            return;
        }

        // 使用一个 IntentFilter 添加所有 Action，更简洁
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(XM_SCAN_ACTION);
        intentFilter.addAction(SHINIOW_SCAN_ACTION);
        intentFilter.addAction(IDATA_SCAN_ACTION);
        intentFilter.addAction(YBX_SCAN_ACTION);
        intentFilter.addAction(PL_SCAN_ACTION);
        intentFilter.addAction(BARCODE_DATA_ACTION);
        intentFilter.addAction(HONEYWELL_SCAN_ACTION);
        intentFilter.addAction(SEUIC_SCAN_ACTION);
        intentFilter.addAction(NL_SCAN_ACTION);
        intentFilter.addAction(YTO_ACTION);

        // 设置高优先级，尝试优先接收广播
        intentFilter.setPriority(Integer.MAX_VALUE);

        Log.d(TAG, "Registering ScanReceiver for activity: " + activity.getLocalClassName());

        activity.registerReceiver(scanReceiver, intentFilter);
    }

    private void unregisterReceiver() {
        if (this.activity != null) {
            try {
                Log.d(TAG, "Unregistering ScanReceiver for activity: " + activity.getLocalClassName());
                activity.unregisterReceiver(scanReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver not registered or already unregistered: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Activity is null, cannot unregister receiver.");
        }
    }

    private static String getSafeStringFromIntent(Intent intent, String key) {
        if (intent == null || key == null || !intent.hasExtra(key)) {
            return null;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) return null;

        Object extraData = extras.get(key);
        if (extraData == null) {
            return null;
        }

        if (extraData instanceof String) {
            return ((String) extraData).trim();
        }

        if (extraData instanceof byte[]) {
            Log.w(TAG, "Intent extra '" + key + "' was of type byte[]. Attempting UTF-8 conversion.");
            byte[] byteArray = (byte[]) extraData;
            if (byteArray.length == 0) {
                return ""; // 空字节数组视为空字符串
            }

            // 这部分是启发式的，具体设备可能行为不同
            int length = byteArray.length; // 默认使用完整数组长度
            String lengthKeyCandidate1 = key + "_length";
            String lengthKeyCandidate2 = "length";

            if (extras.containsKey(lengthKeyCandidate1)) {
                length = extras.getInt(lengthKeyCandidate1, byteArray.length);
            } else if (extras.containsKey(lengthKeyCandidate2)) {
                length = extras.getInt(lengthKeyCandidate2, byteArray.length);
            }

            if (length < 0 || length > byteArray.length) {
                Log.w(TAG, "Invalid length (" + length + ") for byte array key '" + key + "'. Using full array length (" + byteArray.length + ").");
                length = byteArray.length;
            }

            // 如果计算出的长度为0，但实际有字节数据，也使用完整长度（除非原始字节数组就为空）
            if (length == 0 && byteArray.length > 0) {
                 Log.w(TAG, "Calculated length for byte array key '" + key + "' was 0, but array has data. Using full array length.");
                 length = byteArray.length;
            }

            try {
                return new String(byteArray, 0, length, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                Log.e(TAG, "Error converting byte[] to String (UTF-8) for key '" + key + "': " + e.getMessage());
            }
            return null; // 转换失败
        }

        Log.w(TAG, "Intent extra '" + key + "' is of unexpected type: " + extraData.getClass().getName() + ". Value: " + extraData.toString());
        return null;
    }
}
