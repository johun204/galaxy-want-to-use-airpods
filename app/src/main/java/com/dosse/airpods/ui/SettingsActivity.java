package com.dosse.airpods.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.dosse.airpods.BuildConfig;
import com.dosse.airpods.R;
import com.dosse.airpods.receivers.AirPodsConnectionReceiver;
import com.dosse.airpods.receivers.StartupReceiver;
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

        private static final String[] REFRESH_VALUES = {"5", "15", "30", "60", "180", "300"};

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preference_screen, rootKey);

            // 기능별 필요 권한을 요약에 적고, 권한이 없으면 켜지 못하게 막는다
            gate("notif_persistent", PERM_NOTIF);
            gate("notif_wear", PERM_NOTIF);
            gate("island_enabled", PERM_OVERLAY);
            gate("auto_on_connect", PERM_BT);
            gate("scan_saver", PERM_BT);
            gate("fast_scan", PERM_BT);
            gate("aacp_enabled", PERM_BT);
            gate("aacp_battery", PERM_BT);
            gate("on_demand", PERM_NONE);

            Preference refresh = getPreference("battery_refresh");
            updateRefreshSummary(refresh);
            refresh.setOnPreferenceClickListener(pref -> {
                showRefreshDialog(pref);
                return true;
            });

            getPreference("restartService").setOnPreferenceClickListener(preference -> {
                StartupReceiver.restartPodsService(requireContext());
                return true;
            });

            getPreference("about").setSummary(String.format("%s v%s", getString(R.string.app_name), BuildConfig.VERSION_NAME));
        }

        // ---------------- 권한 게이트 ----------------

        private static final int PERM_NONE = 0;
        private static final int PERM_NOTIF = 1;    // 알림
        private static final int PERM_BT = 2;       // 근처 기기 (블루투스)
        private static final int PERM_OVERLAY = 3;  // 다른 앱 위에 표시

        /**
         * 옵션에 필요한 권한을 요약에 덧붙이고, 권한이 없으면 켤 수 없게 한다.
         * 켜려고 하면 해당 권한 화면으로 보낸다.
         */
        private void gate(String key, int perm) {
            Preference p = getPreference(key);
            if (p == null)
                return;

            if (perm != PERM_NONE) {
                CharSequence base = p.getSummary();
                String needs = getString(R.string.perm_needs, getString(permName(perm)));
                p.setSummary(base == null || base.length() == 0 ? needs : base + "\n" + needs);
                // 권한이 없는데 켜져 있으면 실제로는 동작하지 않는다 — 꺼진 상태로 맞춘다
                if (p instanceof SwitchPreference && ((SwitchPreference) p).isChecked()
                        && !hasPerm(p.getContext(), perm))
                    ((SwitchPreference) p).setChecked(false);
            }

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
                StartupReceiver.restartPodsService(c);
                return true;
            });
        }

        private static int permName(int perm) {
            switch (perm) {
                case PERM_NOTIF: return R.string.perm_name_notif;
                case PERM_BT: return R.string.perm_name_bt;
                case PERM_OVERLAY: return R.string.perm_name_overlay;
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
                case PERM_OVERLAY:
                    return IslandOverlay.hasPermission(c);
                default:
                    return true;
            }
        }

        // 권한 화면으로 이동 (영구 거부 상태에서도 확실히 켤 수 있도록 앱 정보/오버레이 설정으로)
        private void openPermissionScreen(Context c, int perm) {
            Intent i = perm == PERM_OVERLAY
                    ? new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + c.getPackageName()))
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

        // 프레임워크 ListPreference 대신 직접 다이얼로그: 설명 + 라디오 선택을 함께 표시
        private void showRefreshDialog(Preference pref) {
            Context ctx = pref.getContext();
            String[] entries = getResources().getStringArray(R.array.battery_refresh_entries);
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
            String current = sp.getString("battery_refresh", "180");
            float d = getResources().getDisplayMetrics().density;

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * d);
            root.setPadding(pad, pad, pad, 0);

            TextView msg = new TextView(ctx);
            msg.setText(R.string.pref_battery_refresh_dialog);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            root.addView(msg);

            RadioGroup group = new RadioGroup(ctx);
            group.setPadding(0, (int) (14 * d), 0, 0);
            for (int i = 0; i < entries.length; i++) {
                RadioButton rb = new RadioButton(ctx);
                rb.setId(i + 1);
                rb.setText(entries[i]);
                rb.setChecked(REFRESH_VALUES[i].equals(current));
                group.addView(rb);
            }
            root.addView(group);

            ScrollView sv = new ScrollView(ctx);
            sv.addView(root);

            AlertDialog dlg = new AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_battery_refresh)
                    .setView(sv)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();

            group.setOnCheckedChangeListener((g, checkedId) -> {
                int idx = checkedId - 1;
                if (idx < 0 || idx >= REFRESH_VALUES.length)
                    return;
                sp.edit().putString("battery_refresh", REFRESH_VALUES[idx]).apply();
                updateRefreshSummary(pref);
                StartupReceiver.restartPodsService(ctx);
                dlg.dismiss();
            });

            dlg.show();
        }

        private void updateRefreshSummary(Preference pref) {
            String[] entries = getResources().getStringArray(R.array.battery_refresh_entries);
            String v = PreferenceManager.getDefaultSharedPreferences(pref.getContext())
                    .getString("battery_refresh", "180");
            for (int i = 0; i < REFRESH_VALUES.length; i++)
                if (REFRESH_VALUES[i].equals(v))
                    pref.setSummary(entries[i]);
        }

        private Preference getPreference(String key) {
            return getPreferenceManager().findPreference(key);
        }
    }
}
