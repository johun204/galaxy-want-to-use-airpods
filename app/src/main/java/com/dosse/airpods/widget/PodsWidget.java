package com.dosse.airpods.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsSnapshot;
import com.dosse.airpods.ui.MainActivity;
import com.dosse.airpods.utils.BatteryRing;

/**
 * 홈 화면 위젯 (가로 1줄). 본체(케이스)와 좌/우 유닛 일러스트 + 배터리 퍼센트.
 * 이미지는 {@link PodsSnapshot} 이 모델별로 확정한 리소스를 그대로 써서 알림/메인 화면과 일치한다.
 * PodsService가 상태 변화 때마다 updateAll()을 호출해 갱신한다.
 * 2x2 버전은 {@link PodsWidget2x2} — 레이아웃만 다르고 view id 는 동일하다.
 */
public class PodsWidget extends AppWidgetProvider {

    protected int layout() {
        return R.layout.widget_pods;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        RemoteViews views = buildViews(context, layout());
        for (int id : appWidgetIds)
            manager.updateAppWidget(id, views);
    }

    /** 모든 종류의 위젯을 갱신. */
    public static void updateAll(Context context) {
        push(context, PodsWidget.class, R.layout.widget_pods);
        push(context, PodsWidget2x2.class, R.layout.widget_pods_2x2);
        push(context, PodsWidget4x1.class, R.layout.widget_pods_4x1);
    }

    private static void push(Context context, Class<?> provider, int layoutRes) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
        if (ids.length == 0)
            return;
        RemoteViews views = buildViews(context, layoutRes);
        for (int id : ids)
            manager.updateAppWidget(id, views);
    }

    static RemoteViews buildViews(Context context, int layoutRes) {
        PodsSnapshot s = PodsSnapshot.last;
        RemoteViews v = new RemoteViews(context.getPackageName(), layoutRes);

        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));

        // 링 비트맵이 충전 표시까지 포함하므로 별도 오버레이는 항상 숨김
        v.setViewVisibility(R.id.w_left_chg, View.GONE);
        v.setViewVisibility(R.id.w_right_chg, View.GONE);
        v.setViewVisibility(R.id.w_case_chg, View.GONE);

        v.setTextViewText(R.id.w_updated, lastUpdatedText(context, s.updatedAt));

        int px = Math.round(46 * context.getResources().getDisplayMetrics().density);

        if (!s.available) {
            v.setViewVisibility(R.id.w_left_col, View.VISIBLE);
            v.setViewVisibility(R.id.w_right_col, View.VISIBLE);
            v.setImageViewBitmap(R.id.w_left_img, BatteryRing.render(context, R.drawable.pod_left_disconnected, -1, false, px));
            v.setImageViewBitmap(R.id.w_right_img, BatteryRing.render(context, R.drawable.pod_right_disconnected, -1, false, px));
            v.setImageViewBitmap(R.id.w_case_img, BatteryRing.render(context, R.drawable.pod_case_disconnected, -1, false, px));
            v.setTextViewText(R.id.w_left_text, "—");
            v.setTextViewText(R.id.w_right_text, "—");
            v.setTextViewText(R.id.w_case_text, "—");
            return v;
        }

        v.setViewVisibility(R.id.w_left_col, s.single ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.w_right_col, s.single ? View.GONE : View.VISIBLE);

        if (!s.single) {
            v.setImageViewBitmap(R.id.w_left_img, BatteryRing.render(context, s.leftImg, s.leftPct, s.leftChg, px));
            v.setImageViewBitmap(R.id.w_right_img, BatteryRing.render(context, s.rightImg, s.rightPct, s.rightChg, px));
            v.setTextViewText(R.id.w_left_text, orDash(s.left));
            v.setTextViewText(R.id.w_right_text, orDash(s.right));
        }

        v.setImageViewBitmap(R.id.w_case_img, BatteryRing.render(context, s.caseImg, s.casePct, s.caseChg, px));
        v.setTextViewText(R.id.w_case_text, orDash(s.caseB));
        return v;
    }

    private static String orDash(String s) {
        return (s == null || s.isEmpty()) ? "—" : s;
    }

    /** "14:05" 형식. 오늘이 아니면 날짜도. 0이면 빈 문자열. */
    public static String lastUpdatedText(Context c, long updatedAt) {
        if (updatedAt <= 0)
            return "";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.setTimeInMillis(updatedAt);
        CharSequence time = android.text.format.DateFormat.getTimeFormat(c).format(t.getTime());
        if (now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR))
            return time.toString();
        return (t.get(java.util.Calendar.MONTH) + 1) + "/" + t.get(java.util.Calendar.DAY_OF_MONTH) + " " + time;
    }
}
