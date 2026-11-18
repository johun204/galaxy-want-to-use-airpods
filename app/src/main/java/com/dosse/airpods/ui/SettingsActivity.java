package com.dosse.airpods.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.dosse.airpods.BuildConfig;
import com.dosse.airpods.R;
import com.dosse.airpods.receivers.AirPodsConnectionReceiver;
import com.dosse.airpods.utils.PermissionUtils;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preference_screen, rootKey);

            // 기능별 필요 권한을 요약에 적고, 권한이 없으면 켜지 못하게 막는다
            gate("notif_wear", PERM_NOTIF);
            gate("island_enabled", PERM_A11Y);
            gate("auto_on_connect", PERM_BT);
            gate("aacp_enabled", PERM_BT);
            gate("aacp_battery", PERM_BT);

            getPreference("island_a11y").setOnPreferenceClickListener(pref -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable ignored) {
                }
                return true;
            });
            getPreference("diag_log").setOnPreferenceClickListener(pref -> {
                showDiagDialog(pref.getContext());
                return true;
            });

            getPreference("about").setSummary(String.format("%s v%s", getString(R.string.app_name), BuildConfig.VERSION_NAME));

            updateAutoSummary();
        }

        @Override
        public void onResume() {
            super.onResume();
            // 접근성 설정에서 돌아왔을 때 상태 반영
            Preference p = getPreference("island_a11y");
            if (p != null)
                p.setSummary(IslandA11yService.isRunning() ? R.string.pref_island_a11y_on : R.string.pref_island_a11y_off);
        }

        private void showDiagDialog(Context ctx) {
            String log = com.dosse.airpods.utils.DiagLog.read(ctx);
            TextView tv = new TextView(ctx);
            tv.setText(log.isEmpty() ? getString(R.string.pref_diag_empty) : log);
            tv.setTextIsSelectable(true);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, 0);
            ScrollView sv = new ScrollView(ctx);
            sv.addView(tv);
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_diag)
                    .setView(sv)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.copy, (d, w) -> {
                        android.content.ClipboardManager cm = ctx.getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", log));
                    })
                    .show();
        }

        // 표시할 항목이 하나도 없는데 자동 표시만 켠 경우 경고
        private void updateAutoSummary() {
            Preference auto = getPreference("auto_on_connect");
            if (auto != null) {
                boolean nothing = !isOn("island_enabled") && !isOn("notif_wear");
                auto.setSummary(nothing
                        ? getString(R.string.warn_nothing_to_show)
                        : getString(R.string.pref_auto_connect_desc) + "\n"
                                + getString(R.string.perm_needs, getString(R.string.perm_name_bt)));
            }
        }

        private boolean isOn(String key) {
            Preference p = getPreference(key);
            return p instanceof SwitchPreference && ((SwitchPreference) p).isChecked();
        }

        // ---------------- 권한 게이트 ----------------

        private static final int PERM_NOTIF = 1;    // 알림
        private static final int PERM_BT = 2;       // 근처 기기 (블루투스)
        private static final int PERM_A11Y = 3;     // 접근성 (아일랜드)

        /**
         * 옵션에 필요한 권한을 요약에 덧붙이고, 권한이 없으면 켤 수 없게 한다.
         * 켜려고 하면 해당 권한 화면으로 보낸다.
         */
        private void gate(String key, int perm) {
            Preference p = getPreference(key);
            if (p == null)
                return;

            CharSequence base = p.getSummary();
            String needs = getString(R.string.perm_needs, getString(permName(perm)));
            p.setSummary(base == null || base.length() == 0 ? needs : base + "\n" + needs);
            // 권한이 없는데 켜져 있으면 실제로는 동작하지 않는다 — 꺼진 상태로 맞춘다.
            // 접근성은 예외: 켜 둔 채로 두고 앱을 열 때마다 접근성을 켜라고 안내한다.
            if (perm != PERM_A11Y && p instanceof SwitchPreference && ((SwitchPreference) p).isChecked()
                    && !hasPerm(p.getContext(), perm))
                ((SwitchPreference) p).setChecked(false);

            p.setOnPreferenceChangeListener((pref, v) -> {
                Context c = pref.getContext();
                if (Boolean.TRUE.equals(v) && !hasPerm(c, perm)) {
                    Toast.makeText(c, getString(R.string.perm_needed, getString(permName(perm))),
                            Toast.LENGTH_LONG).show();
                    openPermissionScreen(c, perm);
                    return false; // 권한 없으면 못 켠다
                }
                if ("auto_on_connect".equals(key))
                    setConnectReceiverEnabled(c, Boolean.TRUE.equals(v));
                // 서비스는 다음에 뜰 때 바뀐 설정을 읽는다 (상주하지 않으므로 재시작할 필요 없음)
                new Handler(Looper.getMainLooper()).post(this::updateAutoSummary); // 저장된 뒤 다시 계산
                return true;
            });
        }

        private static int permName(int perm) {
            switch (perm) {
                case PERM_NOTIF: return R.string.perm_name_notif;
                case PERM_BT: return R.string.perm_name_bt;
                case PERM_A11Y: return R.string.perm_name_a11y;
                default: return R.string.app_name;
            }
        }

        private static boolean hasPerm(Context c, int perm) {
            switch (perm) {
                case PERM_NOTIF:
                    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                            || PermissionUtils.getNotificationPermissions(c);
                case PERM_BT:
                    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                            || PermissionUtils.getBluetoothPermissions(c);
                case PERM_A11Y:
                    return IslandOverlay.hasPermission();
                default:
                    return true;
            }
        }

        // 권한 화면으로 이동 (영구 거부 상태에서도 확실히 켤 수 있도록 앱 정보/접근성 설정으로)
        private void openPermissionScreen(Context c, int perm) {
            Intent i = perm == PERM_A11Y
                    ? new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()));
            try {
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }

        private static void setConnectReceiverEnabled(Context c, boolean on) {
            c.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(c, AirPodsConnectionReceiver.class),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }

        private Preference getPreference(String key) {
            return getPreferenceManager().findPreference(key);
        }
    }
}
