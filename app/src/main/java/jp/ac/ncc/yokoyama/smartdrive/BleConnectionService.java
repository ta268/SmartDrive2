package jp.ac.ncc.yokoyama.smartdrive;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import androidx.core.content.ContextCompat;

@SuppressLint("MissingPermission")
public class BleConnectionService extends Service {
    private static final String TAG = "BleConnectionService";

    // Broadcast actions
    public static final String ACTION_STATE_CHANGED = "jp.ac.ncc.yokoyama.smartdrive.ACTION_STATE_CHANGED";
    public static final String ACTION_DATA_RECEIVED = "jp.ac.ncc.yokoyama.smartdrive.ACTION_DATA_RECEIVED";
    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_DATA = "extra_data";

    // Connection states
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    // UUID Definitions (Custom Service and Characteristic)
    public static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID CHAR_UUID    = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    // RX Characteristic: Android → Raspberry Pi (書き込み用)
    public static final UUID RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL_ID = "ble_connection_channel";
    private static final int NOTIFICATION_ID = 1;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private String targetDeviceName = "omnibus185";
    private int connectionState = STATE_DISCONNECTED;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private int retryCount = 0;

    private LocationManager locationManager;
    private Location lastLocation;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
            Log.d(TAG, "Location updated: " + location.getLatitude() + ", " + location.getLongitude());
        }

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    private long lastDataReceivedTime = 0;
    private final Runnable zombieWatchdog = new Runnable() {
        @Override
        public void run() {
            if (connectionState == STATE_CONNECTED) {
                long elapsed = System.currentTimeMillis() - lastDataReceivedTime;
                // 5分 (300000ms) 以上通信がない場合はゾンビ状態とみなす
                if (elapsed > 5 * 60 * 1000) {
                    Log.w(TAG, "Watchdog detected inactive connection (zombie). Reconnecting...");
                    reconnect();
                }
            }
            handler.postDelayed(this, 30000); // 30秒ごとにチェック
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, getNotification("BLEデバイスの探索中..."));

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        } else {
            Log.e(TAG, "Bluetooth not supported");
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startLocationUpdates();

        // ゾンビ監視タイマーの開始
        handler.postDelayed(zombieWatchdog, 30000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (intent != null && intent.hasExtra("device_name")) {
            targetDeviceName = intent.getStringExtra("device_name");
        }

        if (connectionState == STATE_DISCONNECTED && !isScanning) {
            retryCount = 0;
            startScanAndConnect();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        handler.removeCallbacksAndMessages(null);
        stopScanningInternal();
        disconnectGatt();
        stopLocationUpdates();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Bind is not used in this service
    }

    private void updateState(int newState) {
        connectionState = newState;
        String statusText;
        switch (newState) {
            case STATE_CONNECTING:
                statusText = "接続試行中: " + targetDeviceName;
                break;
            case STATE_CONNECTED:
                statusText = "接続完了: " + targetDeviceName;
                break;
            case STATE_DISCONNECTED:
            default:
                statusText = "切断状態: 再接続待機中";
                break;
        }

        // フォアグラウンド通知の表示更新
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, getNotification(statusText));
        }

        // Activity向けにブロードキャスト送信
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_STATE, newState);
        sendBroadcast(intent);
    }

    private void startScanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is disabled or unsupported");
            return;
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Unable to get BluetoothLeScanner");
            return;
        }

        updateState(STATE_CONNECTING);
        isScanning = true;
        Log.d(TAG, "Starting BLE Scan for device: " + targetDeviceName);
        bluetoothLeScanner.startScan(scanCallback);

        // 15秒間見つからなかったらタイムアウトして再試行
        handler.postDelayed(() -> {
            if (isScanning && connectionState == STATE_CONNECTING) {
                Log.w(TAG, "Scan timeout. Retrying...");
                stopScanningInternal();
                scheduleReconnect();
            }
        }, 15000);
    }

    private void stopScanningInternal() {
        if (isScanning && bluetoothLeScanner != null) {
            Log.d(TAG, "Stopping BLE Scan");
            bluetoothLeScanner.stopScan(scanCallback);
            isScanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) {
                name = device.getName();
            }
            if (name != null && name.equals(targetDeviceName)) {
                Log.d(TAG, "Found target device: " + name + " [" + device.getAddress() + "]");
                stopScanningInternal();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error code: " + errorCode);
            stopScanningInternal();
            scheduleReconnect();
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        disconnectGatt();
        Log.d(TAG, "Connecting to GATT on " + device.getName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private void disconnectGatt() {
        if (bluetoothGatt != null) {
            Log.d(TAG, "Disconnecting and closing GATT");
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    private void reconnect() {
        disconnectGatt();
        stopScanningInternal();
        startScanAndConnect();
    }

    private void scheduleReconnect() {
        updateState(STATE_DISCONNECTED);

        // 指数バックオフ: 2s, 4s, 8s, 16s, 30s (max)
        long delay = (long) Math.min(30000, 2000 * Math.pow(2, retryCount));
        retryCount++;

        Log.d(TAG, "Scheduling reconnect in " + delay + " ms (retry count: " + retryCount + ")");
        handler.postDelayed(() -> {
            if (connectionState == STATE_DISCONNECTED) {
                startScanAndConnect();
            }
        }, delay);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Connected to " + gatt.getDevice().getName());
                retryCount = 0;
                lastDataReceivedTime = System.currentTimeMillis();

                // MTU 拡張を要求 (512バイト)
                Log.d(TAG, "Requesting MTU 512");
                gatt.requestMtu(512);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT Disconnected");
                disconnectGatt();
                scheduleReconnect();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "MTU changed to " + mtu + ", status: " + status);
            // MTU設定完了後にサービス探索を実行
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered successfully");
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_UUID);
                    if (characteristic != null) {
                        enableCharacteristicNotification(gatt, characteristic);
                    } else {
                        Log.e(TAG, "Characteristic not found: " + CHAR_UUID);
                    }
                } else {
                    Log.e(TAG, "Service not found: " + SERVICE_UUID);
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Android SDK compatibility
            byte[] value = characteristic.getValue();
            if (value != null) {
                handleReceivedBytes(value);
            }
        }

        // Required on Android 13+ (API 33+)
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (value != null) {
                handleReceivedBytes(value);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            // CCCD への書き込み完了 = Notify 有効化が確定した瞬間
            // ラズパイに「受信準備完了（READY）」を送信する
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD write success. Sending READY signal to Raspberry Pi.");
                sendReadySignal(gatt);
            } else {
                Log.e(TAG, "CCCD write failed, status: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (RX_CHAR_UUID.equals(characteristic.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "READY signal sent successfully.");
                    // READY 送信完了をもって「完全に準備完了（CONNECTED）」とする
                    updateState(STATE_CONNECTED);
                } else {
                    Log.e(TAG, "Failed to send READY signal, status: " + status);
                }
            }
        }
    };

    private void handleReceivedBytes(byte[] value) {
        lastDataReceivedTime = System.currentTimeMillis();
        String jsonStr = new String(value, StandardCharsets.UTF_8);
        Log.d(TAG, "Data received: " + jsonStr);

        // JSON受信をブロードキャストで通知
        Intent intent = new Intent(ACTION_DATA_RECEIVED);
        intent.putExtra(EXTRA_DATA, jsonStr);
        if (lastLocation != null) {
            intent.putExtra("latitude", lastLocation.getLatitude());
            intent.putExtra("longitude", lastLocation.getLongitude());
        } else {
            intent.putExtra("latitude", 0.0);
            intent.putExtra("longitude", 0.0);
        }
        sendBroadcast(intent);
    }

    private void startLocationUpdates() {
        if (locationManager == null) return;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                // Get last known location first as a fallback
                Location gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (gpsLoc != null && netLoc != null) {
                    lastLocation = gpsLoc.getTime() > netLoc.getTime() ? gpsLoc : netLoc;
                } else {
                    lastLocation = gpsLoc != null ? gpsLoc : netLoc;
                }

                // Request updates: every 5 seconds or 5 meters
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            5000,
                            5f,
                            locationListener
                    );
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            5000,
                            5f,
                            locationListener
                    );
                }
                Log.d(TAG, "Location updates started");
            } else {
                Log.w(TAG, "Location permission not granted for service");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException starting location updates", e);
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
                Log.d(TAG, "Location updates stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping location updates", e);
            }
        }
    }

    private void enableCharacteristicNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        Log.d(TAG, "Enabling notifications on " + characteristic.getUuid());
        gatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            // 書き込み完了は onDescriptorWrite で受け取り、そこで READY 信号を送信する
            gatt.writeDescriptor(descriptor);
        } else {
            Log.e(TAG, "CCCD Descriptor not found for characteristic");
        }
    }

    /**
     * ラズパイの RX キャラクタリスティック（6E400002）に "READY" を書き込む。
     * ラズパイ側はこの信号を受け取ってから蓄積データの一括送信を開始する。
     */
    private void sendReadySignal(BluetoothGatt gatt) {
        if (gatt == null) return;

        // まず現在探索済みのサービスから RX キャラクタリスティックを探す
        BluetoothGattCharacteristic rxChar = null;
        for (BluetoothGattService service : gatt.getServices()) {
            rxChar = service.getCharacteristic(RX_CHAR_UUID);
            if (rxChar != null) break;
        }

        if (rxChar == null) {
            Log.w(TAG, "RX Characteristic (" + RX_CHAR_UUID + ") not found. Skipping READY signal.");
            // RX が存在しない場合でも接続完了とみなす（後方互換性のため）
            updateState(STATE_CONNECTED);
            return;
        }

        byte[] readyBytes = "READY".getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, readyBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            rxChar.setValue(readyBytes);
            rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            gatt.writeCharacteristic(rxChar);
        }
        Log.d(TAG, "READY signal written to RX characteristic.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "BLE Connection Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background Bluetooth connectivity for SmartDrive");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification getNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SmartDrive BLE 通信")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
    }
}
