package com.dosse.airpods.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.utils.SharedPreferencesUtils;

import java.util.Objects;

/**
 * A simple startup class that starts the service when the device is booted, or after an update
 */
public class StartupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (Objects.requireNonNull(intent.getAction())) {
            case Intent.ACTION_BOOT_COMPLETED:
                // 온디맨드 모드에선 부팅 시 띄울 이유가 없다 — 에어팟이 연결되면 리시버가 깨운다
                if (SharedPreferencesUtils.isOnDemandEnabled(context))
                    break;
                startPodsService(context);
                break;
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                startPodsService(context);
                break;
        }
    }

    public static void startPodsService(Context context) {
        context.startForegroundService(new Intent(context, PodsService.class));
    }

    public static void restartPodsService(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, PodsService.class));
        new Handler(Looper.getMainLooper()).postDelayed(() -> startPodsService(app), 600);
    }
}
