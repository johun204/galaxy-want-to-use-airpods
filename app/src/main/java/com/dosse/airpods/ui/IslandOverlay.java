package com.dosse.airpods.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsSnapshot;
import com.dosse.airpods.utils.BatteryRing;
import com.dosse.airpods.utils.Logger;
import com.dosse.airpods.utils.SharedPreferencesUtils;

/**
 * 화면 상단에 잠깐 떴다 사라지는 "배터리 다이나믹 아일랜드".
 * 오버레이 창을 그때그때 붙였다 떼기만 하므로 상주하는 것이 없다.
 * 펀치홀 카메라가 알약의 검은 여백에 들어가도록 위쪽 여백을 크게 잡고, 카메라 위치(상단 중앙)에서
 * 커지고 다시 그쪽으로 모이며 사라진다.
 */
public final class IslandOverlay {
    private static final long IN_MS = 170;
    private static final long OUT_MS = 130;
    private static final float START_SCALE = 0.25f;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = IslandOverlay::hide;

    private static View sView;
    private static Context sCtx;

    private IslandOverlay() {
    }

    public static boolean hasPermission(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** 화면에 떠 있는 시간 (설정값) */
    public static long showMs(Context c) {
        return SharedPreferencesUtils.islandSeconds(c) * 1000L;
    }

    public static void show(Context ctx, PodsSnapshot s) {
        Context app = ctx.getApplicationContext();
        if (!hasPermission(app) || !s.available)
            return;
        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive())
            return; // 화면이 꺼져 있으면 띄워도 못 본다
        H.removeCallbacks(HIDE); // 이전 표시의 자동 숨김 타이머가 새 아일랜드를 지우지 않게
        remove(sView);
        sView = null;
        try {
            View v = LayoutInflater.from(app).inflate(R.layout.island, null);
            bind(app, v, s);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            // 상태바 영역까지 올라가도록 (펀치홀 카메라와 겹침).
                            // NO_LIMITS 는 안 씀 — 좁은 화면에서 알약이 화면 밖으로 나가지 않게
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(6 * app.getResources().getDisplayMetrics().density); // 화면 맨 위에서 살짝 띄움
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                lp.setFitInsetsTypes(0); // 시스템 바 인셋만큼 밀려나지 않게
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                lp.layoutInDisplayCutoutMode = // 펀치홀 카메라 영역까지 그리기
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

            v.setAlpha(0f);
            v.setOnClickListener(x -> {
                hide();
                app.startActivity(new Intent(app, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });

            app.getSystemService(WindowManager.class).addView(v, lp);
            sView = v;
            sCtx = app;

            // 레이아웃이 끝나야 크기를 알 수 있다 → 그때 상단 중앙(펀치홀)을 기준으로 커진다
            v.post(() -> {
                v.setPivotX(v.getWidth() / 2f);
                v.setPivotY(0f);
                v.setScaleX(START_SCALE);
                v.setScaleY(START_SCALE);
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(IN_MS).setInterpolator(new DecelerateInterpolator()).start();
            });
            H.postDelayed(HIDE, showMs(app) + IN_MS);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    /** 상단 중앙으로 모이며 사라진다. */
    public static void hide() {
        View v = sView;
        if (v == null)
            return;
        sView = null;
        H.removeCallbacks(HIDE);
        v.animate().alpha(0f).scaleX(START_SCALE).scaleY(START_SCALE)
                .setDuration(OUT_MS).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> remove(v)).start();
    }

    private static void remove(View v) {
        if (v == null || sCtx == null)
            return;
        try {
            sCtx.getSystemService(WindowManager.class).removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private static void bind(Context ctx, View v, PodsSnapshot s) {
        int px = Math.round(40 * ctx.getResources().getDisplayMetrics().density);
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
