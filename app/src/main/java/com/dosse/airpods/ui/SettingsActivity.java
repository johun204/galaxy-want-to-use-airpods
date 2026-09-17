package com.dosse.airpods.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.dosse.airpods.BuildConfig;
import com.dosse.airpods.R;
import com.dosse.airpods.receivers.StartupReceiver;

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

            // 아일랜드를 켰는데 오버레이 권한이 없으면 설정 화면으로 보낸다
            Preference island = getPreference("island_enabled");
            if (island != null)
                island.setOnPreferenceChangeListener((pref, v) -> {
                    if (Boolean.TRUE.equals(v) && !IslandOverlay.hasPermission(pref.getContext()))
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + pref.getContext().getPackageName())));
                    StartupReceiver.restartPodsService(pref.getContext());
                    return true;
                });

            for (String key : new String[]{"scan_saver", "fast_scan", "aacp_enabled", "aacp_battery",
                    "notif_persistent", "on_demand"}) {
                Preference p = getPreference(key);
                if (p != null)
                    p.setOnPreferenceChangeListener((pref, v) -> {
                        StartupReceiver.restartPodsService(pref.getContext());
                        return true;
                    });
            }

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
