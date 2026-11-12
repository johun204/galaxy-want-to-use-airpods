package com.dosse.airpods;

import android.app.Application;

import com.dosse.airpods.pods.PodsSnapshot;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 프로세스가 새로 떴다 — 위젯/앱이 빈 값을 그리기 전에 마지막 배터리 값을 되살린다
        PodsSnapshot.restore(this);
    }
}
