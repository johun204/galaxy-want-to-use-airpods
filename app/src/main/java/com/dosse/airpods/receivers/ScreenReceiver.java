package com.dosse.airpods.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.Objects;

public abstract class ScreenReceiver extends BroadcastReceiver {
    public abstract void onStart();      // 화면 켜짐

    public abstract void onStop();       // 화면 꺼짐

    public abstract void onUnlock();     // 잠금 해제 (USER_PRESENT)

    public static IntentFilter buildFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        return f;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        switch (Objects.requireNonNull(intent.getAction())) {
            case Intent.ACTION_SCREEN_OFF:
                onStop();
                break;
            case Intent.ACTION_SCREEN_ON:
                onStart();
                break;
            case Intent.ACTION_USER_PRESENT:
                onUnlock();
                break;
        }
    }
}
