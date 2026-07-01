package jp.ac.ncc.yokoyama.smartdrive;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TrafficEventHandler trafficEventHandler;

    private TextView scoreTextView;
    private TrafficLogAdapter logAdapter;
    private RecyclerView recyclerViewLog;

    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleConnectionService.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BleConnectionService.EXTRA_STATE, BleConnectionService.STATE_DISCONNECTED);
                if (state == BleConnectionService.STATE_CONNECTED) {
                    resetScore();
                } else if (state == BleConnectionService.STATE_DISCONNECTED) {
                    Toast.makeText(MainActivity.this, "Bluetoothが切断されました", Toast.LENGTH_SHORT).show();
                }
            } else if (BleConnectionService.ACTION_DATA_RECEIVED.equals(action)) {
                String jsonData = intent.getStringExtra(BleConnectionService.EXTRA_DATA);
                if (jsonData != null) {
                    parseAndProcessJson(jsonData);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        scoreTextView = findViewById(R.id.text_score);

        // RecyclerView の初期設定
        recyclerViewLog = findViewById(R.id.recycler_view_log);
        logAdapter = new TrafficLogAdapter();
        recyclerViewLog.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewLog.setAdapter(logAdapter);

        // 設定画面への遷移ボタン
        findViewById(R.id.button_settings).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        checkBluetoothPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleConnectionService.ACTION_STATE_CHANGED);
        filter.addAction(BleConnectionService.ACTION_DATA_RECEIVED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bleReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(bleReceiver);
        } catch (IllegalArgumentException e) {
            Log.e("MainActivity", "Receiver not registered", e);
        }
    }

    private void checkBluetoothPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> remainingPermissions = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                remainingPermissions.add(perm);
            }
        }

        if (!remainingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    remainingPermissions.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else {
            setupBluetooth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                setupBluetooth();
            } else {
                Toast.makeText(this, "Bluetoothおよび位置情報の権限が必要です", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void resetScore() {
        if (trafficEventHandler == null) {
            trafficEventHandler = new TrafficEventHandler(this);
        }
        trafficEventHandler.driving_score = 100;
        runOnUiThread(() -> {
            if (scoreTextView != null) {
                scoreTextView.setText(String.valueOf(trafficEventHandler.driving_score));
                scoreTextView.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.score_green));
            }
            Toast.makeText(MainActivity.this, "Bluetooth接続完了: スコアをリセットしました", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupBluetooth() {
        trafficEventHandler = new TrafficEventHandler(this);

        // Start Foreground Service for BLE background communication
        Intent serviceIntent = new Intent(this, BleConnectionService.class);
        serviceIntent.putExtra("device_name", "omnibus185");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void parseAndProcessJson(String jsonString) {
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            float x = (float) jsonObject.optDouble("x", 0.0);
            float y = (float) jsonObject.optDouble("y", 0.0);
            float z = (float) jsonObject.optDouble("z", 0.0);

            if (trafficEventHandler != null) {
                trafficEventHandler.currentX = x;
                trafficEventHandler.currentY = y;
                trafficEventHandler.currentZ = z;
            }

            Log.d("MainActivity", "BLE Data parsed - X: " + x + ", Y: " + y + ", Z: " + z);

            // イベントキーが存在し、かつ true の場合のみ通知
            if (jsonObject.optBoolean("s_braked", false)) {
                if (trafficEventHandler != null) trafficEventHandler.handleDangerousEvent("s_braked");
            }
            if (jsonObject.optBoolean("s_accelerated", false)) {
                if (trafficEventHandler != null) trafficEventHandler.handleDangerousEvent("s_accelerated");
            }
            if (jsonObject.optBoolean("s_steered", false)) {
                if (trafficEventHandler != null) trafficEventHandler.handleDangerousEvent("s_steered");
            }
            if (jsonObject.optBoolean("waved", false)) {
                if (trafficEventHandler != null) trafficEventHandler.handleDangerousEvent("waved");
            }
            if (jsonObject.optBoolean("unstable_speed", false)) {
                if (trafficEventHandler != null) trafficEventHandler.handleDangerousEvent("unstable_speed");
            }
        } catch (JSONException e) {
            Log.e("MainActivity", "Error parsing BLE JSON: " + jsonString, e);
        }
    }

    public void updateScore(int score) {
        TextView scoreTV = findViewById(R.id.text_score);
        if (scoreTV != null) {
            scoreTV.setText(String.valueOf(score));
        }
    }

    // イベント発生時にログをリストの先頭に追加する窓口
    public void addLogToUI(TrafficLog log) {
        runOnUiThread(() -> {
            if (logAdapter != null) {
                logAdapter.addLog(log);
                recyclerViewLog.scrollToPosition(0);
            }
        });
    }

    public void changeScoreColor(int color) {
        TextView scoreTV = findViewById(R.id.text_score);
        if (scoreTV == null) return;
        switch (color) {
            case 0:
                scoreTV.setTextColor(ContextCompat.getColor(this, R.color.score_green));
                break;
            case 1:
                scoreTV.setTextColor(ContextCompat.getColor(this, R.color.score_yellow));
                break;
            case 2:
                scoreTV.setTextColor(ContextCompat.getColor(this, R.color.score_red));
                break;
        }
    }
}