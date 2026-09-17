package com.dosse.airpods.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsSnapshot;
import com.dosse.airpods.utils.BatteryRing;
import com.dosse.airpods.utils.Logger;

/**
 * 화면 상단에 잠깐 떴다 사라지는 "다이나믹 아일랜드".
 * 오버레이 창을 그때그때 붙였다 떼기만 하므로 상주하는 것이 없다.
 */
public final class IslandOverlay {
    /** 자동으로 사라지기까지의 시간 */
    public static final long SHOW_MS = 5000;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static View sView;

    private IslandOverlay() {
    }

    public static boolean hasPermission(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static void show(Context ctx, PodsSnapshot s) {
        if (!hasPermission(ctx) || !s.available)
            return;
        hide(ctx);
        try {
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            View v = LayoutInflater.from(ctx).inflate(R.layout.island, null);
            bind(ctx, v, s);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(8 * ctx.getResources().getDisplayMetrics().density);

            v.setAlpha(0f);
            v.setOnClickListener(x -> {
                hide(ctx);
                ctx.startActivity(new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });

            wm.addView(v, lp);
            v.animate().alpha(1f).setDuration(180).start();
            sView = v;
            H.postDelayed(() -> hide(ctx), SHOW_MS);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    public static void hide(Context ctx) {
        View v = sView;
        sView = null;
        if (v == null)
            return;
        try {
            ctx.getSystemService(WindowManager.class).removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private static void bind(Context ctx, View v, PodsSnapshot s) {
        int px = Math.round(30 * ctx.getResources().getDisplayMetrics().density);
        v.findViewById(R.id.i_left).setVisibility(s.single ? View.GONE : View.VISIBLE);
        v.findViewById(R.id.i_right).setVisibility(s.single ? View.GONE : View.VISIBLE);
        if (!s.single) {
            ((ImageView) v.findViewById(R.id.i_left_img))
                    .setImageBitmap(BatteryRing.render(ctx, s.leftImg, s.leftPct, s.leftChg, px));
            ((ImageView) v.findViewById(R.id.i_right_img))
                    .setImageBitmap(BatteryRing.render(ctx, s.rightImg, s.rightPct, s.rightChg, px));
            ((TextView) v.findViewById(R.id.i_left_text)).setText(dash(s.left));
            ((TextView) v.findViewById(R.id.i_right_text)).setText(dash(s.right));
        }
        ((ImageView) v.findViewById(R.id.i_case_img))
                .setImageBitmap(BatteryRing.render(ctx, s.caseImg, s.casePct, s.caseChg, px));
        ((TextView) v.findViewById(R.id.i_case_text)).setText(dash(s.caseB));
    }

    private static String dash(String s) {
        return (s == null || s.isEmpty()) ? "—" : s;
    }
}
