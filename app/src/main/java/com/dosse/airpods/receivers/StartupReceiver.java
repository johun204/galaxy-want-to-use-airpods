package com.dosse.airpods.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.dosse.airpods.pods.PodsService;

import java.util.Objects;

/**
 * A simple startup class that starts the service when the device is booted, or after an update
 */
public class StartupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (Objects.requireNonNull(intent.getAction())) {
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_BOOT_COMPLETED:
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
