package jp.ac.ncc.yokoyama.smartdrive;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TrafficEventHandler {

    private Context context;
    private Handler mainHandler;

    public int driving_score;
    // 最後に受信したセンサー値（TrafficEventListenerから随時更新される想定）
    public float currentX, currentY, currentZ;

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss", Locale.JAPAN);

    public TrafficEventHandler(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // イベント英語名 -> 日本語変換
    private String toJapanese(String eventType) {
        switch (eventType) {
            case "s_braked":       return "急ブレーキ";
            case "s_accelerated":  return "急発進";
            case "s_steered":      return "急ハンドル";
            case "waved":          return "ふらつき運転";
            case "unstable_speed": return "速度不安定";
            default:               return eventType;
        }
    }

    public void handleDangerousEvent(String eventType) {
        final int GREEN = 0;
        final int YELLOW = 1;
        final int RED = 2;

        // タイムスタンプはバックグラウンドスレッドで取得（正確な検知時刻）
        final String timestamp = DATE_FORMAT.format(new Date());
        final String eventNameJa = toJapanese(eventType);
        final float snapX = currentX;
        final float snapY = currentY;
        final float snapZ = currentZ;

        mainHandler.post(() -> {
            Log.w("TrafficEventHandler", "Dangerous Event Detected: " + eventType);
            Toast.makeText(context, eventNameJa + "を検知しました", Toast.LENGTH_SHORT).show();

            // スコア減点
            switch (eventType) {
                case "s_braked":       driving_score -= 6; break;
                case "s_accelerated":  driving_score -= 9; break;
                case "s_steered":      driving_score -= 7; break;
                case "waved":          driving_score -= 8; break;
                case "unstable_speed": driving_score -= 5; break;
            }
            if (driving_score < 0) driving_score = 0;

            ((MainActivity) context).updateScore(driving_score);

            if (driving_score > 95) {
                ((MainActivity) context).changeScoreColor(GREEN);
            } else if (driving_score > 85) {
                ((MainActivity) context).changeScoreColor(YELLOW);
            } else {
                ((MainActivity) context).changeScoreColor(RED);
            }

            // ログをメイン画面のリストに追加
            TrafficLog log = new TrafficLog(timestamp, eventNameJa, snapX, snapY, snapZ, 0.0, 0.0);
            ((MainActivity) context).addLogToUI(log);
        });
    }
}
