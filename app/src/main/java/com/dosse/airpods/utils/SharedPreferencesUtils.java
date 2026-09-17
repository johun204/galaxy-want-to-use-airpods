package com.dosse.airpods.utils;

import android.content.Context;
import androidx.preference.PreferenceManager;

public class SharedPreferencesUtils {

    private static boolean b(Context c, String key, boolean def) {
        return PreferenceManager.getDefaultSharedPreferences(c).getBoolean(key, def);
    }

    // 알림에 배터리 상세 표시 (기본 켜짐)
    public static boolean isPersistentNotificationEnabled(Context c) {
        return b(c, "notif_persistent", true);
    }

    // 연결 시 배터리 알림 1회 (기본 켜짐)
    public static boolean isWearAlertEnabled(Context c) {
        return b(c, "notif_wear", true);
    }

    // 알림 소리 (기본 켜짐)
    public static boolean isWearSoundEnabled(Context c) {
        return b(c, "wear_sound", true);
    }

    // 알림 진동 (기본 켜짐)
    public static boolean isWearVibrateEnabled(Context c) {
        return b(c, "wear_vibrate", true);
    }

    // 연결/바로가기 시 상단 다이나믹 아일랜드 표시 (기본 켜짐, 다른 앱 위에 표시 권한 필요)
    public static boolean isIslandEnabled(Context c) {
        return b(c, "island_enabled", true);
    }

    // 온디맨드 모드: 표시할 걸 띄운 뒤 서비스를 종료해 백그라운드 상주를 없앰 (기본 켜짐)
    public static boolean isOnDemandEnabled(Context c) {
        return b(c, "on_demand", true);
    }

    // 화면 꺼짐/잠금 시 스캔 절전 — 배터리 갱신 주기보다 우선 (기본 켜짐)
    public static boolean isScanSaverEnabled(Context c) {
        return b(c, "scan_saver", true);
    }

    // 연결 직후 고속 스캔 (기본 켜짐)
    public static boolean isFastScanOnConnect(Context c) {
        return b(c, "fast_scan", true);
    }

    // 하드웨어 제어 채널(AACP/L2CAP) 사용 (기본 켜짐)
    public static boolean isAacpEnabled(Context c) {
        return b(c, "aacp_enabled", true);
    }

    // 제어 채널로 배터리 직접 수신 (기본 켜짐, 채널 없으면 자동으로 비콘 폴백)
    public static boolean isAacpBatteryEnabled(Context c) {
        return b(c, "aacp_battery", true);
    }

    // 배터리 갱신 주기(초). 기본 180초(3분). 5/15/30/60/180/300 중 하나.
    public static int batteryRefreshSeconds(Context c) {
        String v = PreferenceManager.getDefaultSharedPreferences(c).getString("battery_refresh", "180");
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 180;
        }
    }
}
