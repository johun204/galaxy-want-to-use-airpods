package com.dosse.airpods.ui;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.utils.Logger;

/**
 * 빠른 설정 타일: 누르면 배터리 다이나믹 아일랜드를 띄운다.
 * 바로가기와 같은 경로(ShortcutActivity)를 타므로 백그라운드 실행 제한에 걸리지 않는다.
 */
public class PodsTileService extends TileService {
    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, ShortcutActivity.class)
                .setAction(PodsService.ACTION_SHOW_ISLAND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_IMMUTABLE));
            else
                startActivityAndCollapse(i);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }
}
