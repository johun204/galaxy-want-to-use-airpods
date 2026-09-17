package com.dosse.airpods.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.AacpManager;
import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.pods.PodsSnapshot;

/**
 * AirPods 하드웨어 설정 (AACP L2CAP). 기기가 실제로 통보한 항목만 노출한다.
 * 값은 명령 전송 후 기기 echo 로 확정된다.
 */
public class AirPodsSettingsActivity extends AppCompatActivity {

    // 각 스위치 ↔ ControlCommand 식별자
    private static final int[][] TOGGLES = {
            {R.id.s_ear_detection, AacpManager.ID_EAR_DETECTION},
            {R.id.s_conversation, AacpManager.ID_CONVERSATION_DETECT},
            {R.id.s_adaptive_volume, AacpManager.ID_ADAPTIVE_VOLUME},
            {R.id.s_one_bud, AacpManager.ID_ONE_BUD_ANC},
            {R.id.s_allow_off, AacpManager.ID_ALLOW_OFF_OPTION},
            {R.id.s_optimized_charge, AacpManager.ID_DYNAMIC_END_OF_CHARGE},
            {R.id.s_auto_connect, AacpManager.ID_AUTOMATIC_CONNECTION},
            {R.id.s_sleep_detect, AacpManager.ID_SLEEP_DETECTION},
            {R.id.s_voice_trigger, AacpManager.ID_VOICE_TRIGGER},
            {R.id.s_in_case_tone, AacpManager.ID_IN_CASE_TONE},
            {R.id.s_volume_swipe, AacpManager.ID_VOLUME_SWIPE},
    };

    // 길게 눌러 전환할 모드 비트 (추정: ANC=1, 주변음=2, 적응형=4, 끄기=8)
    private static final int[][] LP_BITS = {
            {R.id.s_lp_anc, 0x01}, {R.id.s_lp_trans, 0x02},
            {R.id.s_lp_adaptive, 0x04}, {R.id.s_lp_off, 0x08},
    };

    private boolean mUpdatingUi = false;

