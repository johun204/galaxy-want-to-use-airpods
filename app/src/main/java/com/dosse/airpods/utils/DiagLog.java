package com.dosse.airpods.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 아일랜드/알림 표시 과정의 짧은 기록 (최근 40줄). 릴리스 빌드는 logcat 을 남기지 않으므로
 * "왜 안 떴는지"를 설정 화면에서 바로 확인하기 위한 용도. 같은 줄이 연달아 오면 한 번만 남긴다.
 */
public final class DiagLog {
    private static final String PREFS = "diag";
    private static final int MAX_LINES = 40;
    private static String sLast = null;

    private DiagLog() {
    }

    public static synchronized void add(Context c, String msg) {
        if (msg.equals(sLast))
            return;
        sLast = msg;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg;
        String old = p.getString("log", "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - (MAX_LINES - 1)); i < lines.length; i++)
            sb.append(lines[i]).append('\n');
        sb.append(line);
        p.edit().putString("log", sb.toString()).apply();
        Logger.debug("diag: " + msg);
    }

    public static String read(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "");
    }
}
