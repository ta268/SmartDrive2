package jp.ac.ncc.yokoyama.smartdrive;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.location.Address;
import android.location.Geocoder;
import java.io.IOException;
import java.util.List;
import androidx.recyclerview.widget.RecyclerView;

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
        this.driving_score = 100; // 初期スコアは100点
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
        handleDangerousEvent(eventType, 0.0, 0.0);
    }

    public void handleDangerousEvent(String eventType, final double latitude, final double longitude) {
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
            String initialAddress = (latitude == 0.0 && longitude == 0.0) ? "位置情報なし" : "住所を取得中...";
            final TrafficLog log = new TrafficLog(timestamp, eventNameJa, snapX, snapY, snapZ, latitude, longitude, initialAddress);
            ((MainActivity) context).addLogToUI(log);

            // 位置情報が利用可能な場合、非同期で住所を取得
            if (latitude != 0.0 || longitude != 0.0) {
                new Thread(() -> {
                    Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                    try {
                        List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                        if (addresses != null && !addresses.isEmpty()) {
                            Address addressObj = addresses.get(0);
                            String fullAddress = addressObj.getAddressLine(0);
                            
                            // 日本国内の住所表記で不要な国名表記等をトリミングしてスッキリさせる
                            if (fullAddress != null) {
                                fullAddress = fullAddress.replaceFirst("^日本、(〒\\d{3}-\\d{4}\\s+)?", "");
                            } else {
                                fullAddress = "住所不明";
                            }
                            
                            final String resolvedAddress = fullAddress;
                            mainHandler.post(() -> {
                                log.setAddress(resolvedAddress);
                                if (context instanceof MainActivity) {
                                    RecyclerView rv = ((MainActivity) context).findViewById(R.id.recycler_view_log);
                                    if (rv != null && rv.getAdapter() != null) {
                                        rv.getAdapter().notifyDataSetChanged();
                                    }
                                }
                            });
                        } else {
                            mainHandler.post(() -> log.setAddress("住所不明"));
                        }
                    } catch (IOException e) {
                        Log.e("TrafficEventHandler", "Failed to geocode location", e);
                        mainHandler.post(() -> log.setAddress("住所の取得に失敗しました"));
                    }
                }).start();
            }
        });
    }
}
