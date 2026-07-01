package jp.ac.ncc.yokoyama.smartdrive;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class LogDetailActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_detail);

        TrafficLog log = (TrafficLog) getIntent().getSerializableExtra("log_data");
        if (log != null) {
            TextView timeText = findViewById(R.id.text_detail_time);
            TextView eventText = findViewById(R.id.text_detail_event);
            TextView gText = findViewById(R.id.text_detail_g);
            TextView locationText = findViewById(R.id.text_detail_location);

            timeText.setText(log.getTimestamp());
            eventText.setText(log.getEventNameJa());
            gText.setText(String.format("X: %.2f  Y: %.2f  Z: %.2f", log.getX(), log.getY(), log.getZ()));
            locationText.setText(String.format("緯度: %f / 経度: %f", log.getLatitude(), log.getLongitude()));
        }

        // 戻るボタンの処理
        findViewById(R.id.button_back).setOnClickListener(v -> {
            finish(); // 現在のActivityを終了してメイン画面に戻る
        });
    }
}
