package com.dosse.airpods.notification;

import android.app.Notification;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsSnapshot;
import com.dosse.airpods.utils.BatteryRing;

public class NotificationBuilder {
    public static final String TAG = "AirPods";
    public static final long TIMEOUT_CONNECTED = 30000;
    public static final int NOTIFICATION_ID = 1;

    private final Context mContext;
    private final int mRingPx;
    private final RemoteViews[] mRemoteViews;
    private final NotificationCompat.Builder mBuilder;

    public NotificationBuilder(Context context) {
        mContext = context.getApplicationContext();
        mRingPx = Math.round(60 * context.getResources().getDisplayMetrics().density);
        mRemoteViews = new RemoteViews[] {
                new RemoteViews(context.getPackageName(), R.layout.status_big),
                new RemoteViews(context.getPackageName(), R.layout.status_small)
        };

        mBuilder = new NotificationCompat.Builder(context, TAG);
        mBuilder.setShowWhen(false);
        mBuilder.setOngoing(true);
        mBuilder.setSmallIcon(R.drawable.ic_notification);
        mBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        mBuilder.setCustomContentView(mRemoteViews[1]);
        mBuilder.setCustomBigContentView(mRemoteViews[0]);
        mBuilder.setStyle(new NotificationCompat.DecoratedCustomViewStyle()); //Make the notification extendable issue #165
        mBuilder.setCategory(NotificationCompat.CATEGORY_SERVICE); //show notification on android 12 and above #144 #143
    }

    // 듀티 사이클 스캔이라 "오래된 값"이 정상 — 항상 마지막 스냅샷을 그대로 보여준다.
    // 배터리 출처(BLE 비콘 / AACP 제어 채널)는 PodsSnapshot 이 이미 확정해 둔다.
    public Notification build(PodsSnapshot s) {
        for (RemoteViews notification : mRemoteViews) {
            if (!s.single) {
                notification.setImageViewBitmap(R.id.leftPodImg, ring(s.leftImg, s.leftPct, s.leftChg));
                notification.setImageViewBitmap(R.id.rightPodImg, ring(s.rightImg, s.rightPct, s.rightChg));
                notification.setTextViewText(R.id.leftPodText, dash(s.left));
                notification.setTextViewText(R.id.rightPodText, dash(s.right));
                notification.setViewVisibility(R.id.leftInEarImg, s.leftInEar ? View.VISIBLE : View.GONE);
                notification.setViewVisibility(R.id.rightInEarImg, s.rightInEar ? View.VISIBLE : View.GONE);
            }
            notification.setImageViewBitmap(R.id.podCaseImg, ring(s.caseImg, s.casePct, s.caseChg));
            notification.setTextViewText(R.id.podCaseText, dash(s.caseB));

            notification.setViewVisibility(R.id.leftPod, s.single ? View.GONE : View.VISIBLE);
            notification.setViewVisibility(R.id.rightPod, s.single ? View.GONE : View.VISIBLE);
            notification.setViewVisibility(R.id.leftPodText, View.VISIBLE);
            notification.setViewVisibility(R.id.rightPodText, View.VISIBLE);
            notification.setViewVisibility(R.id.podCaseText, View.VISIBLE);
            notification.setViewVisibility(R.id.leftPodUpdating, View.INVISIBLE);
            notification.setViewVisibility(R.id.rightPodUpdating, View.INVISIBLE);
            notification.setViewVisibility(R.id.podCaseUpdating, View.INVISIBLE);
            // 링이 충전/저전량 표시를 포함하므로 기존 배터리 아이콘은 숨김
            notification.setViewVisibility(R.id.leftBatImg, View.GONE);
            notification.setViewVisibility(R.id.rightBatImg, View.GONE);
            notification.setViewVisibility(R.id.caseBatImg, View.GONE);
        }

        return mBuilder.build();
    }

    private android.graphics.Bitmap ring(int illustrationRes, int pct, boolean charging) {
        return BatteryRing.render(mContext, illustrationRes, pct, charging && pct >= 0, mRingPx);
    }

    private static String dash(String s) {
        return (s == null || s.isEmpty()) ? "—" : s;
    }
}
