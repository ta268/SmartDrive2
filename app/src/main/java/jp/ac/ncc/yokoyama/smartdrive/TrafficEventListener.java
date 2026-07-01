package jp.ac.ncc.yokoyama.smartdrive;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.UUID;

public class TrafficEventListener {
    private static final String TAG = "TrafficEventListener";
    // Standard SPP UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    
    // エミュレータテスト用
    private java.net.ServerSocket tcpServerSocket;
    private java.net.Socket tcpClientSocket;

    private InputStream inputStream;
    private Thread receiveThread;
    private volatile boolean isRunning = false;

    private OnDataReceivedListener listener;

    public float currentX = 0f;
    public float currentY = 0f;
    public float currentZ = 0f;

    public interface OnDataReceivedListener {
        void onConnected();
        void onDataReceived(float x, float y, float z);
        void onDangerousEvent(String eventType);
        void onError(String message);
    }

    public TrafficEventListener(OnDataReceivedListener listener) {
        this.listener = listener;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @SuppressLint("MissingPermission")
    public void connectToDevice(String targetDeviceName) {
        if (bluetoothAdapter == null) {
            if (listener != null) listener.onError("Bluetooth is not supported on this device");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            if (listener != null) listener.onError("Bluetooth is not enabled");
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        BluetoothDevice targetDevice = null;

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals(targetDeviceName)) {
                    targetDevice = device;
                    break;
                }
            }
        }

        if (targetDevice == null) {
            if (listener != null) listener.onError("Target device not found in paired devices");
            return;
        }

        try {
            bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            bluetoothSocket.connect();
            inputStream = bluetoothSocket.getInputStream();
            if (listener != null) {
                listener.onConnected();
            }
            startReceiving();
        } catch (IOException e) {
            Log.e(TAG, "Connection failed", e);
            if (listener != null) listener.onError("Failed to connect: " + e.getMessage());
            disconnect();
        }
    }

    // エミュレータテスト用: TCPサーバーを起動してPCからの接続を待つ
    public void startTcpTestServer(int port) {
        new Thread(() -> {
            try {
                tcpServerSocket = new java.net.ServerSocket(port);
                while (true) {
                    Log.d(TAG, "TCP Server started on port " + port + ". Waiting for connection...");
                    java.net.Socket newClient = tcpServerSocket.accept(); // クライアント(PC)の接続待ち
                    Log.d(TAG, "TCP Client connected!");
                    
                    // 前の接続が残っていれば閉じる
                    if (tcpClientSocket != null && !tcpClientSocket.isClosed()) {
                        try { tcpClientSocket.close(); } catch (Exception ignored) {}
                    }
                    tcpClientSocket = newClient;
                    inputStream = tcpClientSocket.getInputStream();
                    
                    if (listener != null) {
                        listener.onConnected();
                    }
                    
                    startReceiving();
                }
            } catch (IOException e) {
                Log.e(TAG, "TCP Server error", e);
                if (listener != null) listener.onError("TCP Server Error: " + e.getMessage());
            }
        }).start();
    }

    private void startReceiving() {
        isRunning = true;
        receiveThread = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            while (isRunning) {
                try {
                    String jsonString = reader.readLine();
                    if (jsonString != null) {
                        parseJsonData(jsonString);
                    } else {
                        // クライアント側から切断された
                        Log.d(TAG, "Stream ended. Client disconnected.");
                        break;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading from stream", e);
                    // Bluetooth通信の時だけエラートーストを出す
                    if (listener != null && bluetoothSocket != null) {
                        listener.onError("Connection lost");
                        disconnect();
                    }
                    break;
                }
            }
        });
        receiveThread.start();
    }

    private void parseJsonData(String jsonString) {
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            currentX = (float) jsonObject.optDouble("x", 0.0);
            currentY = (float) jsonObject.optDouble("y", 0.0);
            currentZ = (float) jsonObject.optDouble("z", 0.0);

            if (listener != null) {
                listener.onDataReceived(currentX, currentY, currentZ);
                
                // イベントキーが存在し、かつ true の場合のみ通知
                if (jsonObject.optBoolean("s_braked", false)) listener.onDangerousEvent("s_braked");
                if (jsonObject.optBoolean("s_accelerated", false)) listener.onDangerousEvent("s_accelerated");
                if (jsonObject.optBoolean("s_steered", false)) listener.onDangerousEvent("s_steered");
                if (jsonObject.optBoolean("waved", false)) listener.onDangerousEvent("waved");
                if (jsonObject.optBoolean("unstable_speed", false)) listener.onDangerousEvent("unstable_speed");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON: " + jsonString, e);
        }
    }

    public void disconnect() {
        isRunning = false;
        try {
            if (inputStream != null) inputStream.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
            if (tcpClientSocket != null) tcpClientSocket.close();
            if (tcpServerSocket != null) tcpServerSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }
    }
}
