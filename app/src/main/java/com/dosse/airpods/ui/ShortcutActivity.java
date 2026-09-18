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
        // 루틴이 바로가기를 실행하면 시스템이 이 액티비티의 태스크를 앞으로 가져온다.
        // 우리 앱 화면을 보고 있던 중이었다면 그 화면을 다시 앞으로 돌려놓는다.
        if (MainActivity.sForeground) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (Throwable t) {
                Logger.error(t);
            }
        } else {
            // 아니면 이 태스크를 뒤로 보내 홈으로 튀는 걸 줄인다
            try {
                moveTaskToBack(true);
            } catch (Throwable ignored) {
            }
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }
}
