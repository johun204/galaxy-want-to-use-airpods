package com.dosse.airpods.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.AacpManager;
import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.pods.PodsSnapshot;
import com.dosse.airpods.utils.BatteryRing;
import com.dosse.airpods.utils.MIUIWarning;
import com.dosse.airpods.utils.PermissionUtils;
import com.dosse.airpods.utils.SharedPreferencesUtils;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private boolean mUpdatingUi = false;

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        wireAacpControls();

        // Check if Bluetooth LE is available on this device. If not, show an error
        BluetoothAdapter btAdapter = ((BluetoothManager)Objects.requireNonNull(getSystemService(Context.BLUETOOTH_SERVICE))).getAdapter();
        if (btAdapter == null || (btAdapter.isEnabled() && btAdapter.getBluetoothLeScanner() == null) || (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))) {
            startActivity(new Intent(MainActivity.this, NoBTActivity.class));
            finish();

            return;
        }

        // 권한이 없어도 실행은 되게 함. 없으면 상단 경고 배너로 안내.
        View warn = findViewById(R.id.perm_warning);
        if (warn != null)
            warn.setOnClickListener(v -> startActivity(new Intent(this, IntroActivity.class)));

        startServiceIfPodsConnected(btAdapter); // 서비스는 가진 권한으로 최선을 다함
        //Warn MIUI users that their rom has known issues
        MIUIWarning.show(this);
    }

    // AirPods가 실제로 연결돼 있을 때만 서비스를 시작한다 (연결 안 됐으면 알림도 안 뜨게).
    @SuppressWarnings("MissingPermission")
    private void startServiceIfPodsConnected(BluetoothAdapter btAdapter) {
        if (btAdapter == null || !btAdapter.isEnabled())
            return;
        try {
            btAdapter.getProfileProxy(getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    boolean found = false;
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) {
                            if (com.dosse.airpods.pods.PodsService.isPods(d)) {
                                found = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    btAdapter.closeProfileProxy(profile, proxy);
                    if (found)
                        ContextCompat.startForegroundService(getApplicationContext(),
                                new Intent(getApplicationContext(), PodsService.class)
                                        .setAction(PodsService.ACTION_REFRESH));
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.HEADSET);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, mStatusReceiver,
                new IntentFilter(PodsSnapshot.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

        PodsService.setAppForeground(this, true); // 앱 열려 있는 동안 연속 스캔

        View warn = findViewById(R.id.perm_warning);
        if (warn != null)
            warn.setVisibility(PermissionUtils.checkAllPermissions(this) ? View.GONE : View.VISIBLE);

        render(); // 브로드캐스트를 기다리지 않고 마지막 스냅샷을 즉시 반영
        maybeAskOverlay();
    }

    // 아일랜드가 켜져 있는데 '다른 앱 위에 표시' 권한이 없으면 한 번만 물어본다
    private void maybeAskOverlay() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (!SharedPreferencesUtils.isIslandEnabled(this) || IslandOverlay.hasPermission(this)
                || sp.getBoolean("island_asked", false))
            return;
        sp.edit().putBoolean("island_asked", true).apply();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.pref_island)
                .setMessage(R.string.pref_island_desc)
                .setPositiveButton(R.string.pref_island_permission, (d, w) -> startActivity(
                        new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // 노이즈 컨트롤 / 귀 감지 버튼 클릭 → 서비스에 명령 전달
    private void wireAacpControls() {
        View panel = findViewById(R.id.m_aacp_panel);
        if (panel == null)
            return;
        setAncClick(R.id.m_anc_off, AacpManager.NOISE_OFF);
        setAncClick(R.id.m_anc_anc, AacpManager.NOISE_ANC);
        setAncClick(R.id.m_anc_trans, AacpManager.NOISE_TRANSPARENCY);
        setAncClick(R.id.m_anc_adaptive, AacpManager.NOISE_ADAPTIVE);

        Switch ear = findViewById(R.id.m_ear_detection);
        ear.setOnCheckedChangeListener((b, checked) -> {
            if (mUpdatingUi)
                return;
            startService(new Intent(this, PodsService.class)
                    .setAction(PodsService.ACTION_SET_CONTROL)
                    .putExtra("id", AacpManager.ID_EAR_DETECTION)
                    .putExtra("value", checked ? 1 : 0)
                    .putExtra("bool", true));
        });

        findViewById(R.id.m_aacp_settings).setOnClickListener(v ->
                startActivity(new Intent(this, AirPodsSettingsActivity.class)));
    }

    private void setAncClick(int viewId, int mode) {
        findViewById(viewId).setOnClickListener(v -> startService(
                new Intent(this, PodsService.class)
                        .setAction(PodsService.ACTION_SET_ANC)
                        .putExtra("mode", mode)));
    }

    @Override
    protected void onStop() {
        super.onStop();
        PodsService.setAppForeground(this, false);
        try {
            unregisterReceiver(mStatusReceiver);
        } catch (Throwable ignored) {
        }
    }

    // 메인 화면 배터리 패널을 현재 스냅샷으로 갱신 (알림/위젯과 동일한 데이터 소스)
    private void render() {
        TextView left = findViewById(R.id.m_left_text);
        if (left == null) // 가로 레이아웃엔 패널이 없음
            return;

        TextView right = findViewById(R.id.m_right_text);
        TextView caseT = findViewById(R.id.m_case_text);
        ImageView leftImg = findViewById(R.id.m_left_img);
        ImageView rightImg = findViewById(R.id.m_right_img);
        ImageView caseImg = findViewById(R.id.m_case_img);
        View leftCol = findViewById(R.id.m_left_col);
        View rightCol = findViewById(R.id.m_right_col);

        // 링 비트맵이 충전 표시를 포함하므로 별도 오버레이는 숨김
        findViewById(R.id.m_left_chg).setVisibility(View.GONE);
        findViewById(R.id.m_right_chg).setVisibility(View.GONE);
        findViewById(R.id.m_case_chg).setVisibility(View.GONE);

        int px = Math.round(72 * getResources().getDisplayMetrics().density);
        PodsSnapshot s = PodsSnapshot.last;

        TextView updated = findViewById(R.id.m_updated);
        String ut = com.dosse.airpods.widget.PodsWidget.lastUpdatedText(this, s.updatedAt);
        updated.setText(ut.isEmpty() ? "" : getString(R.string.updated_at, ut));

        if (!s.available) {
            leftCol.setVisibility(View.VISIBLE);
            rightCol.setVisibility(View.VISIBLE);
            leftImg.setImageBitmap(BatteryRing.render(this, R.drawable.pod_left_disconnected, -1, false, px));
            rightImg.setImageBitmap(BatteryRing.render(this, R.drawable.pod_right_disconnected, -1, false, px));
            caseImg.setImageBitmap(BatteryRing.render(this, R.drawable.pod_case_disconnected, -1, false, px));
            left.setText(R.string.main_not_connected);
            right.setText("");
            caseT.setText("");
            findViewById(R.id.m_aacp_panel).setVisibility(View.GONE);
            return;
        }

        renderAacp(s);

        leftCol.setVisibility(s.single ? View.GONE : View.VISIBLE);
        rightCol.setVisibility(s.single ? View.GONE : View.VISIBLE);

        if (!s.single) {
            leftImg.setImageBitmap(BatteryRing.render(this, s.leftImg, s.leftPct, s.leftChg, px));
            rightImg.setImageBitmap(BatteryRing.render(this, s.rightImg, s.rightPct, s.rightChg, px));
            left.setText(dash(s.left));
            right.setText(dash(s.right));
        }

        caseImg.setImageBitmap(BatteryRing.render(this, s.caseImg, s.casePct, s.caseChg, px));
        caseT.setText(dash(s.caseB));
    }

    private void renderAacp(PodsSnapshot s) {
        View panel = findViewById(R.id.m_aacp_panel);
        if (!s.aacpConnected) {
            panel.setVisibility(View.GONE);
            return;
        }
        panel.setVisibility(View.VISIBLE);

        // 노이즈 컨트롤: 기기가 지원할 때만 (모드 통보를 한 번이라도 받으면 noiseMode>0)
        int mode = s.noiseMode();
        boolean anc = mode > 0;
        findViewById(R.id.m_noise_label).setVisibility(anc ? View.VISIBLE : View.GONE);
        findViewById(R.id.m_noise_row).setVisibility(anc ? View.VISIBLE : View.GONE);
        if (anc) {
            highlight(R.id.m_anc_off, mode == AacpManager.NOISE_OFF);
            highlight(R.id.m_anc_anc, mode == AacpManager.NOISE_ANC);
            highlight(R.id.m_anc_trans, mode == AacpManager.NOISE_TRANSPARENCY);
            highlight(R.id.m_anc_adaptive, mode == AacpManager.NOISE_ADAPTIVE);
        }

        Switch ear = findViewById(R.id.m_ear_detection);
        Integer earVal = s.control(AacpManager.ID_EAR_DETECTION);
        if (earVal != null) {
            ear.setVisibility(View.VISIBLE);
            mUpdatingUi = true;
            ear.setChecked(earVal == 1);
            mUpdatingUi = false;
        } else {
            ear.setVisibility(View.GONE);
        }
    }

    private void highlight(int viewId, boolean active) {
        Button b = findViewById(viewId);
        b.setAlpha(active ? 1f : 0.4f);
        b.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private static String dash(String s) {
        return (s == null || s.isEmpty()) ? "—" : s;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_ab_settings) {
            startActivity(new Intent(this, SettingsActivity.class)); // Settings icon clicked
            return true;
        }

        return false;
    }
}
