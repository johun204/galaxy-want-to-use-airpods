package com.dosse.airpods.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

/**
 * 유닛 일러스트 + 배터리만큼 채워지는 초록 원형 링을 한 장의 비트맵으로 그린다.
 * RemoteViews(위젯/알림)에서도 setImageViewBitmap 으로 쓸 수 있다.
 */
public final class BatteryRing {
    private static final int TRACK = 0xFFE6E6EB;
    private static final int GREEN = 0xFF34C759;
    private static final int LOW = 0xFFFF3B30;

    private BatteryRing() {
    }

    /**
     * @param percent 0..100, 또는 -1(모름)
     */
    public static Bitmap render(Context ctx, int illustrationRes, int percent, boolean charging, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        float stroke = sizePx * 0.085f;
        float pad = stroke / 2f + sizePx * 0.02f;
        RectF arc = new RectF(pad, pad, sizePx - pad, sizePx - pad);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);

        // 트랙
        p.setColor(TRACK);
        c.drawArc(arc, 0, 360, false, p);

        // 채워진 부분
        if (percent >= 0) {
            p.setColor(percent <= 10 && !charging ? LOW : GREEN);
            c.drawArc(arc, -90, Math.max(0, Math.min(100, percent)) * 3.6f, false, p);
        }

        // 일러스트 (링 안쪽에 배치)
        Drawable d = ContextCompat.getDrawable(ctx, illustrationRes);
        if (d != null) {
            int inset = (int) (sizePx * 0.17f);
            d.setBounds(inset, inset, sizePx - inset, sizePx - inset);
            d.draw(c);
        }

        // 충전 표시: 하단에 작은 번개
        if (charging) {
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(GREEN);
            float u = sizePx * 0.06f, cx = sizePx / 2f, cy = sizePx - stroke * 1.15f;
            Path bolt = new Path();
            bolt.moveTo(cx + u * 0.3f, cy - u);
            bolt.lineTo(cx - u * 0.9f, cy + u * 0.2f);
            bolt.lineTo(cx - u * 0.1f, cy + u * 0.2f);
            bolt.lineTo(cx - u * 0.3f, cy + u);
            bolt.lineTo(cx + u * 0.9f, cy - u * 0.2f);
            bolt.lineTo(cx + u * 0.1f, cy - u * 0.2f);
            bolt.close();
            Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setColor(Color.WHITE);
            c.drawCircle(cx, cy, u * 1.5f, halo);
            c.drawPath(bolt, bp);
        }
        return bmp;
    }
}
