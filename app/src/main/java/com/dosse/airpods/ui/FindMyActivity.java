package com.dosse.airpods.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsSnapshot;

/**
 * 내 에어팟 찾기 — 실제 소리 재생은 Android에서 불가능하므로 BLE 신호 세기로 근접도를 보여준다.
 * RSSI/신선도는 PodsSnapshot 의 정적 필드를 700ms 마다 폴링한다 (브로드캐스트 불필요).
 */
public class FindMyActivity extends AppCompatActivity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mLastPct = -1;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            render();
            mHandler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_find_my);
        setTitle(R.string.find_my_title);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.dosse.airpods.pods.PodsService.setAppForeground(this, true);
        mHandler.post(mTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        com.dosse.airpods.pods.PodsService.setAppForeground(this, false);
        mHandler.removeCallbacks(mTick);
    }

    private void render() {
        TextView pctView = findViewById(R.id.f_pct);
        ProgressBar bar = findViewById(R.id.f_bar);
        TextView dir = findViewById(R.id.f_dir);

        int rssi = PodsSnapshot.last.rssi;
        boolean stale = System.currentTimeMillis() - PodsSnapshot.lastBeaconAt > 5000;

        if (rssi == 0 || stale) {
            pctView.setText("—");
            bar.setProgress(0);
            dir.setText(R.string.find_my_no_signal);
            mLastPct = -1;
            return;
        }

        // rssi -75..-35 → 0..100
        int pct = Math.max(0, Math.min(100, Math.round((rssi + 75) / 40f * 100)));
        pctView.setText(pct + "%");
        bar.setProgress(pct);

        if (mLastPct >= 0) {
            if (pct > mLastPct + 2)
                dir.setText(R.string.find_my_closer);
            else if (pct < mLastPct - 2)
                dir.setText(R.string.find_my_farther);
        }
        mLastPct = pct;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
