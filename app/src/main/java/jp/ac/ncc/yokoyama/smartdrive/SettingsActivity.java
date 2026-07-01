package jp.ac.ncc.yokoyama.smartdrive;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.button_back_from_settings).setOnClickListener(v -> {
            finish();
        });
    }
}
