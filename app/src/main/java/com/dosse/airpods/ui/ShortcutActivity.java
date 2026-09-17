package com.dosse.airpods.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.utils.Logger;

/**
 * 홈 화면 아이콘 꾹 누르기(앱 바로가기) / 갤럭시 루틴이 실행하는 진입점.
 * 화면 없이 서비스에 "지금 띄워라"만 전달하고 바로 끝난다.
 */
public class ShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent() != null ? getIntent().getAction() : null;
        if (PodsService.ACTION_SHOW_ISLAND.equals(action) || PodsService.ACTION_SHOW_ALERT.equals(action)) {
            try {
                ContextCompat.startForegroundService(this,
                        new Intent(this, PodsService.class).setAction(action));
            } catch (Throwable t) {
                Logger.error(t);
            }
        }
        finish();
    }
}