    private final BroadcastReceiver mRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_airpods_settings);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        Button save = findViewById(R.id.s_name_save);
        save.setOnClickListener(v -> {
            String name = ((EditText) findViewById(R.id.s_name)).getText().toString().trim();
            if (!name.isEmpty()) {
                send(new Intent(this, PodsService.class).setAction(PodsService.ACTION_RENAME).putExtra("name", name));
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            }
        });

        for (int[] t : TOGGLES) {
            Switch sw = findViewById(t[0]);
            final int id = t[1];
            sw.setOnCheckedChangeListener((b, checked) -> {
                if (!mUpdatingUi)
                    sendControl(id, checked ? 1 : 0, true);
            });
        }

        SeekBar strength = findViewById(R.id.s_strength);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                sendControl(AacpManager.ID_AUTO_ANC_STRENGTH, s.getProgress(), false);
            }
        });

        CompoundButton.OnCheckedChangeListener lpListener = (b, checked) -> {
            if (mUpdatingUi)
                return;
            int value = 0, count = 0;
            for (int[] lp : LP_BITS) {
                if (((CheckBox) findViewById(lp[0])).isChecked()) {
                    value |= lp[1];
                    count++;
                }
            }
            if (count < 2) { // 최소 2개 유지
                Toast.makeText(this, R.string.long_press_min, Toast.LENGTH_SHORT).show();
                render();
                return;
            }
            sendControl(AacpManager.ID_LISTENING_MODE_CONFIGS, value, false);
        };
        for (int[] lp : LP_BITS)
            ((CheckBox) findViewById(lp[0])).setOnCheckedChangeListener(lpListener);

        wirePressSpeed(R.id.s_press_speed, AacpManager.ID_DOUBLE_CLICK_INTERVAL,
                new int[]{R.id.s_ps_0, R.id.s_ps_1, R.id.s_ps_2});
        wirePressSpeed(R.id.s_hold_dur, AacpManager.ID_CLICK_HOLD_INTERVAL,
                new int[]{R.id.s_hd_0, R.id.s_hd_1, R.id.s_hd_2});

        findViewById(R.id.s_find).setOnClickListener(v ->
                startActivity(new Intent(this, FindMyActivity.class)));
    }

    private void wirePressSpeed(int groupId, int controlId, int[] btnIds) {
        ((android.widget.RadioGroup) findViewById(groupId)).setOnCheckedChangeListener((g, checkedId) -> {
            if (mUpdatingUi)
                return;
            for (int i = 0; i < btnIds.length; i++)
                if (btnIds[i] == checkedId)
                    sendControl(controlId, i, false);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.dosse.airpods.pods.PodsService.setAppForeground(this, true);
        ContextCompat.registerReceiver(this, mRx, new IntentFilter(PodsSnapshot.ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        com.dosse.airpods.pods.PodsService.setAppForeground(this, false);
        try {
            unregisterReceiver(mRx);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void render() {
        PodsSnapshot s = PodsSnapshot.last;
        boolean on = s.aacpConnected;

        findViewById(R.id.s_disconnected).setVisibility(on ? View.GONE : View.VISIBLE);
        int base = on ? View.VISIBLE : View.GONE;
        findViewById(R.id.s_name_label).setVisibility(base);
        findViewById(R.id.s_name_row).setVisibility(base);

        findViewById(R.id.s_find).setVisibility(base);

        if (!on) {
            for (int[] t : TOGGLES)
                findViewById(t[0]).setVisibility(View.GONE);
            findViewById(R.id.s_strength_row).setVisibility(View.GONE);
            findViewById(R.id.s_lp_row).setVisibility(View.GONE);
            findViewById(R.id.s_press_row).setVisibility(View.GONE);
            return;
        }

        EditText name = findViewById(R.id.s_name);
        if (!name.hasFocus())
            name.setText(s.deviceName);

        mUpdatingUi = true;
        for (int[] t : TOGGLES) {
            Switch sw = findViewById(t[0]);
            Integer v = s.control(t[1]);
            sw.setVisibility(v != null ? View.VISIBLE : View.GONE);
            if (v != null)
                sw.setChecked(v == 1);
        }

        Integer strength = s.control(AacpManager.ID_AUTO_ANC_STRENGTH);
        findViewById(R.id.s_strength_row).setVisibility(strength != null ? View.VISIBLE : View.GONE);
        if (strength != null)
            ((SeekBar) findViewById(R.id.s_strength)).setProgress(Math.max(0, Math.min(100, strength)));

        Integer lp = s.control(AacpManager.ID_LISTENING_MODE_CONFIGS);
        findViewById(R.id.s_lp_row).setVisibility(lp != null ? View.VISIBLE : View.GONE);
        if (lp != null) {
            for (int[] b : LP_BITS)
                ((CheckBox) findViewById(b[0])).setChecked((lp & b[1]) != 0);
        }

        Integer dci = s.control(AacpManager.ID_DOUBLE_CLICK_INTERVAL);
        Integer chi = s.control(AacpManager.ID_CLICK_HOLD_INTERVAL);
        boolean press = dci != null || chi != null;
        findViewById(R.id.s_press_row).setVisibility(press ? View.VISIBLE : View.GONE);
        if (dci != null)
            checkRadio(new int[]{R.id.s_ps_0, R.id.s_ps_1, R.id.s_ps_2}, dci);
        if (chi != null)
            checkRadio(new int[]{R.id.s_hd_0, R.id.s_hd_1, R.id.s_hd_2}, chi);

        mUpdatingUi = false;
    }

    private void checkRadio(int[] btnIds, int idx) {
        if (idx >= 0 && idx < btnIds.length)
            ((android.widget.RadioButton) findViewById(btnIds[idx])).setChecked(true);
    }

    private void sendControl(int id, int value, boolean bool) {
        send(new Intent(this, PodsService.class).setAction(PodsService.ACTION_SET_CONTROL)
                .putExtra("id", id).putExtra("value", value).putExtra("bool", bool));
    }

    private void send(Intent i) {
        try {
            startService(i);
        } catch (Throwable ignored) {
        }
    }
}
