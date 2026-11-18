package com.dosse.airpods.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 다이나믹 아일랜드를 접근성 오버레이 층에 띄우기 위한 접근성 서비스.
 * <p>
 * '다른 앱 위에 표시' 창은 시스템이 잠금 화면 아래에 숨기고 헤즈업 알림 배너보다도 아래에 그린다.
 * 접근성 오버레이는 잠금 화면·알림 창보다 위 층이라 잠금 상태에서도 바로 보이고 배너에 가려지지 않는다.
 * <p>
 * 접근성 이벤트는 하나도 구독하지 않는다(설정 xml 에 이벤트 종류 없음) — 화면 내용을 읽지 않고,
 * 이벤트로 깨어나지도 않는다. 오버레이 창을 붙일 자격만 쓴다.
 */
public class IslandA11yService extends AccessibilityService {
    private static volatile IslandA11yService sInstance;

    /** 켜져 있으면 접근성 오버레이를 붙일 수 있는 컨텍스트, 아니면 null */
    public static IslandA11yService instance() {
        return sInstance;
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        IslandOverlay.hide();
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
